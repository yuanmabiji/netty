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
import io.netty.util.concurrent.EventExecutor;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.FutureListener;
import io.netty.util.concurrent.GlobalEventExecutor;
import io.netty.util.concurrent.Promise;
import io.netty.util.internal.ObjectUtil;

import java.nio.channels.ClosedChannelException;
import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.Callable;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * {@link ChannelPool} implementation that takes another {@link ChannelPool} implementation and enforce a maximum
 * number of concurrent connections.
 */
public class FixedChannelPool extends SimpleChannelPool {

    /**
     * 这是一个获取连接超时的策略
     */
    public enum AcquireTimeoutAction {
        /**
         * Create a new connection when the timeout is detected.
         * 新建策略：当检测到获取连接超时时，此时新建一个连接
         */
        NEW,

        /**
         * Fail the {@link Future} of the acquire call with a {@link TimeoutException}.
         * 快速失败策略：当检测到获取连接超时时，此时抛出一个异常
         */
        FAIL
    }
    // EventExecutor是一个特殊的EventExecutorGroup，提供了一些判断某个线程是否已经在event loop执行的便利方法
    private final EventExecutor executor;
    // 从连接池获取channel连接的超时时间
    private final long acquireTimeoutNanos;
    // 若从连接池获取channel连接超时时，且有设置AcquireTimeoutAction策略的话，此时需要执行的超时处理任务
    private final Runnable timeoutTask;

    // There is no need to worry about synchronization as everything that modified the queue or counts is done
    // by the above EventExecutor.
    // 当连接池可用channel连接耗尽时，其他获取连接的线程会封装成一个AcquireTask，然后pendingAcquireQueue队列就是用来存储AcquireTask的
    private final Queue<AcquireTask> pendingAcquireQueue = new ArrayDeque<AcquireTask>();
    // 最大连接数，这里指的是netty连接池的最大数量，即连接池容量
    private final int maxConnections;
    // 这个是pendingAcquireQueue队列的大小，若瞬间请求线程数量大于maxConnections与maxPendingAcquires之和，那么会抛出异常
    private final int maxPendingAcquires;
    // 已经获取的连接数量（包括从连接池建立的连接及额外新建的连接），这里注意acquiredChannelCount是原子类型
    private final AtomicInteger acquiredChannelCount = new AtomicInteger();
    // 等待获取channel连接的数量，注意pendingAcquireCount不是原子类型
    // 【思考】为啥pendingAcquireCount可以不是原子类型，没有线程安全风险么？
    private int pendingAcquireCount;
    // FixedChannelPool是否关闭
    private boolean closed;

    /**
     * Creates a new instance using the {@link ChannelHealthChecker#ACTIVE}.
     *
     * @param bootstrap         the {@link Bootstrap} that is used for connections
     * @param handler           the {@link ChannelPoolHandler} that will be notified for the different pool actions
     * @param maxConnections    the number of maximal active connections, once this is reached new tries to acquire
     *                          a {@link Channel} will be delayed until a connection is returned to the pool again.
     */
    public FixedChannelPool(Bootstrap bootstrap,
                            ChannelPoolHandler handler, int maxConnections) {
        this(bootstrap, handler, maxConnections, Integer.MAX_VALUE);
    }

    /**
     * Creates a new instance using the {@link ChannelHealthChecker#ACTIVE}.
     *
     * @param bootstrap             the {@link Bootstrap} that is used for connections
     * @param handler               the {@link ChannelPoolHandler} that will be notified for the different pool actions
     * @param maxConnections        the number of maximal active connections, once this is reached new tries to
     *                              acquire a {@link Channel} will be delayed until a connection is returned to the
     *                              pool again.
     * @param maxPendingAcquires    the maximum number of pending acquires. Once this is exceed acquire tries will
     *                              be failed.
     */
    public FixedChannelPool(Bootstrap bootstrap,
                            ChannelPoolHandler handler, int maxConnections, int maxPendingAcquires) {
        this(bootstrap, handler, ChannelHealthChecker.ACTIVE, null, -1, maxConnections, maxPendingAcquires);
    }

