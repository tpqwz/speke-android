package org.minidns.dane;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.minidns.dnssec.MonoclesDnssecTestResults.result;
import static org.minidns.dnssec.MonoclesDnssecTestResults.strippedInSignedZone;

import java.io.IOException;
import java.security.cert.X509Certificate;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.minidns.cache.LruCache;
import org.minidns.dnsmessage.Question;
import org.minidns.dnsname.DnsName;
import org.minidns.dnssec.DnssecClient;
import org.minidns.dnssec.DnssecQueryResult;
import org.minidns.dnssec.DnssecValidationFailedException;
import org.minidns.record.Record.TYPE;

/**
 * monocles: a TLSA answer that merely lacks signatures although its zone is proven signed (stripped, or a forwarding,
 * split-horizon or VPN resolver) is not authenticated, not bogus. {@link DaneVerifier} then reports
 * {@link DaneVerificationResult#notAuthenticated}, which MemorizingTrustManager treats as fatal only when DANE is
 * enforced or pinned for the account (Codeberg #564-#566). Cryptographically bogus TLSA answers still abort.
 */
public class MonoclesUnverifiableTlsaTest {

    private static final String HOST = "xmpp.example.com";
    private static final String TLSA_NAME = "_5222._tcp." + HOST;
    /** Never inspected: every case returns or throws before a certificate is matched. */
    private static final X509Certificate[] NO_CHAIN = new X509Certificate[0];

    /** A DnssecClient that answers from a table instead of the network. */
    private static final class StubClient extends DnssecClient {
        private final Map<String, Object> answers = new HashMap<>();

        StubClient() {
            super(new LruCache(0));
        }

        StubClient answer(final String name, final TYPE type, final Object resultOrException) {
            answers.put(DnsName.from(name) + "|" + type, resultOrException);
            return this;
        }

        @Override
        public DnssecQueryResult queryDnssec(final CharSequence name, final TYPE type) throws IOException {
            final String key = DnsName.from(name) + "|" + type;
            final Object answer = answers.get(key);
            if (answer instanceof IOException) {
                throw (IOException) answer;
            }
            if (answer == null && answers.containsKey(key)) {
                return null; // no response, e.g. SERVFAIL
            }
            if (answer == null) {
                throw new AssertionError("unexpected query " + name + " " + type);
            }
            return (DnssecQueryResult) answer;
        }
    }

    private static DaneVerificationResult verify(final StubClient client) throws Exception {
        return new DaneVerifier(client).daneVerify(NO_CHAIN, HOST, 5222);
    }

    @Test
    public void strippedTlsaInSignedZoneIsNotAuthenticated() throws Exception {
        final StubClient client = new StubClient()
                .answer(TLSA_NAME, TYPE.TLSA, strippedInSignedZone(new Question(TLSA_NAME, TYPE.TLSA)));
        assertEquals(DaneVerificationResult.notAuthenticated, verify(client));
    }

    @Test
    public void noTlsaResponseIsNotAuthenticated() throws Exception {
        // SERVFAIL/timeout: queryDnssec() returns null. That used to be an accidental NullPointerException.
        final StubClient client = new StubClient().answer(TLSA_NAME, TYPE.TLSA, null);
        assertEquals(DaneVerificationResult.notAuthenticated, verify(client));
    }

    @Test
    public void invalidSignatureStaysBogus() {
        final StubClient client = new StubClient()
                .answer(TLSA_NAME, TYPE.TLSA, new DnssecValidationFailedException(
                        new Question(TLSA_NAME, TYPE.TLSA), "Signature is invalid."))
                .answer(TLSA_NAME, TYPE.CNAME, result(new Question(TLSA_NAME, TYPE.CNAME), true));
        assertThrows(DaneCertificateException.DnssecValidationFailed.class, () -> verify(client));
    }

    @Test
    public void invalidDenialProofStaysBogus() {
        final StubClient client = new StubClient()
                .answer(TLSA_NAME, TYPE.TLSA, new DnssecValidationFailedException(
                        new Question(TLSA_NAME, TYPE.TLSA), "Invalid NSEC3 proof: next closer name is not covered"))
                .answer(TLSA_NAME, TYPE.CNAME, result(new Question(TLSA_NAME, TYPE.CNAME), true));
        assertThrows(DaneCertificateException.DnssecValidationFailed.class, () -> verify(client));
    }
}
