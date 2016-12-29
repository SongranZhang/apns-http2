package com.linkedkeeper.apns.utils;

import com.google.gson.*;

import java.lang.reflect.Type;
import java.util.Date;

/**
 * @author frank@linkedkeerp.com on 2016/12/28.
 */
public class DateAsMillisecondsSinceEpochTypeAdapter implements JsonSerializer<Date>, JsonDeserializer<Date> {
    @Override
    public JsonElement serialize(Date date, Type type, JsonSerializationContext jsonSerializationContext) {
        return null;
    }

    @Override
    public Date deserialize(JsonElement jsonElement, Type type, JsonDeserializationContext jsonDeserializationContext) throws JsonParseException {
        return null;
    }
}
