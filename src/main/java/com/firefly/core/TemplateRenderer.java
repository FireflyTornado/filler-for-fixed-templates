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
            boolean isString = m.group(2) != null;
            String whole = isString ? m.group(2) : m.group(1);
            String content = whole.substring(2, whole.length() - 2).trim();
            if (content.isEmpty()) {
                out.append(whole);              // {{}} / [[]] 原样保留
            } else {
                try {
                    out.append(resolve(whole, content, isString, values, autoVals, stringValues));
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
     * @param whole        占位符原文（如 "{{数量}}" / "[[备注]]"）
     * @param content      去掉定界符并 trim 后的内容（如 "数量" / "备注"）
     * @param isString     是否为 [[字符串]] 占位符
     * @param values       {{变量名}} 的值
     * @param autoVals     自动日期变量的值
     * @param stringValues [[字符串]] 的值
     * @return 替换后的文本；没有对应值时原样返回占位符本身
     */
    public static String resolve(String whole, String content, boolean isString,
                                 Map<String, String> values,
                                 Map<String, String> autoVals,
                                 Map<String, String> stringValues)
            throws ExpressionEvaluator.EvalException {
        if (isString) {
            String v = stringValues.get(content);
            return v != null ? v : whole;
        }
        if (TemplateParser.isExpression(content)) {
            String expr = content.substring(1).trim();
            double r = ExpressionEvaluator.evaluate(expr, values);
            return String.format(Locale.ROOT, "%.2f", r);
        }
        if (autoVals.containsKey(content)) {
            return autoVals.get(content);
        }
        String v = values.get(content);
        return v != null ? v : whole;
    }
}
