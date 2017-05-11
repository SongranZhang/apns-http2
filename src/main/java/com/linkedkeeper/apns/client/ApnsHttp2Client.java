package com.linkedkeeper.apns.client;

import com.linkedkeeper.apns.data.ApnsPushNotification;
import com.linkedkeeper.apns.data.ApnsPushNotificationResponse;
import com.linkedkeeper.apns.exceptions.ClientNotConnectedException;
import com.linkedkeeper.apns.utils.P12Utils;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.oio.OioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.channel.socket.oio.OioSocketChannel;
import io.netty.handler.codec.http2.Http2Error;
import io.netty.handler.codec.http2.Http2Exception;
import io.netty.handler.codec.http2.Http2SecurityUtil;
import io.netty.handler.ssl.*;
import io.netty.handler.ssl.ApplicationProtocolConfig.Protocol;
import io.netty.handler.ssl.ApplicationProtocolConfig.SelectedListenerFailureBehavior;
import io.netty.handler.ssl.ApplicationProtocolConfig.SelectorFailureBehavior;
import io.netty.handler.timeout.IdleStateHandler;
import io.netty.handler.timeout.WriteTimeoutHandler;
import io.netty.util.concurrent.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLException;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.security.KeyStore;
import java.security.KeyStore.PrivateKeyEntry;
import java.security.KeyStoreException;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * @author frank@linkedkeeper.com on 2016/12/27.
 */
public class ApnsHttp2Client<T extends ApnsPushNotification> {

    private static final Logger logger = LoggerFactory.getLogger(ApnsHttp2Client.class);

    private static final String EPOLL_EVENT_LOOP_GROUP_CLASS = "io.netty.channel.epoll.EpollEventLoopGroup";
    private static final String EPOLL_SOCKET_CHANNEL_CLASS = "io.netty.channel.epoll.EpollSocketChannel";

    private final Bootstrap bootstrap;
    private final boolean shouldShutDownEventLoopGroup;

    private Long gracefulShutdownTimeoutMillis;

    private volatile ChannelPromise connectionReadyPromise;
    private volatile ChannelPromise reconnectionPromise;
    private long reconnectDelaySeconds = ApnsHttp2Properties.INITIAL_RECONNECT_DELAY_SECONDS;

    private final Map<T, Promise<ApnsPushNotificationResponse<T>>> responsePromises = new IdentityHashMap<>();

    private ArrayList<String> identities;

    private static final ClientNotConnectedException NOT_CONNECTED_EXCEPTION = new ClientNotConnectedException();

    public ApnsHttp2Client(final File p12File, final String password) throws IOException, KeyStoreException {
        this(p12File, password, null);
    }

    public ApnsHttp2Client(final File p12File, final String password, final EventLoopGroup eventLoopGroup) throws IOException, KeyStoreException {
        this(ApnsHttp2Client.getSslContextWithP12File(p12File, password), eventLoopGroup);
        try (final InputStream p12InputStream = new FileInputStream(p12File)) {
            loadIdentifiers(loadKeyStore(p12InputStream, password));
        }
    }

    public ApnsHttp2Client(KeyStore keyStore, final String password) throws SSLException {
        this(keyStore, password, null);
    }

    public ApnsHttp2Client(final KeyStore keyStore, final String password, final EventLoopGroup eventLoopGroup) throws SSLException {
        this(ApnsHttp2Client.getSslContextWithP12InputStream(keyStore, password), eventLoopGroup);
        loadIdentifiers(keyStore);
    }

    public void abortConnection(ErrorResponse errorResponse) throws Http2Exception {
        disconnect();
        throw new Http2Exception(Http2Error.CONNECT_ERROR, errorResponse.getReason());
    }

    private static KeyStore loadKeyStore(final InputStream p12InputStream, final String password) throws SSLException {
        try {
            return P12Utils.loadPCKS12KeyStore(p12InputStream, password);
        } catch (KeyStoreException | IOException e) {
            throw new SSLException(e);
        }
    }

    private void loadIdentifiers(KeyStore keyStore) throws SSLException {
        try {
            this.identities = P12Utils.getIdentitiesForP12File(keyStore);
        } catch (KeyStoreException | IOException e) {
            throw new SSLException(e);
        }
    }

    public ApnsHttp2Client(final X509Certificate certificate, final PrivateKey privateKey, final String privateKeyPassword) throws SSLException {
        this(certificate, privateKey, privateKeyPassword, null);
    }

