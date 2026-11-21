package eu.siacs.conversations.utils;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class ApngConverterRegionTest {

    @Test
    public void identicalFramesHaveNoRegion() {
        assertNull(ApngConverter.changedRegion(new int[16], new int[16], 4, 4));
    }

    @Test
    public void regionIsTheBoundingBoxWithEvenOrigin() {
        final int[] before = new int[6 * 5];
        final int[] after = new int[6 * 5];
        after[1 * 6 + 3] = 0xff00ff00; // (3,1)
        after[3 * 6 + 4] = 0x80ff0000; // (4,3)
        // left 3 -> 2, top 1 -> 0; right/bottom exclusive
        assertArrayEquals(new int[] {2, 0, 5, 4}, ApngConverter.changedRegion(before, after, 6, 5));
    }

    @Test
    public void transparencyChangesCount() {
        final int[] before = new int[4];
        final int[] after = new int[4];
        before[3] = 0x01000000;
        assertArrayEquals(new int[] {0, 0, 2, 2}, ApngConverter.changedRegion(before, after, 2, 2));
    }
}
