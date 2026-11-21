package eu.siacs.conversations.worker;

import android.database.Cursor;
import org.junit.Assert;
import org.junit.Test;

/** One unusual value must never fail a whole backup. */
public class BackupExportRobustnessTest {

    @Test
    public void binaryColumnsAreLeftOutInsteadOfFailingTheBackup() {
        Assert.assertNull(
                ExportBackupWorker.exportableColumnValue(
                        Cursor.FIELD_TYPE_BLOB,
                        () -> {
                            throw new IllegalStateException("getString on a BLOB");
                        }));
    }

    @Test
    public void otherColumnsAreKept() {
        Assert.assertEquals(
                "hello",
                ExportBackupWorker.exportableColumnValue(Cursor.FIELD_TYPE_STRING, () -> "hello"));
        Assert.assertEquals(
                "42", ExportBackupWorker.exportableColumnValue(Cursor.FIELD_TYPE_INTEGER, () -> "42"));
        Assert.assertNull(
                ExportBackupWorker.exportableColumnValue(Cursor.FIELD_TYPE_NULL, () -> null));
    }
}