    public ApnsHttp2Client(final X509Certificate certificate, final PrivateKey privateKey, final String privateKeyPassword, final EventLoopGroup eventLoopGroup) throws SSLException {
        this(ApnsHttp2Client.getSslContextWithCertificateAndPrivateKey(certificate, privateKey, privateKeyPassword), eventLoopGroup);
    }

    private static SslContext getSslContextWithP12File(final File p12File, final String password) throws IOException, KeyStoreException {
        try (final InputStream p12InputStream = new FileInputStream(p12File)) {
            return ApnsHttp2Client.getSslContextWithP12InputStream(loadKeyStore(p12InputStream, password), password);
        }
    }

    private static SslContext getSslContextWithP12InputStream(final KeyStore keyStore, final String password) throws SSLException {
        final X509Certificate x509Certificate;
        final PrivateKey privateKey;
        try {
            final PrivateKeyEntry privateKeyEntry = P12Utils.getFirstPrivateKeyEntryFromP12InputStream(keyStore, password);
            final Certificate certificate = privateKeyEntry.getCertificate();
            if (!(certificate instanceof X509Certificate)) {
                throw new KeyStoreException("Found a certificate in the provided PKCS#12 file, but it was not an X.509 certificate.");
            }
            x509Certificate = (X509Certificate) certificate;
            privateKey = privateKeyEntry.getPrivateKey();
        } catch (final KeyStoreException | IOException e) {
            throw new SSLException(e);
        }
        return ApnsHttp2Client.getSslContextWithCertificateAndPrivateKey(x509Certificate, privateKey, password);
    }

    private static SslContext getSslContextWithCertificateAndPrivateKey(final X509Certificate certificate, final PrivateKey privateKey, final String privateKeyPassword) throws SSLException {
        return ApnsHttp2Client.getBaseSslContextBuilder()
                .keyManager(privateKey, privateKeyPassword, certificate)
                .build();
    }

    private static SslContextBuilder getBaseSslContextBuilder() {
        final SslProvider sslProvider;

        if (OpenSsl.isAvailable()) {
            if (OpenSsl.isAlpnSupported()) {
                sslProvider = SslProvider.OPENSSL;
            } else {
                sslProvider = SslProvider.JDK;
            }
        } else {
            sslProvider = SslProvider.JDK;
        }

        return SslContextBuilder.forClient()
                .sslProvider(sslProvider)
                .ciphers(Http2SecurityUtil.CIPHERS, SupportedCipherSuiteFilter.INSTANCE)
                .applicationProtocolConfig(
                        new ApplicationProtocolConfig(Protocol.ALPN,
                                SelectorFailureBehavior.NO_ADVERTISE,
                                SelectedListenerFailureBehavior.ACCEPT,
                                ApplicationProtocolNames.HTTP_2));
    }

    protected ApnsHttp2Client(final SslContext sslContext, final EventLoopGroup eventLoopGroup) {
        this.bootstrap = new Bootstrap();

        if (eventLoopGroup != null) {
            this.bootstrap.group(eventLoopGroup);
            this.shouldShutDownEventLoopGroup = false;
        } else {
            this.bootstrap.group(new NioEventLoopGroup(1));
            this.shouldShutDownEventLoopGroup = true;
        }
        this.bootstrap.channel(this.getSocketChannelClass(this.bootstrap.config().group()));
        this.bootstrap.option(ChannelOption.TCP_NODELAY, true);
        this.bootstrap.handler(new ChannelInitializer<SocketChannel>() {
            @Override
            protected void initChannel(final SocketChannel channel) throws Exception {
                final ChannelPipeline pipeline = channel.pipeline();

                if (ApnsHttp2Properties.DEFAULT_WRITE_TIMEOUT_MILLIS > 0) {
                    pipeline.addLast(new WriteTimeoutHandler(ApnsHttp2Properties.DEFAULT_WRITE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS));
                }

                pipeline.addLast(sslContext.newHandler(channel.alloc()));
                pipeline.addLast(new ApplicationProtocolNegotiationHandler("") {
                    @Override
                    protected void configurePipeline(final ChannelHandlerContext context, final String protocol) {
                        if (ApplicationProtocolNames.HTTP_2.equals(protocol)) {
                            final ApnsHttp2ClientHandler<T> apnsHttp2ClientHandler = new ApnsHttp2ClientHandler.ApnsHttp2ClientHandlerBuilder<T>()
                                    .server(false)
                                    .apnsHttp2Client(ApnsHttp2Client.this)
                                    .authority(((InetSocketAddress) context.channel().remoteAddress()).getHostName())
                                    .maxUnflushedNotifications(ApnsHttp2Properties.DEFAULT_MAX_UNFLUSHED_NOTIFICATIONS)
                                    .encoderEnforceMaxConcurrentStreams(true)
                                    .build();

                            synchronized (ApnsHttp2Client.this.bootstrap) {
                                if (ApnsHttp2Client.this.gracefulShutdownTimeoutMillis != null) {
                                    apnsHttp2ClientHandler.gracefulShutdownTimeoutMillis(ApnsHttp2Client.this.gracefulShutdownTimeoutMillis);
                                }
                            }

                            /** IdleStateHandler is send heart-beat to apns that remain the connection **/
                            context.pipeline().addLast(new IdleStateHandler(0,
                                    ApnsHttp2Properties.DEFAULT_FLUSH_AFTER_IDLE_MILLIS,
                                    ApnsHttp2Properties.PING_IDLE_TIME_MILLIS,
                                    TimeUnit.MILLISECONDS));
                            context.pipeline().addLast(apnsHttp2ClientHandler);

                            context.channel().eventLoop().submit(new Runnable() {
                                @Override
                                public void run() {
                                    final ChannelPromise connectionReadyPromise = ApnsHttp2Client.this.connectionReadyPromise;
                                    if (connectionReadyPromise != null) {
                                        connectionReadyPromise.trySuccess();
                                    }
                                }
                            });
                        } else {
                            logger.error("Unexpected protocol: {}", protocol);
                            context.close();
                        }
                    }

                    @Override
                    protected void handshakeFailure(final ChannelHandlerContext context, final Throwable cause) throws Exception {
                        final ChannelPromise connectionReadyPromise = ApnsHttp2Client.this.connectionReadyPromise;
                        if (connectionReadyPromise != null) {
                            connectionReadyPromise.tryFailure(cause);
                        }
                        super.handshakeFailure(context, cause);
                    }
                });
            }
        });
    }

