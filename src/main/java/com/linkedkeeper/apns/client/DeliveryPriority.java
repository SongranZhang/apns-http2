package com.linkedkeeper.apns.client;

/**
 * @author frank@linkedkeeper.com on 2016/12/29.
 */
public enum DeliveryPriority {

    IMMEDIATE(10),
    CONSERVE_POWER(5);

    private final int code;

    DeliveryPriority(int code) {
        this.code = code;
    }

    protected int getCode() {
        return this.code;
    }

    protected static DeliveryPriority getFromCode(final int code) {
        for (final DeliveryPriority priority : DeliveryPriority.values()) {
            if (priority.getCode() == code) {
                return priority;
            }
        }
        throw new IllegalArgumentException(String.format("No delivery priority found with code %d", code));
    }
}
