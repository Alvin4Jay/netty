package imooc.chapter2.bytebuf;

import io.netty.buffer.PooledByteBufAllocator;

/**
 * Class description here.
 *
 * @author xuanjian
 */
public class PageAllocate {

    public static void main(String[] args) {

        int page = 1024 * 8;

        PooledByteBufAllocator allocator = PooledByteBufAllocator.DEFAULT;

        allocator.directBuffer(page);

        allocator.directBuffer(page);

        allocator.directBuffer(2 * page);

        allocator.directBuffer(page);

    }

}
