/*******************************************************************************
 * Copyright (c) 2011, 2016 Eurotech and/or its affiliates
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     Eurotech
 *******************************************************************************/
package org.eclipse.kura.core.ssl;

import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.security.GeneralSecurityException;
import java.security.KeyManagementException;
import java.security.KeyStore;
import java.security.KeyStore.Entry;
import java.security.KeyStore.LoadStoreParameter;
import java.security.KeyStore.PasswordProtection;
import java.security.KeyStore.ProtectionParameter;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.UnrecoverableEntryException;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ConcurrentHashMap;

import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

import org.eclipse.kura.KuraException;
import org.eclipse.kura.configuration.ConfigurableComponent;
import org.eclipse.kura.configuration.ConfigurationService;
import org.eclipse.kura.configuration.Password;
import org.eclipse.kura.crypto.CryptoService;
import org.eclipse.kura.ssl.SslManagerService;
import org.eclipse.kura.ssl.SslManagerServiceOptions;
import org.eclipse.kura.ssl.SslServiceListener;
import org.eclipse.kura.system.SystemService;
import org.osgi.service.component.ComponentContext;
import org.osgi.util.tracker.ServiceTracker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SslManagerServiceImpl implements SslManagerService, ConfigurableComponent {
    private static final Logger s_logger = LoggerFactory.getLogger(SslManagerServiceImpl.class);

    private SslServiceListeners m_sslServiceListeners;

    private ComponentContext         m_ctx;
    private Map<String, Object>      m_properties;
    private SslManagerServiceOptions m_options;

    private CryptoService        m_cryptoService;
    private ConfigurationService m_configurationService;

    private Timer m_timer;

    private Map<ConnectionSslOptions, SSLSocketFactory> m_sslSocketFactories;

    private SystemService m_systemService;

    // ----------------------------------------------------------------
    //
    // Dependencies
    //
    // ----------------------------------------------------------------

    public void setCryptoService(CryptoService cryptoService) {
        this.m_cryptoService = cryptoService;
    }

    public void unsetCryptoService(CryptoService cryptoService) {
        this.m_cryptoService = null;
    }

    public void setConfigurationService(ConfigurationService configurationService) {
        this.m_configurationService = configurationService;
    }

    public void unsetConfigurationService(ConfigurationService configurationService) {
        this.m_configurationService = null;
    }

    public void setSystemService(SystemService systemService) {
        this.m_systemService = systemService;
    }

    public void unsetSystemService(SystemService systemService) {
        this.m_systemService = null;
    }

    // ----------------------------------------------------------------
    //
    // Activation APIs
    //
    // ----------------------------------------------------------------

    protected void activate(ComponentContext componentContext, Map<String, Object> properties) {
        s_logger.info("activate...");

        //
        // save the bundle context and the properties
        m_ctx = componentContext;
        m_properties = properties;
        m_options = new SslManagerServiceOptions(properties);
        m_sslSocketFactories = new ConcurrentHashMap<ConnectionSslOptions, SSLSocketFactory>();

        ServiceTracker<SslServiceListener, SslServiceListener> listenersTracker = new ServiceTracker<SslServiceListener, SslServiceListener>(
                componentContext.getBundleContext(),
                SslServiceListener.class, null);

        // Deferred open of tracker to prevent
        // java.lang.Exception: Recursive invocation of
        // ServiceFactory.getService
        // on ProSyst
        m_sslServiceListeners = new SslServiceListeners(listenersTracker);

        // 1. If the framework is running in secure mode automatically
        // change the default keystore password with a randomly generated one.
        // Then self-update our configuration to reflect the password change.
        if (!changeDefaultKeystorePassword()) {
            // 2. If the password saved in the snapshot and the password hold by
            // the CryptoService do not match change the keystore password
            // to the password in the snapshot.
            changeKeyStorePassword();
        }
    }

    public void updated(Map<String, Object> properties) {
        s_logger.info("updated...");

        m_properties = properties;
        m_options = new SslManagerServiceOptions(properties);

        changeKeyStorePassword();

        // Notify listeners that service has been updated
        m_sslServiceListeners.onConfigurationUpdated();
    }

    protected void deactivate(ComponentContext componentContext) {
        s_logger.info("deactivate...");
        m_timer.cancel();
        m_sslServiceListeners.close();
    }

    // ----------------------------------------------------------------
    //
    // Service APIs
    //
    // ----------------------------------------------------------------

    @Override
    public SSLSocketFactory getSSLSocketFactory()
            throws GeneralSecurityException, IOException {
        return getSSLSocketFactory("");
    }

    @Override
    public SSLSocketFactory getSSLSocketFactory(String keyAlias)
            throws GeneralSecurityException, IOException {
        String protocol = m_options.getSslProtocol();
        String ciphers = m_options.getSslCiphers();
        String trustStore = m_options.getSslKeyStore();
        char[] keyStorePassword = getKeyStorePassword();
        boolean hostnameVerifcation = m_options.isSslHostnameVerification();

        // Note that the SslManagerService configuration now uses a single
        // trust/keystore.
        // FIXME: we should be consistent and have a getSslKeyStore() instead of
        // getSslTrustStore().
        // Also the metatype property ssl.default.trustStore should be changed
        // accordingly.
        return getSSLSocketFactory(protocol, ciphers, trustStore, trustStore,
                keyStorePassword, keyAlias, hostnameVerifcation);
    }

    @Override
    public SSLSocketFactory getSSLSocketFactory(String protocol,
            String ciphers,
            String trustStore,
            String keyStore,
            char[] keyStorePassword,
            String keyAlias) throws GeneralSecurityException, IOException {
        return getSSLSocketFactory(protocol, ciphers, trustStore, keyStore, keyStorePassword, keyAlias, m_options.isSslHostnameVerification());
    }

    @Override
    public SSLSocketFactory getSSLSocketFactory(String protocol,
            String ciphers,
            String trustStore,
            String keyStore,
            char[] keyStorePassword,
            String keyAlias,
            boolean hostnameVerification)
                    throws GeneralSecurityException, IOException {
        ConnectionSslOptions connSslOpts = new ConnectionSslOptions(m_options);
        connSslOpts.setProtocol(protocol);
        connSslOpts.setCiphers(ciphers);
        connSslOpts.setTrustStore(trustStore);
        connSslOpts.setKeyStore(keyStore);
        if (keyStorePassword == null) {
            connSslOpts.setKeyStorePassword(getKeyStorePassword());
        } else {
            connSslOpts.setKeyStorePassword(keyStorePassword);
        }
        connSslOpts.setAlias(keyAlias);
        connSslOpts.setHostnameVerification(hostnameVerification);

        return getSSLSocketFactoryInternal(connSslOpts);
    }

    @Override
    public X509Certificate[] getTrustCertificates()
            throws GeneralSecurityException, IOException {
        // trust store
        X509Certificate[] cacerts = null;
        String trustStore = m_options.getSslKeyStore();
        TrustManager[] tms = getTrustManagers(trustStore);
        for (TrustManager tm : tms) {
            if (tm instanceof X509TrustManager) {
                X509TrustManager x509tm = (X509TrustManager) tm;
                cacerts = x509tm.getAcceptedIssuers();
                break;
                // for (X509Certificate x509cert : x509certs) {
                // System.err.println("TS DN: "+x509cert.getSubjectDN());
                // System.err.println("TS DN:
                // "+x509cert.getSubjectX500Principal().getName());
                // System.err.println("TS CANONICAL:
                // "+x509cert.getSubjectX500Principal().getName(X500Principal.CANONICAL));
                // System.err.println("TS RFC1779:
                // "+x509cert.getSubjectX500Principal().getName(X500Principal.RFC1779));
                // System.err.println("TS RFC2253:
                // "+x509cert.getSubjectX500Principal().getName(X500Principal.RFC2253));
                // System.err.println("TS alt:
                // "+x509cert.getSubjectAlternativeNames());
                // System.err.println("TS not before date:
                // "+x509cert.getNotBefore());
                // System.err.println("TS not after date:
                // "+x509cert.getNotAfter());
                // }
            }
        }
        return cacerts;
    }

    @Override
    public void installTrustCertificate(String alias, X509Certificate x509crt)
            throws GeneralSecurityException, IOException {
        InputStream tsReadStream = null;
        FileOutputStream tsOutStream = null;

        try {
            // load the trust store
            String trustStore = m_options.getSslKeyStore();
            KeyStore ts = KeyStore.getInstance(KeyStore.getDefaultType());
            File fTrustStore = new File(trustStore);
            char[] trustStorePassword = getKeyStorePassword();
            if (fTrustStore.exists()) {
                tsReadStream = new FileInputStream(trustStore);
                ts.load(tsReadStream, trustStorePassword);
            } else {
                ts.load(null, null);
            }

            // add the certificate
            ts.setCertificateEntry(alias, x509crt);

            // save it
            tsOutStream = new FileOutputStream(trustStore);
            ts.store(tsOutStream, trustStorePassword);
        } finally {
            close(tsReadStream);
            close(tsOutStream);
        }
    }

    @Override
    public void deleteTrustCertificate(String alias)
            throws GeneralSecurityException, IOException {
        InputStream tsReadStream = null;

        try {
            // load the trust store
            String trustStore = m_options.getSslKeyStore();
            KeyStore ts = KeyStore.getInstance(KeyStore.getDefaultType());
            tsReadStream = new FileInputStream(trustStore);
            char[] trustStorePassword = getKeyStorePassword();
            ts.load(tsReadStream, trustStorePassword);

            // delete the entry
            ts.deleteEntry(alias);

            // save it
            ts.store(new LoadStoreParameter() {
                @Override
                public ProtectionParameter getProtectionParameter() {
                    PasswordProtection passwordProtection = null;
                    char[] trustStorePassword = getKeyStorePassword();
                    if (trustStorePassword != null) {
                        passwordProtection = new PasswordProtection(trustStorePassword);
                    }
                    return passwordProtection;
                }
            });
        } finally {
            close(tsReadStream);
        }
    }

    @Override
    public void installPrivateKey(String alias, PrivateKey privateKey, char[] password,
            Certificate[] publicCerts)
                    throws GeneralSecurityException, IOException {
        // Note that password parameter is unused

        InputStream tsReadStream = null;
        FileOutputStream tsOutStream = null;

        try {
            // load the key store
            String keyStore = m_options.getSslKeyStore();
            KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
            File fKeyStore = new File(keyStore);
            char[] keyStorePassword = getKeyStorePassword();
            if (fKeyStore.exists()) {
                tsReadStream = new FileInputStream(keyStore);
                ks.load(tsReadStream, keyStorePassword);
            } else {
                ks.load(null, null);
            }

            char[] trustStorePwd = getKeyStorePassword();
            // add the certificate
            ks.setKeyEntry(alias, privateKey, trustStorePwd, publicCerts);

            // save it
            tsOutStream = new FileOutputStream(keyStore);
            ks.store(tsOutStream, keyStorePassword);
        } finally {
            close(tsReadStream);
            close(tsOutStream);
        }
    }

    @Override
    public SslManagerServiceOptions getConfigurationOptions() throws GeneralSecurityException, IOException {
        return m_options;
    }

    // ----------------------------------------------------------------
    //
    // Private methods
    //
    // ----------------------------------------------------------------

    private SSLSocketFactory getSSLSocketFactoryInternal(ConnectionSslOptions options)
            throws GeneralSecurityException, IOException {
        // Only create a new SSLSocketFactory instance if the configuration has
        // changed or
        // for a new alias.
        // This allows for SSL Context Resumption and abbreviated SSL handshake
        // in case of reconnects to the same host.
        SSLSocketFactory factory = m_sslSocketFactories.get(options);
        if (factory == null) {
            s_logger.info("Creating a new SSLSocketFactory instance");

            TrustManager[] tms = getTrustManagers(options.getTrustStore());

            if (tms == null) {
                throw new GeneralSecurityException("SSL keystore tampered!");
            }

            KeyManager[] kms = getKeyManagers(options.getKeyStore(), options.getKeyStorePassword(), options.getAlias());

            factory = createSSLSocketFactory(options.getProtocol(), options.getCiphers(), kms, tms, options.getHostnameVerification());
            m_sslSocketFactories.put(options, factory);
        }

        return factory;
    }

    private static SSLSocketFactory createSSLSocketFactory(String protocol,
            String ciphers,
            KeyManager[] kms,
            TrustManager[] tms,
            boolean hostnameVerification)
                    throws NoSuchAlgorithmException, KeyManagementException {
        // inits the SSL context
        SSLContext sslCtx;
        if (protocol == null) {
            sslCtx = SSLContext.getDefault();
        } else {
            sslCtx = SSLContext.getInstance(protocol);
            sslCtx.init(kms, tms, null);
        }

        // get the SSLSocketFactory
        SSLSocketFactory sslSocketFactory = sslCtx.getSocketFactory();

        // wrap it
        return new SSLSocketFactoryWrapper(sslSocketFactory, ciphers, hostnameVerification);
    }

    private static TrustManager[] getTrustManagers(String trustStore)
            throws KeyStoreException, NoSuchAlgorithmException, CertificateException, IOException {
        TrustManagerFactory tmf = null;
        if (trustStore != null) {

            // Load the configured the Trust Store
            File fTrustStore = new File(trustStore);
            if (fTrustStore.exists()) {

                KeyStore ts = KeyStore.getInstance(KeyStore.getDefaultType());
                InputStream tsReadStream = new FileInputStream(trustStore);
                ts.load(tsReadStream, null);
                tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
                tmf.init(ts);
                tsReadStream.close();
            } else {
                s_logger.info("Could not find trust store at {}. Using Java default.", trustStore);
            }
        }

        if (tmf == null) {
            // Load the default Java VM Trust Store
            tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmf.init((KeyStore) null);
        }
        return tmf.getTrustManagers();
    }

    private KeyManager[] getKeyManagers(String keyStore,
            char[] keyStorePassword,
            String keyAlias)
                    throws KeyStoreException, NoSuchAlgorithmException, CertificateException, IOException, UnrecoverableEntryException {
        KeyStore ks = getKeyStore(keyStore, keyStorePassword, keyAlias);
        KeyManager[] kms = null;
        if (ks != null) {
            KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            kmf.init(ks, keyStorePassword);
            kms = kmf.getKeyManagers();
        }
        return kms;
    }

    private KeyStore getKeyStore(String keyStore,
            char[] keyStorePassword,
            String keyAlias)
                    throws KeyStoreException, FileNotFoundException,
                    IOException, NoSuchAlgorithmException,
                    CertificateException, UnrecoverableEntryException {
        KeyStore ks = null;
        if (keyStore != null) {

            // Load the configured the Key Store
            File fKeyStore = new File(keyStore);
            if (fKeyStore.exists()) {

                ks = KeyStore.getInstance(KeyStore.getDefaultType());
                InputStream ksReadStream = new FileInputStream(keyStore);
                ks.load(ksReadStream, keyStorePassword);

                // if we have an alias, then build KeyStore with such key
                if (keyAlias != null) {
                    if (ks.containsAlias(keyAlias) && ks.isKeyEntry(keyAlias)) {
                        if (ks.size() > 1) {
                            PasswordProtection pp = new PasswordProtection(keyStorePassword);
                            Entry entry = ks.getEntry(keyAlias, pp);
                            ks = KeyStore.getInstance(KeyStore.getDefaultType());
                            ks.load(null, null);
                            ks.setEntry(keyAlias, entry, pp);
                        }
                    } else {
                        s_logger.info("Could not find alias {} in key store at {}. Using Java default.", keyAlias, keyStore);
                        ks = null;
                    }
                }

                ksReadStream.close();
            } else {
                s_logger.info("Could not find key store at {}. Using Java default.", keyStore);
            }
        }

        if (m_cryptoService.isFrameworkSecure()) {
            if (keyStore == null) {
                s_logger.warn("The environment is secured but the provided keystore is null");
                throw new KeyStoreException("The environment is secured but the provided keystore is null");
            } else if (!isKeyStoreAccessible(keyStore, keyStorePassword)) {
                s_logger.warn("The environment is secured but the provided keystore is not accessible");
                throw new KeyStoreException("The environment is secured but the provided keystore is not accessible");
            }
        }

        return ks;
    }

    private char[] getKeyStorePassword() {
        return m_cryptoService.getKeyStorePassword(m_options.getSslKeyStore());
    }

    private static boolean isKeyStoreAccessible(String location, char[] password) {
        try {
            loadKeyStore(location, password);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static KeyStore loadKeyStore(String location, char[] password)
            throws IOException, NoSuchAlgorithmException, CertificateException, KeyStoreException {
        FileInputStream is = null;
        try {
            is = new FileInputStream(location);
            KeyStore keystore = KeyStore.getInstance(KeyStore.getDefaultType());
            keystore.load(is, password);
            return keystore;
        } finally {
            close(is);
        }
    }

    private static void saveKeyStore(KeyStore keystore, String location, char[] newPassword)
            throws IOException, KeyStoreException, NoSuchAlgorithmException, CertificateException, UnrecoverableEntryException {
        FileOutputStream fos = null;
        try {
            fos = new FileOutputStream(location);
            keystore.store(fos, newPassword);
        } finally {
            close(fos);
        }
    }

    private static void close(Closeable c) {
        if (c != null) {
            try {
                c.close();
            } catch (IOException e) {
                s_logger.warn("Failed to close Closeable", e);
            }
        }
    }

    private boolean isDefaultPassword() {
        try {
            boolean isDefaultFromInstaller = isKeyStoreAccessible(m_options.getSslKeyStore(), SslManagerServiceOptions.PROP_DEFAULT_TRUST_PASSWORD.toCharArray());

            boolean isDefaultFromUser = false;
            char[] kuraPropertiesKeystorePassword = m_systemService.getJavaKeyStorePassword();
            if (kuraPropertiesKeystorePassword != null) {
                isDefaultFromUser = isKeyStoreAccessible(m_options.getSslKeyStore(), kuraPropertiesKeystorePassword);
            }

            boolean isDefaultFromCrypto = false;
            char[] cryptoPassword = m_cryptoService.getKeyStorePassword(m_options.getSslKeyStore());
            char[] snapshotPassword = m_cryptoService.decryptAes(m_options.getSslKeystorePassword().toCharArray());
            if (Arrays.equals(SslManagerServiceOptions.PROP_DEFAULT_TRUST_PASSWORD.toCharArray(), snapshotPassword)) {
                isDefaultFromCrypto = isKeyStoreAccessible(m_options.getSslKeyStore(), cryptoPassword);
            }

            return isDefaultFromInstaller || isDefaultFromUser || isDefaultFromCrypto;
        } catch (Exception e) {
            s_logger.error("Exception while evaluating isDefaultPassword!", e);
        }

        return false;
    }

    private boolean changeDefaultKeystorePassword() {
        boolean result = false;

        m_timer = new Timer(true);
        char[] snapshotPassword = null;
        boolean needsPasswordChange = true;
        try {
            snapshotPassword = m_cryptoService.decryptAes(m_options.getSslKeystorePassword().toCharArray());
            needsPasswordChange = isDefaultPassword();
        } catch (KuraException e) {}

        // The password in the snapshot is the default password (or cannot be
        // decrypted).
        // If the framework is running in secure mode we must change the
        // password.
        // The keystore must be accessible with the old/default password.
        char[] oldPassword = m_cryptoService.getKeyStorePassword(m_options.getSslKeyStore());
        if (needsPasswordChange && snapshotPassword != null && isKeyStoreAccessible(m_options.getSslKeyStore(), snapshotPassword)) {
            oldPassword = snapshotPassword;
        }
        if (m_cryptoService.isFrameworkSecure() &&
                needsPasswordChange &&
                oldPassword != null &&
                isKeyStoreAccessible(m_options.getSslKeyStore(), oldPassword)) {
            try {
                // generate a new random password
                char[] newPassword = new BigInteger(160, new SecureRandom()).toString(32).toCharArray();

                // change the password to the keystore
                changeKeyStorePassword(m_options.getSslKeyStore(), oldPassword, newPassword);

                // change the CryptoService SSL keystore password
                m_cryptoService.setKeyStorePassword(m_options.getSslKeyStore(), newPassword);

                // update our configuration with the newly generated password
                final String pid = (String) m_properties.get("service.pid");

                Map<String, Object> props = new HashMap<String, Object>(m_properties);
                props.put(SslManagerServiceOptions.PROP_TRUST_PASSWORD, new Password(newPassword));
                final Map<String, Object> theProperties = props;

                m_timer.scheduleAtFixedRate(new TimerTask() {
                    @Override
                    public void run() {
                        try {
                            if (m_ctx.getServiceReference() != null &&
                                    m_configurationService.getComponentConfiguration(pid) != null) {
                                m_configurationService.updateConfiguration(pid, theProperties);
                                m_timer.cancel();
                            } else {
                                s_logger.info("No service or configuration available yet. Sleeping...");
                            }
                        } catch (KuraException e) {
                            s_logger.warn("Cannot get/update configuration for pid: {}", pid, e);
                        }
                    }
                }, 1000, 1000);

                result = true;
            } catch (Exception e) {
                s_logger.warn("Keystore password change failed");
            }
        }

        return result;
    }

    private boolean changeKeyStorePassword() {
        String password = m_options.getSslKeystorePassword();
        char[] oldPassword = m_cryptoService.getKeyStorePassword(m_options.getSslKeyStore());
        char[] newPassword = oldPassword;
        if (password != null) {
            try {
                newPassword = m_cryptoService.decryptAes(password.toCharArray());
            } catch (KuraException e) {
                s_logger.warn("Failed to decrypt keystore password");
            }
        }

        if (oldPassword == null) {
            s_logger.warn("null old password");
            return false;
        }

        if (!Arrays.equals(oldPassword, newPassword)) {
            try {
                if (isKeyStoreAccessible(m_options.getSslKeyStore(), oldPassword)) {
                    changeKeyStorePassword(m_options.getSslKeyStore(), oldPassword, newPassword);
                } else if (isKeyStoreAccessible(m_options.getSslKeyStore(), newPassword)) {
                    changeKeyStorePassword(m_options.getSslKeyStore(), newPassword, newPassword);
                } else {
                    return false;
                }
                m_cryptoService.setKeyStorePassword(m_options.getSslKeyStore(), newPassword);
                return true;
            } catch (Exception e) {
                s_logger.warn("Failed to change keystore password");
            }
        }
        return false;
    }

    private void changeKeyStorePassword(String location, char[] oldPassword, char[] newPassword)
            throws IOException, NoSuchAlgorithmException, CertificateException, KeyStoreException, UnrecoverableEntryException {
        KeyStore keystore = loadKeyStore(location, oldPassword);

        updateKeyEntiesPasswords(keystore, oldPassword, newPassword);
        saveKeyStore(keystore, location, newPassword);
    }

    private static void updateKeyEntiesPasswords(KeyStore keystore, char[] oldPassword, char[] newPassword)
            throws KeyStoreException, NoSuchAlgorithmException, UnrecoverableEntryException {
        Enumeration<String> aliases = keystore.aliases();
        while (aliases.hasMoreElements()) {
            String alias = aliases.nextElement();
            if (keystore.isKeyEntry(alias)) {
                PasswordProtection oldPP = new PasswordProtection(oldPassword);
                Entry entry = keystore.getEntry(alias, oldPP);
                PasswordProtection newPP = new PasswordProtection(newPassword);
                keystore.setEntry(alias, entry, newPP);
            }
        }
    }
}
