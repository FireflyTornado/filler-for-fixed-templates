package com.firefly.application;

import com.firefly.TemplateConstants;
import com.firefly.core.TemplateParser;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Map;
import java.util.Set;

/** 生成开始时的独立输入快照；不持有窗口或可变变量状态。 */
public record GenerationRequest(long sequence, boolean word, String templateName,
                                String templateText, Path sourceFile, Map<String, String> values,
                                Map<String, String> autoValues, Set<String> numericVariables,
                                int decimalPlaces, TemplateParser.ParsedTemplate parsed, LocalDate date,
                                long templateRevision, long inputRevision) {
    public GenerationRequest {
        values = Map.copyOf(values);
        autoValues = Map.copyOf(autoValues);
        numericVariables = Set.copyOf(numericVariables);
        parsed = new TemplateParser.ParsedTemplate(java.util.List.copyOf(parsed.inputVariables()),
                java.util.List.copyOf(parsed.autoVariables()), parsed.expressionCount(),
                java.util.List.copyOf(parsed.variables()), Set.copyOf(parsed.expressionVariables()));
    }

    public static GenerationRequest capture(long sequence, boolean word, Path sourceFile,
                                            TemplateSession session, LocalDate date) {
        if (session.hasDependencyErrors()) {
            throw new IllegalArgumentException(String.join("；", session.dependencyErrorMessages()));
        }
        VariableValidation.Result validation = VariableValidation.validate(session.variables());
        if (!validation.valid()) throw new IllegalArgumentException("无效数值变量：" + validation.invalidNames());
        return new GenerationRequest(sequence, word, session.templateName(), session.templateText(), sourceFile,
                validation.values(), TemplateConstants.autoValues(date), validation.numericVariables(),
                session.decimalPlaces(), TemplateParser.parse(session.templateText()), date,
                session.templateRevision(), session.inputRevision());
    }

    public boolean isStale(TemplateSession session) {
        return templateRevision != session.templateRevision() || inputRevision != session.inputRevision()
                || !templateName.equals(session.templateName());
    }
}
