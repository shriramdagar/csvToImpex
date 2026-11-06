package com.paytm.hybris.impex.hac;

import java.io.*;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Base64;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Configuration and persistent credentials for connecting to SAP Commerce HAC.
 * Supports direct execution via Import URL, Session Cookies, and CSRF Token,
 * as well as parsing cURL commands copied from DevTools.
 */
public class HacConfig {

    private String importUrl = "https://backoffice-internal.c0a7xy-abdullati1-s1-public.model-t.cc.commerce.ondemand.com/hac/console/impex/import";
    private String cookie = "";
    private String csrfToken = "";
    private String username = "admin";
    private String password = "";

    private String validationEnum = "IMPORT_STRICT";
    private int maxThreads = 16;
    private String encoding = "UTF-8";
    private boolean legacyMode = false;
    private boolean enableCodeExecution = true;
    private boolean distributedMode = false;
    private boolean sldEnabled = false;

    private boolean trustSelfSignedCerts = true;
    private boolean rememberSettings = true;

    private static final String CONFIG_DIR = ".hybris-impex-converter";
    private static final String CONFIG_FILE = "hac.properties";

    public HacConfig() {
    }

    public HacConfig(String importUrl, String cookie, String csrfToken) {
        this.importUrl = importUrl;
        this.cookie = cookie;
        this.csrfToken = csrfToken;
    }

    public String getImportUrl() {
        return importUrl != null ? importUrl.trim() : "";
    }

    public void setImportUrl(String importUrl) {
        this.importUrl = importUrl;
    }

    public String getCookie() {
        return cookie != null ? cookie.trim() : "";
    }

    public void setCookie(String cookie) {
        this.cookie = cookie;
    }

    public String getCsrfToken() {
        return csrfToken != null ? csrfToken.trim() : "";
    }

    public void setCsrfToken(String csrfToken) {
        this.csrfToken = csrfToken;
    }

    public String getUsername() {
        return username != null ? username.trim() : "";
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password != null ? password : "";
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getValidationEnum() {
        return validationEnum != null ? validationEnum : "IMPORT_STRICT";
    }

    public void setValidationEnum(String validationEnum) {
        this.validationEnum = validationEnum;
    }

    public int getMaxThreads() {
        return maxThreads > 0 ? maxThreads : 16;
    }

    public void setMaxThreads(int maxThreads) {
        this.maxThreads = maxThreads;
    }

    public String getEncoding() {
        return encoding != null && !encoding.trim().isEmpty() ? encoding.trim() : "UTF-8";
    }

    public void setEncoding(String encoding) {
        this.encoding = encoding;
    }

    public boolean isLegacyMode() {
        return legacyMode;
    }

    public void setLegacyMode(boolean legacyMode) {
        this.legacyMode = legacyMode;
    }

    public boolean isEnableCodeExecution() {
        return enableCodeExecution;
    }

    public void setEnableCodeExecution(boolean enableCodeExecution) {
        this.enableCodeExecution = enableCodeExecution;
    }

    public boolean isDistributedMode() {
        return distributedMode;
    }

    public void setDistributedMode(boolean distributedMode) {
        this.distributedMode = distributedMode;
    }

    public boolean isSldEnabled() {
        return sldEnabled;
    }

    public void setSldEnabled(boolean sldEnabled) {
        this.sldEnabled = sldEnabled;
    }

    public boolean isTrustSelfSignedCerts() {
        return trustSelfSignedCerts;
    }

    public void setTrustSelfSignedCerts(boolean trustSelfSignedCerts) {
        this.trustSelfSignedCerts = trustSelfSignedCerts;
    }

    public boolean isRememberSettings() {
        return rememberSettings;
    }

    public void setRememberSettings(boolean rememberSettings) {
        this.rememberSettings = rememberSettings;
    }

    /**
     * Resolves the normalized Import URL.
     */
    public String getEffectiveImportUrl() {
        String url = getImportUrl();
        if (url.isEmpty()) return "";
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            url = "https://" + url;
        }
        if (!url.contains("/console/impex/import")) {
            if (url.endsWith("/")) url = url.substring(0, url.length() - 1);
            if (url.endsWith("/hac")) {
                url = url + "/console/impex/import";
            } else {
                url = url + "/hac/console/impex/import";
            }
        }
        return url;
    }

