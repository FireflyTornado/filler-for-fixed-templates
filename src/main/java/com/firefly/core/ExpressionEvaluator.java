package com.firefly.core;

import com.firefly.TemplateConstants;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 安全算术表达式求值；[变量名] 用于显式引用纯数字或含特殊字符的变量名。 */
public final class ExpressionEvaluator {
    private static final String BAD_PART_MSG = "包含无法识别的部分。";

    private ExpressionEvaluator() { }

    public static final class EvalException extends Exception {
        public EvalException(String message) { super(message); }
    }

    /** 返回表达式依赖的变量；与实际求值共用同一个分词器。 */
    public static List<String> referencedVariables(String expr) throws EvalException {
        Lexer lexer = new Lexer(expr);
        Set<String> names = new LinkedHashSet<>();
        Token token;
        do {
            token = lexer.next();
            if (token.type == TokenType.VARIABLE) names.add(token.text);
        } while (token.type != TokenType.EOF);
        return List.copyOf(names);
    }

    public static double evaluate(String expr, Map<String, String> values) throws EvalException {
        Parser parser = new Parser(expr, values == null ? Map.of() : values);
        double result = parser.parse();
        if (Double.isNaN(result) || Double.isInfinite(result)) {
            throw new EvalException("的结果不是有效数字。");
        }
        return result;
    }

    private enum TokenType { NUMBER, VARIABLE, PLUS, MINUS, STAR, SLASH, POWER, LPAREN, RPAREN, EOF }
    private record Token(TokenType type, String text, double number) { }

    private static final class Lexer {
        private final String source;
        private int pos;

        Lexer(String source) { this.source = source == null ? "" : source; }

        Token next() throws EvalException {
            skipWs();
            if (pos >= source.length()) return new Token(TokenType.EOF, "", 0);
            char c = source.charAt(pos);
            switch (c) {
                case '+' -> { pos++; return simple(TokenType.PLUS, "+"); }
                case '-' -> { pos++; return simple(TokenType.MINUS, "-"); }
                case '/' -> { pos++; return simple(TokenType.SLASH, "/"); }
                case '(' -> { pos++; return simple(TokenType.LPAREN, "("); }
                case ')' -> { pos++; return simple(TokenType.RPAREN, ")"); }
                case '*' -> {
                    pos++;
                    if (pos < source.length() && source.charAt(pos) == '*') {
                        pos++;
                        return simple(TokenType.POWER, "**");
                    }
                    return simple(TokenType.STAR, "*");
                }
                case '[' -> { return bracketVariable(); }
                default -> { }
            }
            if (Character.isDigit(c) || c == '.') return number();
            if (isIdentifierStart(c)) return bareVariable();
            throw new EvalException(BAD_PART_MSG);
        }

        private Token bracketVariable() throws EvalException {
            int start = ++pos;
            while (pos < source.length() && source.charAt(pos) != ']') pos++;
            if (pos >= source.length()) throw new EvalException("中的变量引用缺少 ]。");
            String name = source.substring(start, pos++).trim();
            if (name.isEmpty()) throw new EvalException("中的变量名不能为空。");
            return new Token(TokenType.VARIABLE, name, 0);
        }

        private Token bareVariable() {
            int start = pos++;
            while (pos < source.length() && isIdentifierPart(source.charAt(pos))) pos++;
            return new Token(TokenType.VARIABLE, source.substring(start, pos), 0);
        }

        private Token number() throws EvalException {
            int start = pos;
            boolean digit = false, dot = false;
            while (pos < source.length()) {
                char c = source.charAt(pos);
                if (Character.isDigit(c)) { digit = true; pos++; }
                else if (c == '.' && !dot) { dot = true; pos++; }
                else break;
            }
            if (!digit) throw new EvalException(BAD_PART_MSG);
            if (pos < source.length() && (source.charAt(pos) == 'e' || source.charAt(pos) == 'E')) {
                int exponent = pos++;
                if (pos < source.length() && (source.charAt(pos) == '+' || source.charAt(pos) == '-')) pos++;
                int exponentDigits = pos;
                while (pos < source.length() && Character.isDigit(source.charAt(pos))) pos++;
                if (pos == exponentDigits) pos = exponent;
            }
            String text = source.substring(start, pos);
            try { return new Token(TokenType.NUMBER, text, Double.parseDouble(text)); }
            catch (NumberFormatException e) { throw new EvalException(BAD_PART_MSG); }
        }

        private void skipWs() { while (pos < source.length() && Character.isWhitespace(source.charAt(pos))) pos++; }
        private static Token simple(TokenType type, String text) { return new Token(type, text, 0); }
        private static boolean isIdentifierStart(char c) {
            return Character.isLetter(c) || Character.getType(c) == Character.NON_SPACING_MARK;
        }
        private static boolean isIdentifierPart(char c) {
            return isIdentifierStart(c) || Character.isDigit(c) || c == '_';
        }
    }

    private static final class Parser {
        private final Lexer lexer;
        private final Map<String, String> values;
        private Token token;

        Parser(String source, Map<String, String> values) throws EvalException {
            lexer = new Lexer(source);
            this.values = values;
            token = lexer.next();
        }

        double parse() throws EvalException {
            double value = expression();
            if (token.type != TokenType.EOF) throw new EvalException(BAD_PART_MSG);
            return value;
        }

        private double expression() throws EvalException {
            double value = term();
            while (token.type == TokenType.PLUS || token.type == TokenType.MINUS) {
                TokenType op = token.type; advance();
                double rhs = term();
                value = op == TokenType.PLUS ? value + rhs : value - rhs;
            }
            return value;
        }

        private double term() throws EvalException {
            double value = factor();
            while (token.type == TokenType.STAR || token.type == TokenType.SLASH) {
                TokenType op = token.type; advance();
                double rhs = factor();
                if (op == TokenType.SLASH && rhs == 0.0) {
                    throw new EvalException("除数为 0（留空的变量按 0 处理），无法计算。");
                }
                value = op == TokenType.STAR ? value * rhs : value / rhs;
            }
            return value;
        }

        private double factor() throws EvalException {
            if (token.type == TokenType.PLUS || token.type == TokenType.MINUS) {
                TokenType op = token.type; advance();
                double value = factor();
                return op == TokenType.MINUS ? -value : value;
            }
            return power();
        }

        private double power() throws EvalException {
            double base = atom();
            if (token.type == TokenType.POWER) { advance(); return Math.pow(base, power()); }
            return base;
        }

        private double atom() throws EvalException {
            if (token.type == TokenType.NUMBER) { double value = token.number; advance(); return value; }
            if (token.type == TokenType.VARIABLE) { String name = token.text; advance(); return variableValue(name); }
            if (token.type == TokenType.LPAREN) {
                advance();
                double value = expression();
                if (token.type != TokenType.RPAREN) throw new EvalException(BAD_PART_MSG);
                advance();
                return value;
            }
            throw new EvalException(BAD_PART_MSG);
        }

        private double variableValue(String name) throws EvalException {
            if (TemplateConstants.AUTO_VAR_SET.contains(name)) {
                throw new EvalException("自动日期变量「" + name + "」不能参与运算");
            }
            String value = values.get(name);
            if (value == null) throw new EvalException("变量「" + name + "」未定义");
            value = value.trim();
            if (value.isEmpty()) return 0;
            try { return Double.parseDouble(value); }
            catch (NumberFormatException e) { throw new EvalException("变量「" + name + "」不是有效数字。"); }
        }

        private void advance() throws EvalException { token = lexer.next(); }
    }
}
