package com.paytm.hybris.impex.hac;

import javax.net.ssl.*;
import java.io.IOException;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.*;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * HTTP Client for interacting with SAP Commerce (Hybris) Administration Console (HAC).
 * Supports direct execution using Cookie and CSRF token matching CCv2 curl requests,
 * as well as automated session login.
 */
public class HacClient {

    private static final Duration TIMEOUT_CONNECT = Duration.ofSeconds(20);
    private static final Duration TIMEOUT_IMPORT = Duration.ofMinutes(5);

    private static final Pattern CSRF_NAME_VALUE = Pattern.compile("name=[\"']_csrf[\"'][^>]*value=[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE);
    private static final Pattern CSRF_VALUE_NAME = Pattern.compile("value=[\"']([^\"']+)[\"'][^>]*name=[\"']_csrf[\"']", Pattern.CASE_INSENSITIVE);
    private static final Pattern CSRF_NAME_CONTENT = Pattern.compile("name=[\"']_csrf[\"'][^>]*content=[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE);
    private static final Pattern CSRF_CONTENT_NAME = Pattern.compile("content=[\"']([^\"']+)[\"'][^>]*name=[\"']_csrf[\"']", Pattern.CASE_INSENSITIVE);

    /**
     * Test connection and credentials or session/CSRF token validity.
     */
    public HacTestResult testConnection(HacConfig config, Consumer<String> log) {
        long startTime = System.currentTimeMillis();
        Consumer<String> logger = log != null ? log : msg -> {};

        try {
            String importUrl = config.getEffectiveImportUrl();
            if (importUrl.isEmpty()) {
                throw new HacException("HAC Import URL is required.");
            }

            logger.accept("Testing connection to: " + importUrl);
            HttpClient client = createHttpClient(config);

            // If cookie and CSRF are provided, test by requesting the ImpEx console directly
            if (!config.getCookie().isEmpty() || !config.getCsrfToken().isEmpty()) {
                logger.accept("Testing with provided Cookie and CSRF token...");
                HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                        .uri(URI.create(importUrl))
                        .timeout(TIMEOUT_CONNECT)
                        .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                        .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.0.0 Safari/537.36")
                        .GET();

                if (!config.getCookie().isEmpty()) {
                    reqBuilder.header("Cookie", config.getCookie());
                }

                HttpResponse<String> resp = client.send(reqBuilder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                long duration = System.currentTimeMillis() - startTime;

                if (resp.statusCode() == 200) {
                    String extractedCsrf = extractCsrfToken(resp.body());
                    if (!extractedCsrf.isEmpty() && config.getCsrfToken().isEmpty()) {
                        config.setCsrfToken(extractedCsrf);
                        logger.accept("Discovered active CSRF token from page: " + maskToken(extractedCsrf));
                    }
                    logger.accept("Connection successful (HTTP 200). HAC ImpEx Console is reachable.");
                    return new HacTestResult(true, "Successfully connected to HAC ImpEx Console.", duration, "Active Session");
                } else if (resp.statusCode() == 302 || resp.statusCode() == 303) {
                    String loc = resp.headers().firstValue("Location").orElse("");
                    logger.accept("Redirected to: " + loc);
                    return new HacTestResult(false, "Session expired or redirected to login (" + loc + "). Please refresh your Cookie and CSRF token.", duration, null);
                } else {
                    return new HacTestResult(false, "HAC returned HTTP " + resp.statusCode(), duration, null, resp.body());
                }
            }

            // Fallback: Automated login attempt if username & password present
            if (!config.getUsername().isEmpty() && !config.getPassword().isEmpty()) {
                logger.accept("No cookie provided; attempting automated login as '" + config.getUsername() + "'...");
                String endpoint = deriveBaseUrl(importUrl);
                String loginCsrf = fetchLoginCsrf(client, endpoint);
                authenticate(client, endpoint, config.getUsername(), config.getPassword(), loginCsrf);
                String authCsrf = fetchAuthenticatedCsrf(client, importUrl);
                config.setCsrfToken(authCsrf);

                long duration = System.currentTimeMillis() - startTime;
                logger.accept("Automated login succeeded. Obtained CSRF: " + maskToken(authCsrf));
                return new HacTestResult(true, "Successfully logged in as '" + config.getUsername() + "'.", duration, config.getUsername());
            }

            throw new HacException("Please provide a Session Cookie & CSRF Token (or Username & Password).");

        } catch (HacException he) {
            long duration = System.currentTimeMillis() - startTime;
            logger.accept("Test failed: " + he.getMessage());
            return new HacTestResult(false, he.getMessage(), duration, null, he.getServerResponse());
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            logger.accept("Connection error: " + e.getMessage());
            return new HacTestResult(false, "Connection error: " + e.getMessage(), duration, null);
        }
    }

    /**
     * Executes ImpEx directly on HAC.
     */
    public HacImportResult importImpex(HacConfig config, String impexScript, Consumer<String> log) {
        long startTime = System.currentTimeMillis();
        Consumer<String> logger = log != null ? log : msg -> {};

        if (impexScript == null || impexScript.trim().isEmpty()) {
            return new HacImportResult(false, "ImpEx script is empty.", "", "No script provided to import.", 400, 0, 0);
        }

        int totalLines = impexScript.split("\r\n|\r|\n").length;

        try {
            String importUrl = config.getEffectiveImportUrl();
            if (importUrl.isEmpty()) {
                throw new HacException("HAC Import URL is required.");
            }

            logger.accept("Preparing ImpEx execution on HAC...");
            logger.accept("Target URL: " + importUrl);
            logger.accept("Total lines in script: " + totalLines);

            HttpClient client = createHttpClient(config);

            // Check if we need to auto-login first
            String cookie = config.getCookie();
            String csrfToken = config.getCsrfToken();

            if (cookie.isEmpty() && !config.getUsername().isEmpty() && !config.getPassword().isEmpty()) {
                logger.accept("Session Cookie empty; performing automated login as '" + config.getUsername() + "'...");
                String endpoint = deriveBaseUrl(importUrl);
                String loginCsrf = fetchLoginCsrf(client, endpoint);
                authenticate(client, endpoint, config.getUsername(), config.getPassword(), loginCsrf);
                csrfToken = fetchAuthenticatedCsrf(client, importUrl);
                config.setCsrfToken(csrfToken);
            }

            if (csrfToken.isEmpty()) {
                logger.accept("CSRF token is empty; attempting to fetch from " + importUrl + "...");
                try {
                    csrfToken = fetchAuthenticatedCsrfWithCookie(client, importUrl, cookie);
                    if (!csrfToken.isEmpty()) {
                        config.setCsrfToken(csrfToken);
                        logger.accept("Retrieved CSRF token: " + maskToken(csrfToken));
                    }
                } catch (Exception ignored) {}
            }

            // Build form payload matching CCv2 curl format
            Map<String, String> formData = new LinkedHashMap<>();
            formData.put("scriptContent", impexScript);
            formData.put("validationEnum", config.getValidationEnum());
            formData.put("maxThreads", String.valueOf(config.getMaxThreads()));
            formData.put("encoding", config.getEncoding());

            if (config.isLegacyMode()) {
                formData.put("legacyMode", "true");
            }
            formData.put("_legacyMode", "on");

            if (config.isEnableCodeExecution()) {
                formData.put("enableCodeExecution", "true");
            }
            formData.put("_enableCodeExecution", "on");

            if (config.isDistributedMode()) {
                formData.put("distributedMode", "true");
            }
            formData.put("_distributedMode", "on");

            if (config.isSldEnabled()) {
                formData.put("sldEnabled", "true");
            }
            formData.put("_sldEnabled", "on");

            formData.put("_csrf", csrfToken);

            String requestBody = buildFormData(formData);
            String origin = config.getOrigin();
            String referer = config.getReferer();

            logger.accept("Sending POST request to " + importUrl + " (Validation: " + config.getValidationEnum() + ", Threads: " + config.getMaxThreads() + ")...");

            HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(importUrl))
                    .timeout(TIMEOUT_IMPORT)
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/jxl,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7")
                    .header("Accept-Language", "en-US,en;q=0.9,hi;q=0.8,hu;q=0.7")
                    .header("Cache-Control", "max-age=0")
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .header("Origin", origin)
                    .header("Referer", referer)
                    .header("Sec-Fetch-Dest", "document")
                    .header("Sec-Fetch-Mode", "navigate")
                    .header("Sec-Fetch-Site", "same-origin")
                    .header("Sec-Fetch-User", "?1")
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.0.0 Safari/537.36")
                    .header("sec-ch-ua", "\"Not:A-Brand\";v=\"8\", \"Chromium\";v=\"156\", \"Google Chrome\";v=\"156\"")
                    .header("sec-ch-ua-mobile", "?0")
                    .header("sec-ch-ua-platform", "\"Windows\"")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8));

            if (!cookie.isEmpty()) {
                reqBuilder.header("Cookie", cookie);
            }
            if (!csrfToken.isEmpty()) {
                reqBuilder.header("X-CSRF-TOKEN", csrfToken);
            }

            HttpResponse<String> response = client.send(reqBuilder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            long duration = System.currentTimeMillis() - startTime;

            return parseImportResponse(response, duration, totalLines, logger);

        } catch (HacException he) {
            long duration = System.currentTimeMillis() - startTime;
            logger.accept("HAC error: " + he.getMessage());
            return new HacImportResult(false, he.getMessage(), he.getServerResponse(), he.getMessage(), he.getStatusCode(), duration, totalLines);
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            logger.accept("Import execution failed: " + e.getMessage());
            return new HacImportResult(false, "Import execution failed: " + e.getMessage(), "", e.getMessage(), -1, duration, totalLines);
        }
    }

    private HacImportResult parseImportResponse(HttpResponse<String> response, long duration, int totalLines, Consumer<String> logger) {
        int status = response.statusCode();
        String body = response.body() != null ? response.body() : "";

        if (status == 302 || status == 303) {
            String location = response.headers().firstValue("Location").orElse("");
            logger.accept("Redirected to: " + location);
            if (location.contains("login")) {
                return new HacImportResult(false, "Session expired (redirected to login). Please update your Session Cookie and CSRF token.", body, "Session expired.", status, duration, totalLines);
            }
        }

        if (status == 403) {
            logger.accept("Received HTTP 403 Forbidden.");
            return new HacImportResult(false, "403 Forbidden: CSRF token invalid or expired. Please update the CSRF Token and Cookie.", body, "Access denied / Invalid CSRF token.", 403, duration, totalLines);
        }

        if (status >= 400) {
            logger.accept("Server returned HTTP " + status);
            return new HacImportResult(false, "HAC returned HTTP " + status, body, "HTTP Error " + status, status, duration, totalLines);
        }

        String lower = body.toLowerCase();

        // 1. Success indicators (case-insensitive)
        boolean hasSuccessNotice = lower.contains("import finished successfully") ||
                lower.contains("import finished without error") ||
                lower.contains("import has finished successfully") ||
                lower.contains("import completed successfully") ||
                lower.contains("import finished in") ||
                lower.contains("box successful") ||
                lower.contains("box success") ||
                lower.contains("class=\"box-icon tick\"") ||
                lower.contains("distributed import process has been started") ||
                lower.contains("process has been started");

        // 2. Error indicators (case-insensitive)
        boolean hasErrorNotice = lower.contains("import has encountered an error") ||
                lower.contains("import finished with error") ||
                lower.contains("import finished with errors") ||
                lower.contains("impexexception") ||
                lower.contains("dump file") ||
                lower.contains("cannot find target item") ||
                lower.contains("cannot resolve") ||
                lower.contains("column header cannot be resolved") ||
                lower.contains("attribute qualifier") ||
                lower.contains("box error") ||
                lower.contains("error box") ||
                lower.contains("box failed") ||
                lower.contains("class=\"box-icon error\"");

        String containerText = extractImpexResultContainer(body);
        String boxMessage = extractBoxMessage(body);

        if (hasSuccessNotice && !hasErrorNotice) {
            String msg = !boxMessage.isEmpty() ? boxMessage :
                    (!containerText.isEmpty() && containerText.length() < 250 ? containerText :
                            "Import finished successfully in " + duration + " ms (" + totalLines + " lines processed).");
            logger.accept("SUCCESS: " + msg);
            return new HacImportResult(true, msg, body, null, status, duration, totalLines);
        }

        String extractedError = extractErrorDetails(body);
        if (extractedError.isEmpty()) {
            if (!containerText.isEmpty()) {
                extractedError = containerText;
            } else if (!boxMessage.isEmpty()) {
                extractedError = boxMessage;
            } else if (hasSuccessNotice) {
                String msg = "Import finished with warnings in " + duration + " ms.";
                logger.accept(msg);
                return new HacImportResult(true, msg, body, null, status, duration, totalLines);
            } else {
                String clean = cleanHtml(body).replaceAll("\\s+", " ").trim();
                if (clean.length() > 300) {
                    clean = clean.substring(0, 300) + "...";
                }
                extractedError = !clean.isEmpty() ? clean : "ImpEx import completed without confirmation. Check server logs.";
            }
        }

        logger.accept("FAILED: " + (extractedError.length() > 300 ? extractedError.substring(0, 300) + "..." : extractedError));
        return new HacImportResult(false, "Import failed or encountered errors.", body, extractedError, status, duration, totalLines);
    }

    public static String extractImpexResultContainer(String html) {
        if (html == null || html.isEmpty()) return "";
        Pattern p = Pattern.compile("<(?:div|pre|span|section)[^>]*id=[\"']impexResult[\"'][^>]*>(.*?)</(?:div|pre|span|section)>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
        Matcher m = p.matcher(html);
        if (m.find()) {
            return cleanHtml(m.group(1)).trim();
        }
        Pattern p2 = Pattern.compile("<(?:div|pre|span|section)[^>]*class=[\"'][^\"']*impexResult[^\"']*[\"'][^>]*>(.*?)</(?:div|pre|span|section)>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
        Matcher m2 = p2.matcher(html);
        if (m2.find()) {
            return cleanHtml(m2.group(1)).trim();
        }
        return "";
    }

    public static String extractBoxMessage(String html) {
        if (html == null || html.isEmpty()) return "";
        Pattern p = Pattern.compile("<div[^>]*class=[\"'][^\"']*box\\s+([^\"']+)[\"'][^>]*>(.*?)</div>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
        Matcher m = p.matcher(html);
        if (m.find()) {
            return cleanHtml(m.group(2)).trim();
        }
        return "";
    }

    public static String extractErrorDetails(String html) {
        if (html == null || html.isEmpty()) return "";

        StringBuilder errors = new StringBuilder();

        // 1. Check #impexResult container
        String container = extractImpexResultContainer(html);
        if (!container.isEmpty()) {
            String lower = container.toLowerCase();
            if (lower.contains("error") || lower.contains("fail") || lower.contains("dump") || lower.contains("exception") || lower.contains("line")) {
                errors.append(container);
            }
        }

        // 2. Look for preformatted code / dump blocks
        if (errors.length() == 0) {
            Pattern prePattern = Pattern.compile("<pre[^>]*>(.*?)</pre>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
            Matcher preMatcher = prePattern.matcher(html);
            while (preMatcher.find()) {
                String content = cleanHtml(preMatcher.group(1)).trim();
                if (!content.isEmpty()) {
                    if (errors.length() > 0) errors.append("\n---\n");
                    errors.append(content);
                }
            }
        }

        // 3. Look for error boxes
        if (errors.length() == 0) {
            Pattern boxPattern = Pattern.compile("<div[^>]*class=[\"'][^\"']*(?:error|box)[^\"']*[\"'][^>]*>(.*?)</div>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
            Matcher boxMatcher = boxPattern.matcher(html);
            while (boxMatcher.find()) {
                String content = cleanHtml(boxMatcher.group(1)).trim();
                if (!content.isEmpty() && (content.toLowerCase().contains("error") || content.toLowerCase().contains("fail") || content.toLowerCase().contains("line"))) {
                    if (errors.length() > 0) errors.append("\n");
                    errors.append(content);
                }
            }
        }

        // 4. Fallback to scanning for "line X:" or "ImpExException" or "cannot find"
        if (errors.length() == 0) {
            Pattern linePattern = Pattern.compile("((?:line\\s+\\d+|ImpExException|Dump file|cannot find|cannot resolve|Column cannot)[^<\\n\\r]+)", Pattern.CASE_INSENSITIVE);
            Matcher lineMatcher = linePattern.matcher(html);
            while (lineMatcher.find()) {
                if (errors.length() > 0) errors.append("\n");
                errors.append(lineMatcher.group(1).trim());
            }
        }

        return errors.toString().trim();
    }

    private static String cleanHtml(String text) {
        if (text == null) return "";
        return text
                .replaceAll("(?i)<br\\s*/?>", "\n")
                .replaceAll("(?i)</p>", "\n")
                .replaceAll("<[^>]+>", "")
                .replace("&quot;", "\"")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&nbsp;", " ")
                .replaceAll("[ \\t]+", " ");
    }

    private String fetchLoginCsrf(HttpClient client, String endpoint) throws Exception {
        String[] candidatePaths = new String[]{
                endpoint + "/login.jsp",
                endpoint + "/login",
                deriveRootUrl(endpoint) + "/login.jsp",
                deriveRootUrl(endpoint) + "/hac/login.jsp"
        };

        HttpResponse<String> response = null;
        for (String url : candidatePaths) {
            try {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .timeout(TIMEOUT_CONNECT)
                        .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                        .GET()
                        .build();
                HttpResponse<String> r = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                if (r.statusCode() == 200) {
                    response = r;
                    break;
                }
            } catch (Exception ignored) {}
        }

        if (response == null || response.statusCode() != 200) {
            return "";
        }

        String csrf = extractCsrfToken(response.body());
        if (csrf == null || csrf.isEmpty()) {
            csrf = response.headers().firstValue("X-CSRF-TOKEN").orElse("");
        }
        return csrf;
    }

    private void authenticate(HttpClient client, String endpoint, String username, String password, String csrfToken) throws Exception {
        Map<String, String> creds = new LinkedHashMap<>();
        creds.put("j_username", username);
        creds.put("j_password", password);
        if (csrfToken != null && !csrfToken.isEmpty()) {
            creds.put("_csrf", csrfToken);
        }

        String formBody = buildFormData(creds);

        String[] authUrls = new String[]{
                endpoint + "/j_spring_security_check",
                deriveRootUrl(endpoint) + "/j_spring_security_check",
                deriveRootUrl(endpoint) + "/hac/j_spring_security_check"
        };

        HttpResponse<String> response = null;
        for (String authUrl : authUrls) {
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(authUrl))
                    .timeout(TIMEOUT_CONNECT)
                    .header("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                    .header("Referer", endpoint + "/login.jsp")
                    .POST(HttpRequest.BodyPublishers.ofString(formBody, StandardCharsets.UTF_8));

            if (csrfToken != null && !csrfToken.isEmpty()) {
                builder.header("X-CSRF-TOKEN", csrfToken);
            }

            try {
                HttpResponse<String> r = client.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                if (r.statusCode() != 404) {
                    response = r;
                    break;
                }
            } catch (Exception ignored) {}
        }

        if (response == null) {
            throw new HacException("Could not reach authentication endpoint (/j_spring_security_check) on HAC.");
        }

        int status = response.statusCode();
        if (status == 302 || status == 303) {
            String location = response.headers().firstValue("Location").orElse("");
            if (location.contains("login_error") || location.contains("error=true") || location.contains("login.jsp")) {
                throw new HacException("Authentication failed: Invalid username or password for '" + username + "'", 401, response.body());
            }
            return; // Successful login redirect
        }

        if (status == 200) {
            String body = response.body() != null ? response.body() : "";
            if (body.contains("Bad credentials") || body.contains("login_error")) {
                throw new HacException("Authentication failed: Invalid credentials for user '" + username + "'", 401, body);
            }
            return;
        }

        throw new HacException("Authentication failed: Server returned HTTP " + status, status, response.body());
    }

    private String fetchAuthenticatedCsrf(HttpClient client, String importUrl) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(importUrl))
                .timeout(TIMEOUT_CONNECT)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .GET()
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

        int status = response.statusCode();
        if (status == 302 || status == 303) {
            throw new HacException("Session expired or redirected to: " + response.headers().firstValue("Location").orElse("unknown"), status, null);
        }
        if (status == 403) {
            throw new HacException("Access forbidden to " + importUrl + ". User may not have admin privileges.", 403, response.body());
        }
        if (status >= 400) {
            throw new HacException("Failed to access " + importUrl + ": HTTP " + status, status, response.body());
        }

        String csrf = extractCsrfToken(response.body());
        return csrf != null ? csrf : "";
    }

    private String fetchAuthenticatedCsrfWithCookie(HttpClient client, String importUrl, String cookie) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(importUrl))
                .timeout(TIMEOUT_CONNECT)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .GET();

        if (cookie != null && !cookie.isEmpty()) {
            builder.header("Cookie", cookie);
        }

        HttpResponse<String> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        return extractCsrfToken(response.body());
    }

    public static String extractCsrfToken(String html) {
        if (html == null || html.isEmpty()) return "";

        Matcher m = CSRF_NAME_VALUE.matcher(html);
        if (m.find()) return m.group(1);

        m = CSRF_VALUE_NAME.matcher(html);
        if (m.find()) return m.group(1);

        m = CSRF_NAME_CONTENT.matcher(html);
        if (m.find()) return m.group(1);

        m = CSRF_CONTENT_NAME.matcher(html);
        if (m.find()) return m.group(1);

        return "";
    }

    private String deriveBaseUrl(String importUrl) {
        int idx = importUrl.indexOf("/console/");
        if (idx != -1) {
            return importUrl.substring(0, idx);
        }
        return configBaseUrl(importUrl);
    }

    private String deriveRootUrl(String url) {
        try {
            URI u = URI.create(url);
            String scheme = u.getScheme() != null ? u.getScheme() : "https";
            String host = u.getHost();
            int port = u.getPort();
            return scheme + "://" + host + (port != -1 && port != 80 && port != 443 ? ":" + port : "");
        } catch (Exception e) {
            return url;
        }
    }

    private String configBaseUrl(String url) {
        try {
            URI u = URI.create(url);
            String scheme = u.getScheme() != null ? u.getScheme() : "https";
            String host = u.getHost();
            int port = u.getPort();
            String base = scheme + "://" + host + (port != -1 && port != 80 && port != 443 ? ":" + port : "");
            if (url.contains("/hac")) {
                base += "/hac";
            }
            return base;
        } catch (Exception e) {
            return url;
        }
    }

    private HttpClient createHttpClient(HacConfig config) throws Exception {
        CookieManager cookieManager = new CookieManager(null, CookiePolicy.ACCEPT_ALL);

        HttpClient.Builder builder = HttpClient.newBuilder()
                .cookieHandler(cookieManager)
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(TIMEOUT_CONNECT);

        if (config.isTrustSelfSignedCerts()) {
            System.setProperty("jdk.internal.httpclient.disableHostnameVerification", "true");

            TrustManager[] trustAll = new TrustManager[]{
                    new X509TrustManager() {
                        public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
                        public void checkClientTrusted(X509Certificate[] certs, String authType) {}
                        public void checkServerTrusted(X509Certificate[] certs, String authType) {}
                    }
            };

            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, trustAll, new SecureRandom());
            builder.sslContext(sslContext);
        }

        return builder.build();
    }

    private static String buildFormData(Map<String, String> data) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> entry : data.entrySet()) {
            if (sb.length() > 0) sb.append("&");
            sb.append(URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8));
            sb.append("=");
            sb.append(URLEncoder.encode(entry.getValue() != null ? entry.getValue() : "", StandardCharsets.UTF_8));
        }
        return sb.toString();
    }

    private static String maskToken(String token) {
        if (token == null || token.length() <= 8) return "***";
        return token.substring(0, 4) + "..." + token.substring(token.length() - 4);
    }
}
