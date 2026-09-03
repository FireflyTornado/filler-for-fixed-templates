package com.firefly.core;

/** 应用级配置；不包含模板正文、变量值或生成结果。 */
public final class AppConfig {
    public static final int DEFAULT_MAIN_DIVIDER = 610;
    public static final int DEFAULT_PREVIEW_DIVIDER = 330;
    public static final int DEFAULT_EXTRACTION_DIVIDER = 170;
    public static final float DEFAULT_FONT_SCALE = 1.10f;
    public static final float MIN_FONT_SCALE = 0.80f;
    public static final float MAX_FONT_SCALE = 2.00f;

    private String lastTemplate;
    private String lastExcelDirectory;
    private String lastExportDirectory;

    public String lastExcelDirectory() { return lastExcelDirectory; }
    public void setLastExcelDirectory(String value) { lastExcelDirectory = value; }
    public String lastExportDirectory() { return lastExportDirectory; }
    public void setLastExportDirectory(String value) { lastExportDirectory = value; }
    private int mainDividerLocation = DEFAULT_MAIN_DIVIDER;
    private int previewResultDividerLocation = DEFAULT_PREVIEW_DIVIDER;
    private int extractionDividerLocation = DEFAULT_EXTRACTION_DIVIDER;
    private boolean legacyLastValuesMigrated;
    private float fontScale = DEFAULT_FONT_SCALE;

    public String lastTemplate() { return lastTemplate; }
    public void setLastTemplate(String value) { lastTemplate = value; }
    public int mainDividerLocation() { return mainDividerLocation; }
    public void setMainDividerLocation(int value) { if (value > 0) mainDividerLocation = value; }
    public int previewResultDividerLocation() { return previewResultDividerLocation; }
    public void setPreviewResultDividerLocation(int value) {
        if (value > 0) previewResultDividerLocation = value;
    }
    public int extractionDividerLocation() { return extractionDividerLocation; }
    public void setExtractionDividerLocation(int value) { if (value > 0) extractionDividerLocation = value; }
    public boolean legacyLastValuesMigrated() { return legacyLastValuesMigrated; }
    public void setLegacyLastValuesMigrated(boolean value) { legacyLastValuesMigrated = value; }
    public float fontScale() { return fontScale; }
    public void setFontScale(float value) {
        fontScale = isValidFontScale(value) ? value : DEFAULT_FONT_SCALE;
    }

    public static boolean isValidFontScale(float value) {
        return Float.isFinite(value) && value >= MIN_FONT_SCALE && value <= MAX_FONT_SCALE;
    }
}
