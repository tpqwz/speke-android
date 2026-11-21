package eu.siacs.conversations.utils;

import org.junit.Assert;
import org.junit.Test;

public class CameraFrameTest {

    @Test
    public void squareFromLandscapeAndPortrait() {
        Assert.assertArrayEquals(
                new int[] {500, 0, 3000, 3000}, CameraFrame.centerCropRect(4000, 3000, 1.0));
        Assert.assertArrayEquals(
                new int[] {0, 500, 3000, 3000}, CameraFrame.centerCropRect(3000, 4000, 1.0));
    }

    @Test
    public void wideRatioFromFourToThree() {
        // 4:3 landscape to 16:9: the short side is trimmed
        Assert.assertArrayEquals(
                new int[] {0, 375, 4000, 2250}, CameraFrame.centerCropRect(4000, 3000, 16.0 / 9));
        // portrait keeps being portrait
        Assert.assertArrayEquals(
                new int[] {375, 0, 2250, 4000}, CameraFrame.centerCropRect(3000, 4000, 16.0 / 9));
    }

    @Test
    public void tallScreenRatioFromSixteenToNine() {
        // 16:9 portrait (1080x1920) to a 20:9 screen: the short side is trimmed
        final int[] crop = CameraFrame.centerCropRect(1080, 1920, 20.0 / 9);
        Assert.assertEquals(1920, crop[3]);
        Assert.assertEquals(864, crop[2]);
        Assert.assertEquals((1080 - 864) / 2, crop[0]);
    }

    @Test
    public void matchingRatioIsUnchanged() {
        Assert.assertArrayEquals(
                new int[] {0, 0, 4000, 3000}, CameraFrame.centerCropRect(4000, 3000, 4.0 / 3));
    }

    @Test
    public void invalidInputIsHarmless() {
        Assert.assertArrayEquals(new int[] {0, 0, 0, 0}, CameraFrame.centerCropRect(0, 0, 1.0));
        Assert.assertArrayEquals(
                new int[] {0, 0, 10, 20}, CameraFrame.centerCropRect(10, 20, 0.5));
    }
}
