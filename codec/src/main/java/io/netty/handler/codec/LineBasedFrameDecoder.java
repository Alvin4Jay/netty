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
package io.netty.handler.codec;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.util.ByteProcessor;

import java.util.List;

/**
 * A decoder that splits the received {@link ByteBuf}s on line endings.
 * <p>
 * Both {@code "\n"} and {@code "\r\n"} are handled.
 * For a more general delimiter-based decoder, see {@link DelimiterBasedFrameDecoder}.
 */
public class LineBasedFrameDecoder extends ByteToMessageDecoder {

    /** Maximum length of a frame we're willing to decode.  */
    private final int maxLength; // 最大解码帧长度
    /** Whether or not to throw an exception as soon as we exceed maxLength. */
    private final boolean failFast; // 是否一遇到解码帧长度超出maxLength，就直接抛出异常 默认false
    private final boolean stripDelimiter; // 解码出来的数据包，是否去除分隔符

    /** True if we're discarding input because we're already over maxLength.  */
    private boolean discarding; // 是否处于丢弃模式
    private int discardedBytes; // 丢弃模式下，已丢弃的字节数

    /**
     * Creates a new decoder.
     * @param maxLength  the maximum length of the decoded frame.
     *                   A {@link TooLongFrameException} is thrown if
     *                   the length of the frame exceeds this value.
     */
    public LineBasedFrameDecoder(final int maxLength) {
        this(maxLength, true, false);
    }

    /**
     * Creates a new decoder.
     * @param maxLength  the maximum length of the decoded frame.
     *                   A {@link TooLongFrameException} is thrown if
     *                   the length of the frame exceeds this value.
     * @param stripDelimiter  whether the decoded frame should strip out the
     *                        delimiter or not
     * @param failFast  If <tt>true</tt>, a {@link TooLongFrameException} is
     *                  thrown as soon as the decoder notices the length of the
     *                  frame will exceed <tt>maxFrameLength</tt> regardless of
     *                  whether the entire frame has been read. (true，不考虑是否已读完整个帧，直接抛出异常)
     *                  If <tt>false</tt>, a {@link TooLongFrameException} is
     *                  thrown after the entire frame that exceeds
     *                  <tt>maxFrameLength</tt> has been read. (false，在读完整个帧之后抛出异常)
     */
    public LineBasedFrameDecoder(final int maxLength, final boolean stripDelimiter, final boolean failFast) {
        this.maxLength = maxLength;
        this.failFast = failFast;
        this.stripDelimiter = stripDelimiter;
    }

    @Override
    protected final void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
        Object decoded = decode(ctx, in);
        if (decoded != null) {
            out.add(decoded);
        }
    }

    /**
     * Create a frame out of the {@link ByteBuf} and return it.
     *
     * @param   ctx             the {@link ChannelHandlerContext} which this {@link ByteToMessageDecoder} belongs to
     * @param   buffer          the {@link ByteBuf} from which to read data
     * @return  frame           the {@link ByteBuf} which represent the frame or {@code null} if no frame could
     *                          be created.
     */
    protected Object decode(ChannelHandlerContext ctx, ByteBuf buffer) throws Exception {
        final int eol = findEndOfLine(buffer); // 找到\r\n 或\n的位置
        if (!discarding) { // discarding: false 非丢弃模式处理
            if (eol >= 0) { // 找到行分割符
                final ByteBuf frame;
                final int length = eol - buffer.readerIndex(); // 当前readerIndex到分隔符的字节长度
                final int delimLength = buffer.getByte(eol) == '\r'? 2 : 1;// 分隔符长度

                if (length > maxLength) {
                    buffer.readerIndex(eol + delimLength); // 丢弃eol之前的字节
                    fail(ctx, length); // 抛出异常，传播帧太长的事件
                    return null;
                }

                if (stripDelimiter) { // 是否去掉行分隔符
                    frame = buffer.readRetainedSlice(length); // 取出不包含行分隔符的字节数据，引用计数+1
                    buffer.skipBytes(delimLength); // 跳过行分隔符字节
                } else {
                    frame = buffer.readRetainedSlice(length + delimLength); // 取出字节数据(包含行分隔符)，引用计数+1
                }

                return frame;
            } else { // 未找到行分割符
                final int length = buffer.readableBytes(); // 可读字节数
                if (length > maxLength) { // 当前的可读字节书超过maxLength
                    discardedBytes = length; // 记录已丢弃的字节数
                    buffer.readerIndex(buffer.writerIndex()); // 直接跳过当前所有的可取字节，设置读指针
                    discarding = true; // 进入丢弃模式
                    if (failFast) { // 立即触发failfast
                        fail(ctx, "over " + discardedBytes);
                    }
                }
                return null;
            }
        } else { // 丢弃模式处理
            if (eol >= 0) { // 找到行分割符
                final int length = discardedBytes + eol - buffer.readerIndex(); // 总的丢弃字节数
                final int delimLength = buffer.getByte(eol) == '\r'? 2 : 1; // 分隔符长度
                buffer.readerIndex(eol + delimLength); // 设置读指针
                discardedBytes = 0;
                discarding = false; // 进入非丢弃模式
                if (!failFast) { // 所有字节丢弃后触发
                    fail(ctx, length);
                }
            } else { // 未找到行分割符
                discardedBytes += buffer.readableBytes(); // 继续累加丢弃的字节
                buffer.readerIndex(buffer.writerIndex()); // 直接跳过当前所有的可取字节，设置读指针
            }
            return null;
        }
    }

    private void fail(final ChannelHandlerContext ctx, int length) {
        fail(ctx, String.valueOf(length));
    }

    private void fail(final ChannelHandlerContext ctx, String length) {
        ctx.fireExceptionCaught(
                new TooLongFrameException(
                        "frame length (" + length + ") exceeds the allowed maximum (" + maxLength + ')'));
    }

    /**
     * Returns the index in the buffer of the end of line found.
     * Returns -1 if no end of line was found in the buffer.
     */
    private static int findEndOfLine(final ByteBuf buffer) {
        int i = buffer.forEachByte(ByteProcessor.FIND_LF); // 查找\n
        if (i > 0 && buffer.getByte(i - 1) == '\r') { // \r\n
            i--;
        }
        return i;
    }
}
