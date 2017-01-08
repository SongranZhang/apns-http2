package com.linkedkeeper.apns.data;

import java.util.Date;

/**
 * @author frank@linkedkeeper.com on 2016/12/29.
 */
public class ApnsHttp2PushNotificationResponse<T extends ApnsPushNotification> implements ApnsPushNotificationResponse<T> {

    private final T apnsPushNotification;
    private final boolean success;
    private final String rejectionReason;
    private final Date tokenExpirationTimestamp;

    public ApnsHttp2PushNotificationResponse(T apnsPushNotification, boolean success, String rejectionReason, Date tokenExpirationTimestamp) {
        this.apnsPushNotification = apnsPushNotification;
        this.success = success;
        this.rejectionReason = rejectionReason;
        this.tokenExpirationTimestamp = tokenExpirationTimestamp;
    }

    @Override
    public T getApnsPushNotification() {
        return this.apnsPushNotification;
    }

    @Override
    public boolean isAccepted() {
        return this.success;
    }

    @Override
    public String getRejectionReason() {
        return this.rejectionReason;
    }

    @Override
    public Date getTokenInvalidationTimestamp() {
        return this.tokenExpirationTimestamp;
    }

    @Override
    public String toString() {
        final StringBuilder builder = new StringBuilder();
        builder.append("ApnsHttp2PushNotificationResponse [ApsnPushNotification=");
        builder.append(this.apnsPushNotification);
        builder.append(", success=");
        builder.append(this.success);
        builder.append(", rejectionReason=");
        builder.append(this.rejectionReason);
        builder.append(", tokenExpirationTimestamp=");
        builder.append(this.tokenExpirationTimestamp);
        builder.append("]");
        return builder.toString();
    }
}
