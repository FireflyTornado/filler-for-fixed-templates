package com.firefly.core;

import com.firefly.TemplateConstants;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 安全的算术表达式求值（支持 + - * / ** 和括号，变量名可含中文）。
 * 规则：
 *   * 先正则提取变量名并替换成用户输入的数字（空值按 0）
 *   * 替换结果必须只能包含数字、运算符、括号和小数点，否则拒绝
 *   * 用自写的递归下降解析器求值，不依赖任何动态求值能力
 */
public final class ExpressionEvaluator {

    /** 替换完变量后，表达式中只允许出现这些字符 */
    private static final Pattern ALLOWED_CHARS = Pattern.compile("[\\d+\\-*/().\\s]*");

    private ExpressionEvaluator() {
    }

    /** 求值失败时抛出；message 已去掉「表达式…」前缀，由渲染层负责拼接。 */
    public static final class EvalException extends Exception {
        public EvalException(String message) {
            super(message);
        }
    }

    /**
     * 计算占位符里的算术表达式。
     *
     * @param expr   表达式原文（如 "数量*单价"）
     * @param values 变量名 -> 数值字符串（如 "3"、"12.5"）
     * @return 计算结果
     */
    public static double evaluate(String expr, Map<String, String> values) throws EvalException {
        String substituted = substituteIdentifiers(expr, values);
        if (!ALLOWED_CHARS.matcher(substituted).matches()) {
            throw new EvalException("包含无法识别的部分。");
        }
        try {
            Parser parser = new Parser(substituted);
            double result = parser.parse();
            if (Double.isNaN(result) || Double.isInfinite(result)) {
                throw new EvalException("的结果不是有效数字。");
            }
            return result;
        } catch (EvalException e) {
            throw e;
        } catch (Exception e) {
            throw new EvalException("计算失败：" + e.getMessage());
        }
    }

    /** 用变量值替换表达式里的变量名；空值按 0 处理；自动日期变量 / 未定义变量报错。 */
    private static String substituteIdentifiers(String expr, Map<String, String> values)
            throws EvalException {
        StringBuilder sb = new StringBuilder();
        Matcher m = TemplateConstants.IDENT_RE.matcher(expr);
        int pos = 0;
        while (m.find()) {
            sb.append(expr, pos, m.start());
            sb.append(lookup(m.group(), values));
            pos = m.end();
        }
        sb.append(expr.substring(pos));
        return sb.toString();
    }

    private static String lookup(String name, Map<String, String> values) throws EvalException {
        if (TemplateConstants.AUTO_VAR_SET.contains(name)) {
            throw new EvalException("自动日期变量「" + name + "」不能参与运算");
        }
        String v = values.get(name);
        if (v == null) {
            throw new EvalException("变量「" + name + "」未定义");
        }
        v = v.trim();
        return v.isEmpty() ? "0" : v;
    }

    /**
     * 递归下降解析器：
     *   expression -> term ((+|-) term)*
     *   term       -> factor ((*|/) factor)*
     *   factor     -> (+|-) factor | power
     *   power      -> atom (** power)?    （右结合，与 Python ** 一致）
     *   atom       -> number | ( expression )
     */
    private static final class Parser {
        private final String s;
        private int pos;

        Parser(String s) {
            this.s = s;
        }

        double parse() throws EvalException {
            double v = parseExpression();
            skipWs();
            if (pos < s.length()) {
                throw new EvalException("包含无法识别的部分。");
            }
            return v;
        }

        private double parseExpression() throws EvalException {
            double v = parseTerm();
            while (true) {
                skipWs();
                if (pos < s.length() && (s.charAt(pos) == '+' || s.charAt(pos) == '-')) {
                    char op = s.charAt(pos++);
                    double rhs = parseTerm();
                    v = (op == '+') ? v + rhs : v - rhs;
                } else {
                    break;
                }
            }
            return v;
        }

        private double parseTerm() throws EvalException {
            double v = parseFactor();
            while (true) {
                skipWs();
                if (pos < s.length() && (s.charAt(pos) == '*' || s.charAt(pos) == '/')) {
                    char op = s.charAt(pos++);
                    double rhs = parseFactor();
                    if (op == '/') {
                        if (rhs == 0.0) {
                            throw new EvalException("除数为 0（留空的变量按 0 处理），无法计算。");
                        }
                        v = v / rhs;
                    } else {
                        v = v * rhs;
                    }
                } else {
                    break;
                }
            }
            return v;
        }

        private double parseFactor() throws EvalException {
            skipWs();
            if (pos >= s.length()) {
                throw new EvalException("包含无法识别的部分。");
            }
            char c = s.charAt(pos);
            if (c == '+' || c == '-') {
                pos++;
                double v = parseFactor();
                return (c == '-') ? -v : v;
            }
            return parsePower();
        }

        private double parsePower() throws EvalException {
            double base = parseAtom();
            skipWs();
            if (pos + 1 < s.length() && s.charAt(pos) == '*' && s.charAt(pos + 1) == '*') {
                pos += 2;
                double exponent = parsePower(); // 右结合
                return Math.pow(base, exponent);
            }
            return base;
        }

        private double parseAtom() throws EvalException {
            skipWs();
            if (pos >= s.length()) {
                throw new EvalException("包含无法识别的部分。");
            }
            char c = s.charAt(pos);
            if (c == '(') {
                pos++;
                double v = parseExpression();
                skipWs();
                if (pos >= s.length() || s.charAt(pos) != ')') {
                    throw new EvalException("包含无法识别的部分。");
                }
                pos++;
                return v;
            }
            if (Character.isDigit(c) || c == '.') {
                return parseNumber();
            }
            throw new EvalException("包含无法识别的部分。");
        }

        /** 解析数字：整数 / 小数 / 科学计数法，如 5、3.5、.5、2e3、1.5E-2。 */
        private double parseNumber() throws EvalException {
            int start = pos;
            boolean hasDot = false;
            while (pos < s.length()) {
                char ch = s.charAt(pos);
                if (ch >= '0' && ch <= '9') {
                    pos++;
                } else if (ch == '.' && !hasDot) {
                    hasDot = true;
                    pos++;
                } else {
                    break;
                }
            }
            if (pos < s.length() && (s.charAt(pos) == 'e' || s.charAt(pos) == 'E')) {
                int save = pos;
                pos++;
                if (pos < s.length() && (s.charAt(pos) == '+' || s.charAt(pos) == '-')) {
                    pos++;
                }
                int digitStart = pos;
                while (pos < s.length() && Character.isDigit(s.charAt(pos))) {
                    pos++;
                }
                if (pos == digitStart) {
                    pos = save; // 无效的指数部分，当作数字结束
                }
            }
            String numStr = s.substring(start, pos);
            if (numStr.isEmpty() || ".".equals(numStr)) {
                throw new EvalException("包含无法识别的部分。");
            }
            try {
                return Double.parseDouble(numStr);
            } catch (NumberFormatException e) {
                throw new EvalException("包含无法识别的部分。");
            }
        }

        private void skipWs() {
            while (pos < s.length() && Character.isWhitespace(s.charAt(pos))) {
                pos++;
            }
        }
    }
}
