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

final class PoolSubpage<T> implements PoolSubpageMetric {

    final PoolChunk<T> chunk;
    private final int memoryMapIdx; // 当前page在chunk中的id
    private final int runOffset; // 当前page在chunk.memory的偏移量
    private final int pageSize; // 8192byte=8kB
    private final long[] bitmap; // 记录SubPage内存分配情况 1表示已分配

    PoolSubpage<T> prev; // 双向链表连接
    PoolSubpage<T> next;

    boolean doNotDestroy;
    int elemSize; // SubPage均分为2kB或1kB或... 该page切分后每一段的大小
    private int maxNumElems; // 该page包含的段数量
    private int bitmapLength;
    private int nextAvail; // 下一个可用的位置
    private int numAvail; // 可用的段数量

    // TODO: Test if adding padding helps under contention
    //private long pad0, pad1, pad2, pad3, pad4, pad5, pad6, pad7;

    /** Special constructor that creates a linked list head */
    PoolSubpage(int pageSize) {
        chunk = null;
        memoryMapIdx = -1;
        runOffset = -1;
        elemSize = -1;
        this.pageSize = pageSize;
        bitmap = null;
    }

    PoolSubpage(PoolSubpage<T> head, PoolChunk<T> chunk, int memoryMapIdx, int runOffset, int pageSize, int elemSize) {
        this.chunk = chunk; // 所属chunk
        this.memoryMapIdx = memoryMapIdx; // page id
        this.runOffset = runOffset; // id对应page相对于chunk的内存偏移
        this.pageSize = pageSize; // 8kB
        // 这里bitmap长度8已足够，因为elemSize最小为16B，一个page 8kB，最多可以分为512份，这512份可以用8个long类型数表示，
        // 一个long类型数表示64份，每一个bit位表示每一份的分配情况
        bitmap = new long[pageSize >>> 10]; // pageSize / 16 / 64 = 8
        init(head, elemSize);
    }

    void init(PoolSubpage<T> head, int elemSize) {
        doNotDestroy = true;
        this.elemSize = elemSize;
        if (elemSize != 0) {
            maxNumElems = numAvail = pageSize / elemSize; // page划分的份数
            nextAvail = 0;

            bitmapLength = maxNumElems >>> 6; // 计算需要使用的bitmap long类型数个数，即bitmapLength
            if ((maxNumElems & 63) != 0) {
                bitmapLength ++;
            }

            for (int i = 0; i < bitmapLength; i ++) {
                bitmap[i] = 0; // 初始化，bitmap一个long类型元素可表示64个内存段的使用情况(一个long 64位)
            }
        }
        addToPool(head); // 添加到subpage链表
    }

    /**
     * Returns the bitmap index of the subpage allocation.
     */
    long allocate() {
        if (elemSize == 0) {
            return toHandle(0);
        }

        if (numAvail == 0 || !doNotDestroy) { // 如果可用内存段为0，返回-1，不可用
            return -1;
        }

        final int bitmapIdx = getNextAvail(); // 内存段位置，如67=64+3
        int q = bitmapIdx >>> 6; // bitmap数组索引，如1
        int r = bitmapIdx & 63; //  bitmap某个数组元素64位中第几位，如3
        assert ((bitmap[q] >>> r) & 1) == 0; // 断言该内存段空闲
        bitmap[q] |= 1L << r; // 或操作，赋值

        if (-- numAvail == 0) { // 可用内存段减1
            removeFromPool(); // 将自己从链表移除
        }

        return toHandle(bitmapIdx);
    }

    /**
     * @return {@code true} if this subpage is in use.
     *         {@code false} if this subpage is not used by its chunk and thus it's OK to be released.
     */
    boolean free(PoolSubpage<T> head, int bitmapIdx) {
        if (elemSize == 0) {
            return true;
        }
        int q = bitmapIdx >>> 6;
        int r = bitmapIdx & 63;
        assert (bitmap[q] >>> r & 1) != 0;
        bitmap[q] ^= 1L << r; // 更新为未使用0

        setNextAvail(bitmapIdx);

        if (numAvail ++ == 0) {
            addToPool(head);
            return true;
        }

        if (numAvail != maxNumElems) {
            return true;
        } else {
            // Subpage not in use (numAvail == maxNumElems)
            if (prev == next) {
                // Do not remove if this subpage is the only one left in the pool.
                return true;
            }

            // Remove this subpage from the pool if there are other subpages left in the pool.
            doNotDestroy = false;
            removeFromPool();
            return false;
        }
    }

    private void addToPool(PoolSubpage<T> head) {
        assert prev == null && next == null;
        prev = head; // 头插法插入
        next = head.next;
        next.prev = this;
        head.next = this;
    }

    private void removeFromPool() { // 将自己从链表移除
        assert prev != null && next != null;
        prev.next = next;
        next.prev = prev;
        next = null;
        prev = null;
    }

    private void setNextAvail(int bitmapIdx) {
        nextAvail = bitmapIdx;
    }

    private int getNextAvail() {
        int nextAvail = this.nextAvail;
        if (nextAvail >= 0) {
            this.nextAvail = -1;
            return nextAvail;
        }
        return findNextAvail();
    }

    private int findNextAvail() {
        final long[] bitmap = this.bitmap;
        final int bitmapLength = this.bitmapLength;
        for (int i = 0; i < bitmapLength; i ++) {
            long bits = bitmap[i];
            if (~bits != 0) { // 表示该long bits表示的多个内存段中还有空闲的部分
                return findNextAvail0(i, bits);
            }
        }
        return -1;
    }

    private int findNextAvail0(int i, long bits) {
        final int maxNumElems = this.maxNumElems;
        final int baseVal = i << 6;

        for (int j = 0; j < 64; j ++) {
            if ((bits & 1) == 0) { // 查到可用的内存段
                int val = baseVal | j; // 内存段bitmapIdx
                if (val < maxNumElems) { // 得到的内存段bitmapIdx不能超过最大的内存段数maxNumElems
                    return val;
                } else {
                    break;
                }
            }
            bits >>>= 1; // 右移一位继续判断
        }
        return -1;
    }

    private long toHandle(int bitmapIdx) { // bitmapIdx + memoryMapIdx
        return 0x4000000000000000L | (long) bitmapIdx << 32 | memoryMapIdx;
    }

    @Override
    public String toString() {
        if (!doNotDestroy) {
            return "(" + memoryMapIdx + ": not in use)";
        }

        return String.valueOf('(') + memoryMapIdx + ": " + (maxNumElems - numAvail) + '/' + maxNumElems +
               ", offset: " + runOffset + ", length: " + pageSize + ", elemSize: " + elemSize + ')';
    }

    @Override
    public int maxNumElements() {
        return maxNumElems;
    }

    @Override
    public int numAvailable() {
        return numAvail;
    }

    @Override
    public int elementSize() {
        return elemSize;
    }

    @Override
    public int pageSize() {
        return pageSize;
    }

    void destroy() {
        if (chunk != null) {
            chunk.destroy();
        }
    }
}
