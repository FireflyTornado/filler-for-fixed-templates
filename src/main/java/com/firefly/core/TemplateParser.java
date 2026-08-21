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

    /** 单次扫描模板得到的解析结果。 */
    public record ParsedTemplate(List<String> inputVariables, List<String> autoVariables,
                                 List<String> stringVariables, int expressionCount) {
    }

    /** 单次遍历模板：同时提取输入变量、自动日期变量、字符串变量，并统计表达式个数。 */
    public static ParsedTemplate parse(String template) {
        List<String> inputs = new ArrayList<>();
        List<String> autos = new ArrayList<>();
        List<String> strings = new ArrayList<>();
        Set<String> seenInputs = new LinkedHashSet<>();
        Set<String> seenAutos = new LinkedHashSet<>();
        Set<String> seenStrings = new LinkedHashSet<>();
        int exprCount = 0;

        Matcher m = TemplateConstants.ALL_PLACEHOLDER_RE.matcher(template);
        while (m.find()) {
            if (m.group(2) != null) {                    // [[字符串]]
                String content = m.group(2).substring(2, m.group(2).length() - 2).trim();
                if (!content.isEmpty() && !seenStrings.contains(content)) {
                    seenStrings.add(content);
                    strings.add(content);
                }
                continue;
            }
            String content = m.group(1).substring(2, m.group(1).length() - 2).trim();
            if (content.isEmpty() || isNumber(content)) {
                continue;
            }
            if (isExpression(content)) {
                exprCount++;
                Matcher im = TemplateConstants.IDENT_RE.matcher(content);
                while (im.find()) {
                    String name = im.group();
                    if (!TemplateConstants.AUTO_VAR_SET.contains(name) && !seenInputs.contains(name)) {
                        seenInputs.add(name);
                        inputs.add(name);
                    }
                }
            } else if (TemplateConstants.AUTO_VAR_SET.contains(content)) {
                if (!seenAutos.contains(content)) {
                    seenAutos.add(content);
                    autos.add(content);
                }
            } else if (!seenInputs.contains(content)) {
                seenInputs.add(content);
                inputs.add(content);
            }
        }
        return new ParsedTemplate(inputs, autos, strings, exprCount);
    }
}
