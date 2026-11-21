/* MemorizingTrustManager - a TrustManager which asks the user about invalid
 *  certificates and memorizes their decision.
 *
 * Copyright (c) 2010 Georg Lukas <georg@op-co.de>
 *
 * MemorizingTrustManager.java contains the actual trust manager and interface
 * code to create a MemorizingActivity and obtain the results.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package eu.siacs.conversations.services;

import android.app.Application;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.util.Base64;
import android.util.Log;
import android.util.SparseArray;
import androidx.appcompat.app.AppCompatActivity;
import com.google.common.base.Joiner;
import eu.siacs.conversations.Config;
import eu.siacs.conversations.R;
import eu.siacs.conversations.crypto.TrustManagers;
import eu.siacs.conversations.crypto.XmppDomainVerifier;
import eu.siacs.conversations.entities.MTMDecision;
import eu.siacs.conversations.persistance.FileBackend;
import eu.siacs.conversations.ui.MemorizingActivity;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertPathValidatorException;
import java.security.cert.Certificate;
import java.security.cert.CertificateEncodingException;
import java.security.cert.CertificateException;
import java.security.cert.CertificateExpiredException;
import java.security.cert.CertificateNotYetValidException;
import java.security.cert.CertificateParsingException;
import java.security.cert.X509Certificate;
import java.text.SimpleDateFormat;
import java.util.Enumeration;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import javax.net.ssl.X509TrustManager;
import org.minidns.dane.DaneCertificateException;
import org.minidns.dane.DaneVerificationResult;
import org.minidns.dane.DaneVerifier;
import org.minidns.dnssec.DnssecClient;
import org.minidns.hla.DnssecResolverApi;

/**
 * A X509 trust manager implementation which asks the user about invalid certificates and memorizes
 * their decision.
 *
 * <p>The certificate validity is checked using the system default X509 TrustManager, creating a
 * query Dialog if the check fails.
 *
 * <p><b>WARNING:</b> This only works if a dedicated thread is used for opening sockets!
 */
public class MemorizingTrustManager {

    private static final SimpleDateFormat DATE_FORMAT =
            new SimpleDateFormat("yyyy-MM-dd", Locale.US);

    static final String DECISION_INTENT = "de.duenndns.ssl.DECISION";
    public static final String DECISION_INTENT_ID = DECISION_INTENT + ".decisionId";
    public static final String DECISION_INTENT_CERT = DECISION_INTENT + ".cert";
    public static final String DECISION_TITLE_ID = DECISION_INTENT + ".titleId";
    static final String NO_TRUST_ANCHOR = "Trust anchor for certification path not found.";
    private static final Pattern PATTERN_IPV4 =
            Pattern.compile(
                    "\\A(25[0-5]|2[0-4]\\d|[0-1]?\\d?\\d)(\\.(25[0-5]|2[0-4]\\d|[0-1]?\\d?\\d)){3}\\z");
    private static final Pattern PATTERN_IPV6_HEX4DECCOMPRESSED =
            Pattern.compile(
                    "\\A((?:[0-9A-Fa-f]{1,4}(?::[0-9A-Fa-f]{1,4})*)?)"
                        + " ::((?:[0-9A-Fa-f]{1,4}:)*)(25[0-5]|2[0-4]\\d|[0-1]?\\d?\\d)(\\.(25[0-5]|2[0-4]\\d|[0-1]?\\d?\\d)){3}\\z");
    private static final Pattern PATTERN_IPV6_6HEX4DEC =
            Pattern.compile(
                    "\\A((?:[0-9A-Fa-f]{1,4}:){6,6})(25[0-5]|2[0-4]\\d|[0-1]?\\d?\\d)(\\.(25[0-5]|2[0-4]\\d|[0-1]?\\d?\\d)){3}\\z");
    private static final Pattern PATTERN_IPV6_HEXCOMPRESSED =
            Pattern.compile(
                    "\\A((?:[0-9A-Fa-f]{1,4}(?::[0-9A-Fa-f]{1,4})*)?)::((?:[0-9A-Fa-f]{1,4}(?::[0-9A-Fa-f]{1,4})*)?)\\z");
    private static final Pattern PATTERN_IPV6 =
            Pattern.compile("\\A(?:[0-9a-fA-F]{1,4}:){7}[0-9a-fA-F]{1,4}\\z");
    private static final Logger LOGGER = Logger.getLogger(MemorizingTrustManager.class.getName());
    static String KEYSTORE_DIR = "KeyStore";
    static String KEYSTORE_FILE = "KeyStore.bks";
    private static int decisionId = 0;
    private static final SparseArray<MTMDecision> openDecisions = new SparseArray<MTMDecision>();
    Context master;
    AppCompatActivity foregroundAct;
    NotificationManager notificationManager;
    Handler masterHandler;
    private File keyStoreFile;
    private KeyStore appKeyStore;
    private final X509TrustManager defaultTrustManager;
    private final DaneVerifier daneVerifier;

