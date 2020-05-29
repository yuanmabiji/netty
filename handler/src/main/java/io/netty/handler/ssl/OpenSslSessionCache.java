/*
 * Copyright 2020 The Netty Project
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
package io.netty.handler.ssl;

import io.netty.internal.tcnative.SSLSessionCache;
import io.netty.util.internal.SystemPropertyUtil;

import javax.net.ssl.SSLSession;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

// TODO: Handle timeout
class OpenSslSessionCache implements SSLSessionCache {
    private static final int DEFAULT_CACHE_SIZE;
    static {
        int cacheSize = SystemPropertyUtil.getInt("javax.net.ssl.sessionCacheSize", 20480);
        if (cacheSize >= 0) {
            DEFAULT_CACHE_SIZE = cacheSize;
        } else {
            DEFAULT_CACHE_SIZE = 20480;
        }
    }

    private final OpenSslEngineMap engineMap;

    private final Map<OpenSslSessionId, OpenSslSession> sessions =
            new LinkedHashMap<OpenSslSessionId, OpenSslSession>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<OpenSslSessionId, OpenSslSession> eldest) {
            int maxSize = maximumCacheSize.get();
            if (maxSize >= 0 && this.size() > maxSize) {
                OpenSslSession session = eldest.getValue();
                sessionRemoved(session);
                free(session);
                return true;
            }
            return false;
        }
    };

    private final AtomicInteger maximumCacheSize = new AtomicInteger(DEFAULT_CACHE_SIZE);

    OpenSslSessionCache(OpenSslEngineMap engineMap) {
        this.engineMap = engineMap;
    }

    /**
     * Called once a new {@link OpenSslSession} was created.
     *
     * @param session the new session.
     * @return {@code true} if the session should be cached, {@code false} otherwise.
     */
    protected boolean sessionCreated(OpenSslSession session) {
        return true;
    }

    /**
     * Called once an {@link OpenSslSession} was removed from the cache.
     *
     * @param session the session to remove.
     */
    protected void sessionRemoved(OpenSslSession session) { }

    final void setSessionCacheSize(int size) {
        if (size < 0) {
            throw new IllegalArgumentException();
        }
        long oldSize = maximumCacheSize.getAndSet(size);
        if (oldSize < size) {
            // Just keep it simple for now and drain the whole cache.
            freeSessions();
        }
    }

    final int getSessionCacheSize() {
        return maximumCacheSize.get();
    }

    @Override
    public final boolean sessionCreated(long ssl, long sslSession) {
        ReferenceCountedOpenSslEngine engine = engineMap.get(ssl);
        if (engine == null) {
            return false;
        }

        synchronized (this) {
            OpenSslSession session = engine.setSession(sslSession);
            if (!sessionCreated(session)) {
                return false;
            }

            final OpenSslSession old = sessions.put(session.sessionId(), session);
            if (old != null) {
                sessionRemoved(old);
                free(old);
            }
        }
        return true;
    }

    @Override
    public final long getSession(long ssl, byte[] sessionId) {
        OpenSslSessionId id = new OpenSslSessionId(sessionId);
        synchronized (this) {
            OpenSslSession session = sessions.get(id);
            if (session == null) {
                return -1;
            }
            long nativeAddr = session.nativeAddr();
            if (nativeAddr ==  -1) {
                // Should only be used once
                sessions.remove(id);
                sessionRemoved(session);
                return -1;
            }
            if (!session.isValid()) {
                sessions.remove(id);
                sessionRemoved(session);
                free(session);
                return -1;
            }

            // This needs to happen in the synchronized block so we ensure we never destroy it before we incremented
            // the reference count.
            io.netty.internal.tcnative.SSLSession.upRef(nativeAddr);
            if (io.netty.internal.tcnative.SSLSession.shouldBeSingleUse(nativeAddr)) {
                // Should only be used once
                sessions.remove(id);
                sessionRemoved(session);
                io.netty.internal.tcnative.SSLSession.free(nativeAddr);
            }
            session.updateLastAccessedTime();
            return nativeAddr;
        }
    }

    final SSLSession getSession(byte[] bytes) {
        OpenSslSessionId id = new OpenSslSessionId(bytes);
        synchronized (this) {
            return sessions.get(id);
        }
    }

    final synchronized OpenSslSessionId[] getIds() {
        return sessions.keySet().toArray(new OpenSslSessionId[0]);
    }

    final synchronized void freeSessions() {
        final OpenSslSession[] sessionsArray;
        sessionsArray = sessions.values().toArray(new OpenSslSession[0]);
        sessions.clear();

        for (OpenSslSession session: sessionsArray) {
            sessionRemoved(session);
            free(session);
        }
    }

    private static void free(OpenSslSession session) {
        long pointer = session.free();
        if (pointer != -1) {
            io.netty.internal.tcnative.SSLSession.free(pointer);
        }
    }
}
