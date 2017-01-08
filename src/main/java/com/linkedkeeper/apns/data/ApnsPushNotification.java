package com.linkedkeeper.apns.data;

import com.linkedkeeper.apns.client.DeliveryPriority;

import java.util.Date;

/**
 * @author frank@linkedkeeper.com on 2016/12/27.
 */
public interface ApnsPushNotification {

    String getToken();

    String getPayload();

    Date getExpiration();

    DeliveryPriority getPriority();

    String getTopic();

    void setTopic(String topic);

}