    // TLSA lookups must use the DNSSEC client that Resolver.init() configures (user DNS server,
    // recursive only), not a fresh DnssecClient with MiniDNS defaults.
    private static DaneVerifier newDaneVerifier() {
        return new DaneVerifier((DnssecClient) DnssecResolverApi.INSTANCE.getClient());
    }

    private final java.util.EnumMap<
                    eu.siacs.conversations.http.HttpConnectionManager.Route, DaneVerifier>
            proxiedDaneVerifiers =
                    new java.util.EnumMap<>(
                            eu.siacs.conversations.http.HttpConnectionManager.Route.class);

    /**
     * The DANE verifier for a connection's route: TLSA lookups of a connection made through Tor/I2P
     * go through Tor/I2P as well (Resolver.proxiedDnssecClient), never to the local resolver.
     */
    // Overall time a DANE check of a Tor/I2P connection may take (TLSA + DNSSEC chain, each query
    // a separate stream through the proxy). It runs inside the server's TLS handshake.
    private static final long PROXIED_DANE_BUDGET_SECONDS = 20;

    // At most this many proxied DANE checks run at once (one per connecting account at most is
    // normal). A check that hangs is stopped when its budget runs out (socket closed, see
    // ProxiedDnsDataSource.Cancellation), and this bound keeps stuck ones from piling up meanwhile.
    private static final int PROXIED_DANE_MAX_THREADS = 4;

    private static final java.util.concurrent.ExecutorService DANE_EXECUTOR =
            new java.util.concurrent.ThreadPoolExecutor(
                    0,
                    PROXIED_DANE_MAX_THREADS,
                    60,
                    java.util.concurrent.TimeUnit.SECONDS,
                    new java.util.concurrent.SynchronousQueue<>(),
                    r -> {
                        final Thread thread = new Thread(r, "dane-proxied");
                        thread.setDaemon(true);
                        return thread;
                    },
                    new java.util.concurrent.ThreadPoolExecutor.AbortPolicy());

    /**
     * {@link DaneVerifier#daneVerify} for the connection's route. Through Tor/I2P the whole check
     * is bounded by {@link #PROXIED_DANE_BUDGET_SECONDS}; running out counts as a lookup failure
     * (not enforced: the normal certificate check continues; enforced: fatal). A mismatching or
     * bogus TLSA answer stays a {@link DaneCertificateException}.
     */
    private DaneVerificationResult daneVerify(
            final eu.siacs.conversations.http.HttpConnectionManager.Route route,
            final X509Certificate[] chain,
            final String hostname,
            final int port)
            throws CertificateException {
        final DaneVerifier verifier = daneVerifierFor(route);
        if (route == null
                || route == eu.siacs.conversations.http.HttpConnectionManager.Route.DIRECT) {
            return verifier.daneVerify(chain, hostname, port);
        }
        final eu.siacs.conversations.utils.ProxiedDnsDataSource.Cancellation cancellation =
                new eu.siacs.conversations.utils.ProxiedDnsDataSource.Cancellation();
        final java.util.concurrent.Future<DaneVerificationResult> future;
        try {
            future =
                    DANE_EXECUTOR.submit(() ->
                            eu.siacs.conversations.utils.ProxiedDnsDataSource.runCancellable(
                                    cancellation,
                                    () -> verifier.daneVerify(chain, hostname, port)));
        } catch (final java.util.concurrent.RejectedExecutionException e) {
            // As a timeout: a lookup failure, fatal only when DANE is enforced or pinned.
            throw new IllegalStateException(
                    "too many DANE lookups via " + route + " in progress for " + hostname);
        }
        try {
            return future.get(PROXIED_DANE_BUDGET_SECONDS, java.util.concurrent.TimeUnit.SECONDS);
        } catch (final java.util.concurrent.TimeoutException e) {
            cancelProxiedDane(future, cancellation);
            throw new IllegalStateException(
                    "DANE lookup via " + route + " timed out for " + hostname);
        } catch (final InterruptedException e) {
            cancelProxiedDane(future, cancellation);
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted during DANE lookup for " + hostname);
        } catch (final java.util.concurrent.ExecutionException e) {
            final Throwable cause = e.getCause();
            if (cause instanceof CertificateException certificateException)
                throw certificateException;
            if (cause instanceof RuntimeException runtimeException) throw runtimeException;
            if (cause instanceof Error error) throw error;
            throw new IllegalStateException(cause);
        }
    }

