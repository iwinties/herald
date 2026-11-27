package io.github.iwinties.herald;

import java.util.concurrent.atomic.AtomicLong;

/** One main-looper playback attempt. Invalidate its token before releasing the decoder. */
final class MediaState {
    interface Output {
        void releasePlayer();
        void show(boolean playing, String caption);
        void receipt(String state, boolean afterFirstFrame);
    }

    private static final AtomicLong generations = new AtomicLong();
    final long generation = generations.incrementAndGet();
    private final Backend backend;
    private final Output output;
    private final boolean muted;
    private boolean live = true, rendered, audible;

    MediaState(CardSpec spec, Backend backend, Output output) {
        this.backend = backend;
        this.output = output;
        muted = muted(spec);
    }

    static boolean muted(CardSpec spec) {
        return (boolean) spec.video.getOrDefault("muted", !"on".equals(spec.style.fields.get("video_sound")));
    }

    boolean owns(long token) { return live && token == generation; }

    void firstFrame(long token) {
        if (!owns(token) || rendered) return;
        rendered = true;
        output.show(true, "");
        output.receipt("playing", false);
    }

    void audioAdvancing(long token) {
        if (owns(token) && !muted && !audible) {
            audible = true;
            backend.ownVideoAudio(token, true);
        }
    }

    void timeout(long token) {
        if (!rendered) failed(token);
    }

    void failed(long token) {
        if (!owns(token)) return;
        release();
        output.show(false, rendered ? "Live view ended" : "Live view unavailable");
        output.receipt("failed", rendered);
    }

    void release() {
        if (!live) return;
        live = false;
        if (audible) backend.ownVideoAudio(generation, false);
        output.releasePlayer();
    }
}
