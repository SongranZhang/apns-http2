package com.linkedkeeper.apns.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.linkedkeeper.apns.data.ApnsHttp2PushNotificationResponse;
import com.linkedkeeper.apns.data.ApnsPushNotification;
import com.linkedkeeper.apns.utils.DateAsMillisecondsSinceEpochTypeAdapter;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http2.*;
import io.netty.util.concurrent.ScheduledFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * @author frank@linkedkeerp.com on 2016/12/28.
 */
class ApnsHttp2ClientHandler<T extends ApnsPushNotification> extends Http2ConnectionHandler {

    private static final Logger logger = LoggerFactory.getLogger(ApnsHttp2ClientHandler.class);

    private final AtomicBoolean receivedInitialSettings = new AtomicBoolean(false);

    private final Map<Integer, Http2Headers> headersByStreamId = new HashMap<>();
    private final Map<Integer, T> pushNotificationsByStreamId = new HashMap<>();

    private final ApnsHttp2Client<T> apnsHttp2Client;
    private final String authority;
    private final int maxUnflushedNotifications;

    private ScheduledFuture<?> pingTimeoutFuture;

    private static final Gson gson = new GsonBuilder()
            .registerTypeAdapter(Date.class, new DateAsMillisecondsSinceEpochTypeAdapter())
            .create();

    public static class ApnsHttp2ClientHandlerBuilder<S extends ApnsPushNotification> extends AbstractHttp2ConnectionHandlerBuilder<ApnsHttp2ClientHandler<S>, ApnsHttp2ClientHandlerBuilder<S>> {

        private ApnsHttp2Client<S> apnsHttp2Client;
        private String authority;
        private int maxUnflushedNotifications = 0;

        public ApnsHttp2ClientHandlerBuilder<S> apnsHttp2Client(final ApnsHttp2Client<S> apnsHttp2Client) {
            this.apnsHttp2Client = apnsHttp2Client;
            return this;
        }

        public ApnsHttp2Client<S> apsnHttp2Client() {
            return this.apnsHttp2Client;
        }

        public ApnsHttp2ClientHandlerBuilder<S> authority(final String authority) {
            this.authority = authority;
            return this;
        }

        public String authority() {
            return this.authority;
        }

        public ApnsHttp2ClientHandlerBuilder<S> maxUnflushedNotifications(final int maxUnflushedNotifications) {
            this.maxUnflushedNotifications = maxUnflushedNotifications;
            return this;
        }

        public int maxUnflushedNotifications() {
            return this.maxUnflushedNotifications;
        }

        @Override
        public ApnsHttp2ClientHandlerBuilder<S> server(final boolean isServer) {
            return super.server(isServer);
        }

        @Override
        public ApnsHttp2ClientHandlerBuilder<S> encoderEnforceMaxConcurrentStreams(final boolean enforceMaxConcurrentStreams) {
            return super.encoderEnforceMaxConcurrentStreams(enforceMaxConcurrentStreams);
        }

        @Override
        protected ApnsHttp2ClientHandler<S> build(final Http2ConnectionDecoder decoder, final Http2ConnectionEncoder encoder, final Http2Settings initialSettings) throws Exception {
            Objects.requireNonNull(this.authority, "Authority must be set before building an HttpClientHandler.");

            final ApnsHttp2ClientHandler<S> handler = new ApnsHttp2ClientHandler<>(decoder, encoder, initialSettings, this.apnsHttp2Client, this.authority, this.maxUnflushedNotifications);
            this.frameListener(handler.new ApnsHttp2ClientHandlerFrameAdapter());
            return handler;
        }

        @Override
        public ApnsHttp2ClientHandler<S> build() {
            return super.build();
        }
    }

    protected ApnsHttp2ClientHandler(final Http2ConnectionDecoder decoder, final Http2ConnectionEncoder encoder, final Http2Settings initialSettings, final ApnsHttp2Client<T> apnsHttp2Client, final String authority, final int maxUnflushedNotifications) {
        super(decoder, encoder, initialSettings);

        this.apnsHttp2Client = apnsHttp2Client;
        this.authority = authority;
        this.maxUnflushedNotifications = maxUnflushedNotifications;
    }

