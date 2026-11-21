package eu.siacs.conversations.worker;

import eu.siacs.conversations.entities.Message;
import org.junit.Assert;
import org.junit.Test;

/** A disappearing message goes into a backup only as a content-free deleted row. */
public class BackupDisappearingMessageTest {

    private static String disappearing(final String column, final String value) {
        return ExportBackupWorker.backupColumnValue(column, value, true);
    }

    @Test
    public void contentOfADisappearingMessageIsLeftOut() {
        Assert.assertNull(disappearing(Message.BODY, "secret"));
        Assert.assertNull(disappearing(Message.SUBJECT, "topic"));
        Assert.assertNull(disappearing(Message.PAYLOADS, "<html/>"));
        Assert.assertNull(disappearing(Message.FILE_PARAMS, "https://example.org/f#key"));
        Assert.assertNull(disappearing(Message.RELATIVE_FILE_PATH, "/data/files/a.jpg"));
        Assert.assertNull(disappearing(Message.REACTIONS, "[\"x\"]"));
        Assert.assertNull(disappearing(Message.BODY_LANGUAGE, "de"));
    }

    @Test
    public void itIsStoredAsDeletedAndUnencrypted() {
        Assert.assertEquals("1", disappearing(Message.DELETED, "0"));
        Assert.assertEquals("1", disappearing("file_deleted", "0"));
        Assert.assertEquals(
                String.valueOf(Message.ENCRYPTION_NONE),
                disappearing(
                        Message.ENCRYPTION, String.valueOf(Message.ENCRYPTION_AXOLOTL_OMEMO2)));
    }

    @Test
    public void idsAndTimerAreKept() {
        Assert.assertEquals("uuid-1", disappearing(Message.UUID, "uuid-1"));
        Assert.assertEquals("origin-1", disappearing(Message.REMOTE_MSG_ID, "origin-1"));
        Assert.assertEquals("stanza-1", disappearing(Message.SERVER_MSG_ID, "stanza-1"));
        Assert.assertEquals("3600", disappearing(Message.EPHEMERAL_TIMER, "3600"));
        Assert.assertEquals("1700000000000", disappearing(Message.EXPIRE_AT, "1700000000000"));
    }

    @Test
    public void aNormalMessageIsUnchanged() {
        Assert.assertEquals(
                "hello", ExportBackupWorker.backupColumnValue(Message.BODY, "hello", false));
        Assert.assertEquals("0", ExportBackupWorker.backupColumnValue(Message.DELETED, "0", false));
        Assert.assertEquals(
                "/data/files/a.jpg",
                ExportBackupWorker.backupColumnValue(
                        Message.RELATIVE_FILE_PATH, "/data/files/a.jpg", false));
    }
}
