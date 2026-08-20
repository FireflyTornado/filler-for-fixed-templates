package com.firefly.core;

import java.util.regex.Pattern;

/**
 * 校验并规范化一个数字输入：
 *   * 留空            -> 返回 "0"（按 0 处理）
 *   * 合法数字        -> 返回原样（保留书写格式，如 3.50、-2）
 *   * 非数字 / NaN/∞  -> 返回 null（由界面给出友好提示）
 */
public final class ValueNormalizer {

    /** 普通十进制数字（可带符号、小数、科学计数法），拒绝 NaN/Infinity/十六进制 */
    private static final Pattern PLAIN_NUMBER =
            Pattern.compile("[+-]?(?:\\d+(?:\\.\\d*)?|\\.\\d+)(?:[eE][+-]?\\d+)?");

    private ValueNormalizer() {
    }

    public static String normalize(String raw) {
        if (raw == null) {
            return "0";
        }
        String s = raw.trim();
        if (s.isEmpty()) {
            return "0";
        }
        if (!PLAIN_NUMBER.matcher(s).matches()) {
            return null;
        }
        try {
            double num = Double.parseDouble(s);
            if (Double.isNaN(num) || Double.isInfinite(num)) {
                return null;
            }
        } catch (NumberFormatException e) {
            return null;
        }
        return s;
    }
}
