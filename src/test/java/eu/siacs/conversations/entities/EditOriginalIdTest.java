package eu.siacs.conversations.entities;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Assert;
import org.junit.Test;

/** A correction always names the original message (XEP-0308), whatever came after it. */
public class EditOriginalIdTest {

    @Test
    public void theOriginalIdIsNamed() {
        Assert.assertEquals("origin-1", Edit.originalId(List.of(new Edit("origin-1", "stanza-1"))));
    }

    @Test
    public void laterEditsDoNotChangeIt() {
        final List<Edit> edits =
                Arrays.asList(
                        new Edit("origin-1", "stanza-1"),
                        new Edit("local-uuid", "stanza-1"),
                        new Edit("correction-1", "stanza-2"));
        Assert.assertEquals("origin-1", Edit.originalId(edits));
    }

    @Test
    public void aMissingOriginalIdFallsBackToItsArchiveId() {
        Assert.assertEquals(
                "stanza-1",
                Edit.originalId(
                        Arrays.asList(new Edit(null, "stanza-1"), new Edit("c-1", "stanza-2"))));
        Assert.assertEquals("stanza-1", Edit.originalId(List.of(new Edit("", "stanza-1"))));
    }

    @Test
    public void nothingKnownGivesNull() {
        Assert.assertNull(Edit.originalId(List.of(new Edit(null, null))));
        Assert.assertNull(Edit.originalId(new ArrayList<>()));
    }
}