    /**
     * Creates a new instance.
     *
     * @param bootstrap             the {@link Bootstrap} that is used for connections
     * @param handler               the {@link ChannelPoolHandler} that will be notified for the different pool actions
     * @param healthCheck           the {@link ChannelHealthChecker} that will be used to check if a {@link Channel} is
     *                              still healthy when obtain from the {@link ChannelPool}
     * @param action                the {@link AcquireTimeoutAction} to use or {@code null} if non should be used.
     *                              In this case {@param acquireTimeoutMillis} must be {@code -1}.
     * @param acquireTimeoutMillis  the time (in milliseconds) after which an pending acquire must complete or
     *                              the {@link AcquireTimeoutAction} takes place.
     * @param maxConnections        the number of maximal active connections, once this is reached new tries to
     *                              acquire a {@link Channel} will be delayed until a connection is returned to the
     *                              pool again.
     * @param maxPendingAcquires    the maximum number of pending acquires. Once this is exceed acquire tries will
     *                              be failed.
     */
    public FixedChannelPool(Bootstrap bootstrap,
                            ChannelPoolHandler handler,
                            ChannelHealthChecker healthCheck, AcquireTimeoutAction action,
                            final long acquireTimeoutMillis,
                            int maxConnections, int maxPendingAcquires) {
        this(bootstrap, handler, healthCheck, action, acquireTimeoutMillis, maxConnections, maxPendingAcquires, true);
    }

    /**
     * Creates a new instance.
     *
     * @param bootstrap             the {@link Bootstrap} that is used for connections
     * @param handler               the {@link ChannelPoolHandler} that will be notified for the different pool actions
     * @param healthCheck           the {@link ChannelHealthChecker} that will be used to check if a {@link Channel} is
     *                              still healthy when obtain from the {@link ChannelPool}
     * @param action                the {@link AcquireTimeoutAction} to use or {@code null} if non should be used.
     *                              In this case {@param acquireTimeoutMillis} must be {@code -1}.
     * @param acquireTimeoutMillis  the time (in milliseconds) after which an pending acquire must complete or
     *                              the {@link AcquireTimeoutAction} takes place.
     * @param maxConnections        the number of maximal active connections, once this is reached new tries to
     *                              acquire a {@link Channel} will be delayed until a connection is returned to the
     *                              pool again.
     * @param maxPendingAcquires    the maximum number of pending acquires. Once this is exceed acquire tries will
     *                              be failed.
     * @param releaseHealthCheck    will check channel health before offering back if this parameter set to
     *                              {@code true}.
     */
    public FixedChannelPool(Bootstrap bootstrap,
                            ChannelPoolHandler handler,
                            ChannelHealthChecker healthCheck, AcquireTimeoutAction action,
                            final long acquireTimeoutMillis,
                            int maxConnections, int maxPendingAcquires, final boolean releaseHealthCheck) {
        this(bootstrap, handler, healthCheck, action, acquireTimeoutMillis, maxConnections, maxPendingAcquires,
                releaseHealthCheck, true);
    }