    /**
     * Interrupting the worker does not unblock a socket read, so the lookup's in-flight DNS query
     * is aborted by closing its socket, and it starts no further one.
     */
    private static void cancelProxiedDane(
            final java.util.concurrent.Future<?> future,
            final eu.siacs.conversations.utils.ProxiedDnsDataSource.Cancellation cancellation) {
        cancellation.cancel();
        future.cancel(true);
    }

    private DaneVerifier daneVerifierFor(
            final eu.siacs.conversations.http.HttpConnectionManager.Route route) {
        if (route == null
                || route == eu.siacs.conversations.http.HttpConnectionManager.Route.DIRECT) {
            return daneVerifier;
        }
        synchronized (proxiedDaneVerifiers) {
            DaneVerifier verifier = proxiedDaneVerifiers.get(route);
            if (verifier == null) {
                verifier =
                        new DaneVerifier(
                                eu.siacs.conversations.utils.Resolver.proxiedDnssecClient(route));
                proxiedDaneVerifiers.put(route, verifier);
            }
            return verifier;
        }
    }

    /**
     * Creates an instance of the MemorizingTrustManager class that falls back to a custom
     * TrustManager.
     *
     * <p>You need to supply the application context. This has to be one of: - Application -
     * Activity - Service
     *
     * <p>The context is used for file management, to display the dialog / notification and for
     * obtaining translated strings.
     *
     * @param context Context for the application.
     * @param defaultTrustManager Delegate trust management to this TM. If null, the user must
     *     accept every certificate.
     */
    public MemorizingTrustManager(
            final Context context, final X509TrustManager defaultTrustManager) {
        init(context);
        this.defaultTrustManager = defaultTrustManager;
        this.daneVerifier = newDaneVerifier();
    }

