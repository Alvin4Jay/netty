/*
 * Copyright 2012 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package io.netty.buffer;

/**
 * Description of algorithm for PageRun/PoolSubpage allocation from PoolChunk
 *
 * Notation: The following terms are important to understand the code
 * > page  - a page is the smallest unit of memory chunk that can be allocated
 * > chunk - a chunk is a collection of pages
 * > in this code chunkSize = 2^{maxOrder} * pageSize ==> 16MB = 2^11 * 8kB
 *
 * To begin we allocate a byte array of size = chunkSize
 * Whenever a ByteBuf of given size needs to be created we search for the first position
 * in the byte array that has enough empty space to accommodate the requested size and
 * return a (long) handle that encodes this offset information, (this memory segment is then
 * marked as reserved so it is always used by exactly one ByteBuf and no more)
 *
 * For simplicity all sizes are normalized according to {@link PoolArena#normalizeCapacity} method
 * This ensures that when we request for memory segments of size >= pageSize the normalizedCapacity
 * equals the next nearest power of 2     ==> normalizedCapacity >= size
 *
 * To search for the first offset in chunk that has at least requested size available we construct a
 * complete balanced binary tree(完全平衡二叉树) and store it in an array (just like heaps(二叉堆)) - memoryMap
 *
 * The tree looks like this (the size of each node being mentioned in the parenthesis(圆括号))
 *
 * depth=0        1 node (chunkSize)
 * depth=1        2 nodes (chunkSize/2)
 * ..
 * ..
 * depth=d        2^d nodes (chunkSize/2^d)
 * ..
 * depth=maxOrder 2^maxOrder nodes (chunkSize/2^{maxOrder} = pageSize)  ==> pageSize: 8kB，maxOrder: 11
 *
 * depth=maxOrder is the last level and the leafs consist of pages
 *
 * With this tree available searching in chunkArray translates like this:
 * To allocate a memory segment of size chunkSize/2^k we search for the first node (from left) at height k
 * which is unused
 *
 * Algorithm(算法):
 * ----------
 * Encode the tree in memoryMap with the notation(标记)
 *   memoryMap[id] = x => in the subtree rooted at id, the first node that is free to be allocated
 *   is at depth x (counted from depth=0) i.e., at depths [depth_of_id, x), there is no node that is free
 *
 *  As we allocate & free nodes, we update values stored in memoryMap so that the property is maintained
 *
 * Initialization(初始化) -
 *   In the beginning we construct the memoryMap array by storing the depth of a node at each node
 *     i.e., memoryMap[id] = depth_of_id
 *
 * Observations(规则):
 * -------------
 * 1) memoryMap[id] = depth_of_id  => it is free / unallocated 表明未分配
 * 2) memoryMap[id] > depth_of_id  => at least one of its child nodes is allocated(子节点被分配), so we cannot allocate it, but
 *                                    some of its children can still be allocated based on their availability
 * 3) memoryMap[id] = maxOrder(11) + 1 => the node is fully allocated & thus none of its children can be allocated, it
 *                                    is thus marked as unusable 表明节点已被完全分配。子节点无法再被分配，被标识为不能用
 *
 * Algorithm: [allocateNode(d) => we want to find the first node (from left) at height h that can be allocated]
 * ----------
 * 1) start at root (i.e., depth = 0 or id = 1)
 * 2) if memoryMap[1] > d => cannot be allocated from this chunk 定位到d深度，但是其子节点已分配，所以该节点无法再分配
 * 3) if left node value <= h; we can allocate from left subtree so move to left and repeat until found
 * 4) else try in right subtree
 *
 * Algorithm: [allocateRun(size)]
 * ----------
 * 1) Compute d = log_2(chunkSize/size)
 * 2) Return allocateNode(d)
 *
 * Algorithm: [allocateSubpage(size)]
 * ----------
 * 1) use allocateNode(maxOrder) to find an empty (i.e., unused) leaf (i.e., page)
 * 2) use this handle to construct the PoolSubpage object or if it already exists just call init(normCapacity)
 *    note that this PoolSubpage object is added to subpagesPool in the PoolArena when we init() it
 *
 * Note:
 * -----
 * In the implementation for improving cache coherence,
 * we store 2 pieces of information (i.e, 2 byte vals) as a short value in memoryMap
 *
 * memoryMap[id]= (depth_of_id, x)
 * where as per convention defined above
 * the second value (i.e, x) indicates that the first node which is free to be allocated is at depth x (from root)
 */
final class PoolChunk<T> implements PoolChunkMetric {

    private static final int INTEGER_SIZE_MINUS_ONE = Integer.SIZE - 1;