    private class ApnsHttp2ClientHandlerFrameAdapter extends Http2FrameAdapter {
        @Override
        public void onSettingsRead(final ChannelHandlerContext context, final Http2Settings settings) {
            logger.trace("Received settings from APNs gateway: {}", settings);

            synchronized (ApnsHttp2ClientHandler.this.receivedInitialSettings) {
                ApnsHttp2ClientHandler.this.receivedInitialSettings.set(true);
                ApnsHttp2ClientHandler.this.receivedInitialSettings.notifyAll();
            }
        }

        @Override
        public int onDataRead(final ChannelHandlerContext context, final int streamId, final ByteBuf data, final int padding, final boolean endOfStream) throws Http2Exception {
            logger.trace("Received data from APNs gateway on stream {}: {}", streamId, data.toString(StandardCharsets.UTF_8));

            final int bytesProcessed = data.readableBytes() + padding;

            if (endOfStream) {
                final Http2Headers headers = ApnsHttp2ClientHandler.this.headersByStreamId.remove(streamId);
                final T pushNotification = ApnsHttp2ClientHandler.this.pushNotificationsByStreamId.remove(streamId);

                final boolean success = HttpResponseStatus.OK.equals(HttpResponseStatus.parseLine(headers.status()));
                final ErrorResponse errorResponse = gson.fromJson(data.toString(StandardCharsets.UTF_8), ErrorResponse.class);

                ApnsHttp2ClientHandler.this.apnsHttp2Client.handlePushNotificationResponse(new ApnsHttp2PushNotificationResponse<>(
                        pushNotification, success, errorResponse.getReason(), errorResponse.getTimestamp()));
            } else {
                logger.error("Gateway sent a DATA frame that was not the end of a stream.");
            }

            return bytesProcessed;
        }

        @Override
        public void onHeadersRead(final ChannelHandlerContext context, final int streamId, final Http2Headers headers, final int streamDependency, final short weight, final boolean exclusive, final int padding, final boolean endOfStream) throws Http2Exception {
            this.onHeadersRead(context, streamId, headers, padding, endOfStream);
        }

        @Override
        public void onHeadersRead(final ChannelHandlerContext context, final int streamId, final Http2Headers headers, final int padding, final boolean endOfStream) throws Http2Exception {
            logger.trace("Received headers from APNs gateway on stream {}: {}", streamId, headers);

            final boolean success = HttpResponseStatus.OK.equals(HttpResponseStatus.parseLine(headers.status()));

            if (endOfStream) {
                if (!success) {
                    logger.error("Gateway sent an end-of-stream HEADERS frame for an unsuccessful notification.");
                }
                final T pushNotification = ApnsHttp2ClientHandler.this.pushNotificationsByStreamId.remove(streamId);
                ApnsHttp2ClientHandler.this.apnsHttp2Client.handlePushNotificationResponse(new ApnsHttp2PushNotificationResponse<>(
                        pushNotification, success, null, null));
            } else {
                ApnsHttp2ClientHandler.this.headersByStreamId.put(streamId, headers);
            }
        }

        @Override
        public void onPingAckRead(final ChannelHandlerContext context, final ByteBuf data) {
            if (ApnsHttp2ClientHandler.this.pingTimeoutFuture != null) {
                logger.trace("Received reply to ping.");
                ApnsHttp2ClientHandler.this.pingTimeoutFuture.cancel(false);
            } else {
                logger.error("Received PING ACK, but no corresponding outbound PING found.");
            }
        }

        @Override
        public void onGoAwayRead(final ChannelHandlerContext context, final int lastStreamId, final long errorCode, final ByteBuf debugData) throws Http2Exception {
            logger.info("Received GOAWAY from APNs server: {}", debugData.toString(StandardCharsets.UTF_8));

            ErrorResponse errorResponse = gson.fromJson(debugData.toString(StandardCharsets.UTF_8), ErrorResponse.class);
            ApnsHttp2ClientHandler.this.apnsHttp2Client.abortConnection(errorResponse);
        }
    }

    // todo write

    // todo flush

    // todo userEventTriggered

    // todo exceptionCaught

    // todo waitForInitialSettings
}
























