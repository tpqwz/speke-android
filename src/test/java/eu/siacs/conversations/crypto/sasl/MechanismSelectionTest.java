package eu.siacs.conversations.crypto.sasl;

import eu.siacs.conversations.entities.Account;
import eu.siacs.conversations.utils.SSLSockets;
import eu.siacs.conversations.xmpp.Jid;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import org.junit.Assert;
import org.junit.Test;

/**
 * Mechanism choice in {@link SaslMechanism.Factory}: OAUTHBEARER beats PLAIN only for an account
 * without a password (upstream be1da49ae9); an account with a password is never moved to a
 * bearer or anonymous login.
 */
public class MechanismSelectionTest {

    private static SaslMechanism pick(final String password, final Collection<String> mechanisms) {
        final var account = new Account(Jid.of("alice@example.com"), password);
        return new SaslMechanism.Factory(account)
                .of(
                        mechanisms,
                        Collections.emptyList(),
                        SaslMechanism.Version.SASL,
                        SSLSockets.Version.TLS_1_3,
                        false);
    }

    @Test
    public void noPasswordPrefersOAuthOverPlain() {
        Assert.assertTrue(pick("", List.of("PLAIN", "OAUTHBEARER")) instanceof OAuthBearer);
        Assert.assertTrue(pick(null, List.of("OAUTHBEARER", "PLAIN")) instanceof OAuthBearer);
    }

    @Test
    public void passwordNeverPicksOAuth() {
        Assert.assertTrue(pick("secret", List.of("PLAIN", "OAUTHBEARER")) instanceof Plain);
        Assert.assertNull(pick("secret", List.of("OAUTHBEARER")));
        Assert.assertNull(pick("secret", List.of("ANONYMOUS")));
    }

    @Test
    public void scramStillBeatsOAuth() {
        Assert.assertTrue(
                pick("", List.of("SCRAM-SHA-256", "OAUTHBEARER")) instanceof ScramSha256);
    }

    @Test
    public void plainStillBeatsAnonymous() {
        Assert.assertTrue(pick("", List.of("ANONYMOUS", "PLAIN")) instanceof Plain);
        Assert.assertTrue(pick("", List.of("ANONYMOUS")) instanceof Anonymous);
        Assert.assertTrue(pick("", List.of("PLAIN")) instanceof Plain);
    }
}
