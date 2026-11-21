package eu.siacs.conversations.entities;

import org.junit.Assert;
import org.junit.Test;

/** Story media only over https; contacts' stories load after a tap. */
public class StoryUrlTest {

    @Test
    public void onlyHttps() {
        Assert.assertTrue(Story.isAllowedUrl("https://upload.example.org/a.jpg"));
        Assert.assertFalse(Story.isAllowedUrl("http://upload.example.org/a.jpg"));
        Assert.assertFalse(Story.isAllowedUrl("file:///data/data/x"));
        Assert.assertFalse(Story.isAllowedUrl("content://x/y"));
        Assert.assertFalse(Story.isAllowedUrl(null));
    }
}
