#apns-http2
A Java library for sending notifications via APNS using Apple's new HTTP/2 API. This library uses Netty4.<br/>
Note: This is required until Java 7 is released.

#Installation
Clone this repository, and add it as a dependent maven project

#Usage
##Create a client
###Using provider certificates
```java 
ApnsHttp2 client = new ApnsHttp2(new FileInputStream("/path/to/certificate.p12", pwd).productMode();
```
###Build your notification
```java
tring paylaod = Payload.newPayload()
                        .alertBody("Hello")
                        .badge(1)
                        .build();
```
##Send the notification
###Asynchronous
```java
Future<ApnsPushNotificationResponse<ApnsPushNotification>> response 
        = client.pushMessageAsync(paylaod, "<the device token>");
ApnsPushNotificationResponse<ApnsPushNotification> notification = response.get();
boolean success = notification.isAccepted();
System.out.println(success);
```
###Synchronous
```java
ApnsPushNotificationResponse<ApnsPushNotification> notification 
        = client.pushMessageSync(paylaod, "<the device token>");
boolean success = notification.isAccepted();
System.out.println(success);
```
