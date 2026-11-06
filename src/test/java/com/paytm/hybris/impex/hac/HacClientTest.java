package com.paytm.hybris.impex.hac;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

public class HacClientTest {

    private static HttpServer mockServer;
    private static int mockPort;
    private static final String VALID_USER = "admin";
    private static final String VALID_PASS = "nimda";
    private static final String MOCK_CSRF = "mock-csrf-token-123456789";
    private static final String VALID_SESSION = "MOCK_SESSION_12345";

    @BeforeAll
    public static void setUpServer() throws Exception {
        mockServer = HttpServer.create(new InetSocketAddress(0), 0);
        mockPort = mockServer.getAddress().getPort();

        HttpHandler loginHandler = exchange -> {
            String html = "<html><body>" +
                    "<form action=\"/j_spring_security_check\" method=\"post\">" +
                    "<input type=\"hidden\" name=\"_csrf\" value=\"" + MOCK_CSRF + "\" />" +
                    "</form></body></html>";
            byte[] bytes = html.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        };

        HttpHandler authHandler = exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            Map<String, String> params = parseForm(body);

            if (VALID_USER.equals(params.get("j_username")) && VALID_PASS.equals(params.get("j_password"))) {
                exchange.getResponseHeaders().set("Location", "/hac/console/impex/import");
                exchange.getResponseHeaders().add("Set-Cookie", "JSESSIONID=" + VALID_SESSION + "; Path=/");
                exchange.sendResponseHeaders(302, -1);
            } else {
                exchange.getResponseHeaders().set("Location", "/login.jsp?login_error=1");
                exchange.sendResponseHeaders(302, -1);
            }
        };

        // Register on both root and /hac/ paths
        mockServer.createContext("/login.jsp", loginHandler);
        mockServer.createContext("/hac/login.jsp", loginHandler);
        mockServer.createContext("/j_spring_security_check", authHandler);
        mockServer.createContext("/hac/j_spring_security_check", authHandler);

        // ImpEx Console
        mockServer.createContext("/hac/console/impex/import", exchange -> {
            String cookieHeader = exchange.getRequestHeaders().getFirst("Cookie");
            boolean hasSession = cookieHeader != null && cookieHeader.contains(VALID_SESSION);

            String method = exchange.getRequestMethod();
            if ("GET".equalsIgnoreCase(method)) {
                if (!hasSession) {
                    exchange.getResponseHeaders().set("Location", "/login.jsp");
                    exchange.sendResponseHeaders(302, -1);
                    return;
                }
                String html = "<html><head><meta name=\"_csrf\" content=\"" + MOCK_CSRF + "\"/></head>" +
                        "<body><h1>ImpEx Import Console</h1></body></html>";
                byte[] bytes = html.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
                exchange.sendResponseHeaders(200, bytes.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(bytes);
                }
            } else if ("POST".equalsIgnoreCase(method)) {
                if (!hasSession) {
                    exchange.getResponseHeaders().set("Location", "/login.jsp");
                    exchange.sendResponseHeaders(302, -1);
                    return;
                }

                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                Map<String, String> params = parseForm(body);

                String script = params.get("scriptContent");
                if (script != null && script.contains("FAIL_ERROR")) {
                    String errHtml = "<html><body><div class=\"box error\">Import has encountered an error: line 5: Column cannot be null</div></body></html>";
                    byte[] bytes = errHtml.getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
                    exchange.sendResponseHeaders(200, bytes.length);
                    try (OutputStream os = exchange.getResponseBody()) {
                        os.write(bytes);
                    }
                } else {
                    String successHtml = "<html><body><div class=\"box success\">Import finished successfully</div></body></html>";
                    byte[] bytes = successHtml.getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
                    exchange.sendResponseHeaders(200, bytes.length);
                    try (OutputStream os = exchange.getResponseBody()) {
                        os.write(bytes);
                    }
                }
            }
        });

