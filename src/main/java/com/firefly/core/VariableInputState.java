package com.firefly.core;

import java.util.EnumMap;
import java.util.Map;

/** 单个变量在统一输入系统中的内存状态。 */
public final class VariableInputState {
    private final String name;
    private VariableType type;
    private final EnumMap<VariableType, String> drafts = new EnumMap<>(VariableType.class);
    private final boolean numericLocked;
    private final boolean legacyMultilineSyntax;
    private final boolean braceSyntax;
    private boolean numericAttentionRequired;

    public VariableInputState(String name, VariableType type, String value,
                              boolean numericLocked, boolean legacyMultilineSyntax,
                              boolean braceSyntax) {
        this(name, type, value, Map.of(), numericLocked, legacyMultilineSyntax, braceSyntax);
    }

    public VariableInputState(String name, VariableType type, String value,
                              Map<VariableType, String> savedDrafts,
                              boolean numericLocked, boolean legacyMultilineSyntax,
                              boolean braceSyntax) {
        this.name = name;
        this.numericLocked = numericLocked;
        this.legacyMultilineSyntax = legacyMultilineSyntax;
        this.braceSyntax = braceSyntax;
        if (savedDrafts != null) savedDrafts.forEach((key, draft) -> {
            if (key != null) drafts.put(key, safe(draft));
        });
        VariableType savedType = type == null ? VariableType.NUMBER : type;
        drafts.putIfAbsent(savedType, safe(value));
        this.type = numericLocked ? VariableType.NUMBER : savedType;
        if (numericLocked && !drafts.containsKey(VariableType.NUMBER)) {
            String source = drafts.getOrDefault(savedType, "").trim();
            if (source.isEmpty()) source = firstTextDraft().trim();
            if (!source.isEmpty() && ValueNormalizer.normalize(source) != null) {
                drafts.put(VariableType.NUMBER, source);
            } else if (!source.isEmpty()) {
                numericAttentionRequired = true;
            }
        }
    }

    public String name() { return name; }
    public VariableType type() { return type; }
    public String value() { return drafts.getOrDefault(type, ""); }
    public boolean numericLocked() { return numericLocked; }
    public boolean legacyMultilineSyntax() { return legacyMultilineSyntax; }
    public boolean braceSyntax() { return braceSyntax; }

    public boolean hasDraft(VariableType draftType) { return drafts.containsKey(draftType); }
    public String draft(VariableType draftType) { return drafts.getOrDefault(draftType, ""); }
    public Map<VariableType, String> drafts() { return Map.copyOf(drafts); }
    public boolean requiresNumericAttention() { return numericAttentionRequired; }

    public void activateType(VariableType nextType, String initialDraft) {
        if (numericLocked && nextType != VariableType.NUMBER) return;
        drafts.putIfAbsent(nextType, safe(initialDraft));
        type = nextType;
    }

    public void setType(VariableType type) { activateType(type, ""); }
    public void setValue(String value) {
        drafts.put(type, safe(value));
        if (type == VariableType.NUMBER) numericAttentionRequired = false;
    }
    public void setDraft(VariableType draftType, String value) { drafts.put(draftType, safe(value)); }

    public VariableInputState copyFor(TemplateParser.VariableSpec spec) {
        return new VariableInputState(spec.name(), type, value(), drafts, spec.numericLocked(),
                spec.legacySyntax(), spec.braceSyntax());
    }

    private static String safe(String value) { return value == null ? "" : value; }

    private String firstTextDraft() {
        String shortText = drafts.get(VariableType.SHORT_TEXT);
        if (shortText != null && !shortText.isEmpty()) return shortText;
        return drafts.getOrDefault(VariableType.MULTILINE_TEXT, "");
    }
}
