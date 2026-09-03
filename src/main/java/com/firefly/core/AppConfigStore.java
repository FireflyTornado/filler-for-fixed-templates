package com.firefly.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/** 根目录 config.json 的容错读取与原子写入。 */
public final class AppConfigStore {
    private final Path configFile;

    public AppConfigStore(Path appDir) {
        configFile = appDir.resolve("config.json");
    }

    public boolean exists() { return Files.isRegularFile(configFile); }

    @SuppressWarnings("unchecked")
    public AppConfig load() {
        AppConfig config = new AppConfig();
        if (!exists()) return config;
        try {
            Object parsed = JsonData.parse(Files.readString(configFile, StandardCharsets.UTF_8));
            if (!(parsed instanceof Map<?, ?> root)) return config;
            if (root.get("lastExcelDirectory") instanceof String directory) config.setLastExcelDirectory(directory);
            if (root.get("lastExportDirectory") instanceof String directory) config.setLastExportDirectory(directory);
            Object last = root.get("lastTemplate");
            if (last instanceof String text && !text.isBlank()) config.setLastTemplate(text);
            Object migrated = root.get("legacyLastValuesMigrated");
            if (migrated instanceof Boolean flag) config.setLegacyLastValuesMigrated(flag);
            Object layoutValue = root.get("layout");
            if (layoutValue instanceof Map<?, ?> layout) {
                config.setMainDividerLocation(positiveInt(layout.get("mainDividerLocation")));
                config.setPreviewResultDividerLocation(
                        positiveInt(layout.get("previewResultDividerLocation")));
            }
            Object appearanceValue = root.get("appearance");
            if (appearanceValue instanceof Map<?, ?> appearance) {
                Object fontScale = appearance.get("fontScale");
                if (fontScale instanceof Number number) {
                    config.setFontScale(number.floatValue());
                }
            }
        } catch (Exception ignored) {
            // 单个字段或整个文件损坏时使用默认配置。
        }
        return config;
    }

    public void save(AppConfig config) throws IOException {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("version", 1);
        root.put("lastTemplate", config.lastTemplate());
        root.put("lastExcelDirectory", config.lastExcelDirectory());
        root.put("lastExportDirectory", config.lastExportDirectory());
        root.put("legacyLastValuesMigrated", config.legacyLastValuesMigrated());
        Map<String, Object> layout = new LinkedHashMap<>();
        layout.put("mainDividerLocation", config.mainDividerLocation());
        layout.put("previewResultDividerLocation", config.previewResultDividerLocation());
        root.put("layout", layout);
        Map<String, Object> appearance = new LinkedHashMap<>();
        appearance.put("fontScale", config.fontScale());
        root.put("appearance", appearance);
        AtomicConfigWriter.write(configFile, JsonData.stringify(root));
    }

    private static int positiveInt(Object value) {
        return value instanceof Number number && number.intValue() > 0 ? number.intValue() : -1;
    }
}
