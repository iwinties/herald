package io.github.iwinties.herald;

/** Android KeyEvent values, kept platform-independent for the JVM policy tests. */
final class KeyPolicy {
    static boolean isCardKey(int keyCode) {
        switch (keyCode) {
            case 4:   // BACK
            case 19:  // DPAD_UP
            case 20:  // DPAD_DOWN
            case 21:  // DPAD_LEFT
            case 22:  // DPAD_RIGHT
            case 23:  // DPAD_CENTER
            case 66:  // ENTER
            case 160: // NUMPAD_ENTER
                return true;
            default:
                return false;
        }
    }
}
