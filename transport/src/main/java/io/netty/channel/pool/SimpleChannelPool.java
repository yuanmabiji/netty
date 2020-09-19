/*
 * Copyright 2015 The Netty Project
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
package io.netty.channel.pool;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoop;
import io.netty.util.AttributeKey;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.FutureListener;
import io.netty.util.concurrent.GlobalEventExecutor;
import io.netty.util.concurrent.Promise;
import io.netty.util.internal.PlatformDependent;

import java.util.Deque;
import java.util.concurrent.Callable;

import static io.netty.util.internal.ObjectUtil.*;

/**
 * Simple {@link ChannelPool} implementation which will create new {@link Channel}s if someone tries to acquire
 * a {@link Channel} but none is in the pool atm. No limit on the maximal concurrent {@link Channel}s is enforced.
 *
 * This implementation uses LIFO order for {@link Channel}s in the {@link ChannelPool}.
 *
 */
public class SimpleChannelPool implements ChannelPool {
    private static final AttributeKey<SimpleChannelPool> POOL_KEY =
        AttributeKey.newInstance("io.netty.channel.pool.SimpleChannelPool");
    private final Deque<Channel> deque = PlatformDependent.newConcurrentDeque();
    private final ChannelPoolHandler handler;
    private final ChannelHealthChecker healthCheck;
    private final Bootstrap bootstrap;
    private final boolean releaseHealthCheck;
    private final boolean lastRecentUsed;

    /**
     * Creates a new instance using the {@link ChannelHealthChecker#ACTIVE}.
     *
     * @param bootstrap         the {@link Bootstrap} that is used for connections
     * @param handler           the {@link ChannelPoolHandler} that will be notified for the different pool actions
     */
    public SimpleChannelPool(Bootstrap bootstrap, final ChannelPoolHandler handler) {
        this(bootstrap, handler, ChannelHealthChecker.ACTIVE);
    }

    /**
     * Creates a new instance.
     *
     * @param bootstrap         the {@link Bootstrap} that is used for connections
     * @param handler           the {@link ChannelPoolHandler} that will be notified for the different pool actions
     * @param healthCheck       the {@link ChannelHealthChecker} that will be used to check if a {@link Channel} is
     *                          still healthy when obtain from the {@link ChannelPool}
     */
    public SimpleChannelPool(Bootstrap bootstrap, final ChannelPoolHandler handler, ChannelHealthChecker healthCheck) {
        this(bootstrap, handler, healthCheck, true);
    }

    /**
     * Creates a new instance.
     *
     * @param bootstrap          the {@link Bootstrap} that is used for connections
     * @param handler            the {@link ChannelPoolHandler} that will be notified for the different pool actions
     * @param healthCheck        the {@link ChannelHealthChecker} that will be used to check if a {@link Channel} is
     *                           still healthy when obtain from the {@link ChannelPool}
     * @param releaseHealthCheck will check channel health before offering back if this parameter set to {@code true};
     *                           otherwise, channel health is only checked at acquisition time
     */
    public SimpleChannelPool(Bootstrap bootstrap, final ChannelPoolHandler handler, ChannelHealthChecker healthCheck,
                             boolean releaseHealthCheck) {
        this(bootstrap, handler, healthCheck, releaseHealthCheck, true);
    }

    /**
     * Creates a new instance.
     *
     * @param bootstrap          the {@link Bootstrap} that is used for connections
     * @param handler            the {@link ChannelPoolHandler} that will be notified for the different pool actions
     * @param healthCheck        the {@link ChannelHealthChecker} that will be used to check if a {@link Channel} is
     *                           still healthy when obtain from the {@link ChannelPool}
     * @param releaseHealthCheck will check channel health before offering back if this parameter set to {@code true};
     *                           otherwise, channel health is only checked at acquisition time
     * @param lastRecentUsed    {@code true} {@link Channel} selection will be LIFO, if {@code false} FIFO.
     */
    public SimpleChannelPool(Bootstrap bootstrap, final ChannelPoolHandler handler, ChannelHealthChecker healthCheck,
                             boolean releaseHealthCheck, boolean lastRecentUsed) {
        this.handler = checkNotNull(handler, "handler");
        this.healthCheck = checkNotNull(healthCheck, "healthCheck");
        this.releaseHealthCheck = releaseHealthCheck;
        // Clone the original Bootstrap as we want to set our own handler
        this.bootstrap = checkNotNull(bootstrap, "bootstrap").clone();
        this.bootstrap.handler(new ChannelInitializer<Channel>() {
            @Override
            protected void initChannel(Channel ch) throws Exception {
                assert ch.eventLoop().inEventLoop();
                handler.channelCreated(ch);
            }
        });
        this.lastRecentUsed = lastRecentUsed;
    }

