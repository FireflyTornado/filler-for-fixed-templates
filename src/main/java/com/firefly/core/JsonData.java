package com.firefly.core;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 零依赖的通用 JSON 读写器，仅覆盖配置文件需要的标准 JSON 类型。 */
public final class JsonData {
    private JsonData() { }

    public static Object parse(String json) throws IOException {
        Parser parser = new Parser(json);
        Object value = parser.value();
        parser.ws();
        if (!parser.end()) {
            throw new IOException("JSON 末尾包含多余内容");
        }
        return value;
    }

    public static String stringify(Object value) {
        StringBuilder out = new StringBuilder();
        write(value, out, 0);
        out.append('\n');
        return out.toString();
    }

    private static void write(Object value, StringBuilder out, int indent) {
        if (value == null) {
            out.append("null");
        } else if (value instanceof String text) {
            quote(text, out);
        } else if (value instanceof Number || value instanceof Boolean) {
            out.append(value);
        } else if (value instanceof Map<?, ?> map) {
            out.append('{');
            if (!map.isEmpty()) {
                boolean first = true;
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    out.append(first ? '\n' : ",\n");
                    first = false;
                    spaces(out, indent + 2);
                    quote(String.valueOf(entry.getKey()), out);
                    out.append(": ");
                    write(entry.getValue(), out, indent + 2);
                }
                out.append('\n');
                spaces(out, indent);
            }
            out.append('}');
        } else if (value instanceof List<?> list) {
            out.append('[');
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) out.append(", ");
                write(list.get(i), out, indent);
            }
            out.append(']');
        } else {
            quote(String.valueOf(value), out);
        }
    }

    private static void quote(String text, StringBuilder out) {
        out.append('"');
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                default -> {
                    if (c < 0x20) out.append(String.format("\\u%04x", (int) c));
                    else out.append(c);
                }
            }
        }
        out.append('"');
    }

    private static void spaces(StringBuilder out, int count) {
        out.append(" ".repeat(Math.max(0, count)));
    }

    private static final class Parser {
        private final String text;
        private int at;

        Parser(String text) {
            this.text = text != null && !text.isEmpty() && text.charAt(0) == 0xFEFF
                    ? text.substring(1) : text;
        }

        boolean end() { return at >= text.length(); }
        void ws() { while (!end() && Character.isWhitespace(text.charAt(at))) at++; }

        Object value() throws IOException {
            ws();
            if (end()) throw error("缺少值");
            return switch (text.charAt(at)) {
                case '{' -> object();
                case '[' -> array();
                case '"' -> string();
                case 't' -> literal("true", Boolean.TRUE);
                case 'f' -> literal("false", Boolean.FALSE);
                case 'n' -> literal("null", null);
                default -> number();
            };
        }

        Map<String, Object> object() throws IOException {
            at++;
            Map<String, Object> map = new LinkedHashMap<>();
            ws();
            if (take('}')) return map;
            while (true) {
                ws();
                if (end() || text.charAt(at) != '"') throw error("对象键必须是字符串");
                String key = string();
                ws();
                expect(':');
                map.put(key, value());
                ws();
                if (take('}')) return map;
                expect(',');
            }
        }

        List<Object> array() throws IOException {
            at++;
            List<Object> list = new ArrayList<>();
            ws();
            if (take(']')) return list;
            while (true) {
                list.add(value());
                ws();
                if (take(']')) return list;
                expect(',');
            }
        }

        String string() throws IOException {
            expect('"');
            StringBuilder out = new StringBuilder();
            while (!end()) {
                char c = text.charAt(at++);
                if (c == '"') return out.toString();
                if (c != '\\') {
                    out.append(c);
                    continue;
                }
                if (end()) throw error("字符串转义不完整");
                char escaped = text.charAt(at++);
                switch (escaped) {
                    case '"', '\\', '/' -> out.append(escaped);
                    case 'b' -> out.append('\b');
                    case 'f' -> out.append('\f');
                    case 'n' -> out.append('\n');
                    case 'r' -> out.append('\r');
                    case 't' -> out.append('\t');
                    case 'u' -> {
                        if (at + 4 > text.length()) throw error("Unicode 转义不完整");
                        try {
                            out.append((char) Integer.parseInt(text.substring(at, at + 4), 16));
                        } catch (NumberFormatException e) {
                            throw error("Unicode 转义无效");
                        }
                        at += 4;
                    }
                    default -> throw error("未知字符串转义");
                }
            }
            throw error("字符串未闭合");
        }

        Object number() throws IOException {
            int start = at;
            while (!end() && "-+0123456789.eE".indexOf(text.charAt(at)) >= 0) at++;
            if (start == at) throw error("无法识别的值");
            String token = text.substring(start, at);
            try {
                return token.contains(".") || token.contains("e") || token.contains("E")
                        ? Double.parseDouble(token) : Long.parseLong(token);
            } catch (NumberFormatException e) {
                throw error("数字格式错误");
            }
        }

        Object literal(String literal, Object value) throws IOException {
            if (!text.startsWith(literal, at)) throw error("无法识别的值");
            at += literal.length();
            return value;
        }

        boolean take(char c) {
            if (!end() && text.charAt(at) == c) { at++; return true; }
            return false;
        }

        void expect(char c) throws IOException {
            if (!take(c)) throw error("缺少 '" + c + "'");
        }

        IOException error(String message) {
            return new IOException("JSON 格式错误（位置 " + at + "）：" + message);
        }
    }
}
