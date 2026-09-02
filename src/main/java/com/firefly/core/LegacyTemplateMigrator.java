package com.firefly.core;

import com.firefly.TemplateConstants;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;

/** 检测并一次性迁移已弃用的 [[变量]] 模板语法。 */
public final class LegacyTemplateMigrator {
    public record Scan(int count, List<String> variableNames) {
        public boolean found() { return count > 0; }
    }

    public record MigrationResult(Scan scan, Path backup) { }

    private LegacyTemplateMigrator() { }

    public static Scan scan(String text) {
        Matcher matcher = TemplateConstants.LEGACY_PLACEHOLDER_RE.matcher(text == null ? "" : text);
        int count = 0;
        Set<String> names = new LinkedHashSet<>();
        while (matcher.find()) {
            String name = matcher.group(1).trim();
            if (name.isEmpty()) continue;
            count++;
            names.add(name);
        }
        return new Scan(count, List.copyOf(names));
    }

    public static String migrateText(String text) {
        Matcher matcher = TemplateConstants.LEGACY_PLACEHOLDER_RE.matcher(text);
        StringBuffer result = new StringBuffer();
        while (matcher.find()) {
            String replacement = "{{" + matcher.group(1) + "}}";
            matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    /** 先创建唯一备份，再通过同目录临时文件原子替换原模板。 */
    public static MigrationResult migrate(Path template, boolean docx, String extractedText)
            throws IOException {
        Scan scan = scan(extractedText);
        if (!scan.found()) return new MigrationResult(scan, null);

        Path backup = uniqueBackup(template);
        Files.copy(template, backup);
        Path temp = Files.createTempFile(template.getParent(), template.getFileName().toString(), ".migration.tmp");
        boolean committed = false;
        try {
            if (docx) {
                DocxProcessor.migrateLegacyPlaceholders(template, temp);
            } else {
                TextFileWriter.writeText(temp, migrateText(extractedText));
            }
            String migratedText = docx ? DocxProcessor.extractText(temp) : TextFileWriter.readText(temp);
            if (scan(migratedText).found()) {
                throw new IOException("转换后的模板仍包含无法迁移的旧占位符");
            }
            try {
                Files.move(temp, template, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, template, StandardCopyOption.REPLACE_EXISTING);
            }
            committed = true;
            return new MigrationResult(scan, backup);
        } finally {
            if (!committed) Files.deleteIfExists(temp);
        }
    }

    private static Path uniqueBackup(Path template) {
        Path first = template.resolveSibling(template.getFileName() + ".bak");
        if (!Files.exists(first)) return first;
        for (int number = 2; ; number++) {
            Path candidate = template.resolveSibling(template.getFileName() + ".bak." + number);
            if (!Files.exists(candidate)) return candidate;
        }
    }
}
