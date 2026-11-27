package co.tbrkr.herald;

/** All rectangles are in display pixels, with exclusive right and bottom edges. */
final class CardFrame {
    static int[] insets(int spread, int offset, int glow) {
        int side = Math.max(spread, glow);
        return new int[]{side, Math.max(spread - offset, glow), side, Math.max(spread + offset, glow)};
    }

    static int[][] place(String placement, int screenW, int screenH, int[][] contentSizes,
            boolean[] fullWidth, int gap, int[][] insets) {
        return place(placement, screenW, screenH, contentSizes, fullWidth, gap, insets,
                new int[contentSizes.length][2]);
    }

    // The renderer also needs the original content origin to clip the layers without shifting them.
    static int[][] place(String placement, int screenW, int screenH, int[][] contentSizes,
            boolean[] fullWidth, int gap, int[][] insets, int[][] origins) {
        int count = contentSizes.length;
        int total = Math.max(0, count - 1) * gap;
        for (int[] size : contentSizes) total += size[1];
        int marginY = Math.round(.03f * screenH);
        boolean bottom = placement.startsWith("bottom");
        int cursor = placement.startsWith("top") ? marginY : bottom ? screenH - marginY
                : Math.round((screenH - total) / 2f);
        int[][] windows = new int[count][4];
        for (int i = 0; i < count; i++) {
            int w = fullWidth[i] ? screenW : contentSizes[i][0];
            int h = contentSizes[i][1];
            int marginX = w == screenW ? 0 : Math.round(.03f * screenW);
            int x = placement.endsWith("left") ? marginX : placement.endsWith("right")
                    ? screenW - marginX - w : Math.round((screenW - w) / 2f);
            int y = bottom ? cursor - h : cursor;
            if (h == screenH) y = 0;
            origins[i][0] = x;
            origins[i][1] = y;
            int[] edge = insets[i];
            int[] rect = windows[i];
            rect[0] = clamp(x - edge[0], screenW);
            rect[1] = clamp(y - edge[1], screenH);
            rect[2] = clamp(x + w + edge[2], screenW);
            rect[3] = clamp(y + h + edge[3], screenH);
            cursor = bottom ? y - gap : y + h + gap;
        }
        return windows;
    }

    private static int clamp(int value, int limit) { return Math.max(0, Math.min(value, limit)); }
}
