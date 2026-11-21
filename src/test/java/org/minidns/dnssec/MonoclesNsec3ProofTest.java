package org.minidns.dnssec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.minidns.DnsWorld.nsec3;
import static org.minidns.DnsWorld.record;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.minidns.dnsmessage.Question;
import org.minidns.dnsname.DnsName;
import org.minidns.dnssec.Verifier.Nsec3Proof.Status;
import org.minidns.record.NSEC3;
import org.minidns.record.Record;
import org.minidns.record.Record.TYPE;

/**
 * monocles: {@link Verifier#verifyNsec3Proof} with the real NSEC3 RRs of trolug.de (SHA-1, 21 iterations, salted),
 * whose NXDOMAIN for the TLSA RR of its XMPP server upstream MiniDNS rejected as bogus. The proof for
 * _5222._tcp.xmpp.trolug.de is: xmpp.trolug.de (closest encloser) matches ip6tujf..., the next closer name
 * _tcp.xmpp.trolug.de is covered by dees9um..., and *.xmpp.trolug.de is covered by bn1dfoe.... No RR covers the
 * QNAME itself.
 */
public class MonoclesNsec3ProofTest {

    private static final DnsName ZONE = DnsName.from("trolug.de");
    private static final byte[] SALT = hex("765243706C4B62774B5566744F4F523065644B3464413D3D");
    private static final int ITERATIONS = 21;
    private static final Question TLSA_QUESTION = new Question("_5222._tcp.xmpp.trolug.de", TYPE.TLSA);

    private static final String CLOSEST_ENCLOSER = "ip6tujf84lms8u7mdapfh0p2nc4ltq11";
    private static final String NEXT_CLOSER_COVER = "dees9um18baa072on2vunvqaubn8997g";
    private static final String WILDCARD_COVER = "bn1dfoe50l5ksd2kuoncoqhvpbpvv3jf";

    private static Record<NSEC3> rr(String owner, String next, int iterations, byte flags, TYPE... types) {
        NSEC3 nsec3 = nsec3((byte) 1, flags, iterations, SALT, base32hex(next), types);
        return record(owner + ".trolug.de", nsec3).as(NSEC3.class);
    }

    private static Record<NSEC3> closestEncloser(TYPE... types) {
        return rr(CLOSEST_ENCLOSER, "iuab27idssjqllnl235h69csavm6chur", ITERATIONS, (byte) 0, types);
    }

    private static Record<NSEC3> nextCloserCover(byte flags) {
        return rr(NEXT_CLOSER_COVER, "f7u91i1urhvabq0lt4qckib8stggin3j", ITERATIONS, flags, TYPE.TXT, TYPE.RRSIG);
    }

    private static Record<NSEC3> wildcardCover() {
        return rr(WILDCARD_COVER, "cjoobch22egduhe12votcbie6t4r4ah4", ITERATIONS, (byte) 0, TYPE.TXT, TYPE.RRSIG);
    }

    private static List<Record<NSEC3>> trolugProof() {
        return Arrays.asList(closestEncloser(TYPE.A, TYPE.MX, TYPE.RRSIG), nextCloserCover((byte) 0), wildcardCover());
    }

    private static Status verify(List<Record<NSEC3>> records, Question q, boolean nxDomain) {
        return Verifier.verifyNsec3Proof(ZONE, records, q, nxDomain).status;
    }

    @Test
    public void nxDomainClosestEncloserProofIsValid() {
        assertEquals(Status.VALID, verify(trolugProof(), TLSA_QUESTION, true));
        assertEquals(Status.VALID, verify(trolugProof(), new Question("_5223._tcp.xmpp.trolug.de", TYPE.TLSA), true));
    }

    @Test
    public void nxDomainWithoutWildcardCoverIsInvalid() {
        List<Record<NSEC3>> records = Arrays.asList(closestEncloser(TYPE.A, TYPE.RRSIG), nextCloserCover((byte) 0));
        assertEquals(Status.INVALID, verify(records, TLSA_QUESTION, true));
    }

    @Test
    public void nxDomainWithoutNextCloserCoverIsInvalid() {
        List<Record<NSEC3>> records = Arrays.asList(closestEncloser(TYPE.A, TYPE.RRSIG), wildcardCover());
        assertEquals(Status.INVALID, verify(records, TLSA_QUESTION, true));
    }

    @Test
    public void nxDomainWithoutClosestEncloserIsInvalid() {
        List<Record<NSEC3>> records = Arrays.asList(nextCloserCover((byte) 0), wildcardCover());
        assertEquals(Status.INVALID, verify(records, TLSA_QUESTION, true));
    }

    @Test
    public void nxDomainBelowDnameOrDelegationIsInvalid() {
        List<Record<NSEC3>> dname = Arrays.asList(closestEncloser(TYPE.DNAME, TYPE.RRSIG), nextCloserCover((byte) 0), wildcardCover());
        assertEquals(Status.INVALID, verify(dname, TLSA_QUESTION, true));
        List<Record<NSEC3>> delegation = Arrays.asList(closestEncloser(TYPE.NS, TYPE.RRSIG), nextCloserCover((byte) 0), wildcardCover());
        assertEquals(Status.INVALID, verify(delegation, TLSA_QUESTION, true));
    }

