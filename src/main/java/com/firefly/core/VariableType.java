package com.firefly.core;

/** 用户可为普通模板变量选择的输入类型。 */
public enum VariableType {
    NUMBER("数值"),
    SHORT_TEXT("短字符串"),
    MULTILINE_TEXT("多行文本");

    private final String displayName;

    VariableType(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }

    @Override
    public String toString() {
        return displayName;
    }

    public static VariableType fromName(String name, VariableType fallback) {
        if (name != null) {
            try {
                return valueOf(name);
            } catch (IllegalArgumentException ignored) {
                // 损坏或未来版本的类型值按调用方提供的默认值处理。
            }
        }
        return fallback;
    }
}