    final PoolArena<T> arena;
    final T memory; // DirectByteBuffer/byte[]
    final boolean unpooled;

    private final byte[] memoryMap;
    private final byte[] depthMap;
    private final PoolSubpage<T>[] subpages;
    /** Used to determine if the requested capacity is equal to or greater than pageSize. */
    private final int subpageOverflowMask;
    private final int pageSize;
    private final int pageShifts;
    private final int maxOrder;
    private final int chunkSize;
    private final int log2ChunkSize;
    private final int maxSubpageAllocs;
    /** Used to mark memory as unusable */
    private final byte unusable;

    private int freeBytes; // 可用字节数

    PoolChunkList<T> parent; // 所属的PoolChunkList
    PoolChunk<T> prev; // PoolChunk双向链表的前后指针
    PoolChunk<T> next;

    // TODO: Test if adding padding helps under contention
    //private long pad0, pad1, pad2, pad3, pad4, pad5, pad6, pad7;

    PoolChunk(PoolArena<T> arena, T memory, int pageSize, int maxOrder, int pageShifts, int chunkSize) {
        unpooled = false;
        this.arena = arena;
        this.memory = memory; // DirectByteBuffer/byte[]
        this.pageSize = pageSize; // 8kB
        this.pageShifts = pageShifts; // 13
        this.maxOrder = maxOrder; // 11
        this.chunkSize = chunkSize; // 16MB
        unusable = (byte) (maxOrder + 1); // 12表示不可用
        log2ChunkSize = log2(chunkSize); // 24
        subpageOverflowMask = ~(pageSize - 1); // -pageSize: 8192, 8kB
        freeBytes = chunkSize; // 16MB

        assert maxOrder < 30 : "maxOrder should be < 30, but is: " + maxOrder;
        maxSubpageAllocs = 1 << maxOrder; // maxOrder: 11

        // Generate the memory map. (重点)
        memoryMap = new byte[maxSubpageAllocs << 1]; // maxSubpageAllocs: 2048, memoryMap.length: 4096
        depthMap = new byte[memoryMap.length];
        int memoryMapIndex = 1;
        for (int d = 0; d <= maxOrder; ++ d) { // move down the tree one level at a time
            int depth = 1 << d;
            for (int p = 0; p < depth; ++ p) {
                // in each level traverse left to right and set value to the depth of subtree
                memoryMap[memoryMapIndex] = (byte) d;
                depthMap[memoryMapIndex] = (byte) d;
                memoryMapIndex ++;
            }
        }

        subpages = newSubpageArray(maxSubpageAllocs); // 2048个subpage对象
    }

    /** Creates a special chunk that is not pooled非池化chunk. */
    PoolChunk(PoolArena<T> arena, T memory, int size) {
        unpooled = true; // 未池化
        this.arena = arena;
        this.memory = memory;
        memoryMap = null;
        depthMap = null;
        subpages = null;
        subpageOverflowMask = 0;
        pageSize = 0;
        pageShifts = 0;
        maxOrder = 0;
        unusable = (byte) (maxOrder + 1);
        chunkSize = size;
        log2ChunkSize = log2(chunkSize);
        maxSubpageAllocs = 0;
    }

    @SuppressWarnings("unchecked")
    private PoolSubpage<T>[] newSubpageArray(int size) {
        return new PoolSubpage[size];
    }

    @Override
    public int usage() {
        final int freeBytes = this.freeBytes;
        if (freeBytes == 0) {
            return 100;
        }

        int freePercentage = (int) (freeBytes * 100L / chunkSize);
        if (freePercentage == 0) {
            return 99;
        }
        return 100 - freePercentage;
    }

    long allocate(int normCapacity) {
        if ((normCapacity & subpageOverflowMask) != 0) { // >= pageSize 8kB
            return allocateRun(normCapacity); // normal分配
        } else {
            return allocateSubpage(normCapacity); // subpage分配
        }
    }

    /**
     * Update method used by allocate
     * This is triggered only when a successor is allocated and all its predecessors
     * need to update their state
     * The minimal depth at which subtree rooted at id has some free space
     *
     * @param id id
     */
    private void updateParentsAlloc(int id) {
        while (id > 1) {
            int parentId = id >>> 1;
            byte val1 = value(id);
            byte val2 = value(id ^ 1); // 兄弟节点对应的val
            byte val = val1 < val2 ? val1 : val2; // val取较小值，更新父节点
            setValue(parentId, val);
            id = parentId;
        }
    }

