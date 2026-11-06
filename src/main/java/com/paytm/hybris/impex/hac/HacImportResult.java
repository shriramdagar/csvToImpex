package com.paytm.hybris.impex.hac;

/**
 * Result of executing an ImpEx import on HAC.
 */
public class HacImportResult {

    private final boolean success;
    private final String summary;
    private final String rawResponse;
    private final String errorDetails;
    private final int httpStatus;
    private final long durationMs;
    private final int totalLines;

    public HacImportResult(
            boolean success,
            String summary,
            String rawResponse,
            String errorDetails,
            int httpStatus,
            long durationMs,
            int totalLines
    ) {
        this.success = success;
        this.summary = summary;
        this.rawResponse = rawResponse;
        this.errorDetails = errorDetails;
        this.httpStatus = httpStatus;
        this.durationMs = durationMs;
        this.totalLines = totalLines;
    }

    public boolean isSuccess() {
        return success;
    }

    public String getSummary() {
        return summary;
    }

    public String getRawResponse() {
        return rawResponse;
    }

    public String getErrorDetails() {
        return errorDetails;
    }

    public int getHttpStatus() {
        return httpStatus;
    }

    public long getDurationMs() {
        return durationMs;
    }

    public int getTotalLines() {
        return totalLines;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append(success ? "SUCCESS: " : "FAILED: ").append(summary);
        if (durationMs > 0) {
            sb.append(" (").append(durationMs).append(" ms)");
        }
        if (!success && errorDetails != null && !errorDetails.isEmpty()) {
            sb.append("\nDetails:\n").append(errorDetails);
        }
        return sb.toString();
    }
}
