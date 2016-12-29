package com.linkedkeeper.apns.client;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.security.KeyStore;
import java.security.KeyStoreException;

import javax.net.ssl.SSLException;

import com.linkedkeeper.apns.utils.P12Utils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linkedkeeper.apns.data.PushNotification;

/**
 * @author frank@linkedkeerp.com on 2016/12/27.
 */
public class ApnsHttp2 {

    private static final Logger logger = LoggerFactory.getLogger(ApnsHttp2.class);

    private ApnsHttp2Client<PushNotification> apnsHttp2Client;
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
    }
}