    /**
     * Creates an instance of the MemorizingTrustManager class using the system X509TrustManager.
     *
     * <p>You need to supply the application context. This has to be one of: - Application -
     * Activity - Service
     *
     * <p>The context is used for file management, to display the dialog / notification and for
     * obtaining translated strings.
     *
     * @param context Context for the application.
     */
    public MemorizingTrustManager(final Context context) {
        init(context);
        this.daneVerifier = newDaneVerifier();
        try {
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.N) {
                this.defaultTrustManager = TrustManagers.defaultWithBundledLetsEncrypt(context);
            } else {
                this.defaultTrustManager = TrustManagers.createDefaultTrustManager();
            }
        } catch (final NoSuchAlgorithmException
                | KeyStoreException
                | CertificateException
                | IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static boolean isIp(final String server) {
        return server != null
                && (PATTERN_IPV4.matcher(server).matches()
                        || PATTERN_IPV6.matcher(server).matches()
                        || PATTERN_IPV6_6HEX4DEC.matcher(server).matches()
                        || PATTERN_IPV6_HEX4DECCOMPRESSED.matcher(server).matches()
                        || PATTERN_IPV6_HEXCOMPRESSED.matcher(server).matches());
    }

    private static String getBase64Hash(X509Certificate certificate, String digest)
            throws CertificateEncodingException {
        MessageDigest md;
        try {
            md = MessageDigest.getInstance(digest);
        } catch (NoSuchAlgorithmException e) {
            return null;
        }
        md.update(certificate.getEncoded());
        return Base64.encodeToString(md.digest(), Base64.NO_WRAP);
    }

    private static String hexString(byte[] data) {
        StringBuffer si = new StringBuffer();
        for (int i = 0; i < data.length; i++) {
            si.append(String.format("%02x", data[i]));
            if (i < data.length - 1) si.append(":");
        }
        return si.toString();
    }

    private static String certHash(final X509Certificate cert, String digest) {
        try {
            MessageDigest md = MessageDigest.getInstance(digest);
            md.update(cert.getEncoded());
            return hexString(md.digest());
        } catch (CertificateEncodingException | NoSuchAlgorithmException e) {
            return e.getMessage();
        }
    }

    public static void interactResult(int decisionId, int choice) {
        MTMDecision d;
        synchronized (openDecisions) {
            d = openDecisions.get(decisionId);
            openDecisions.remove(decisionId);
        }
        if (d == null) {
            LOGGER.log(Level.SEVERE, "interactResult: aborting due to stale decision reference!");
            return;
        }
        synchronized (d) {
            d.state = choice;
            d.notify();
        }
    }

    void init(final Context context) {
        master = context;
        masterHandler = new Handler(context.getMainLooper());
        notificationManager =
                (NotificationManager) master.getSystemService(Context.NOTIFICATION_SERVICE);

        Application app;
        if (context instanceof Application) {
            app = (Application) context;
        } else if (context instanceof Service) {
            app = ((Service) context).getApplication();
        } else if (context instanceof AppCompatActivity) {
            app = ((AppCompatActivity) context).getApplication();
        } else
            throw new ClassCastException(
                    "MemorizingTrustManager context must be either Activity or Service!");

        File dir = app.getDir(KEYSTORE_DIR, Context.MODE_PRIVATE);
        keyStoreFile = new File(dir + File.separator + KEYSTORE_FILE);

        appKeyStore = loadAppKeyStore();
    }

    /**
     * Get a list of all certificate aliases stored in MTM.
     *
     * @return an {@link Enumeration} of all certificates
     */
    public Enumeration<String> getCertificates() {
        try {
            return appKeyStore.aliases();
        } catch (KeyStoreException e) {
            // this should never happen, however...
            throw new RuntimeException(e);
        }
    }

    /**
     * Removes the given certificate from MTMs key store.
     *
     * <p><b>WARNING</b>: this does not immediately invalidate the certificate. It is well possible
     * that (a) data is transmitted over still existing connections or (b) new connections are
     * created using TLS renegotiation, without a new cert check.
     *
     * @param alias the certificate's alias as returned by {@link #getCertificates()}.
     * @throws KeyStoreException if the certificate could not be deleted.
     */
    public synchronized void deleteCertificate(String alias) throws KeyStoreException {
        appKeyStore.deleteEntry(alias);
        keyStoreUpdated();
    }

    KeyStore loadAppKeyStore() {
        KeyStore ks;
        try {
            ks = KeyStore.getInstance(KeyStore.getDefaultType());
        } catch (KeyStoreException e) {
            LOGGER.log(Level.SEVERE, "getAppKeyStore()", e);
            return null;
        }
        FileInputStream fileInputStream = null;
        try {
            ks.load(null, null);
            fileInputStream = new FileInputStream(keyStoreFile);
            ks.load(fileInputStream, "MTM".toCharArray());
        } catch (java.io.FileNotFoundException e) {
            LOGGER.log(Level.INFO, "getAppKeyStore(" + keyStoreFile + ") - file does not exist");
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "getAppKeyStore(" + keyStoreFile + ")", e);
        } finally {
            FileBackend.close(fileInputStream);
        }
        return ks;
    }

    synchronized void storeCert(String alias, Certificate cert) {
        try {
            appKeyStore.setCertificateEntry(alias, cert);
        } catch (KeyStoreException e) {
            LOGGER.log(Level.SEVERE, "storeCert(" + cert + ")", e);
            return;
        }
        keyStoreUpdated();
    }

    void storeCert(X509Certificate cert) {
        storeCert(cert.getSubjectDN().toString(), cert);
    }

