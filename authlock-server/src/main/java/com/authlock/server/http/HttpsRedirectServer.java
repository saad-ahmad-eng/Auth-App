package com.authlock.server.http;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;

/**
 * Plain-HTTP listener (port 8080 by default) that does nothing but
 * 301-redirect every request to the HTTPS listener ({@link AuthLockHttpServer}
 * on 8443) — kept only so a browser that types {@code http://} doesn't get a
 * connection-refused, never as a working login path (Phase 13 TLS follow-up:
 * once HTTPS exists, credentials must never be submittable over plaintext).
 * No routing, no session/CSRF/rate-limit logic — a redirect needs none of it.
 */
public final class HttpsRedirectServer {

    private final HttpServer httpServer;

    public HttpsRedirectServer(int httpPort, int httpsPort) throws IOException {
        this.httpServer = HttpServer.create(new InetSocketAddress(httpPort), 0);
        httpServer.createContext("/", exchange -> {
            String hostHeader = exchange.getRequestHeaders().getFirst("Host");
            String hostname = hostHeader != null ? hostHeader.replaceFirst(":\\d+$", "") : "localhost";
            String query = exchange.getRequestURI().getRawQuery();
            String target = "https://" + hostname + ":" + httpsPort + exchange.getRequestURI().getRawPath()
                    + (query != null ? "?" + query : "");
            exchange.getResponseHeaders().set("Location", target);
            exchange.sendResponseHeaders(301, -1);
            exchange.close();
        });
        httpServer.setExecutor(null);
    }

    public void start() {
        httpServer.start();
    }

    public void stop() {
        httpServer.stop(0);
    }

    public int port() {
        return httpServer.getAddress().getPort();
    }
}
