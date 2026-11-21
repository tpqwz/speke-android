package eu.siacs.conversations.crypto.sasl;

import com.google.common.hash.HashFunction;
import com.google.common.hash.Hashing;
import com.google.common.io.BaseEncoding;
import eu.siacs.conversations.entities.Account;
import eu.siacs.conversations.xmpp.Jid;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.Assert;
import org.junit.Test;

/**
 * Covers the XEP-0474 handling in {@link ScramMechanism}: when downgrade protection is required the
 * server-first-message must carry a matching hash, and the rule-6 path sends "n" in the GS2 header
 * instead of "y".
 */
public class ScramDowngradeProtectionTest {

    private static final HashFunction SHA256 = Hashing.sha256();
    private static final DowngradeProtection ANNOUNCED =
            new DowngradeProtection(
                    List.of("SCRAM-SHA-1", "SCRAM-SHA-256", "SCRAM-SHA-256-PLUS"),
                    List.of("tls-unique"));

    private static String correctHash() {
        return BaseEncoding.base64()
                .encode(
                        SHA256.hashBytes(ANNOUNCED.asHString().getBytes(StandardCharsets.UTF_8))
                                .asBytes());
    }

    private static String clientFirst(final ScramMechanism scram) {
        return new String(
                BaseEncoding.base64().decode(scram.getClientFirstMessage(null)),
                StandardCharsets.UTF_8);
    }

    private static ScramSha256 mechanism() {
        return new ScramSha256(new Account(Jid.of("alice@example.com"), "password"));
    }

    @Test
    public void withoutRequirementAMissingHashIsAccepted() throws Exception {
        ScramMechanism.checkDowngradeProtection(null, ANNOUNCED, false, SHA256);
    }

    @Test(expected = SaslMechanism.DowngradeException.class)
    public void requiredProtectionRejectsAMissingHash() throws Exception {
        ScramMechanism.checkDowngradeProtection(null, ANNOUNCED, true, SHA256);
    }

    @Test
    public void requiredProtectionAcceptsAMatchingHash() throws Exception {
        ScramMechanism.checkDowngradeProtection(correctHash(), ANNOUNCED, true, SHA256);
    }

    /** A mismatching hash is a downgrade, whether or not protection was required. */
    @Test(expected = SaslMechanism.DowngradeException.class)
    public void aMismatchingHashIsADowngrade() throws Exception {
        final String wrong = BaseEncoding.base64().encode(new byte[32]);
        ScramMechanism.checkDowngradeProtection(wrong, ANNOUNCED, false, SHA256);
    }

    /** The hash covers the channel-binding list too: stripping a type changes it. */
    @Test(expected = SaslMechanism.DowngradeException.class)
    public void strippedChannelBindingIsDetected() throws Exception {
        final DowngradeProtection stripped =
                new DowngradeProtection(
                        List.of("SCRAM-SHA-1", "SCRAM-SHA-256", "SCRAM-SHA-256-PLUS"), List.of());
        ScramMechanism.checkDowngradeProtection(correctHash(), stripped, true, SHA256);
    }

    /** By default a non-PLUS SCRAM login signals "y" (client supports binding, server doesn't). */
    @Test
    public void defaultHeaderIsY() {
        Assert.assertTrue(clientFirst(mechanism()).startsWith("y,,"));
    }

    @Test
    public void continuingWithoutChannelBindingSendsN() {
        final ScramSha256 scram = mechanism();
        scram.continueWithoutChannelBinding();
        Assert.assertTrue(clientFirst(scram).startsWith("n,,"));
    }

    /** Only allowed before the client-first-message went out. */
    @Test(expected = IllegalStateException.class)
    public void headerCannotChangeAfterTheFirstMessage() {
        final ScramSha256 scram = mechanism();
        scram.getClientFirstMessage(null);
        scram.continueWithoutChannelBinding();
    }
}
