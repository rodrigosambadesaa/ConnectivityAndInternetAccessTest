package net.i2p.android.router.util;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeNoException;
import static org.junit.Assume.assumeTrue;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class LocalProtocolProbeTest {

    @Test
    public void defaultTcpProbeCompletesRealIpv4SocketConnection() throws Exception {
        try (ServerSocket server = new ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"))) {
            Thread acceptor = daemon(() -> {
                try (Socket ignored = server.accept()) {
                    // A successful accept is enough for the TCP probe.
                } catch (IOException ignored) {
                }
            });
            acceptor.start();

            assertTrue(new ConnectivityAndInternetAccess.DefaultTcpProbe()
                    .checkTcp("127.0.0.1", server.getLocalPort(), null));
            acceptor.join(2_000L);
        }
    }

    @Test
    public void defaultTcpProbeSupportsRealIpv6LoopbackWhenRunnerHasIpv6() throws Exception {
        ServerSocket server;
        try {
            server = new ServerSocket(0, 10, InetAddress.getByName("::1"));
        } catch (Exception unavailable) {
            assumeNoException("IPv6 loopback unavailable on runner", unavailable);
            return;
        }

        try (ServerSocket closeable = server) {
            Thread acceptor = daemon(() -> {
                try (Socket ignored = closeable.accept()) {
                } catch (IOException ignored) {
                }
            });
            acceptor.start();

            assertTrue(new ConnectivityAndInternetAccess.DefaultTcpProbe()
                    .checkTcp("::1", closeable.getLocalPort(), null));
            acceptor.join(2_000L);
        }
    }

    @Test
    public void defaultDnsProbeAcceptsMatchingDnsResponseOverUdp() throws Exception {
        try (DatagramSocket dns = new DatagramSocket(0, InetAddress.getByName("127.0.0.1"))) {
            AtomicReference<Throwable> serverFailure = new AtomicReference<>();
            Thread responder = daemon(() -> {
                try {
                    byte[] queryBytes = new byte[512];
                    DatagramPacket query = new DatagramPacket(queryBytes, queryBytes.length);
                    dns.receive(query);

                    byte[] response = new byte[12];
                    response[0] = query.getData()[0];
                    response[1] = query.getData()[1];
                    response[2] = (byte) 0x81;
                    response[3] = (byte) 0x80;
                    response[4] = 0x00;
                    response[5] = 0x01;

                    dns.send(new DatagramPacket(
                            response,
                            response.length,
                            query.getAddress(),
                            query.getPort()));
                } catch (Throwable failure) {
                    serverFailure.set(failure);
                }
            });
            responder.start();

            assertTrue(new ConnectivityAndInternetAccess.DefaultDnsProbe()
                    .checkDns("127.0.0.1:" + dns.getLocalPort(), null));
            responder.join(2_000L);
            if (serverFailure.get() != null) {
                throw new AssertionError(serverFailure.get());
            }
        }
    }

    @Test
    public void defaultDnsProbeRejectsWrongTransactionId() throws Exception {
        try (DatagramSocket dns = new DatagramSocket(0, InetAddress.getByName("127.0.0.1"))) {
            Thread responder = daemon(() -> {
                try {
                    byte[] queryBytes = new byte[512];
                    DatagramPacket query = new DatagramPacket(queryBytes, queryBytes.length);
                    dns.receive(query);

                    byte[] response = new byte[12];
                    response[0] = (byte) (query.getData()[0] ^ 0x55);
                    response[1] = query.getData()[1];
                    response[2] = (byte) 0x81;
                    response[3] = (byte) 0x80;
                    response[5] = 0x01;
                    dns.send(new DatagramPacket(
                            response,
                            response.length,
                            query.getAddress(),
                            query.getPort()));
                } catch (IOException ignored) {
                }
            });
            responder.start();

            assertFalse(new ConnectivityAndInternetAccess.DefaultDnsProbe()
                    .checkDns("127.0.0.1:" + dns.getLocalPort(), null));
            responder.join(2_000L);
        }
    }

    @Test
    public void defaultHttpProbeAcceptsRealHttpResponseButDoesNotFollowRedirects() throws Exception {
        AtomicReference<Integer> loginHits = new AtomicReference<>(0);
        try (MiniHttpServer server = new MiniHttpServer()) {
            server.setLoginHits(loginHits);
            server.start();

            assertTrue(new ConnectivityAndInternetAccess.DefaultHttpProbe()
                    .checkHttp(server.url("/ok"), null));
            assertTrue(new ConnectivityAndInternetAccess.DefaultHttpProbe()
                    .checkHttp(server.url("/redirect"), null));
            assertTrue("default probe should classify the redirect response itself as reachable",
                    loginHits.get() == 0);
        }
    }

    @Test
    public void defaultNtpProbeCompletesRealUdpRoundTripOnPort123() throws Exception {
        DatagramSocket ntp;
        try {
            ntp = new DatagramSocket(123, InetAddress.getByName("127.0.0.1"));
        } catch (Exception unavailable) {
            assumeNoException("UDP/123 unavailable on runner", unavailable);
            return;
        }

        try (DatagramSocket server = ntp) {
            Thread responder = daemon(() -> {
                try {
                    byte[] input = new byte[48];
                    DatagramPacket request = new DatagramPacket(input, input.length);
                    server.receive(request);

                    byte[] response = new byte[48];
                    response[0] = 0x1c;
                    server.send(new DatagramPacket(
                            response,
                            response.length,
                            request.getAddress(),
                            request.getPort()));
                } catch (IOException ignored) {
                }
            });
            responder.start();

            assertTrue(new ConnectivityAndInternetAccess.DefaultNtpProbe()
                    .checkNtp("127.0.0.1", null));
            responder.join(2_000L);
        }
    }

    @Test
    public void defaultTlsProbeCompletesRealTrustedTlsHandshake() throws Exception {
        String keyStorePath = System.getenv("TEST_TLS_KEYSTORE");
        assumeTrue("TEST_TLS_KEYSTORE is configured by CI", keyStorePath != null);

        char[] password = "changeit".toCharArray();
        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        try (FileInputStream in = new FileInputStream(keyStorePath)) {
            keyStore.load(in, password);
        }

        KeyManagerFactory kmf =
                KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(keyStore, password);

        SSLContext serverContext = SSLContext.getInstance("TLS");
        serverContext.init(kmf.getKeyManagers(), null, null);

        try (SSLServerSocket server = (SSLServerSocket)
                serverContext.getServerSocketFactory().createServerSocket(
                        0, 10, InetAddress.getByName("127.0.0.1"))) {
            CountDownLatch handshaken = new CountDownLatch(1);
            AtomicReference<Throwable> failure = new AtomicReference<>();
            Thread responder = daemon(() -> {
                try (SSLSocket socket = (SSLSocket) server.accept()) {
                    socket.startHandshake();
                    handshaken.countDown();
                } catch (Throwable error) {
                    failure.set(error);
                }
            });
            responder.start();

            assertTrue(new ConnectivityAndInternetAccess.DefaultTlsProbe()
                    .checkTls("localhost", server.getLocalPort(), null));
            assertTrue(handshaken.await(3, TimeUnit.SECONDS));
            responder.join(2_000L);
            if (failure.get() != null) {
                throw new AssertionError(failure.get());
            }
        }
    }

    @Test
    public void defaultTlsProbeRejectsPlainTcpServer() throws Exception {
        try (ServerSocket server = new ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"))) {
            Thread responder = daemon(() -> {
                try (Socket socket = server.accept()) {
                    socket.getOutputStream().write("not tls".getBytes(StandardCharsets.US_ASCII));
                    socket.getOutputStream().flush();
                } catch (IOException ignored) {
                }
            });
            responder.start();

            assertFalse(new ConnectivityAndInternetAccess.DefaultTlsProbe()
                    .checkTls("localhost", server.getLocalPort(), null));
            responder.join(3_000L);
        }
    }

    private static Thread daemon(Runnable runnable) {
        Thread thread = new Thread(runnable, "local-probe-test-server");
        thread.setDaemon(true);
        return thread;
    }

    private static final class MiniHttpServer implements AutoCloseable {
        private final ServerSocket server =
                new ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"));
        private volatile boolean running = true;
        private Thread thread;
        private AtomicReference<Integer> loginHits;

        MiniHttpServer() throws IOException {}

        void setLoginHits(AtomicReference<Integer> loginHits) {
            this.loginHits = loginHits;
        }

        String url(String path) {
            return "http://127.0.0.1:" + server.getLocalPort() + path;
        }

        void start() {
            thread = daemon(() -> {
                while (running) {
                    try {
                        handle(server.accept());
                    } catch (IOException e) {
                        if (running) {
                            throw new RuntimeException(e);
                        }
                    }
                }
            });
            thread.start();
        }

        private void handle(Socket socket) throws IOException {
            try (Socket connection = socket;
                 BufferedReader reader = new BufferedReader(new InputStreamReader(
                         connection.getInputStream(), StandardCharsets.US_ASCII));
                 OutputStream output = connection.getOutputStream()) {
                String requestLine = reader.readLine();
                if (requestLine == null) {
                    return;
                }
                String[] parts = requestLine.split(" ");
                String path = parts.length >= 2 ? parts[1] : "/";
                String line;
                while ((line = reader.readLine()) != null && !line.isEmpty()) {
                }

                if ("/redirect".equals(path)) {
                    write(output, 302, "Location: " + url("/login") + "\r\n", "");
                } else if ("/login".equals(path)) {
                    if (loginHits != null) {
                        loginHits.set(loginHits.get() + 1);
                    }
                    write(output, 200, "", "login");
                } else {
                    write(output, 204, "", "");
                }
            }
        }

        private static void write(OutputStream out, int status, String headers, String body)
                throws IOException {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            String head = "HTTP/1.1 " + status + " Test\r\n"
                    + headers
                    + "Connection: close\r\n"
                    + "Content-Length: " + bytes.length + "\r\n\r\n";
            out.write(head.getBytes(StandardCharsets.US_ASCII));
            out.write(bytes);
            out.flush();
        }

        @Override
        public void close() throws Exception {
            running = false;
            server.close();
            if (thread != null) {
                thread.join(2_000L);
            }
        }
    }
}