    @Test
    public void nxDomainForExistingNameIsInvalid() {
        assertEquals(Status.INVALID, verify(trolugProof(), new Question("xmpp.trolug.de", TYPE.TLSA), true));
    }

    @Test
    public void nxDomainInOptOutSpanIsInsecure() {
        List<Record<NSEC3>> records = Arrays.asList(closestEncloser(TYPE.A, TYPE.RRSIG), nextCloserCover(NSEC3.FLAG_OPT_OUT), wildcardCover());
        assertEquals(Status.INSECURE, verify(records, TLSA_QUESTION, true));
    }

    @Test
    public void tooManyIterationsAreInsecure() {
        int iterations = Verifier.NSEC3_MAX_ITERATIONS + 1;
        List<Record<NSEC3>> records = new ArrayList<>();
        records.add(rr(CLOSEST_ENCLOSER, "iuab27idssjqllnl235h69csavm6chur", iterations, (byte) 0, TYPE.A));
        assertEquals(Status.INSECURE, verify(records, TLSA_QUESTION, true));
    }

    @Test
    public void mixedParametersAreInvalid() {
        List<Record<NSEC3>> records = new ArrayList<>(trolugProof());
        records.set(2, rr(WILDCARD_COVER, "cjoobch22egduhe12votcbie6t4r4ah4", ITERATIONS + 1, (byte) 0, TYPE.TXT));
        assertEquals(Status.INVALID, verify(records, TLSA_QUESTION, true));
    }

    @Test
    public void nsec3OutsideZoneIsInvalid() {
        List<Record<NSEC3>> records = new ArrayList<>(trolugProof());
        NSEC3 nsec3 = nsec3((byte) 1, (byte) 0, ITERATIONS, SALT, base32hex("cjoobch22egduhe12votcbie6t4r4ah4"), TYPE.TXT);
        records.set(2, record(WILDCARD_COVER + ".example.org", nsec3).as(NSEC3.class));
        assertEquals(Status.INVALID, verify(records, TLSA_QUESTION, true));
    }

    @Test
    public void wrappingLastNsec3Covers() {
        // The last RR of a chain wraps around: v000... -> e000... covers the next closer name (dl8h...) and the
        // wildcard (bugn...).
        Record<NSEC3> last = rr("v0000000000000000000000000000000", "e0000000000000000000000000000000", ITERATIONS, (byte) 0, TYPE.TXT);
        List<Record<NSEC3>> records = Arrays.asList(closestEncloser(TYPE.A, TYPE.RRSIG), last);
        assertEquals(Status.VALID, verify(records, TLSA_QUESTION, true));
    }

    @Test
    public void noData() {
        List<Record<NSEC3>> records = Arrays.asList(closestEncloser(TYPE.A, TYPE.MX, TYPE.RRSIG));
        assertEquals(Status.VALID, verify(records, new Question("xmpp.trolug.de", TYPE.TXT), false));
        assertEquals(Status.INVALID, verify(records, new Question("xmpp.trolug.de", TYPE.A), false));
        List<Record<NSEC3>> cname = Arrays.asList(closestEncloser(TYPE.CNAME, TYPE.RRSIG));
        assertEquals(Status.INVALID, verify(cname, new Question("xmpp.trolug.de", TYPE.TXT), false));
        // A NODATA without a matching NSEC3 RR is no proof.
        assertEquals(Status.INVALID, verify(trolugProof(), TLSA_QUESTION, false));
    }

    @Test
    public void noDsInOptOutSpanIsInsecure() {
        Question ds = new Question("_tcp.xmpp.trolug.de", TYPE.DS);
        List<Record<NSEC3>> records = Arrays.asList(closestEncloser(TYPE.A, TYPE.RRSIG), nextCloserCover(NSEC3.FLAG_OPT_OUT));
        assertEquals(Status.INSECURE, verify(records, ds, false));
        List<Record<NSEC3>> noOptOut = Arrays.asList(closestEncloser(TYPE.A, TYPE.RRSIG), nextCloserCover((byte) 0));
        assertEquals(Status.INVALID, verify(noOptOut, ds, false));
    }

    private static byte[] hex(String hex) {
        byte[] bytes = new BigInteger("01" + hex, 16).toByteArray();
        return Arrays.copyOfRange(bytes, 1, bytes.length);
    }

    private static byte[] base32hex(String s) {
        BigInteger value = BigInteger.ONE;
        for (char c : s.toCharArray()) {
            value = value.shiftLeft(5).or(BigInteger.valueOf(Character.digit(c, 32)));
        }
        byte[] bytes = value.toByteArray();
        return Arrays.copyOfRange(bytes, bytes.length - s.length() * 5 / 8, bytes.length);
    }
}