    private Class<? extends Channel> getSocketChannelClass(final EventLoopGroup eventLoopGroup) {
        if (eventLoopGroup == null) {
            logger.warn("Asked for socket channel class to work with null event loop group, returning NioSocketChannel class.");
            return NioSocketChannel.class;
        }
        if (eventLoopGroup instanceof NioEventLoopGroup) {
            return NioSocketChannel.class;
        } else if (eventLoopGroup instanceof OioEventLoopGroup) {
            return OioSocketChannel.class;
        }
        final String className = eventLoopGroup.getClass().getName();
        if (EPOLL_EVENT_LOOP_GROUP_CLASS.equals(className)) {
            return this.loadSocketChannelClass(EPOLL_SOCKET_CHANNEL_CLASS);
        }

        throw new IllegalArgumentException(
                "Don't know which socket channel class to return for event loop group " + className);
    }

    private Class<? extends Channel> loadSocketChannelClass(final String className) {
        try {
            final Class<?> clazz = Class.forName(className);
            logger.info("Loaded socket channel class: {}", clazz);
            return clazz.asSubclass(Channel.class);
        } catch (final ClassNotFoundException e) {
            throw new IllegalArgumentException(e.getMessage(), e);
        }
    }

    public void setConnectionTimeout(final int timeoutMillis) {
        synchronized (this.bootstrap) {
            this.bootstrap.option(ChannelOption.CONNECT_TIMEOUT_MILLIS, timeoutMillis);
        }
    }

    public Future<Void> connect(final String host) {
        return this.connect(host, ApnsHttp2Properties.DEFAULT_APNS_PORT);
    }

    public Future<Void> connectSandBox() {
        return this.connect(ApnsHttp2Properties.DEVELOPMENT_APNS_HOST, ApnsHttp2Properties.DEFAULT_APNS_PORT);
    }

    public Future<Void> connectProduction() {
        return this.connect(ApnsHttp2Properties.PRODUCTION_APNS_HOST, ApnsHttp2Properties.DEFAULT_APNS_PORT);
    }

