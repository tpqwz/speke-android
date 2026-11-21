package org.minidns.dane;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.minidns.DnsWorld.cname;
import static org.minidns.DnsWorld.record;
import static org.minidns.dnssec.MonoclesDnssecTestResults.result;

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
 * monocles: pins the local {@link DaneVerifier} patch for TLSA records reached through a signed
 * CNAME into an unsigned zone. MiniDNS sees that combined answer as partially signed and throws,
 * but it is insecure, not bogus: DANE doesn't apply and the normal certificate check decides.
 * Everything that really is bogus must still abort (RFC 6698 § 4.1), because monocles treats a
 * bogus TLSA answer as fatal even without "Enforce DANE".
 */
public class MonoclesCnameIntoUnsignedZoneTest {

    private static final String TLSA_NAME = "_443._tcp.www.example.com";
    private static final String TARGET = "_443._tcp.host.unsigned-provider.net";
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
            final Object answer = answers.get(DnsName.from(name) + "|" + type);
            if (answer instanceof IOException) {
                throw (IOException) answer;
            }
            if (answer == null) {
                throw new AssertionError("unexpected query " + name + " " + type);
            }
            return (DnssecQueryResult) answer;
        }
    }

    private static DnssecValidationFailedException partiallySigned(final String name) {
        return new DnssecValidationFailedException(new Question(name, TYPE.TLSA), "Only some records are signed!");
    }

    private static DaneVerificationResult verify(final StubClient client) throws Exception {
        return new DaneVerifier(client).daneVerify(NO_CHAIN, "www.example.com", 443);
    }

    @Test
    public void signedCnameIntoUnsignedZoneIsNotAuthenticated() throws Exception {
        final StubClient client = new StubClient()
                .answer(TLSA_NAME, TYPE.TLSA, partiallySigned(TLSA_NAME))
                .answer(TLSA_NAME, TYPE.CNAME,
                        result(new Question(TLSA_NAME, TYPE.CNAME), true, record(TLSA_NAME, cname(TARGET))))
                .answer(TARGET, TYPE.TLSA, result(new Question(TARGET, TYPE.TLSA), false));
        assertEquals(DaneVerificationResult.notAuthenticated, verify(client));
    }

    /** A CNAME that is not itself authenticated could be forged: stay bogus. */
    @Test
    public void unauthenticatedCnameStaysBogus() {
        final StubClient client = new StubClient()
                .answer(TLSA_NAME, TYPE.TLSA, partiallySigned(TLSA_NAME))
                .answer(TLSA_NAME, TYPE.CNAME,
                        result(new Question(TLSA_NAME, TYPE.CNAME), false, record(TLSA_NAME, cname(TARGET))));
        assertThrows(DaneCertificateException.DnssecValidationFailed.class, () -> verify(client));
    }

    /** No alias at the name: the failure is about the TLSA records themselves. */
    @Test
    public void bogusWithoutCnameStaysBogus() {
        final StubClient client = new StubClient()
                .answer(TLSA_NAME, TYPE.TLSA, partiallySigned(TLSA_NAME))
                .answer(TLSA_NAME, TYPE.CNAME, result(new Question(TLSA_NAME, TYPE.CNAME), true));
        assertThrows(DaneCertificateException.DnssecValidationFailed.class, () -> verify(client));
    }

    /** A bogus CNAME answer is no way out either. */
    @Test
    public void bogusCnameStaysBogus() {
        final StubClient client = new StubClient()
                .answer(TLSA_NAME, TYPE.TLSA, partiallySigned(TLSA_NAME))
                .answer(TLSA_NAME, TYPE.CNAME, partiallySigned(TLSA_NAME));
        assertThrows(DaneCertificateException.DnssecValidationFailed.class, () -> verify(client));
    }

    /** Following the CNAME into a zone whose own TLSA answer is bogus must still abort. */
    @Test
    public void bogusTargetStaysBogus() {
        final StubClient client = new StubClient()
                .answer(TLSA_NAME, TYPE.TLSA, partiallySigned(TLSA_NAME))
                .answer(TLSA_NAME, TYPE.CNAME,
                        result(new Question(TLSA_NAME, TYPE.CNAME), true, record(TLSA_NAME, cname(TARGET))))
                .answer(TARGET, TYPE.TLSA, partiallySigned(TARGET))
                .answer(TARGET, TYPE.CNAME, result(new Question(TARGET, TYPE.CNAME), true));
        assertThrows(DaneCertificateException.DnssecValidationFailed.class, () -> verify(client));
    }
}
