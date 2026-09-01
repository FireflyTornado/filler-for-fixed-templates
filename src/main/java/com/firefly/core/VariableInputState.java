package com.firefly.core;

/** 单个变量在统一输入系统中的内存状态。 */
public final class VariableInputState {
    private final String name;
    private VariableType type;
    private String value;
    private final boolean numericLocked;
    private final boolean legacyMultilineSyntax;
    private final boolean braceSyntax;

    public VariableInputState(String name, VariableType type, String value,
                              boolean numericLocked, boolean legacyMultilineSyntax,
                              boolean braceSyntax) {
        this.name = name;
        this.type = numericLocked ? VariableType.NUMBER : type;
        this.value = value == null ? "" : value;
        this.numericLocked = numericLocked;
        this.legacyMultilineSyntax = legacyMultilineSyntax;
        this.braceSyntax = braceSyntax;
    }

    public String name() { return name; }
    public VariableType type() { return type; }
    public String value() { return value; }
    public boolean numericLocked() { return numericLocked; }
    public boolean legacyMultilineSyntax() { return legacyMultilineSyntax; }
    public boolean braceSyntax() { return braceSyntax; }

    public void setType(VariableType type) {
        this.type = numericLocked ? VariableType.NUMBER : type;
    }

    public void setValue(String value) {
        this.value = value == null ? "" : value;
    }

    public VariableInputState copyFor(TemplateParser.VariableSpec spec) {
        VariableType nextType = spec.numericLocked() ? VariableType.NUMBER : type;
        return new VariableInputState(spec.name(), nextType, value, spec.numericLocked(),
                spec.legacySyntax(), spec.braceSyntax());
    }
}
