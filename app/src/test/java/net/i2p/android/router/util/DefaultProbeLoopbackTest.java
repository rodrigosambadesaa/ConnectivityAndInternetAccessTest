package net.i2p.android.router.util;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class DefaultProbeLoopbackTest {

    @Test
    public void defaultTcpProbeReachesIpv4Loopback() throws Exception {
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            Thread acceptor = acceptOnce(server);
            assertTrue(new ConnectivityAndInternetAccess.DefaultTcpProbe()
                    .checkTcp("127.0.0.1", server.getLocalPort(), null));
            acceptor.join(1000L);
        }
    }

    @Test
    public void defaultTcpProbeReachesIpv6Loopback() throws Exception {
        InetAddress loopback6 = InetAddress.getByName("::1");
        Assume.assumeTrue(loopback6 instanceof Inet6Address);

        ServerSocket server;
        try {
            server = new ServerSocket();
            server.bind(new InetSocketAddress(loopback6, 0), 1);
        } catch (IOException unsupported) {
            Assume.assumeNoException("IPv6 loopback unavailable on this runner", unsupported);
            return;
        }

        try (ServerSocket closeable = server) {
            Thread acceptor = acceptOnce(closeable);
            assertTrue(new ConnectivityAndInternetAccess.DefaultTcpProbe()
                    .checkTcp("::1", closeable.getLocalPort(), null));
            acceptor.join(1000L);
        }
    }

    @Test
    public void defaultDnsProbeAcceptsStructurallyValidResponse() throws Exception {
        try (MiniDnsServer server = new MiniDnsServer(false)) {
            server.start();
            String resolver = "127.0.0.1:" + server.getPort();
            assertTrue(new ConnectivityAndInternetAccess.DefaultDnsProbe()
                    .checkDns(resolver, null));
        }
    }

    @Test
    public void defaultDnsProbeRejectsWrongTransactionId() throws Exception {
        try (MiniDnsServer server = new MiniDnsServer(true)) {
            server.start();
            String resolver = "127.0.0.1:" + server.getPort();
            assertFalse(new ConnectivityAndInternetAccess.DefaultDnsProbe()
                    .checkDns(resolver, null));
        }
    }

    @Test
    public void defaultHttpProbeAcceptsReachableHttpStatusWithoutFollowingRedirect() throws Exception {
        AtomicBoolean redirectedTargetHit = new AtomicBoolean(false);
        try (MiniHttpServer server = new MiniHttpServer(redirectedTargetHit)) {
            server.start();
            String url = "http://127.0.0.1:" + server.getPort() + "/redirect";
            assertTrue(new ConnectivityAndInternetAccess.DefaultHttpProbe()
                    .checkHttp(url, null));
            assertFalse("DefaultHttpProbe must not follow redirects", redirectedTargetHit.get());
        }
    }

    @Test
    public void ciNtpProbeReceivesReal48ByteUdpReply() {
        Assume.assumeTrue("CI NTP loopback server not enabled",
                "1".equals(System.getenv("CONNECTIVITY_CI_NTP")));
        assertTrue(new ConnectivityAndInternetAccess.DefaultNtpProbe()
                .checkNtp("127.0.0.1", null));
    }

    @Test
    public void ciLiveTlsHandshakeSucceeds() {
        Assume.assumeTrue("Live network smoke tests not enabled",
                "1".equals(System.getenv("CONNECTIVITY_CI_LIVE")));
        assertTrue(new ConnectivityAndInternetAccess.DefaultTlsProbe()
                .checkTls("www.google.com", 443, null));
    }

    private static Thread acceptOnce(ServerSocket server) {
        Thread thread = new Thread(() -> {
            try (Socket ignored = server.accept()) {
                // Connection establishment is the behavior under test.
            } catch (IOException ignored) {
                // Server may be closed during cleanup.
            }
        }, "tcp-loopback-server");
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    private static final class MiniDnsServer implements AutoCloseable {
        private final DatagramSocket socket;
        private final boolean corruptTransactionId;
        private final CountDownLatch ready = new CountDownLatch(1);
        private Thread thread;

        MiniDnsServer(boolean corruptTransactionId) throws IOException {
            this.socket = new DatagramSocket(0, InetAddress.getByName("127.0.0.1"));
            this.corruptTransactionId = corruptTransactionId;
        }

        int getPort() {
            return socket.getLocalPort();
        }

        void start() throws InterruptedException {
            thread = new Thread(() -> {
                ready.countDown();
                try {
                    byte[] requestBytes = new byte[512];
                    DatagramPacket request = new DatagramPacket(requestBytes, requestBytes.length);
                    socket.receive(request);

                    byte[] response = new byte[12];
                    response[0] = requestBytes[0];
                    response[1] = requestBytes[1];
                    if (corruptTransactionId) {
                        response[1] ^= 0x01;
                    }
                    response[2] = (byte) 0x81;
                    response[3] = (byte) 0x80;
                    response[4] = 0x00;
                    response[5] = 0x01;

                    DatagramPacket answer = new DatagramPacket(
                            response,
                            response.length,
                            request.getAddress(),
                            request.getPort());
                    socket.send(answer);
                } catch (IOException ignored) {
                    // Socket closed during cleanup.
                }
            }, "dns-loopback-server");
            thread.setDaemon(true);
            thread.start();
            assertTrue(ready.await(1, TimeUnit.SECONDS));
        }

        @Override
        public void close() throws Exception {
            socket.close();
            if (thread != null) {
                thread.join(1000L);
            }
        }
    }

    private static final class MiniHttpServer implements AutoCloseable {
        private final ServerSocket socket;
        private final AtomicBoolean redirectedTargetHit;
        private volatile boolean running = true;
        private Thread thread;

        MiniHttpServer(AtomicBoolean redirectedTargetHit) throws IOException {
            this.socket = new ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"));
            this.redirectedTargetHit = redirectedTargetHit;
        }

        int getPort() {
            return socket.getLocalPort();
        }

        void start() {
            thread = new Thread(() -> {
                while (running) {
                    try {
                        handle(socket.accept());
                    } catch (IOException ignored) {
                        if (running) {
                            throw new RuntimeException(ignored);
                        }
                    }
                }
            }, "http-loopback-server");
            thread.setDaemon(true);
            thread.start();
        }

        private void handle(Socket client) throws IOException {
            try (Socket connection = client;
                 BufferedReader reader = new BufferedReader(
                         new InputStreamReader(connection.getInputStream(), StandardCharsets.US_ASCII));
                 OutputStream output = connection.getOutputStream()) {
                String requestLine = reader.readLine();
                if (requestLine == null) {
                    return;
                }
                String[] parts = requestLine.split(" ");
                String path = parts.length > 1 ? parts[1] : "/";
                String line;
                while ((line = reader.readLine()) != null && !line.isEmpty()) {
                    // consume headers
                }

                if ("/target".equals(path)) {
                    redirectedTargetHit.set(true);
                    write(output, 200, null);
                } else {
                    write(output, 302, "http://127.0.0.1:" + getPort() + "/target");
                }
            }
        }

        private static void write(OutputStream output, int status, String location) throws IOException {
            StringBuilder response = new StringBuilder();
            response.append("HTTP/1.1 ").append(status)
                    .append(status == 302 ? " Found" : " OK").append("\r\n");
            response.append("Connection: close\r\n");
            if (location != null) {
                response.append("Location: ").append(location).append("\r\n");
            }
            response.append("Content-Length: 0\r\n\r\n");
            output.write(response.toString().getBytes(StandardCharsets.US_ASCII));
            output.flush();
        }

        @Override
        public void close() throws Exception {
            running = false;
            socket.close();
            if (thread != null) {
                thread.join(1000L);
            }
        }
    }
}
