package com.linkedkeeper.apns.client;

class ApnsHttp2Properties {

    static final long DEFAULT_WRITE_TIMEOUT_MILLIS = 20_000;
    static final long DEFAULT_FLUSH_AFTER_IDLE_MILLIS = 50;
    static final int DEFAULT_MAX_UNFLUSHED_NOTIFICATIONS = 1280;
    static final String PRODUCTION_APNS_HOST = "api.push.apple.com";
    static final String DEVELOPMENT_APNS_HOST = "api.development.push.apple.com";
    static final int DEFAULT_APNS_PORT = 443;
    static final int ALTERNATE_APNS_PORT = 2197;
    static final long INITIAL_RECONNECT_DELAY_SECONDS = 1;
    static final long MAX_RECONNECT_DELAY_SECONDS = 60;
    static final int PING_IDLE_TIME_MILLIS = 60_000;
}
