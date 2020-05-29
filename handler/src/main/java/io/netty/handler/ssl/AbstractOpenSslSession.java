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

import io.netty.util.internal.EmptyArrays;
import io.netty.util.internal.ObjectUtil;

import javax.net.ssl.SSLSessionBindingEvent;
import javax.net.ssl.SSLSessionBindingListener;
import java.util.HashMap;
import java.util.Map;

abstract class AbstractOpenSslSession implements OpenSslSession {

    private final OpenSslSessionContext sessionContext;
    private final String peerHost;
    private final int peerPort;
    private final OpenSslSessionId id;

    // lazy init for memory reasons
    private Map<String, Object> values;

    AbstractOpenSslSession(OpenSslSessionContext sessionContext, String peerHost, int peerPort, byte[] id) {
        this.sessionContext = sessionContext;
        this.peerHost = peerHost;
        this.peerPort = peerPort;
        this.id = new OpenSslSessionId(id);
    }

    @Override
    public final OpenSslSessionContext getSessionContext() {
        return sessionContext;
    }

    @Override
    public final void putValue(String name, Object value) {
        ObjectUtil.checkNotNull(name, "name");
        ObjectUtil.checkNotNull(value, "value");

        final Object old;
        synchronized (this) {
            Map<String, Object> values = this.values;
            if (values == null) {
                // Use size of 2 to keep the memory overhead small
                values = this.values = new HashMap<String, Object>(2);
            }
            old = values.put(name, value);
        }

        if (value instanceof SSLSessionBindingListener) {
            ((SSLSessionBindingListener) value).valueBound(new SSLSessionBindingEvent(this, name));
        }
        notifyUnbound(old, name);
    }

    @Override
    public final Object getValue(String name) {
        ObjectUtil.checkNotNull(name, "name");
        synchronized (this) {
            if (values == null) {
                return null;
            }
            return values.get(name);
        }
    }

    @Override
    public final void removeValue(String name) {
        ObjectUtil.checkNotNull(name, "name");

        final Object old;
        synchronized (this) {
            Map<String, Object> values = this.values;
            if (values == null) {
                return;
            }
            old = values.remove(name);
        }

        notifyUnbound(old, name);
    }

    @Override
    public final String[] getValueNames() {
        synchronized (this) {
            Map<String, Object> values = this.values;
            if (values == null || values.isEmpty()) {
                return EmptyArrays.EMPTY_STRINGS;
            }
            return values.keySet().toArray(new String[0]);
        }
    }

    private void notifyUnbound(Object value, String name) {
        if (value instanceof SSLSessionBindingListener) {
            ((SSLSessionBindingListener) value).valueUnbound(new SSLSessionBindingEvent(this, name));
        }
    }

    @Override
    public final String getPeerHost() {
        return peerHost;
    }

    @Override
    public final int getPeerPort() {
        return peerPort;
    }

    @Override
    public OpenSslSessionId sessionId() {
        return id;
    }

    @Override
    public final byte[] getId() {
        return sessionId().asBytes();
    }

    @Override
    public final int hashCode() {
        return sessionId().hashCode();
    }

    @Override
    public final boolean equals(Object obj) {
        if (!(obj instanceof AbstractOpenSslSession)) {
            return false;
        }
        return sessionId().equals(((AbstractOpenSslSession) obj).sessionId());
    }
}
