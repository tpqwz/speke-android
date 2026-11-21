package eu.siacs.conversations.utils;

import eu.siacs.conversations.xmpp.Jid;
import org.junit.Assert;
import org.junit.Test;

public class JidHelperTest {

    @Test
    public void domainOnlyJidDoesNotCrash() {
        // a MUC may report an occupant's real JID as a bare domain
        Assert.assertEquals("example", JidHelper.localPartOrFallback(Jid.ofDomain("example.com")));
    }

    @Test
    public void blacklistedLocalPartFallsBackToDomain() {
        Assert.assertEquals("example", JidHelper.localPartOrFallback(Jid.of("xmpp@example.com")));
    }

    @Test
    public void localPartIsUsed() {
        Assert.assertEquals("alice", JidHelper.localPartOrFallback(Jid.of("alice@example.com")));
    }
}
