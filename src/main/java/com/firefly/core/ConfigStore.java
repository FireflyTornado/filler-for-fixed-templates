package com.firefly.core;

import com.firefly.TemplateConstants;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;

/**
 * template.conf 的读写。
 * 规则：
 *   * 只读取 / 替换 template = ... 行
 *   * 配置文件中用 \n 两个字符表示换行，读取时还原成真实换行
 *   * 首次运行配置文件不存在时，自动生成默认配置
 */
public final class ConfigStore {

    private final Path configFile;

    public ConfigStore(Path appDir) {
        this.configFile = appDir.resolve(TemplateConstants.CONFIG_FILENAME);
    }

    public Path configFile() {
        return configFile;
    }

    /** 配置文件不存在时，自动创建一个带示例的默认配置。 */
    public void ensureDefaultConfig() throws IOException {
        if (!Files.exists(configFile)) {
            TextFileWriter.writeText(configFile, TemplateConstants.DEFAULT_CONFIG);
        }
    }

    /**
     * 读取模板字符串；找不到有效配置（文件不存在或没有 template 行）返回 null。
     */
    public String readTemplate() {
        if (!Files.exists(configFile)) {
            return null;
        }
        try {
            for (String line : splitLines(TextFileWriter.readText(configFile))) {
                Matcher m = TemplateConstants.TPL_LINE.matcher(line);
                if (m.matches()) {
                    // 配置里用 \n 表示换行，读的时候还原
                    return m.group(1).replace("\\n", "\n");
                }
            }
        } catch (IOException e) {
            return null;
        }
        return null;
    }

    /**
     * 把模板写回配置文件（保留原有注释，只替换 template 那一行）。
     * 若文件里没有 template 行，则在末尾追加。
     */
    public void writeTemplate(String template) throws IOException {
        // 换行写成 \n，避免撑破单行配置
        String escaped = template.replace("\r\n", "\n").replace("\r", "\n").replace("\n", "\\n");
        List<String> lines = Files.exists(configFile)
                ? splitLines(TextFileWriter.readText(configFile))
                : new ArrayList<>();
        StringBuilder out = new StringBuilder();
        boolean found = false;
        for (String line : lines) {
            if (!found && TemplateConstants.TPL_LINE.matcher(line).matches()) {
                out.append("template = ").append(escaped).append("\n");
                found = true;
            } else {
                out.append(line).append("\n");
            }
        }
        if (!found) {
            out.append("\n");
            out.append("# 模板内容，使用 {{变量名}} 作为占位符\n");
            out.append("template = ").append(escaped).append("\n");
        }
        TextFileWriter.writeText(configFile, out.toString());
    }

    /**
     * 按行拆分：兼容 \r\n、\n、\r；文件末尾的换行不会产生多余的空行。
     */
    private static List<String> splitLines(String content) {
        String normalized = content.replace("\r\n", "\n").replace("\r", "\n");
        List<String> lines = new ArrayList<>(Arrays.asList(normalized.split("\n", -1)));
        if (!lines.isEmpty() && lines.get(lines.size() - 1).isEmpty()) {
            lines.remove(lines.size() - 1);
        }
        return lines;
    }
}
