package com.firefly.core;

import com.firefly.TemplateConstants;

import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;

/**
 * 把模板渲染成最终结果。
 * 规则：
 *   * 普通 {{变量名}}           -> 用户输入的值（纯数字内容也作为变量名处理）
 *   * 自动日期变量               -> 系统日期
 *   * {{=数量*单价}} 等表达式   -> 求值并保留两位小数
 *   * [[字符串名]]               -> 原样替换（含换行、空格、格式）
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

    public static RenderResult render(String template,
                                      Map<String, String> values,
                                      Map<String, String> autoVals,
                                      Map<String, String> stringValues) {
        if (stringValues == null) {
            stringValues = Map.of();
        }
        StringBuilder out = new StringBuilder();
        Matcher m = TemplateConstants.ALL_PLACEHOLDER_RE.matcher(template);
        int pos = 0;
        while (m.find()) {
            out.append(template, pos, m.start());
            if (m.group(2) != null) {           // [[字符串]]
                String whole = m.group(2);
                String content = whole.substring(2, whole.length() - 2).trim();
                if (content.isEmpty()) {
                    out.append(whole);
                } else {
                    String v = stringValues.get(content);
                    out.append(v != null ? v : whole);
                }
            } else {                            // {{...}}
                String whole = m.group(1);
                String content = whole.substring(2, whole.length() - 2).trim();
                if (content.isEmpty()) {
                    out.append(whole);
                } else if (TemplateParser.isExpression(content)) {
                    String expr = content.substring(1).trim();
                    try {
                        double r = ExpressionEvaluator.evaluate(expr, values);
                        out.append(String.format(Locale.ROOT, "%.2f", r));
                    } catch (ExpressionEvaluator.EvalException e) {
                        return new RenderResult(null,
                                "表达式「" + expr + "」" + e.getMessage());
                    }
                } else if (autoVals.containsKey(content)) {
                    out.append(autoVals.get(content));
                } else {
                    String v = values.get(content);
                    out.append(v != null ? v : whole);
                }
            }
            pos = m.end();
        }
        out.append(template.substring(pos));
        return new RenderResult(out.toString(), null);
    }
}