    public Future<Void> connect(final String host, final int port) {
        final Future<Void> connectionReadyFuture;

        if (this.bootstrap.config().group().isShuttingDown() || this.bootstrap.config().group().isShutdown()) {
            connectionReadyFuture = new FailedFuture<>(GlobalEventExecutor.INSTANCE,
                    new IllegalStateException("Client's event loop group has been shut down and cannot be restarted."));
        } else {
            synchronized (this.bootstrap) {
                logger.info("connect {}:{}. this connectionReadyPromise {}.", host, port, this.connectionReadyPromise);
                if (this.connectionReadyPromise == null) {
                    final ChannelFuture connectFuture = this.bootstrap.connect(host, port);
                    this.connectionReadyPromise = connectFuture.channel().newPromise();
                    /** this listener is add in channel, its effect is attempt to reconnect when the channel close **/
                    connectFuture.channel().closeFuture().addListener(new GenericFutureListener<ChannelFuture>() {
                        @Override
                        public void operationComplete(final ChannelFuture future) throws Exception {
                            logger.info("connectFuture.channel close operationComplete, this connectionReadyPromise {}, this reconnectionPromise {}",
                                    ApnsHttp2Client.this.connectionReadyPromise, ApnsHttp2Client.this.reconnectionPromise);
                            synchronized (ApnsHttp2Client.this.bootstrap) {
                                if (ApnsHttp2Client.this.connectionReadyPromise != null) {
                                    ApnsHttp2Client.this.connectionReadyPromise.tryFailure(
                                            new IllegalStateException("Channel closed before HTTP/2 preface completed."));
                                    ApnsHttp2Client.this.connectionReadyPromise = null;
                                }
                                if (ApnsHttp2Client.this.reconnectionPromise != null) {
                                    logger.error("Disconnected. Next automatic reconnection attempt in {} seconds.", ApnsHttp2Client.this.reconnectDelaySeconds);
                                    future.channel().eventLoop().schedule(new Runnable() {
                                        @Override
                                        public void run() {
                                            logger.warn("Attempting to reconnect.");
                                            ApnsHttp2Client.this.connect(host, port);
                                        }
                                    }, ApnsHttp2Client.this.reconnectDelaySeconds, TimeUnit.SECONDS);
                                    ApnsHttp2Client.this.reconnectDelaySeconds = Math.min(ApnsHttp2Client.this.reconnectDelaySeconds, ApnsHttp2Properties.MAX_RECONNECT_DELAY_SECONDS);
                                }
                            }
                            future.channel().eventLoop().submit(new Runnable() {
                                @Override
                                public void run() {
                                    for (final Promise<ApnsPushNotificationResponse<T>> responsePromise : ApnsHttp2Client.this.responsePromises.values()) {
                                        responsePromise.tryFailure(new ClientNotConnectedException("Client disconnected unexpectedly."));
                                    }
                                    ApnsHttp2Client.this.responsePromises.clear();
                                }
                            });
                        }
                    });
                    /** this listener's effect is get reconnectionPromise after connect success. **/
                    this.connectionReadyPromise.addListener(new GenericFutureListener<ChannelFuture>() {
                        @Override
                        public void operationComplete(final ChannelFuture future) throws Exception {
                            logger.info("connectionReadyPromise operationComplete, this connectionReadyPromise {}, this reconnectionPromise {}",
                                    ApnsHttp2Client.this.connectionReadyPromise, ApnsHttp2Client.this.reconnectionPromise);
                            if (future.isSuccess()) {
                                synchronized (ApnsHttp2Client.this.bootstrap) {
                                    if (ApnsHttp2Client.this.reconnectionPromise != null) {
                                        logger.info("Connection to {} restored.", future.channel().remoteAddress());
                                        ApnsHttp2Client.this.reconnectionPromise.trySuccess();
                                    } else {
                                        logger.info("Connected to {}.", future.channel().remoteAddress());
                                    }
                                    ApnsHttp2Client.this.reconnectDelaySeconds = ApnsHttp2Properties.INITIAL_RECONNECT_DELAY_SECONDS;
                                    ApnsHttp2Client.this.reconnectionPromise = future.channel().newPromise();
                                }
                            } else {
                                logger.error("Failed to connect.", future.cause());
                            }
                        }
                    });
                }

                if (this.connectionReadyPromise != null) {
                    logger.trace("this connectionReadyPromise isSuccess {} and its channel isActive {}", this.connectionReadyPromise.isSuccess(), this.connectionReadyPromise.channel().isActive());
                }
                connectionReadyFuture = this.connectionReadyPromise;
            }
        }
        return connectionReadyFuture;
    }

    public boolean isConnected() {
        final ChannelPromise connectionReadyPromise = this.connectionReadyPromise;
        return (connectionReadyPromise != null && connectionReadyPromise.isSuccess());
    }

    void waitForInitialSettings() throws InterruptedException {
        this.connectionReadyPromise.channel().pipeline().get(ApnsHttp2ClientHandler.class).waitForInitialSettings();
    }