    /**
     * Update method used by free
     * This needs to handle the special case when both children are completely free
     * in which case parent be directly allocated on request of size = child-size * 2
     *
     * @param id id
     */
    private void updateParentsFree(int id) {
        int logChild = depth(id) + 1;
        while (id > 1) {
            int parentId = id >>> 1;
            byte val1 = value(id);
            byte val2 = value(id ^ 1);
            logChild -= 1; // in first iteration equals log, subsequently reduce 1 from logChild as we traverse up

            if (val1 == logChild && val2 == logChild) {
                setValue(parentId, (byte) (logChild - 1));
            } else {
                byte val = val1 < val2 ? val1 : val2;
                setValue(parentId, val);
            }

            id = parentId;
        }
    }

    /**
     * Algorithm to allocate an index in memoryMap when we query for a free node
     * at depth d
     *
     * @param d depth
     * @return index in memoryMap
     */
    private int allocateNode(int d) {
        int id = 1;
        int initial = - (1 << d); // has last d bits = 0 and rest all = 1
        byte val = value(id);
        if (val > d) { // unusable 不可用
            return -1;
        }
        while (val < d || (id & initial) == 0) { // id & initial == 1 << d for all ids at depth d, for < d it is 0
            id <<= 1; // 向下层遍历
            val = value(id);
            if (val > d) {
                id ^= 1;  // 计算兄弟节点id
                val = value(id);
            }
        }
        byte value = value(id); // 得到满足分配要求的id
        assert value == d && (id & initial) == 1 << d : String.format("val = %d, id & initial = %d, d = %d",
                value, id & initial, d);
        setValue(id, unusable); // mark as unusable id指向的内存段标记为不可用
        updateParentsAlloc(id); // 更新
        return id;
    }

    /**
     * Allocate a run of pages (>=1) normal分配
     *
     * @param normCapacity normalized capacity
     * @return index in memoryMap
     */
    private long allocateRun(int normCapacity) {
        int d = maxOrder - (log2(normCapacity) - pageShifts); // 计算平衡二叉树深度
        int id = allocateNode(d);
        if (id < 0) { // 不可用，返回
            return id;
        }
        freeBytes -= runLength(id); // 计算剩余可用字节数，runLength(id): 计算id对应节点的字节数
        return id;
    }

    /**
     * Create/ initialize a new PoolSubpage of normCapacity
     * Any PoolSubpage created/ initialized here is added to subpage pool in the PoolArena that owns this PoolChunk
     *
     * @param normCapacity normalized capacity
     * @return index in memoryMap （bitMapIdx+memoryMapIdx）
     */
    private long allocateSubpage(int normCapacity) {
        // Obtain the head of the PoolSubPage pool that is owned by the PoolArena and synchronize on it.
        // This is need as we may add it back and so alter the linked-list structure.
        PoolSubpage<T> head = arena.findSubpagePoolHead(normCapacity);
        synchronized (head) {
            int d = maxOrder; // subpages are only be allocated from pages i.e., subpage内存分配，只在leaves节点 --> 11
            int id = allocateNode(d); // 分配page
            if (id < 0) { // 无法分配
                return id;
            }

            final PoolSubpage<T>[] subpages = this.subpages; // subpages.length: 2048
            final int pageSize = this.pageSize; // 8kB

            freeBytes -= pageSize; // 更新可用字节数

            int subpageIdx = subpageIdx(id); // subpage索引，当前page索引对应的subpage对象
            PoolSubpage<T> subpage = subpages[subpageIdx];
            if (subpage == null) {
                // 创建PoolSubpage, 会添加到head链表
                // runOffset(id): 计算id对应page相对于该PoolChunk的字节偏移
                subpage = new PoolSubpage<T>(head, this, id, runOffset(id), pageSize, normCapacity);
                subpages[subpageIdx] = subpage;
            } else {
                subpage.init(head, normCapacity);
            }
            return subpage.allocate(); // subpage内存分配
        }
    }

    /**
     * Free a subpage or a run of pages
     * When a subpage is freed from PoolSubpage, it might be added back to subpage pool of the owning PoolArena
     * If the subpage pool in PoolArena has at least one other PoolSubpage of given elemSize, we can
     * completely free the owning Page so it is available for subsequent allocations
     *
     * @param handle handle to free
     */
    void free(long handle) {
        int memoryMapIdx = memoryMapIdx(handle);
        int bitmapIdx = bitmapIdx(handle); // sub page

        if (bitmapIdx != 0) { // free a subpage
            PoolSubpage<T> subpage = subpages[subpageIdx(memoryMapIdx)];
            assert subpage != null && subpage.doNotDestroy;

            // Obtain the head of the PoolSubPage pool that is owned by the PoolArena and synchronize on it.
            // This is need as we may add it back and so alter the linked-list structure.
            PoolSubpage<T> head = arena.findSubpagePoolHead(subpage.elemSize);
            synchronized (head) {
                if (subpage.free(head, bitmapIdx & 0x3FFFFFFF)) {
                    return;
                }
            }
        }
        freeBytes += runLength(memoryMapIdx); // 可用字节数更新
        setValue(memoryMapIdx, depth(memoryMapIdx)); // memoryMapIdx节点标记为可用
        updateParentsFree(memoryMapIdx); // 更新父节点
    }

