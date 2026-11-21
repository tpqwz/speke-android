package eu.siacs.conversations.http;

import org.junit.Assert;
import org.junit.Test;

/** PQ OMEMO2 files are uploaded under a name that tells the server nothing. */
public class OpaqueUploadNameTest {

    @Test
    public void randomHexWithoutExtension() {
        final String a = HttpUploadConnection.opaqueUploadName();
        final String b = HttpUploadConnection.opaqueUploadName();
        Assert.assertTrue(a, a.matches("[0-9a-f]{32}"));
        Assert.assertFalse(a.contains("."));
        Assert.assertNotEquals(a, b);
    }
}
