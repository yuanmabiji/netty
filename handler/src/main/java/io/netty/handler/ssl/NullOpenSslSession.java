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

import javax.net.ssl.SSLPeerUnverifiedException;
import javax.security.cert.X509Certificate;
import java.security.Principal;
import java.security.cert.Certificate;

final class NullOpenSslSession extends AbstractOpenSslSession {

    private final Certificate[] localCertificate;

    NullOpenSslSession(OpenSslSessionContext sessionContext, Certificate[] localCertificate) {
        super(sessionContext, null, -1, EmptyArrays.EMPTY_BYTES);
        this.localCertificate = localCertificate;
    }

    @Override
    public boolean isNullSession() {
        return true;
    }

    @Override
    public long nativeAddr() {
        return -1;
    }

    @Override
    public void setLocalCertificate(Certificate[] localCertificate) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void tryExpandApplicationBufferSize(int packetLengthDataOnly) {
        // NOOP
    }

    @Override
    public void setPacketBufferSize(int packetBufferSize) {
        // NOOP
    }

    @Override
    public void updateLastAccessedTime() {
        // NOOP
    }

    @Override
    public long free() {
        return -1;
    }

    @Override
    public long getCreationTime() {
        return 0;
    }

    @Override
    public long getLastAccessedTime() {
        return 0;
    }

    @Override
    public void invalidate() {
        // NOOP
    }

    @Override
    public boolean isValid() {
        return false;
    }

    @Override
    public Certificate[] getPeerCertificates() throws SSLPeerUnverifiedException {
        throw new SSLPeerUnverifiedException("NULL session");
    }

    @Override
    public Certificate[] getLocalCertificates() {
        if (localCertificate != null) {
            return localCertificate.clone();
        }
        return null;
    }

    @Override
    public X509Certificate[] getPeerCertificateChain() throws SSLPeerUnverifiedException {
        throw new SSLPeerUnverifiedException("NULL session");
    }

    @Override
    public Principal getPeerPrincipal() throws SSLPeerUnverifiedException {
        throw new SSLPeerUnverifiedException("NULL session");
    }

    @Override
    public Principal getLocalPrincipal() {
        return null;
    }

    @Override
    public String getCipherSuite() {
        return SslUtils.INVALID_CIPHER;
    }

    @Override
    public String getProtocol() {
        return "NONE";
    }

    @Override
    public int getPacketBufferSize() {
        return ReferenceCountedOpenSslEngine.MAX_RECORD_SIZE;
    }

    @Override
    public int getApplicationBufferSize() {
        return ReferenceCountedOpenSslEngine.MAX_PLAINTEXT_LENGTH;
    }
}
