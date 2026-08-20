package com.firefly.core;

import com.firefly.TemplateConstants;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 模板占位符解析：提取需要填写的变量、自动日期变量、字符串变量。
 */
public final class TemplateParser {

    /** 纯数字占位符内容，如 0.9 */
    private static final Pattern NUMBER_RE = Pattern.compile("(?:\\d+(?:\\.\\d+)?|\\.\\d+)");

    private TemplateParser() {
    }

    /** 占位符内容是否为纯数字（如 0.9）。 */
    public static boolean isNumber(String content) {
        return NUMBER_RE.matcher(content).matches();
    }

    /** 占位符内容是否为算术表达式（含 + - * / 或括号）。 */
    public static boolean isExpression(String content) {
        return TemplateConstants.OPERATOR_RE.matcher(content).find();
    }

    /** 返回需要用户填写的变量名（普通变量 + 表达式里引用的变量），按出现顺序去重。 */
    public static List<String> collectInputVariables(String template) {
        List<String> names = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        Matcher m = TemplateConstants.PLACEHOLDER_RE.matcher(template);
        while (m.find()) {
            String content = m.group(1).trim();
            if (content.isEmpty() || isNumber(content)) {
                continue;
            }
            if (isExpression(content)) {
                Matcher im = TemplateConstants.IDENT_RE.matcher(content);
                while (im.find()) {
                    String name = im.group();
                    if (!TemplateConstants.AUTO_VAR_SET.contains(name) && !seen.contains(name)) {
                        seen.add(name);
                        names.add(name);
                    }
                }
            } else {
                if (!TemplateConstants.AUTO_VAR_SET.contains(content) && !seen.contains(content)) {
                    seen.add(content);
                    names.add(content);
                }
            }
        }
        return names;
    }

    /** 返回模板中出现的自动日期变量（{{年}}/{{年月}}/{{年月日}}），按出现顺序去重。 */
    public static List<String> extractAutoVariables(String template) {
        List<String> names = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        Matcher m = TemplateConstants.PLACEHOLDER_RE.matcher(template);
        while (m.find()) {
            String content = m.group(1).trim();
            if (!isExpression(content)
                    && TemplateConstants.AUTO_VAR_SET.contains(content)
                    && !seen.contains(content)) {
                seen.add(content);
                names.add(content);
            }
        }
        return names;
    }

    /** 返回模板中出现的字符串变量（[[字符串]]），按出现顺序去重。 */
    public static List<String> collectStringVariables(String template) {
        List<String> names = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        Matcher m = TemplateConstants.STRING_RE.matcher(template);
        while (m.find()) {
            String content = m.group(1).trim();
            if (!content.isEmpty() && !seen.contains(content)) {
                seen.add(content);
                names.add(content);
            }
        }
        return names;
    }

    /** 统计模板中表达式占位符的个数（如 {{数量*单价}}）。 */
    public static int countExpressions(String template) {
        int n = 0;
        Matcher m = TemplateConstants.PLACEHOLDER_RE.matcher(template);
        while (m.find()) {
            if (isExpression(m.group(1).trim())) {
                n++;
            }
        }
        return n;
    }
}
