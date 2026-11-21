/*
 * Copyright 2015-2024 the original author or authors
 *
 * This software is licensed under the Apache License, Version 2.0,
 * the GNU Lesser General Public License version 2 or later ("LGPL")
 * and the WTFPL.
 * You may choose either license to govern your use of this software only
 * upon the condition that you accept all of the terms of either
 * the Apache License 2.0, the LGPL 2.1+ or the WTFPL.
 */
package org.minidns.dnssec;

import org.minidns.dnslabel.DnsLabel;
import org.minidns.dnsmessage.Question;
import org.minidns.dnsname.DnsName;
import org.minidns.dnssec.DnssecUnverifiedReason.AlgorithmExceptionThrownReason;
import org.minidns.dnssec.DnssecUnverifiedReason.AlgorithmNotSupportedReason;
import org.minidns.dnssec.DnssecUnverifiedReason.NSECDoesNotMatchReason;
import org.minidns.dnssec.DnssecValidationFailedException.DigestComparisonFailedException;
import org.minidns.dnssec.algorithms.AlgorithmMap;
import org.minidns.record.DNSKEY;
import org.minidns.record.Data;
import org.minidns.record.DelegatingDnssecRR;
import org.minidns.record.NSEC;
import org.minidns.record.NSEC3;
import org.minidns.record.RRSIG;
import org.minidns.record.Record;
import org.minidns.record.Record.TYPE;
import org.minidns.util.Base32;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

class Verifier {
    private static final AlgorithmMap algorithmMap = AlgorithmMap.INSTANCE;

    public static DnssecUnverifiedReason verify(Record<DNSKEY> dnskeyRecord, DelegatingDnssecRR ds) throws DnssecValidationFailedException {
        DNSKEY dnskey = dnskeyRecord.payloadData;
        DigestCalculator digestCalculator = algorithmMap.getDsDigestCalculator(ds.digestType);
        if (digestCalculator == null) {
            return new AlgorithmNotSupportedReason(ds.digestTypeByte, ds.getType(), dnskeyRecord);
        }

        byte[] dnskeyData = dnskey.toByteArray();
        byte[] dnskeyOwner = dnskeyRecord.name.getBytes();
        byte[] combined = new byte[dnskeyOwner.length + dnskeyData.length];
        System.arraycopy(dnskeyOwner, 0, combined, 0, dnskeyOwner.length);
        System.arraycopy(dnskeyData, 0, combined, dnskeyOwner.length, dnskeyData.length);
        byte[] digest;
        try {
            digest = digestCalculator.digest(combined);
        } catch (Exception e) {
            return new AlgorithmExceptionThrownReason(ds.digestType, "DS", dnskeyRecord, e);
        }

        if (!ds.digestEquals(digest)) {
            throw DigestComparisonFailedException.from(dnskeyRecord, ds, digest);
        }
        return null;
    }

    public static DnssecUnverifiedReason verify(List<Record<? extends Data>> records, RRSIG rrsig, DNSKEY key) throws IOException {
        SignatureVerifier signatureVerifier = algorithmMap.getSignatureVerifier(rrsig.algorithm);
        if (signatureVerifier == null) {
            return new AlgorithmNotSupportedReason(rrsig.algorithmByte, rrsig.getType(), records.get(0));
        }

        byte[] combine = combine(rrsig, records);
        if (signatureVerifier.verify(combine, rrsig, key)) {
            return null;
        } else {
            throw new DnssecValidationFailedException(records, "Signature is invalid.");
        }
    }

    public static DnssecUnverifiedReason verifyNsec(Record<NSEC> nsecRecord, Question q) {
        NSEC nsec = nsecRecord.payloadData;
        if (nsecRecord.name.equals(q.name) && !nsec.types.contains(q.type)) {
            // records with same name but different types exist
            return null;
        } else if (nsecMatches(q.name, nsecRecord.name, nsec.next)) {
            return null;
        }
        return new NSECDoesNotMatchReason(q, nsecRecord);
    }

