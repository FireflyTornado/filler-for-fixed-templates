package com.firefly.ui;

import com.firefly.core.AppConfig;

/** 用户可选的界面字号倍率。倍率只叠加在系统 Look and Feel 字体之上。 */
public enum FontScalePreset {
    SYSTEM(1.00f, "跟随系统"),
    COMFORTABLE(1.10f, "较大"),
    LARGE(1.25f, "大"),
    EXTRA_LARGE(1.40f, "特大");

    private final float scale;
    private final String displayName;

    FontScalePreset(float scale, String displayName) {
        this.scale = scale;
        this.displayName = displayName;
    }

    public float scale() { return scale; }
    @Override public String toString() { return displayName; }

    public static FontScalePreset closest(float scale) {
        if (!AppConfig.isValidFontScale(scale)) return COMFORTABLE;
        FontScalePreset result = SYSTEM;
        float distance = Float.MAX_VALUE;
        for (FontScalePreset preset : values()) {
            float candidate = Math.abs(preset.scale - scale);
            if (candidate < distance) { distance = candidate; result = preset; }
        }
        return result;
    }

    public FontScalePreset larger() {
        FontScalePreset[] presets = values();
        return presets[Math.min(ordinal() + 1, presets.length - 1)];
    }

    public FontScalePreset smaller() {
        FontScalePreset[] presets = values();
        return presets[Math.max(ordinal() - 1, 0)];
    }
}
