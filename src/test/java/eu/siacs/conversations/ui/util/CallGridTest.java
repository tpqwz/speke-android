package eu.siacs.conversations.ui.util;

import org.junit.Assert;
import org.junit.Test;

public class CallGridTest {

    private static final int PORTRAIT_W = 1080, PORTRAIT_H = 2000;

    @Test
    public void portraitStacksSmallCallsVertically() {
        Assert.assertEquals(1, CallGrid.columns(1, PORTRAIT_W, PORTRAIT_H));
        Assert.assertEquals(1, CallGrid.columns(2, PORTRAIT_W, PORTRAIT_H));
        Assert.assertEquals(1, CallGrid.columns(3, PORTRAIT_W, PORTRAIT_H));
    }

    @Test
    public void portraitUsesTwoColumnsForLargerCalls() {
        for (int n = 4; n <= 8; n++) {
            Assert.assertEquals("n=" + n, 2, CallGrid.columns(n, PORTRAIT_W, PORTRAIT_H));
        }
    }

    @Test
    public void landscapeGoesSideBySide() {
        Assert.assertEquals(2, CallGrid.columns(2, PORTRAIT_H, PORTRAIT_W));
        Assert.assertEquals(3, CallGrid.columns(3, PORTRAIT_H, PORTRAIT_W));
    }

    @Test
    public void degenerateSizesDoNotThrow() {
        Assert.assertEquals(1, CallGrid.columns(0, 0, 0));
        Assert.assertEquals(1, CallGrid.columns(5, 0, 100));
    }
}
