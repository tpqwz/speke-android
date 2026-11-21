package eu.siacs.conversations.crypto.sasl;

import java.util.Collection;

/**
 * The client rules of XEP-0440 (SASL Channel-Binding Type Capability) v1.0.0, with rule 6 as
 * relaxed by XEP-0474 (SASL SCRAM Downgrade Protection) v0.5.0.
 *
 * <p>They decide, from what the server announced, whether authentication may go ahead at all. Kept
 * free of connection state so every rule can be tested on its own.
 */
public final class ChannelBindingRules {

    public enum Outcome {
        /** Nothing suspicious; pick the best mechanism as usual. */
        PROCEED,
        /** The announcements contradict each other: an implementation error or an active MITM. */
        ABORT,
        /**
         * SCRAM-*-PLUS and channel-binding types were announced, but none of the types is usable
         * here. Continue with a non-PLUS SCRAM mechanism, send "n" in the GS2 header and require a
         * valid XEP-0474 downgrade protection hash (new rule 6).
         */
        CONTINUE_WITHOUT_CHANNEL_BINDING
    }

    private ChannelBindingRules() {}

    public static boolean isPlusMechanism(final String mechanism) {
        return mechanism != null && mechanism.endsWith("-PLUS");
    }

    public static boolean anyPlusMechanism(final Collection<String> mechanisms) {
        for (final String mechanism : mechanisms) {
            if (isPlusMechanism(mechanism)) {
                return true;
            }
        }
        return false;
    }

    /**
     * @param version the SASL profile used for this authentication
     * @param plusOffered whether any SCRAM-*-PLUS (channel-binding) mechanism was announced
     * @param bindingsAnnounced whether the server announced at least one channel-binding type
     * @param bindingUsable whether one of the announced types can be used on this connection
     */
    public static Outcome evaluate(
            final SaslMechanism.Version version,
            final boolean plusOffered,
            final boolean bindingsAnnounced,
            final boolean bindingUsable) {
        if (plusOffered && !bindingsAnnounced) {
            // rule 4: with SASL2 the types must have been stripped; undefined for SASL1, where
            // the client may still try a channel binding of its choice
            return version == SaslMechanism.Version.SASL_2 ? Outcome.ABORT : Outcome.PROCEED;
        }
        if (bindingsAnnounced && !plusOffered) {
            // rule 5: MUST abort with SASL2. With SASL1 it is only a SHOULD; a non-PLUS SCRAM
            // login still sends "y", so a server that really supports channel binding refuses it.
            return version == SaslMechanism.Version.SASL_2 ? Outcome.ABORT : Outcome.PROCEED;
        }
        if (plusOffered && !bindingUsable) {
            // rule 6 as changed by XEP-0474
            return Outcome.CONTINUE_WITHOUT_CHANNEL_BINDING;
        }
        return Outcome.PROCEED;
    }
}