    /**
     * Creates a new instance.
     *
     * @param bootstrap             the {@link Bootstrap} that is used for connections
     * @param handler               the {@link ChannelPoolHandler} that will be notified for the different pool actions
     * @param healthCheck           the {@link ChannelHealthChecker} that will be used to check if a {@link Channel} is
     *                              still healthy when obtain from the {@link ChannelPool}
     * @param action                the {@link AcquireTimeoutAction} to use or {@code null} if non should be used.
     *                              In this case {@param acquireTimeoutMillis} must be {@code -1}.
     * @param acquireTimeoutMillis  the time (in milliseconds) after which an pending acquire must complete or
     *                              the {@link AcquireTimeoutAction} takes place.
     * @param maxConnections        the number of maximal active connections, once this is reached new tries to
     *                              acquire a {@link Channel} will be delayed until a connection is returned to the
     *                              pool again.
     * @param maxPendingAcquires    the maximum number of pending acquires. Once this is exceed acquire tries will
     *                              be failed.
     * @param releaseHealthCheck    will check channel health before offering back if this parameter set to
     *                              {@code true}.
     * @param lastRecentUsed        {@code true} {@link Channel} selection will be LIFO, if {@code false} FIFO.
     */
    public FixedChannelPool(Bootstrap bootstrap,
                            ChannelPoolHandler handler,
                            ChannelHealthChecker healthCheck, AcquireTimeoutAction action,
                            final long acquireTimeoutMillis,
                            int maxConnections, int maxPendingAcquires,
                            boolean releaseHealthCheck, boolean lastRecentUsed) {
        super(bootstrap, handler, healthCheck, releaseHealthCheck, lastRecentUsed);
        if (maxConnections < 1) {
            throw new IllegalArgumentException("maxConnections: " + maxConnections + " (expected: >= 1)");
        }
        if (maxPendingAcquires < 1) {
            throw new IllegalArgumentException("maxPendingAcquires: " + maxPendingAcquires + " (expected: >= 1)");
        }
        // 这里表示初始化时获取连接超时action策略为null且acquireTimeoutMillis == -1
        if (action == null && acquireTimeoutMillis == -1) {
            timeoutTask = null;
            acquireTimeoutNanos = -1;
        // 做一些不合理的参数校验
        } else if (action == null && acquireTimeoutMillis != -1) {
            throw new NullPointerException("action");
        // 做一些不合理的参数校验
        } else if (action != null && acquireTimeoutMillis < 0) {
            throw new IllegalArgumentException("acquireTimeoutMillis: " + acquireTimeoutMillis + " (expected: >= 0)");
        // 执行到这里，表示action != null且acquireTimeoutMillis >= -1，即设置了获取连接超时的从处理策略
        } else {
            acquireTimeoutNanos = TimeUnit.MILLISECONDS.toNanos(acquireTimeoutMillis);
            // 判断是NEW还是FAIL策略
            switch (action) {
                // （1）如果是获取连接超时FAIL策略，当获取连接超时的话，此时如果pendingAcquireQueue队列中还有未能拿到连接的线程任务，此时直接失败抛异常，简单粗暴！！！
                case FAIL:
                    timeoutTask = new TimeoutTask() {
                        @Override
                        public void onTimeout(AcquireTask task) {
                            // Fail the promise as we timed out.
                            task.promise.setFailure(new TimeoutException(
                                    "Acquire operation took longer then configured maximum time") {
                                @Override
                                public Throwable fillInStackTrace() {
                                    return this;
                                }
                            });
                        }
                    };
                    break;
                // （2）如果是获取连接超时NEW策略，当获取连接超时的话，此时如果pendingAcquireQueue队列中还有未能拿到连接的线程任务，
                // 此时会为这些获取连接的线程任务新建连接，这里理性一点，到时如果设置pendingAcquireCount过大，在高并发情况下会导致大量连接创建
                // 有着耗尽资源的风险
                case NEW:
                    timeoutTask = new TimeoutTask() {
                        @Override
                        public void onTimeout(AcquireTask task) {
                            // Increment the acquire count and delegate to super to actually acquire a Channel which will
                            // create a new connection.
                            // acquiredChannelCount获取的连接数+1且给acquired赋值true
                            task.acquired();
                            // 调用父类SimpleChannelPool.acquire来创建一直新连接
                            FixedChannelPool.super.acquire(task.promise);
                        }
                    };
                    break;
                default:
                    throw new Error();
                }
        }
        executor = bootstrap.config().group().next();
        this.maxConnections = maxConnections;
        this.maxPendingAcquires = maxPendingAcquires;
    }

    /** Returns the number of acquired channels that this pool thinks it has. */
    public int acquiredChannelCount() {
        return acquiredChannelCount.get();
    }

    @Override
    public Future<Channel> acquire(final Promise<Channel> promise) {
        try {
            // 如果当前线程是executor的线程，那么就直接调用acquire0方法获取连接
            if (executor.inEventLoop()) {
                acquire0(promise);
            // 如果当前线程不是executor的线程，那么就新开一个线程调用acquire0方法获取连接
            } else {
                executor.execute(new Runnable() {
                    @Override
                    public void run() {
                        acquire0(promise);
                    }
                });
            }
        } catch (Throwable cause) {
            // 出现异常，设置失败回调
            promise.setFailure(cause);
        }
        // 返回保证，这里的保证是能拿到Channel，Promise继承了Future
        return promise;
    }

