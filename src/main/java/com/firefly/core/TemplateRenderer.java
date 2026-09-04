package com.firefly.core;

import com.firefly.TemplateConstants;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * 把模板渲染成最终结果。
 * 规则：
 *   * 普通 {{变量名}}           -> 用户输入的值（纯数字内容也作为变量名处理）
 *   * 自动变量                   -> 日历基准日期的派生文本或数值
 *   * 数值变量和表达式结果       -> 按当前模板设置统一保留小数位数
 *   * 文本变量中的占位符         -> 递归展开；反斜杠转义后始终作为纯文本
 *   * 已弃用的 [[变量]]          -> 原样保留，由加载模板时的迁移功能处理
 */
public final class TemplateRenderer {
    private static final int MAX_NESTING_DEPTH = 20;
    private static final int MAX_RESULT_LENGTH = 1_048_576;

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
        PreparedValues prepared = prepareValues(values, autoVals, numericVariables, decimalPlaces);
        if (prepared.error() != null) return new RenderResult(null, prepared.error());
        return renderPrepared(template, prepared.values(), values, autoVals,
                numericVariables, decimalPlaces);
    }

    /** 先递归展开文本变量；数值变量保留原始值，避免表达式计算前损失精度。 */
    public static PreparedValues prepareValues(Map<String, String> values,
                                               Map<String, String> autoVals,
                                               Set<String> numericVariables,
                                               int decimalPlaces) {
        Map<String, String> prepared = new LinkedHashMap<>();
        try {
            for (String name : values.keySet()) resolveVariableValue(name, values, autoVals,
                    numericVariables == null ? Set.of() : numericVariables,
                    decimalPlaces, prepared, new LinkedHashSet<>(), 0);
            return new PreparedValues(Map.copyOf(prepared), null);
        } catch (RenderException | ExpressionEvaluator.EvalException e) {
            return new PreparedValues(Map.of(), e.getMessage());
        }
    }

    public record PreparedValues(Map<String, String> values, String error) { }

    /** 使用已递归展开的变量值渲染一段文本；插入的结果不会被再次扫描。 */
    public static RenderResult renderPrepared(String template,
                                              Map<String, String> preparedValues,
                                              Map<String, String> expressionValues,
                                              Map<String, String> autoVals,
                                              Set<String> numericVariables,
                                              int decimalPlaces) {
        StringBuilder out = new StringBuilder();
        int pos = 0;
        for (TemplateSyntax.Placeholder placeholder : TemplateSyntax.placeholders(template)) {
            out.append(template, pos, placeholder.start()).append(placeholder.literalPrefix());
            String whole = placeholder.whole();
            String content = placeholder.content();
            if (placeholder.escaped()) {
                out.append(whole);
                pos = placeholder.end();
                continue;
            }
            if (content.isEmpty()) {
                out.append(whole);              // {{}} 原样保留
            } else {
                try {
                    out.append(resolve(whole, content, preparedValues, autoVals,
                            expressionValues,
                            numericVariables, decimalPlaces));
                } catch (ExpressionEvaluator.EvalException e) {
                    String expr = content.substring(1).trim();
                    return new RenderResult(null, "表达式「" + expr + "」" + e.getMessage());
                }
            }
            if (out.length() > MAX_RESULT_LENGTH) return new RenderResult(null, "展开结果超过 1 MB 限制");
            pos = placeholder.end();
        }
        out.append(template.substring(pos));
        if (out.length() > MAX_RESULT_LENGTH) return new RenderResult(null, "展开结果超过 1 MB 限制");
        return new RenderResult(out.toString(), null);
    }

    private static String resolveVariableValue(String name, Map<String, String> values,
                                               Map<String, String> autoVals,
                                               Set<String> numericVariables, int decimalPlaces,
                                               Map<String, String> prepared, LinkedHashSet<String> stack,
                                               int depth)
            throws RenderException, ExpressionEvaluator.EvalException {
        if (prepared.containsKey(name)) return prepared.get(name);
        String raw = values.get(name);
        if (raw == null) return null;
        if (numericVariables.contains(name)) {
            prepared.put(name, raw);
            return raw;
        }
        if (depth >= MAX_NESTING_DEPTH) throw new RenderException("变量嵌套超过 " + MAX_NESTING_DEPTH + " 层：" + name);
        if (!stack.add(name)) {
            StringBuilder path = new StringBuilder();
            boolean append = false;
            for (String item : stack) {
                if (item.equals(name)) append = true;
                if (append) {
                    if (path.length() > 0) path.append(" → ");
                    path.append(item);
                }
            }
            if (path.length() > 0) path.append(" → ");
            path.append(name);
            throw new RenderException("循环引用：" + path);
        }
        Map<String, String> dependencies = new LinkedHashMap<>();
        for (TemplateSyntax.Placeholder placeholder : TemplateSyntax.placeholders(raw)) {
            if (placeholder.escaped() || placeholder.content().isEmpty()
                    || TemplateParser.isExpression(placeholder.content())
                    || autoVals.containsKey(placeholder.content())) continue;
            String child = placeholder.content();
            String value = resolveVariableValue(child, values, autoVals, numericVariables,
                    decimalPlaces, prepared, stack, depth + 1);
            if (value != null) dependencies.put(child, value);
        }
        RenderResult rendered = renderPrepared(raw, dependencies, values, autoVals,
                numericVariables, decimalPlaces);
        stack.remove(name);
        if (rendered.hasError()) throw new RenderException("变量“" + name + "”中的" + rendered.error());
        prepared.put(name, rendered.result());
        return rendered.result();
    }

    private static final class RenderException extends Exception {
        RenderException(String message) { super(message); }
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
        return resolve(whole, content, values, autoVals, values, numericVariables, decimalPlaces);
    }

    private static String resolve(String whole, String content,
                                  Map<String, String> values,
                                  Map<String, String> autoVals,
                                  Map<String, String> rawExpressionValues,
                                  Set<String> numericVariables,
                                  int decimalPlaces)
            throws ExpressionEvaluator.EvalException {
        if (TemplateParser.isExpression(content)) {
            String expr = content.substring(1).trim();
            double r = ExpressionEvaluator.evaluate(expr, expressionValues(rawExpressionValues, autoVals));
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
