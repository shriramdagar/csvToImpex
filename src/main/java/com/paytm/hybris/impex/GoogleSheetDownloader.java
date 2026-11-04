package com.paytm.hybris.impex;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Downloads public / shareable Google Sheets as XLSX or CSV using standard Java 11+ HttpClient.
 */
public class GoogleSheetDownloader {

    public static boolean isGoogleSheetUrl(String url) {
        return url != null && url.contains("docs.google.com/spreadsheets");
    }

    public static byte[] download(String url, boolean asExcel) throws Exception {
        String docId = extractDocId(url);
        String gid = extractGid(url);

        String exportUrl;
        if (asExcel) {
            exportUrl = "https://docs.google.com/spreadsheets/d/" + docId + "/export?format=xlsx";
        } else {
            String gidParam = (gid != null && !gid.isEmpty()) ? "&gid=" + gid : "";
            exportUrl = "https://docs.google.com/spreadsheets/d/" + docId + "/export?format=csv" + gidParam;
        }

        HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.ALWAYS)
                .connectTimeout(Duration.ofSeconds(20))
                .build();

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(exportUrl))
                .header("User-Agent", "Mozilla/5.0")
                .timeout(Duration.ofSeconds(45))
                .GET()
                .build();

        HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() >= 400) {
            throw new RuntimeException("Failed to download Google Sheet: HTTP " + response.statusCode());
        }

        return response.body();
    }

    public static String extractDocId(String url) {
        Pattern pattern = Pattern.compile("/spreadsheets/d/([a-zA-Z0-9-_]+)");
        Matcher matcher = pattern.matcher(url);
        if (matcher.find()) {
            return matcher.group(1);
        }
        throw new IllegalArgumentException("Cannot extract Google Sheet ID from URL: " + url);
    }

    public static String extractGid(String url) {
        Pattern pattern = Pattern.compile("[?&#]gid=([0-9]+)");
        Matcher matcher = pattern.matcher(url);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return null;
    }
}
