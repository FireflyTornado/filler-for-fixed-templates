package com.firefly.core;

import com.firefly.TemplateConstants;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * last_values.json 的读写：记住上次输入的数据，下次打开自动回填。
 * UTF-8 带 BOM，中文原样保存（记事本可正常打开）。
 */
public final class LastValuesStore {

    private final Path valuesFile;

    public LastValuesStore(Path appDir) {
        this.valuesFile = appDir.resolve(TemplateConstants.VALUES_FILENAME);
    }

    /** 读取上次保存的输入；文件不存在或损坏时返回空字典。 */
    public Map<String, String> load() {
        if (!Files.exists(valuesFile)) {
            return new LinkedHashMap<>();
        }
        try {
            String text = TextFileWriter.readText(valuesFile);
            Map<String, String> map = MiniJson.parseFlatStringMap(text);
            Map<String, String> out = new LinkedHashMap<>();
            for (Map.Entry<String, String> e : map.entrySet()) {
                out.put(String.valueOf(e.getKey()), String.valueOf(e.getValue()));
            }
            return out;
        } catch (Exception e) {
            return new LinkedHashMap<>(); // 文件损坏时忽略，重新开始
        }
    }

    /** 保存输入数据为 JSON 文件。 */
    public void save(Map<String, String> data) {
        try {
            TextFileWriter.writeText(valuesFile, MiniJson.toJson(data));
        } catch (IOException e) {
            // 忽略写入失败，不影响主流程
        }
    }
}