    /**
     * Returns the {@link Bootstrap} this pool will use to open new connections.
     *
     * @return the {@link Bootstrap} this pool will use to open new connections
     */
    protected Bootstrap bootstrap() {
        return bootstrap;
    }

    /**
     * Returns the {@link ChannelPoolHandler} that will be notified for the different pool actions.
     *
     * @return the {@link ChannelPoolHandler} that will be notified for the different pool actions
     */
    protected ChannelPoolHandler handler() {
        return handler;
    }

    /**
     * Returns the {@link ChannelHealthChecker} that will be used to check if a {@link Channel} is healthy.
     *
     * @return the {@link ChannelHealthChecker} that will be used to check if a {@link Channel} is healthy
     */
    protected ChannelHealthChecker healthChecker() {
        return healthCheck;
    }

    /**
     * Indicates whether this pool will check the health of channels before offering them back into the pool.
     *
     * @return {@code true} if this pool will check the health of channels before offering them back into the pool, or
     * {@code false} if channel health is only checked at acquisition time
     */
    protected boolean releaseHealthCheck() {
        return releaseHealthCheck;
    }

    @Override
    public final Future<Channel> acquire() {
        return acquire(bootstrap.config().group().next().<Channel>newPromise());
    }

    @Override
    public Future<Channel> acquire(final Promise<Channel> promise) {
        // 这里直接调用acquireHealthyFromPoolOrNew方法，根据名字就可以知道先从池子拿连接，如果连接是健康的，那么说明可用，然后返回；
        // 如果连接不健康，那么就重新创建一个健康的Channel连接吧
        return acquireHealthyFromPoolOrNew(checkNotNull(promise, "promise"));
    }

    /**
     * Tries to retrieve healthy channel from the pool if any or creates a new channel otherwise.
     * @param promise the promise to provide acquire result.
     * @return future for acquiring a channel.
     */
    private Future<Channel> acquireHealthyFromPoolOrNew(final Promise<Channel> promise) {
        try {
            // 从连接池获取channel连接，即从deque队列中取出一个连接
            final Channel ch = pollChannel();
            // 这里是懒加载的思想，把初始化延迟到使用时
            // 如果获取的连接是null，那么说明连接池还没创建过channel连接，因此这里给创建一个
            // TODO 【思考】这里创建连接后并没有马上把这个连接归还到连接池，想想这个连接什么时候会被归还到连接池呢？
            if (ch == null) {
                // No Channel left in the pool bootstrap a new Channel
                // 克隆一个bootstrap对象出来
                Bootstrap bs = bootstrap.clone();
                bs.attr(POOL_KEY, this);
                // 调用connectChannel来获取一个Channel连接，注意这里是非阻塞的，调用完方法马上返回
                ChannelFuture f = connectChannel(bs);
                // 先判断future是否已经返回，若已返回，那么调用notifyConnect来回调ChannelPoolHandler的channelAcquired方法
                if (f.isDone()) {
                    notifyConnect(f, promise);
                // 执行到这里，说明future还没拿到正在创建的channel连接，此时为该future添加一个ChannelFutureListener监听器
                // 【重要】当channel连接创建完成时，会回调该监听器的operationComplete方法，到时再继续调用notifyConnect方法哈
                // 【思考】这个回调逻辑是怎样是实现的呢？？
                } else {
                    f.addListener(new ChannelFutureListener() {
                        @Override
                        public void operationComplete(ChannelFuture future) throws Exception {
                            notifyConnect(future, promise);
                        }
                    });
                }
                // 返回promise
                return promise;
            }
            // 代码执行到这里，表示从连接池获取了一个channel连接
            EventLoop loop = ch.eventLoop();
            // 如果当前线程是loop的线程，那么就直接调用doHealthCheck方法来为这个连接做健康检查
            if (loop.inEventLoop()) {
                doHealthCheck(ch, promise);
            } else {
                loop.execute(new Runnable() {
                    @Override
                    public void run() {
                        doHealthCheck(ch, promise);
                    }
                });
            }
        } catch (Throwable cause) {
            promise.tryFailure(cause);
        }
        return promise;
    }

