package com.linkedkeeper.apns.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.linkedkeeper.apns.data.ApnsHttp2PushNotificationResponse;
import com.linkedkeeper.apns.data.ApnsPushNotification;
import com.linkedkeeper.apns.utils.DateAsMillisecondsSinceEpochTypeAdapter;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http2.*;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.handler.timeout.WriteTimeoutException;
import io.netty.util.AsciiString;
import io.netty.util.concurrent.GenericFutureListener;
import io.netty.util.concurrent.PromiseCombiner;
import io.netty.util.concurrent.ScheduledFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * @author frank@linkedkeeper.com on 2016/12/28.
 */
class ApnsHttp2ClientHandler<T extends ApnsPushNotification> extends Http2ConnectionHandler {

    private static final Logger logger = LoggerFactory.getLogger(ApnsHttp2ClientHandler.class);

    private final AtomicBoolean receivedInitialSettings = new AtomicBoolean(false);
    private long nextStreamId = 1;

    private final Map<Integer, Http2Headers> headersByStreamId = new HashMap<>();
    private final Map<Integer, T> pushNotificationsByStreamId = new HashMap<>();

    private final ApnsHttp2Client<T> apnsHttp2Client;
    private final String authority;

    private long nextPingId = new Random().nextLong();
    private ScheduledFuture<?> pingTimeoutFuture;

    private final int maxUnflushedNotifications;
    private int unflushedNotifications = 0;

    private static final int PING_TIMEOUT_SECONDS = 30;

    private static final String APNS_PATH_PREFIX = "/3/device/";
    private static final AsciiString APNS_EXPIRATION_HEADER = new AsciiString("apns-expiration");
    private static final AsciiString APNS_TOPIC_HEADER = new AsciiString("apns-topic");
    private static final AsciiString APNS_PRIORITY_HEADER = new AsciiString("apns-priority");

