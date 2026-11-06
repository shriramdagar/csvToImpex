package com.paytm.hybris.impex.hac;

/**
 * Exception thrown when a HAC connection, authentication, or import error occurs.
 */
public class HacException extends Exception {

    private final int statusCode;
    private final String serverResponse;

    public HacException(String message) {
        this(message, -1, null, null);
    }

    public HacException(String message, Throwable cause) {
        this(message, -1, null, cause);
    }

    public HacException(String message, int statusCode, String serverResponse) {
        this(message, statusCode, serverResponse, null);
    }

    public HacException(String message, int statusCode, String serverResponse, Throwable cause) {
        super(message, cause);
        this.statusCode = statusCode;
        this.serverResponse = serverResponse;
    }

    public int getStatusCode() {
        return statusCode;
    }

    public String getServerResponse() {
        return serverResponse;
    }
}
