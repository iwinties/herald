package co.tbrkr.herald;

import java.util.Map;

/** Android operations used by the pure API and pairing state machine. */
interface Backend {
    String certSha256();
    String pinSha256();
    int tlsFailures();
    Map<String, Object> statusFields();
    boolean soundPlaying();
    void ownVideoAudio(long generation, boolean playing);
    void playCardSound(String sound, int volume);
    void testChime(int volume);
    boolean screenOn();
    boolean overlayAllowed();
    boolean pairingAllowed();
    boolean showPairingCode(String client, String code, int seconds);
    void hidePairingCode();
    void showPaired(String client);
    void persistAuth(String json);
    long clipSize(String key);                                         // -1 when not cached
    boolean putClip(String key, byte[] body);                          // false when storing failed
    Map<String,Object> announce(AnnounceSpec spec);
    Object admissionLock();
    Object checkSpeech(AnnounceSpec spec, boolean journalFull);
    void queueSpeech(Object checked, String job, CardSpec owned, long generation);
    void stop();
}