    // synchronized: storeCert (decision thread) and deleteCertificate (UI) share the .tmp path
    synchronized void keyStoreUpdated() {
        // Write to a temporary file and rename it over the real one: writing KeyStore.bks in
        // place would leave a truncated (unloadable) file if the app is killed mid-write, and
        // every memorized "always" decision would be gone.
        final File tmp = new File(keyStoreFile.getPath() + ".tmp");
        boolean written = false;
        try (final java.io.FileOutputStream fos = new java.io.FileOutputStream(tmp)) {
            appKeyStore.store(fos, "MTM".toCharArray());
            fos.flush();
            fos.getFD().sync();
            written = true;
        } catch (final Exception e) {
            LOGGER.log(Level.SEVERE, "storeCert(" + keyStoreFile + ")", e);
        }
        if (written && !tmp.renameTo(keyStoreFile)) {
            LOGGER.log(Level.SEVERE, "storeCert(" + keyStoreFile + "): could not replace file");
            written = false;
        }
        if (!written && tmp.exists() && !tmp.delete()) {
            LOGGER.log(Level.WARNING, "storeCert(" + tmp + "): could not delete");
        }
    }

    // If this exact certificate is stored in the app key store and is currently within its
    // validity period, it is considered "known". Memorized certificates are only ever compared
    // against the server (leaf) certificate; they are never trust anchors, so a memorized
    // certificate cannot vouch for another certificate it signed.
    private boolean isCertKnown(final X509Certificate cert) {
        if (appKeyStore == null || cert == null) {
            return false;
        }
        final String alias;
        try {
            alias = appKeyStore.getCertificateAlias(cert);
        } catch (final KeyStoreException e) {
            return false;
        }
        if (alias == null) {
            return false;
        }
        try {
            cert.checkValidity();
            return true;
        } catch (final CertificateExpiredException | CertificateNotYetValidException e) {
            LOGGER.log(
                    Level.INFO,
                    "isCertKnown: stored certificate '" + alias + "' is not currently valid",
                    e);
            return false;
        }
    }

    /** Thrown to leave the DANE stage and continue with the memorized / CA / prompt stages. */
    private static CertificateException notMemorized() {
        // keep the 'trust anchor not found' cause so the dialog shows mtm_trust_anchor
        return new CertificateException(new CertPathValidatorException(NO_TRUST_ANCHOR));
    }

