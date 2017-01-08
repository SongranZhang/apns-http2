package com.linkedkeeper.apns;

import com.linkedkeeper.apns.client.ApnsHttp2;
import com.linkedkeeper.apns.data.ApnsPushNotification;
import com.linkedkeeper.apns.data.ApnsPushNotificationResponse;
import com.linkedkeeper.apns.data.Payload;
import com.linkedkeeper.apns.exceptions.CertificateNotValidException;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang.time.DateFormatUtils;

import javax.net.ssl.SSLException;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.text.DateFormat;
import java.util.Date;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

/**
 * @author frank@linkedkeeper.com on 2016/12/28.
 */
public class TestApnsHttp2 {

    static final String PUSH_PATH_FILE = "push_release.p12";
    static final boolean product = true;
    static final String pwd = "123456";

    static final String goodToken = "<9d564a11 4490146d eb03cc13 e700e407 1438ba6c 74272e20 2fbd7548 b10dc974>";

    public static void main(String[] args) {
        try {
            ApnsHttp2 client = new ApnsHttp2(new FileInputStream(generatePushFile()), pwd)
                    .productMode();

            for (int i = 0; i < 10; i++) {
                String paylaod = Payload.newPayload()
                        .alertBody("test#1 apns-http2, i = " + i + " " + DateFormatUtils.format(new Date(), "yyyy-MM-dd HH:mm:ss"))
                        .badge(1)
                        .build();

                Future<ApnsPushNotificationResponse<ApnsPushNotification>> response = client.pushMessageAsync(paylaod, splitDeviceToken(goodToken));
                ApnsPushNotificationResponse<ApnsPushNotification> notification = response.get();
                boolean success = notification.isAccepted();
                System.out.println(success);

//                Thread.sleep(1000);
            }

            client.disconnect();
        } catch (SSLException e) {
            e.printStackTrace();
        } catch (FileNotFoundException e) {
            e.printStackTrace();
        } catch (ExecutionException e) {
            e.printStackTrace();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    private static String splitDeviceToken(String deviceToken) {
        return StringUtils.remove(StringUtils.remove(StringUtils.remove(deviceToken, "<"), ">"), " ");
    }

    private static String generatePushFile() {
        String path = "d:/";
        String pushFile = path + PUSH_PATH_FILE;
        return pushFile;
    }
}
