package co.tbrkr.herald;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.widget.FrameLayout;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.net.Uri;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.PowerManager;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

/** Local, D-pad-operated controls. The service owns all settings and pairing state. */
public final class SettingsActivity extends Activity {
    private static final int TEXT = 0xffe8ecf2, MUTED = 0xff93a0b4, BLUE = 0xff3ba3e0;
    private static final String COLORS = "#4FC3F7 #FFB74D #81C784 #E57373 #BA68C8 #FFF176 #90A4AE #B0BEC5 #4DB6AC #F06292 #000000 #FFFFFF";
    private final Handler main = new Handler(android.os.Looper.getMainLooper());
    private final List<Button> tabs = new ArrayList<>();
    private LinearLayout rows;
    private TextView ntfyStatus;
    private TextView pairingStatus, pairingFingerprint;
    private final List<TextView> statusViews = new ArrayList<>();
    private int statusStart;
    private long nextStatus;
    private boolean started;
    private boolean receiving;
    private String receivingStatus;
    private TestCard testCard;
    private LookPreview liveLook;
    private View previewTile;
    private final Runnable endPreview = this::dismissLookPreview;
    private HeraldService service;
    private int section;
    private boolean rendering;
    private String category, lastPairing;
    private String editingLook, integration;
    private boolean pickingLook;
    private Auth.Client selectedClient;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        receiving = HeraldService.receiving(this);
        section = state == null ? 0 : Math.max(0, SettingsModel.SECTIONS.indexOf(state.getString("sectionName")));
        if (state != null && SettingsModel.INTEGRATIONS.contains(state.getString("integration")))
            integration = state.getString("integration");
        LinearLayout frame = new LinearLayout(this);
        frame.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xff11151c, 0xff0b0e13}));
        frame.setOrientation(LinearLayout.HORIZONTAL);
        if (Build.VERSION.SDK_INT >= 30) getWindow().setDecorFitsSystemWindows(false);
        frame.setOnApplyWindowInsetsListener((view, insets) -> {
            String window;
            int left, top, right, bottom;
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
                left = bars.left; top = bars.top; right = bars.right; bottom = bars.bottom;
                window = getWindowManager().getCurrentWindowMetrics().getBounds().flattenToString();
            } else {
                left = insets.getSystemWindowInsetLeft(); top = insets.getSystemWindowInsetTop();
                right = insets.getSystemWindowInsetRight(); bottom = insets.getSystemWindowInsetBottom();
                window = getWindow().getDecorView().getWidth() + "x" + getWindow().getDecorView().getHeight();
            }
            view.setPadding(left, top, right, bottom);
            // The emulator check (plan.md E05) compares the content bounds against these.
            android.util.Log.i(HeraldService.TAG, "settings window " + window + " insets "
                    + left + "," + top + "," + right + "," + bottom);
            return insets;
        });
        LinearLayout sections = column(frame, R.id.herald_rail, 24);
        rows = column(frame, R.id.herald_content, 76);
        LinearLayout brand = new LinearLayout(this);
        brand.setGravity(Gravity.CENTER_VERTICAL);
        sections.addView(brand);
        android.widget.ImageView mark = new android.widget.ImageView(this);
        mark.setImageResource(R.drawable.herald_mark);
        mark.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        brand.addView(mark, new LinearLayout.LayoutParams(sh(.075f), sh(.075f)));
        heading(brand, " Herald", .038f, TEXT, true);
        brand.setPadding(0, 0, 0, sh(.025f));
        for (int i = 0; i < SettingsModel.SECTIONS.size(); i++) {
            final int index = i;
            Button tab = button(sections, SettingsModel.SECTIONS.get(i));
            StateListDrawable background = new StateListDrawable();
            background.addState(new int[]{android.R.attr.state_focused}, shape(0xff254863, true));
            background.addState(new int[]{android.R.attr.state_selected}, shape(0xff293b4b, false));
            background.addState(new int[0], shape(android.graphics.Color.TRANSPARENT, false));
            tab.setBackground(background);
            tab.setFocusedByDefault(i == section);
            tabs.add(tab);
            tab.setOnFocusChangeListener((v, focus) -> {
                scale(v, focus);
                if (focus && !rendering && section != index) openSection(index, -1);
            });
            tab.setOnClickListener(v -> openSection(index, 0));
            tab.setOnKeyListener((v, key, event) -> {
                if (key != KeyEvent.KEYCODE_DPAD_RIGHT) return false;
                if (event.getAction() == KeyEvent.ACTION_DOWN) render(0);
                return true;
            });
        }
        rendering = true;
        setContentView(frame);
        tabs.get(section).requestFocus();
        rendering = false;
        if (Build.VERSION.SDK_INT >= 33) getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, this::back);
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        state.putString("sectionName", SettingsModel.SECTIONS.get(section));
        state.putString("integration", integration);
    }

    @Override public void onStart() {
        super.onStart();
        started = true;
        poll.run();
    }

    @Override public void onResume() {
        super.onResume();
        render(rows.indexOfChild(rows.getFocusedChild()));
    }

    @Override public void onStop() {
        started = false;
        dismissLookPreview();
        if (testCard != null) testCard.dismiss();
        if (pairingStatus != null) pairingStatus.setText("");
        secureCode(false);
        main.removeCallbacks(poll);
        super.onStop();
    }

    private final Runnable poll = new Runnable() {
        public void run() {
            HeraldService current = HeraldService.instance();
            boolean on = HeraldService.receiving(SettingsActivity.this);
            boolean error = HeraldService.startRefused || getSharedPreferences("herald", MODE_PRIVATE).getBoolean("start_error", false);
            String status = Setup.receivingStatus(on, HeraldService.startPending, error);
            if (current != service || on != receiving || !status.equals(receivingStatus)) {
                int focused = rows.indexOfChild(rows.getFocusedChild());
                receiving = on;
                receivingStatus = status;
                service = current;
                render(focused);
            }
            if (service != null && pairingStatus != null) {
                String paired = service.auth().pairedName();
                if (!java.util.Objects.equals(paired, lastPairing)) render(rows.hasFocus() ? 1 : -1);
                else updatePairing();
            }
            if (service != null && ntfyStatus != null) ntfyStatus.setText("Status    " + service.ntfyStatus());
            if (!statusViews.isEmpty() && android.os.SystemClock.uptimeMillis() >= nextStatus) refreshStatus();
            main.postDelayed(this, 250);
        }
    };

    @Override public void onBackPressed() { back(); }
    private void back() {
        if (liveLook != null) { dismissLookPreview(); return; }
        if (testCard != null && testCard.dismiss()) return;
        if (pickingLook) {
            pickingLook = false;
            render(0);
        } else if (editingLook != null) {
            String id = editingLook;
            editingLook = null;
            render(0);
            View tile = rows.findViewWithTag(id);
            if (tile != null) tile.requestFocus();
        } else if (category != null || selectedClient != null) {
            String previous = category == null ? selectedClient.name : category;
            category = null;
            selectedClient = null;
            render(0);
            View row = rows.findViewWithTag(previous);
            if (row != null) row.requestFocus();
        } else if (integration != null) {
            String previous = integration;
            integration = null;
            render(0);
            View link = rows.findViewWithTag(previous);
            if (link != null) link.requestFocus();
        } else if (rows.hasFocus()) {
            tabs.get(section).requestFocus();
        } else finish();
    }

    private int sh(float fraction) {
        return Math.round(getResources().getDisplayMetrics().heightPixels * fraction);
    }
    private LinearLayout column(LinearLayout frame, int id, int weight) {
        ScrollView scroll = new ScrollView(this);
        scroll.setId(id);
        scroll.setFocusable(false);
        if (id == R.id.herald_rail) scroll.setBackgroundColor(0xff19232d);
        frame.addView(scroll, new LinearLayout.LayoutParams(0, -1, weight));
        LinearLayout column = new LinearLayout(this);
        column.setPadding(sh(.034f), sh(.038f), sh(.034f), sh(.03f));
        column.setClipToPadding(false);
        // Let focus outlines draw into the column padding.
        column.setClipChildren(false);
        column.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(column);
        return column;
    }
    private GradientDrawable shape(int fill, boolean stroke) {
        GradientDrawable draw = new GradientDrawable();
        draw.setColor(fill);
        draw.setCornerRadius(sh(.012f));
        if (stroke) draw.setStroke(2, BLUE);
        return draw;
    }
    private void scale(View view, boolean focus) {
        float size = focus ? 1.012f : 1f;
        view.animate().scaleX(size).scaleY(size).setDuration(150).start();
    }
    private void styleText(TextView view) {
        view.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        view.setTextColor(TEXT);
        view.setTextSize(TypedValue.COMPLEX_UNIT_PX, sh(.023f));
        view.setSingleLine(true);
        view.setEllipsize(android.text.TextUtils.TruncateAt.END);
    }

    private void styleRow(View view) {
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{android.R.attr.state_focused}, shape(0x293ba3e0, true));
        states.addState(new int[0], shape(0xff1b222c, false));
        view.setBackground(states);
        view.setStateListAnimator(null);
        view.setElevation(0);
        view.setPadding(sh(.02f), 0, sh(.02f), 0);
        view.setFocusable(true);
        view.setOnFocusChangeListener(this::scale);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, sh(.055f));
        params.topMargin = sh(.006f);
        view.setLayoutParams(params);
    }
    private TextView heading(LinearLayout parent, String text, float size, int color, boolean bold) {
        TextView label = new TextView(this);
        label.setText(text);
        label.setTextColor(color);
        label.setTextSize(TypedValue.COMPLEX_UNIT_PX, sh(size));
        if (bold) label.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        parent.addView(label);
        return label;
    }
    private Button button(LinearLayout parent, String label) {
        Button b = new Button(this);
        styleText(b);
        styleRow(b);
        b.setText(label);
        b.setAllCaps(false);
        parent.addView(b);
        return b;
    }
    private TextView line(String text) {
        TextView label = new TextView(this);
        styleText(label);
        styleRow(label);
        label.setTextColor(MUTED);
        // Keep long read-only pages scrollable with the remote.
        StateListDrawable background = new StateListDrawable();
        background.addState(new int[]{android.R.attr.state_focused}, shape(0xff254863, true));
        background.addState(new int[0], shape(android.graphics.Color.TRANSPARENT, false));
        label.setBackground(background);
        label.setText(text);
        rows.addView(label);
        return label;
    }
    private void feedback(String text) { Toast.makeText(this, text, Toast.LENGTH_LONG).show(); }
    private void act(Runnable action) {
        try { action.run(); }
        catch (Settings.Invalid e) {
            if ("id".equals(e.field)) feedback("Use 1–32 lowercase letters, digits or underscores.");
            else if ("compat.tvoverlay.allowed".equals(e.field)) feedback("Enter a trusted sender IP address or network range.");
            else feedback("Check the value for " + SettingsModel.fieldLabel(e.field.substring(e.field.lastIndexOf('.') + 1)) + ".");
        }
        catch (Api.Failure e) {
            String reason = e.getMessage();
            if ("pairing in progress".equals(reason)) reason = "A pairing is in progress";
            if ("try later".equals(reason)) reason = "Try again in a minute";
            feedback(reason);
        }
    }
    private Button action(String label, Runnable run) {
        Button b = button(rows, label);
        b.setOnClickListener(v -> act(run));
        return b;
    }
    private void confirm(String title, String message, String accept, Runnable apply) {
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle(title).setMessage(message)
                .setNegativeButton("Cancel", null)
                .setPositiveButton(accept, (d, w) -> act(apply)).show();
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).requestFocus();
    }

    private void edit(String label, String value, boolean secret, Consumer<String> save) {
        EditText text = new EditText(this);
        text.setSingleLine();
        if (secret) {
            text.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
            text.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
            text.setSaveEnabled(false);
        }
        text.setText(value);
        text.setSelectAllOnFocus(true);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle(label).setView(text)
                .setNegativeButton("Cancel", null).setPositiveButton("Save",
                        (d,w) -> act(() -> save.accept(text.getText().toString()))).create();
        if (secret) dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        dialog.show();
        text.requestFocus();
    }

    private void openSection(int index, int focus) {
        if (index != section) {
            section = index;
            category = editingLook = integration = null;
            selectedClient = null;
            pickingLook = false;
        }
        render(focus);
    }

    private static final Object OPTIONS_TAG = new Object();

    private String optionsKey() {
        if (integration != null) return "integration." + integration;
        String page = SettingsModel.SECTIONS.get(section);
        return editingLook != null || category != null ? page + ".editor" : page;
    }

    private boolean expanded() {
        return getSharedPreferences("settings_ui", MODE_PRIVATE).getBoolean(optionsKey(), false);
    }

    private boolean moreOptions() {
        String key = optionsKey();
        boolean open = expanded();
        String noun = key.equals("Status") ? "details" : "options";
        Button toggle = action((open ? "Fewer " : "More ") + noun + (open ? "  ▴" : "  ▾"), () -> {
            getSharedPreferences("settings_ui", MODE_PRIVATE).edit().putBoolean(key, !open).apply();
            render(-1);
            View replacement = rows.findViewWithTag(OPTIONS_TAG);
            if (replacement != null) replacement.requestFocus();
        });
        toggle.setFocusableInTouchMode(true);
        toggle.setTag(OPTIONS_TAG);
        toggle.setTextColor(0xff9fd8f8);
        toggle.setContentDescription("More " + noun + (open ? ", expanded" : ", collapsed"));
        return open;
    }

    private void render(int focus) {
        rendering = true;
        try { populate(focus); } finally { rendering = false; }
    }

    private void populate(int focus) {
        rows.removeAllViews();
        ntfyStatus = null;
        pairingStatus = null;
        statusViews.clear();
        String name = SettingsModel.SECTIONS.get(section);
        for (int i = 0; i < tabs.size(); i++) {
            tabs.get(i).setSelected(i == section);
            tabs.get(i).setFocusedByDefault(i == section);
        }
        TextView title = heading(rows, integration == null ? name : integration, .04f, TEXT, true);
        title.setPadding(0, 0, 0, sh(.013f));
        if (!Setup.available(name, receiving)) line("Start receiving to change this.");
        else if ("Setup".equals(name)) setup();
        else if ("About".equals(name)) diagnostics();
        else if (service == null) line("On".equals(receivingStatus) ? "Open Setup to start receiving." : receivingStatus);
        else switch (name) {
            case "Looks":
                if (editingLook == null) lookGrid();
                else customLook();
                break;
            case "Categories": categories(); break;
            case "Display":
                groupHeading("Cards");
                group("display", "max_visible", "night_start", "night_end");
                groupHeading("Status chips");
                group("display", "chip_look", "chip_placement");
                if (moreOptions()) group("display", "pixel_shift", "sticky_cap", "focus_while_playing");
                break;
            case "Sound & voice":
                group("audio", "volume_tv", "volume_speaker", "chime");
                if (!moreOptions()) break;
                group("audio", "speaker_at_night");
                voiceTest("TV", "tv");
                voiceTest("box speaker", "speaker");
                action("Chime test", () -> service.testChime((int)AnnounceSpec.audioSettings(service.settings()).get("volume_tv")));
                break;
            case "Pairing & clients": pairing(); break;
            case "Integrations": integrations(); break;
            case "Status":
                statusStart = rows.getChildCount();
                refreshStatus();
                moreOptions();
                break;
        }
        if (pairingStatus == null) secureCode(false);
        if (focus >= 0) {
            for (int i = Math.min(focus, rows.getChildCount()-1); i < rows.getChildCount() && i >= 0; i++) {
                if (rows.getChildAt(i).requestFocus()) break;
            }
        }
    }

    private void groupHeading(String title) {
        TextView heading = heading(rows, title, .019f, MUTED, true);
        heading.setPadding(0, sh(.013f), 0, sh(.003f));
    }

    private void integrations() {
        if (integration == null) {
            Map<String,Object> settings = service.settings().document();
            for (String name : SettingsModel.INTEGRATIONS) {
                Map<String,Object> config = "TvOverlay".equals(name) ? Compat.config(settings)
                        : Settings.object(settings.get(name.toLowerCase(Locale.ROOT)), name);
                LinearLayout link = valueRow(name, Boolean.TRUE.equals(config.get("enabled")) ? "On  ›" : "Off  ›",
                        SettingsModel.help(name));
                link.setOnClickListener(v -> { integration = name; render(0); });
                link.setTag(name);
            }
        } else if ("MQTT".equals(integration)) {
            group("mqtt", "enabled", "host", "port", "tls", "username", "password_set");
            if (moreOptions()) group("mqtt", "base_topic", "discovery", "discovery_prefix", "commands");
        } else if ("ntfy".equals(integration)) ntfy();
        else compatibility();
    }

    private void voiceTest(String label, String output) {
        action("Voice test (" + label + ")", () -> test("announce", Json.fields(
                "text", "This is the Herald voice test.", "output", output)));
    }
    private void test(String cmd, Map<String,Object> body) {
        service.command(cmd, Json.stringify(body).getBytes(StandardCharsets.UTF_8));
    }
    private static String label(String name) {
        return name.substring(0, 1).toUpperCase(Locale.ROOT) + name.substring(1);
    }

    private Map<String,Object> lookCard(Look look) {
        return Json.fields("id", "herald-look-test", "title", label(look.name) + " look",
                "message", "This is the " + look.name.toLowerCase(Locale.ROOT) + " look.",
                "look", look.id, "style", Json.fields("duration", 8));
    }

    private LookPreview preview(Look look, boolean thumbnail) {
        Map<String,Object> body = lookCard(look);
        body.put("title", "Front door");
        body.put("message", "A package has arrived.");
        body.put("icon", "info");
        return new LookPreview(this, new CardSpec(body, service.settings()), thumbnail);
    }

    private void showLookPreview(Look look, View tile) {
        dismissLookPreview();
        previewTile = tile;
        liveLook = preview(look, false);
        FrameLayout content = findViewById(android.R.id.content);
        content.addView(liveLook, new FrameLayout.LayoutParams(-1, -1));
        liveLook.setContentDescription(look.name + " preview. Back or OK to close.");
        liveLook.setFocusableInTouchMode(true);
        liveLook.setOnClickListener(v -> dismissLookPreview());
        liveLook.setOnKeyListener((v, key, event) -> {
            if (key == KeyEvent.KEYCODE_DPAD_CENTER || key == KeyEvent.KEYCODE_ENTER ||
                    key == KeyEvent.KEYCODE_NUMPAD_ENTER) {
                if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) dismissLookPreview();
                return true;
            }
            return key == KeyEvent.KEYCODE_DPAD_LEFT || key == KeyEvent.KEYCODE_DPAD_RIGHT ||
                    key == KeyEvent.KEYCODE_DPAD_UP || key == KeyEvent.KEYCODE_DPAD_DOWN;
        });
        liveLook.requestFocus();
        main.postDelayed(endPreview, 8000);
    }

    private void dismissLookPreview() {
        main.removeCallbacks(endPreview);
        if (liveLook == null) return;
        // Removing the focused overlay can send focus to the first sidebar item.
        // Restore the source tile before Android searches for another destination.
        if (previewTile.isAttachedToWindow()) previewTile.requestFocusFromTouch();
        ((FrameLayout)liveLook.getParent()).removeView(liveLook);
        liveLook = null;
        previewTile = null;
    }

    private void lookGrid() {
        if (!pickingLook) heading(rows, "OK to preview · Hold OK to assign", .019f, MUTED, false);
        Settings settings = service.settings();
        int gap = Math.round(.012f * getResources().getDisplayMetrics().widthPixels);
        synchronized (settings) {
            Map<Look,List<String>> tiles = SettingsModel.lookTiles(settings);
            LinearLayout strip = null;
            int column = 0;
            for (Map.Entry<Look,List<String>> entry : tiles.entrySet()) {
                if (column == 0) {
                    strip = new LinearLayout(this);
                    strip.setClipChildren(false);
                    LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
                    params.topMargin = gap;
                    rows.addView(strip, params);
                }
                Look look = entry.getKey();
                LinearLayout tile = new LinearLayout(this);
                tile.setOrientation(LinearLayout.VERTICAL);
                tile.setDescendantFocusability(LinearLayout.FOCUS_BLOCK_DESCENDANTS);
                tile.setFocusable(true);
                tile.setTag(look.id);
                StateListDrawable states = new StateListDrawable();
                states.addState(new int[]{android.R.attr.state_focused}, shape(0x293ba3e0, true));
                states.addState(new int[0], shape(0xff1b222c, false));
                tile.setBackground(states);
                int inset = sh(.006f);
                tile.setPadding(inset, inset, inset, inset);
                tile.setOnFocusChangeListener(this::scale);
                String categories = String.join(", ", entry.getValue());
                tile.setContentDescription(label(look.name) + (categories.isEmpty() ? "" : " · " + categories));
                tile.addView(preview(look, true), new LinearLayout.LayoutParams(-1, -2));
                for (String caption : Arrays.asList(label(look.name), categories)) {
                    boolean title = tile.getChildCount() == 1;
                    TextView text = heading(tile, caption, title ? .023f : .017f, title ? TEXT : MUTED, title);
                    text.setSingleLine();
                    text.setPadding(sh(.012f), 0, sh(.012f), 0);
                    text.setEllipsize(android.text.TextUtils.TruncateAt.END);
                }
                tile.setOnClickListener(v -> act(() -> {
                    if (pickingLook) {
                        settings.putCategory(category, Json.fields("look", look.id));
                        pickingLook = false;
                        render(0);
                    } else showLookPreview(look, tile);
                }));
                if (!pickingLook) {
                    tile.setOnLongClickListener(v -> { assignLook(look); return true; });
                    tile.setOnKeyListener(new View.OnKeyListener() {
                        boolean held;
                        public boolean onKey(View view, int key, KeyEvent event) {
                            if (key != KeyEvent.KEYCODE_DPAD_CENTER && key != KeyEvent.KEYCODE_ENTER
                                    && key != KeyEvent.KEYCODE_NUMPAD_ENTER) return false;
                            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                                if (event.getRepeatCount() == 0) held = false;
                                if (event.isLongPress() && !held) {
                                    held = true;
                                    view.performLongClick();
                                }
                            } else if (event.getAction() == KeyEvent.ACTION_UP && !held && !event.isCanceled())
                                view.performClick();
                            return true;
                        }
                    });
                }
                addGridCell(strip, tile, column, gap);
                column = (column + 1) % 3;
            }
            while (column != 0) {
                addGridCell(strip, new View(this), column, gap);
                column = (column + 1) % 3;
            }
            if (pickingLook || !moreOptions()) return;
            action("New look", this::newLook);
            for (Look look : tiles.keySet()) if (Look.builtin(look.id) == null)
                action("Edit " + look.name, () -> { editingLook = look.id; render(0); });
        }
    }

    private void addGridCell(LinearLayout strip, View cell, int column, int gap) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -2, 1);
        params.leftMargin = column == 0 ? 0 : gap;
        strip.addView(cell, params);
    }

    private void assignLook(Look look) {
        Settings settings = service.settings();
        List<Map<String,Object>> categories = settings.categories();
        String[] names = new String[categories.size()];
        for (int i = 0; i < names.length; i++) names[i] = (String)categories.get(i).get("name");
        new AlertDialog.Builder(this).setTitle("Use for category…").setItems(names, (dialog, i) -> act(() -> {
            String id = (String)categories.get(i).get("id");
            synchronized (settings) {
                if (!settings.categoryId(id).equals(id)) { feedback("Category no longer exists"); return; }
                settings.putCategory(id, Json.fields("look", look.id));
            }
            render(0);
            rows.findViewWithTag(look.id).requestFocus();
        })).show();
    }

    private void newLook() {
        String[] ids = Look.BUILTIN_IDS.toArray(new String[0]);
        new AlertDialog.Builder(this).setTitle("Copy a built-in look").setItems(ids, (dialog, i) ->
                edit("New look name", "", false, name -> {
                    Settings settings = service.settings();
                    synchronized (settings) {
                        Set<String> taken = new HashSet<>();
                        for (Map<String,Object> row : settings.looks()) taken.add((String)row.get("id"));
                        String id = SettingsModel.newLookId(name, taken);
                        settings.putLook(id, Json.fields("name", name, "style", Look.builtin(ids[i]).style));
                        editingLook = id;
                    }
                    render(0);
                })).show();
    }

    private void previewButtons(Map<String, Object> card) {
        card.put("buttons", Arrays.asList(Json.fields("id", "okay", "label", "OK", "action", "dismiss"),
                Json.fields("id", "close", "label", "Close", "action", "dismiss")));
        test("card", card);
    }

    private void customLook() {
        Settings settings = service.settings();
        String id = editingLook;
        Look look = settings.look(id);
        if (look == null) { editingLook = null; render(0); return; }
        heading(rows, look.name, .028f, TEXT, true);
        action("Preview buttons", () -> previewButtons(lookCard(look)));
        lookFields(settings, look, "background", "opacity", "corner", "button_shape", "button_spacing");
        if (!moreOptions()) return;
        lookFields(settings, look, "border_color", "border", "shadow", "glow", "accent_style", "title_weight",
                "button_background", "button_text_color", "button_focus_background", "button_focus_text_color");
        action("Delete Look", () -> confirm("Delete " + look.name + "?", "Categories using this Look will use their fallback.",
                "Delete", () -> { settings.deleteLook(id); editingLook = null; render(0); }));
    }

    private void lookFields(Settings settings, Look look, String... fields) {
        for (String key : fields) {
            Object value = look.style.getOrDefault(key, Look.BASE.get(key));
            field(key, value, next -> {
                synchronized (settings) {
                    Look current = settings.look(look.id);
                    if (current == null) throw new Api.Failure(404, "unknown look");
                    Map<String,Object> style = new LinkedHashMap<>(current.style);
                    style.put(key, next);
                    settings.putLook(look.id, Json.fields("name", current.name, "style", style));
                }
            }, choices(key, value));
        }
    }

    private void categories() {
        Settings settings = service.settings();
        for (Map<String,Object> item : settings.categories()) {
            String id = (String)item.get("id");
            if (category == null) {
                String name = item.get("name").toString();
                String label = name.equalsIgnoreCase(id) ? name : name + " (" + id + ")";
                action(label, () -> { category = id; render(0); }).setTag(id);
            } else if (category.equals(id)) {
                if (pickingLook) { lookGrid(); return; }
                heading(rows, (String)item.get("name"), .028f, TEXT, true);
                Look assigned = settings.look((String)item.get("look"));
                action("Look    " + (assigned == null ? item.get("look") : label(assigned.name)), () -> { pickingLook = true; render(0); });
                field("Speak cards aloud", item.get("announce"), value -> settings.putCategory(id, Json.fields("announce", value)),
                        Arrays.asList(false, true));
                Map<String,Object> effective = Settings.object(item.get("effective"), "effective");
                action("Preview buttons", () -> previewButtons(Json.fields("title", item.get("name") + " test",
                        "category", id, "style", Json.fields("duration", 8))));
                action("Send test", () -> test("card", Json.fields("category", id, "title", item.get("name") + " test",
                        "message", "This is a test card.")));
                categoryFields(settings, id, effective, "placement", "size", "duration", "button_shape", "button_spacing");
                if (!moreOptions()) return;
                categoryFields(settings, id, effective, "accent", "corner", "border", "opacity", "button_background",
                        "button_text_color", "button_focus_background", "button_focus_text_color");
                action("Reset to default", () -> confirm("Reset " + item.get("name") + "?",
                        "Restore its Look, speech and appearance defaults.", "Reset", () -> {
                    synchronized (settings) {
                        Map<String,Object> removals = Json.fields();
                        for (Map<String,Object> current : settings.categories()) {
                            if (id.equals(current.get("id")))
                                for (String key : Settings.object(current.get("style"), "style").keySet()) removals.put(key, null);
                        }
                        Category base = Category.base(id);
                        settings.putCategory(id, Json.fields("look", base.look, "announce", base.announce, "style", removals));
                    }
                    render(0);
                }));
                if (!Category.isBuiltin(id)) action("Delete category", () -> confirm("Delete " + item.get("name") + "?",
                        "Remove this custom category and its settings.", "Delete",
                        () -> { settings.deleteCategory(id); category = null; render(0); }));
                return;
            }
        }
        if (category != null) { category = null; render(0); return; }
        if (!moreOptions()) return;
        action("Add category", () -> edit("New category ID", "", false, id -> {
            synchronized (settings) {
                if (settings.categoryId(id).equals(id)) { feedback("Category already exists"); return; }
                settings.putCategory(id, Json.fields("name", id));
            }
            category = id;
            render(0);
        }));
    }
    private void categoryFields(Settings settings, String id, Map<String,Object> effective, String... keys) {
        for (String key : keys) {
            Object value = effective.get(key);
            List<Object> options = key.equals("border") ? Arrays.asList("none", "thin") : choices(key, value);
            field(key, value, next -> settings.putCategory(id, Json.fields("style", Json.fields(key, next))), options);
        }
    }

    private void group(String group, String... keys) {
        Map<String,Object> values = Settings.object(service.settings().document().get(group), group);
        for (String key : keys) {
            if (key.equals("password_set")) {
                String label = (boolean)values.get(key) ? "Password (set)" : "Password (unset)";
                int row = rows.getChildCount();
                action(label, () -> edit("MQTT password", "", true, value -> {
                    service.changeSettings(Json.fields("mqtt", Json.fields("password", value)));
                    render(row);
                }));
            } else field(key, values.get(key), value -> service.changeSettings(Json.fields(group, Json.fields(key, value))));
        }
    }

    private void ntfySetting(String key, Object value) {
        service.changeSettings(Json.fields("ntfy", Json.fields(key, value)));
    }

    private void ntfy() {
        Settings settings = service.settings();
        Map<String,Object> group = Settings.object(settings.document().get("ntfy"), "ntfy");
        field("Enabled", group.get("enabled"), value -> ntfySetting("enabled", value));
        field("Server", group.get("server"), value -> ntfySetting("server", value));
        boolean hasToken = Boolean.TRUE.equals(group.get("token_set"));
        action("Token    " + (hasToken ? "set" : "unset"), () -> edit("Token", "", true, value -> {
            synchronized (settings) {
                if (!group.get("server").equals(settings.ntfyPrivate().get("server"))) {
                    feedback("Server changed. Reopen Token to enter it for the new server.");
                } else ntfySetting("token", value);
            }
            render(0);
        }));
        if (hasToken) action("Clear token", () -> { ntfySetting("token", null); render(0); });

        ntfyStatus = line("Status    " + service.ntfyStatus());
        List<Object> categories = new ArrayList<>(), looks = new ArrayList<>();
        looks.add("Use category look");
        for (Map<String,Object> row : settings.categories()) categories.add(row.get("id"));
        for (Map<String,Object> row : settings.looks()) looks.add(row.get("id"));
        for (Map<String,Object> topic : Settings.topics(group)) {
            String name = (String)topic.get("topic");
            field("Topic", name, value -> editTopic(name, "topic", value));
            field("Label", topic.get("label"), value -> editTopic(name, "label", value));
            field("Category", topic.get("category"), value -> editTopic(name, "category", value), categories);
            Object look = topic.get("look");
            field("Look", look == null ? looks.get(0) : look,
                    value -> editTopic(name, "look", value.equals(looks.get(0)) ? null : value), looks);
            field("Speak priority 5", topic.get("speak"), value -> editTopic(name, "speak", value));
            action("Remove topic", () -> { editTopic(name, null, null); render(0); });
        }
        if (Settings.topics(group).size() < 4) action("Add topic", () -> edit("Topic", "", false, name -> {
            editTopic(null, "topic", name);
            render(0);
        }));
        if (moreOptions()) field("Catch-up (seconds)", group.get("catch_up"), value -> ntfySetting("catch_up", value));
    }

    private void editTopic(String name, String key, Object value) {
        Settings settings = service.settings();
        synchronized (settings) {
            List<Map<String,Object>> list = new ArrayList<>(Settings.topics(settings.ntfyPrivate()));
            if (name == null) list.add(Json.fields(key, value));
            else {
                int i = 0;
                while (i < list.size() && !name.equals(list.get(i).get("topic"))) i++;
                if (i == list.size()) { feedback("Topic changed"); return; }
                if (key == null) list.remove(i);
                else {
                    Map<String,Object> row = new LinkedHashMap<>(list.get(i));
                    row.put(key, value);
                    list.set(i, row);
                }
            }
            ntfySetting("topics", list);
        }
    }

    private void compatibility() {
        Settings settings = service.settings();
        Map<String,Object> cfg = Compat.config(settings.document());
        List<String> senders = new ArrayList<>();
        for (Object address : (List<?>)cfg.get("allowed")) senders.add((String)address);
        field("Allowed senders (comma-separated)", String.join(", ", senders), value -> {
            List<String> allowed = new ArrayList<>();
            String raw = ((String)value).trim();
            if (!raw.isEmpty()) {
                for (String part : raw.split(",", -1)) allowed.add(part.trim());
            }
            saveCompat("allowed", allowed);
        });
        field("enabled", cfg.get("enabled"), value -> {
            if (Boolean.TRUE.equals(value) && senders.isEmpty()) feedback("Add an allowed sender first.");
            else saveCompat("enabled", value);
        });
        if (!moreOptions()) return;
        field("port", cfg.get("port"), value -> saveCompat("port", value));
        List<Object> ids = new ArrayList<>();
        for (Map<String,Object> item : settings.categories()) ids.add(item.get("id"));
        field("category", cfg.get("category"), value -> saveCompat("category", value), ids);
    }

    private void saveCompat(String key, Object value) {
        service.changeSettings(Json.fields("compat", Json.fields("tvoverlay", Json.fields(key, value))));
    }

    private void pairing() {
        Auth auth = service.auth();
        if (selectedClient != null) {
            line(selectedClient.name);
            Auth.Client client = selectedClient;
            action("Remove client", () -> confirm("Remove " + client.name + "?", "This client will need to pair again.",
                    "Remove", () -> { auth.remove(client); selectedClient = null; render(0); }));
            return;
        }
        if (!service.settings().pairingAllowed()) group("pairing", "allowed");
        action("Pair an app or script", () -> { auth.startTv(); updatePairing(); });
        pairingStatus = line("");
        pairingStatus.setSaveEnabled(false);
        pairingFingerprint = line("");
        pairingFingerprint.setSingleLine(false);
        pairingFingerprint.setLayoutParams(new LinearLayout.LayoutParams(-1, -2));
        lastPairing = auth.pairedName();
        updatePairing();
        for (Auth.Client c : auth.localClients())
            action(c.name, () -> { selectedClient = c; render(0); }).setTag(c.name);
        if (service.settings().pairingAllowed() && moreOptions()) group("pairing", "allowed");
    }
    private void updatePairing() {
        Auth auth = service.auth();
        String code = started ? auth.tvCode() : null, paired = auth.pairedName();
        // A TV-started code stays valid until it times out, also after leaving this screen (operator, D23).
        if (code != null) secureCode(true);
        String fingerprint = service.certSha256();
        String identity = fingerprint.isEmpty() ? "" : "Fingerprint " + TlsIdentity.grouped(fingerprint);
        String state = code != null ? "Pairing code  " + code : paired == null ? "" : "Paired with " + paired;
        pairingStatus.setVisibility(state.isEmpty() ? View.GONE : View.VISIBLE);
        pairingFingerprint.setVisibility(identity.isEmpty() ? View.GONE : View.VISIBLE);
        if (!state.contentEquals(pairingStatus.getText())) pairingStatus.setText(state);
        if (!identity.contentEquals(pairingFingerprint.getText())) pairingFingerprint.setText(identity);
        if (code == null) secureCode(false);
    }
    private void secureCode(boolean secure) {
        int flag = WindowManager.LayoutParams.FLAG_SECURE;
        if (secure == ((getWindow().getAttributes().flags & flag) != 0)) return;
        if (secure) getWindow().addFlags(flag);
        else getWindow().clearFlags(flag);
    }

    private List<String> addresses() {
        List<String> result = new ArrayList<>();
        ConnectivityManager network = getSystemService(ConnectivityManager.class);
        LinkProperties link = network.getLinkProperties(network.getActiveNetwork());
        if (link != null) for (LinkAddress address : link.getLinkAddresses()) {
            String host = address.getAddress().getHostAddress();
            if (host.contains(":")) host = "[" + host + "]";
            result.add(host + ":" + service.settings().port());
        }
        return result;
    }

    private void refreshStatus() {
        List<String> clients = new ArrayList<>();
        for (Auth.Client c : service.auth().localClients()) clients.add(c.name);
        List<String> values = SettingsModel.statusRows(service.settingsStatus(), clients, addresses(), expanded());
        // Only the address group varies in length. Keep the other row views and their focus.
        if (statusViews.isEmpty()) {
            for (String value : values) statusViews.add(line(value));
        } else {
            while (statusViews.size() < values.size()) {
                TextView address = new TextView(this);
                styleText(address);
                styleRow(address);
                statusViews.add(1, address);
                rows.addView(address, statusStart + 1);
            }
            while (statusViews.size() > values.size()) {
                if (statusViews.get(1).hasFocus()) statusViews.get(2).requestFocus();
                rows.removeView(statusViews.remove(1));
            }
            for (int i = 0; i < values.size(); i++)
                if (!values.get(i).contentEquals(statusViews.get(i).getText())) statusViews.get(i).setText(values.get(i));
        }
        nextStatus = android.os.SystemClock.uptimeMillis() + 1000;
    }

    private void setup() {
        boolean overlay = android.provider.Settings.canDrawOverlays(this);
        boolean exempt = getSystemService(PowerManager.class).isIgnoringBatteryOptimizations(getPackageName());
        Uri pkg = Uri.parse("package:" + getPackageName());
        Intent plain = new Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION);
        Intent specific = new Intent(plain).setData(pkg);
        Intent details = new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg);
        Intent battery = new Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS);
        Setup.Target target = Setup.overlayTarget(resolves(specific), resolves(plain), resolves(details));
        Intent overlayIntent = target == Setup.Target.PACKAGE_OVERLAY ? specific
                : target == Setup.Target.OVERLAY ? plain : target == Setup.Target.APP_DETAILS ? details : null;
        permissionRow("1. Show cards over other apps", overlay, overlayIntent, Setup.OVERLAY_ADB);
        action("2. Test card    " + Setup.testCardStatus(overlay), () -> {
            if (!android.provider.Settings.canDrawOverlays(this)) { feedback(Setup.testCardStatus(false)); return; }
            if (testCard != null) testCard.dismiss();
            testCard = TestCard.show(this);
        });
        if (receivingStatus != null && !"On".equals(receivingStatus) && !"Off".equals(receivingStatus))
            line("Receiving    " + receivingStatus);
        if (!receiving || (service == null && !HeraldService.startPending)) {
            action("3. Start receiving", () -> {
                Runnable start = () -> {
                    HeraldService.requestStart(this, HeraldService.receiving(this));
                    refreshSetup();
                };
                if (android.provider.Settings.canDrawOverlays(this)) start.run();
                else confirm("Start without TV cards?", "Audio can play. Allow Show cards over other apps to display cards and pairing codes.", "Start", start);
            });
        }
        if (receiving) action("Stop receiving", () -> {
            getSharedPreferences("herald", MODE_PRIVATE).edit().putBoolean("receiving", false).remove("start_error").commit();
            HeraldService.startRefused = false;
            HeraldService.startPending = false;
            if (HeraldService.instance() != null) startService(new Intent(this, HeraldService.class).setAction(HeraldService.STOP));
            refreshSetup();
        });
        if (receiving && service != null && "On".equals(receivingStatus)) {
            List<String> hosts = addresses();
            if (hosts.isEmpty()) line("Connect this TV to your network to pair Home Assistant.");
            else {
                String address = hosts.get(0);
                for (String host : hosts) if (!host.startsWith("[")) { address = host; break; }
                line("Receiving at " + address);
                if (overlay) line("Next: add this TV in Home Assistant → Devices & services.");
                else line("Allow cards over other apps to pair Home Assistant.");
            }
        }
        privacyRow();
        if (moreOptions()) permissionRow("Battery (optional)", exempt,
                Setup.batteryTarget(resolves(battery)) == Setup.Target.BATTERY ? battery : null, Setup.BATTERY_ADB);
    }

    private void refreshSetup() {
        main.removeCallbacks(poll);
        poll.run();
    }

    private boolean resolves(Intent intent) { return intent.resolveActivity(getPackageManager()) != null; }

    private void permissionRow(String label, boolean granted, Intent intent, String fallback) {
        String status = Setup.BATTERY_ADB.equals(fallback) ? (granted ? "Exempt" : "Not exempt") : Setup.grantStatus(granted);
        String title = label + "    " + status;
        Button row = button(rows, title);
        row.setOnClickListener(v -> {
            if (intent != null) {
                try { startActivity(intent); return; }
                catch (android.content.ActivityNotFoundException unavailable) { /* Show the ADB instructions. */ }
            }
            row.setSingleLine(false);
            row.setText(title + "\n" + fallback);
            row.getLayoutParams().height = LinearLayout.LayoutParams.WRAP_CONTENT;
            row.requestLayout();
        });
    }

    private void privacyRow() {
        action("Privacy", () -> new AlertDialog.Builder(this).setTitle("Privacy").setMessage(Setup.PRIVACY)
                .setPositiveButton("Close", null).show());
    }

    private void diagnostics() {
        line("Herald " + HeraldService.VERSION);
        privacyRow();
        if (!receiving || service == null) return;
        group("device", "name");
        if (!moreOptions()) return;
        group("device", "port");
        line("Device ID  " + service.deviceId());
        List<String> addresses = addresses();
        if (addresses.isEmpty()) line("No active network");
        for (String address : addresses) line("Address  " + address);
        if (service.assignedName() != null) line("Discovery name  " + service.assignedName());
        boolean overlay = service.overlayAllowed();
        line("Overlay permission  " + (overlay ? "allowed" : "missing"));
        boolean exempt = getSystemService(PowerManager.class).isIgnoringBatteryOptimizations(getPackageName());
        line("Battery exemption  " + (exempt ? "Exempt" : "Not exempt"));
        line("Media3  " + Libs.media3());
        EventLog log = service.events();
        List<?> recent = (List<?>)log.await(Math.max(0, log.latest()-20), 0).get("events");
        line("Recent events");
        for (int i = Math.max(0, recent.size()-20); i < recent.size(); i++) line(Json.stringify(recent.get(i)));
        action("Refresh diagnostics", () -> render(rows.getChildCount()-1));
    }

    private LinearLayout valueRow(String title, String display, String help) {
        LinearLayout row = new LinearLayout(this) {
            @Override public CharSequence getAccessibilityClassName() { return Button.class.getName(); }
        };
        styleRow(row);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.setPadding(0, sh(.007f), sh(.018f), sh(.007f));
        row.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
        TextView name = heading(labels, title, .023f, TEXT, false);
        styleText(name);
        if (help != null) heading(labels, help, .018f, MUTED, false);
        labels.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        row.getLayoutParams().height = LinearLayout.LayoutParams.WRAP_CONTENT;
        row.setMinimumHeight(sh(.055f));
        TextView current = heading(row, display, .023f, TEXT, false);
        styleText(current);
        current.setTextColor(0xffb5d8eb);
        current.setMaxWidth(Math.round(.38f * getResources().getDisplayMetrics().widthPixels));
        row.setContentDescription(title + ", " + display + (help == null ? "" : ". " + help));
        row.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        name.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        current.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        rows.addView(row);
        return row;
    }

    private void field(String key, Object value, Consumer<Object> put) {
        field(key, value, put, choices(key, value));
    }

    private void field(String key, Object value, Consumer<Object> put, List<Object> choices) {
        String title = SettingsModel.fieldLabel(key);
        String display = SettingsModel.fieldValue(key, value);
        if (choices != null && value instanceof String && choices.contains(value)
                && display.equals(value) && display.matches("[a-z][a-z_]*")) display = label(display.replace('_', ' '));
        int index = rows.getChildCount();
        Consumer<Object> save = next -> { put.accept(next); render(index); };
        LinearLayout row = valueRow(title, display, SettingsModel.help(key));
        if (choices != null && value instanceof String && ((String)value).matches("#[a-fA-F0-9]{6}")) {
            View swatch = new View(this);
            GradientDrawable fill = shape(android.graphics.Color.parseColor((String)value), false);
            fill.setStroke(1, 0xffb5bdc5);
            swatch.setBackground(fill);
            LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(sh(.026f), sh(.026f));
            size.setMarginEnd(sh(.012f));
            row.addView(swatch, row.getChildCount() - 1, size);
        }
        IntConsumer step = dir -> {
            if (choices == null) return;
            int pos = choices.indexOf(value);
            if (pos >= 0) pos = Math.floorMod(pos + dir, choices.size());
            else if (value instanceof Number || key.startsWith("night_")) {
                // Custom API values (e.g. 12 seconds) move to the next available step.
                pos = dir > 0 ? 0 : choices.size()-1;
                for (int i = 0; i < choices.size(); i++) {
                    Object option = choices.get(i);
                    int cmp = value instanceof Number ? Integer.compare((int)option, ((Number)value).intValue())
                            : option.toString().compareTo(value.toString());
                    if (cmp * dir > 0) { pos = i; if (dir > 0) break; }
                }
            } else pos = dir > 0 ? 0 : choices.size()-1;
            save.accept(choices.get(pos));
        };
        row.setOnKeyListener((v,k,e) -> {
            if (choices == null || (k != KeyEvent.KEYCODE_DPAD_LEFT && k != KeyEvent.KEYCODE_DPAD_RIGHT)) return false;
            if (e.getAction() == KeyEvent.ACTION_DOWN) act(() -> step.accept(k == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 1));
            return true;
        });
        row.setOnClickListener(v -> act(() -> {
            if (choices != null && !key.equals("sound")) { step.accept(1); return; }
            edit(title, String.valueOf(value), false, raw -> {
                if (value instanceof Number) {
                    try { save.accept(Integer.valueOf(raw)); }
                    catch (NumberFormatException invalid) { feedback("Enter a whole number"); }
                } else save.accept(raw);
            });
        }));
    }

    private static List<Object> choices(String key, Object value) {
        List<Object> list = new ArrayList<>();
        if (value instanceof Boolean) return Arrays.asList(false, true);
        String words;
        switch (key) {
            case "placement": words = "top_left top top_right left center right bottom_left bottom bottom_right fullscreen"; break;
            case "chip_placement": words = "top_left top top_right bottom_left bottom bottom_right"; break;
            case "size": words = "small medium large full"; break;
            case "text_size": words = "small medium large xlarge"; break;
            case "corner": words = "none small medium large pill"; break;
            case "border": words = "none thin medium thick"; break;
            case "shadow": words = "none soft strong"; break;
            case "glow": words = "none accent border"; break;
            case "accent_style": words = "bar badge dot underline none"; break;
            case "button_shape": words = "square rounded pill"; break;
            case "button_spacing": words = "compact normal roomy"; break;
            case "button_background": case "button_text_color": case "button_focus_background": case "button_focus_text_color":
                words = COLORS + " #303744 #111827"; break;
            case "title_weight": words = "bold normal"; break;
            case "accent": case "background": case "border_color": words = COLORS; break;
            case "icon": words = "none info bell door camera alarm warning home media megaphone person package tv timer check"; break;
            case "stacking": words = "replace queue stack"; break;
            case "sound": words = "none chime"; break;
            case "video_sound": words = "off on"; break;
            case "over_playback": words = "show skip"; break;
            case "night": words = "normal dim hide"; break;
            case "focus": words = "auto never"; break;
            case "animation": words = "slide fade none"; break;
            case "speaker_at_night": words = "urgent always never"; break;
            case "opacity": case "sound_volume": case "volume_tv": case "volume_speaker":
                for (int i=0; i<=100; i+=5) list.add(i);
                return list;
            case "max_visible":
                for (int i=1; i<=6; i++) list.add(i);
                return list;
            case "duration":
                list.add(0);
                for (int i=3; i<=3600; i += i < 10 ? 1 : i < 60 ? 5 : 30) list.add(i);
                return list;
            case "night_start": case "night_end":
                for (int i=0; i<1440; i+=30) list.add(String.format(Locale.ROOT, "%02d:%02d", i/60, i%60));
                return list;
            default: return null;
        }
        list.addAll(Arrays.asList(words.split(" ")));
        return list;
    }
}