    private void checkCertTrusted(
            X509Certificate[] chain,
            String authType,
            String domain,
            boolean isServer,
            boolean interactive,
            String verifiedHostname,
            int port,
            Consumer<Boolean> daneCb,
            boolean enforceDane,
            eu.siacs.conversations.http.HttpConnectionManager.Route dnsRoute)
            throws CertificateException {
        LOGGER.log(
                Level.FINE, "checkCertTrusted(" + chain + ", " + authType + ", " + isServer + ")");
        if (chain == null || chain.length == 0) {
            throw new IllegalArgumentException("null or zero-length certificate chain");
        }
        try {
            if (isServer) {
                // No DANE for hidden services: there is no DNS to ask (a TLSA lookup would only
                // leak the name), and the .onion/.i2p address authenticates the server itself.
                // Over I2P the TLSA lookup is only tried when DANE is enforced: I2P rarely has an
                // outproxy for DNS, so otherwise it would just delay every handshake.
                if (verifiedHostname != null
                        && !eu.siacs.conversations.utils.IP.matches(verifiedHostname)
                        && !eu.siacs.conversations.http.HttpConnectionManager.isOnionHost(
                                verifiedHostname)
                        && !eu.siacs.conversations.http.HttpConnectionManager.isI2PHost(
                                verifiedHostname)
                        && !(dnsRoute == eu.siacs.conversations.http.HttpConnectionManager.Route.I2P
                                && !enforceDane)) {
                    try {
                        final DaneVerificationResult dane =
                                daneVerify(dnsRoute, chain, verifiedHostname, port);
                        if (dane.isFullyVerified()) {
                            // DANE-EE (usage 3) matched: trusted on its own.
                            if (daneCb != null) daneCb.accept(true);
                            return;
                        } else if (dane == DaneVerificationResult.matchedPkixRequired) {
                            // PKIX-EE (usage 1) matched: the certificate must ALSO pass the CA
                            // check (RFC 6698 §2.1.1), and that check is part of DANE here, so a
                            // failure is fatal - no memorized-certificate or prompt fallback.
                            // Memorized certificates are not consulted here; the CA store is
                            // defaultTrustManager.
                            try {
                                if (defaultTrustManager == null) {
                                    throw new CertificateException("no CA trust manager");
                                }
                                defaultTrustManager.checkServerTrusted(chain, authType);
                            } catch (final CertificateException e) {
                                throw new DaneEnforcementException(
                                        "DANE PKIX-EE matched but the CA check failed for "
                                                + verifiedHostname
                                                + ": "
                                                + e.getMessage());
                            }
                            if (daneCb != null) daneCb.accept(true);
                            return;
                        } else if (dane != DaneVerificationResult.notAuthenticated
                                && daneCb != null) {
                            // Validated, but no usable TLSA records.
                            daneCb.accept(false);
                        }
                        if (enforceDane) {
                            throw new DaneEnforcementException(
                                    "DANE is enforced but verification did not match for "
                                            + verifiedHostname);
                        }
                    } catch (final DaneEnforcementException e) {
                        throw e;
                    } catch (final DaneCertificateException e) {
                        // DNSSEC-validated TLSA records that the certificate does not match, or a
                        // TLSA answer that is cryptographically bogus (invalid signature,
                        // contradicting denial proof): both mean the connection must be aborted
                        // (RFC 6698 §2.1, §4.1), whether or not DANE is enforced. Without this, the
                        // generic path below would still accept a memorized certificate, any
                        // CA-valid one, or a user-confirmed one. Bogus means: an invalid signature,
                        // an invalid or contradicting NSEC/NSEC3 proof, a partially signed RRset, a
                        // signature by a key the zone does not have. An answer that can merely not
                        // be verified is not thrown: no signatures (unsigned zone, or stripped by a
                        // forwarding/VPN resolver), a negative answer without SOA (e.g. unsigned
                        // *.duckdns.org via AdGuard/Cloudflare/MikroTik), or no answer at all
                        // (SERVFAIL). It comes back notAuthenticated, which is fatal above only
                        // when DANE is enforced or pinned for the account.
                        Log.d(
                                Config.LOGTAG,
                                "checkCertTrusted DANE rejected the certificate: " + e);
                        throw new DaneEnforcementException("DANE failure: " + e.getMessage());
                    } catch (final CertificateException e) {
                        Log.d(Config.LOGTAG, "checkCertTrusted DANE failure: " + e);
                        if (enforceDane) {
                            throw new DaneEnforcementException("DANE failure: " + e.getMessage());
                        }
                        throw e;
                    } catch (final Throwable e) {
                        Log.d(Config.LOGTAG, "checkCertTrusted DANE related failure: " + e);
                        if (enforceDane) {
                            throw new DaneEnforcementException(
                                    "DANE related failure: " + e.getMessage());
                        }
                    }
                } else if (enforceDane) {
                    throw new DaneEnforcementException(
                            "DANE is enforced but verified hostname is missing or IP for "
                                    + domain);
                }
            }
            // DANE did not settle it. There is deliberately no trust manager built from the
            // memorized certificates: that would make each of them a trust anchor.
            throw notMemorized();
        } catch (final CertificateException ae) {
            if (ae instanceof DaneEnforcementException) {
                throw ae;
            }
            if (isCertKnown(chain[0])) {
                LOGGER.log(
                        Level.INFO, "checkCertTrusted: accepting cert already stored in keystore");
                return;
            }
            try {
                if (defaultTrustManager == null) throw ae;
                LOGGER.log(Level.FINE, "checkCertTrusted: trying defaultTrustManager");
                if (isServer) defaultTrustManager.checkServerTrusted(chain, authType);
                else defaultTrustManager.checkClientTrusted(chain, authType);
            } catch (final CertificateException e) {
                if (interactive) {
                    interactCert(chain, authType, e);
                } else {
                    throw e;
                }
            }
        }
    }

    private X509Certificate[] getAcceptedIssuers() {
        return defaultTrustManager == null
                ? new X509Certificate[0]
                : defaultTrustManager.getAcceptedIssuers();
    }

    private int createDecisionId(MTMDecision d) {
        int myId;
        synchronized (openDecisions) {
            myId = decisionId;
            openDecisions.put(myId, d);
            decisionId += 1;
        }
        return myId;
    }