    public static DnssecUnverifiedReason verifyNsec3(DnsName zone, Record<NSEC3> nsec3record, Question q) {
        NSEC3 nsec3 = nsec3record.payloadData;
        DigestCalculator digestCalculator = algorithmMap.getNsecDigestCalculator(nsec3.hashAlgorithm);
        if (digestCalculator == null) {
            return new AlgorithmNotSupportedReason(nsec3.hashAlgorithmByte, nsec3.getType(), nsec3record);
        }

        byte[] bytes = nsec3hash(digestCalculator, nsec3, q.name, nsec3.iterations);
        String s = Base32.encodeToString(bytes);
        DnsName computedNsec3Record = DnsName.from(s + "." + zone);
        if (nsec3record.name.equals(computedNsec3Record)) {
            if (nsec3.types.contains(q.type)) {
                // TODO: Refine exception thrown in this case.
                return new NSECDoesNotMatchReason(q, nsec3record);
            }
            return null;
        }
        if (nsecMatches(s, nsec3record.name.getHostpart(), Base32.encodeToString(nsec3.getNextHashed()))) {
            return null;
        }
        return new NSECDoesNotMatchReason(q, nsec3record);
    }

    /**
     * monocles: NSEC3 iteration counts above this make the response insecure instead of being hashed (RFC 9276 § 3.2).
     * It also bounds the hashing work an attacker can cause with one response.
     */
    static final int NSEC3_MAX_ITERATIONS = 150;

    /**
     * monocles: the result of {@link #verifyNsec3Proof(DnsName, List, Question, boolean)}.
     */
    static final class Nsec3Proof {
        enum Status {
            /** The NSEC3 RRs prove the denial of existence. */
            VALID,
            /** The denial can not be validated (opt-out, too many iterations, unknown hash), DNSSEC does not apply. */
            INSECURE,
            /** The NSEC3 RRs do not prove the denial: the response is bogus. */
            INVALID,
        }

        final Status status;
        final DnssecUnverifiedReason reason;
        final String error;

        private Nsec3Proof(Status status, DnssecUnverifiedReason reason, String error) {
            this.status = status;
            this.reason = reason;
            this.error = error;
        }

        static final Nsec3Proof VALID = new Nsec3Proof(Status.VALID, null, null);

        static Nsec3Proof insecure(DnssecUnverifiedReason reason) {
            return new Nsec3Proof(Status.INSECURE, reason, null);
        }

        static Nsec3Proof invalid(String error) {
            return new Nsec3Proof(Status.INVALID, null, error);
        }
    }

    static final class Nsec3InsecureReason extends DnssecUnverifiedReason {
        private final String reason;

        Nsec3InsecureReason(Question q, String reason) {
            this.reason = "NSEC3 denial of " + q.type + " at " + q.name + " is insecure: " + reason;
        }

        @Override
        public String getReasonString() {
            return reason;
        }
    }

