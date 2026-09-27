package net.i2p.android.router.util;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

public class StrictCaptivePortalProbeTest {
    private HttpServer server;
    private String baseUrl;
    private final AtomicInteger loginHits = new AtomicInteger();

    @Before
    public void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/login", exchange -> {
            loginHits.incrementAndGet();
            respond(exchange, 200, "<html><body>Sign in</body></html>");
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @After
    public void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    public void genuineGenerate204IsAccepted() {
        context("/generate_204", exchange -> respond(exchange, 204, null));
        assertTrue(probe("/generate_204"));
    }

    @Test
    public void captivePortal302RedirectIsRejectedAndNotFollowed() {
        context("/generate_204", exchange -> {
            exchange.getResponseHeaders().add("Location", baseUrl + "/login");
            respond(exchange, 302, null);
        });

        assertFalse(probe("/generate_204"));
        assertTrue("strict probe must not follow captive-portal redirects", loginHits.get() == 0);
    }

    @Test
    public void captivePortal307RedirectIsRejected() {
        context("/generate_204", exchange -> {
            exchange.getResponseHeaders().add("Location", baseUrl + "/login");
            respond(exchange, 307, null);
        });
        assertFalse(probe("/generate_204"));
    }

    @Test
    public void captivePortal200LoginHtmlIsRejected() {
        context("/generate_204", exchange ->
                respond(exchange, 200, "<html><title>Wi-Fi login</title><form>Authenticate</form></html>"));
        assertFalse(probe("/generate_204"));
    }

    @Test
    public void captivePortal511NetworkAuthenticationRequiredIsRejected() {
        context("/generate_204", exchange ->
                respond(exchange, 511, "Network Authentication Required"));
        assertFalse(probe("/generate_204"));
    }

    @Test
    public void captivePortal401ChallengeIsRejected() {
        context("/generate_204", exchange -> {
            exchange.getResponseHeaders().add("WWW-Authenticate", "Basic realm=wifi");
            respond(exchange, 401, "Login required");
        });
        assertFalse(probe("/generate_204"));
    }

    @Test
    public void captivePortal503SplashOrGatewayFailureIsRejected() {
        context("/generate_204", exchange ->
                respond(exchange, 503, "Temporary captive gateway page"));
        assertFalse(probe("/generate_204"));
    }

    @Test
    public void redirectChainBeginningAtGenerate204IsRejectedImmediately() {
        context("/generate_204", exchange -> {
            exchange.getResponseHeaders().add("Location", baseUrl + "/portal-hop");
            respond(exchange, 301, null);
        });
        context("/portal-hop", exchange -> {
            exchange.getResponseHeaders().add("Location", baseUrl + "/login");
            respond(exchange, 302, null);
        });

        assertFalse(probe("/generate_204"));
        assertTrue("strict probe must stop at the first redirect", loginHits.get() == 0);
    }

    private boolean probe(String path) {
        ConnectivityAndInternetAccess.StrictHttpProbe probe =
                new ConnectivityAndInternetAccess.StrictHttpProbe();
        return probe.checkHttp(baseUrl + path, null);
    }

    private void context(String path, HttpHandler handler) {
        server.createContext(path, handler);
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
        if (status == 204) {
            exchange.sendResponseHeaders(status, -1);
            exchange.close();
            return;
        }
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }
}
