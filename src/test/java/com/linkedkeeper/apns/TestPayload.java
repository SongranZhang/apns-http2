package com.linkedkeeper.apns;

import com.linkedkeeper.apns.data.Payload;

import java.util.HashMap;
import java.util.Map;

/**
 * Created by zhangsongran@linkedkeeper.com on 2017/6/9.
 */
public class TestPayload {

    public static void main(String[] args) {
        Map<String, Object> data = new HashMap<>();
        data.put("a", 1);
        data.put("b", 2);
        data.put("c", "3");

        String paylaod = Payload.newPayload()
                .alertBody("alertContext")
                .badge(1)
                .sound()
                .addField("test1", "abc")
                .addField("test2", 123)
                .addField("test3", data)
                .build();

        System.out.println(paylaod);
    }
}
