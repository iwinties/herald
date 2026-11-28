package co.tbrkr.herald;

import java.util.Collections;
import java.util.Map;

final class MotionPresets {
    static final String TRANSITIONS = "none fade slide_auto slide_left slide_right slide_top slide_bottom "
            + "slide_auto_fade slide_left_fade slide_right_fade slide_top_fade slide_bottom_fade "
            + "grow shrink grow_fade shrink_fade";
    static final Map<String,Object> OPTIONS = Collections.unmodifiableMap(Json.fields(
            "transition_in", TRANSITIONS, "transition_out", TRANSITIONS,
            "attention_animation", "none pulse double_pulse glow nudge",
            "transition_expand", "morph morph_fade fade none", "transition_minimize", "morph morph_fade fade none",
            "text_animation", "none ticker", "ticker_speed", "slow normal fast"));
    static final Map<String,Object> DEFAULTS = Collections.unmodifiableMap(Json.fields(
            "transition_in", "slide_auto", "transition_out", "fade", "attention_animation", "none",
            "transition_expand", "morph", "transition_minimize", "morph", "text_animation", "none", "ticker_speed", "normal"));
    private MotionPresets() {}
}
