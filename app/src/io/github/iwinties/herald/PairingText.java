package io.github.iwinties.herald;

import java.util.Locale;

/** Fixed screen-pixel slots, independent of density, font scale and the client name. */
final class PairingText {
    static String groups(String code) {
        return code.substring(0, 3) + " " + code.substring(3);
    }

    static String remaining(int seconds) {
        return String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60);
    }

    // Integer thousandths keep half-pixel ties exact.
    static int px(int dimension, int thousandths) {
        return (int) (((long) dimension * thousandths + 500) / 1000);
    }

    static final class Layout {
        final int[] window; // left, top, exclusive right, exclusive bottom (CardFrame)
        final int[] body;   // left, top, width, height in screen pixels
        final int[][] boxes = new int[6][4]; // same representation as body
        final int badgeY, titleY, subtitleY, progressY, footerY; // relative to body

        Layout(int sw, int sh) {
            int width = px(sw, 340), height = px(sh, 352);
            int[] insets = CardFrame.insets(px(sh, 16), px(sh, 4), 0);
            int[][] origins = new int[1][2];
            window = CardFrame.place("center", sw, sh, new int[][]{{width, height}},
                    new boolean[]{false}, 0, new int[][]{insets}, origins)[0];
            body = new int[]{origins[0][0], origins[0][1], width, height};
            badgeY = px(sh, 30);
            titleY = badgeY + px(sh, 40) + px(sh, 16);
            subtitleY = titleY + px(sh, 45) + px(sh, 8);
            int rowY = subtitleY + px(sh, 30) + px(sh, 24);
            int boxW = px(sh, 45), boxH = px(sh, 60);
            int gap = px(sh, 8), groupGap = px(sh, 20);
            int rowW = 6 * boxW + 4 * gap + groupGap;
            int x = body[0] + Math.floorDiv(width - rowW + 1, 2);
            for (int i = 0; i < boxes.length; i++) {
                boxes[i] = new int[]{x, body[1] + rowY, boxW, boxH};
                x += boxW + (i == 2 ? groupGap : gap);
            }
            progressY = rowY + boxH + px(sh, 24);
            footerY = progressY + px(sh, 4) + px(sh, 16);
        }
    }

    static Layout layout(int sw, int sh) { return new Layout(sw, sh); }
}
