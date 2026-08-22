package com.firefly.core;

import com.firefly.TemplateConstants;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * last_values.json 的读写：按模板分别记住上次输入的数据，打开对应模板时自动回填。
 * 结构为「模板名 → 该模板的输入」，外加保留键 {@code @@last_template@@} 记录上次使用的模板。
 * UTF-8 带 BOM，中文原样保存（记事本可正常打开）。
 */
public final class LastValuesStore {

    private final Path valuesFile;

    public LastValuesStore(Path appDir) {
        this.valuesFile = appDir.resolve(TemplateConstants.VALUES_FILENAME);
    }

    /** 读取整个存档：模板名 → 该模板上次的输入；文件不存在或损坏时返回空字典。 */
    private Map<String, Map<String, String>> loadAll() {
        if (!Files.exists(valuesFile)) {
            return new LinkedHashMap<>();
        }
        try {
            return MiniJson.parseNestedStringMap(TextFileWriter.readText(valuesFile));
        } catch (Exception e) {
            return new LinkedHashMap<>(); // 文件损坏时忽略，重新开始
        }
    }

    private void saveAll(Map<String, Map<String, String>> data) {
        try {
            TextFileWriter.writeText(valuesFile, MiniJson.toJsonNested(data));
        } catch (IOException e) {
            // 忽略写入失败，不影响主流程
        }
    }

    /** 读取某个模板上次保存的输入；没有则返回空字典。 */
    public Map<String, String> loadFor(String templateName) {
        Map<String, String> m = loadAll().get(templateName);
        return m != null ? new LinkedHashMap<>(m) : new LinkedHashMap<>();
    }

    /** 读取上次使用的模板文件名；没有则返回 null。 */
    public String loadLastTemplate() {
        Map<String, String> m = loadAll().get(TemplateConstants.LAST_TEMPLATE_KEY);
        return m != null ? m.get("name") : null;
    }

    /** 保存某个模板的输入（合并进整体存档，不影响其他模板）。 */
    public void saveFor(String templateName, Map<String, String> values) {
        Map<String, Map<String, String>> all = loadAll();
        all.put(templateName, new LinkedHashMap<>(values));
        saveAll(all);
    }

    /** 记录上次使用的模板文件名。 */
    public void saveLastTemplate(String name) {
        Map<String, Map<String, String>> all = loadAll();
        Map<String, String> m = new LinkedHashMap<>();
        m.put("name", name);
        all.put(TemplateConstants.LAST_TEMPLATE_KEY, m);
        saveAll(all);
    }
}
