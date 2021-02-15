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

import static io.netty.util.internal.ObjectUtil.checkNotNull;
import static io.netty.util.internal.ObjectUtil.checkPositive;
import static io.netty.util.internal.ObjectUtil.checkPositiveOrZero;

import java.nio.ByteOrder;
import java.util.List;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;

/**
 * 【结论】最终解码截取多少个字节长度取决于lengthFieldOffset，lengthFieldLength，lengthAdjustment，
 *        即每次要从ByteBuf累积的字节流中截取的实际字节流长度满足以下公式：
 *        actualFrameLength = frameLength + lengthAdjustment + lengthFieldOffset + lengthFieldLength - initialBytesToStrip
 * 和initialBytesToStrip这四个参数综合作用
 * A decoder that splits the received {@link ByteBuf}s dynamically by the
 * value of the length field in the message.  It is particularly useful when you
 * decode a binary message which has an integer header field that represents the
 * length of the message body or the whole message.
 * <p>
 * {@link LengthFieldBasedFrameDecoder} has many configuration parameters so
 * that it can decode any message with a length field, which is often seen in
 * proprietary client-server protocols. Here are some example that will give
 * you the basic idea on which option does what.
 *
 * <h3>2 bytes length field at offset 0, do not strip header</h3>
 *
 * The value of the length field in this example is <tt>12 (0x0C)</tt> which
 * represents the length of "HELLO, WORLD".  By default, the decoder assumes
 * that the length field represents the number of the bytes that follows the
 * length field.  Therefore, it can be decoded with the simplistic parameter
 * combination.
 * <pre>
 * 1）因为Length所在位置向右偏移了0个字节，故lengthFieldOffset=0
 * 2）因为长度域内容只表示消息体的长度，长度域Length与消息体之间没有任何消息头，故lengthAdjustment=0
 * 3）从第0个字节开始截取故initialBytesToStrip = 0
 * <b>lengthFieldOffset</b>   = <b>0</b>
 * <b>lengthFieldLength</b>   = <b>2</b>
 * lengthAdjustment    = 0
 * initialBytesToStrip = 0 (= do not strip header)
 *
 * BEFORE DECODE (14 bytes)         AFTER DECODE (14 bytes)
 * +--------+----------------+      +--------+----------------+
 * | Length | Actual Content |----->| Length | Actual Content |
 * | 0x000C | "HELLO, WORLD" |      | 0x000C | "HELLO, WORLD" |
 * +--------+----------------+      +--------+----------------+
 * </pre>
 *
 * <h3>2 bytes length field at offset 0, strip header</h3>
 *
 * Because we can get the length of the content by calling
 * {@link ByteBuf#readableBytes()}, you might want to strip the length
 * field by specifying <tt>initialBytesToStrip</tt>.  In this example, we
 * specified <tt>2</tt>, that is same with the length of the length field, to
 * strip the first two bytes.
 * <pre>
 * 1）因为Length所在位置向右偏移了0个字节，故lengthFieldOffset=0
 * 2）因为长度域内容只表示消息体的长度，长度域Length与消息体之间没有任何消息头，故lengthAdjustment=0
 * 3）从第3个字节开始截取故initialBytesToStrip = 2去掉消息长度
 * lengthFieldOffset   = 0
 * lengthFieldLength   = 2
 * lengthAdjustment    = 0
 * <b>initialBytesToStrip</b> = <b>2</b> (= the length of the Length field)
 *
 * BEFORE DECODE (14 bytes)         AFTER DECODE (12 bytes)
 * +--------+----------------+      +----------------+
 * | Length | Actual Content |----->| Actual Content |
 * | 0x000C | "HELLO, WORLD" |      | "HELLO, WORLD" |
 * +--------+----------------+      +----------------+
 * </pre>
 *
 * <h3>2 bytes length field at offset 0, do not strip header, the length field
 *     represents the length of the whole message</h3>
 *
 * In most cases, the length field represents the length of the message body
 * only, as shown in the previous examples.  However, in some protocols, the
 * length field represents the length of the whole message, including the
 * message header.  In such a case, we specify a non-zero
 * <tt>lengthAdjustment</tt>.  Because the length value in this example message
 * is always greater than the body length by <tt>2</tt>, we specify <tt>-2</tt>
 * as <tt>lengthAdjustment</tt> for compensation.
 * <pre>
 * 1）因为Length所在位置向右偏移了0个字节，故lengthFieldOffset=0
 * 2）因为长度域内容只表示整个消息的长度，长度域Length与消息体之间没有任何消息头，而消息体内容字节长度只有12，故lengthAdjustment=-2
 * 3）从第0个字节开始截取故initialBytesToStrip = 0
 * lengthFieldOffset   =  0
 * lengthFieldLength   =  2
 * 因为长度域占2个字节，而长度域内容又是默认表示的真正内容的长度，而此时长度域内容又包括了长度域的长度，所以要减去长度域的长度，所以lengthAdjustment=-2
 * <b>lengthAdjustment</b>    = <b>-2</b> (= the length of the Length field)
 * initialBytesToStrip =  0
 *
 * BEFORE DECODE (14 bytes)         AFTER DECODE (14 bytes)
 * +--------+----------------+      +--------+----------------+
 * | Length | Actual Content |----->| Length | Actual Content |
 * | 0x000E | "HELLO, WORLD" |      | 0x000E | "HELLO, WORLD" |
 * +--------+----------------+      +--------+----------------+
 * </pre>
 *
 * <h3>3 bytes length field at the end of 5 bytes header, do not strip header</h3>
 *
 * The following message is a simple variation of the first example.  An extra
 * header value is prepended to the message.  <tt>lengthAdjustment</tt> is zero
 * again because the decoder always takes the length of the prepended data into
 * account during frame length calculation.
 * <pre>
 * 1）因为Length所在位置向右偏移了2个字节，故lengthFieldOffset=2
 * 2）因为长度域内容只表示消息体的长度，长度域Length与消息体之间没有任何消息头，故lengthAdjustment=0
 * 3）从第0个字节开始截取故initialBytesToStrip = 0
 * <b>lengthFieldOffset</b>   = <b>2</b> (= the length of Header 1)
 * <b>lengthFieldLength</b>   = <b>3</b>
 * lengthAdjustment    = 0
 * initialBytesToStrip = 0
 *
 * BEFORE DECODE (17 bytes)                      AFTER DECODE (17 bytes)
 * +----------+----------+----------------+      +----------+----------+----------------+
 * | Header 1 |  Length  | Actual Content |----->| Header 1 |  Length  | Actual Content |
 * |  0xCAFE  | 0x00000C | "HELLO, WORLD" |      |  0xCAFE  | 0x00000C | "HELLO, WORLD" |
 * +----------+----------+----------------+      +----------+----------+----------------+
 * </pre>
 *
 * <h3>3 bytes length field at the beginning of 5 bytes header, do not strip header</h3>
 *
 * This is an advanced example that shows the case where there is an extra
 * header between the length field and the message body.  You have to specify a
 * positive <tt>lengthAdjustment</tt> so that the decoder counts the extra
 * header into the frame length calculation.
 * <pre>
 * 1）因为Length所在位置向右偏移了0个字节，故lengthFieldOffset=0
 * 2）因为长度域内容只表示消息体的长度，而右边多了个HDR2站2个字节，故lengthAdjustment=2
 * 3）从第0个字节开始截取故initialBytesToStrip = 0
 * lengthFieldOffset   = 0
 * lengthFieldLength   = 3
 * <b>lengthAdjustment</b>    = <b>2</b> (= the length of Header 1)
 * initialBytesToStrip = 0
 *
 * BEFORE DECODE (17 bytes)                      AFTER DECODE (17 bytes)
 * +----------+----------+----------------+      +----------+----------+----------------+
 * |  Length  | Header 1 | Actual Content |----->|  Length  | Header 1 | Actual Content |
 * | 0x00000C |  0xCAFE  | "HELLO, WORLD" |      | 0x00000C |  0xCAFE  | "HELLO, WORLD" |
 * +----------+----------+----------------+      +----------+----------+----------------+
 * </pre>
 *
 * <h3>2 bytes length field at offset 1 in the middle of 4 bytes header,
 *     strip the first header field and the length field</h3>
 *
 * This is a combination of all the examples above.  There are the prepended
 * header before the length field and the extra header after the length field.
 * The prepended header affects the <tt>lengthFieldOffset</tt> and the extra
 * header affects the <tt>lengthAdjustment</tt>.  We also specified a non-zero
 * <tt>initialBytesToStrip</tt> to strip the length field and the prepended
 * header from the frame.  If you don't want to strip the prepended header, you
 * could specify <tt>0</tt> for <tt>initialBytesToSkip</tt>.
 * <pre>
 * 1）因为Length所在位置向右偏移了一个字节，故lengthFieldOffset=1
 * 2）因为长度域内容只表示消息体的长度，而右边多了个HDR2站1个字节，故lengthAdjustment=1
 * 3）但要从HDR2开始截取，故initialBytesToStrip=3，
 * lengthFieldOffset   = 1 (= the length of HDR1)
 * lengthFieldLength   = 2
 * <b>lengthAdjustment</b>    = <b>1</b> (= the length of HDR2)
 * <b>initialBytesToStrip</b> = <b>3</b> (= the length of HDR1 + LEN)
 *
 * BEFORE DECODE (16 bytes)                       AFTER DECODE (13 bytes)
 * +------+--------+------+----------------+      +------+----------------+
 * | HDR1 | Length | HDR2 | Actual Content |----->| HDR2 | Actual Content |
 * | 0xCA | 0x000C | 0xFE | "HELLO, WORLD" |      | 0xFE | "HELLO, WORLD" |
 * +------+--------+------+----------------+      +------+----------------+
 * </pre>
 *
 * <h3>2 bytes length field at offset 1 in the middle of 4 bytes header,
 *     strip the first header field and the length field, the length field
 *     represents the length of the whole message</h3>
 *
 * Let's give another twist to the previous example.  The only difference from
 * the previous example is that the length field represents the length of the
 * whole message instead of the message body, just like the third example.
 * We have to count the length of HDR1 and Length into <tt>lengthAdjustment</tt>.
 * Please note that we don't need to take the length of HDR2 into account
 * because the length field already includes the whole header length.
 * <pre>
 * 1）Length向右偏移了一个字节，故lengthFieldOffset   =  1
 * 2）从HDR2开始截取，故initialBytesToStrip=3
 * 3）因为从HDR2开始截取16个字节，但因为长度域代表了整个消息的长度，故要减去3即lengthAdjustment=-3
 * lengthFieldOffset   =  1
 * lengthFieldLength   =  2
 * TODO 【Question35】 lengthAdjustment这个参数如何理解？
 *      【Answer35】 lengthAdjustment用来当调整要截取的字符串长度，即从Length域结尾开始截取多少个字节
 * <b>lengthAdjustment</b>    = <b>-3</b> (= the length of HDR1 + LEN, negative)
 * <b>initialBytesToStrip</b> = <b> 3</b>
 *
 * BEFORE DECODE (16 bytes)                       AFTER DECODE (13 bytes)
 * +------+--------+------+----------------+      +------+----------------+
 * | HDR1 | Length | HDR2 | Actual Content |----->| HDR2 | Actual Content |
 * | 0xCA | 0x0010 | 0xFE | "HELLO, WORLD" |      | 0xFE | "HELLO, WORLD" |
 * +------+--------+------+----------------+      +------+----------------+
 * </pre>
 * @see LengthFieldPrepender
 */