    /**
     * monocles: verifies the NSEC3 RRs of a negative response as a whole (RFC 5155 § 8). Upstream MiniDNS only checks
     * whether a single NSEC3 RR matches or covers the QNAME, but an NXDOMAIN proof consists of the closest encloser
     * proof plus a covered wildcard, and the QNAME itself is usually covered by none of the NSEC3 RRs. Those valid
     * proofs were reported as bogus, which made DANE abort the connection to e.g. every NSEC3-signed XMPP server
     * without TLSA RRs.
     * <p>
     * The signatures of the NSEC3 RRs are not checked here, the caller has to do that.
     * </p>
     *
     * @param zone the zone of the SOA RR in the authority section.
     * @param nsec3Records the NSEC3 RRs of the authority section.
     * @param q the question.
     * @param nxDomain whether the response code is NXDOMAIN, otherwise it is a NODATA response.
     * @return the result of the verification.
     */
    static Nsec3Proof verifyNsec3Proof(DnsName zone, List<Record<NSEC3>> nsec3Records, Question q, boolean nxDomain) {
        if (nsec3Records.isEmpty()) {
            return Nsec3Proof.invalid("no NSEC3 RRs");
        }
        if (!q.name.isChildOf(zone)) {
            return Nsec3Proof.invalid(q.name + " is not in zone " + zone);
        }

        NSEC3 first = nsec3Records.get(0).payloadData;
        for (Record<NSEC3> record : nsec3Records) {
            NSEC3 nsec3 = record.payloadData;
            if (nsec3.hashAlgorithmByte != first.hashAlgorithmByte || nsec3.iterations != first.iterations
                    || !Arrays.equals(nsec3.getSalt(), first.getSalt())) {
                return Nsec3Proof.invalid("NSEC3 RRs with different parameters");
            }
            if (record.name.getLabelCount() != zone.getLabelCount() + 1 || !record.name.isChildOf(zone)) {
                return Nsec3Proof.invalid("NSEC3 RR " + record.name + " is not directly below zone " + zone);
            }
        }

        DigestCalculator digestCalculator = algorithmMap.getNsecDigestCalculator(first.hashAlgorithm);
        if (digestCalculator == null) {
            return Nsec3Proof.insecure(new AlgorithmNotSupportedReason(first.hashAlgorithmByte, first.getType(), nsec3Records.get(0)));
        }
        if (first.iterations > NSEC3_MAX_ITERATIONS) {
            return Nsec3Proof.insecure(new Nsec3InsecureReason(q, first.iterations + " iterations exceed " + NSEC3_MAX_ITERATIONS));
        }

        Nsec3Chain chain = new Nsec3Chain(digestCalculator, first, nsec3Records);

        Record<NSEC3> qnameMatch = chain.matching(q.name);
        if (nxDomain) {
            if (qnameMatch != null) {
                return Nsec3Proof.invalid("NXDOMAIN, but an NSEC3 RR matches " + q.name);
            }
        } else if (qnameMatch != null) {
            // NODATA (RFC 5155 § 8.5, § 8.6 for DS).
            List<TYPE> types = qnameMatch.payloadData.types;
            if (types.contains(q.type) || types.contains(TYPE.CNAME)) {
                return Nsec3Proof.invalid("NSEC3 RR of " + q.name + " has type " + q.type + " or CNAME");
            }
            if (q.type != TYPE.DS && types.contains(TYPE.NS) && !types.contains(TYPE.SOA)) {
                return Nsec3Proof.invalid("NSEC3 RR of " + q.name + " is a delegation, not authoritative for " + q.type);
            }
            return Nsec3Proof.VALID;
        }

        // Closest encloser proof (RFC 5155 § 8.3).
        DnsName closestEncloser = null;
        Record<NSEC3> closestEncloserMatch = null;
        for (DnsName candidate = q.name; candidate.getLabelCount() > zone.getLabelCount(); ) {
            candidate = candidate.getParent();
            closestEncloserMatch = chain.matching(candidate);
            if (closestEncloserMatch != null) {
                closestEncloser = candidate;
                break;
            }
        }
        if (closestEncloser == null) {
            return Nsec3Proof.invalid("no closest encloser of " + q.name);
        }
        List<TYPE> closestEncloserTypes = closestEncloserMatch.payloadData.types;
        if (closestEncloserTypes.contains(TYPE.DNAME)
                || (closestEncloserTypes.contains(TYPE.NS) && !closestEncloserTypes.contains(TYPE.SOA))) {
            // The names below a DNAME or a delegation are not in this zone (RFC 5155 § 8.3).
            return Nsec3Proof.invalid("closest encloser " + closestEncloser + " is a DNAME or a delegation");
        }
        DnsName nextCloser = q.name.stripToLabels(closestEncloser.getLabelCount() + 1);
        Record<NSEC3> nextCloserCover = chain.covering(nextCloser);
        if (nextCloserCover == null) {
            return Nsec3Proof.invalid("next closer name " + nextCloser + " is not covered");
        }
        boolean optOut = (nextCloserCover.payloadData.flags & NSEC3.FLAG_OPT_OUT) != 0;

        DnsName wildcard = DnsName.from(DnsLabel.WILDCARD_LABEL, closestEncloser);
        if (nxDomain) {
            // RFC 5155 § 8.4.
            if (chain.covering(wildcard) == null) {
                return Nsec3Proof.invalid("wildcard " + wildcard + " is not covered");
            }
            if (optOut) {
                // An unsigned delegation may exist at the next closer name (RFC 5155 § 9.2).
                return Nsec3Proof.insecure(new Nsec3InsecureReason(q, "opt-out span covers " + nextCloser));
            }
            return Nsec3Proof.VALID;
        }

        // Wildcard NODATA (RFC 5155 § 8.7).
        Record<NSEC3> wildcardMatch = chain.matching(wildcard);
        if (wildcardMatch != null) {
            List<TYPE> types = wildcardMatch.payloadData.types;
            if (types.contains(q.type) || types.contains(TYPE.CNAME)) {
                return Nsec3Proof.invalid("NSEC3 RR of " + wildcard + " has type " + q.type + " or CNAME");
            }
            return Nsec3Proof.VALID;
        }
        // No DS at an unsigned delegation in an opt-out span (RFC 5155 § 8.6).
        if (q.type == TYPE.DS && optOut) {
            return Nsec3Proof.insecure(new Nsec3InsecureReason(q, "opt-out span covers " + nextCloser));
        }
        return Nsec3Proof.invalid("no NSEC3 RR proves NODATA for " + q.type + " at " + q.name);
    }