    /**
     * Extracts origin URL from the import URL (e.g., https://host:port).
     */
    public String getOrigin() {
        try {
            URI u = URI.create(getEffectiveImportUrl());
            String scheme = u.getScheme() != null ? u.getScheme() : "https";
            String host = u.getHost();
            int port = u.getPort();
            if (port != -1 && port != 80 && port != 443) {
                return scheme + "://" + host + ":" + port;
            }
            return scheme + "://" + host;
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * Extracts referer URL.
     */
    public String getReferer() {
        String eff = getEffectiveImportUrl();
        int idx = eff.lastIndexOf("/import");
        if (idx != -1) {
            return eff.substring(0, idx) + "/import";
        }
        return eff;
    }

    /**
     * Parses a pasted cURL command from Chrome DevTools to extract
     * URL, Cookie, and CSRF token automatically.
     */
    public boolean parseFromCurl(String curl) {
        if (curl == null || curl.trim().isEmpty()) return false;
        boolean updated = false;

        // Clean up Windows escape carets if present
        String cleanCurl = curl.replace("^\n", "\n").replace("^\r", "\r").replace("^\"", "\"").replace("\\\"", "\"");

        // Extract URL
        Pattern urlPattern = Pattern.compile("(?:--url\\s+[\"']?|curl\\s+[\"']?)(https?://[^\"'\\s]+)", Pattern.CASE_INSENSITIVE);
        Matcher urlMatcher = urlPattern.matcher(cleanCurl);
        if (urlMatcher.find()) {
            this.importUrl = urlMatcher.group(1).trim();
            updated = true;
        }

        // Extract Cookie from -b or -H "Cookie: ..."
        Pattern cookiePattern = Pattern.compile("(?:-b|-H\\s+[\"']Cookie:)\\s+[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE);
        Matcher cookieMatcher = cookiePattern.matcher(cleanCurl);
        if (cookieMatcher.find()) {
            this.cookie = cookieMatcher.group(1).trim();
            updated = true;
        }

        // Extract CSRF token from --data or --data-raw or form fields
        Pattern csrfPattern = Pattern.compile("_csrf=([^&\"'\\s]+)", Pattern.CASE_INSENSITIVE);
        Matcher csrfMatcher = csrfPattern.matcher(cleanCurl);
        if (csrfMatcher.find()) {
            try {
                this.csrfToken = URLDecoder.decode(csrfMatcher.group(1).trim(), StandardCharsets.UTF_8);
            } catch (Exception e) {
                this.csrfToken = csrfMatcher.group(1).trim();
            }
            updated = true;
        } else {
            // Check X-CSRF-TOKEN header
            Pattern csrfHeader = Pattern.compile("X-CSRF-TOKEN:\\s*([^\"'\\r\\n]+)", Pattern.CASE_INSENSITIVE);
            Matcher cm = csrfHeader.matcher(cleanCurl);
            if (cm.find()) {
                this.csrfToken = cm.group(1).trim();
                updated = true;
            }
        }

        // Extract maxThreads if present
        Pattern threadsPattern = Pattern.compile("maxThreads=(\\d+)", Pattern.CASE_INSENSITIVE);
        Matcher tm = threadsPattern.matcher(cleanCurl);
        if (tm.find()) {
            try {
                this.maxThreads = Integer.parseInt(tm.group(1));
            } catch (NumberFormatException ignored) {}
        }

        // Extract validationEnum
        Pattern valPattern = Pattern.compile("validationEnum=([A-Z_]+)", Pattern.CASE_INSENSITIVE);
        Matcher vm = valPattern.matcher(cleanCurl);
        if (vm.find()) {
            this.validationEnum = vm.group(1).trim();
        }

        return updated;
    }

    public static Path getConfigFilePath() {
        String userHome = System.getProperty("user.home");
        return Paths.get(userHome, CONFIG_DIR, CONFIG_FILE);
    }

    public static HacConfig load() {
        HacConfig config = new HacConfig();
        Path path = getConfigFilePath();
        if (Files.exists(path)) {
            Properties props = new Properties();
            try (InputStream in = Files.newInputStream(path)) {
                props.load(in);
                config.setImportUrl(props.getProperty("hac.importUrl", config.importUrl));
                config.setCookie(props.getProperty("hac.cookie", ""));
                config.setCsrfToken(props.getProperty("hac.csrfToken", ""));
                config.setUsername(props.getProperty("hac.username", "admin"));
                config.setValidationEnum(props.getProperty("hac.validationEnum", "IMPORT_STRICT"));
                config.setMaxThreads(Integer.parseInt(props.getProperty("hac.maxThreads", "16")));
                config.setEncoding(props.getProperty("hac.encoding", "UTF-8"));
                config.setLegacyMode(Boolean.parseBoolean(props.getProperty("hac.legacyMode", "false")));
                config.setEnableCodeExecution(Boolean.parseBoolean(props.getProperty("hac.enableCodeExecution", "true")));
                config.setDistributedMode(Boolean.parseBoolean(props.getProperty("hac.distributedMode", "false")));
                config.setSldEnabled(Boolean.parseBoolean(props.getProperty("hac.sldEnabled", "false")));
                config.setTrustSelfSignedCerts(Boolean.parseBoolean(props.getProperty("hac.trustSelfSignedCerts", "true")));
                config.setRememberSettings(Boolean.parseBoolean(props.getProperty("hac.rememberSettings", "true")));

                String encodedPass = props.getProperty("hac.password", "");
                if (config.isRememberSettings() && !encodedPass.isEmpty()) {
                    try {
                        byte[] decoded = Base64.getDecoder().decode(encodedPass);
                        config.setPassword(new String(decoded, StandardCharsets.UTF_8));
                    } catch (Exception ignored) {
                        config.setPassword(encodedPass);
                    }
                }
            } catch (Exception e) {
                System.err.println("[WARN] Could not load HAC configuration: " + e.getMessage());
            }
        }
        return config;
    }

    public void save() {
        if (!rememberSettings) return;
        try {
            Path path = getConfigFilePath();
            if (path.getParent() != null && !Files.exists(path.getParent())) {
                Files.createDirectories(path.getParent());
            }

            Properties props = new Properties();
            props.setProperty("hac.importUrl", getImportUrl());
            props.setProperty("hac.cookie", getCookie());
            props.setProperty("hac.csrfToken", getCsrfToken());
            props.setProperty("hac.username", getUsername());
            props.setProperty("hac.validationEnum", getValidationEnum());
            props.setProperty("hac.maxThreads", String.valueOf(getMaxThreads()));
            props.setProperty("hac.encoding", getEncoding());
            props.setProperty("hac.legacyMode", String.valueOf(isLegacyMode()));
            props.setProperty("hac.enableCodeExecution", String.valueOf(isEnableCodeExecution()));
            props.setProperty("hac.distributedMode", String.valueOf(isDistributedMode()));
            props.setProperty("hac.sldEnabled", String.valueOf(isSldEnabled()));
            props.setProperty("hac.trustSelfSignedCerts", String.valueOf(isTrustSelfSignedCerts()));
            props.setProperty("hac.rememberSettings", String.valueOf(isRememberSettings()));

            if (!getPassword().isEmpty()) {
                String encoded = Base64.getEncoder().encodeToString(getPassword().getBytes(StandardCharsets.UTF_8));
                props.setProperty("hac.password", encoded);
            } else {
                props.setProperty("hac.password", "");
            }

            try (OutputStream out = Files.newOutputStream(path)) {
                props.store(out, "Hybris Administration Console (HAC) Connection Settings");
            }
        } catch (Exception e) {
            System.err.println("[WARN] Could not save HAC configuration: " + e.getMessage());
        }
    }
}
