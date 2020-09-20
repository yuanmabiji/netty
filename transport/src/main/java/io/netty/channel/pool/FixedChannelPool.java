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
        // 这个executor是用来获取连接的，总是同一个executor异步去获取连接
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
            // 如果当前线程是executor的线程，那么就直接调用acquire0方法获取连接，
            // 【注意】这里是异步去获取channel连接哈，如果调用future.get方法，只要连接没获取到，那么将一直阻塞，直到连接获取完成。
            if (executor.inEventLoop()) {
                acquire0(promise);
            // 如果当前线程不是executor的线程，那么就由executor这个线程调用acquire0方法获取连接,这里是异步获取连接哈
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
            // TODO [思考]大胆猜测，如果后续的获取连接若失败，肯定有acquiredChannelCount减1的代码，但是在哪里呢
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
                        // 这里调度超时任务后，然后再给task.timeoutFuture赋值，也是为了做标记的意思，因为后面一个线程释放连接后
                        // 会继续“唤醒”pendingAcquireQueue的一个任务，那时候这个任务肯定是未超时的，所以需要取消这个定时任务
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
        // 新建一个Promise
        final Promise<Void> p = executor.newPromise();
        // 然后再调用父类SimpleChannelPool的release(final Channel channel, final Promise<Void> promise)方法，
        // 【思考】这里为啥要这么绕呀？？？先是调用父类SimpleChannelPool的release(Channel channel)，然后在父类SimpleChannelPool的release方法
        // 中再调用本方法，而明明父类就有这个release(final Channel channel, final Promise<Void> promise)方法，为何不直接调用呢？？？
        //【答案】答案就是SimpleChannelPool只实现了连接池获取连接，释放连接和健康检查的相关基本方法，而连接释放回连接池后，我们是不是要唤醒
        // pendingAcquireQueue队列中的一个任务呢？是吧，因此下面就给Promise又添加了一个FutureListener监听器，这个监听器的作用就是当SimpleChannelPool的
        // release方法把连接放回连接池后，此时回调该监听器的operationComplete方法来唤醒pendingAcquireQueue里的一个任务，嘿嘿，是不是有点绕，哈哈
        super.release(channel, p.addListener(new FutureListener<Void>() {

            @Override
            public void operationComplete(Future<Void> future) throws Exception {
                assert executor.inEventLoop();
                // 以为连接池已经关闭，我们没得选择只能关闭channel，然后回调setFailure反弹广发
                if (closed) {
                    // Since the pool is closed, we have no choice but to close the channel
                    channel.close();
                    promise.setFailure(new IllegalStateException("FixedChannelPool was closed"));
                    return;
                }
                // （1）如果释放连接回连接池成功
                // TODO【思考】这个future是只哪个future呢？你能找到这个future是从哪里传进来的吗？嘿嘿嘿
                if (future.isSuccess()) {
                    // 那么此时就要就获取的连接数量acquiredChannelCount减1且“唤醒”pendingAcquireQueue队列的一个待获取连接的一个任务
                    // 还记得之前分析acquire源码时当连接池无可用连接时，此时会将这个获取连接的一个线程封装成一个AcquireTask任务放进pendingAcquireQueue队列吗？
                    decrementAndRunTaskQueue();
                    // 回到setSuccess方法
                    promise.setSuccess(null);
                // （2）如果连接没有成功释放回连接池，且没有还错池子的情况下发生了异常，那么这里同样需要获取的连接数量acquiredChannelCount减1
                // 且“唤醒”pendingAcquireQueue队列的一个待获取连接的一个任务
                // TODO 纳尼？？这里还可以多个池子？为啥没还错池子的情况发生了归还连接的时候发生异常就不用 decrementAndRunTaskQueue呢？二十直接调用setFailure方法，
                //  这个setFailure方法又因此着什么逻辑呢？
                } else {
                    Throwable cause = future.cause();
                    // Check if the exception was not because of we passed the Channel to the wrong pool.
                    if (!(cause instanceof IllegalArgumentException)) {
                        decrementAndRunTaskQueue();
                    }
                    // 回调setFailure方法
                    promise.setFailure(future.cause());
                }
            }
        }));
        return promise;
    }

    private void decrementAndRunTaskQueue() {
        // We should never have a negative value.
        // 因为前面已经把连接归还回连接池了，自然这里会将已获取的连接数量减1
        int currentCount = acquiredChannelCount.decrementAndGet();
        assert currentCount >= 0;

        // Run the pending acquire tasks before notify the original promise so if the user would
        // try to acquire again from the ChannelFutureListener and the pendingAcquireCount is >=
        // maxPendingAcquires we may be able to run some pending tasks first and so allow to add
        // more.
        // 然后“唤醒”pendingAcquireQueue队列的一个待获取连接的一个任务去连接池拿连接或者新建一个连接出来
        runTaskQueue();
    }

    private void runTaskQueue() {
        // c)这里非超时任务应该留给连接池的可用连接去处理哈，因为这里pendingAcquireQueue里的任务本来就是因为连接池资源耗尽的情况下，
        //      其余获取连接的任务才入pendingAcquireQueue队列的，因此当一个线程用完从连接池获取的连接后，这个线程把连接归还给连接池后，
        //      这个线程首先判断连接池还有无可用连接，若连接池还有可用连接，那么其有义务有“唤醒”pendingAcquireQueue队列中的一个未超时的任务，
        //      这个任务被唤醒后，然后再去连接池获取连接即可

        // 如果acquiredChannelCount小于连接池数量，说明连接池还有可用连接
        // TODO 【思考】这里while (acquiredChannelCount.get() < maxConnections)判断的初衷感觉像是一定要从连接池获取一个连接，
        //      而不是新建一个连接，否则就不用这么判断了。那么问题来了：
        //      while (acquiredChannelCount.get() < maxConnections)没有线程安全问题么？？？如果不用锁的话可能会出现“一票多卖”问题
        //      除非这里是单线程执行就没有线程安全问题。
        //      如果存在线程安全问题，当并发量大的话出现“一票多卖问题”，即最终还会导致连接池可用连接耗尽，其他没能拿到连接的线程还是会新建
        //      一些连接出来，这么做可是可以，但却又违反了“未超时任务的连接只能等待线程池的连接，超时任务再由定时任务额外新建连接”的初衷，
        //      因为执行到这里从pendingAcquireQueue队列取出的任务的一般都是未超时的。
        //      答案这里应该是单线程执行？待确认？调试的时候发现基本是同一个线程
        //
        while (acquiredChannelCount.get() < maxConnections) {
            // 取出一个待获取连接的未超时的任务，因为如果是超时的获取连接任务的话，已经被定时任务移除掉了哈
            AcquireTask task = pendingAcquireQueue.poll();
            // 若队列里没有待获取连接的任务，直接跳出即可
            if (task == null) {
                break;
            }
            // 如果当初有设置定时任务清理超时的带获取连接任务，那么此时timeoutFuture不为Null，因此需要取消这个定时任务的执行
            // Cancel the timeout if one was scheduled
            ScheduledFuture<?> timeoutFuture = task.timeoutFuture;
            if (timeoutFuture != null) {
                timeoutFuture.cancel(false);
            }
            // pendingAcquireCount减1
            --pendingAcquireCount;
            // acquiredChannelCount加1
            task.acquired();
            // 调用父类SimpleChannelPool的acquire方法：
            // 1）连接池有可用连接，从连接池取出即可；
            // 2）连接池没有可用连接，此时直接NEW一个连接出来
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
            // 获取系统当前时间
            long nanoTime = System.nanoTime();
            // 进入死循环
            for (;;) {
                // 从pendingAcquireQueue队列中获取一个待获取连接的任务，【注意】这里是peek哈，相当于查询，而不会移除队列中的元素
                // 【思考】天哪，这里是死循环+查询队列的操作，那当一个获取连接超时定时任务到来时，岂不会将pendingAcquireQueue队列中的
                // 所有任务（包括未timeout的任务）都查出来？可以肯定的是这里确实是这样子，答案见后面代码注释分析
                AcquireTask task = pendingAcquireQueue.peek();
                // Compare nanoTime as descripted in the javadocs of System.nanoTime()
                //
                // See https://docs.oracle.com/javase/7/docs/api/java/lang/System.html#nanoTime()
                // See https://github.com/netty/netty/issues/3705  这里估计出现过bug，后面修复了，嘿嘿。以后有空再去看看这个issue
                // （1）如果从pendingAcquireQueue队列获取的任务为空，那么则说明没有待获取连接的任务了，此时直接break；
                // (2) 如果从pendingAcquireQueue队列获取的任务不为空，此时肯定不能直接进行remove操作吧，想想，此时pendingAcquireQueue队列里
                // 是不是有可能还有未超时的任务，
                // 2.1）因此下面需要执行nanoTime - task.expireNanoTime是不是小于0，如果小于0直接break，等这个任务超时时再来执行这里的代码，
                // 想想如果这里将非超时的任务也一起取出来去执行也不是不可以，想想这里不这样做的原因如下：
                //      a)这里的职责是专门处理获取连接超时任务的，如果这里也执行非超时任务，那么造成功能混乱；
                //      b)若本来超时任务就多，此时又加上处理非超时任务的话，那么系统压力会更大
                //      c)这里非超时任务应该留给连接池的可用连接去处理哈，因为这里pendingAcquireQueue里的任务本来就是因为连接池资源耗尽的情况下，
                //      其余获取连接的任务才入pendingAcquireQueue队列的，因此当一个线程用完从连接池获取的连接后，这个线程把连接归还给连接池后，
                //      这个线程首先判断连接池还有无可用连接，若连接池还有可用连接，那么其有义务有“唤醒”pendingAcquireQueue队列中的一个未超时的任务，
                //      这个任务被唤醒后，然后再去连接池获取连接即可
                // 2.2）如果大于等于0，那么就根据是NEW还是FAIL策略来执行这个获取连接超时任务了
                if (task == null || nanoTime - task.expireNanoTime < 0) {
                    break;
                }
                // 执行到这里，说明获取连接任务确实超时了，因此可以将这个任务直接从pendingAcquireQueue队列移除了哈
                pendingAcquireQueue.remove();
                // 自然，pendingAcquireCount也会减1
                --pendingAcquireCount;
                // 还记得FixedChannelPool的一个构造方法最终会根据AcquireTimeoutAction的NEW还是FAIL策略来新建一个TimeoutTask，
                // 然后当获取连接时连接池又无可用连接情况下，此时除了获取连接任务会入pendingAcquireQueue队列外，另外TimeoutTask也会交给
                // 一个定时任务调度线程线程去执行，还记得么？
                // 那么代码执行到这里，说明已经在这个定时任务的调度方面里面了，此时再回调TimeoutTask的onTimeout方法哈
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
