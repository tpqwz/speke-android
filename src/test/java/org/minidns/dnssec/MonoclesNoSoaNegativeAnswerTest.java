package org.minidns.dnssec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.minidns.DnsWorld.a;
import static org.minidns.DnsWorld.applyZones;
import static org.minidns.DnsWorld.ns;
import static org.minidns.DnsWorld.record;
import static org.minidns.DnsWorld.zone;
import static org.minidns.dnssec.DnssecWorld.addNsec;
import static org.minidns.dnssec.DnssecWorld.ds;
import static org.minidns.dnssec.DnssecWorld.merge;
import static org.minidns.dnssec.DnssecWorld.sign;
import static org.minidns.dnssec.DnssecWorld.signedRootZone;

import java.net.InetAddress;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.minidns.DnsWorld;
import org.minidns.cache.LruCache;
import org.minidns.constants.DnssecConstants.DigestAlgorithm;
import org.minidns.constants.DnssecConstants.SignatureAlgorithm;
import org.minidns.dane.DaneVerificationResult;
import org.minidns.dane.DaneVerifier;
import org.minidns.dnsmessage.DnsMessage;
import org.minidns.dnsmessage.Question;
import org.minidns.dnsname.DnsName;
import org.minidns.iterative.ReliableDnsClient.Mode;
import org.minidns.record.DNSKEY;
import org.minidns.record.Data;
import org.minidns.record.Record;
import org.minidns.record.Record.TYPE;

/**
 * monocles: a negative answer without SOA RR is not authenticated, never bogus. Recursive resolvers (AdGuard,
 * Cloudflare, MikroTik) answer TLSA questions for hosts in the unsigned duckdns.org zone with NOERROR and empty answer
 * and authority sections; upstream MiniDNS threw AuthorityDoesNotContainSoa for that, which made DANE abort every such
 * connection with DANE_FAILED even with DANE off. In a proven signed zone the same shape is flagged as possible
 * stripping (SignaturesExpectedButMissingReason), so it stays fatal when DANE is enforced or pinned.
 */
public class MonoclesNoSoaNegativeAnswerTest {

    private static final String HOST = "example.com";
    private static final String TLSA_NAME = "_5222._tcp." + HOST;
    /** Never inspected: every case returns before a certificate is matched. */
    private static final X509Certificate[] NO_CHAIN = new X509Certificate[0];

    private static final SignatureAlgorithm ALGORITHM = SignatureAlgorithm.RSASHA256;
    private static final DigestAlgorithm DIGEST = DigestAlgorithm.SHA1;
    private static final DNSKEY ROOT_KSK;
    private static final PrivateKey ROOT_PRIVATE_KSK;
    private static final DNSKEY ROOT_ZSK;
    private static final PrivateKey ROOT_PRIVATE_ZSK;
    private static final DNSKEY COM_KSK;
    private static final PrivateKey COM_PRIVATE_KSK;

    static {
        DnssecWorld.DnssecData root = DnssecWorld.getDnssecDataFor("");
        ROOT_KSK = root.ksk;
        ROOT_PRIVATE_KSK = root.privateKsk;
        ROOT_ZSK = root.zsk;
        ROOT_PRIVATE_ZSK = root.privateZsk;
        DnssecWorld.DnssecData com = DnssecWorld.getDnssecDataFor("com");
        COM_KSK = com.ksk;
        COM_PRIVATE_KSK = com.privateKsk;
    }

    /** NOERROR with empty answer and authority sections (no SOA) for the TLSA question, from any server. */
    private static final class EmptyNoErrorResponse implements DnsWorld.PreparedResponse {
        private final Question question = new Question(TLSA_NAME, TYPE.TLSA);

        @Override
        public boolean isResponse(DnsMessage request, InetAddress address) {
            return request.questions.size() == 1 && request.questions.get(0).equals(question);
        }

        @Override
        public DnsMessage getResponse() {
            return DnsMessage.builder()
                    .setResponseCode(DnsMessage.RESPONSE_CODE.NO_ERROR)
                    .setQrFlag(true)
                    .setRecursionAvailable(true)
                    .setAuthoritativeAnswer(true)
                    .build();
        }
    }

    private static DnssecClient client() {
        DnssecClient client = new DnssecClient(new LruCache(0));
        client.addSecureEntryPoint(DnsName.ROOT, ROOT_KSK.getKey());
        client.setMode(Mode.iterativeOnly);
        return client;
    }