    private void acquire0(final Promise<Channel> promise) {
        assert executor.inEventLoop();
        // 判断FixedChannelPool连接池是否已经关闭
        if (closed) {
            promise.setFailure(new IllegalStateException("FixedChannelPool was closed"));
            return;
        }
        // 【1】如果已经获取的连接数量acquiredChannelCount小于Channel连接池的数量，说明连接池还有可用连接，因此这里直接从池子里取连接即可
        // 注意：acquiredChannelCount是从0开始计数的哈
        if (acquiredChannelCount.get() < maxConnections) {
            assert acquiredChannelCount.get() >= 0;

            // We need to create a new promise as we need to ensure the AcquireListener runs in the correct
            // EventLoop
            Promise<Channel> p = executor.newPromise();
            // 新建一个AcquireListener，这个AcquireListener是FixedChannelPool的一个内部类，
            // TODO 用来当获取到连接回调其内部的operationComplete方法？
            AcquireListener l = new AcquireListener(promise);
            // 调用AcquireListener的acquired方法，在获取到连接前先给acquiredChannelCount加1，
            // 大胆猜测，如果后续的获取连接若失败，肯定有acquiredChannelCount减1的代码
            l.acquired();
            // 给保证添加AcquireListener监听器
            p.addListener(l);
            // 这里还是调用父类SimpleChannelPool来获取连接，这里先提下父类SimpleChannelPool没有实现连接池数量控制的相关功能，
            // SimpleChannelPool只是实现了新建连接，健康检查等逻辑
            super.acquire(p);
        // 【2】获取连接时，能执行到这里，说明已经获取的连接数量acquiredChannelCount大于或等于Channel连接池的数量，
        // 即表明连接池无可用连接了，此时就需要根据有无设置AcquireTimeoutAction策略来执行相应的操作了
        } else {
            // 【2.1】如果等待获取连接数量pendingAcquireCount超过队列的最大容量maxPendingAcquires的话，此时直接抛异常
            if (pendingAcquireCount >= maxPendingAcquires) {
                tooManyOutstanding(promise);
            // 【2.2】若等待获取连接数量pendingAcquireCount还没占满pendingAcquireQueue队列
            } else {
                // 这里把等待获取连接的保证promise封装成AcquireTask任务
                AcquireTask task = new AcquireTask(promise);
                // 将之前封装的AcquireTask任务入pendingAcquireQueue队列,这里pendingAcquireQueue是一个ArrayDeque队列
                if (pendingAcquireQueue.offer(task)) {
                    // 入队成功，因此pendingAcquireCount自增1
                    ++pendingAcquireCount;
                    // 【重要】这里如果timeoutTask不为null，则说明要么设置了获取连接超时的处理策略，目前的netty连接池内置的策略中，要么为NEW，要么为FAIL
                    if (timeoutTask != null) {
                        // 设置了获取连接超时处理策略的话，那么把timeoutTask扔到定时任务里去，一旦获取连接超时，那么就执行timeoutTask
                        // 若策略为NEW，那么就会新建连接然后返回；若策略为FAIL，那么直接抛异常
                        task.timeoutFuture = executor.schedule(timeoutTask, acquireTimeoutNanos, TimeUnit.NANOSECONDS);
                    }
                // 执行到这里，说明前面入pendingAcquireQueue队列时队列已满，然后也直接抛异常
                } else {
                    tooManyOutstanding(promise);
                }
            }

            assert pendingAcquireCount > 0;
        }
    }

    private void tooManyOutstanding(Promise<?> promise) {
        promise.setFailure(new IllegalStateException("Too many outstanding acquire operations"));
    }

    @Override
    public Future<Void> release(final Channel channel, final Promise<Void> promise) {
        ObjectUtil.checkNotNull(promise, "promise");
        final Promise<Void> p = executor.newPromise();
        super.release(channel, p.addListener(new FutureListener<Void>() {

            @Override
            public void operationComplete(Future<Void> future) throws Exception {
                assert executor.inEventLoop();

                if (closed) {
                    // Since the pool is closed, we have no choice but to close the channel
                    channel.close();
                    promise.setFailure(new IllegalStateException("FixedChannelPool was closed"));
                    return;
                }

                if (future.isSuccess()) {
                    decrementAndRunTaskQueue();
                    promise.setSuccess(null);
                } else {
                    Throwable cause = future.cause();
                    // Check if the exception was not because of we passed the Channel to the wrong pool.
                    if (!(cause instanceof IllegalArgumentException)) {
                        decrementAndRunTaskQueue();
                    }
                    promise.setFailure(future.cause());
                }
            }
        }));
        return promise;
    }

    private void decrementAndRunTaskQueue() {
        // We should never have a negative value.
        int currentCount = acquiredChannelCount.decrementAndGet();
        assert currentCount >= 0;

        // Run the pending acquire tasks before notify the original promise so if the user would
        // try to acquire again from the ChannelFutureListener and the pendingAcquireCount is >=
        // maxPendingAcquires we may be able to run some pending tasks first and so allow to add
        // more.
        runTaskQueue();
    }