public class LengthFieldBasedFrameDecoder extends ByteToMessageDecoder {

    private final ByteOrder byteOrder;
    private final int maxFrameLength;
    private final int lengthFieldOffset;
    private final int lengthFieldLength;
    private final int lengthFieldEndOffset;
    private final int lengthAdjustment;
    private final int initialBytesToStrip;
    private final boolean failFast;
    private boolean discardingTooLongFrame;
    private long tooLongFrameLength;
    private long bytesToDiscard;

    /**
     * Creates a new instance.
     *
     * @param maxFrameLength
     *        the maximum length of the frame.  If the length of the frame is
     *        greater than this value, {@link TooLongFrameException} will be
     *        thrown.
     * @param lengthFieldOffset
     *        the offset of the length field
     * @param lengthFieldLength
     *        the length of the length field
     */
    public LengthFieldBasedFrameDecoder(
            int maxFrameLength,
            int lengthFieldOffset, int lengthFieldLength) {
        this(maxFrameLength, lengthFieldOffset, lengthFieldLength, 0, 0);
    }

    /**
     * Creates a new instance.
     *
     * @param maxFrameLength
     *        the maximum length of the frame.  If the length of the frame is
     *        greater than this value, {@link TooLongFrameException} will be
     *        thrown.
     * @param lengthFieldOffset
     *        the offset of the length field
     * @param lengthFieldLength
     *        the length of the length field
     * @param lengthAdjustment
     *        the compensation value to add to the value of the length field
     * @param initialBytesToStrip
     *        the number of first bytes to strip out from the decoded frame
     */
    public LengthFieldBasedFrameDecoder(
            int maxFrameLength,
            int lengthFieldOffset, int lengthFieldLength,
            int lengthAdjustment, int initialBytesToStrip) {
        this(
                maxFrameLength,
                lengthFieldOffset, lengthFieldLength, lengthAdjustment,
                initialBytesToStrip, true);
    }

