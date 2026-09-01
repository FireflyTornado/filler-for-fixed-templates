package com.firefly.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/** Config/模板完整文件名.json 的安全路径、容错读取与原子写入。 */
public final class TemplateConfigStore {
    private final Path configDir;

    public TemplateConfigStore(Path appDir) {
        configDir = appDir.resolve("Config").toAbsolutePath().normalize();
    }

    public void ensureDirectory() throws IOException { Files.createDirectories(configDir); }

    public Path configFileForTemplate(String templateName) {
        if (templateName == null || templateName.isBlank()) {
            throw new IllegalArgumentException("模板文件名不能为空");
        }
        if (templateName.indexOf('/') >= 0 || templateName.indexOf('\\') >= 0
                || templateName.contains("..")) {
            throw new IllegalArgumentException("模板文件名不能包含路径");
        }
        Path plain = Path.of(templateName);
        if (plain.isAbsolute() || plain.getNameCount() != 1
                || !plain.getFileName().toString().equals(templateName)
                || templateName.equals(".") || templateName.equals("..")) {
            throw new IllegalArgumentException("模板文件名不能包含路径");
        }
        Path result = configDir.resolve(templateName + ".json").normalize();
        if (!result.getParent().equals(configDir)) {
            throw new IllegalArgumentException("配置路径超出 Config 文件夹");
        }
        return result;
    }

    public boolean exists(String templateName) {
        try {
            return Files.isRegularFile(configFileForTemplate(templateName));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    public TemplateConfig load(String templateName) {
        TemplateConfig config = new TemplateConfig(templateName);
        Path file;
        try {
            file = configFileForTemplate(templateName);
            if (!Files.isRegularFile(file)) return config;
            Object parsed = JsonData.parse(Files.readString(file, StandardCharsets.UTF_8));
            if (!(parsed instanceof Map<?, ?> root)) return config;
            Object variables = root.get("variables");
            if (!(variables instanceof Map<?, ?> variableMap)) return config;
            for (Map.Entry<?, ?> item : variableMap.entrySet()) {
                if (!(item.getKey() instanceof String name) || !(item.getValue() instanceof Map<?, ?> data)) {
                    continue;
                }
                String typeName = data.get("type") instanceof String text ? text : null;
                VariableType parsedType = VariableType.fromName(typeName, null);
                String oldValue = data.get("value") instanceof String text ? text : "";
                Map<VariableType, String> sessionValues = new java.util.EnumMap<>(VariableType.class);
                readLegacyValues(data.get("drafts"), sessionValues);
                // valuesByType 是较新的旧字段，冲突时优先于 drafts。
                readLegacyValues(data.get("valuesByType"), sessionValues);
                String currentValue = parsedType == null
                        ? oldValue : sessionValues.getOrDefault(parsedType, oldValue);
                if (parsedType != null) sessionValues.put(parsedType, currentValue);
                config.variables().put(name, new TemplateConfig.Entry(
                        parsedType, currentValue, sessionValues));
            }
        } catch (Exception ignored) {
            // 此模板的配置损坏只使该模板回退默认值。
        }
        return config;
    }

    /** 合并写入，保留当前模板里暂时不存在的旧变量。 */
    public void save(String templateName, Map<String, VariableInputState> states) throws IOException {
        TemplateConfig merged = load(templateName);
        for (VariableInputState state : states.values()) {
            merged.variables().put(state.name(), new TemplateConfig.Entry(
                    state.type(), state.value()));
        }
        saveConfig(merged);
    }

    public void saveConfig(TemplateConfig config) throws IOException {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("version", 2);
        root.put("template", config.templateName());
        Map<String, Object> variables = new LinkedHashMap<>();
        for (Map.Entry<String, TemplateConfig.Entry> item : config.variables().entrySet()) {
            Map<String, Object> data = new LinkedHashMap<>();
            VariableType type = item.getValue().type() == null
                    ? VariableType.NUMBER : item.getValue().type();
            data.put("type", type.name());
            data.put("value", item.getValue().value());
            variables.put(item.getKey(), data);
        }
        root.put("variables", variables);
        AtomicConfigWriter.write(configFileForTemplate(config.templateName()), JsonData.stringify(root));
    }

    private static void readLegacyValues(Object value, Map<VariableType, String> destination) {
        if (!(value instanceof Map<?, ?> values)) return;
        for (Map.Entry<?, ?> draft : values.entrySet()) {
            if (draft.getKey() instanceof String typeName
                    && draft.getValue() instanceof String text) {
                VariableType type = VariableType.fromName(typeName, null);
                if (type != null) destination.put(type, text);
            }
        }
    }
}
