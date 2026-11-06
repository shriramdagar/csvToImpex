package com.paytm.hybris.impex.hac;

/**
 * Result of testing a connection and authentication against HAC.
 */
public class HacTestResult {

    private final boolean success;
    private final String message;
    private final long latencyMs;
    private final String authenticatedUser;
    private final String details;

    public HacTestResult(boolean success, String message, long latencyMs, String authenticatedUser) {
        this(success, message, latencyMs, authenticatedUser, null);
    }

    public HacTestResult(boolean success, String message, long latencyMs, String authenticatedUser, String details) {
        this.success = success;
        this.message = message;
        this.latencyMs = latencyMs;
        this.authenticatedUser = authenticatedUser;
        this.details = details;
    }

    public boolean isSuccess() {
        return success;
    }

    public String getMessage() {
        return message;
    }

    public long getLatencyMs() {
        return latencyMs;
    }

    public String getAuthenticatedUser() {
        return authenticatedUser;
    }

    public String getDetails() {
        return details;
    }

    @Override
    public String toString() {
        return (success ? "[SUCCESS] " : "[FAILED] ") + message + (latencyMs > 0 ? " (" + latencyMs + " ms)" : "");
    }
}
