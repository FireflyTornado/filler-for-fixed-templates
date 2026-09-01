package com.firefly.core;

import com.firefly.TemplateConstants;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/** 把旧 last_values.json 只读迁移到新版应用/模板配置。 */
public final class LegacyConfigMigrator {
    private final LastValuesStore legacyStore;
    private final TemplateStore templateStore;
    private final TemplateConfigStore templateConfigs;

    public LegacyConfigMigrator(Path appDir, TemplateStore templateStore,
                                TemplateConfigStore templateConfigs) {
        this.legacyStore = new LastValuesStore(appDir);
        this.templateStore = templateStore;
        this.templateConfigs = templateConfigs;
    }

    public boolean migrateIfNeeded(AppConfig appConfig, boolean appConfigExisted) throws IOException {
        if (appConfig.legacyLastValuesMigrated() || !legacyStore.exists()) return false;
        boolean needsLegacyLastTemplate = !appConfigExisted
                || appConfig.lastTemplate() == null || appConfig.lastTemplate().isBlank();
        if (!needsLegacyLastTemplate) return false;
        Map<String, Map<String, String>> all = legacyStore.loadAllForMigration();
        if (needsLegacyLastTemplate) {
            Map<String, String> last = all.get(TemplateConstants.LAST_TEMPLATE_KEY);
            if (last != null && last.get("name") != null) appConfig.setLastTemplate(last.get("name"));
        }
        for (Map.Entry<String, Map<String, String>> templateEntry : all.entrySet()) {
            String templateName = templateEntry.getKey();
            if (TemplateConstants.LAST_TEMPLATE_KEY.equals(templateName)
                    || templateConfigs.exists(templateName)) {
                continue;
            }
            try {
                templateConfigs.configFileForTemplate(templateName);
            } catch (IllegalArgumentException unsafeName) {
                continue;
            }
            TemplateParser.ParsedTemplate parsed = parseTemplateIfAvailable(templateName);
            Map<String, TemplateParser.VariableSpec> specs = new LinkedHashMap<>();
            if (parsed != null) {
                for (TemplateParser.VariableSpec spec : parsed.variables()) specs.put(spec.name(), spec);
            }
            TemplateConfig migrated = new TemplateConfig(templateName);
            for (Map.Entry<String, String> oldValue : templateEntry.getValue().entrySet()) {
                String oldName = oldValue.getKey();
                boolean legacyKey = oldName.startsWith("[[") && oldName.endsWith("]]" )
                        && oldName.length() > 4;
                String name = legacyKey ? oldName.substring(2, oldName.length() - 2).trim() : oldName;
                if (name.isEmpty()) continue;
                TemplateParser.VariableSpec spec = specs.get(name);
                VariableType type = spec != null ? spec.defaultType()
                        : (legacyKey ? VariableType.MULTILINE_TEXT : VariableType.NUMBER);
                migrated.variables().put(name, new TemplateConfig.Entry(type, oldValue.getValue()));
            }
            templateConfigs.saveConfig(migrated);
        }
        appConfig.setLegacyLastValuesMigrated(true);
        return true;
    }

    private TemplateParser.ParsedTemplate parseTemplateIfAvailable(String name) {
        try {
            Path path = templateStore.templateFile(name);
            if (!Files.isRegularFile(path)) return null;
            String content = DocxProcessor.isDocxName(name)
                    ? DocxProcessor.extractText(path) : templateStore.readTemplate(name);
            return TemplateParser.parse(content);
        } catch (Exception ignored) {
            return null;
        }
    }
}
