package com.firefly.core;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** 普通数值替换与表达式结果共用的定点小数格式。 */
public final class NumericFormatter {
    public static final int DEFAULT_DECIMAL_PLACES = 2;
    public static final int MIN_DECIMAL_PLACES = 0;
    public static final int MAX_DECIMAL_PLACES = 10;

    private NumericFormatter() { }

    public static int clampDecimalPlaces(int value) {
        return Math.max(MIN_DECIMAL_PLACES, Math.min(MAX_DECIMAL_PLACES, value));
    }

    /** 精确保留用户输入的十进制含义，并按统一规则四舍五入。 */
    public static String format(String value, int decimalPlaces) {
        String normalized = ValueNormalizer.normalize(value);
        if (normalized == null) return value;
        return new BigDecimal(normalized)
                .setScale(clampDecimalPlaces(decimalPlaces), RoundingMode.HALF_UP)
                .toPlainString();
    }

    /** 表达式当前使用 double 求值；valueOf 可避免直接展开其二进制尾差。 */
    public static String format(double value, int decimalPlaces) {
        return BigDecimal.valueOf(value)
                .setScale(clampDecimalPlaces(decimalPlaces), RoundingMode.HALF_UP)
                .toPlainString();
    }
}