    private void notifyConnect(ChannelFuture future, Promise<Channel> promise) throws Exception {
        // 如果成功返回channel
        if (future.isSuccess()) {
            // 拿到channel
            Channel channel = future.channel();
            // 拿到channel后，回调ChannelPoolHandler的channelAcquired方法，
            // 当成功获取到连接时，我们可以实现ChannelPoolHandler的channelAcquired方法实现相关业务逻辑，当获取到连接时该方法会被回调
            handler.channelAcquired(channel);
            // 若promise.trySuccess(channel)返回false，则说明保证Promise在完成期间被调用了canceled等方法，此时需要释放刚才创建的连接
            if (!promise.trySuccess(channel)) {
                // Promise was completed in the meantime (like cancelled), just release the channel again
                release(channel);
            }
        // 否则，调用保证promise的tryFailure方法
        } else {
            promise.tryFailure(future.cause());
        }
    }

    private void doHealthCheck(final Channel ch, final Promise<Channel> promise) {
        assert ch.eventLoop().inEventLoop();
        // 对从连接池取出的连接做健康检查，接口ChannelHealthChecker内部有个默认叫ACTIVE的ChannelHealthChecker，
        // 默认对调用channel.isActive()方法来做健康检查，举个栗子，对于NioSocketChannel来说，如果ch.isOpen()且ch.isConnected()的话，那么该通道就是健康的
        Future<Boolean> f = healthCheck.isHealthy(ch);
        // 如果健康检查完毕，那么直接调用notifyHealthCheck方法来回调ChannelPoolHandler的channelAcquired方法等
        if (f.isDone()) {
            notifyHealthCheck(f, ch, promise);
        // 如果健康检查还没完成，那么添加FutureListener监听器，等健康检查完毕时再回调notifyHealthCheck方法
        } else {
            f.addListener(new FutureListener<Boolean>() {
                @Override
                public void operationComplete(Future<Boolean> future) throws Exception {
                    notifyHealthCheck(future, ch, promise);
                }
            });
        }
    }

    private void notifyHealthCheck(Future<Boolean> future, Channel ch, Promise<Channel> promise) {
        assert ch.eventLoop().inEventLoop();
        // 如果获取的channel通道健康检查通过
        if (future.isSuccess()) {
            if (future.getNow()) {
                try {
                    ch.attr(POOL_KEY).set(this);
                    handler.channelAcquired(ch);
                    // 执行到这里说明获取的连接是健康的，回调setSuccess方法
                    promise.setSuccess(ch);
                } catch (Throwable cause) {
                    // 如果抛出异常，那么关闭channel连接
                    closeAndFail(ch, cause, promise);
                }
            // 如果获取的通道是不健康的，那么关闭通道；且重新递归调用acquireHealthyFromPoolOrNew方法来从连接池获取一个连接或新建一个连接，
            // 总之方法执行到这里表明这个连接一定是从连接池拿出来的，此时这个拿出来的连接如果不可用，那么就新建一个连接
            // TODO 【思考】假如从连接池拿出这个连接真的不可用，假如再调用acquireHealthyFromPoolOrNew去连接池拿连接时无可用连接，
            //  此时就会创建一个连接，这个连接最终会补回连接池吗？
            } else {
                closeChannel(ch);
                acquireHealthyFromPoolOrNew(promise);
            }
        // 如果获取的通道是不健康的，那么关闭通道；且重新递归调用acquireHealthyFromPoolOrNew方法来从连接池获取一个连接或新建一个连接，
        // 总之方法执行到这里表明这个连接一定是从连接池拿出来的，此时这个拿出来的连接如果不可用，那么就新建一个连接
        // TODO 【思考】假如从连接池拿出这个连接真的不可用，假如再调用acquireHealthyFromPoolOrNew去连接池拿连接时无可用连接，
        //  此时就会创建一个连接，这个连接最终会补回连接池吗？
        } else {
            closeChannel(ch);
            acquireHealthyFromPoolOrNew(promise);
        }
    }

    /**
     * Bootstrap a new {@link Channel}. The default implementation uses {@link Bootstrap#connect()}, sub-classes may
     * override this.
     * <p>
     * The {@link Bootstrap} that is passed in here is cloned via {@link Bootstrap#clone()}, so it is safe to modify.
     */
    protected ChannelFuture connectChannel(Bootstrap bs) {
        return bs.connect();
    }

    @Override
    public final Future<Void> release(Channel channel) {
        // 这里如果连接池是FixedChannelPool的话，这里实质调用的是FixedChannelPool的release(final Channel channel, final Promise<Void> promise)方法，
        // 因为FixedChannelPool重载了SimpleChannelPool的release(final Channel channel, final Promise<Void> promise)方法
        return release(channel, channel.eventLoop().<Void>newPromise());
    }