    private void certDetails(
            final StringBuffer si, final X509Certificate c, final boolean showValidFor) {

        si.append("\n");
        if (showValidFor) {
            try {
                si.append("Valid for: ");
                si.append(Joiner.on(", ").join(XmppDomainVerifier.parseValidDomains(c).all()));
            } catch (final CertificateParsingException e) {
                si.append("Unable to parse Certificate");
            }
            si.append("\n");
        } else {
            si.append(c.getSubjectDN());
        }
        si.append("\n");
        si.append(DATE_FORMAT.format(c.getNotBefore()));
        si.append(" - ");
        si.append(DATE_FORMAT.format(c.getNotAfter()));
        si.append("\nSHA-256: ");
        si.append(certHash(c, "SHA-256"));
        si.append("\nSHA-1: ");
        si.append(certHash(c, "SHA-1"));
        si.append("\nSigned by: ");
        si.append(c.getIssuerDN().toString());
        si.append("\n");
    }

    private String certChainMessage(final X509Certificate[] chain, CertificateException cause) {
        Throwable e = cause;
        LOGGER.log(Level.FINE, "certChainMessage for " + e);
        final StringBuffer si = new StringBuffer();
        if (e.getCause() != null) {
            e = e.getCause();
            // HACK: there is no sane way to check if the error is a "trust anchor
            // not found", so we use string comparison.
            if (NO_TRUST_ANCHOR.equals(e.getMessage())) {
                si.append(master.getString(R.string.mtm_trust_anchor));
            } else si.append(e.getLocalizedMessage());
            si.append("\n");
        }
        si.append("\n");
        si.append(master.getString(R.string.mtm_connect_anyway));
        si.append("\n\n");
        si.append(master.getString(R.string.mtm_cert_details));
        si.append('\n');
        for (int i = 0; i < chain.length; ++i) {
            certDetails(si, chain[i], i == 0);
        }
        return si.toString();
    }

    /**
     * Returns the top-most entry of the activity stack.
     *
     * @return the Context of the currently bound UI or the master context if none is bound
     */
    Context getUI() {
        return (foregroundAct != null) ? foregroundAct : master;
    }

    int interact(final String message, final int titleId) {
        /* prepare the MTMDecision blocker object */
        MTMDecision choice = new MTMDecision();
        final int myId = createDecisionId(choice);

        masterHandler.post(
                new Runnable() {
                    public void run() {
                        Intent ni = new Intent(master, MemorizingActivity.class);
                        ni.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        ni.setData(Uri.parse(MemorizingTrustManager.class.getName() + "/" + myId));
                        ni.putExtra(DECISION_INTENT_ID, myId);
                        ni.putExtra(DECISION_INTENT_CERT, message);
                        ni.putExtra(DECISION_TITLE_ID, titleId);

                        // we try to directly start the activity and fall back to
                        // making a notification
                        try {
                            getUI().startActivity(ni);
                        } catch (Exception e) {
                            LOGGER.log(Level.FINE, "startActivity(MemorizingActivity)", e);
                        }
                    }
                });

        LOGGER.log(Level.FINE, "openDecisions: " + openDecisions + ", waiting on " + myId);
        try {
            synchronized (choice) {
                choice.wait();
            }
        } catch (InterruptedException e) {
            LOGGER.log(Level.FINER, "InterruptedException", e);
        }
        LOGGER.log(Level.FINE, "finished wait on " + myId + ": " + choice.state);
        return choice.state;
    }

    void interactCert(final X509Certificate[] chain, String authType, CertificateException cause)
            throws CertificateException {
        switch (interact(certChainMessage(chain, cause), R.string.mtm_accept_cert)) {
            case MTMDecision.DECISION_ALWAYS:
                storeCert(chain[0]); // only store the server cert, not the whole chain
            case MTMDecision.DECISION_ONCE:
                break;
            default:
                throw (cause);
        }
    }

    public X509TrustManager getNonInteractive(
            String domain,
            String verifiedHostname,
            int port,
            Consumer<Boolean> daneCb,
            boolean enforceDane) {
        return getNonInteractive(
                domain,
                verifiedHostname,
                port,
                daneCb,
                enforceDane,
                eu.siacs.conversations.http.HttpConnectionManager.Route.DIRECT);
    }

    public X509TrustManager getInteractive(
            String domain,
            String verifiedHostname,
            int port,
            Consumer<Boolean> daneCb,
            boolean enforceDane) {
        return getInteractive(
                domain,
                verifiedHostname,
                port,
                daneCb,
                enforceDane,
                eu.siacs.conversations.http.HttpConnectionManager.Route.DIRECT);
    }

