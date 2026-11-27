package io.github.iwinties.herald;

import java.util.HashSet;
import java.util.Set;

/** Advisory sound state. The service serializes access and supplies elapsed-realtime milliseconds. */
final class SoundDecision {
    private final Set<Long> audiblePlayers = new HashSet<>();
    private boolean jobPlaying;
    private long ignoreUntil;
    private long lastTrue;
    private boolean playing;
    private int opposingSamples;

    SoundDecision(long start) {
        lastTrue = start;
    }

    void ownJob(boolean active, long now) {
        if (jobPlaying == active) return;
        jobPlaying = active;
        if (!active && audiblePlayers.isEmpty()) ignoreUntil = now + 1000;
    }

    void ownVideoAudio(long generation, boolean active, long now) {
        if (active) {
            audiblePlayers.add(generation);
        } else if (audiblePlayers.remove(generation) && audiblePlayers.isEmpty() && !jobPlaying) {
            ignoreUntil = now + 1000;
        }
    }

    /** Returns true only when this eligible sample changes the reported state. */
    boolean sample(boolean active, long now) {
        if (jobPlaying || !audiblePlayers.isEmpty() || now < ignoreUntil) return false;
        if (active) lastTrue = now;
        if (active == playing) {
            opposingSamples = 0;
            return false;
        }
        if (++opposingSamples < (active ? 2 : 3)) return false;
        playing = active;
        opposingSamples = 0;
        return true;
    }

    boolean playing() { return playing; }

    long quietSeconds(long now) { return (now - lastTrue) / 1000; }
}
