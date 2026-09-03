package com.firefly.core;

import com.firefly.extraction.MappingProfile;
import java.util.LinkedHashMap;
import java.util.Map;

/** 一个模板的独立变量配置。 */
public final class TemplateConfig {
    public record Entry(VariableType type, String value, Map<VariableType, String> legacySessionValues) {
        public Entry(VariableType type, String value) {
            this(type, value, type == null ? Map.of() : Map.of(type, value == null ? "" : value));
        }

        public Entry {
            legacySessionValues = legacySessionValues == null ? Map.of() : Map.copyOf(legacySessionValues);
        }

        /** 旧调用名仅用于升级期间载入会话，保存器不会写回这些值。 */
        public Map<VariableType, String> drafts() { return legacySessionValues; }
    }

    private final String templateName;
    private final Map<String, Entry> variables = new LinkedHashMap<>();
    private int decimalPlaces = NumericFormatter.DEFAULT_DECIMAL_PLACES;
    private MappingProfile dataExtraction = MappingProfile.EMPTY;

    public MappingProfile dataExtraction() { return dataExtraction; }
    public void setDataExtraction(MappingProfile value) { dataExtraction = value == null ? MappingProfile.EMPTY : value; }

    public TemplateConfig(String templateName) {
        this.templateName = templateName;
    }

    public String templateName() { return templateName; }
    public Map<String, Entry> variables() { return variables; }
    public int decimalPlaces() { return decimalPlaces; }
    public void setDecimalPlaces(int decimalPlaces) {
        this.decimalPlaces = NumericFormatter.clampDecimalPlaces(decimalPlaces);
    }
}
