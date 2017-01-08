package com.linkedkeeper.apns.data;

import java.util.Date;

/**
 * @author frank@linkedkeeper.com on 2016/12/29.
 */
public interface ApnsPushNotificationResponse<T extends ApnsPushNotification> {

    T getApnsPushNotification();

    boolean isAccepted();

    String getRejectionReason();

    Date getTokenInvalidationTimestamp();

}
