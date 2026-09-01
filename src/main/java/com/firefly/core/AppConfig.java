package com.firefly.core;

/** 应用级配置；不包含模板正文、变量值或生成结果。 */
public final class AppConfig {
    public static final int DEFAULT_MAIN_DIVIDER = 610;
    public static final int DEFAULT_PREVIEW_DIVIDER = 330;

    private String lastTemplate;
    private int mainDividerLocation = DEFAULT_MAIN_DIVIDER;
    private int previewResultDividerLocation = DEFAULT_PREVIEW_DIVIDER;
    private boolean legacyLastValuesMigrated;

    public String lastTemplate() { return lastTemplate; }
    public void setLastTemplate(String value) { lastTemplate = value; }
    public int mainDividerLocation() { return mainDividerLocation; }
    public void setMainDividerLocation(int value) { if (value > 0) mainDividerLocation = value; }
    public int previewResultDividerLocation() { return previewResultDividerLocation; }
    public void setPreviewResultDividerLocation(int value) {
        if (value > 0) previewResultDividerLocation = value;
    }
    public boolean legacyLastValuesMigrated() { return legacyLastValuesMigrated; }
    public void setLegacyLastValuesMigrated(boolean value) { legacyLastValuesMigrated = value; }
}