    void initBuf(PooledByteBuf<T> buf, long handle, int reqCapacity) {
        int memoryMapIdx = memoryMapIdx(handle);
        int bitmapIdx = bitmapIdx(handle); // 该变量只在subpage分配时不为0
        if (bitmapIdx == 0) { // normal
            byte val = value(memoryMapIdx);
            assert val == unusable : String.valueOf(val);
            // runOffset(memoryMapIdx): chunk内存的字节偏移， runLength(memoryMapIdx): memoryMapIdx对应那块内存的最大值
            buf.init(this, handle, runOffset(memoryMapIdx), reqCapacity, runLength(memoryMapIdx),
                     arena.parent.threadCache());
        } else {
            initBufWithSubpage(buf, handle, bitmapIdx, reqCapacity); // 根据subPage初始化ByteBuf
        }
    }

    void initBufWithSubpage(PooledByteBuf<T> buf, long handle, int reqCapacity) {
        initBufWithSubpage(buf, handle, bitmapIdx(handle), reqCapacity);
    }

    private void initBufWithSubpage(PooledByteBuf<T> buf, long handle, int bitmapIdx, int reqCapacity) {
        assert bitmapIdx != 0;

        int memoryMapIdx = memoryMapIdx(handle); // page 索引

        // subpageIdx(memoryMapIdx)：获取page相对于最左端的偏移，如2048，为0；2049，则为1(在深度11这一层)
        PoolSubpage<T> subpage = subpages[subpageIdx(memoryMapIdx)]; // 获取对应的subpage
        assert subpage.doNotDestroy;
        assert reqCapacity <= subpage.elemSize;

        // runOffset(memoryMapIdx) + (bitmapIdx & 0x3FFFFFFF) * subpage.elemSize: 计算分配到的内存相对于当前PoolChunk的偏移字节数
        // - runOffset(memoryMapIdx): 获取当前分配到的内存对应的page，相对于当前PoolChunk的偏移字节数，
        // - (bitmapIdx & 0x3FFFFFFF) * subpage.elemSize：获取当前page中，分配到的内存相对于page的偏移字节数
        buf.init( // ByteBuf初始化
            this, handle,
            runOffset(memoryMapIdx) + (bitmapIdx & 0x3FFFFFFF) * subpage.elemSize, reqCapacity, subpage.elemSize,
            arena.parent.threadCache());
    }

    private byte value(int id) {
        return memoryMap[id];
    }

    private void setValue(int id, byte val) {
        memoryMap[id] = val;
    }

    private byte depth(int id) {
        return depthMap[id];
    }

    private static int log2(int val) {
        // compute the (0-based, with lsb = 0) position of highest set bit i.e, log2
        return INTEGER_SIZE_MINUS_ONE - Integer.numberOfLeadingZeros(val);
    }

    private int runLength(int id) {
        // represents the size in #bytes supported by node 'id' in the tree
        return 1 << (log2ChunkSize - depth(id));
    }

    private int runOffset(int id) {
        // represents the 0-based offset in #bytes from start of the byte-array chunk
        int shift = id ^ (1 << depth(id)); // id那一层，从左边开始计算，偏移多少
        return shift * runLength(id);// 偏移字节数
    }

    private int subpageIdx(int memoryMapIdx) {
        return memoryMapIdx ^ maxSubpageAllocs; // remove highest set bit, to get offset
    }

    private static int memoryMapIdx(long handle) {
        return (int) handle; // 低32位为memoryMap索引
    }

    private static int bitmapIdx(long handle) {
        return (int) (handle >>> Integer.SIZE);
    }

    @Override
    public int chunkSize() {
        return chunkSize;
    }

    @Override
    public int freeBytes() {
        return freeBytes;
    }

    @Override
    public String toString() {
        return new StringBuilder()
            .append("Chunk(")
            .append(Integer.toHexString(System.identityHashCode(this)))
            .append(": ")
            .append(usage())
            .append("%, ")
            .append(chunkSize - freeBytes)
            .append('/')
            .append(chunkSize)
            .append(')')
            .toString();
    }

    void destroy() {
        arena.destroyChunk(this);
    }
}
