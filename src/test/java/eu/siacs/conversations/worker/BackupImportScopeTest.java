package eu.siacs.conversations.worker;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.util.Map;
import java.util.Set;
import org.junit.Assert;
import org.junit.Test;

/** A backup may only add rows to its own account and its own conversations. */
public class BackupImportScopeTest {

    private static final String ACCOUNT = "acc-1";
    private static final Set<String> CONVERSATIONS = ImmutableSet.of("conv-1");
    private static final Set<String> MUCS = ImmutableSet.of("room@conference.example.org");

    private static boolean inScope(final String table, final Map<String, String> row) {
        return ImportBackupWorker.rowInScope(table, row::get, ACCOUNT, CONVERSATIONS, MUCS);
    }

    @Test
    public void ownRowsAreAccepted() {
        Assert.assertTrue(
                inScope("conversations", ImmutableMap.of("accountUuid", ACCOUNT, "uuid", "conv-2")));
        Assert.assertTrue(inScope("messages", ImmutableMap.of("conversationUuid", "conv-1")));
        Assert.assertTrue(inScope("webxdc_updates", ImmutableMap.of("conversationUuid", "conv-1")));
        Assert.assertTrue(
                inScope(
                        "pinned_messages",
                        ImmutableMap.of("account_uuid", ACCOUNT, "conversation_uuid", "conv-1")));
        Assert.assertTrue(
                inScope(
                        "muted_participants",
                        ImmutableMap.of("muc_jid", "room@conference.example.org", "occupant_id", "x")));
        Assert.assertTrue(inScope("identities", ImmutableMap.of("account", ACCOUNT)));
        Assert.assertTrue(inScope("omemo2_sessions", ImmutableMap.of("account", ACCOUNT)));
    }

    @Test
    public void rowsOfOtherAccountsAreRejected() {
        Assert.assertFalse(
                inScope("conversations", ImmutableMap.of("accountUuid", "other", "uuid", "conv-9")));
        Assert.assertFalse(inScope("messages", ImmutableMap.of("conversationUuid", "conv-9")));
        Assert.assertFalse(inScope("messages", ImmutableMap.of()));
        Assert.assertFalse(inScope("webxdc_updates", ImmutableMap.of("conversationUuid", "conv-9")));
        Assert.assertFalse(
                inScope(
                        "pinned_messages",
                        ImmutableMap.of("account_uuid", "other", "conversation_uuid", "conv-1")));
        Assert.assertFalse(
                inScope(
                        "pinned_messages",
                        ImmutableMap.of("account_uuid", ACCOUNT, "conversation_uuid", "conv-9")));
        Assert.assertFalse(
                inScope("muted_participants", ImmutableMap.of("muc_jid", "other@conference.example.org")));
        Assert.assertFalse(inScope("identities", ImmutableMap.of("account", "other")));
        Assert.assertFalse(inScope("kyber_prekeys", ImmutableMap.of()));
        Assert.assertFalse(inScope("unknown_table", ImmutableMap.of("account", ACCOUNT)));
    }

    @Test
    public void nothingIsAcceptedBeforeTheAccountIsKnown() {
        Assert.assertFalse(
                ImportBackupWorker.rowInScope(
                        "conversations",
                        ImmutableMap.of("accountUuid", ACCOUNT, "uuid", "c")::get,
                        null,
                        CONVERSATIONS,
                        MUCS));
    }
}