    /**
     * monocles: the types of the NSEC3 RR that matches name, or null if none does or the NSEC3 RRs can not be used.
     * The NSEC3 RRs must have been validated.
     */
    static List<TYPE> nsec3MatchingTypes(DnsName zone, List<Record<NSEC3>> nsec3Records, DnsName name) {
        NSEC3 first = nsec3Records.get(0).payloadData;
        for (Record<NSEC3> record : nsec3Records) {
            NSEC3 nsec3 = record.payloadData;
            if (nsec3.hashAlgorithmByte != first.hashAlgorithmByte || nsec3.iterations != first.iterations
                    || !Arrays.equals(nsec3.getSalt(), first.getSalt()) || !record.name.isChildOf(zone)) {
                return null;
            }
        }
        DigestCalculator digestCalculator = algorithmMap.getNsecDigestCalculator(first.hashAlgorithm);
        if (digestCalculator == null || first.iterations > NSEC3_MAX_ITERATIONS) {
            return null;
        }
        Record<NSEC3> match = new Nsec3Chain(digestCalculator, first, nsec3Records).matching(name);
        return match == null ? null : match.payloadData.types;
    }

    /**
     * monocles: the hashed owner names of a set of NSEC3 RRs with the same parameters.
     */
    private static final class Nsec3Chain {
        private final DigestCalculator digestCalculator;
        private final NSEC3 parameters;
        private final List<Record<NSEC3>> records;
        private final List<String> owners;
        private final List<String> nexts;

        Nsec3Chain(DigestCalculator digestCalculator, NSEC3 parameters, List<Record<NSEC3>> records) {
            this.digestCalculator = digestCalculator;
            this.parameters = parameters;
            this.records = records;
            owners = new ArrayList<>(records.size());
            nexts = new ArrayList<>(records.size());
            for (Record<NSEC3> record : records) {
                owners.add(record.name.getHostpart().toLowerCase(Locale.ROOT));
                nexts.add(Base32.encodeToString(record.payloadData.getNextHashed()).toLowerCase(Locale.ROOT));
            }
        }

        private String hash(DnsName name) {
            byte[] hash = nsec3hash(digestCalculator, parameters, name, parameters.iterations);
            return Base32.encodeToString(hash).toLowerCase(Locale.ROOT);
        }

        Record<NSEC3> matching(DnsName name) {
            String hash = hash(name);
            for (int i = 0; i < records.size(); i++) {
                if (owners.get(i).equals(hash)) {
                    return records.get(i);
                }
            }
            return null;
        }

        /**
         * The NSEC3 RR whose span strictly contains the hash of name. Base32hex preserves the order of the hashes and
         * all have the same length, so comparing the strings compares the hashes.
         */
        Record<NSEC3> covering(DnsName name) {
            String hash = hash(name);
            for (int i = 0; i < records.size(); i++) {
                String owner = owners.get(i);
                String next = nexts.get(i);
                boolean covered;
                if (owner.compareTo(next) < 0) {
                    covered = owner.compareTo(hash) < 0 && hash.compareTo(next) < 0;
                } else {
                    // The last NSEC3 RR of the chain wraps around to the first one.
                    covered = owner.compareTo(hash) < 0 || hash.compareTo(next) < 0;
                }
                if (covered) {
                    return records.get(i);
                }
            }
            return null;
        }
    }