    private void runTaskQueue() {
        while (acquiredChannelCount.get() < maxConnections) {
            AcquireTask task = pendingAcquireQueue.poll();
            if (task == null) {
                break;
            }

            // Cancel the timeout if one was scheduled
            ScheduledFuture<?> timeoutFuture = task.timeoutFuture;
            if (timeoutFuture != null) {
                timeoutFuture.cancel(false);
            }

            --pendingAcquireCount;
            task.acquired();

            super.acquire(task.promise);
        }

        // We should never have a negative value.
        assert pendingAcquireCount >= 0;
        assert acquiredChannelCount.get() >= 0;
    }

    // AcquireTask extends AcquireListener to reduce object creations and so GC pressure
    private final class AcquireTask extends AcquireListener {
        final Promise<Channel> promise;
        final long expireNanoTime = System.nanoTime() + acquireTimeoutNanos;
        ScheduledFuture<?> timeoutFuture;

        AcquireTask(Promise<Channel> promise) {
            super(promise);
            // We need to create a new promise as we need to ensure the AcquireListener runs in the correct
            // EventLoop.
            this.promise = executor.<Channel>newPromise().addListener(this);
        }
    }

    private abstract class TimeoutTask implements Runnable {
        @Override
        public final void run() {
            assert executor.inEventLoop();
            long nanoTime = System.nanoTime();
            for (;;) {
                AcquireTask task = pendingAcquireQueue.peek();
                // Compare nanoTime as descripted in the javadocs of System.nanoTime()
                //
                // See https://docs.oracle.com/javase/7/docs/api/java/lang/System.html#nanoTime()
                // See https://github.com/netty/netty/issues/3705
                if (task == null || nanoTime - task.expireNanoTime < 0) {
                    break;
                }
                pendingAcquireQueue.remove();

                --pendingAcquireCount;
                onTimeout(task);
            }
        }

        public abstract void onTimeout(AcquireTask task);
    }

    private class AcquireListener implements FutureListener<Channel> {
        private final Promise<Channel> originalPromise;
        // 是否已经获取到了连接的标志
        protected boolean acquired;

        AcquireListener(Promise<Channel> originalPromise) {
            this.originalPromise = originalPromise;
        }

        @Override
        public void operationComplete(Future<Channel> future) throws Exception {
            assert executor.inEventLoop();

            if (closed) {
                if (future.isSuccess()) {
                    // Since the pool is closed, we have no choice but to close the channel
                    future.getNow().close();
                }
                originalPromise.setFailure(new IllegalStateException("FixedChannelPool was closed"));
                return;
            }

            if (future.isSuccess()) {
                originalPromise.setSuccess(future.getNow());
            } else {
                if (acquired) {
                    decrementAndRunTaskQueue();
                } else {
                    runTaskQueue();
                }

                originalPromise.setFailure(future.cause());
            }
        }

        public void acquired() {
            if (acquired) {
                return;
            }
            acquiredChannelCount.incrementAndGet();
            acquired = true;
        }
    }

    @Override
    public void close() {
        try {
            closeAsync().await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }

    /**
     * Closes the pool in an async manner.
     *
     * @return Future which represents completion of the close task
     */
    @Override
    public Future<Void> closeAsync() {
        if (executor.inEventLoop()) {
            return close0();
        } else {
            final Promise<Void> closeComplete = executor.newPromise();
            executor.execute(new Runnable() {
                @Override
                public void run() {
                    close0().addListener(new FutureListener<Void>() {
                        @Override
                        public void operationComplete(Future<Void> f) throws Exception {
                            if (f.isSuccess()) {
                                closeComplete.setSuccess(null);
                            } else {
                                closeComplete.setFailure(f.cause());
                            }
                        }
                    });
                }
            });
            return closeComplete;
        }
    }

    private Future<Void> close0() {
        assert executor.inEventLoop();

        if (!closed) {
            closed = true;
            for (;;) {
                AcquireTask task = pendingAcquireQueue.poll();
                if (task == null) {
                    break;
                }
                ScheduledFuture<?> f = task.timeoutFuture;
                if (f != null) {
                    f.cancel(false);
                }
                task.promise.setFailure(new ClosedChannelException());
            }
            acquiredChannelCount.set(0);
            pendingAcquireCount = 0;

            // Ensure we dispatch this on another Thread as close0 will be called from the EventExecutor and we need
            // to ensure we will not block in a EventExecutor.
            return GlobalEventExecutor.INSTANCE.submit(new Callable<Void>() {
                @Override
                public Void call() throws Exception {
                    FixedChannelPool.super.close();
                    return null;
                }
            });
        }

        return GlobalEventExecutor.INSTANCE.newSucceededFuture(null);
    }
}