    /** {@code dnsRoute}: the network the connection uses; DANE's DNS lookups follow it. */
    public X509TrustManager getNonInteractive(
            String domain,
            String verifiedHostname,
            int port,
            Consumer<Boolean> daneCb,
            boolean enforceDane,
            eu.siacs.conversations.http.HttpConnectionManager.Route dnsRoute) {
        return new NonInteractiveMemorizingTrustManager(
                domain, verifiedHostname, port, daneCb, enforceDane, dnsRoute);
    }

    public X509TrustManager getInteractive(
            String domain,
            String verifiedHostname,
            int port,
            Consumer<Boolean> daneCb,
            boolean enforceDane,
            eu.siacs.conversations.http.HttpConnectionManager.Route dnsRoute) {
        return new InteractiveMemorizingTrustManager(
                domain, verifiedHostname, port, daneCb, enforceDane, dnsRoute);
    }

    public X509TrustManager getNonInteractive() {
        return new NonInteractiveMemorizingTrustManager(
                null,
                null,
                0,
                null,
                false,
                eu.siacs.conversations.http.HttpConnectionManager.Route.DIRECT);
    }

    public X509TrustManager getInteractive() {
        return new InteractiveMemorizingTrustManager(
                null,
                null,
                0,
                null,
                false,
                eu.siacs.conversations.http.HttpConnectionManager.Route.DIRECT);
    }

    private class NonInteractiveMemorizingTrustManager implements X509TrustManager {

        private final String domain;
        private final String verifiedHostname;
        private final int port;
        private final Consumer<Boolean> daneCb;
        private final boolean enforceDane;
        private final eu.siacs.conversations.http.HttpConnectionManager.Route dnsRoute;

        public NonInteractiveMemorizingTrustManager(
                String domain,
                String verifiedHostname,
                int port,
                Consumer<Boolean> daneCb,
                boolean enforceDane,
                eu.siacs.conversations.http.HttpConnectionManager.Route dnsRoute) {
            this.domain = domain;
            this.verifiedHostname = verifiedHostname;
            this.port = port;
            this.daneCb = daneCb;
            this.enforceDane = enforceDane;
            this.dnsRoute = dnsRoute;
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType)
                throws CertificateException {
            MemorizingTrustManager.this.checkCertTrusted(
                    chain,
                    authType,
                    domain,
                    false,
                    false,
                    verifiedHostname,
                    port,
                    daneCb,
                    enforceDane,
                    dnsRoute);
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType)
                throws CertificateException {
            MemorizingTrustManager.this.checkCertTrusted(
                    chain,
                    authType,
                    domain,
                    true,
                    false,
                    verifiedHostname,
                    port,
                    daneCb,
                    enforceDane,
                    dnsRoute);
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return MemorizingTrustManager.this.getAcceptedIssuers();
        }
    }

    private class InteractiveMemorizingTrustManager implements X509TrustManager {
        private final String domain;
        private final String verifiedHostname;
        private final int port;
        private final Consumer<Boolean> daneCb;
        private final boolean enforceDane;
        private final eu.siacs.conversations.http.HttpConnectionManager.Route dnsRoute;

        public InteractiveMemorizingTrustManager(
                String domain,
                String verifiedHostname,
                int port,
                Consumer<Boolean> daneCb,
                boolean enforceDane,
                eu.siacs.conversations.http.HttpConnectionManager.Route dnsRoute) {
            this.domain = domain;
            this.verifiedHostname = verifiedHostname;
            this.port = port;
            this.daneCb = daneCb;
            this.enforceDane = enforceDane;
            this.dnsRoute = dnsRoute;
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType)
                throws CertificateException {
            MemorizingTrustManager.this.checkCertTrusted(
                    chain,
                    authType,
                    domain,
                    false,
                    true,
                    verifiedHostname,
                    port,
                    daneCb,
                    enforceDane,
                    dnsRoute);
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType)
                throws CertificateException {
            MemorizingTrustManager.this.checkCertTrusted(
                    chain,
                    authType,
                    domain,
                    true,
                    true,
                    verifiedHostname,
                    port,
                    daneCb,
                    enforceDane,
                    dnsRoute);
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return MemorizingTrustManager.this.getAcceptedIssuers();
        }
    }
}
