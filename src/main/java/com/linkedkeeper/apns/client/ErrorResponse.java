package com.linkedkeeper.apns.client;

import java.util.Date;

/**
 * @author frank@linkedkeeper.com on 2016/12/28.
 */
public class ErrorResponse {

    private final String reason;
    private final Date timestamp;

    public ErrorResponse(String reason, Date timestamp) {
        this.reason = reason;
        this.timestamp = timestamp;
    }

    public String getReason() {
        return reason;
    }

    public Date getTimestamp() {
        return timestamp;
    }

    @Override
    public String toString() {
        final StringBuilder builder = new StringBuilder();
        builder.append("ErrorResponse [reason=");
        builder.append(this.reason);
        builder.append(", timestamp=");
        builder.append(this.timestamp);
        builder.append("]");
        return builder.toString();
    }
}
