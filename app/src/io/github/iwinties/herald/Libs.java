package io.github.iwinties.herald;

import androidx.media3.common.MediaLibraryInfo;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.dash.DashMediaSource;
import androidx.media3.exoplayer.hls.HlsMediaSource;
import androidx.media3.exoplayer.rtsp.RtspMediaSource;
import org.eclipse.paho.client.mqttv3.MqttClient;

final class Libs {
    static String media3() {
        return MediaLibraryInfo.VERSION;
    }

    static final Class<?>[] REQUIRED = {
        ExoPlayer.class, RtspMediaSource.Factory.class, HlsMediaSource.Factory.class,
        DashMediaSource.Factory.class, MqttClient.class
    };

    private Libs() {}
}
