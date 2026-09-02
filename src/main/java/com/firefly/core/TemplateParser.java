package com.firefly.core;

import com.firefly.TemplateConstants;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;

/**
 * 模板占位符解析：提取需要填写的变量与自动日期变量。
 */
public final class TemplateParser {

    private TemplateParser() {
    }

    /** 占位符内容是否为算术表达式（以 = 开头）。 */
    public static boolean isExpression(String content) {
        return content.startsWith("=");
    }

    /** 统一变量的模板使用信息。 */
    public record VariableSpec(String name, boolean numericLocked) {
        public VariableType defaultType() { return VariableType.NUMBER; }
    }

    /** 单次扫描模板得到的解析结果。 */
    public record ParsedTemplate(List<String> inputVariables, List<String> autoVariables,
                                 int expressionCount,
                                 List<VariableSpec> variables,
                                 Set<String> expressionVariables) {
    }

    /** 单次遍历模板：同时提取输入变量、自动日期变量，并统计表达式个数。 */
    public static ParsedTemplate parse(String template) {
        List<String> inputs = new ArrayList<>();
        List<String> autos = new ArrayList<>();
        Set<String> seenInputs = new LinkedHashSet<>();
        Set<String> seenAutos = new LinkedHashSet<>();
        Set<String> usages = new LinkedHashSet<>();
        Set<String> expressionVariables = new LinkedHashSet<>();
        int exprCount = 0;

        Matcher m = TemplateConstants.PLACEHOLDER_RE.matcher(template);
        while (m.find()) {
            String content = m.group(1).trim();
            if (content.isEmpty()) {
                continue;
            }
            if (isExpression(content)) {
                exprCount++;
                String expr = content.substring(1).trim();
                try {
                    for (String name : ExpressionEvaluator.referencedVariables(expr)) {
                        if (!TemplateConstants.AUTO_VAR_SET.contains(name) && !seenInputs.contains(name)) {
                            seenInputs.add(name);
                            inputs.add(name);
                        }
                        if (!TemplateConstants.AUTO_VAR_SET.contains(name)) {
                            expressionVariables.add(name);
                            usages.add(name);
                        }
                    }
                } catch (ExpressionEvaluator.EvalException ignored) {
                    // 语法错误在生成时显示；模板编辑阶段仍保留已能识别的其他变量。
                }
            } else if (TemplateConstants.AUTO_VAR_SET.contains(content)) {
                if (!seenAutos.contains(content)) {
                    seenAutos.add(content);
                    autos.add(content);
                }
            } else if (!seenInputs.contains(content)) {
                seenInputs.add(content);
                inputs.add(content);
                usages.add(content);
            } else {
                usages.add(content);
            }
        }
        List<VariableSpec> variables = new ArrayList<>();
        for (String name : usages) {
            variables.add(new VariableSpec(name, expressionVariables.contains(name)));
        }
        return new ParsedTemplate(inputs, autos, exprCount,
                List.copyOf(variables), Set.copyOf(expressionVariables));
    }
}
