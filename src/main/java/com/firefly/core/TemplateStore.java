package com.firefly.core;

import com.firefly.TemplateConstants;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * Templates 文件夹的读写：模板文件可任意命名，统一放在 jar 同级的 Templates 目录。
 * 规则：
 *   * 模板文件是纯文本，使用真实换行（所见即所得）
 *   * 首次运行时若目录为空/不存在，自动生成一份 example.txt
 */
public final class TemplateStore {

    private final Path templatesDir;

    public TemplateStore(Path appDir) {
        this.templatesDir = appDir.resolve(TemplateConstants.TEMPLATES_DIR_NAME);
    }

    public Path templatesDir() {
        return templatesDir;
    }

    /** 模板文件名对应的完整路径。 */
    public Path templateFile(String name) {
        return templatesDir.resolve(name);
    }

    /** 扫描模板目录，返回所有模板文件名（忽略隐藏文件，按名称排序）；目录不存在时返回空列表。 */
    public List<String> listTemplateNames() {
        if (!Files.isDirectory(templatesDir)) {
            return List.of();
        }
        List<String> names = new ArrayList<>();
        try (Stream<Path> s = Files.list(templatesDir)) {
            s.filter(Files::isRegularFile)
             .filter(p -> !p.getFileName().toString().startsWith("."))
             .filter(p -> {
                 String name = p.getFileName().toString().toLowerCase(Locale.ROOT);
                 return name.endsWith(".txt") || name.endsWith(".docx");
             })
             .map(p -> p.getFileName().toString())
             .sorted(Comparator.naturalOrder())
             .forEach(names::add);
        } catch (IOException e) {
            return List.of();
        }
        return names;
    }

    /**
     * 读取模板文件内容。
     * 统一把 \r\n / \r 归一化成 \n（写入端 TextFileWriter 会转成 CRLF），
     * 这样与 JTextArea 的内部行分隔符一致，避免误判「未保存修改」。
     */
    public String readTemplate(String name) throws IOException {
        return TextFileWriter.readText(templateFile(name))
                .replace("\r\n", "\n")
                .replace("\r", "\n");
    }

    /** 把内容写入（或新建）指定模板文件。 */
    public void writeTemplate(String name, String content) throws IOException {
        Files.createDirectories(templatesDir);
        TextFileWriter.writeText(templateFile(name), content);
    }

    /**
     * 确保模板文件夹非空：目录不存在或里面没有模板时，生成内置示例模板——
     * example.txt（纯文本示例）与 example.docx（Word 示例，占位符与 example.txt 一致）。
     */
    public void ensureTemplatesExist() throws IOException {
        if (!listTemplateNames().isEmpty()) {
            return;
        }
        Files.createDirectories(templatesDir);
        writeTemplate(TemplateConstants.EXAMPLE_TEMPLATE_NAME, TemplateConstants.DEFAULT_TEMPLATE);
        try {
            DocxProcessor.createExampleDocx(templateFile(TemplateConstants.EXAMPLE_DOCX_NAME));
        } catch (IOException e) {
            // 忽略：Word 示例生成失败不影响主流程（文本示例已就绪）
        }
    }
}
