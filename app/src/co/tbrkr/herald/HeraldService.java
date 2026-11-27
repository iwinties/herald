package co.tbrkr.herald;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.media.MediaPlayer;
import android.media.VolumeShaper;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.util.Log;
import android.content.BroadcastReceiver;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import java.security.SecureRandom;
import java.util.UUID;
import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;
import java.util.concurrent.TimeUnit;

/**
 * Herald: a foreground service that listens on TCP port 7979 (Herald HTTPS API 2),
 * keeps the speech clips Home Assistant pushes to it (ClipStore) and plays them, turns text
 * into speech with the on-device engine, or plays a recording sent with the request (a voice
 * note), all the same way. Jobs run one after another: chime, 200 ms, then the voice with a
 * 150 ms fade-in, on the output the request asked for. Only duck-enabled jobs request transient audio focus. Card image URLs are fetched on image workers. On a sleeping box
 * the system pauses every player right after it starts; ours ignore that pause (ClipPlayer,
 * ChimeTrack) and the service ends playback only with release(). With the TV off it first
 * unmutes the speaker (wakeSpeaker). PLAN-20261125-herald-v1.
 */
public class HeraldService extends Service implements Backend {
    static final String TAG = "Herald";
    static final String VERSION = "0.4.0";
    /** Name prefix of a voice note's temporary file in getCacheDir(). */
    static final String VOICE_PREFIX = "voice-";
    static final int RATE = 22050;
    static final int CLIP_CAP = 200;
    static final int READ_TIMEOUT_MS = 10000;
    static final int MAX_CONNECTIONS = 16;
    static final long PLAY_TIMEOUT_MS = 60000;
    static final long SYNTH_TIMEOUT_MS = 30000;
    static final long WAKE_MS = 90000;
    static final long PAUSE_MS = 200;
    /** A speech engine that has not finished starting for this long may be replaced. */
    static final long ENGINE_START_MS = 10000;

    private static final AudioAttributes VOICE = new AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build();
    private static final AudioAttributes CHIME = new AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build();
    private static final VolumeShaper.Configuration FADE_IN = new VolumeShaper.Configuration.Builder()
            .setDuration(150)
            .setCurve(new float[] {0f, 1f}, new float[] {0f, 1f})
            .setInterpolatorType(VolumeShaper.Configuration.INTERPOLATOR_TYPE_LINEAR)
            .build();

    /**
     * A MediaPlayer that ignores pause(). A sleeping box pauses every player a few milliseconds
     * after it starts (the Herald never pauses its own players), so without this nothing is
     * heard with the TV off.
     */
    static final class ClipPlayer extends MediaPlayer {
        @Override public void pause() {
            Log.i(TAG, "ignored a pause of the clip player");
        }
    }

    /** A static AudioTrack that ignores pause(), for the same reason as ClipPlayer. */
    static final class ChimeTrack extends AudioTrack {
        ChimeTrack(AudioAttributes attributes, AudioFormat format, int bufferSizeInBytes) {
            super(attributes, format, bufferSizeInBytes, AudioTrack.MODE_STATIC, AudioManager.AUDIO_SESSION_ID_GENERATE);
        }

        @Override public void pause() {
            Log.i(TAG, "ignored a pause of the chime");
        }
    }

    /**
     * One announcement: a cached clip (key), text made into a temporary WAV (speech), or a
     * voice note in a temporary file (recording).
     */
    private static final class Job {
        final AnnounceSpec spec;
        final String id, output;
        final SpokenCards.JobCard spoken;
        Long voiceAt;
        final long accepted = SystemClock.elapsedRealtime();
        long chimeMs, voiceMs, playbackDeadline;
        final File speech, recording;
        final int level;
        final float volume;
        final AudioDeviceInfo device;
        final CountDownLatch synthesized = new CountDownLatch(1);
        final CountDownLatch end = new CountDownLatch(1);
        final CountDownLatch finished = new CountDownLatch(1);
        volatile CountDownLatch clipEnd;
        volatile String error;
        volatile boolean cancelled;
        FutureTask<byte[]> fetch;
        boolean reported;

        Job(AnnounceSpec spec, String id, String output, AudioDeviceInfo device, int level,
                File speechDir, File cacheDir, SpokenCards.JobCard spoken) {
            this.spoken = spoken;
            this.spec = spec;
            this.id = id;
            this.output = output;
            this.device = device;
            this.level = level;
            volume = level / 100f;
            speech = spec.text == null ? null : new File(speechDir, id + ".wav");
            recording = spec.audio == null && spec.url == null ? null : new File(cacheDir, VOICE_PREFIX + id);
        }

        long elapsed() { return SystemClock.elapsedRealtime() - accepted; }
    }