    /**
     * Creates a new instance.
     *
     * @param maxFrameLength
     *        the maximum length of the frame.  If the length of the frame is
     *        greater than this value, {@link TooLongFrameException} will be
     *        thrown.
     * @param lengthFieldOffset
     *        the offset of the length field
     * @param lengthFieldLength
     *        the length of the length field
     * @param lengthAdjustment
     *        the compensation value to add to the value of the length field
     * @param initialBytesToStrip
     *        the number of first bytes to strip out from the decoded frame
     * @param failFast
     *        If <tt>true</tt>, a {@link TooLongFrameException} is thrown as
     *        soon as the decoder notices the length of the frame will exceed
     *        <tt>maxFrameLength</tt> regardless of whether the entire frame
     *        has been read.  If <tt>false</tt>, a {@link TooLongFrameException}
     *        is thrown after the entire frame that exceeds <tt>maxFrameLength</tt>
     *        has been read.
     */
    public LengthFieldBasedFrameDecoder(
            int maxFrameLength, int lengthFieldOffset, int lengthFieldLength,
            int lengthAdjustment, int initialBytesToStrip, boolean failFast) {
        this(
                ByteOrder.BIG_ENDIAN, maxFrameLength, lengthFieldOffset, lengthFieldLength,
                lengthAdjustment, initialBytesToStrip, failFast);
    }

