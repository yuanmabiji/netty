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
package io.netty.handler.timeout;

import io.netty.util.internal.PlatformDependent;

/**
 * 【设计模式之单例模式】
 * A {@link TimeoutException} raised by {@link ReadTimeoutHandler} when no data
 * was read within a certain period of time.
 */
public final class ReadTimeoutException extends TimeoutException {

    private static final long serialVersionUID = 169287984113283421L;
    // 【Question44】这里创建ReadTimeoutException为啥不用同步块？
    // 【Answer44】因为INSTANCE是静态变量，在jvm加载类的时候会默认为ReadTimeoutException这个类加上同步块，即这个类的初始化是线程安全的，
    //            而INSTANCE就是在初始化过程中被创建的
    public static final ReadTimeoutException INSTANCE = PlatformDependent.javaVersion() >= 7 ?
            new ReadTimeoutException(true) : new ReadTimeoutException();

    ReadTimeoutException() { }

    private ReadTimeoutException(boolean shared) {
        super(shared);
    }
}
