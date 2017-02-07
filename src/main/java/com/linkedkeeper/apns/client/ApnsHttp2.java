package com.linkedkeeper.apns.client;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.util.concurrent.ExecutionException;

import javax.net.ssl.SSLException;

import com.linkedkeeper.apns.data.ApnsHttp2PushNotification;
import com.linkedkeeper.apns.data.ApnsPushNotificationResponse;
import com.linkedkeeper.apns.exceptions.CertificateNotValidException;
import com.linkedkeeper.apns.exceptions.ClientNotConnectedException;
import com.linkedkeeper.apns.utils.P12Utils;
import io.netty.util.concurrent.Future;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linkedkeeper.apns.data.ApnsPushNotification;

/**
 * @author frank@linkedkeeper.com on 2016/12/27.
 */
public class ApnsHttp2 {

    private static final Logger logger = LoggerFactory.getLogger(ApnsHttp2.class);

    private ApnsHttp2Client<ApnsPushNotification> apnsHttp2Client;
    private boolean sandboxEnvironment;

    public ApnsHttp2(final File certificateFile, final String password) throws SSLException {
        try {
            this.apnsHttp2Client = new ApnsHttp2Client<>(certificateFile, password);
        } catch (IOException | KeyStoreException e) {
            throw new SSLException(e);
        }
        this.sandboxEnvironment = false;
    }

    public ApnsHttp2(final InputStream p12InputStream, final String password) throws SSLException {
        try {
            KeyStore keyStore = P12Utils.loadPCKS12KeyStore(p12InputStream, password);
            this.apnsHttp2Client = new ApnsHttp2Client<>(keyStore, password);
        } catch (SSLException e) {
            throw e;
        } catch (IOException | KeyStoreException e) {
            e.printStackTrace();
        }
        this.sandboxEnvironment = false;
    }

    public ApnsPushNotificationResponse<ApnsPushNotification> pushMessageSync(final String payload, final String token) throws ExecutionException, CertificateNotValidException {
        try {
            if (!this.apnsHttp2Client.isConnected()) {
                logger.error("APNs http2 client connect is lost, stablish connection ...");
                stablishConnection();
            }
            final ApnsPushNotification apnsPushNotification = new ApnsHttp2PushNotification(token, null, payload);
            final Future<ApnsPushNotificationResponse<ApnsPushNotification>> sendNotificationFuture = this.apnsHttp2Client.sendNotification(apnsPushNotification);
            final ApnsPushNotificationResponse<ApnsPushNotification> apnsPushNotificationResponse = sendNotificationFuture.get();

            return apnsPushNotificationResponse;
        } catch (final ExecutionException e) {
            logger.error("Failed to send push notification.", e);
            if (e.getCause() instanceof CertificateNotValidException) {
                throw e;
            }
            if (e.getCause() instanceof ClientNotConnectedException) {
                throw new CertificateNotValidException(e.getMessage());
            }
            throw e;
        } catch (InterruptedException e) {
            throw new ExecutionException(e);
        }
    }

    /**
     * Partially async, as it still need connection wait if doestn have connected before
     *
     * @param payload
     * @param token
     * @return
     * @throws ExecutionException
     */
    public Future<ApnsPushNotificationResponse<ApnsPushNotification>> pushMessageAsync(final String payload, final String token) throws ExecutionException {
        try {
            if (!this.apnsHttp2Client.isConnected()) {
                logger.error("APNs http2 client connect is lost, stablish connection ...");
                stablishConnection();
            }
            final ApnsPushNotification apnsPushNotification = new ApnsHttp2PushNotification(token, null, payload);
            final Future<ApnsPushNotificationResponse<ApnsPushNotification>> sendNotificationFuture = this.apnsHttp2Client.sendNotification(apnsPushNotification);

            return sendNotificationFuture;
        } catch (InterruptedException e) {
            logger.error("Failed to send push notification.", e);
            throw new ExecutionException(e);
        }
    }

    public ApnsHttp2 productMode() {
        this.sandboxEnvironment = false;
        return this;
    }

    public ApnsHttp2 sandboxMode() {
        this.sandboxEnvironment = true;
        return this;
    }

    private void stablishConnection() throws InterruptedException {
        final Future<Void> connectFuture = sandboxEnvironment ? this.apnsHttp2Client.connectSandBox() : this.apnsHttp2Client.connectProduction();
        connectFuture.await();
    }

    public void disconnect() {
        if (apnsHttp2Client.isConnected()) {
            apnsHttp2Client.disconnect();
        }
    }
}