    /**
     * Creates a new instance.
     *
     * @param byteOrder
     *        the {@link ByteOrder} of the length field
     * @param maxFrameLength
     *        the maximum length of the frame.  If the length of the frame is
     *        greater than this value, {@link TooLongFrameException} will be
     *        thrown.
     * @param lengthFieldOffset
     *        the offset of the length field
     * @param lengthFieldLength
     *        the length of the length field
     * @param lengthAdjustment
     *        the compensation value to add to the value of the length field
     * @param initialBytesToStrip
     *        the number of first bytes to strip out from the decoded frame
     * @param failFast
     *        If <tt>true</tt>, a {@link TooLongFrameException} is thrown as
     *        soon as the decoder notices the length of the frame will exceed
     *        <tt>maxFrameLength</tt> regardless of whether the entire frame
     *        has been read.  If <tt>false</tt>, a {@link TooLongFrameException}
     *        is thrown after the entire frame that exceeds <tt>maxFrameLength</tt>
     *        has been read.
     */
    public LengthFieldBasedFrameDecoder(
            ByteOrder byteOrder, int maxFrameLength, int lengthFieldOffset, int lengthFieldLength,
            int lengthAdjustment, int initialBytesToStrip, boolean failFast) {

        this.byteOrder = checkNotNull(byteOrder, "byteOrder");

        checkPositive(maxFrameLength, "maxFrameLength");

        checkPositiveOrZero(lengthFieldOffset, "lengthFieldOffset");

        checkPositiveOrZero(initialBytesToStrip, "initialBytesToStrip");

        if (lengthFieldOffset > maxFrameLength - lengthFieldLength) {
            throw new IllegalArgumentException(
                    "maxFrameLength (" + maxFrameLength + ") " +
                    "must be equal to or greater than " +
                    "lengthFieldOffset (" + lengthFieldOffset + ") + " +
                    "lengthFieldLength (" + lengthFieldLength + ").");
        }

        this.maxFrameLength = maxFrameLength;
        this.lengthFieldOffset = lengthFieldOffset;
        this.lengthFieldLength = lengthFieldLength;
        this.lengthAdjustment = lengthAdjustment;
        // 这个表示lengthField的结束位置即lengthField的开始位置加上lengthField的长度
        this.lengthFieldEndOffset = lengthFieldOffset + lengthFieldLength;
        this.initialBytesToStrip = initialBytesToStrip;
        this.failFast = failFast;
    }