    static byte[] combine(RRSIG rrsig, List<Record<? extends Data>> records) {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(bos);

        // Write RRSIG without signature
        try {
            rrsig.writePartialSignature(dos);

            DnsName sigName = records.get(0).name;
            if (!sigName.isRootLabel()) {
                if (sigName.getLabelCount() < rrsig.labels) {
                    // TODO: This is currently not covered by the unit tests.
                    throw new DnssecValidationFailedException("Invalid RRsig record");
                }

                if (sigName.getLabelCount() > rrsig.labels) {
                    // TODO: This is currently not covered by the unit tests.
                    // Expand wildcards
                    sigName = DnsName.from(DnsLabel.WILDCARD_LABEL, sigName.stripToLabels(rrsig.labels));
                }
            }

            List<byte[]> recordBytes = new ArrayList<>(records.size());
            for (Record<? extends Data> record : records) {
                Record<Data> ref = new Record<Data>(sigName, record.type, record.clazzValue, rrsig.originalTtl, record.payloadData);
                recordBytes.add(ref.toByteArray());
            }

            // Sort correctly (cause they might be ordered randomly) as per RFC 4034 § 6.3.
            final int offset = sigName.size() + 10; // Where the RDATA begins
            Collections.sort(recordBytes, new Comparator<byte[]>() {
                @Override
                public int compare(byte[] b1, byte[] b2) {
                    for (int i = offset; i < b1.length && i < b2.length; i++) {
                        if (b1[i] != b2[i]) {
                            return (b1[i] & 0xFF) - (b2[i] & 0xFF);
                        }
                    }
                    return b1.length - b2.length;
                }
            });

            for (byte[] recordByte : recordBytes) {
                dos.write(recordByte);
            }
            dos.flush();
        } catch (IOException e) {
            // Never happens
            throw new RuntimeException(e);
        }
        return bos.toByteArray();
    }

    static boolean nsecMatches(String test, String lowerBound, String upperBound) {
        return nsecMatches(DnsName.from(test), DnsName.from(lowerBound), DnsName.from(upperBound));
    }

    /**
     * Tests if a nsec domain name is part of an NSEC record.
     *
     * @param test       test domain name
     * @param lowerBound inclusive lower bound
     * @param upperBound exclusive upper bound
     * @return test domain name is covered by NSEC record
     */
    static boolean nsecMatches(DnsName test, DnsName lowerBound, DnsName upperBound) {
        int lowerParts = lowerBound.getLabelCount();
        int upperParts = upperBound.getLabelCount();
        int testParts = test.getLabelCount();

        if (testParts > lowerParts && !test.isChildOf(lowerBound) && test.stripToLabels(lowerParts).compareTo(lowerBound) < 0)
            return false;
        if (testParts <= lowerParts && test.compareTo(lowerBound.stripToLabels(testParts)) < 0)
            return false;

        if (testParts > upperParts && !test.isChildOf(upperBound) && test.stripToLabels(upperParts).compareTo(upperBound) > 0)
            return false;
        if (testParts <= upperParts && test.compareTo(upperBound.stripToLabels(testParts)) >= 0)
            return false;

        return true;
    }

    static byte[] nsec3hash(DigestCalculator digestCalculator, NSEC3 nsec3, DnsName ownerName, int iterations) {
        return nsec3hash(digestCalculator, nsec3.getSalt(), ownerName.getBytes(), iterations);
    }

    /**
     * Derived from RFC 5155 Section 5.
     *
     * @param digestCalculator the digest calculator.
     * @param salt the salt.
     * @param data the data.
     * @param iterations the number of iterations.
     * @return the NSEC3 hash.
     */
    static byte[] nsec3hash(DigestCalculator digestCalculator, byte[] salt, byte[] data, int iterations) {
        while (iterations-- >= 0) {
            byte[] combined = new byte[data.length + salt.length];
            System.arraycopy(data, 0, combined, 0, data.length);
            System.arraycopy(salt, 0, combined, data.length, salt.length);
            data = digestCalculator.digest(combined);
        }
        return data;
    }
}
