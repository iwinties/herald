package co.tbrkr.herald;

import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.analytics.AnalyticsListener;
import androidx.media3.exoplayer.rtsp.RtspMediaSource;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.exoplayer.source.MediaSource;

final class VideoCard implements MediaState.Output {
    private final CardView view;
    private final CardBoard cards;
    private final MediaState state;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable timeout;
    private ExoPlayer player;

    VideoCard(CardView view, CardBoard cards, Backend backend) {
        this.view = view;
        this.cards = cards;
        state = new MediaState(view.spec, backend, this);
        long generation = state.generation;
        timeout = () -> state.timeout(generation);
    }

    void start() {
        CardSpec spec = view.spec;
        long generation = state.generation;
        // Player errors (including nested causes) can contain the full authenticated URI.
        androidx.media3.common.util.Log.setLogLevel(androidx.media3.common.util.Log.LOG_LEVEL_OFF);
        try {
            Uri uri = Uri.parse((String) spec.video.get("url")).normalizeScheme();
            MediaSource.Factory factory = "rtsp".equals(uri.getScheme())
                    ? new RtspMediaSource.Factory().setForceUseRtpTcp((boolean) spec.video.get("tcp"))
                    : new DefaultMediaSourceFactory(view.getContext());
            player = new ExoPlayer.Builder(view.getContext())
                    .setLoadControl(new DefaultLoadControl.Builder().setBufferDurationsMs(500, 2000, 250, 500).build())
                    .build();
            player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, MediaState.muted(spec)).build());
            player.setVideoTextureView(view.videoTexture);
            player.addListener(new Player.Listener() {
                @Override public void onRenderedFirstFrame() {
                    if (state.owns(generation)) state.firstFrame(generation);
                }
                @Override public void onPlayerError(PlaybackException ignored) {
                    if (state.owns(generation)) state.failed(generation);
                }
                @Override public void onPlaybackStateChanged(int playbackState) {
                    if (state.owns(generation) && playbackState == Player.STATE_ENDED) state.failed(generation);
                }
            });
            player.addAnalyticsListener(new AnalyticsListener() {
                @Override public void onAudioPositionAdvancing(EventTime time, long startMs) {
                    state.audioAdvancing(generation);
                }
            });
            player.setMediaSource(factory.createMediaSource(MediaItem.fromUri(uri)));
            main.postDelayed(timeout, (int) spec.video.get("timeout") * 1000L);
            player.prepare();
            player.play();
        } catch (RuntimeException unavailable) { state.failed(generation); }
    }

    void close() { state.release(); }

    @Override public void releasePlayer() {
        main.removeCallbacks(timeout);
        if (player != null) {
            player.release();
            player = null;
        }
    }

    @Override public void show(boolean playing, String caption) {
        view.showVideo(playing, caption);
    }

    @Override public void receipt(String status, boolean afterFirstFrame) {
        long ms = cards.videoMedia(view.spec, status, afterFirstFrame);
        if (ms >= 0) Log.i("Herald", "video " + view.spec.id + " " + (afterFirstFrame ? "ended" : status) + " " + ms);
    }
}
