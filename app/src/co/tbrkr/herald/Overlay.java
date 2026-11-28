package co.tbrkr.herald;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Window ownership and bitmap delivery are confined to the main looper. */
final class Overlay implements CardBoard.Display {
    private final HeraldService service;
    private final Settings settings;
    private final ImageStore images;
    private final ChipBoard chips;
    final CardBoard cards;
    private final WindowManager wm;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ThreadPoolExecutor workers = new ThreadPoolExecutor(3, 3, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(64));
    private final File spool;
    private volatile boolean closed;
    private final Map<String, Window> windows = new HashMap<>();
    private final Map<String, Window> retiring = new HashMap<>();
    private final Map<CardSpec, File> stills = new HashMap<>();
    private final Map<String, ChipBar> bars = new HashMap<>();
    private final ChipPulse pulses = new ChipPulse();
    private int chipRevision = -1;
    private final Point chipSize = new Point();
    private Map<String, ChipLayout.Group> groups = new HashMap<>();
    private List<Map<String, Object>> previousChips;
    private final Runnable redraw = this::render;

    /** Keep API 33 callback types out of classes loaded on older systems. */
    private static final class BackRegistration {
        final OnBackInvokedDispatcher dispatcher;
        final OnBackInvokedCallback callback;

        BackRegistration(OnBackInvokedDispatcher dispatcher, Runnable action) {
            this.dispatcher = dispatcher;
            callback = action::run;
            dispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_OVERLAY, callback);
        }

