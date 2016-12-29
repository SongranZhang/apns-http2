package com.linkedkeeper.apns.client;

import com.linkedkeeper.apns.data.ApnsPushNotification;
import com.linkedkeeper.apns.data.ApnsPushNotificationResponse;
import com.linkedkeeper.apns.proxy.ProxyHandlerFactory;
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
import io.netty.util.concurrent.Promise;
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
 * @author frank@linkedkeerp.com on 2016/12/27.
 */
public class ApnsHttp2Client<T extends ApnsPushNotification> {

    private static final Logger logger = LoggerFactory.getLogger(ApnsHttp2Client.class);

    private static final String EPOLL_EVENT_LOOP_GROUP_CLASS = "io.netty.channel.epoll.EpollEventLoopGroup";
    private static final String EPOLL_SOCKET_CHANNEL_CLASS = "io.netty.channel.epoll.EpollSocketChannel";

    private final Bootstrap bootstrap;
    private final boolean shouldShutDownEventLoopGroup;
    private volatile ProxyHandlerFactory proxyHandlerFactory;

    private Long gracefulShutdownTimeoutMillis;
    private volatile ChannelPromise connectionReadyPromise;
    private volatile ChannelPromise reconnectionPromise;

    private final Map<T, Promise<ApnsPushNotificationResponse<T>>> responsePromises = new IdentityHashMap<>();

    private ArrayList<String> identities;

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
            protected void initChannel(SocketChannel channel) throws Exception {
                final ChannelPipeline pipeline = channel.pipeline();

                final ProxyHandlerFactory proxyHandlerFactory = ApnsHttp2Client.this.proxyHandlerFactory;
                if (proxyHandlerFactory != null) {
                    pipeline.addFirst(proxyHandlerFactory.createProxyHandler());
                }

                if (ApnsHttp2Properties.DEFAULT_WRITE_TIMEOUT_MILLIS > 0) {
                    pipeline.addLast(new WriteTimeoutHandler(ApnsHttp2Properties.DEFAULT_WRITE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS));
                }

                pipeline.addLast(sslContext.newHandler(channel.alloc()));
                pipeline.addLast(new ApplicationProtocolNegotiationHandler("") {
                    @Override
                    protected void configurePipeline(final ChannelHandlerContext context, final String protocol) throws Exception {
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
            logger.debug("Loaded socket channel class: {}", clazz);
            return clazz.asSubclass(Channel.class);
        } catch (final ClassNotFoundException e) {
            throw new IllegalArgumentException(e.getMessage(), e);
        }
    }

    // todo setProxyHandlerFactory

    // todo setConnectionTimerout

    // todo connect

    // todo connectSandBox

    // todo connectProduction

    // todo connect

    // todo isConnected

    // todo waitForInitialSettings

    // todo getReconnectionFuture

    // todo sendNotification

    // todo verifyTopic

    protected void handlePushNotificationResponse(final ApnsPushNotificationResponse<T> response) {
        logger.info("Received response from APNs gateway: {}", response);
        if (response.getApnsPushNotification() != null) {
            this.responsePromises.remove(response.getApnsPushNotification()).setSuccess(response);
        } else {
            this.responsePromises.clear();
        }
    }

    // todo setGracefulShutdownTimeout

    // todo disconnect
}
