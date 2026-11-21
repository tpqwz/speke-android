package eu.siacs.conversations.crypto.sasl;

import static eu.siacs.conversations.crypto.sasl.ChannelBindingRules.Outcome.ABORT;
import static eu.siacs.conversations.crypto.sasl.ChannelBindingRules.Outcome.CONTINUE_WITHOUT_CHANNEL_BINDING;
import static eu.siacs.conversations.crypto.sasl.ChannelBindingRules.Outcome.PROCEED;

import java.util.List;
import org.junit.Assert;
import org.junit.Test;

/**
 * Covers {@link ChannelBindingRules} - the XEP-0440 v1.0.0 client rules, with rule 6 as relaxed by
 * XEP-0474 v0.5.0. A wrong answer here either lets a MITM strip channel binding or locks users out,
 * so each rule is pinned for both SASL profiles.
 */
public class ChannelBindingRulesTest {

    private static final SaslMechanism.Version SASL1 = SaslMechanism.Version.SASL;
    private static final SaslMechanism.Version SASL2 = SaslMechanism.Version.SASL_2;

    @Test
    public void plusMechanismsAreRecognised() {
        Assert.assertTrue(ChannelBindingRules.isPlusMechanism("SCRAM-SHA-256-PLUS"));
        Assert.assertFalse(ChannelBindingRules.isPlusMechanism("SCRAM-SHA-256"));
        Assert.assertFalse(ChannelBindingRules.isPlusMechanism(null));
        Assert.assertTrue(
                ChannelBindingRules.anyPlusMechanism(List.of("PLAIN", "SCRAM-SHA-1-PLUS")));
        Assert.assertFalse(ChannelBindingRules.anyPlusMechanism(List.of("PLAIN", "SCRAM-SHA-1")));
    }

    /** The normal case: PLUS offered, types announced, one of them usable. */
    @Test
    public void usableChannelBindingProceeds() {
        Assert.assertEquals(PROCEED, ChannelBindingRules.evaluate(SASL2, true, true, true));
        Assert.assertEquals(PROCEED, ChannelBindingRules.evaluate(SASL1, true, true, true));
    }

    /** A server without channel binding at all must still work. */
    @Test
    public void noChannelBindingAnywhereProceeds() {
        Assert.assertEquals(PROCEED, ChannelBindingRules.evaluate(SASL2, false, false, false));
        Assert.assertEquals(PROCEED, ChannelBindingRules.evaluate(SASL1, false, false, false));
    }

    /** Rule 4: PLUS offered over SASL2 but the types were stripped. */
    @Test
    public void strippedTypesAbortWithSasl2() {
        Assert.assertEquals(ABORT, ChannelBindingRules.evaluate(SASL2, true, false, false));
    }

    /** Rule 4 is undefined for SASL1, which may still try a binding of its own choice. */
    @Test
    public void strippedTypesProceedWithSasl1() {
        Assert.assertEquals(PROCEED, ChannelBindingRules.evaluate(SASL1, true, false, false));
    }

    /** Rule 5: types announced over SASL2 but the PLUS mechanisms were stripped. */
    @Test
    public void strippedPlusMechanismsAbortWithSasl2() {
        Assert.assertEquals(ABORT, ChannelBindingRules.evaluate(SASL2, false, true, true));
        Assert.assertEquals(ABORT, ChannelBindingRules.evaluate(SASL2, false, true, false));
    }

    /**
     * Rule 5 is only a SHOULD for SASL1; the "y" flag still makes the server refuse a downgrade.
     */
    @Test
    public void strippedPlusMechanismsProceedWithSasl1() {
        Assert.assertEquals(PROCEED, ChannelBindingRules.evaluate(SASL1, false, true, true));
    }

    /** Rule 6 (XEP-0474): no usable type - continue without binding, but only with SSDP. */
    @Test
    public void unusableTypesContinueWithoutChannelBinding() {
        Assert.assertEquals(
                CONTINUE_WITHOUT_CHANNEL_BINDING,
                ChannelBindingRules.evaluate(SASL2, true, true, false));
        Assert.assertEquals(
                CONTINUE_WITHOUT_CHANNEL_BINDING,
                ChannelBindingRules.evaluate(SASL1, true, true, false));
    }
}
