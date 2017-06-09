package com.linkedkeeper.apns.data;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

/**
 * Represents a builder for constructing Payload requests, as
 * specified by Apple Push Notification Programming Guide.
 *
 * @author frank@linkedkeeper.com on 2016/12/29.
 */
public final class PayloadBuilder {

    private static final ObjectMapper mapper = new ObjectMapper();

    private final Map<String, Object> root;
    private final Map<String, Object> aps;
    private final Map<String, Object> customAlert;
    private final Map<String, Object> fields;

    /**
     * Constructs a new instance of {@code PayloadBuilder}
     */
    public PayloadBuilder() {
        this.root = new HashMap<>();
        this.aps = new HashMap<>();
        this.customAlert = new HashMap<>();
        this.fields = new HashMap<>();
    }

    /**
     * Sets the alert body text, the text the appears to the user,
     * to the passed value
     *
     * @param alert the text to appear to the user
     * @return this
     */
    public PayloadBuilder alertBody(final String alert) {
        customAlert.put("body", alert);
        return this;
    }

    /**
     * Sets the notification badge to be displayed next to the
     * application icon.
     * <p/>
     * The passed value is the value that should be displayed
     * (it will be added to the previous badge number), and
     * a badge of 0 clears the badge indicator.
     *
     * @param badge the badge number to be displayed
     * @return this
     */
    public PayloadBuilder badge(final int badge) {
        aps.put("badge", badge);
        return this;
    }

    /**
     * Sets
     *
     * @return this
     */
    public PayloadBuilder sound() {
        aps.put("sound", "default");
        return this;
    }

    /**
     * Sets
     *
     * @param sound
     * @return this
     */
    public PayloadBuilder sound(final String sound) {
        aps.put("sound", sound);
        return this;
    }

    /**
     * Sets
     *
     * @return this
     */
    public PayloadBuilder addField(final String key, final Object value) {
        fields.put(key, value);
        return this;
    }

    public PayloadBuilder addField(final String key, final Map<String, Object> value) {
        fields.put(key, value);
        return this;
    }

    /**
     * Returns the JSON String representation of the payload
     * according to Apple APNS specification
     *
     * @return the String representation as expected by Apple
     */
    public String build() {
        if (!root.containsKey("mdm")) {
            insertCustomAlert();
            root.put("aps", aps);
        }
        if (fields.size() > 0) {
            root.putAll(fields);
        }
        try {
            return mapper.writeValueAsString(root);
        } catch (final Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void insertCustomAlert() {
        switch (customAlert.size()) {
            case 0:
                aps.remove("alert");
                break;
            case 1:
                if (customAlert.containsKey("body")) {
                    aps.put("alert", customAlert.get("body"));
                    break;
                }
                // else follow through
                //$FALL-THROUGH$
            default:
                aps.put("alert", customAlert);
        }
    }
}
