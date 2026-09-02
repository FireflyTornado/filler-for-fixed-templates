package com.firefly.core;

import java.util.EnumMap;
import java.util.Map;

/** 单个变量在统一输入系统中的内存状态。 */
public final class VariableInputState {
    private final String name;
    private VariableType type;
    private final EnumMap<VariableType, String> sessionValues = new EnumMap<>(VariableType.class);
    private final boolean numericLocked;
    private boolean numericAttentionRequired;

    public VariableInputState(String name, VariableType type, String value,
                              boolean numericLocked) {
        this(name, type, value, Map.of(), numericLocked);
    }

    public VariableInputState(String name, VariableType type, String value,
                              Map<VariableType, String> savedDrafts,
                              boolean numericLocked) {
        this.name = name;
        this.numericLocked = numericLocked;
        if (savedDrafts != null) savedDrafts.forEach((key, draft) -> {
            if (key != null) sessionValues.put(key, safe(draft));
        });
        VariableType savedType = type == null ? VariableType.NUMBER : type;
        sessionValues.putIfAbsent(savedType, safe(value));
        this.type = numericLocked ? VariableType.NUMBER : savedType;
        if (numericLocked && !sessionValues.containsKey(VariableType.NUMBER)) {
            String source = sessionValues.getOrDefault(savedType, "").trim();
            if (source.isEmpty()) source = firstTextDraft().trim();
            if (!source.isEmpty() && ValueNormalizer.normalize(source) != null) {
                sessionValues.put(VariableType.NUMBER, source);
            } else if (!source.isEmpty()) {
                numericAttentionRequired = true;
            }
        }
    }

    public String name() { return name; }
    public VariableType type() { return type; }
    public String value() { return sessionValues.getOrDefault(type, ""); }
    public boolean numericLocked() { return numericLocked; }

    public boolean hasDraft(VariableType draftType) { return sessionValues.containsKey(draftType); }
    public String draft(VariableType draftType) { return sessionValues.getOrDefault(draftType, ""); }
    public Map<VariableType, String> sessionValues() { return Map.copyOf(sessionValues); }
    /** 兼容现有调用；返回的内容只可用于当前会话，存储层不得序列化。 */
    public Map<VariableType, String> drafts() { return sessionValues(); }
    public boolean hasOtherTypeValues() {
        return sessionValues.keySet().stream().anyMatch(candidate -> candidate != type);
    }
    public void clearOtherTypeValues() {
        String current = value();
        sessionValues.clear();
        sessionValues.put(type, current);
    }
    public boolean requiresNumericAttention() { return numericAttentionRequired; }

    public void activateType(VariableType nextType, String initialDraft) {
        if (numericLocked && nextType != VariableType.NUMBER) return;
        sessionValues.putIfAbsent(nextType, safe(initialDraft));
        type = nextType;
    }

    public void setType(VariableType type) { activateType(type, ""); }
    public void setValue(String value) {
        sessionValues.put(type, safe(value));
        if (type == VariableType.NUMBER) numericAttentionRequired = false;
    }
    public void setDraft(VariableType draftType, String value) { sessionValues.put(draftType, safe(value)); }

    public VariableInputState copyFor(TemplateParser.VariableSpec spec) {
        return new VariableInputState(spec.name(), type, value(), sessionValues, spec.numericLocked());
    }

    private static String safe(String value) { return value == null ? "" : value; }

    private String firstTextDraft() {
        String shortText = sessionValues.get(VariableType.SHORT_TEXT);
        if (shortText != null && !shortText.isEmpty()) return shortText;
        return sessionValues.getOrDefault(VariableType.MULTILINE_TEXT, "");
    }
}
