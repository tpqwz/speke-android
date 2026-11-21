package eu.siacs.conversations.crypto;

import android.content.Context;
import android.util.Log;
import androidx.annotation.Nullable;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.hash.Hashing;
import eu.siacs.conversations.Config;
import eu.siacs.conversations.R;
import java.io.IOException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.Set;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

public final class TrustManagers {

    // SHA-256 of root CAs we refuse even though the system store ships them (Conversations
    // 9233a441e). Applies to everything built on createDefaultTrustManager(): XMPP (PKIX and the
    // DANE PKIX-EE check), HTTP upload/download, link previews and channel discovery.
    private static final Set<String> BLOCKED_ROOT_SHA256 =
            ImmutableSet.of(
                    // https://emudhra.we-have-a-problem.com/roots.json
                    "7dd78d5f4f13459a83dff9abbba62edbaf6f2d102bf257fd712f4d9f2746ed8d",
                    "f2edaaff3d9b701fb4d14c9f285f8c0bad90fc3bcc1fa07d47f4718edaba34a5",
                    "d7a7a0fb5d7e2731d771e9484ebcdef71d5f0c3e0a2948782bc83ee0ea699ef4",
                    "86a1ecba089c4a8d3bbe2734c612ba341d813e043cf9e8a862cd5c57a36bbe6b",
                    // the other emSign Root CAs
                    "40f6af0346a99aa1cd1d555a4e9cce62c7f9634603ee406615833dc8c8d00367",
                    "125609aa301da0a249b97a8239cb6a34216f44dcac9f3954b14292f2e8c8608f",
                    "bc4d809b15189d78db3e1d8cf4f9726a795da1643ca5f1358e1ddb0edc0d7eb3");

    private static X509TrustManager filteredDefaultTrustManager;

    private TrustManagers() {
        throw new IllegalStateException("Do not instantiate me");
    }

    public static X509TrustManager createTrustManager(@Nullable final KeyStore keyStore)
            throws NoSuchAlgorithmException, KeyStoreException {
        final TrustManagerFactory trustManagerFactory =
                TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trustManagerFactory.init(keyStore);
        return Iterables.getOnlyElement(
                Iterables.filter(
                        Arrays.asList(trustManagerFactory.getTrustManagers()),
                        X509TrustManager.class));
    }

    public static boolean isBlockedRoot(final X509Certificate certificate)
            throws CertificateException {
        return BLOCKED_ROOT_SHA256.contains(
                Hashing.sha256().hashBytes(certificate.getEncoded()).toString());
    }

    /**
     * The system trust store (system and user CAs, as our network security config trusts both)
     * minus {@link #BLOCKED_ROOT_SHA256}. Built once. If the device's CA store cannot be read we
     * fall back to the unfiltered platform default (what we used before the blocklist) rather than
     * leaving the app without any trust manager; that fallback is not cached.
     */
    public static synchronized X509TrustManager createDefaultTrustManager()
            throws NoSuchAlgorithmException, KeyStoreException, CertificateException, IOException {
        if (filteredDefaultTrustManager == null) {
            final KeyStore filtered;
            try {
                filtered = getFilteredKeyStore();
            } catch (final KeyStoreException
                    | CertificateException
                    | IOException
                    | NoSuchAlgorithmException e) {
                Log.w(
                        Config.LOGTAG,
                        "unable to build filtered CA store, using platform default",
                        e);
                return createTrustManager(null);
            }
            filteredDefaultTrustManager = createTrustManager(filtered);
        }
        return filteredDefaultTrustManager;
    }

    private static KeyStore getFilteredKeyStore()
            throws KeyStoreException, CertificateException, IOException, NoSuchAlgorithmException {
        final KeyStore systemStore = KeyStore.getInstance("AndroidCAStore");
        systemStore.load(null, null);
        final KeyStore filteredStore = KeyStore.getInstance(KeyStore.getDefaultType());
        filteredStore.load(null, null);
        int count = 0;
        final Enumeration<String> aliases = systemStore.aliases();
        while (aliases.hasMoreElements()) {
            final String alias = aliases.nextElement();
            if (!systemStore.isCertificateEntry(alias)) {
                continue;
            }
            final Certificate certificate = systemStore.getCertificate(alias);
            if (certificate instanceof X509Certificate x509) {
                if (isBlockedRoot(x509)) {
                    Log.d(Config.LOGTAG, "blocking CA " + x509.getSubjectX500Principal());
                    continue;
                }
                filteredStore.setCertificateEntry(alias, x509);
                count++;
            }
        }
        if (count == 0) {
            // never end up with an empty anchor set; createDefaultTrustManager falls back
            throw new KeyStoreException("system CA store is empty");
        }
        return filteredStore;
    }

    public static X509TrustManager defaultWithBundledLetsEncrypt(final Context context)
            throws NoSuchAlgorithmException, KeyStoreException, CertificateException, IOException {
        final BundledTrustManager bundleTrustManager =
                BundledTrustManager.builder()
                        .loadKeyStore(
                                context.getResources().openRawResource(R.raw.letsencrypt),
                                "letsencrypt")
                        .build();
        return CombiningTrustManager.combineWithDefault(bundleTrustManager);
    }
}