    private static final int INITIAL_PAYLOAD_BUFFER_CAPACITY = 4096;
    private static final long STREAM_ID_RESET_THRESHOLD = Integer.MAX_VALUE - 1;

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
        public ApnsHttp2ClientHandler<S> build(final Http2ConnectionDecoder decoder, final Http2ConnectionEncoder encoder, final Http2Settings initialSettings) throws Exception {
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
            logger.info("Received GoAway from APNs server: {}", debugData.toString(StandardCharsets.UTF_8));

            ErrorResponse errorResponse = gson.fromJson(debugData.toString(StandardCharsets.UTF_8), ErrorResponse.class);
            ApnsHttp2ClientHandler.this.apnsHttp2Client.abortConnection(errorResponse);
        }
    }


    protected ApnsHttp2ClientHandler(final Http2ConnectionDecoder decoder, final Http2ConnectionEncoder encoder, final Http2Settings initialSettings, final ApnsHttp2Client<T> apnsHttp2Client, final String authority, final int maxUnflushedNotifications) {
        super(decoder, encoder, initialSettings);

        this.apnsHttp2Client = apnsHttp2Client;
        this.authority = authority;
        this.maxUnflushedNotifications = maxUnflushedNotifications;
    }

    @Override
    public void write(final ChannelHandlerContext context, final Object payload, final ChannelPromise writePromise) throws Http2Exception {
        try {
            final T pushNotification = (T) payload;
            final int streamId = (int) this.nextStreamId;

            final Http2Headers headers = new DefaultHttp2Headers()
                    .method(HttpMethod.POST.asciiName())
                    .authority(this.authority)
                    .path(APNS_PATH_PREFIX + pushNotification.getToken())
                    .addInt(APNS_EXPIRATION_HEADER, pushNotification.getExpiration() == null ? 0 : (int) (pushNotification.getExpiration().getTime() / 1000));

            if (pushNotification.getPriority() != null) {
                headers.addInt(APNS_PRIORITY_HEADER, pushNotification.getPriority().getCode());
            }
            if (pushNotification.getTopic() != null) {
                headers.add(APNS_TOPIC_HEADER, pushNotification.getTopic());
            }

            final ChannelPromise headersPromise = context.newPromise();
            this.encoder().writeHeaders(context, streamId, headers, 0, false, headersPromise);
            logger.trace("Wrote headers on stream {}: {}", streamId, headers);

            final ByteBuf payloadBuffer = context.alloc().ioBuffer(INITIAL_PAYLOAD_BUFFER_CAPACITY);
            payloadBuffer.writeBytes(pushNotification.getPayload().getBytes(StandardCharsets.UTF_8));

            final ChannelPromise dataPromise = context.newPromise();
            this.encoder().writeData(context, streamId, payloadBuffer, 0, true, dataPromise);
            logger.trace("Wrote payload on stream {}: {}", streamId, pushNotification.getPayload());

            final PromiseCombiner promiseCombiner = new PromiseCombiner();
            promiseCombiner.addAll(headersPromise, dataPromise);
            promiseCombiner.finish(writePromise);

            writePromise.addListener(new GenericFutureListener<ChannelPromise>() {
                @Override
                public void operationComplete(final ChannelPromise future) throws Exception {
                    if (future.isSuccess()) {
                        ApnsHttp2ClientHandler.this.pushNotificationsByStreamId.put(streamId, pushNotification);
                    } else {
                        logger.trace("Failed to write push notification on stream {}.", streamId, future.cause());
                    }
                }
            });

            this.nextStreamId += 2;

            if (++this.unflushedNotifications >= this.maxUnflushedNotifications) {
                this.flush(context);
            }
            if (this.nextStreamId >= STREAM_ID_RESET_THRESHOLD) {
                context.close();
            }
        } catch (final ClassCastException e) {
            logger.error("Unexpected object in pipeline: {}", payload);
            context.write(payload, writePromise);
        }
    }

    @Override
    public void flush(final ChannelHandlerContext context) throws Http2Exception {
        super.flush(context);
        this.unflushedNotifications = 0;
    }

    @Override
    public void userEventTriggered(final ChannelHandlerContext context, final Object event) throws Exception {
        if (event instanceof IdleStateEvent) {
            final IdleStateEvent idleStateEvent = (IdleStateEvent) event;

            if (IdleState.WRITER_IDLE.equals(idleStateEvent.state())) {
                if (this.unflushedNotifications > 0) {
                    this.flush(context);
                }
            } else {
                assert PING_TIMEOUT_SECONDS < ApnsHttp2Properties.PING_IDLE_TIME_MILLIS;

                logger.info("Sending ping due to inactivity.");

                final ByteBuf pingDataBuffer = context.alloc().ioBuffer(8, 8);
                pingDataBuffer.writeLong(this.nextPingId++);

                this.encoder().writePing(context, false, pingDataBuffer, context.newPromise().addListener(new GenericFutureListener<ChannelFuture>() {
                    @Override
                    public void operationComplete(final ChannelFuture future) throws Exception {
                        if (future.isSuccess()) {
                            ApnsHttp2ClientHandler.this.pingTimeoutFuture = future.channel().eventLoop().schedule(new Runnable() {
                                @Override
                                public void run() {
                                    logger.info("Closing channel due to ping timeout.");
                                    future.channel().close();
                                }
                            }, PING_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                        } else {
                            logger.error("Failed to write PING frame.", future.cause());
                            future.channel().close();
                        }
                    }
                }));
                this.flush(context);
            }
        }
        super.userEventTriggered(context, event);
    }

    @Override
    public void exceptionCaught(final ChannelHandlerContext context, final Throwable cause) throws Exception {
        if (cause instanceof WriteTimeoutException) {
            logger.error("Closing connection due to write timeout.");
            context.close();
        } else {
            logger.warn("APNs client pipeline exception.", cause);
        }
    }

    void waitForInitialSettings() throws InterruptedException {
        synchronized (this.receivedInitialSettings) {
            while (!this.receivedInitialSettings.get()) {
                this.receivedInitialSettings.wait();
            }
        }
    }
}
