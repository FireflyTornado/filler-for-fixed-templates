package com.firefly.core;

import java.util.LinkedHashMap;
import java.util.Map;

/** 一个模板的独立变量配置。 */
public final class TemplateConfig {
    public record Entry(VariableType type, String value, Map<VariableType, String> drafts) {
        public Entry(VariableType type, String value) {
            this(type, value, type == null ? Map.of() : Map.of(type, value == null ? "" : value));
        }

        public Entry {
            drafts = drafts == null ? Map.of() : Map.copyOf(drafts);
        }
    }

    private final String templateName;
    private final Map<String, Entry> variables = new LinkedHashMap<>();

    public TemplateConfig(String templateName) {
        this.templateName = templateName;
    }

    public String templateName() { return templateName; }
    public Map<String, Entry> variables() { return variables; }
}
