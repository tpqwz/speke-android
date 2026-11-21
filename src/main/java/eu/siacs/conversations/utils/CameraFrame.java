package eu.siacs.conversations.utils;

/** Frame maths of the in-app camera (pure, testable). */
public final class CameraFrame {

    private CameraFrame() {}

    /**
     * The centred crop of a {@code width}×{@code height} image to the aspect ratio {@code
     * longToShort} (long side divided by short side, ≥ 1), keeping the image's own orientation:
     * a landscape image stays landscape, a portrait one portrait.
     *
     * @return {left, top, width, height}
     */
    public static int[] centerCropRect(final int width, final int height, final double longToShort) {
        if (width <= 0 || height <= 0 || longToShort < 1) {
            return new int[] {0, 0, Math.max(0, width), Math.max(0, height)};
        }
        final boolean landscape = width >= height;
        final int longSide = landscape ? width : height;
        final int shortSide = landscape ? height : width;
        int newLong = longSide;
        int newShort = shortSide;
        if ((double) longSide / shortSide > longToShort) {
            newLong = (int) Math.round(shortSide * longToShort); // too long: trim the long side
        } else {
            newShort = (int) Math.round(longSide / longToShort); // too wide: trim the short side
        }
        newLong = Math.min(Math.max(1, newLong), longSide);
        newShort = Math.min(Math.max(1, newShort), shortSide);
        final int cropWidth = landscape ? newLong : newShort;
        final int cropHeight = landscape ? newShort : newLong;
        return new int[] {(width - cropWidth) / 2, (height - cropHeight) / 2, cropWidth, cropHeight};
    }
}
