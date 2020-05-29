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
import io.netty.util.internal.StringUtil;

import javax.net.ssl.SSLPeerUnverifiedException;
import javax.security.cert.X509Certificate;
import java.security.Principal;
import java.security.cert.Certificate;

final class DefaultOpenSslSession extends AbstractOpenSslSession  {
    private final X509Certificate[] x509PeerCerts;
    private final Certificate[] peerCerts;
    private final String protocol;
    private final String cipher;
    private final long sslSession;
    private final long creationTime;

    private volatile int applicationBufferSize = ReferenceCountedOpenSslEngine.MAX_PLAINTEXT_LENGTH;
    private volatile int packetBufferSize = ReferenceCountedOpenSslEngine.MAX_RECORD_SIZE;
    private volatile long lastAccessed;
    private volatile Certificate[] localCertificateChain;

    // Guarded by synchronized(this)
    private boolean freed;

    DefaultOpenSslSession(OpenSslSessionContext sessionContext, String peerHost, int peerPort, long sslSession,
                          String version,
                          String cipher,
                          OpenSslJavaxX509Certificate[] x509PeerCerts,
                          long creationTime) {
        super(sessionContext, peerHost, peerPort, id(sslSession));
        this.sslSession = sslSession;
        this.cipher = cipher == null ? SslUtils.INVALID_CIPHER : cipher;
        this.x509PeerCerts = x509PeerCerts;
        if (x509PeerCerts != null) {
            peerCerts = new Certificate[x509PeerCerts.length];
            for (int i = 0; i < peerCerts.length; i++) {
                peerCerts[i] = new OpenSslX509Certificate(x509PeerCerts[i].getBytes());
            }
        } else {
            peerCerts = null;
        }
        this.protocol = version == null ? StringUtil.EMPTY_STRING : version;
        this.creationTime = creationTime;
        this.lastAccessed = creationTime;
    }

    private static byte[] id(long sslSession) {
        if (sslSession == -1) {
            return EmptyArrays.EMPTY_BYTES;
        }
        byte[] id = io.netty.internal.tcnative.SSLSession.getSessionId(sslSession);
        return id == null ? EmptyArrays.EMPTY_BYTES : id;
    }

    @Override
    public boolean isNullSession() {
        return false;
    }

    @Override
    public long getCreationTime() {
        return creationTime;
    }

    @Override
    public long getLastAccessedTime() {
        return lastAccessed;
    }

    @Override
    public void invalidate() {
        if (sslSession == -1) {
            return;
        }
        synchronized (this) {
            if (!freed) {
                io.netty.internal.tcnative.SSLSession.setTimeout(sslSession, 0);
            }
        }
    }

    @Override
    public boolean isValid() {
        if (sslSession == -1) {
            return false;
        }
        long current = System.currentTimeMillis();
        synchronized (this) {
            if (!freed) {
                return current -
                        (io.netty.internal.tcnative.SSLSession.getTimeout(sslSession) * 1000L)
                        < getCreationTime();
            }
        }
        return false;
    }

    @Override
    public long nativeAddr() {
        return sslSession;
    }

    @Override
    public void setLocalCertificate(Certificate[] localCertificate) {
        this.localCertificateChain = localCertificate;
    }

    @Override
    public void setPacketBufferSize(int packetBufferSize) {
        this.packetBufferSize = packetBufferSize;
    }

    @Override
    public void updateLastAccessedTime() {
        if (lastAccessed == -1) {
            lastAccessed = System.currentTimeMillis();
        }
    }

    @Override
    public synchronized long free() {
        long sslSession = this.sslSession;
        freed = true;
        return sslSession;
    }

    @Override
    public Certificate[] getPeerCertificates() throws SSLPeerUnverifiedException {
        if (SslUtils.isEmpty(peerCerts)) {
            throw new SSLPeerUnverifiedException("peer not verified");
        }
        return peerCerts.clone();
    }

    @Override
    public Certificate[] getLocalCertificates() {
        Certificate[] localCerts = localCertificateChain;
        if (localCerts == null) {
            return null;
        }
        return localCerts.clone();
    }

    @Override
    public X509Certificate[] getPeerCertificateChain() throws SSLPeerUnverifiedException {
        if (SslUtils.isEmpty(x509PeerCerts)) {
            throw new SSLPeerUnverifiedException("peer not verified");
        }
        return x509PeerCerts.clone();
    }

    @Override
    public Principal getPeerPrincipal() throws SSLPeerUnverifiedException {
        Certificate[] peer = getPeerCertificates();
        // No need for null or length > 0 is needed as this is done in getPeerCertificates()
        // already.
        return ((java.security.cert.X509Certificate) peer[0]).getSubjectX500Principal();
    }

    @Override
    public Principal getLocalPrincipal() {
        Certificate[] local = localCertificateChain;
        if (SslUtils.isEmpty(local)) {
            return null;
        }
        return ((java.security.cert.X509Certificate) local[0]).getIssuerX500Principal();
    }

    @Override
    public String getCipherSuite() {
        return cipher;
    }

    @Override
    public String getProtocol() {
        return protocol;
    }

    @Override
    public int getPacketBufferSize() {
        return packetBufferSize;
    }

    @Override
    public int getApplicationBufferSize() {
        return applicationBufferSize;
    }

    @Override
    public void tryExpandApplicationBufferSize(int packetLengthDataOnly) {
        if (packetLengthDataOnly > ReferenceCountedOpenSslEngine.MAX_PLAINTEXT_LENGTH &&
                applicationBufferSize != ReferenceCountedOpenSslEngine.MAX_RECORD_SIZE) {
            applicationBufferSize = ReferenceCountedOpenSslEngine.MAX_RECORD_SIZE;
        }
    }
}
