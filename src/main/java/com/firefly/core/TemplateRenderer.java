package com.firefly.core;

import com.firefly.TemplateConstants;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;

/**
 * 把模板渲染成最终结果。
 * 规则：
 *   * 普通 {{变量名}}           -> 用户输入的值（纯数字内容也作为变量名处理）
 *   * 自动变量                   -> 日历基准日期的派生文本或数值
 *   * 数值变量和表达式结果       -> 按当前模板设置统一保留小数位数
 *   * 已弃用的 [[变量]]          -> 原样保留，由加载模板时的迁移功能处理
 */
public final class TemplateRenderer {

    /** 渲染结果：要么有结果文本，要么有错误信息。 */
    public record RenderResult(String result, String error) {
        public boolean hasError() {
            return error != null;
        }
    }

    private TemplateRenderer() {
    }

    /** 统一变量入口。 */
    public static RenderResult renderUnified(String template,
                                             Map<String, String> values,
                                             Map<String, String> autoVals) {
        return render(template, values, autoVals);
    }

    public static RenderResult renderUnified(String template,
                                             Map<String, String> values,
                                             Map<String, String> autoVals,
                                             Set<String> numericVariables,
                                             int decimalPlaces) {
        return render(template, values, autoVals, numericVariables, decimalPlaces);
    }

    public static RenderResult render(String template,
                                      Map<String, String> values,
                                      Map<String, String> autoVals) {
        return render(template, values, autoVals, Set.of(),
                NumericFormatter.DEFAULT_DECIMAL_PLACES);
    }

    public static RenderResult render(String template,
                                      Map<String, String> values,
                                      Map<String, String> autoVals,
                                      Set<String> numericVariables,
                                      int decimalPlaces) {
        StringBuilder out = new StringBuilder();
        Matcher m = TemplateConstants.PLACEHOLDER_RE.matcher(template);
        int pos = 0;
        while (m.find()) {
            out.append(template, pos, m.start());
            String whole = m.group();
            String content = m.group(1).trim();
            if (content.isEmpty()) {
                out.append(whole);              // {{}} 原样保留
            } else {
                try {
                    out.append(resolve(whole, content, values, autoVals,
                            numericVariables, decimalPlaces));
                } catch (ExpressionEvaluator.EvalException e) {
                    String expr = content.substring(1).trim();
                    return new RenderResult(null, "表达式「" + expr + "」" + e.getMessage());
                }
            }
            pos = m.end();
        }
        out.append(template.substring(pos));
        return new RenderResult(out.toString(), null);
    }

    /**
     * 解析单个占位符并返回替换文本（纯文本渲染与 Word 渲染共用，保证两边行为一致）。
     *
     * @param whole        占位符原文（如 "{{数量}}"）
     * @param content      去掉定界符并 trim 后的内容（如 "数量" / "备注"）
     * @param values       {{变量名}} 的值
     * @param autoVals     自动变量的值
     * @return 替换后的文本；没有对应值时原样返回占位符本身
     */
    public static String resolve(String whole, String content,
                                 Map<String, String> values,
                                 Map<String, String> autoVals)
            throws ExpressionEvaluator.EvalException {
        return resolve(whole, content, values, autoVals, Set.of(),
                NumericFormatter.DEFAULT_DECIMAL_PLACES);
    }

    public static String resolve(String whole, String content,
                                 Map<String, String> values,
                                 Map<String, String> autoVals,
                                 Set<String> numericVariables,
                                 int decimalPlaces)
            throws ExpressionEvaluator.EvalException {
        if (TemplateParser.isExpression(content)) {
            String expr = content.substring(1).trim();
            double r = ExpressionEvaluator.evaluate(expr, expressionValues(values, autoVals));
            return NumericFormatter.format(r, decimalPlaces);
        }
        if (autoVals.containsKey(content)) {
            return autoVals.get(content);
        }
        String v = values.get(content);
        if (v == null) return whole;
        return numericVariables != null && numericVariables.contains(content)
                ? NumericFormatter.format(v, decimalPlaces) : v;
    }

    /** 只有自动数值变量进入运算上下文；日期文本仍由求值器明确拒绝。 */
    private static Map<String, String> expressionValues(Map<String, String> values,
                                                        Map<String, String> autoVals) {
        Map<String, String> result = new LinkedHashMap<>();
        if (values != null) result.putAll(values);
        if (autoVals != null) {
            for (String name : TemplateConstants.AUTO_NUMERIC_VAR_SET) {
                String value = autoVals.get(name);
                if (value != null) result.put(name, value);
            }
        }
        return result;
    }
}