    private final AudioQueue<Job> queue = new AudioQueue<>();
    private final Object enqueue = new Object();
    private final Semaphore fetchSlot = new Semaphore(1);
    private RequestIds requestIds;
    private final Map<String, Job> synthesizing = new ConcurrentHashMap<String, Job>();
    private final Semaphore connections = new Semaphore(MAX_CONNECTIONS);
    private final Object engineLock = new Object();
    private TextToSpeech tts;          // guarded by engineLock
    private int engineGeneration;      // guarded by engineLock
    private long engineStarted;        // guarded by engineLock
    private boolean enginePending;     // guarded by engineLock
    private volatile boolean ttsReady;
    private volatile String last = "none";
    private volatile boolean running;
    private final Object listenerLock = new Object();
    private ServerSocket server, compatServer;
    private Compat compat;
    private int compatPort; // zero while disabled; guarded by listenerLock
    private long compatRevision;
    private volatile String compatStatus = "off";
    private volatile int listenerPort;
    private volatile TlsIdentity tlsIdentity;
    private volatile boolean tlsUnavailable;
    private final AtomicInteger tlsFailures = new AtomicInteger();
    private int listenerRevision;
    private Settings settings;
    private ClipStore clips;
    private File speechDir;
    private short[] chimePcm;
    private PowerManager power;
    private PowerManager.WakeLock wake;
    private AudioManager audio;
    private final SoundDecision sound = new SoundDecision(SystemClock.elapsedRealtime());
    private HandlerThread soundThread;
    private Handler soundHandler;
    private Handler main;
    private Thread worker;

    private static volatile HeraldService instance;
    private SharedPreferences prefs;
    private PairingWindow pairingWindow;
    private Overlay overlay;
    private Auth auth;
    private Discovery discovery;
    private volatile String advertisedName;
    private MqttLink mqtt;
    private NtfyLink ntfy;

    String ntfyStatus() { return ntfy.status(); }
    private ChipBoard chips;
    private String deviceId;
    private Api api;
    private EventLog events;

    static HeraldService instance() { return instance; }
    static final String STOP = "co.tbrkr.herald.action.STOP";
    // Requests, service lifecycle and Setup run on this process's main thread.
    static boolean startPending, startRefused;
    private boolean screenRegistered;

    static boolean receiving(Context c) {
        SharedPreferences p = c.getSharedPreferences("herald", MODE_PRIVATE);
        Boolean stored = p.contains("receiving") ? p.getBoolean("receiving", false) : null;
        boolean on = Setup.resolveReceiving(stored, p.contains("device_id"), p.contains("auth"), p.contains("settings"));
        if (stored == null) p.edit().putBoolean("receiving", on).commit();
        return on;
    }

    static boolean tryStart(Context c) {
        try {
            c.startForegroundService(new Intent(c, HeraldService.class));
            startPending = instance == null;
            startRefused = false;
            return true;
        } catch (IllegalStateException e) {
            Log.w(TAG, "start refused", e);
            startPending = false;
            startRefused = true;
            return false;
        }
    }

    static boolean requestStart(Context c, boolean before) {
        SharedPreferences p = c.getSharedPreferences("herald", MODE_PRIVATE);
        p.edit().putBoolean("receiving", true).commit();
        if (tryStart(c)) return true;
        p.edit().putBoolean("receiving", Setup.afterStart(before, true)).commit();
        return false;
    }

    Auth auth() { return auth; }
    Settings settings() { return settings; }
    EventLog events() { return events; }
    String deviceId() { return deviceId; }
    @Override public String certSha256() {
        TlsIdentity identity = tlsIdentity;
        return identity == null ? "" : identity.fingerprint();
    }
    @Override public String pinSha256() {
        TlsIdentity identity = tlsIdentity;
        return identity == null ? "" : identity.pin();
    }
    @Override public int tlsFailures() { return tlsFailures.get(); }
    String assignedName() { return discovery.assignedName(); }

    void changeSettings(Map<String,Object> patch) {
        synchronized (settings) {
            int before = settings.port();
            settings.put(patch);
            if (before != settings.port()) rebind(settings.revision(), settings.port());
        }
    }

    Map<String,Object> command(String name, byte[] payload) {
        return MqttTopics.run(name, payload, this, settings, overlay.cards, chips, requestIds);
    }

    String mqttState() {
        Map<String,Object> state = overlay.cards.status();
        state.put("screen_on", screenOn());
        state.put("overlay_allowed", overlayAllowed());
        state.put("sound_playing", soundPlaying());
        return Json.stringify(state);
    }

    long quietSeconds() {
        synchronized (sound) { return sound.quietSeconds(SystemClock.elapsedRealtime()); }
    }

    private void refreshDiscovery() {
        synchronized (listenerLock) {
            int bound = server == null || server.isClosed() ? 0 : server.getLocalPort();
            discovery.advertise(advertisedName, bound);
        }
    }

