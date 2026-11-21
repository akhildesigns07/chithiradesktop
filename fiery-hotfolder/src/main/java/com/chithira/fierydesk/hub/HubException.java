package com.chithira.fierydesk.hub;

import java.io.IOException;

/**
 * The hub answered with an error, or could not be reached ({@code status} 0).
 * The message is meant for staff, e.g. "Print Hub rejected the API key".
 */
public class HubException extends IOException {

    private final int status;
    private final String responseBody;

    public HubException(int status, String message, String responseBody) {
        super(message);
        this.status = status;
        this.responseBody = responseBody;
    }

    /** HTTP status, or 0 when the hub was not reached at all. */
    public int status() { return status; }

    public String responseBody() { return responseBody; }

    /** Worth retrying later: hub unreachable, overloaded or failing (not a problem with the request itself). */
    public boolean isTemporary() { return status == 0 || status == 408 || status == 429 || status >= 500; }

    /** The API key is wrong or revoked: nothing will work until the config is fixed. */
    public boolean isKeyProblem() { return status == 401 || status == 403; }
}
