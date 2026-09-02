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
 * Templates 文件夹的读写：模板文件可任意命名，并可按子文件夹整理。
 * 规则：
 *   * 模板文件是纯文本，使用真实换行（所见即所得）
 *   * 首次运行时若目录为空/不存在，自动生成一份 example.txt
 */
public final class TemplateStore {

    private final Path templatesDir;

    public TemplateStore(Path appDir) {
        this.templatesDir = appDir.resolve(TemplateConstants.TEMPLATES_DIR_NAME)
                .toAbsolutePath().normalize();
    }

    public Path templatesDir() {
        return templatesDir;
    }

    /** 模板相对路径对应的完整路径。 */
    public Path templateFile(String name) {
        return resolveRelativePath(name);
    }

    /**
     * 把 Templates 内的文件转成使用 / 分隔的相对路径，便于跨平台保存到配置。
     */
    public String templateName(Path file) {
        Path absolute = file.toAbsolutePath().normalize();
        if (!absolute.startsWith(templatesDir) || absolute.equals(templatesDir)) {
            throw new IllegalArgumentException("模板文件必须位于 Templates 文件夹内");
        }
        return portableName(templatesDir.relativize(absolute));
    }

    /** 递归扫描模板目录，返回所有模板相对路径（忽略隐藏项，按名称排序）。 */
    public List<String> listTemplateNames() {
        if (!Files.isDirectory(templatesDir)) {
            return List.of();
        }
        List<String> names = new ArrayList<>();
        try (Stream<Path> s = Files.walk(templatesDir)) {
            s.filter(Files::isRegularFile)
             .filter(p -> !hasHiddenSegment(templatesDir.relativize(p)))
             .filter(p -> {
                 String name = p.getFileName().toString().toLowerCase(Locale.ROOT);
                 return name.endsWith(".txt") || name.endsWith(".docx");
             })
             .map(this::templateName)
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
        Path file = templateFile(name);
        Files.createDirectories(file.getParent());
        TextFileWriter.writeText(file, content);
    }

    /** 在原子移动可用时原子重命名模板；新名称可以包含原有的相对子文件夹。 */
    public void renameTemplate(String oldName, String newName) throws IOException {
        Path source = templateFile(oldName);
        Path target = templateFile(newName);
        if (source.equals(target)) return;
        if (!Files.isRegularFile(source)) throw new IOException("模板文件不存在：" + source);
        boolean sameFile = Files.exists(target) && Files.isSameFile(source, target);
        if (Files.exists(target) && !sameFile) throw new IOException("目标模板已存在：" + target);
        Files.createDirectories(target.getParent());
        if (sameFile) moveChangingOnlyCase(source, target);
        else move(source, target);
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

    private Path resolveRelativePath(String name) {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("模板路径不能为空");
        String portable = name.replace('\\', '/');
        if (portable.startsWith("/") || portable.endsWith("/") || portable.contains("//")) {
            throw new IllegalArgumentException("模板路径不合法");
        }
        Path relative = Path.of(portable.replace('/', java.io.File.separatorChar));
        if (relative.isAbsolute() || relative.getNameCount() == 0) {
            throw new IllegalArgumentException("模板路径必须是相对路径");
        }
        for (Path part : relative) {
            String text = part.toString();
            if (text.isBlank() || text.equals(".") || text.equals("..")) {
                throw new IllegalArgumentException("模板路径不能包含 . 或 ..");
            }
        }
        Path result = templatesDir.resolve(relative).normalize();
        if (!result.startsWith(templatesDir) || result.equals(templatesDir)) {
            throw new IllegalArgumentException("模板路径超出 Templates 文件夹");
        }
        return result;
    }

    private static boolean hasHiddenSegment(Path relative) {
        for (Path part : relative) if (part.toString().startsWith(".")) return true;
        return false;
    }

    private static String portableName(Path relative) {
        StringBuilder value = new StringBuilder();
        for (Path part : relative) {
            if (!value.isEmpty()) value.append('/');
            value.append(part);
        }
        return value.toString();
    }

    private static void move(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            Files.move(source, target);
        }
    }

    /** Windows 的大小写不敏感文件系统需要经过同目录临时名称才能只修改文件名大小写。 */
    private static void moveChangingOnlyCase(Path source, Path target) throws IOException {
        Path temporary = Files.createTempFile(source.getParent(), ".template-rename-", ".tmp");
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
}