        mockServer.start();
    }

    @AfterAll
    public static void tearDownServer() {
        if (mockServer != null) {
            mockServer.stop(0);
        }
    }

    @Test
    public void testCurlParsing() {
        String curl = "curl --url ^\"https://backoffice-internal.c0a7xy-abdullati1-s1-public.model-t.cc.commerce.ondemand.com/hac/console/impex/import^\" ^\n" +
                "  -b ^\"JSESSIONID=7D7C460078EADF66704A18CD94441634.backoffice-d9bfbb556-xq5h6; ROUTE=.backoffice-d9bfbb556-xq5h6^\" ^\n" +
                "  --data-raw ^\"scriptContent=^&validationEnum=IMPORT_STRICT^&maxThreads=16^&encoding=UTF-8^&_legacyMode=on^&_enableCodeExecution=on^&_distributedMode=on^&_sldEnabled=on^&_csrf=u_RXAy5orQRKraCmF7gLhQf3aPyCPkbfk4hsy-JxSuvhu467gsBuMUtalTJnnMOXJZU_4WXGRcWyWnPy9r0O_tYVfdnZjr7f^\"";

        HacConfig config = new HacConfig();
        boolean parsed = config.parseFromCurl(curl);

        assertTrue(parsed);
        assertEquals("https://backoffice-internal.c0a7xy-abdullati1-s1-public.model-t.cc.commerce.ondemand.com/hac/console/impex/import", config.getImportUrl());
        assertEquals("JSESSIONID=7D7C460078EADF66704A18CD94441634.backoffice-d9bfbb556-xq5h6; ROUTE=.backoffice-d9bfbb556-xq5h6", config.getCookie());
        assertEquals("u_RXAy5orQRKraCmF7gLhQf3aPyCPkbfk4hsy-JxSuvhu467gsBuMUtalTJnnMOXJZU_4WXGRcWyWnPy9r0O_tYVfdnZjr7f", config.getCsrfToken());
        assertEquals(16, config.getMaxThreads());
        assertEquals("IMPORT_STRICT", config.getValidationEnum());
    }

    @Test
    public void testSuccessfulConnectionAndImportWithCredentials() {
        HacConfig config = new HacConfig();
        config.setImportUrl("http://localhost:" + mockPort + "/hac/console/impex/import");
        config.setUsername(VALID_USER);
        config.setPassword(VALID_PASS);

        HacClient client = new HacClient();
        List<String> logs = new ArrayList<>();

        // Test pre-flight check
        HacTestResult testRes = client.testConnection(config, logs::add);
        assertTrue(testRes.isSuccess(), "Pre-flight connection should succeed: " + testRes.getMessage());
        assertEquals(VALID_USER, testRes.getAuthenticatedUser());

        // Test execution
        String impex = "INSERT_UPDATE Title;code[unique=true];name\n;mr;Mister\n";
        HacImportResult importRes = client.importImpex(config, impex, logs::add);

        assertTrue(importRes.isSuccess(), "ImpEx execution should succeed: " + importRes.getSummary());
        assertTrue(importRes.getSummary().contains("Import finished successfully"));
        assertEquals(2, importRes.getTotalLines());
    }

    @Test
    public void testFailedConnectionWithBadCredentials() {
        HacConfig config = new HacConfig();
        config.setImportUrl("http://localhost:" + mockPort + "/hac/console/impex/import");
        config.setUsername("wrong_user");
        config.setPassword("wrong_pass");

        HacClient client = new HacClient();
        HacTestResult testRes = client.testConnection(config, null);

        assertFalse(testRes.isSuccess(), "Connection should fail on bad credentials");
        assertTrue(testRes.getMessage().contains("Authentication failed"));
    }

    @Test
    public void testImportErrorExtraction() {
        HacConfig config = new HacConfig();
        config.setImportUrl("http://localhost:" + mockPort + "/hac/console/impex/import");
        config.setUsername(VALID_USER);
        config.setPassword(VALID_PASS);

        HacClient client = new HacClient();
        String impex = "INSERT_UPDATE Title;code[unique=true]\n;FAIL_ERROR;bad\n";
        HacImportResult importRes = client.importImpex(config, impex, null);

        assertFalse(importRes.isSuccess(), "Import should report failure");
        assertNotNull(importRes.getErrorDetails());
        assertTrue(importRes.getErrorDetails().contains("line 5"));
    }

    private static Map<String, String> parseForm(String formData) {
        Map<String, String> map = new HashMap<>();
        if (formData == null || formData.isEmpty()) return map;
        String[] pairs = formData.split("&");
        for (String pair : pairs) {
            int idx = pair.indexOf("=");
            if (idx > 0) {
                String key = URLDecoder.decode(pair.substring(0, idx), StandardCharsets.UTF_8);
                String val = pair.length() > idx + 1 ? URLDecoder.decode(pair.substring(idx + 1), StandardCharsets.UTF_8) : "";
                map.put(key, val);
            }
        }
        return map;
    }
}