    private final BroadcastReceiver screenReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            auth.expire();
            boolean on = Intent.ACTION_SCREEN_ON.equals(intent.getAction());
            events.add("screen", Json.fields("on", on));
            overlay.cards.screen(on);
        }
    };

    private final UtteranceProgressListener progress = new UtteranceProgressListener() {
        @Override public void onStart(String id) {}
        @Override public void onDone(String id) { synthesized(id, null); }
        @Override public void onError(String id) { synthesized(id, "failed"); }
        @Override public void onError(String id, int code) { synthesized(id, "code " + code); }
        @Override public void onStop(String id, boolean interrupted) { synthesized(id, "stopped"); }
    };

    @Override public IBinder onBind(Intent i) { return null; }

    @Override public void onCreate() {
        super.onCreate();
        prefs = getSharedPreferences("herald", MODE_PRIVATE);
        startPending = false;
        try {
            goForeground();
        } catch (RuntimeException e) {
            Log.e(TAG, "could not start in the foreground", e);
            prefs.edit().putBoolean("start_error", true).commit();
            stopSelf();
            return;
        }
        boolean on = receiving(this);
        prefs.edit().remove("start_error").commit();
        if (!on) {
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
            return;
        }
        main = new Handler(Looper.getMainLooper());
        power = (PowerManager) getSystemService(Context.POWER_SERVICE);
        audio = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        wake = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "herald:job");
        wake.setReferenceCounted(false);
        clips = new ClipStore(new File(getCacheDir(), "clips"), CLIP_CAP);
        speechDir = new File(getCacheDir(), "speech");
        File[] leftovers = speechDir.listFiles();
        if (leftovers != null) for (File f : leftovers) f.delete();
        File[] notes = getCacheDir().listFiles((dir, name) -> name.startsWith(VOICE_PREFIX));
        if (notes != null) for (File f : notes) f.delete();
        requestIds = new RequestIds(new File(getFilesDir(), "request-ids.json"), System::currentTimeMillis);
        chimePcm = Chime.pcm(RATE);
        String id = prefs.getString("device_id", null);
        if (id == null) {
            id = UUID.randomUUID().toString();
            if (!prefs.edit().putString("device_id", id).commit()) throw new IllegalStateException("device id write failed");
        }
        deviceId = id;
        discovery = new Discovery(getSystemService(android.net.nsd.NsdManager.class), main, id);
        String name = android.provider.Settings.Global.getString(getContentResolver(), android.provider.Settings.Global.DEVICE_NAME);
        SecureRandom random = new SecureRandom();
        events = new EventLog(System::currentTimeMillis, random);
        auth = Auth.fromJson(prefs.getString("auth", null), this, events, System::currentTimeMillis, random);
        pairingWindow = new PairingWindow(this);
        settings = new Settings(name == null || name.isEmpty() ? Build.MODEL : name, prefs.getString("settings", null),
                json -> {
                    if (!prefs.edit().putString("settings", json).commit()) throw new Api.Failure(500, "settings store failed");
                    Map<String,Object> doc = Json.parse(json);
                    configureCompat(doc);
                    ntfy.configure(Settings.object(doc.get("ntfy"), "ntfy"));
                    mqtt.configure(Settings.object(doc.get("mqtt"), "mqtt"));
                    advertisedName = (String)Settings.object(doc.get("device"), "device").get("name");
                    refreshDiscovery();
                }, events);
        if (settings.discardedStored()) Log.i(TAG, "settings: discarded a stored document with an unsupported schema");
        listenerPort = settings.port();
        advertisedName = settings.name();
        chips = new ChipBoard(new File(getFilesDir(), "chips.json"), System::currentTimeMillis, settings::hasLook);
        ImageStore images = new ImageStore(new File(getCacheDir(), "images"), Overlay::decodes);
        overlay = new Overlay(this, settings, events, chips, images);
        api = new Api(this, auth, events, settings, this::rebind, id, Build.MODEL, VERSION, overlay.cards, chips, images, requestIds);
        IntentFilter filter = new IntentFilter(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        registerReceiver(screenReceiver, filter);
        screenRegistered = true;
        running = true;
        mqtt = new MqttLink(this, prefs, events, id, settings.mqttPrivate());
        ntfy = new NtfyLink(getSharedPreferences("ntfy", MODE_PRIVATE), settings, doc ->
                SpokenCards.post(new CardSpec(doc, settings), true, doc.get("announce"), settings, this, overlay.cards, requestIds));
        compat = new Compat(settings, overlay.cards, chips, System::currentTimeMillis, this, requestIds);
        configureCompat(settings.document());
        new Thread(this::listenCompat, "herald-compat").start();
        instance = this;
        newEngine();
        worker = new Thread(this::work, "herald-worker");
        worker.start();
        soundThread = new HandlerThread("herald-sound");
        soundThread.start();
        soundHandler = new Handler(soundThread.getLooper());
        soundHandler.post(sampleSound);
        ntfy.configure(settings.ntfyPrivate());
        new Thread(this::listen, "herald-http").start();
    }

    @Override public int onStartCommand(Intent i, int flags, int id) {
        if (i != null && STOP.equals(i.getAction()) && !receiving(this)) {
            prefs.edit().putBoolean("receiving", false).commit();
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
            return START_NOT_STICKY;
        }
        return running ? START_STICKY : START_NOT_STICKY;
    }

    @Override public void onDestroy() {
        instance = null;
        running = false;
        if (ntfy != null) ntfy.close();
        if (mqtt != null) mqtt.close();
        if (discovery != null) discovery.close();
        if (soundHandler != null) soundHandler.removeCallbacks(sampleSound);
        if (soundThread != null) soundThread.quitSafely();
        if (screenRegistered) unregisterReceiver(screenReceiver);
        if (auth != null) auth.close();
        if (pairingWindow != null) pairingWindow.close();

        synchronized (listenerLock) {
            close(server);
            close(compatServer);
            listenerLock.notifyAll();
        }
        if (overlay != null) {
            stop();
            overlay.close();
        }
        if (worker != null) worker.interrupt();
        synchronized (engineLock) {
            if (tts != null) tts.shutdown();
            tts = null;
            ttsReady = false;
        }
        if (wake != null && wake.isHeld()) wake.release();
        super.onDestroy();
    }

    private void goForeground() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        nm.createNotificationChannel(new NotificationChannel("svc", "Herald", NotificationManager.IMPORTANCE_MIN));
        Notification n = new Notification.Builder(this, "svc")
                .setContentTitle("Herald")
                .setSmallIcon(android.R.drawable.ic_lock_silent_mode_off)
                .build();
        if (Build.VERSION.SDK_INT >= 34) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        else startForeground(1, n);
    }

    // ---- Backend (called on request threads) ----

    @Override public Map<String, Object> statusFields() {
        List<String> outputs = new ArrayList<>();
        if (device("speaker") != null) outputs.add("speaker");
        if (device("tv") != null) outputs.add("tv");
        String[] speech = speechDir.list();
        String[] notes = getCacheDir().list((dir, name) -> name.startsWith(VOICE_PREFIX));
        Map<String, Object> fields = Json.fields("battery_exempt", power.isIgnoringBatteryOptimizations(getPackageName()),
                "tts_ready", ttsReady, "outputs", outputs, "audio_busy", audioBusy(),
                "last_audio", last, "clips", clips.count(),
                "temp_files", (speech == null ? 0 : speech.length) + (notes == null ? 0 : notes.length));
        synchronized (sound) {
            fields.put("sound_playing", sound.playing());
            fields.put("quiet_seconds", sound.quietSeconds(SystemClock.elapsedRealtime()));
        }
        fields.put("mqtt", mqtt.status());
        fields.put("ntfy", ntfy.status());
        fields.put("compat", compatStatus);
        return fields;
    }

    private final Runnable sampleSound = new Runnable() {
        @Override public void run() {
            if (!running) return;
            synchronized (sound) {
                // Serialize the platform read with ownership changes on the worker and UI threads.
                try {
                    if (sound.sample(audio.isMusicActive(), SystemClock.elapsedRealtime())) {
                        events.add("sound", Json.fields("playing", sound.playing()));
                    }
                } catch (RuntimeException unavailable) {
                    // An unavailable audio service supplies no evidence for either state.
                }
            }
            if (running) soundHandler.postDelayed(this, 2000);
        }
    };

    @Override public boolean soundPlaying() {
        synchronized (sound) { return sound.playing(); }
    }

    @Override public void ownVideoAudio(long generation, boolean playing) {
        synchronized (sound) { sound.ownVideoAudio(generation, playing, SystemClock.elapsedRealtime()); }
    }

    private void ownJobAudio(boolean playing) {
        synchronized (sound) { sound.ownJob(playing, SystemClock.elapsedRealtime()); }
    }

    Map<String,Object> settingsStatus() {
        Map<String,Object> status = statusFields();
        status.putAll(overlay.cards.status());
        status.put("version", VERSION);
        synchronized (listenerLock) {
            status.put("api_status", tlsUnavailable ? "TLS unavailable"
                    : server != null && !server.isClosed() ? "HTTPS" : "Waiting");
        }
        status.put("screen_on", screenOn());
        status.put("overlay_allowed", overlayAllowed());
        return status;
    }

    @Override public void testChime(int volume) {
        announce(AnnounceSpec.cardSound("chime", volume));
    }

    @Override public void playCardSound(String sound, int volume) {
        try { announce(AnnounceSpec.cardSound(sound, volume)); }
        catch (Api.Failure refused) { /* A card remains visible when its optional sound is refused. */ }
    }

    private boolean audioBusy() { synchronized (enqueue) { return queue.busy(); } }
    @Override public boolean screenOn() { return power.isInteractive(); }
    @Override public boolean overlayAllowed() { return android.provider.Settings.canDrawOverlays(this); }
    @Override public boolean pairingAllowed() { return settings.pairingAllowed(); }

    @Override public boolean showPairingCode(String client, String code, int seconds) {
        return pairingWindow.showCode(client, code, advertisedName, seconds, certSha256());
    }

    @Override public void hidePairingCode() { pairingWindow.hide(); }

    @Override public void showPaired(String client) {
        if (overlayAllowed()) pairingWindow.showPaired(client);
    }

    @Override public void persistAuth(String json) {
        if (!prefs.edit().putString("auth", json).commit()) throw new Api.Failure(500, "credential store failed");
    }

    @Override public long clipSize(String key) {
        return clips.size(key);
    }

    @Override public boolean putClip(String key, byte[] body) {
        try {
            clips.put(key, body);
            return true;
        } catch (IOException | RuntimeException e) {
            Log.w(TAG, "clip store failed");
            return false;
        }
    }

    @Override public Object admissionLock() { return enqueue; }

    private final class Admission {
        final AnnounceSpec spec;
        final AudioDeviceInfo device;
        final String output;
        final int level;

        Admission(AnnounceSpec spec) {
            this.spec = spec;
            Map<String,Object> config = AnnounceSpec.audioSettings(settings);
            AudioDeviceInfo tv = device("tv"), speaker = device("speaker");
            output = spec.checkOutput(key -> clips.size(key) >= 0,
                    route -> route.equals("tv") ? tv != null : speaker != null,
                    CardBoard.night(settings.display(), System.currentTimeMillis()), config, () -> {
                        if (ttsReady) return true;
                        renewEngine();
                        return false;
                    });
            device = output.equals("tv") ? tv : speaker;
            level = spec.volume(output, config);
        }
    }

    @Override public Object checkSpeech(AnnounceSpec spec, boolean journalFull) {
        Admission checked = new Admission(spec);
        if (journalFull) throw new Api.Failure(429, "too many request ids");
        if (!running || queue.full()) throw new Api.Failure(429, "busy");
        return checked;
    }

    @Override public void queueSpeech(Object checked, String id, CardSpec owned, long generation) {
        Admission a = (Admission)checked;
        if (a.spec.clip != null) clips.touch(a.spec.clip);
        if (a.spec.chimeKey != null) clips.touch(a.spec.chimeKey);
        queue.add(new Job(a.spec, id, a.output, a.device, a.level, speechDir, getCacheDir(),
                owned == null ? null : new SpokenCards.JobCard(owned, generation)));
        enqueue.notifyAll();
    }

    @Override public Map<String,Object> announce(AnnounceSpec spec) {
        synchronized (enqueue) {
            final Admission[] checked = new Admission[1];
            return requestIds.admit(spec.requestId, () -> checked[0] = new Admission(spec),
                    () -> !running || queue.full(), id -> queueSpeech(checked[0], id, null, -1));
        }
    }

    @Override public void stop() {
        List<Job> stopped;
        synchronized (overlay.cards) {
            synchronized (enqueue) { stopped = cancelJobs(); }
        }
        for (Job j : stopped) {
            endJobCard(j, "stopped");
            await(j.finished, READ_TIMEOUT_MS);
        }
    }

    // Caller holds board then enqueue, serializing cancellation with a display attempt.
    private List<Job> cancelJobs() {
        List<Job> stopped = new ArrayList<>();
        List<Job> pending = queue.stop(j -> {
            j.cancelled = true;
            j.synthesized.countDown();
            j.end.countDown();
            if (j.clipEnd != null) j.clipEnd.countDown();
            if (j.fetch != null) j.fetch.cancel(true);
            if (j.speech != null) synchronized (engineLock) { if (tts != null) tts.stop(); }
            stopped.add(j);
        });
        for (Job j : pending) {
            j.cancelled = true;
            report(j, "stopped");
            j.finished.countDown();
            stopped.add(j);
        }
        return stopped;
    }

    private void endJobCard(Job j, String outcome) {
        if (j.spoken != null) j.spoken.end(overlay.cards, outcome);
        else j.spec.endCard(overlay.cards, outcome);
    }

    /** "speaker": the built-in speaker; "tv": the first HDMI output by type. null: none now. */
    private AudioDeviceInfo device(String output) {
        int[] types = output.equals("speaker")
                ? new int[] {AudioDeviceInfo.TYPE_BUILTIN_SPEAKER}
                : new int[] {AudioDeviceInfo.TYPE_HDMI, AudioDeviceInfo.TYPE_HDMI_ARC, AudioDeviceInfo.TYPE_HDMI_EARC};
        AudioDeviceInfo[] all = audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS);
        for (int type : types) {
            for (AudioDeviceInfo d : all) {
                if (d.getType() == type) return d;
            }
        }
        return null;
    }

    // ---- Speech engine ----

    /** Main thread only: replaces the speech engine instance. */
    private void newEngine() {
        synchronized (engineLock) {
            if (tts != null) {
                try {
                    tts.shutdown();
                } catch (RuntimeException e) {
                    Log.w(TAG, "speech engine shutdown failed", e);
                }
            }
            ttsReady = false;
            enginePending = true;
            engineStarted = SystemClock.elapsedRealtime();
            final int generation = ++engineGeneration;
            TextToSpeech t = new TextToSpeech(this, status -> engineInit(generation, status));
            t.setOnUtteranceProgressListener(progress);
            tts = t;
        }
    }

    private void engineInit(int generation, int status) {
        synchronized (engineLock) {
            if (generation != engineGeneration) return;
            enginePending = false;
            ttsReady = status == TextToSpeech.SUCCESS;
        }
        Log.i(TAG, "speech engine init status " + status);
    }

    /** Any thread: a new engine instance, unless the current one is still starting. */
    private void renewEngine() {
        main.post(() -> {
            synchronized (engineLock) {
                if (enginePending && SystemClock.elapsedRealtime() - engineStarted < ENGINE_START_MS) return;
            }
            newEngine();
        });
    }

    private void synthesized(String id, String error) {
        Job j = id == null ? null : synthesizing.remove(id);
        if (j == null) return;
        if (error != null) j.error = error;
        j.synthesized.countDown();
    }

    // ---- Jobs (worker thread) ----

    private void work() {
        while (running) {
            Job j;
            synchronized (enqueue) {
                while ((j = queue.start()) == null) {
                    if (!running) return;
                    try { enqueue.wait(); } catch (InterruptedException done) { return; }
                }
            }
            boolean played = false;
            AudioFocusRequest focus = null;
            try {
                wake.acquire(WAKE_MS);
                synchronized (enqueue) {
                    if (stopped(j)) continue;
                    events.add("announce.started", Json.fields("job", j.id));
                }
                File file = prepare(j);
                synchronized (enqueue) {
                    if (stopped(j)) continue;
                    requestIds.state(j.spec.requestId, j.id, "playing");
                    ownJobAudio(true);
                    focus = j.spec.focus(this::requestDuck);
                }
                wakeSpeaker(j);
                if (!stopped(j)) played = run(j, file);
            } catch (IOException | RuntimeException e) {
                last = j.spec.failure("playback");
            } finally {
                ownJobAudio(false);
                if (focus != null) {
                    try { audio.abandonAudioFocusRequest(focus); }
                    catch (RuntimeException e) { Log.w(TAG, "audio focus release failed"); }
                }
                synchronized (overlay.cards) {
                    String outcome = stopped(j) ? "stopped" : played ? "played" : "error";
                    if (j.spoken != null) endJobCard(j, outcome);
                    synchronized (enqueue) {
                        if (stopped(j)) last = j.spec.failure("stopped");
                        discard(j);
                        report(j, outcome);
                        queue.finished();
                        if (!queue.busy() && wake.isHeld()) wake.release();
                    }
                    if (j.spoken == null) endJobCard(j, outcome);
                }
                j.finished.countDown();
            }
        }
    }

    private AudioFocusRequest requestDuck() {
        AudioFocusRequest focus = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setOnAudioFocusChangeListener(change -> {}).build();
        try { return audio.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED ? focus : null; }
        catch (RuntimeException refused) { return null; }
    }

    private void report(Job j, String outcome) {
        if (j.reported) return;
        j.reported = true;
        try { requestIds.state(j.spec.requestId, j.id, outcome); }
        catch (Api.Failure failure) { Log.w(TAG, "job outcome store failed"); }
        events.add("announce.ended", Json.fields("job", j.id, "result", outcome, "voice_at", j.voiceAt));
        Log.i(TAG, "job " + j.id + " out " + j.output + " vol " + j.level
                + (j.spec.chime ? " chime " + j.chimeMs : "") + " voice " + j.voiceMs + " end " + j.elapsed());
    }

    private boolean stopped(Job j) { return j.cancelled; }

    private void discard(Job j) {
        synthesizing.remove(j.id);
        if (j.speech != null) j.speech.delete();
        if (j.recording != null) j.recording.delete();
    }

    private File prepare(Job j) throws IOException {
        if (j.spec.clip != null) return clips.file(j.spec.clip);
        if (j.recording != null) {
            byte[] data = j.spec.url == null ? j.spec.audio : fetch(j);
            if (stopped(j)) throw new IOException();
            try (FileOutputStream out = new FileOutputStream(j.recording)) { out.write(data); }
            return j.recording;
        }
        if (j.speech == null) return null; // Internal chime-only job.
        synchronized (enqueue) {
            if (stopped(j)) throw new IOException();
            speechDir.mkdirs();
            synthesizing.put(j.id, j);
            synchronized (engineLock) {
                if (tts == null || tts.synthesizeToFile(j.spec.text, new Bundle(), j.speech, j.id) != TextToSpeech.SUCCESS) {
                    renewEngine();
                    throw new IOException();
                }
            }
        }
        if (!await(j.synthesized, SYNTH_TIMEOUT_MS) || j.error != null || j.speech.length() == 0) {
            synchronized (engineLock) { if (tts != null) tts.stop(); }
            throw new IOException();
        }
        return j.speech;
    }

    // The caller enforces the total deadline even if DNS or a trickling response stalls.
    // A lingering network reader cannot create files and occupies the only fetch slot.
    private byte[] fetch(Job j) throws IOException {
        FutureTask<byte[]> task = new FutureTask<>(() -> {
            HttpURLConnection c = (HttpURLConnection) new URL(j.spec.url).openConnection();
            c.setConnectTimeout(READ_TIMEOUT_MS);
            c.setReadTimeout(READ_TIMEOUT_MS);
            try {
                if (c.getResponseCode() / 100 != 2 || c.getContentLengthLong() > 4194304) throw new IOException();
                try (InputStream in = c.getInputStream()) {
                    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                    byte[] buffer = new byte[8192];
                    int n;
                    while ((n = in.read(buffer)) != -1) {
                        if (Thread.currentThread().isInterrupted() || bytes.size() + n > 4194304) throw new IOException();
                        bytes.write(buffer, 0, n);
                    }
                    return bytes.toByteArray();
                }
            } finally { c.disconnect(); }
        });
        synchronized (enqueue) {
            if (stopped(j) || !fetchSlot.tryAcquire()) throw new IOException();
            j.fetch = task;
            new Thread(() -> {
                try { task.run(); } finally { fetchSlot.release(); }
            }, "herald-audio-fetch").start();
        }
        try { return task.get(READ_TIMEOUT_MS, TimeUnit.MILLISECONDS); }
        catch (InterruptedException | ExecutionException | TimeoutException | CancellationException e) { throw new IOException(); }
        finally { task.cancel(true); }
    }

    private boolean run(Job j, File file) {
        j.playbackDeadline = SystemClock.elapsedRealtime() + PLAY_TIMEOUT_MS;
        String name = j.spec.clip != null ? j.spec.clip : j.recording != null ? "voice" : "speech";
        AudioTrack tone = null;
        try (PreparedFile voice = file == null ? null : new PreparedFile()) {
            if (voice != null) {
                voice.prepare(j, file);
                if (voice.failed) {
                    last = j.spec.failure("play " + name);
                    return false;
                }
            }
            if (stopped(j)) return false;
            if (j.spec.chimeKey != null) {
                if (!playChimeClip(j, name)) return false;
            } else if (j.spec.chime) {
                tone = startChime(j, name);
                if (tone == null && (voice == null || j.spoken != null)) return false;
                if (tone != null) {
                    awaitChime(j, AudioTiming.remainingMs(j.chimeMs, chimePcm.length, RATE, PAUSE_MS, j.elapsed()));
                    if (!canStart(j, name)) return false;
                }
            }
            if (voice != null) return playFile(j, voice, true, name);
            if (stopped(j)) return false;
            last = "played chime";
            return true;
        } catch (IOException | RuntimeException failure) {
            last = j.spec.failure("play " + name);
            return false;
        } finally {
            if (tone != null) tone.release();
        }
    }

    private boolean playChimeClip(Job j, String name) throws IOException {
        long duration;
        try (PreparedFile chime = new PreparedFile()) {
            chime.prepare(j, clips.file(j.spec.chimeKey));
            duration = chime.player.getDuration();
            if (!playFile(j, chime, false, name)) return false;
            if (duration < 0) duration = j.elapsed() - j.chimeMs;
        }
        awaitChime(j, j.chimeMs + duration + PAUSE_MS - j.elapsed());
        return true;
    }

    private void awaitChime(Job j, long remaining) {
        await(j.end, Math.max(0L, Math.min(remaining, j.playbackDeadline - SystemClock.elapsedRealtime())));
    }

    private boolean canStart(Job j, String name) {
        if (stopped(j)) return false;
        if (SystemClock.elapsedRealtime() < j.playbackDeadline) return true;
        last = j.spec.failure("timeout " + name);
        return false;
    }

    /**
     * Unmutes the box speaker when the TV is off. The box mutes its speaker amplifier while the
     * TV plays and unmutes it only when sound is newly routed to the speaker. With the TV off
     * the route already is the speaker, so nothing unmutes it and a clip plays into a muted
     * amplifier. A quarter second of silence sent to another output first (the box lists a
     * "Mute Device") makes the speaker route new again. Seen on the Living Room box on
     * 2026-11-25: kernel log `spk_mute_set: mute flag = 0` right after this step.
     * With an HDMI output present nothing is done: choosing the speaker is a new route then.
     */
    private void wakeSpeaker(Job j) {
        if (device("tv") != null) return;
        AudioDeviceInfo other = null;
        for (AudioDeviceInfo d : audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
            if (d.getType() != AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) {
                other = d;
                break;
            }
        }
        if (other == null) return;
        AudioTrack t = null;
        try {
            short[] silence = new short[RATE / 4];
            t = new ChimeTrack(CHIME,
                    new AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(RATE)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build(),
                    silence.length * 2);
            if (t.write(silence, 0, silence.length) != silence.length) {
                throw new IllegalStateException("silence write failed");
            }
            t.setPreferredDevice(other);
            t.setVolume(0f);
            t.play();
            await(j.end, 400);
        } catch (RuntimeException e) {
            Log.w(TAG, "speaker wake failed", e);
        } finally {
            if (t != null) t.release();
        }
        if (!stopped(j)) await(j.end, 150);
    }

    private AudioTrack startChime(Job j, String name) {
        AudioTrack t = null;
        try {
            t = new ChimeTrack(CHIME,
                    new AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(RATE)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build(),
                    chimePcm.length * 2);
            if (t.write(chimePcm, 0, chimePcm.length) != chimePcm.length) {
                throw new IllegalStateException("chime write failed");
            }
            if (j.device != null) t.setPreferredDevice(j.device);
            t.setVolume(j.volume);
            synchronized (enqueue) {
                if (!canStart(j, name)) {
                    t.release();
                    return null;
                }
                t.play();
                j.chimeMs = j.elapsed();
            }
            return t;
        } catch (RuntimeException e) {
            last = "error chime";
            Log.w(TAG, "chime failed", e);
            if (t != null) t.release();
            return null;
        }
    }

    private static final class PreparedFile implements AutoCloseable {
        final MediaPlayer player = new ClipPlayer();
        final CountDownLatch ended = new CountDownLatch(1);
        volatile boolean failed;
        VolumeShaper fade;

        void prepare(Job j, File file) throws IOException {
            player.setAudioAttributes(VOICE);
            player.setOnCompletionListener(m -> ended.countDown());
            player.setOnErrorListener((m, what, extra) -> {
                failed = true;
                ended.countDown();
                return true;
            });
            try (FileInputStream in = new FileInputStream(file)) {
                player.setDataSource(in.getFD());
            }
            player.prepare();
            if (j.device != null && Build.VERSION.SDK_INT >= 28) player.setPreferredDevice(j.device);
            player.setVolume(j.volume, j.volume);
            try {
                fade = player.createVolumeShaper(FADE_IN);
                fade.apply(VolumeShaper.Operation.PLAY);
            } catch (RuntimeException e) {
                Log.w(TAG, "no fade-in", e);
            }
        }

        @Override public void close() {
            if (fade != null) {
                try { fade.close(); }
                catch (RuntimeException e) { Log.w(TAG, "fade-in close failed", e); }
            }
            player.release();
        }
    }

    private boolean playFile(Job j, PreparedFile file, boolean voice, String name) {
        boolean showCard = voice && j.spec.card != null && (j.spoken == null || j.spoken.generation >= 0);
        if (showCard && !j.spec.chime) showVoiceCard(j, file);
        synchronized (enqueue) {
            if (!canStart(j, name)) return false;
            file.player.start();
            if (voice) {
                j.voiceAt = System.currentTimeMillis();
                j.voiceMs = j.elapsed();
                if (j.spec.clip != null) last = "playing " + j.spec.clip;
            } else {
                j.chimeMs = j.elapsed();
            }
            j.clipEnd = file.ended;
        }
        try {
            if (showCard && j.spec.chime) showVoiceCard(j, file);
            boolean complete = await(file.ended, Math.max(0L, j.playbackDeadline - SystemClock.elapsedRealtime()));
            if (stopped(j)) last = j.spec.failure("stopped");
            else if (!complete) last = j.spec.failure("timeout " + name);
            else if (file.failed) last = j.spec.failure("play " + name);
            else {
                if (voice) last = j.spec.played();
                return true;
            }
            return false;
        } finally {
            synchronized (enqueue) { j.clipEnd = null; }
        }
    }

    private void showVoiceCard(Job j, PreparedFile file) {
        synchronized (overlay.cards) {
            if (stopped(j)) return;
            try {
                int length = file.player.getDuration();
                if (j.spoken != null) j.spoken.show(overlay.cards, length);
                else j.spec.show(overlay.cards, length);
            }
            catch (Api.Failure refused) { /* Display eligibility does not suppress speech. */ }
        }
    }

    private static boolean await(CountDownLatch latch, long ms) {
        try {
            return latch.await(ms, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return true;
        }
    }

    // ---- HTTP (listener and request threads) ----

    private void rebind(int revision, int port) {
        synchronized (listenerLock) {
            if (revision <= listenerRevision) return;
            listenerRevision = revision;
            listenerPort = port;
            if (server != null && server.getLocalPort() != port) close(server);
            listenerLock.notifyAll();
        }
    }

    private void listen() {
        try {
            tlsIdentity = AndroidTls.identity(this, deviceId);
        } catch (java.security.GeneralSecurityException | IOException | RuntimeException e) {
            tlsUnavailable = true;
            Log.w(TAG, "TLS identity unavailable");
            return;
        }
        while (running) {
            int port = listenerPort;
            SSLServerSocket s = null;
            try {
                s = (SSLServerSocket) tlsIdentity.sslContext().getServerSocketFactory().createServerSocket();
                TlsIdentity.configure(s);
                s.setReuseAddress(true);
                synchronized (listenerLock) {
                    if (!running) return;
                    port = listenerPort;
                    s.bind(new InetSocketAddress(port));
                    server = s;
                    refreshDiscovery();
                }
                Log.i(TAG, "listening on port " + port);
                accept(s, false);
            } catch (IOException e) {
                if (running && port == listenerPort) Log.w(TAG, "listener failed; trying again in 5 s", e);
            } finally {
                synchronized (listenerLock) {
                    close(s);
                    if (s != null && server == s) {
                        server = null;
                        refreshDiscovery();
                    }
                }
            }
            if (running && port == listenerPort) SystemClock.sleep(5000);
        }
    }

    private void configureCompat(Map<String,Object> doc) {
        Map<String,Object> cfg = Compat.config(doc);
        int port = (boolean)cfg.get("enabled") ? ((Number)cfg.get("port")).intValue() : 0;
        synchronized (listenerLock) {
            if (port != compatPort) {
                close(compatServer);
                compatStatus = "off";
            }
            compatPort = port;
            compatRevision++;
            listenerLock.notifyAll();
        }
    }

    private void listenCompat() {
        long attempted = -1;
        while (running) {
            ServerSocket s = null;
            try {
                synchronized (listenerLock) {
                    // A port swap must wait for the main listener's after-reply rebind.
                    while (running && (compatPort == 0 || compatPort == listenerPort || attempted == compatRevision))
                        listenerLock.wait();
                    if (!running) return;
                    attempted = compatRevision;
                    s = new ServerSocket();
                    s.setReuseAddress(true);
                    s.bind(new InetSocketAddress(compatPort));
                    compatServer = s;
                    compatStatus = "listening";
                }
                accept(s, true);
            } catch (InterruptedException stopped) {
                Thread.currentThread().interrupt();
                return;
            } catch (IOException failure) {
                synchronized (listenerLock) {
                    if (running && (s == null || !s.isClosed()) && attempted == compatRevision) {
                        compatStatus = "port in use";
                        Log.w(TAG, "compat listener unavailable");
                    }
                }
            } finally {
                synchronized (listenerLock) {
                    close(s);
                    if (compatServer == s) compatServer = null;
                }
            }
        }
    }

    private void accept(ServerSocket listener, boolean compatibility) throws IOException {
        while (running) {
            Socket c = listener.accept();
            if (!connections.tryAcquire()) {
                close(c);
                continue;
            }
            try {
                new Thread(() -> serve(c, compatibility), "herald-request").start();
            } catch (Throwable failure) {
                connections.release();
                close(c);
                Log.w(TAG, "no thread for a request");
            }
        }
    }

    private void serve(Socket c, boolean compatibility) {
        try {
            c.setSoTimeout(READ_TIMEOUT_MS);
            if (!compatibility && !TlsIdentity.handshake((SSLSocket) c, tlsFailures)) return;
            BufferedOutputStream out = new BufferedOutputStream(c.getOutputStream());
            if (compatibility) {
                String peer = c.getInetAddress().getHostAddress();
                int zone = peer.indexOf('%');
                compat.handle(zone < 0 ? peer : peer.substring(0, zone), c.getLocalPort(), c.getInputStream(), out);
            } else api.handle(c.getInputStream(), out);
        } catch (IOException | RuntimeException e) {
            Log.w(TAG, "request failed");
        } finally {
            close(c);
            connections.release();
        }
    }

    private static void close(Closeable c) {
        if (c == null) return;
        try {
            c.close();
        } catch (IOException e) {
            // closing anyway
        }
    }
}