    public Future<Void> getReconnectionFuture() {
        final Future<Void> reconnectionFuture;
        synchronized (this.bootstrap) {
            if (this.isConnected()) {
                reconnectionFuture = this.connectionReadyPromise.channel().newSucceededFuture();
            } else if (this.reconnectionPromise != null) {
                reconnectionFuture = this.reconnectionPromise;
            } else {
                reconnectionFuture = new FailedFuture<>(GlobalEventExecutor.INSTANCE,
                        new IllegalStateException("Client was not previously connected."));
            }
        }
        return reconnectionFuture;
    }

    public Future<ApnsPushNotificationResponse<T>> sendNotification(final T notification) {
        final Future<ApnsPushNotificationResponse<T>> responseFuture;

        if (connectionReadyPromise != null && connectionReadyPromise.isSuccess() && connectionReadyPromise.channel().isActive()) {
            verifyTopic(notification);

            final ChannelPromise connectionReadyPromise = this.connectionReadyPromise;
            final DefaultPromise<ApnsPushNotificationResponse<T>> responsePromise
                    = new DefaultPromise<>(connectionReadyPromise.channel().eventLoop());

            connectionReadyPromise.channel().eventLoop().submit(new Runnable() {
                @Override
                public void run() {
                    if (ApnsHttp2Client.this.responsePromises.containsKey(notification)) {
                        responsePromise.setFailure(new IllegalStateException(
                                "The given notification has already been sent and not yet resolved."));
                    } else {
                        ApnsHttp2Client.this.responsePromises.put(notification, responsePromise);
                    }
                }
            });

            connectionReadyPromise.channel().write(notification).addListener(new GenericFutureListener<ChannelFuture>() {
                @Override
                public void operationComplete(final ChannelFuture future) throws Exception {
                    if (!future.isSuccess()) {
                        logger.error("Failed to write push notification: {}", notification, future.cause());

                        ApnsHttp2Client.this.responsePromises.remove(notification);
                        responsePromise.tryFailure(future.cause());
                    } else {
                        logger.info("Success to write push notification: {}", notification);
                    }
                }
            });

            responseFuture = responsePromise;
        } else {
            logger.error("Failed to send push notification because client is not connected: {}", notification);
            responseFuture = new FailedFuture<>(GlobalEventExecutor.INSTANCE, NOT_CONNECTED_EXCEPTION);
        }

        return responseFuture;
    }

    private void verifyTopic(T notification) {
        if (notification.getTopic() == null
                && this.identities != null
                && !this.identities.isEmpty()) {
            notification.setTopic(this.identities.get(0));
        }
    }

    protected void handlePushNotificationResponse(final ApnsPushNotificationResponse<T> response) {
        logger.info("Received response from APNs gateway: {}", response);
        if (response.getApnsPushNotification() != null) {
            this.responsePromises.remove(response.getApnsPushNotification()).setSuccess(response);
        } else {
            this.responsePromises.clear();
        }
    }

    public void setGracefulShutdownTimeout(final long timeoutMillis) {
        synchronized (this.bootstrap) {
            this.gracefulShutdownTimeoutMillis = timeoutMillis;
            if (this.connectionReadyPromise != null) {
                final ApnsHttp2ClientHandler handler = this.connectionReadyPromise.channel().pipeline().get(ApnsHttp2ClientHandler.class);
                if (handler != null) {
                    handler.gracefulShutdownTimeoutMillis(timeoutMillis);
                }
            }
        }
    }

    public Future<Void> disconnect() {
        logger.info("Disconnecting.");
        final Future<Void> disconnectFuture;
        synchronized (this.bootstrap) {
            this.reconnectionPromise = null;

            final Future<Void> channelCloseFuture;

            if (this.connectionReadyPromise != null) {
                channelCloseFuture = this.connectionReadyPromise.channel().close();
            } else {
                channelCloseFuture = new SucceededFuture<>(GlobalEventExecutor.INSTANCE, null);
            }

            if (this.shouldShutDownEventLoopGroup) {
                channelCloseFuture.addListener(new GenericFutureListener<Future<Void>>() {
                    @Override
                    public void operationComplete(final Future<Void> future) throws Exception {
                        ApnsHttp2Client.this.bootstrap.config().group().shutdownGracefully();
                    }
                });
                disconnectFuture = new DefaultPromise<>(GlobalEventExecutor.INSTANCE);

                this.bootstrap.config().group().terminationFuture().addListener(new GenericFutureListener() {
                    @Override
                    public void operationComplete(final Future future) throws Exception {
                        assert disconnectFuture instanceof DefaultPromise;
                        ((DefaultPromise<Void>) disconnectFuture).trySuccess(null);
                    }
                });
            } else {
                disconnectFuture = channelCloseFuture;
            }
        }
        return disconnectFuture;
    }
}
