package org.minidns.dnssec;

import java.util.Collections;
import java.util.Set;

import org.minidns.dnsmessage.DnsMessage;
import org.minidns.dnsmessage.Question;
import org.minidns.dnsqueryresult.TestWorldDnsQueryResult;
import org.minidns.record.Data;
import org.minidns.record.RRSIG;
import org.minidns.record.Record;

/**
 * monocles: builds {@link DnssecQueryResult}s for tests outside this package (its constructor is
 * package-private), so a stub {@link DnssecClient} can answer with a chosen DNSSEC outcome.
 */
public final class MonoclesDnssecTestResults {

    private MonoclesDnssecTestResults() {}

    /** A result for {@code question} with the given answers, authenticated or not (unsigned). */
    @SafeVarargs
    public static DnssecQueryResult result(
            final Question question, final boolean authenticated, final Record<? extends Data>... answers) {
        final DnsMessage.Builder builder = DnsMessage.builder().setQuestion(question);
        for (final Record<? extends Data> answer : answers) {
            builder.addAnswer(answer);
        }
        final DnsMessage response = builder.build();
        final DnsMessage query = DnsMessage.builder().setQuestion(question).build();
        final Set<DnssecUnverifiedReason> reasons =
                authenticated
                        ? Collections.<DnssecUnverifiedReason>emptySet()
                        : Collections.<DnssecUnverifiedReason>singleton(
                                new DnssecUnverifiedReason.NoSignaturesReason(question));
        return new DnssecQueryResult(
                response,
                new TestWorldDnsQueryResult(query, response),
                Collections.<Record<RRSIG>>emptySet(),
                reasons);
    }

    /**
     * An unsigned result for {@code question} from a zone that {@code DnssecClient.provenSigned()} shows to be signed,
     * as {@code DnssecClient.queryDnssec()} returns it: possibly stripped, not authenticated.
     */
    @SafeVarargs
    public static DnssecQueryResult strippedInSignedZone(
            final Question question, final Record<? extends Data>... answers) {
        final DnssecQueryResult unsigned = result(question, false, answers);
        final Set<DnssecUnverifiedReason> reasons = new java.util.HashSet<>(unsigned.getUnverifiedReasons());
        reasons.add(new DnssecUnverifiedReason.SignaturesExpectedButMissingReason(question));
        return new DnssecQueryResult(
                unsigned.synthesizedResponse,
                unsigned.dnsQueryResult,
                Collections.<Record<RRSIG>>emptySet(),
                reasons);
    }
}
