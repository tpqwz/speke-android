package eu.siacs.conversations.worker;

import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.GregorianCalendar;
import java.util.List;
import org.junit.Assert;
import org.junit.Test;

/** Plain-text exports and .ceb backups are matched exactly, and only old complete ones pruned. */
public class PlainTextExportTest {

    private static final String KIM = "kim@example.org";

    @Test
    public void folderNameCarriesAccountAndSeconds() {
        final var date = new GregorianCalendar(2026, Calendar.NOVEMBER, 20, 14, 30, 5).getTime();
        Assert.assertEquals(
                "kim@example.org.2026-11-20-14-30-05.plaintext",
                PlainTextExport.folderName(KIM, date));
    }

    @Test
    public void completeAndPartialFoldersAreTellApart() {
        Assert.assertTrue(
                PlainTextExport.isCompleteFolder("kim@example.org.2026-11-20-14-30-05.plaintext", KIM));
        Assert.assertFalse(
                PlainTextExport.isPartialFolder("kim@example.org.2026-11-20-14-30-05.plaintext", KIM));
        Assert.assertTrue(
                PlainTextExport.isPartialFolder(
                        "kim@example.org.2026-11-20-14-30-05.plaintext.partial", KIM));
        Assert.assertFalse(
                PlainTextExport.isCompleteFolder(
                        "kim@example.org.2026-11-20-14-30-05.plaintext.partial", KIM));
    }

    @Test
    public void anotherAccountWithTheSamePrefixNeverMatches() {
        for (final String name :
                new String[] {
                    "kim@example.org.uk.2026-11-20-14-30-05.plaintext",
                    "kim@example.org.uk.2026-11-20-14-30-05.plaintext.partial",
                    "xkim@example.org.2026-11-20-14-30-05.plaintext",
                    "kim@example.org",
                    "kim@example.org.2026-11-20-14-30.ceb",
                    "kim@example.org.2026-11-20.plaintext",
                    "kim@exampleXorg.2026-11-20-14-30-05.plaintext"
                }) {
            Assert.assertFalse(name, PlainTextExport.isCompleteFolder(name, KIM));
            Assert.assertFalse(name, PlainTextExport.isPartialFolder(name, KIM));
        }
    }

    @Test
    public void onlyTheOldestCompleteExportsArePruned() {
        final List<String> names =
                Arrays.asList(
                        "kim@example.org.2026-11-20-10-00-00.plaintext",
                        "kim@example.org.2026-11-18-10-00-00.plaintext",
                        "kim@example.org.2026-11-21-10-00-00.plaintext",
                        "kim@example.org.2026-11-19-10-00-00.plaintext",
                        "kim@example.org.2026-11-17-10-00-00.plaintext.partial",
                        "kim@example.org.uk.2026-11-01-10-00-00.plaintext",
                        "kim@example.org.2026-11-01-10-00.ceb",
                        "kim@example.org");
        Assert.assertEquals(
                Collections.singletonList("kim@example.org.2026-11-18-10-00-00.plaintext"),
                PlainTextExport.foldersToPrune(names, KIM, 3));
    }

    @Test
    public void nothingIsPrunedWithThreeOrFewer() {
        Assert.assertTrue(
                PlainTextExport.foldersToPrune(
                                Arrays.asList(
                                        "kim@example.org.2026-11-20-10-00-00.plaintext",
                                        "kim@example.org.2026-11-21-10-00-00.plaintext",
                                        "kim@example.org.2026-11-22-10-00-00.plaintext"),
                                KIM,
                                3)
                        .isEmpty());
    }

    @Test
    public void cebCleanupMatchesOnlyTheOwnAccount() {
        Assert.assertTrue(
                ExportBackupWorker.isBackupFileOf("kim@example.org.2026-11-20-14-30.ceb", KIM));
        Assert.assertFalse(
                ExportBackupWorker.isBackupFileOf("kim@example.org.uk.2026-11-20-14-30.ceb", KIM));
        Assert.assertFalse(
                ExportBackupWorker.isBackupFileOf("kim@example.org.2026-11-20-14-30.ceb.txt", KIM));
        Assert.assertFalse(ExportBackupWorker.isBackupFileOf("other.ceb", KIM));
        Assert.assertFalse(ExportBackupWorker.isBackupFileOf(null, KIM));
    }
    @Test
    public void ordinaryAddressesKeepTheirName() {
        Assert.assertEquals("alex@example.org", PlainTextExport.safeFileName("alex@example.org"));
        Assert.assertEquals(
                "room+chat@conference.example.org",
                PlainTextExport.safeFileName("room+chat@conference.example.org"));
        Assert.assertEquals("jürgen@example.org", PlainTextExport.safeFileName("jürgen@example.org"));
    }

    @Test
    public void unsafeCharactersAreReplacedAndHashed() {
        final String name = PlainTextExport.safeFileName("a*b?c|d\\e:f\"g<h>i\u0001@example.org");
        Assert.assertTrue(name, name.matches("[\\p{L}\\p{N}@._+-]+-[0-9a-f]{8}"));
        Assert.assertTrue(name.startsWith("a_b_c_d_e_f_g_h_i_@example.org-"));
        Assert.assertNotEquals(
                PlainTextExport.safeFileName("a*b@example.org"),
                PlainTextExport.safeFileName("a?b@example.org"));
    }

    @Test
    public void longAndDotNamesAreMadeSafe() {
        final String name = PlainTextExport.safeFileName("x".repeat(3000) + "@example.org");
        Assert.assertTrue(String.valueOf(name.length()), name.length() <= 159);
        final String wide =
                PlainTextExport.safeFileName(
                        new String(Character.toChars(0x20000)).repeat(3000) + "@example.org");
        Assert.assertTrue(
                wide, wide.getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= 159);
        Assert.assertTrue(wide, wide.matches(".*-[0-9a-f]{8}"));
        for (final String dots : new String[] {"..", ".", ".hidden", ""}) {
            final String safe = PlainTextExport.safeFileName(dots);
            Assert.assertTrue(safe, safe.startsWith("_"));
        }
    }

    @Test
    public void foldersOfASanitizedAccountStillMatch() {
        final String account = "odd*name@example.org";
        final var date = new GregorianCalendar(2026, Calendar.NOVEMBER, 20, 14, 30, 5).getTime();
        final String folder = PlainTextExport.folderName(account, date);
        Assert.assertFalse(folder.contains("*"));
        Assert.assertTrue(PlainTextExport.isCompleteFolder(folder, account));
        Assert.assertTrue(PlainTextExport.isPartialFolder(folder + ".partial", account));
    }

    @Test
    public void sameNamesInOneRunGetACounter() throws java.io.IOException {
        final java.util.Set<String> used = new java.util.HashSet<>();
        Assert.assertEquals("alex@x.txt", PlainTextExport.uniqueName("alex@x", used, n -> false));
        Assert.assertEquals("ALEX@x-2.txt", PlainTextExport.uniqueName("ALEX@x", used, n -> false));
        Assert.assertEquals("alex@x-3.txt", PlainTextExport.uniqueName("alex@x", used, n -> false));
        Assert.assertEquals(
                "bo@x-2.txt",
                PlainTextExport.uniqueName("bo@x", new java.util.HashSet<>(), "bo@x.txt"::equals));
    }
}
