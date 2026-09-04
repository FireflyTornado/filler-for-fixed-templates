package com.firefly.core;

import com.firefly.TemplateConstants;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;

/** 占位符词法扫描；统一处理主模板、文本变量和 Word 文本中的反斜杠转义。 */
public final class TemplateSyntax {
    public record Placeholder(int start, int end, String whole, String content,
                              boolean escaped, String literalPrefix) { }

    private TemplateSyntax() { }

    /**
     * 紧邻占位符的反斜杠按奇偶解释：每两个输出一个普通反斜杠，余下一个转义占位符。
     */
    public static List<Placeholder> placeholders(String text) {
        List<Placeholder> result = new ArrayList<>();
        Matcher matcher = TemplateConstants.PLACEHOLDER_RE.matcher(text == null ? "" : text);
        while (matcher.find()) {
            int slashes = 0;
            for (int i = matcher.start() - 1; i >= 0 && text.charAt(i) == '\\'; i--) slashes++;
            result.add(new Placeholder(matcher.start() - slashes, matcher.end(), matcher.group(),
                    matcher.group(1).trim(), (slashes & 1) == 1, "\\".repeat(slashes / 2)));
        }
        return List.copyOf(result);
    }
}