    /** com is an unsigned delegation, proven by the signed root. */
    private static DnssecClient unsignedComClient(boolean emptyNoError) {
        DnssecClient client = client();
        DnsWorld world = applyZones(client,
                signedRootZone(
                        sign(ROOT_KSK, "", ROOT_PRIVATE_KSK, ALGORITHM,
                                record("", ROOT_KSK),
                                record("", ROOT_ZSK)),
                        sign(ROOT_ZSK, "", ROOT_PRIVATE_ZSK, ALGORITHM,
                                record("com", ns("ns.com"))),
                        sign(ROOT_ZSK, "", ROOT_PRIVATE_ZSK, ALGORITHM,
                                record("ns.com", a("1.1.1.1")))
                ), zone("com", "ns.com", "1.1.1.1",
                        record(HOST, a("1.1.1.2"))
                )
        );
        addNsec(world, "", "a.root-servers.net", "com", "org", TYPE.NS);
        if (emptyNoError) {
            world.addPreparedResponse(new EmptyNoErrorResponse());
        }
        return client;
    }

    /** com is signed (DS at the root, self-signed DNSKEY RRset). */
    private static DnssecClient signedComClient(boolean emptyNoError) {
        DnssecClient client = client();
        List<Record<? extends Data>> com = new ArrayList<>(merge(
                sign(COM_KSK, "com", COM_PRIVATE_KSK, ALGORITHM,
                        record("com", COM_KSK),
                        record("com", DnssecWorld.getDnssecDataFor("com").zsk))));
        com.add(record(HOST, a("1.1.1.2")));
        DnsWorld world = applyZones(client,
                signedRootZone(
                        sign(ROOT_KSK, "", ROOT_PRIVATE_KSK, ALGORITHM,
                                record("", ROOT_KSK),
                                record("", ROOT_ZSK)),
                        sign(ROOT_ZSK, "", ROOT_PRIVATE_ZSK, ALGORITHM,
                                record("com", ds("com", DIGEST, COM_KSK))),
                        sign(ROOT_ZSK, "", ROOT_PRIVATE_ZSK, ALGORITHM,
                                record("com", ns("ns.com"))),
                        sign(ROOT_ZSK, "", ROOT_PRIVATE_ZSK, ALGORITHM,
                                record("ns.com", a("1.1.1.1")))
                ), zone("com", "ns.com", "1.1.1.1", com)
        );
        if (emptyNoError) {
            world.addPreparedResponse(new EmptyNoErrorResponse());
        }
        return client;
    }

    private static boolean hasReason(DnssecQueryResult result, Class<? extends DnssecUnverifiedReason> type) {
        return result.getUnverifiedReasons().stream().anyMatch(type::isInstance);
    }

    @Test
    public void emptyNoErrorWithoutSoaInUnsignedZoneIsUnauthenticated() throws Exception {
        DnssecClient client = unsignedComClient(true);
        DnssecQueryResult result = client.queryDnssec(TLSA_NAME, TYPE.TLSA);
        assertFalse(result.isAuthenticData());
        assertTrue(hasReason(result, DnssecUnverifiedReason.NoSoaInNegativeAnswerReason.class),
                result.getUnverifiedReasons().toString());
        assertFalse(hasReason(result, DnssecUnverifiedReason.SignaturesExpectedButMissingReason.class),
                result.getUnverifiedReasons().toString());
    }

    @Test
    public void emptyNoErrorWithoutSoaInUnsignedZoneIsNotFatalForDane() throws Exception {
        DnssecClient client = unsignedComClient(true);
        assertEquals(DaneVerificationResult.notAuthenticated,
                new DaneVerifier(client).daneVerify(NO_CHAIN, HOST, 5222));
    }

    @Test
    public void nxDomainWithoutSoaInUnsignedZoneIsNotFatalForDane() throws Exception {
        DnssecClient client = unsignedComClient(false);
        assertEquals(DaneVerificationResult.notAuthenticated,
                new DaneVerifier(client).daneVerify(NO_CHAIN, HOST, 5222));
    }

    @Test
    public void emptyNoErrorWithoutSoaInSignedZoneIsReportedAsStripping() throws Exception {
        DnssecClient client = signedComClient(true);
        DnssecQueryResult result = client.queryDnssec(TLSA_NAME, TYPE.TLSA);
        assertFalse(result.isAuthenticData());
        assertTrue(hasReason(result, DnssecUnverifiedReason.NoSoaInNegativeAnswerReason.class),
                result.getUnverifiedReasons().toString());
        assertTrue(hasReason(result, DnssecUnverifiedReason.SignaturesExpectedButMissingReason.class),
                result.getUnverifiedReasons().toString());
        // Unauthenticated, so fatal only when DANE is enforced or pinned (MemorizingTrustManager).
        assertEquals(DaneVerificationResult.notAuthenticated,
                new DaneVerifier(signedComClient(true)).daneVerify(NO_CHAIN, HOST, 5222));
    }
}