        void clear() { dispatcher.unregisterOnBackInvokedCallback(callback); }
    }

    @android.annotation.TargetApi(33)
    private static void setTouchBounds(View view, int left, int top, int width, int height) {
        android.view.AttachedSurfaceControl surface = view.getRootSurfaceControl();
        if (surface != null) surface.setTouchableRegion(new android.graphics.Region(left,top,left+width,top+height));
    }

    private final class Window extends FrameLayout {
        final long position;
        CardView view;
        VideoCard video;
        WindowManager.LayoutParams layout, nextLayout;
        boolean loading, pictured, owner;
        CardMotion.Frame nextFrame, target, presented;
        ValueAnimator animator;
        int screenW, screenH;
        String slot;
        boolean resizeSurface;
        Map<String,Object> appearance;

        void cancelMotion() {
            if (animator != null) {
                animator.removeAllListeners();
                animator.removeAllUpdateListeners();
                animator.cancel();
                animator = null;
            }
            view.attentionGlow(0);
        }

        void play(int duration, android.animation.TimeInterpolator curve,
                java.util.function.Consumer<Float> frame, Runnable end) {
            if (!ValueAnimator.areAnimatorsEnabled() || duration == 0) {
                frame.accept(1f); end.run(); return;
            }
            ValueAnimator run = ValueAnimator.ofFloat(0,1);
            animator = run;
            run.setDuration(duration);
            run.setInterpolator(curve);
            run.addUpdateListener(a -> frame.accept((float)a.getAnimatedValue()));
            run.addListener(new AnimatorListenerAdapter() {
                @Override public void onAnimationEnd(Animator a) {
                    if (animator != run) return;
                    animator = null;
                    end.run();
                }
            });
            frame.accept(0f);
            run.start();
        }

        void drawFrame(CardMotion.Frame frame) {
            presented = frame;
            int[] edge = view.insets;
            int left = Math.round(frame.x), top = Math.round(frame.y);
            int width = Math.max(1,Math.round(frame.width)), height = Math.max(1,Math.round(frame.height));
            // Clip at the TV edges while the window itself follows the visible card.
            int x = Math.max(0,Math.min(screenW-1,left-edge[0]));
            int y = Math.max(0,Math.min(screenH-1,top-edge[1]));
            WindowManager.LayoutParams next = new WindowManager.LayoutParams();
            next.copyFrom(nextLayout);
            if (resizeSurface) { x = 0; y = 0; }
            next.x = x; next.y = y;
            next.width = Math.max(1,Math.min(screenW,left+width+edge[2])-x);
            next.height = Math.max(1,Math.min(screenH,top+height+edge[3])-y);
            if (resizeSurface) { next.width = screenW; next.height = screenH; }
            next.windowAnimations = 0;
            positionChild(view,left-x,top-y,width,height);
            positionChild(view.decoration,left-x-edge[0],top-y-edge[1],width+edge[0]+edge[2],height+edge[1]+edge[3]);
            view.presentation(frame.full);
            setAlpha(frame.opacity);
            if (layout == null) {
                wm.addView(this,next);
                layout = next;
            } else if (layout.copyFrom(next) != 0) wm.updateViewLayout(this,layout);
            if (Build.VERSION.SDK_INT >= 33 && view.spec.video != null)
                setTouchBounds(this, left-x,top-y,width,height);

        }

        void attention() {
            String kind = (String)appearance.get("attention_animation");
            if ("none".equals(kind) || !ValueAnimator.areAnimatorsEnabled()) return;
            CardMotion.Frame rest = target;
            int duration = "double_pulse".equals(kind) ? 740 : "glow".equals(kind) ? 650 : "nudge".equals(kind) ? 360 : 450;
            play(duration,new android.view.animation.LinearInterpolator(), t -> {
                float wave = (float)Math.pow(Math.sin(Math.PI*t*("double_pulse".equals(kind) ? 2 : 1)),2);
                if ("glow".equals(kind)) view.attentionGlow(wave);
                else if ("nudge".equals(kind)) drawFrame(rest.transformed(rest.width*.006f*wave,0,1,1));
                else drawFrame(rest.transformed(0,0,1+.025f*wave,1));
            }, () -> { view.attentionGlow(0); drawFrame(rest); });
        }

        void present(CardMotion.Frame next, boolean fresh) {
            if (!fresh && next.sameGeometry(target)) {
                drawFrame(presented);
                return;
            }
            CardMotion.Frame previous = presented;
            boolean resize = target != null && next.full != target.full;
            boolean continuing = previous != null && previous.full != next.full;
            target = next;
            cancelMotion();
            resizeSurface = false;
            if (fresh) {
                String kind = (String)appearance.get("transition_in");
                CardMotion.Frame start = CardMotion.edge(next,kind,true,view.placement.startsWith("bottom"));
                play("none".equals(kind) ? 0 : 250,new android.view.animation.DecelerateInterpolator(),
                        t -> drawFrame(start.toward(next,t)),this::attention);
            } else if (resize || continuing) {
                String kind = (String)appearance.get(next.full == 1 ? "transition_expand" : "transition_minimize");
                // Keep a single backing surface while moving the video. The touch region tracks the card.
                resizeSurface = !"none".equals(kind) && ValueAnimator.areAnimatorsEnabled()
                        && (view.spec.video == null || Build.VERSION.SDK_INT >= 33);
                play("none".equals(kind) ? 0 : 300,new android.view.animation.AccelerateDecelerateInterpolator(),
                        t -> {
                            android.os.Trace.beginSection(t == 0 ? "Herald resize start" : t == 1 ? "Herald resize end" : "Herald resize frame");
                            try { drawFrame(CardMotion.resize(previous,next,kind,t)); }
                            finally { android.os.Trace.endSection(); }
                        }, () -> {
                            resizeSurface = false;
                            drawFrame(next);
                        });
            } else drawFrame(next);
        }
        BackRegistration back;
        CardSpec armed;
        int armedKey;

        Window(Context context, long position) {
            super(context);
            this.position = position;
            setFocusableInTouchMode(true);
        }

        void syncBack() {
            if (Build.VERSION.SDK_INT < 33) return;
            if (owner && hasWindowFocus() && isAttachedToWindow()) {
                if (back == null && findOnBackInvokedDispatcher() != null) {
                    CardSpec spec = view.spec;
                    back = new BackRegistration(findOnBackInvokedDispatcher(), () -> {
                        if (owner && hasWindowFocus()) cards.back(spec, SystemClock.uptimeMillis());
                    });
                }
            } else if (back != null) {
                back.clear();
                back = null;
            }
        }

        @Override public void onWindowFocusChanged(boolean focus) {
            super.onWindowFocusChanged(focus);
            if (!focus) armed = null;
            syncBack();
        }

        @Override public boolean dispatchKeyEvent(KeyEvent event) {
            int key = event.getKeyCode();
            if (!KeyPolicy.isCardKey(key)) return false;
            if (!owner || !hasWindowFocus() || cards.focusOwner() != view.spec) return true;
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                if (event.getRepeatCount() == 0) {
                    armed = view.spec;
                    armedKey = key;
                }
                if (key == KeyEvent.KEYCODE_DPAD_LEFT) view.move(-1);
                if (key == KeyEvent.KEYCODE_DPAD_RIGHT) view.move(1);
            } else if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) {
                if (key == KeyEvent.KEYCODE_BACK && Build.VERSION.SDK_INT < 33) {
                    cards.back(view.spec, SystemClock.uptimeMillis());
                } else if (armed == view.spec && armedKey == key &&
                        (key == KeyEvent.KEYCODE_ENTER || key == KeyEvent.KEYCODE_DPAD_CENTER
                                || key == KeyEvent.KEYCODE_NUMPAD_ENTER)) {
                    cards.press(view.spec, view.selected());
                }
                armed = null;
            }
            return true;
        }

        void release() {
            owner = false;
            armed = null;
            syncBack();
            if (video != null) {
                video.close();
                video = null;
            }
        }
    }

    Overlay(HeraldService service, Settings settings, EventLog events, ChipBoard chips, ImageStore images) {
        this.service = service;
        this.settings = settings;
        this.chips = chips;
        this.images = images;
        wm = (WindowManager) service.getSystemService(Context.WINDOW_SERVICE);
        cards = new CardBoard(settings, events, service, this, System::currentTimeMillis);
        spool = new File(service.getCacheDir(), "card-stills");
        spool.mkdirs();
        File[] old = spool.listFiles();
        if (old != null) for (File file : old) file.delete();
        main.post(tick);
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (closed) return;
            cards.tick();
            render();
            main.postDelayed(this, 500);
        }
    };

    @Override public void changed() {
        main.removeCallbacks(redraw);
        if (!closed) main.post(redraw);
    }

    static Bitmap decode(byte[] bytes) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, o);
        if (o.outWidth <= 0 || o.outHeight <= 0) return null;
        o.inSampleSize = ImageStore.sampleSize(o.outWidth, o.outHeight);
        o.inJustDecodeBounds = false;
        Bitmap bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, o);
        if (bmp == null) return null;
        int longest = Math.max(bmp.getWidth(), bmp.getHeight());
        if (longest <= 1280) return bmp;
        float scale = 1280f / longest;
        Bitmap small = Bitmap.createScaledBitmap(bmp, Math.max(1,Math.round(bmp.getWidth()*scale)),
                Math.max(1,Math.round(bmp.getHeight()*scale)), true);
        bmp.recycle();
        return small;
    }

    static boolean decodes(byte[] data) {
        Bitmap bitmap = decode(data);
        if (bitmap == null) return false;
        bitmap.recycle();
        return true;
    }

    private byte[] fetch(String url, long end) throws IOException {
        long available = end - SystemClock.elapsedRealtime();
        if (available <= 0) throw new IOException();
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        try {
            connection.setConnectTimeout((int) available);
            connection.setReadTimeout((int) available);
            connection.setInstanceFollowRedirects(false);
            int code = connection.getResponseCode();
            if (code < 200 || code >= 300 || connection.getContentLengthLong() > ImageStore.LIMIT)
                throw new IOException();
            try (InputStream stream = connection.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buf = new byte[8192];
                while (true) {
                    long remaining = end - SystemClock.elapsedRealtime();
                    if (remaining <= 0) throw new IOException();
                    connection.setReadTimeout((int) remaining);
                    int n = stream.read(buf);
                    if (SystemClock.elapsedRealtime() > end) throw new IOException();
                    if (n == -1) return out.toByteArray();
                    if (out.size() + n > ImageStore.LIMIT) throw new IOException();
                    out.write(buf, 0, n);
                }
            }
        } finally { connection.disconnect(); }
    }

    @Override public void loadImage(CardSpec spec) {
        if (spec.previewTimestamp != 0 && !CardSpec.freshPreview(spec.previewTimestamp, System.currentTimeMillis()/1000)) return;
        // Include time waiting for a worker or DNS in the URL deadline. A late result is discarded.
        long deadline = SystemClock.elapsedRealtime() + 5000;
        java.util.concurrent.atomic.AtomicBoolean timedOut = new java.util.concurrent.atomic.AtomicBoolean();
        Runnable timeout = () -> {
            timedOut.set(true);
            if (!closed) cards.media(spec, "failed");
        };
        boolean remote = spec.image.containsKey("url");
        if (remote) main.postDelayed(timeout, 5000);
        try {
            workers.execute(() -> {
                if (!cards.owns(spec) || closed || timedOut.get()) {
                    main.removeCallbacks(timeout);
                    return;
                }
                File file = null;
                try {
                    ImageStore.Picture<Bitmap> still = images.load(spec, url -> {
                        byte[] body = fetch(url, deadline);
                        main.removeCallbacks(timeout);
                        return body;
                    }, Overlay::decode);
                    if (timedOut.get()) return;
                    Bitmap bitmap = still.bitmap;
                    file = File.createTempFile("still-", ".img", spool);
                    Files.write(file.toPath(), still.bytes);
                    File saved = file;
                    main.post(() -> {
                        if (closed || timedOut.get() || !cards.owns(spec)) { saved.delete(); return; }
                        stills.put(spec, saved);
                        Window w = windows.get(spec.id);
                        if (w != null && w.view.spec == spec && service.screenOn()) picture(w, bitmap);
                        changed();
                    });
                } catch (IOException | RuntimeException failure) {
                    if (file != null) file.delete();
                    main.removeCallbacks(timeout);
                    main.post(() -> { if (!closed && !timedOut.get()) cards.media(spec, "failed"); });
                }
            });
        } catch (RejectedExecutionException busy) {
            main.removeCallbacks(timeout);
            cards.media(spec, "failed");
        }
    }

    private void picture(Window w, Bitmap bitmap) {
        w.view.picture(bitmap);
        w.pictured = true;
    }

    private void restore(Window w) {
        CardSpec spec = w.view.spec;
        File file = stills.get(spec);
        if (file == null || w.loading || w.pictured) return;
        w.loading = true;
        try {
            workers.execute(() -> {
                Bitmap result = null;
                try { result = decode(Files.readAllBytes(file.toPath())); }
                catch (IOException | RuntimeException ignored) { }
                Bitmap bitmap = result;
                main.post(() -> {
                    if (closed || !service.screenOn() || !cards.owns(spec) || windows.get(spec.id) != w || w.view.spec != spec) return;
                    if (bitmap == null) cards.media(spec, "failed");
                    else { picture(w, bitmap); changed(); }
                });
            });
        } catch (RejectedExecutionException busy) { w.loading = false; }
    }

    private static int gravity(String placement) {
        int horizontal = placement.endsWith("left") ? Gravity.LEFT : placement.endsWith("right") ? Gravity.RIGHT : Gravity.CENTER_HORIZONTAL;
        int vertical = placement.startsWith("top") ? Gravity.TOP : placement.startsWith("bottom") ? Gravity.BOTTOM : Gravity.CENTER_VERTICAL;
        return vertical | horizontal;
    }

    private WindowManager.LayoutParams params(int width, int height, String placement, Point size) {
        WindowManager.LayoutParams p = new WindowManager.LayoutParams(width, height,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE, PixelFormat.TRANSLUCENT);
        p.gravity = gravity(placement);
        if (placement.endsWith("left") || placement.endsWith("right")) p.x = Math.round(.03f * size.x);
        if (placement.startsWith("top") || placement.startsWith("bottom")) p.y = Math.round(.03f * size.y);
        return p;
    }

    private void render() {
        if (closed) return;
        synchronized (cards) {
            if (!service.screenOn() || !service.overlayAllowed()) clearRetiring();
            List<CardBoard.Entry> visible = service.screenOn() ? cards.showing() : new ArrayList<>();
            windows.entrySet().removeIf(pair -> {
                for (CardBoard.Entry e : visible) if (pair.getKey().equals(e.spec.id) && pair.getValue().position == e.position) return false;
                retire(pair.getValue());
                return true;
            });
            stills.entrySet().removeIf(pair -> {
                if (cards.owns(pair.getKey())) return false;
                pair.getValue().delete();
                return true;
            });
            Point size = new Point();
            wm.getDefaultDisplay().getRealSize(size);
            visible.sort(Comparator.comparingLong((CardBoard.Entry e) -> e.position).reversed());
            Map<String, List<Window>> stacks = new HashMap<>();
            int gap = Math.round(size.y * .015f);
            CardSpec focus = cards.focusOwner();
            boolean paused = cards.pauseToUseButtons();
            for (CardBoard.Entry e : visible) {
                Window w = windows.computeIfAbsent(e.spec.id, id -> new Window(service, e.position));
                if (w.view == null || w.view.spec != e.spec) {
                    if (w.view != null) w.cancelMotion();
                    w.release();
                    w.removeAllViews();
                    String categoryName = e.spec.category;
                    if ("category".equals(e.appearance.fields.get("label"))) {
                        for (Map<String, Object> row : settings.categories()) {
                            if (e.spec.category.equals(row.get("id"))) categoryName = (String) row.get("name");
                        }
                    }
                    w.view = new CardView(service, e, size.x, size.y, categoryName, images, () -> cards.media(e.spec, "shown"));
                    w.addView(w.view.decoration);
                    w.addView(w.view);
                    w.loading = w.pictured = false;
                }
                w.appearance = e.appearance.fields;
                w.view.setExpanded(e.expanded, size.x);
                w.owner = e.spec == focus;
                w.view.buttonFocus(w.owner, paused);
                w.syncBack();
                boolean full = "fullscreen".equals(w.view.placement);
                boolean wrap = !full && "pill".equals(w.view.layout)
                        && !"full".equals(e.appearance.fields.get("size"));
                w.view.measure(View.MeasureSpec.makeMeasureSpec(w.view.width,
                                wrap ? View.MeasureSpec.AT_MOST : View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(size.y, full ? View.MeasureSpec.EXACTLY : View.MeasureSpec.AT_MOST));
                stacks.computeIfAbsent(w.view.placement, key -> new ArrayList<>()).add(w);
            }
            for (List<Window> stack : stacks.values()) position(stack, size, gap);
            for (CardBoard.Entry e : visible) {
                Window w = windows.get(e.spec.id);
                try {
                    boolean fresh = w.layout == null;
                    w.present(w.nextFrame,fresh);
                    if (fresh) cards.attached(e.spec, System.currentTimeMillis());
                    if (e.spec.video != null && w.video == null) {
                        w.video = new VideoCard(w.view, cards, service);
                        w.video.start();
                    }
                    restore(w);
                } catch (RuntimeException denied) {
                    w.cancelMotion();
                    w.release();
                    remove(w);
                    windows.remove(e.spec.id);
                    cards.failed(e.spec);
                }
            }
            renderChips(size);
        }
    }

    private void position(List<Window> stack, Point size, int gap) {
        int count = stack.size();
        int[][] sizes = new int[count][2], edges = new int[count][], origins = new int[count][2];
        boolean[] fullWidth = new boolean[count];
        int[] margins = new int[count];
        for (int i = 0; i < count; i++) {
            CardView card = stack.get(i).view;
            sizes[i][0] = card.getMeasuredWidth();
            sizes[i][1] = card.getMeasuredHeight();
            fullWidth[i] = sizes[i][0] == size.x;
            edges[i] = card.insets;
            margins[i] = (int)stack.get(i).appearance.get("edge_margin");
        }
        int[][] rects = CardFrame.place(stack.get(0).view.placement, size.x, size.y, sizes,
                fullWidth, gap, edges, origins, margins);
        for (int i = 0; i < count; i++) {
            Window w = stack.get(i);
            int[] r = rects[i], edge = edges[i];
            int x = origins[i][0] - r[0], y = origins[i][1] - r[1];
            int width = sizes[i][0], height = sizes[i][1];
            w.screenW = size.x; w.screenH = size.y;
            w.slot = w.view.placement + ":" + i;
            w.nextFrame = new CardMotion.Frame(origins[i][0],origins[i][1],width,height,1,
                    "fullscreen".equals(w.view.placement) ? 1 : 0);
            WindowManager.LayoutParams p = params(r[2] - r[0], r[3] - r[1], "top_left", size);
            p.x = r[0];
            p.y = r[1];
            if (w.view.spec.video != null) {
                // Android caps click-through overlay opacity. Target this window for pointer
                // events inside its bounds and let the app underneath receive outside touches.
                p.flags &= ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
                p.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL;
            }
            if (w.owner) p.flags &= ~WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
            // Service-owned windows need a hardware renderer to composite the video texture.
            p.flags |= WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED;
            w.nextLayout = p;
        }
    }

    private static void positionChild(View view, int x, int y, int width, int height) {
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) view.getLayoutParams();
        if (lp.leftMargin == x && lp.topMargin == y && lp.width == width && lp.height == height) return;
        lp.gravity = Gravity.TOP | Gravity.LEFT;
        lp.leftMargin = x;
        lp.topMargin = y;
        lp.width = width;
        lp.height = height;
        view.setLayoutParams(lp);
    }

    private void retire(Window w) {
        w.cancelMotion();
        String kind = (String)w.appearance.get("transition_out");
        if (closed || !service.screenOn() || !service.overlayAllowed() || w.presented == null
                || "none".equals(kind) || !ValueAnimator.areAnimatorsEnabled()) {
            w.release();
            remove(w);
            return;
        }
        if (w.video != null) w.view.freezeVideo();
        w.release();
        Window old = retiring.put(w.slot,w);
        if (old != null) { old.cancelMotion(); remove(old); }
        w.nextLayout.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        if (w.view.spec.video != null) w.nextLayout.alpha = Math.min(w.nextLayout.alpha, .8f);
        CardMotion.Frame from = w.presented;
        CardMotion.Frame to = CardMotion.edge(from,kind,false,w.view.placement.startsWith("bottom"));
        w.play(180,new android.view.animation.AccelerateInterpolator(),t -> w.drawFrame(from.toward(to,t)), () -> {
            retiring.remove(w.slot,w);
            remove(w);
        });
    }

    private void clearRetiring() {
        for (Window w : retiring.values()) { w.cancelMotion(); remove(w); }
        retiring.clear();
    }

    private void renderChips(Point size) {
        try {
            List<Map<String, Object>> rows = chips.rows();
            if (!service.screenOn() || !service.overlayAllowed() || rows.isEmpty()) {
                clearBars();
                return;
            }
            int revision = settings.revision();
            Map<String, Object> config = settings.display();
            boolean rebuild = !rows.equals(previousChips) || revision != chipRevision || !size.equals(chipSize);
            if (rebuild) {
                groups = ChipLayout.groups(rows, (String) config.get("chip_placement"));
                pulses.update(groups, SystemClock.uptimeMillis());
                bars.entrySet().removeIf(entry -> {
                    if (groups.containsKey(entry.getKey())) return false;
                    remove(entry.getValue());
                    return true;
                });
                previousChips = rows;
                chipRevision = revision;
                chipSize.set(size.x, size.y);
            }
            for (Map.Entry<String, ChipLayout.Group> entry : groups.entrySet()) {
                String position = entry.getKey();
                ChipBar bar = bars.computeIfAbsent(position, key -> new ChipBar(service));
                if (rebuild) bar.render(entry.getValue(), settings, images, pulses, size.y, size.x);
                WindowManager.LayoutParams p = params(-2, -2, position, size);
                if (Boolean.TRUE.equals(config.get("pixel_shift"))) {
                    long phase = SystemClock.elapsedRealtime() / 120000;
                    p.x += (int)(phase % 3 - 1) * 4;
                    p.y += (int)(phase / 3 % 3 - 1) * 4;
                }
                if (bar.getParent() == null) wm.addView(bar, p);
                else {
                    WindowManager.LayoutParams old = (WindowManager.LayoutParams) bar.getLayoutParams();
                    if (old.copyFrom(p) != 0) wm.updateViewLayout(bar, old);
                }
                bar.setAlpha(CardBoard.night(config, System.currentTimeMillis()) ? .6f : 1f);
            }
        } catch (RuntimeException unavailable) { clearBars(); }
    }

    private void clearBars() {
        for (ChipBar bar : bars.values()) remove(bar);
        bars.clear();
        groups.clear();
        pulses.update(groups, SystemClock.uptimeMillis());
        previousChips = null;
    }

    private void remove(View v) {
        if (v != null && v.getParent() != null) {
            try { wm.removeViewImmediate(v); } catch (IllegalArgumentException alreadyRemoved) { }
        }
    }

    void close() {
        closed = true;
        main.removeCallbacks(tick);
        main.removeCallbacks(redraw);
        workers.shutdownNow();
        clearRetiring();
        for (Window w : windows.values()) {
            w.cancelMotion();
            w.release();
            remove(w);
        }
        windows.clear();
        for (File still : stills.values()) still.delete();
        stills.clear();
        clearBars();
    }
}