    @Override
    protected final void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
        Object decoded = decode(ctx, in);
        if (decoded != null) {
            out.add(decoded);
        }
    }

    private void discardingTooLongFrame(ByteBuf in) {
        // this.bytesToDiscard是上一次IO事件中ByteBuf累积的字节流不够用来丢弃剩下的待丢弃的长度
        long bytesToDiscard = this.bytesToDiscard;
        // 此时计算此次ByteBuf累积的字节流和待丢弃的字节流哪个更小，如果ByteBuf累积的字节流比待丢弃的字节流长度还小，此时直接将ByteBuf的读指针移动到末尾，此时重新计算待丢弃的字节长度
        // 若ByteBuf累积的字节流大于等于待丢弃的字节流长度，此时将ByteBuf的读指针向右移动待丢弃的字节长度即可，此时待丢弃的字节已经被丢弃完，此时this.bytesToDiscard为0
        int localBytesToDiscard = (int) Math.min(bytesToDiscard, in.readableBytes());
        in.skipBytes(localBytesToDiscard);
        bytesToDiscard -= localBytesToDiscard;
        this.bytesToDiscard = bytesToDiscard;

        failIfNecessary(false);
    }

    private static void failOnNegativeLengthField(ByteBuf in, long frameLength, int lengthFieldEndOffset) {
        in.skipBytes(lengthFieldEndOffset);
        throw new CorruptedFrameException(
           "negative pre-adjustment length field: " + frameLength);
    }

    private static void failOnFrameLengthLessThanLengthFieldEndOffset(ByteBuf in,
                                                                      long frameLength,
                                                                      int lengthFieldEndOffset) {
        in.skipBytes(lengthFieldEndOffset);
        throw new CorruptedFrameException(
           "Adjusted frame length (" + frameLength + ") is less " +
              "than lengthFieldEndOffset: " + lengthFieldEndOffset);
    }

    private void exceededFrameLength(ByteBuf in, long frameLength) {
        // 计算待丢弃的字节即frameLength减去ByteBuf的剩余可读字节数，因为一次可能丢弃不完所有要丢弃的字节即ByteBuf的可读字节数可能比frameLength还少
        long discard = frameLength - in.readableBytes();
        tooLongFrameLength = frameLength;
        // ByteBuf的剩余可读字节数仍大于frameLength（但frameLength已经超过指定的最大字节数），此时直接将ByteBuf的readerIndex指针向右移动frameLength个位置跳过即可
        if (discard < 0) {
            // buffer contains more bytes then the frameLength so we can discard all now
            in.skipBytes((int) frameLength);
        // 若frameLength大于ByteBuf的剩余可读字节数，说明这一次IO流之前（包括本次IO流）累积的字节数还不够frameLength，故此时需要开启丢弃模式，直接将ByteBuf的readerIndex指针向右移动到剩余可读字节数的末尾处
        } else {
            // Enter the discard mode and discard everything received so far.
            discardingTooLongFrame = true;
            // 保存下一次要丢弃的字节数
            bytesToDiscard = discard;
            in.skipBytes(in.readableBytes());
        }
        // 如果有必要则直接失败
        failIfNecessary(true);
    }

    private static void failOnFrameLengthLessThanInitialBytesToStrip(ByteBuf in,
                                                                     long frameLength,
                                                                     int initialBytesToStrip) {
        in.skipBytes((int) frameLength);
        throw new CorruptedFrameException(
           "Adjusted frame length (" + frameLength + ") is less " +
              "than initialBytesToStrip: " + initialBytesToStrip);
    }

    /**
     * Create a frame out of the {@link ByteBuf} and return it.
     *
     * @param   ctx             the {@link ChannelHandlerContext} which this {@link ByteToMessageDecoder} belongs to
     * @param   in              the {@link ByteBuf} from which to read data
     * @return  frame           the {@link ByteBuf} which represent the frame or {@code null} if no frame could
     *                          be created.
     */
    protected Object decode(ChannelHandlerContext ctx, ByteBuf in) throws Exception {
        // 默认为false，当frameLength大于maxFrameLength时，将会开启丢弃模式即discardingTooLongFrame=true，
        // 即当IO流到来时首先丢弃上次未丢弃完的字节（长度为this.bytesToDiscard）,等丢弃完后再继续进行下一轮（Frame）的解码
        if (discardingTooLongFrame) {
            discardingTooLongFrame(in);
        }
        // 若ByteBuf的字节流小于lengthField的结束位置，那么不用解码，此时直接返回null
        if (in.readableBytes() < lengthFieldEndOffset) {
            return null;
        }
        // ByteBuf的读指针加上lengthField的Offset才是真正的lengthFieldOffset
        int actualLengthFieldOffset = in.readerIndex() + lengthFieldOffset;
        // 获取还未调整的待解码内容的帧长度
        long frameLength = getUnadjustedFrameLength(in, actualLengthFieldOffset, lengthFieldLength,  byteOrder);
        // 如果待解码内容长度小于0，则需要抛出异常，同时需要将读指针readerIndex向右移动到lengthFieldEndOffset处，以免下次IO流的解码受影响
        if (frameLength < 0) {
            failOnNegativeLengthField(in, frameLength, lengthFieldEndOffset);
        }
        // TODO 【Question37】 为何要加上lendthFieldEndOffset呢？如何理解？
        frameLength += lengthAdjustment + lengthFieldEndOffset;
        // 若待解码内容长度小于lengthFieldEndOffset，同样需要抛出异常，同时需要将读指针readerIndex向右移动到lengthFieldEndOffset处，以免下次IO流的解码受影响
        if (frameLength < lengthFieldEndOffset) {
            failOnFrameLengthLessThanLengthFieldEndOffset(in, frameLength, lengthFieldEndOffset);
        }
        // 如果待解码内容长度大于指定的最大帧长度，则开启丢弃模式
        if (frameLength > maxFrameLength) {
            exceededFrameLength(in, frameLength);
            return null;
        }

        // never overflows because it's less than maxFrameLength
        // 能执行到这里frameLength肯定是小于等于maxFrameLength，而maxFrameLength又是int类型，可以直接转
        int frameLengthInt = (int) frameLength;
        // 若ByteBuf的累积字节数（剩余可读字节数）小于待解码内容长度，此时直接返回null，等待下一次IO流到来累积多点字节再继续解码
        if (in.readableBytes() < frameLengthInt) {
            return null;
        }
        // 若待解码内容长度小于要跳过的初始字节数initialBytesToStrip，此时直接抛异常，并将ByteBuf的readerIndex指针向右移动frameLengthInt个字节
        if (initialBytesToStrip > frameLengthInt) {
            failOnFrameLengthLessThanInitialBytesToStrip(in, frameLength, initialBytesToStrip);
        }
        // 因为待解码内容要跳过initialBytesToStrip个字节
        in.skipBytes(initialBytesToStrip);

        // extract frame
        int readerIndex = in.readerIndex();
        // 将待解码内容长度减去要跳过的初始字节数得到真正待解码内容的字节长度actualFrameLength
        int actualFrameLength = frameLengthInt - initialBytesToStrip;
        // 最终截取实际待解码内容的ByteBuf，因为这里不会改变ByteBuf的读写指针
        ByteBuf frame = extractFrame(ctx, in, readerIndex, actualFrameLength);
        // 将ByteBuf的读指针设置为读取解码内容后的位置
        in.readerIndex(readerIndex + actualFrameLength);
        return frame;
    }

    /**
     * Decodes the specified region of the buffer into an unadjusted frame length.  The default implementation is
     * capable of decoding the specified region into an unsigned 8/16/24/32/64 bit integer.  Override this method to
     * decode the length field encoded differently.  Note that this method must not modify the state of the specified
     * buffer (e.g. {@code readerIndex}, {@code writerIndex}, and the content of the buffer.)
     *
     * @throws DecoderException if failed to decode the specified region
     */
    protected long getUnadjustedFrameLength(ByteBuf buf, int offset, int length, ByteOrder order) {
        buf = buf.order(order);
        long frameLength;
        // 根据lengthField的长度来获取待解码内容的长度
        switch (length) {
        case 1:
            frameLength = buf.getUnsignedByte(offset);
            break;
        case 2:
            frameLength = buf.getUnsignedShort(offset);
            break;
        case 3:
            frameLength = buf.getUnsignedMedium(offset);
            break;
        case 4:
            frameLength = buf.getUnsignedInt(offset);
            break;
        case 8:
            frameLength = buf.getLong(offset);
            break;
        default:
            throw new DecoderException(
                    "unsupported lengthFieldLength: " + lengthFieldLength + " (expected: 1, 2, 3, 4, or 8)");
        }
        return frameLength;
    }

    private void failIfNecessary(boolean firstDetectionOfTooLongFrame) {
        // 若bytesToDiscard == 0则说明ByteBuf的剩余可读字节数刚好与要丢弃的字节数frameLength相等，刚好抵消即刚好把要丢弃的完整消息给丢弃完，此时需要关闭丢弃模式
        if (bytesToDiscard == 0) {
            // Reset to the initial state and tell the handlers that
            // the frame was too large.
            long tooLongFrameLength = this.tooLongFrameLength;
            this.tooLongFrameLength = 0;
            discardingTooLongFrame = false;
            if (!failFast || firstDetectionOfTooLongFrame) {
                fail(tooLongFrameLength);
            }
        // 若bytesToDiscard不等于0则说明ByteBuf的剩余可读字节数还不够用来丢弃，则要等待下一次IO流到来继续丢弃，此时若开启了failFast模式则先直接失败抛出异常
        } else {
            // Keep discarding and notify handlers if necessary.
            if (failFast && firstDetectionOfTooLongFrame) {
                fail(tooLongFrameLength);
            }
        }
    }

    /**
     * Extract the sub-region of the specified buffer.
     */
    protected ByteBuf extractFrame(ChannelHandlerContext ctx, ByteBuf buffer, int index, int length) {
        // 返回最终要截取的真正字节数的ByteBuf，注意retainedSlice方法不会改变读指针readerIndex或写指针writerIndex
        // TODO 【Question36】这里为何要用不会改变读写指针的retainedSlice方法呢
        return buffer.retainedSlice(index, length);
    }

    private void fail(long frameLength) {
        if (frameLength > 0) {
            throw new TooLongFrameException(
                            "Adjusted frame length exceeds " + maxFrameLength +
                            ": " + frameLength + " - discarded");
        } else {
            throw new TooLongFrameException(
                            "Adjusted frame length exceeds " + maxFrameLength +
                            " - discarding");
        }
    }
}
