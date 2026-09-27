package net.i2p.android.router.util;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class StrictCaptivePortalProbeTest {
    private MiniHttpServer server;
    private String baseUrl;
    private final AtomicInteger loginHits = new AtomicInteger();

    @Before
    public void setUp() throws Exception {
        loginHits.set(0);
        server = new MiniHttpServer();
        server.route("/login", new Response(200, null, "<html><body>Sign in</body></html>", loginHits));
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getPort();
    }

    @After
    public void tearDown() throws Exception {
        if (server != null) {
            server.close();
        }
    }

    @Test
    public void genuineGenerate204IsAccepted() {
        server.route("/generate_204", new Response(204, null, null));
        assertTrue(probe("/generate_204"));
    }

    @Test
    public void captivePortal302RedirectIsRejectedAndNotFollowed() {
        server.route("/generate_204", new Response(302, baseUrl + "/login", null));

        assertFalse(probe("/generate_204"));
        assertTrue("strict probe must not follow captive-portal redirects", loginHits.get() == 0);
    }

    @Test
    public void captivePortal307RedirectIsRejected() {
        server.route("/generate_204", new Response(307, baseUrl + "/login", null));
        assertFalse(probe("/generate_204"));
    }

    @Test
    public void captivePortal200LoginHtmlIsRejected() {
        server.route("/generate_204", new Response(
                200,
                null,
                "<html><title>Wi-Fi login</title><form>Authenticate</form></html>"));
        assertFalse(probe("/generate_204"));
    }

    @Test
    public void captivePortal511NetworkAuthenticationRequiredIsRejected() {
        server.route("/generate_204", new Response(511, null, "Network Authentication Required"));
        assertFalse(probe("/generate_204"));
    }

    @Test
    public void captivePortal401ChallengeIsRejected() {
        server.route("/generate_204", new Response(401, null, "Login required")
                .header("WWW-Authenticate", "Basic realm=wifi"));
        assertFalse(probe("/generate_204"));
    }

    @Test
    public void captivePortal503SplashOrGatewayFailureIsRejected() {
        server.route("/generate_204", new Response(503, null, "Temporary captive gateway page"));
        assertFalse(probe("/generate_204"));
    }

    @Test
    public void redirectChainBeginningAtGenerate204IsRejectedImmediately() {
        AtomicInteger portalHopHits = new AtomicInteger();
        server.route("/generate_204", new Response(301, baseUrl + "/portal-hop", null));
        server.route("/portal-hop", new Response(302, baseUrl + "/login", null, portalHopHits));

        assertFalse(probe("/generate_204"));
        assertTrue("strict probe must stop at the first redirect", portalHopHits.get() == 0);
        assertTrue("strict probe must never reach the login page", loginHits.get() == 0);
    }

    private boolean probe(String path) {
        ConnectivityAndInternetAccess.StrictHttpProbe probe =
                new ConnectivityAndInternetAccess.StrictHttpProbe();
        return probe.checkHttp(baseUrl + path, null);
    }

    private static final class MiniHttpServer implements AutoCloseable {
        private final ServerSocket socket;
        private final Map<String, Response> routes = new ConcurrentHashMap<>();
        private final AtomicBoolean running = new AtomicBoolean(false);
        private Thread thread;

        MiniHttpServer() throws IOException {
            socket = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
        }

        int getPort() {
            return socket.getLocalPort();
        }

        void route(String path, Response response) {
            routes.put(path, response);
        }

        void start() {
            running.set(true);
            thread = new Thread(() -> {
                while (running.get()) {
                    try {
                        handle(socket.accept());
                    } catch (IOException e) {
                        if (running.get()) {
                            throw new RuntimeException(e);
                        }
                    }
                }
            }, "captive-portal-test-server");
            thread.setDaemon(true);
            thread.start();
        }

        private void handle(Socket client) throws IOException {
            try (Socket connection = client;
                 BufferedReader reader = new BufferedReader(
                         new InputStreamReader(connection.getInputStream(), StandardCharsets.US_ASCII));
                 OutputStream output = connection.getOutputStream()) {

                String requestLine = reader.readLine();
                if (requestLine == null || requestLine.isEmpty()) {
                    return;
                }
                String[] request = requestLine.split(" ");
                String path = request.length >= 2 ? request[1] : "/";

                String line;
                while ((line = reader.readLine()) != null && !line.isEmpty()) {
                    // Consume headers before answering.
                }

                Response response = routes.get(path);
                if (response == null) {
                    response = new Response(404, null, "Not found");
                }
                response.write(output);
            }
        }

        @Override
        public void close() throws Exception {
            running.set(false);
            socket.close();
            if (thread != null) {
                thread.join(2000L);
            }
        }
    }

    private static final class Response {
        private final int status;
        private final String location;
        private final String body;
        private final AtomicInteger hitCounter;
        private final Map<String, String> headers = new ConcurrentHashMap<>();

        Response(int status, String location, String body) {
            this(status, location, body, null);
        }

        Response(int status, String location, String body, AtomicInteger hitCounter) {
            this.status = status;
            this.location = location;
            this.body = body == null ? "" : body;
            this.hitCounter = hitCounter;
        }

        Response header(String name, String value) {
            headers.put(name, value);
            return this;
        }

        void write(OutputStream output) throws IOException {
            if (hitCounter != null) {
                hitCounter.incrementAndGet();
            }

            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            StringBuilder head = new StringBuilder();
            head.append("HTTP/1.1 ").append(status).append(' ').append(reason(status)).append("\r\n");
            head.append("Connection: close\r\n");
            if (location != null) {
                head.append("Location: ").append(location).append("\r\n");
            }
            for (Map.Entry<String, String> header : headers.entrySet()) {
                head.append(header.getKey()).append(": ").append(header.getValue()).append("\r\n");
            }
            if (status != 204) {
                head.append("Content-Type: text/html; charset=utf-8\r\n");
                head.append("Content-Length: ").append(bytes.length).append("\r\n");
            }
            head.append("\r\n");

            output.write(head.toString().getBytes(StandardCharsets.US_ASCII));
            if (status != 204 && bytes.length > 0) {
                output.write(bytes);
            }
            output.flush();
        }

        private static String reason(int status) {
            switch (status) {
                case 200: return "OK";
                case 204: return "No Content";
                case 301: return "Moved Permanently";
                case 302: return "Found";
                case 307: return "Temporary Redirect";
                case 401: return "Unauthorized";
                case 404: return "Not Found";
                case 503: return "Service Unavailable";
                case 511: return "Network Authentication Required";
                default: return "Status";
            }
        }
    }
}
