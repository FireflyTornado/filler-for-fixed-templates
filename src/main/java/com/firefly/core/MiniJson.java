package com.firefly.core;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 极简 JSON 读写，只用于 last_values.json 这种「扁平字符串键值对」结构，
 * 避免引入第三方 JSON 依赖。
 */
public final class MiniJson {

    private MiniJson() {
    }

    /** 把一个扁平字符串映射序列化成 JSON（带缩进，与 Python 的 indent=2 类似）。 */
    public static String toJson(Map<String, String> map) {
        StringBuilder sb = new StringBuilder("{\n");
        boolean first = true;
        for (Map.Entry<String, String> e : map.entrySet()) {
            if (!first) {
                sb.append(",\n");
            }
            first = false;
            sb.append("  ").append(quote(e.getKey())).append(": ").append(quote(e.getValue()));
        }
        sb.append("\n}");
        return sb.toString();
    }

    /** 解析扁平字符串映射；值也可以是数字 / true / false / null（转成字符串）。 */
    public static Map<String, String> parseFlatStringMap(String json) throws IOException {
        Parser p = new Parser(json);
        Map<String, String> map = new LinkedHashMap<>();
        p.skipWs();
        p.expect('{');
        p.skipWs();
        if (p.peek() == '}') {
            return map;
        }
        while (true) {
            p.skipWs();
            String key = p.parseString();
            p.skipWs();
            p.expect(':');
            p.skipWs();
            String value = p.parseValue();
            map.put(key, value);
            p.skipWs();
            char c = p.next();
            if (c == ',') {
                continue;
            }
            if (c == '}') {
                break;
            }
            throw new IOException("JSON 格式错误：意外的字符 '" + c + "'");
        }
        return map;
    }

    /** 转义一个 JSON 字符串字面量。 */
    private static String quote(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        sb.append('"');
        return sb.toString();
    }

    /** 手写的 JSON 扫描器。 */
    private static final class Parser {
        private final String s;
        private int pos;

        Parser(String s) {
            this.s = s;
        }

        char peek() {
            return s.charAt(pos);
        }

        char next() {
            return s.charAt(pos++);
        }

        void expect(char c) throws IOException {
            if (pos >= s.length() || s.charAt(pos) != c) {
                throw new IOException("JSON 格式错误：缺少 '" + c + "'");
            }
            pos++;
        }

        void skipWs() {
            while (pos < s.length()) {
                char c = s.charAt(pos);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                    pos++;
                } else {
                    break;
                }
            }
        }

        /** 解析字符串字面量（支持反斜杠加 u 开头的四位数 Unicode 转义等常见转义）。 */
        String parseString() throws IOException {
            if (pos >= s.length() || s.charAt(pos) != '"') {
                throw new IOException("JSON 格式错误：缺少字符串");
            }
            pos++;
            StringBuilder sb = new StringBuilder();
            while (pos < s.length()) {
                char c = s.charAt(pos++);
                if (c == '"') {
                    return sb.toString();
                }
                if (c == '\\') {
                    if (pos >= s.length()) {
                        throw new IOException("JSON 格式错误：转义不完整");
                    }
                    char e = s.charAt(pos++);
                    switch (e) {
                        case '"': sb.append('"'); break;
                        case '\\': sb.append('\\'); break;
                        case '/': sb.append('/'); break;
                        case 'b': sb.append('\b'); break;
                        case 'f': sb.append('\f'); break;
                        case 'n': sb.append('\n'); break;
                        case 'r': sb.append('\r'); break;
                        case 't': sb.append('\t'); break;
                        case 'u':
                            if (pos + 4 > s.length()) {
                                throw new IOException("JSON 格式错误：\\u 转义不完整");
                            }
                            String hex = s.substring(pos, pos + 4);
                            pos += 4;
                            try {
                                sb.append((char) Integer.parseInt(hex, 16));
                            } catch (NumberFormatException ex) {
                                throw new IOException("JSON 格式错误：\\u 转义无效");
                            }
                            break;
                        default:
                            throw new IOException("JSON 格式错误：未知转义 '\\" + e + "'");
                    }
                } else {
                    sb.append(c);
                }
            }
            throw new IOException("JSON 格式错误：字符串未闭合");
        }

        /** 解析值：只接受字符串、数字、true/false/null（扁平映射场景够用）。 */
        String parseValue() throws IOException {
            if (pos >= s.length()) {
                throw new IOException("JSON 格式错误：缺少值");
            }
            char c = s.charAt(pos);
            if (c == '"') {
                return parseString();
            }
            int start = pos;
            while (pos < s.length()) {
                c = s.charAt(pos);
                if (c == ',' || c == '}') {
                    break;
                }
                pos++;
            }
            String token = s.substring(start, pos).trim();
            if (token.isEmpty()) {
                throw new IOException("JSON 格式错误：缺少值");
            }
            if ("null".equals(token)) {
                return ""; // 与 Python json 把 null -> None 的处理对齐
            }
            return token;
        }
    }
}
