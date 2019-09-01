package imooc.chapter2.bytebuf;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.PooledByteBufAllocator;

/**
 * Class description here.
 *
 * @author xuanjian
 */
public class SuPageAllocate {

    public static void main(String[] args) {

        PooledByteBufAllocator allocator = PooledByteBufAllocator.DEFAULT;

        allocator.directBuffer(16); // 16B, tiny

        ByteBuf byteBuf1 = allocator.directBuffer(1024); // 1kB, small
        ByteBuf byteBuf2 = allocator.directBuffer(1024); // 1kB, small

        byteBuf1.release(); // 释放内存
        byteBuf2.release();

    }

}
