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
 * <p>
 * The byte stream is expected to be in UTF-8 character encoding or ASCII. The current implementation
 * uses direct {@code byte} to {@code char} cast and then compares that {@code char} to a few low range
 * ASCII characters like {@code '\n'} or {@code '\r'}. UTF-8 is not using low range [0..0x7F]
 * byte values for multibyte codepoint representations therefore fully supported by this implementation.
 * <p>
 * For a more general delimiter-based decoder, see {@link DelimiterBasedFrameDecoder}.
 */
public class LineBasedFrameDecoder extends ByteToMessageDecoder {

    /** Maximum length of a frame we're willing to decode.  */
    private final int maxLength;
    /** Whether or not to throw an exception as soon as we exceed maxLength. */
    private final boolean failFast;
    private final boolean stripDelimiter;

    /** True if we're discarding input because we're already over maxLength.  */
    private boolean discarding;
    private int discardedBytes;

    /** Last scan position. */
    private int offset;

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
     *                  whether the entire frame has been read.
     *                  If <tt>false</tt>, a {@link TooLongFrameException} is
     *                  thrown after the entire frame that exceeds
     *                  <tt>maxFrameLength</tt> has been read.
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
     * 每解码出一行会触发一次业务handler的channelRead方法
     * Create a frame out of the {@link ByteBuf} and return it.
     *
     * @param   ctx             the {@link ChannelHandlerContext} which this {@link ByteToMessageDecoder} belongs to
     * @param   buffer          the {@link ByteBuf} from which to read data
     * @return  frame           the {@link ByteBuf} which represent the frame or {@code null} if no frame could
     *                          be created.
     */
    // TODO 【Question33】当最后一行中没有行分隔符时怎么办？是不是永远都解码不了？还是说最后一场默认有行分隔符？
    protected Object decode(ChannelHandlerContext ctx, ByteBuf buffer) throws Exception {
        // 找到换行符的位置
        final int eol = findEndOfLine(buffer);
        // 默认为false
        if (!discarding) {
            if (eol >= 0) {
                final ByteBuf frame;
                // 拿到需要换行的长度
                final int length = eol - buffer.readerIndex();
                // 如果是\r\n换行符的话，delimLength=2；
                // 如果是\n换行符的话，delimLength=1；
                final int delimLength = buffer.getByte(eol) == '\r'? 2 : 1;
                // 如果需要换行的长度大于maxLength
                if (length > maxLength) {
                    // 将ByteBuf的读指针向右移动delimLength + delimLength位
                    buffer.readerIndex(eol + delimLength);
                    // 此时直接触发fireExceptionCaught
                    // 【Question31】此时触发的是触发业务handler的exceptionCaught方法？？?
                    // 【Anser31】是的
                    fail(ctx, length);
                    // 触发业务handler的exceptionCaught方法后，返回null，此时不会再触发业务handler的channelRead方法
                    return null;
                }
                // 默认为true表示解码的内容不包括换行符
                if (stripDelimiter) {
                    // 根据readerIndex到换行符之间的长度截取一个ByteBuf对象返回,注意此时buffer的readerIndex指针也会向右移动length个位置
                    frame = buffer.readRetainedSlice(length);
                    // readerIndex指针向右移动length个位置后，此时要调用skipBytes方法再向右移动delimLength个位置跳过换行符
                    buffer.skipBytes(delimLength);
                // 如果解码的内容也包括换行符的话
                } else {
                    frame = buffer.readRetainedSlice(length + delimLength);
                }
                return frame;
            // 若findEndOfLine方法返回-1即没有找到换行符则执行到这里，说明可能累积的字节流还不够。然后判断下ByteBuf容量有没超，
            // 若有超过maxLength，则开启丢弃模式，最后返回null；若没有超过maxLength，则直接返回null。返回null后等下次IO流到来时继续解码
            // TODO 【Question32】最后一行默认有没有换行符呢？按照这里的逻辑最后一行也需要换行符
            } else {
                // 若ByteBuf的字节长度大于maxLength，则丢弃相应字节，置discarding = true，且触发exceptionCaught方法
                final int length = buffer.readableBytes();
                if (length > maxLength) {
                    discardedBytes = length;
                    buffer.readerIndex(buffer.writerIndex());
                    // 开启丢弃模式
                    discarding = true;
                    offset = 0;
                    if (failFast) {
                        fail(ctx, "over " + discardedBytes);
                    }
                }
                // 最后返回null，待下次IO流到来时再继续解码
                return null;
            }
        // 执行到这里，说明前面没有换行符的情况下ByteBuf的长度已经超过maxLength并且已经被丢弃了，已经开启了丢弃模式
        } else {
            // 当有一次IO流到来时，若此时继续读到换行符，那么则继续丢弃原来的那一行
            if (eol >= 0) {
                final int length = discardedBytes + eol - buffer.readerIndex();
                final int delimLength = buffer.getByte(eol) == '\r'? 2 : 1;
                buffer.readerIndex(eol + delimLength);
                discardedBytes = 0;
                discarding = false;
                // 这个!failFast跟前面非丢弃模式的failFast一一对应，如果failFast=true，则只要已超过maxLength就立即触发exceptionCaught；
                // 如果failFast=false不会立即触发exceptionCaught，只要等到真正丢弃完一行后才触发exceptionCaught
                if (!failFast) {
                    fail(ctx, length);
                }
            // 若读到的IO流中仍然没有换行符，则继续丢弃
            } else {
                discardedBytes += buffer.readableBytes();
                buffer.readerIndex(buffer.writerIndex());
                // We skip everything in the buffer, we need to set the offset to 0 again.
                offset = 0;
            }
            // 返回null
            return null;
        }
    }

    private void fail(final ChannelHandlerContext ctx, int length) {
        fail(ctx, String.valueOf(length));
    }

    private void fail(final ChannelHandlerContext ctx, String length) {
        // 这里只是触发业务handler的exceptionCaught方法，并不会throw抛出一个异常
        ctx.fireExceptionCaught(
                new TooLongFrameException(
                        "frame length (" + length + ") exceeds the allowed maximum (" + maxLength + ')'));
    }

    /**
     * Returns the index in the buffer of the end of line found.
     * Returns -1 if no end of line was found in the buffer.
     */
    private int findEndOfLine(final ByteBuf buffer) {
        int totalLength = buffer.readableBytes();
        // 从ByteBuf容器里找到第一个ByteProcessor.FIND_LF（\n）字符出现的位置，没有则返回-1
        int i = buffer.forEachByte(buffer.readerIndex() + offset, totalLength - offset, ByteProcessor.FIND_LF);
        if (i >= 0) {
            offset = 0;
            // 如果\n字符前面是\r字符，此时i--退到\r字符位置
            if (i > 0 && buffer.getByte(i - 1) == '\r') {
                i--;
            }
        // 如果i==-1说明没有ByteBuf容器里没有\n字符，此时直接给offset赋值totalLength
        } else {
            offset = totalLength;
        }
        return i;
    }
}
