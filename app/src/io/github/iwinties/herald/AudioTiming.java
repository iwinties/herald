package io.github.iwinties.herald;

final class AudioTiming {
    private AudioTiming() {}

    static long remainingMs(long startMs, int frames, int rate, long pauseMs, long nowMs) {
        long durationMs = (frames * 1000L + rate - 1) / rate;
        return Math.max(0L, startMs + durationMs + pauseMs - nowMs);
    }
}
