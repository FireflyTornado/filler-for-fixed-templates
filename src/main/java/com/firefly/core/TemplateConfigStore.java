package com.firefly.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Config/模板相对路径.json 的安全路径、容错读取与原子写入；目录结构与 Templates 镜像。 */
public final class TemplateConfigStore {
    /** 退出前确认使用的清理清单；只包含已检查模板中不再使用的变量。 */
    public record CleanupReport(Map<String, List<String>> unusedVariables) {
        public CleanupReport {
            Map<String, List<String>> copy = new LinkedHashMap<>();
            if (unusedVariables != null) {
                unusedVariables.forEach((template, names) ->
                        copy.put(template, List.copyOf(names)));
            }
            unusedVariables = java.util.Collections.unmodifiableMap(copy);
        }

        public boolean isEmpty() { return unusedVariables.isEmpty(); }
        public int templateCount() { return unusedVariables.size(); }
        public int variableCount() {
            return unusedVariables.values().stream().mapToInt(List::size).sum();
        }
    }

    private final Path configDir;

    public TemplateConfigStore(Path appDir) {
        configDir = appDir.resolve("Config").toAbsolutePath().normalize();
    }

    public void ensureDirectory() throws IOException { Files.createDirectories(configDir); }

    public Path configFileForTemplate(String templateName) {
        if (templateName == null || templateName.isBlank()) {
            throw new IllegalArgumentException("模板文件名不能为空");
        }
        String portable = templateName.replace('\\', '/');
        if (portable.startsWith("/") || portable.endsWith("/") || portable.contains("//")) {
            throw new IllegalArgumentException("模板路径不合法");
        }
        Path plain = Path.of(portable.replace('/', java.io.File.separatorChar));
        if (plain.isAbsolute() || plain.getNameCount() == 0) {
            throw new IllegalArgumentException("模板路径必须是相对路径");
        }
        for (Path part : plain) {
            String text = part.toString();
            if (text.isBlank() || text.equals(".") || text.equals("..")) {
                throw new IllegalArgumentException("模板路径不能包含 . 或 ..");
            }
        }
        Path result = configDir.resolve(plain.toString() + ".json").normalize();
        if (!result.startsWith(configDir) || result.equals(configDir)) {
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
            if (!(parsed instanceof Map<?, ?> parsedRoot)) return config;
            Map<?, ?> root = migrateMappingState(file, parsedRoot, false);
            config.setDataExtraction(com.firefly.extraction.MappingProfile.fromJson(root.get("dataExtraction")));
            Object decimalPlaces = root.get("decimalPlaces");
            if (decimalPlaces instanceof Number number) {
                double raw = number.doubleValue();
                int places = number.intValue();
                if (Double.isFinite(raw) && raw == places
                        && places >= NumericFormatter.MIN_DECIMAL_PLACES
                        && places <= NumericFormatter.MAX_DECIMAL_PLACES) {
                    config.setDecimalPlaces(places);
                }
            }
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

    /** 一次性改写旧映射状态，保留变量、精度及其他配置字段；运行时不再维护启用状态。 */
    private Map<?, ?> migrateMappingState(Path file, Map<?, ?> root, boolean requireSaved) throws IOException {
        if (!(root.get("dataExtraction") instanceof Map<?, ?> extraction)
                || !(extraction.get("bindings") instanceof List<?> items)
                || items.stream().noneMatch(item -> item instanceof Map<?, ?> binding && binding.containsKey("enabled"))) return root;
        List<Object> converted = new java.util.ArrayList<>();
        for (Object item : items) {
            if (!(item instanceof Map<?, ?> binding)) { converted.add(item); continue; }
            if (Boolean.FALSE.equals(binding.get("enabled"))) continue;
            Map<Object, Object> next = new LinkedHashMap<>(binding); next.remove("enabled"); converted.add(next);
        }
        Map<Object, Object> nextExtraction = new LinkedHashMap<>(extraction);
        nextExtraction.put("version", 3); nextExtraction.put("bindings", converted);
        Map<Object, Object> nextRoot = new LinkedHashMap<>(root); nextRoot.put("dataExtraction", nextExtraction);
        try { AtomicConfigWriter.write(file, JsonData.stringify(nextRoot)); }
        catch (IOException failure) {
            if (requireSaved) throw failure;
            // 只读/被占用的旧文件也按转换后的内容读取，不丢失变量或重新启用旧映射。
            java.util.logging.Logger.getLogger(TemplateConfigStore.class.getName())
                    .log(java.util.logging.Level.WARNING, "旧映射配置暂时无法保存，下一次读取将重试：" + file, failure);
        }
        return nextRoot;
    }

    /** 启动时转换全部旧配置，未打开的模板也不再保留停用映射。 */
    public void migrateLegacyMappingStates() throws IOException {
        if (!Files.isDirectory(configDir)) return;
        try (var paths = Files.walk(configDir)) {
            for (Path file : paths.filter(p -> Files.isRegularFile(p, java.nio.file.LinkOption.NOFOLLOW_LINKS)
                    && p.getFileName().toString().endsWith(".json")).toList()) {
                String text = Files.readString(file, StandardCharsets.UTF_8);
                Object parsed;
                try { parsed = JsonData.parse(text); } catch (IOException invalidJson) { continue; }
                if (parsed instanceof Map<?, ?> root) migrateMappingState(file, root, true);
            }
        }
    }

    /** 合并写入，保留当前模板里暂时不存在的旧变量。 */
    public void save(String templateName, Map<String, VariableInputState> states) throws IOException {
        TemplateConfig merged = load(templateName);
        saveMerged(merged, states);
    }

    /** 合并写入变量与当前模板的小数位数。 */
    public void save(String templateName, Map<String, VariableInputState> states,
                     int decimalPlaces) throws IOException {
        TemplateConfig merged = load(templateName);
        merged.setDecimalPlaces(decimalPlaces);
        saveMerged(merged, states);
    }

    private void saveMerged(TemplateConfig merged, Map<String, VariableInputState> states)
            throws IOException {
        for (VariableInputState state : states.values()) {
            merged.variables().put(state.name(), new TemplateConfig.Entry(
                    state.type(), state.value()));
        }
        saveConfig(merged);
    }

    /**
     * 仅检查调用方已确认过内容的模板。这里不读取模板文件，因此退出耗时与模板总数无关。
     */
    public CleanupReport findUnusedVariables(Map<String, ? extends Set<String>> activeVariables) {
        Map<String, List<String>> unused = new LinkedHashMap<>();
        for (Map.Entry<String, ? extends Set<String>> checked : activeVariables.entrySet()) {
            if (!exists(checked.getKey())) continue;
            Set<String> active = checked.getValue() == null ? Set.of() : checked.getValue();
            List<String> names = load(checked.getKey()).variables().keySet().stream()
                    .filter(name -> !active.contains(name))
                    .toList();
            if (!names.isEmpty()) unused.put(checked.getKey(), names);
        }
        return new CleanupReport(unused);
    }

    /** 按已确认清单删除变量；再次载入后只删除清单中的名称，避免误删后来新增的数据。 */
    public void pruneUnusedVariables(CleanupReport report) throws IOException {
        for (Map.Entry<String, List<String>> item : report.unusedVariables().entrySet()) {
            TemplateConfig config = load(item.getKey());
            if (config.variables().keySet().removeAll(item.getValue())) saveConfig(config);
        }
    }

    public void saveConfig(TemplateConfig config) throws IOException {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("version", 4);
        root.put("template", config.templateName());
        root.put("decimalPlaces", config.decimalPlaces());
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
        root.put("dataExtraction", config.dataExtraction().toJson());
        AtomicConfigWriter.write(configFileForTemplate(config.templateName()), JsonData.stringify(root));
    }

    /** 重新读取当前配置后只合并映射，避免覆盖变量自动保存的结果。 */
    public void saveMapping(String templateName, com.firefly.extraction.MappingProfile profile) throws IOException {
        TemplateConfig config = load(templateName);
        config.setDataExtraction(profile);
        saveConfig(config);
    }

    /** 随模板重命名其配置文件；没有配置文件时无需创建空配置。 */
    public void rename(String oldTemplateName, String newTemplateName) throws IOException {
        Path source = configFileForTemplate(oldTemplateName);
        Path target = configFileForTemplate(newTemplateName);
        if (source.equals(target) || !Files.exists(source)) return;
        boolean sameFile = Files.exists(target) && Files.isSameFile(source, target);
        if (Files.exists(target) && !sameFile) throw new IOException("目标模板配置已存在：" + target);
        Files.createDirectories(target.getParent());
        try {
            if (sameFile) moveChangingOnlyCase(source, target);
            else move(source, target);
            // 同步 JSON 内记录的模板相对路径；读取时仍采用新名称作为权威值。
            saveConfig(load(newTemplateName));
        } catch (IOException error) {
            if (Files.exists(target) && !Files.exists(source)) {
                try { move(target, source); }
                catch (IOException rollbackError) { error.addSuppressed(rollbackError); }
            }
            throw error;
        }
    }

    private static void move(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            Files.move(source, target);
        }
    }

    private static void moveChangingOnlyCase(Path source, Path target) throws IOException {
        Path temporary = Files.createTempFile(source.getParent(), ".config-rename-", ".tmp");
        Files.delete(temporary);
        move(source, temporary);
        try {
            move(temporary, target);
        } catch (IOException error) {
            try { move(temporary, source); }
            catch (IOException rollbackError) { error.addSuppressed(rollbackError); }
            throw error;
        }
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
