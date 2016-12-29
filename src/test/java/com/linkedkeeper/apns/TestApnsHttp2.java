package com.linkedkeeper.apns;

import com.linkedkeeper.apns.client.ApnsHttp2;
import com.linkedkeeper.apns.data.Payload;
import org.apache.commons.lang.StringUtils;

import javax.net.ssl.SSLException;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.util.concurrent.ExecutionException;

/**
 * @author frank@linkedkeerp.com on 2016/12/28.
 */
public class TestApnsHttp2 {


    static final String PUSH_PATH_FILE = "push_release.p12";
    static final boolean product = true;
    static final String pwd = "123456";

    static final String goodToken = "<9d564a11 4490146d eb03cc13 e700e407 1438ba6c 74272e20 2fbd7548 b10dc974>";

    public static void main(String[] args) {
        try {
            ApnsHttp2 client = new ApnsHttp2(new FileInputStream(generatePushFile()), pwd);

            String paylaod = Payload.newPayload()
                    .alertBody("test message from apns http2")
                    .badge(1)
                    .build();
            client.pushMessageAsync(paylaod, splitDeviceToken(goodToken));
        } catch (SSLException e) {
            e.printStackTrace();
        } catch (FileNotFoundException e) {
            e.printStackTrace();
        } catch (ExecutionException e) {
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
