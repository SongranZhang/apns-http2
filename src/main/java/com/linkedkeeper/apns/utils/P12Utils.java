package com.linkedkeeper.apns.utils;

import java.io.IOException;
import java.io.InputStream;
import java.security.*;
import java.security.KeyStore.PrivateKeyEntry;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.Objects;
import java.util.UUID;

/**
 * @author frank@linkedkeeper.com on 2016/12/27.
 */
public class P12Utils {

    public static KeyStore.PrivateKeyEntry getFirstPrivateKeyEntryFromP12InputStream(final KeyStore keyStore, final String password) throws KeyStoreException, IOException {
        Objects.requireNonNull(password, "Password may be blank, but must not be null.");

        final Enumeration<String> aliases = keyStore.aliases();
        final KeyStore.PasswordProtection passwordProtection = new KeyStore.PasswordProtection(password.toCharArray());

        while (aliases.hasMoreElements()) {
            final String alias = aliases.nextElement();

            KeyStore.Entry entry;
            try {
                try {
                    entry = keyStore.getEntry(alias, passwordProtection);
                } catch (final UnsupportedOperationException e) {
                    entry = keyStore.getEntry(alias, null);
                }
            } catch (final UnrecoverableEntryException | NoSuchAlgorithmException e) {
                throw new KeyStoreException(e);
            }

            if (entry instanceof KeyStore.PrivateKeyEntry) {
                return (PrivateKeyEntry) entry;
            }
        }

        throw new KeyStoreException("Key store did not contain any private key entries.");
    }

    public static KeyStore loadPCKS12KeyStore(final InputStream p12InputStream, final String password) throws KeyStoreException, IOException {
        final KeyStore keyStore = KeyStore.getInstance("PKCS12");

        try {
            keyStore.load(p12InputStream, password.toCharArray());
        } catch (NoSuchAlgorithmException | CertificateException e) {
            throw new KeyStoreException(e);
        }
        return keyStore;
    }

    private static final String UDID_KEY = "UID";

    public static ArrayList<String> getIdentitiesForP12File(final KeyStore keyStore) throws KeyStoreException, IOException {
        final Enumeration<String> aliases = keyStore.aliases();
        ArrayList<String> identifiers = new ArrayList<>();
        while (aliases.hasMoreElements()) {
            String alias = aliases.nextElement();
            X509Certificate c = (X509Certificate) keyStore.getCertificate(alias);
            Principal subject = c.getSubjectDN();
            String subjectArray[] = subject.toString().split(",");
            for (String s : subjectArray) {
                String[] str = s.trim().split("=");
                String key = str[0];
                String value = str[1];
                if (UDID_KEY.equals(key)) {
                    identifiers.add(value);
                }
            }
        }
        return identifiers;
    }
}
