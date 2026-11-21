package eu.siacs.conversations.ui.util;

/** Layout arithmetic for the XEP-0272 Muji group-call participant grid. */
public final class CallGrid {

    private CallGrid() {}

    /**
     * The column count for {@code count} tiles in a {@code width}×{@code height} grid: the one
     * whose cells are closest to square (participant video can be portrait or landscape, and is
     * scaled to fit), preferring fewer empty cells on a tie. A phone in portrait therefore stacks
     * 2–3 participants and switches to two columns from 4 on; landscape goes side by side.
     */
    public static int columns(final int count, final int width, final int height) {
        if (count <= 1 || width <= 0 || height <= 0) {
            return 1;
        }
        int best = 1;
        double bestScore = -1;
        int bestEmpty = Integer.MAX_VALUE;
        for (int columns = 1; columns <= count; columns++) {
            final int rows = (count + columns - 1) / columns;
            final double cellW = (double) width / columns;
            final double cellH = (double) height / rows;
            final double squareness = Math.min(cellW, cellH) / Math.max(cellW, cellH);
            final int empty = rows * columns - count;
            if (squareness > bestScore + 1e-9
                    || (Math.abs(squareness - bestScore) <= 1e-9 && empty < bestEmpty)) {
                best = columns;
                bestScore = squareness;
                bestEmpty = empty;
            }
        }
        return best;
    }
}
