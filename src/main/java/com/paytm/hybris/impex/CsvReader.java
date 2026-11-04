package com.paytm.hybris.impex;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Robust CSV reader with automatic delimiter sniffing and RFC-4180 quote escaping support.
 */
public class CsvReader {

    public static class CsvData {
        public List<String> headers = new ArrayList<>();
        public List<List<String>> rows = new ArrayList<>();
    }

    public static CsvData read(File file) throws IOException {
        String content = java.nio.file.Files.readString(file.toPath(), StandardCharsets.UTF_8);
        return parse(content);
    }

    public static CsvData parse(String content) {
        char delimiter = sniffDelimiter(content);
        return parseWithDelimiter(content, delimiter);
    }

    public static char sniffDelimiter(String sample) {
        String[] lines = sample.split("\r?\n");
        if (lines.length == 0) return ',';

        char[] candidates = new char[]{',', ';', '\t', '|'};
        char bestDelim = ',';
        int maxCount = -1;

        for (char c : candidates) {
            int countInLine0 = countOccurrences(lines[0], c);
            if (countInLine0 > maxCount) {
                maxCount = countInLine0;
                bestDelim = c;
            }
        }
        return bestDelim;
    }

    private static int countOccurrences(String str, char c) {
        int count = 0;
        for (int i = 0; i < str.length(); i++) {
            if (str.charAt(i) == c) count++;
        }
        return count;
    }

    public static CsvData parseWithDelimiter(String content, char delimiter) {
        CsvData data = new CsvData();
        List<String> currentRow = new ArrayList<>();
        StringBuilder currentField = new StringBuilder();
        boolean inQuotes = false;

        int len = content.length();
        for (int i = 0; i < len; i++) {
            char ch = content.charAt(i);

            if (inQuotes) {
                if (ch == '"') {
                    if (i + 1 < len && content.charAt(i + 1) == '"') {
                        currentField.append('"');
                        i++; // skip escaped quote
                    } else {
                        inQuotes = false;
                    }
                } else {
                    currentField.append(ch);
                }
            } else {
                if (ch == '"') {
                    inQuotes = true;
                } else if (ch == delimiter) {
                    currentRow.add(currentField.toString().trim());
                    currentField.setLength(0);
                } else if (ch == '\r') {
                    // skip or handle in \n
                } else if (ch == '\n') {
                    currentRow.add(currentField.toString().trim());
                    currentField.setLength(0);
                    addRow(data, currentRow);
                    currentRow = new ArrayList<>();
                } else {
                    currentField.append(ch);
                }
            }
        }

        if (currentField.length() > 0 || !currentRow.isEmpty()) {
            currentRow.add(currentField.toString().trim());
            addRow(data, currentRow);
        }

        return data;
    }

    private static void addRow(CsvData data, List<String> row) {
        boolean hasContent = row.stream().anyMatch(f -> !f.trim().isEmpty());
        if (!hasContent) return;

        if (data.headers.isEmpty()) {
            data.headers.addAll(row);
        } else {
            data.rows.add(row);
        }
    }
}