    @Override
    public Future<Void> release(final Channel channel, final Promise<Void> promise) {
        checkNotNull(channel, "channel");
        checkNotNull(promise, "promise");
        try {
            EventLoop loop = channel.eventLoop();
            if (loop.inEventLoop()) {
                doReleaseChannel(channel, promise);
            } else {
                loop.execute(new Runnable() {
                    @Override
                    public void run() {
                        doReleaseChannel(channel, promise);
                    }
                });
            }
        } catch (Throwable cause) {
            closeAndFail(channel, cause, promise);
        }
        return promise;
    }

    private void doReleaseChannel(Channel channel, Promise<Void> promise) {
        assert channel.eventLoop().inEventLoop();
        // Remove the POOL_KEY attribute from the Channel and check if it was acquired from this pool, if not fail.
        if (channel.attr(POOL_KEY).getAndSet(null) != this) {
            closeAndFail(channel,
                         // Better include a stacktrace here as this is an user error.
                         new IllegalArgumentException(
                                 "Channel " + channel + " was not acquired from this ChannelPool"),
                         promise);
        } else {
            try {
                if (releaseHealthCheck) {
                    doHealthCheckOnRelease(channel, promise);
                } else {
                    releaseAndOffer(channel, promise);
                }
            } catch (Throwable cause) {
                closeAndFail(channel, cause, promise);
            }
        }
    }

    private void doHealthCheckOnRelease(final Channel channel, final Promise<Void> promise) throws Exception {
        final Future<Boolean> f = healthCheck.isHealthy(channel);
        if (f.isDone()) {
            releaseAndOfferIfHealthy(channel, promise, f);
        } else {
            f.addListener(new FutureListener<Boolean>() {
                @Override
                public void operationComplete(Future<Boolean> future) throws Exception {
                    releaseAndOfferIfHealthy(channel, promise, f);
                }
            });
        }
    }

    /**
     * Adds the channel back to the pool only if the channel is healthy.
     * @param channel the channel to put back to the pool
     * @param promise offer operation promise.
     * @param future the future that contains information fif channel is healthy or not.
     * @throws Exception in case when failed to notify handler about release operation.
     */
    private void releaseAndOfferIfHealthy(Channel channel, Promise<Void> promise, Future<Boolean> future)
            throws Exception {
        if (future.getNow()) { //channel turns out to be healthy, offering and releasing it.
            releaseAndOffer(channel, promise);
        } else { //channel not healthy, just releasing it.
            handler.channelReleased(channel);
            promise.setSuccess(null);
        }
    }

    private void releaseAndOffer(Channel channel, Promise<Void> promise) throws Exception {
        if (offerChannel(channel)) {
            handler.channelReleased(channel);
            promise.setSuccess(null);
        } else {
            closeAndFail(channel, new IllegalStateException("ChannelPool full") {
                @Override
                public Throwable fillInStackTrace() {
                    return this;
                }
            }, promise);
        }
    }

    private void closeChannel(Channel channel) {
        channel.attr(POOL_KEY).getAndSet(null);
        channel.close();
    }

    private void closeAndFail(Channel channel, Throwable cause, Promise<?> promise) {
        closeChannel(channel);
        promise.tryFailure(cause);
    }

    /**
     * Poll a {@link Channel} out of the internal storage to reuse it. This will return {@code null} if no
     * {@link Channel} is ready to be reused.
     *
     * Sub-classes may override {@link #pollChannel()} and {@link #offerChannel(Channel)}. Be aware that
     * implementations of these methods needs to be thread-safe!
     */
    protected Channel pollChannel() {
        return lastRecentUsed ? deque.pollLast() : deque.pollFirst();
    }

    /**
     * Offer a {@link Channel} back to the internal storage. This will return {@code true} if the {@link Channel}
     * could be added, {@code false} otherwise.
     *
     * Sub-classes may override {@link #pollChannel()} and {@link #offerChannel(Channel)}. Be aware that
     * implementations of these methods needs to be thread-safe!
     */
    protected boolean offerChannel(Channel channel) {
        return deque.offer(channel);
    }

    @Override
    public void close() {
        for (;;) {
            Channel channel = pollChannel();
            if (channel == null) {
                break;
            }
            // Just ignore any errors that are reported back from close().
            channel.close().awaitUninterruptibly();
        }
    }

    /**
     * Closes the pool in an async manner.
     *
     * @return Future which represents completion of the close task
     */
    public Future<Void> closeAsync() {
        // Execute close asynchronously in case this is being invoked on an eventloop to avoid blocking
        return GlobalEventExecutor.INSTANCE.submit(new Callable<Void>() {
            @Override
            public Void call() throws Exception {
                close();
                return null;
            }
        });
    }
}
