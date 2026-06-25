package com.faradaym.irc24.client;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javax.net.ssl.*;
import java.io.IOException;
import java.math.BigInteger;
import java.security.*;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.*;

class TlsTest {

    /** Server SSLContext: self-signed RSA cert */
    private static SSLContext serverCtx;

    /** Client SSLContext: trusts only our self-signed cert */
    private static SSLContext trustingClientCtx;

    @BeforeAll
    static void generateSelfSignedCert() throws Exception {
        // 1. Generate RSA key pair
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048, new SecureRandom());
        KeyPair kp = kpg.generateKeyPair();

        // 2. Self-signed X509 certificate via Bouncy Castle
        X500Name subject = new X500Name("CN=localhost");
        Date notBefore = new Date();
        Date notAfter = new Date(notBefore.getTime() + 365L * 24 * 3600 * 1000);

        ContentSigner signer = new JcaContentSignerBuilder("SHA256WithRSA").build(kp.getPrivate());
        X509Certificate cert = new JcaX509CertificateConverter().getCertificate(
                new JcaX509v3CertificateBuilder(
                        subject, BigInteger.valueOf(System.currentTimeMillis()),
                        notBefore, notAfter, subject, kp.getPublic()
                ).build(signer)
        );

        // 3. KeyStore for the server
        KeyStore ks = KeyStore.getInstance("PKCS12");
        ks.load(null, null);
        ks.setKeyEntry("key", kp.getPrivate(), new char[0], new java.security.cert.Certificate[]{cert});

        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, new char[0]);

        serverCtx = SSLContext.getInstance("TLS");
        serverCtx.init(kmf.getKeyManagers(), null, null);

        // 4. TrustManager for the client: trusts only our certificate
        KeyStore trustStore = KeyStore.getInstance("PKCS12");
        trustStore.load(null, null);
        trustStore.setCertificateEntry("cert", cert);

        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(trustStore);

        trustingClientCtx = SSLContext.getInstance("TLS");
        trustingClientCtx.init(null, tmf.getTrustManagers(), null);
    }

    // --- Successful TLS handshake ---

    @Test
    void tlsHandshakeSucceeds() throws Exception {
        FakeServer server = FakeServer.tls(serverCtx);

        IrcClientConfig config = IrcClientConfig.of("localhost", server.port(), "tlsnick")
                .withSslContext(trustingClientCtx)
                .withReconnect(ReconnectStrategy.noReconnect());

        IrcClient client = server.connectClient(config);

        // Reaching here means handshake succeeded and 001 was received
        assertTrue(true);

        client.close();
        server.close();
    }

    // --- Untrusted certificate — SSLHandshakeException ---

    @Test
    void tlsHandshakeFailsWithUntrustedCert() throws Exception {
        FakeServer server = FakeServer.tls(serverCtx);

        // Client uses JVM-default SSLContext — does not trust the server's self-signed cert
        IrcClientConfig config = IrcClientConfig.of("localhost", server.port(), "tlsnick")
                .withTls(true)  // tls=true, sslContext = JVM default
                .withHandshakeTimeout(Duration.ofSeconds(3))
                .withReconnect(ReconnectStrategy.noReconnect());

        // Accept the connection and start handshake — otherwise client waits forever for ServerHello
        Thread acceptThread = Thread.ofVirtual().start(() -> {
            try {
                server.awaitConnection();
                server.startTlsHandshakeQuietly(); // SSLHandshakeException expected — client rejects cert
            } catch (Exception ignored) {}
        });

        IrcClient client = new IrcClient(config);
        assertThrows(IOException.class, client::start,
                "Client should throw IOException for an untrusted TLS certificate");

        acceptThread.join(2000);
        server.close();
    }
}
