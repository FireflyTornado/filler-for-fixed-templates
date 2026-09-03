package com.firefly.application;

import com.firefly.core.ValueNormalizer;
import com.firefly.core.VariableInputState;
import com.firefly.core.VariableType;

import java.util.*;

/** 生成前的值规范化与问题汇总；错误展示由调用方负责。 */
public final class VariableValidation {
    private VariableValidation() { }

    public record Result(Map<String, String> values, Set<String> numericVariables, List<String> invalidNames) {
        public Result {
            values = Collections.unmodifiableMap(new LinkedHashMap<>(values));
            numericVariables = Set.copyOf(numericVariables);
            invalidNames = List.copyOf(invalidNames);
        }
        public boolean valid() { return invalidNames.isEmpty(); }
    }

    public static Result validate(Map<String, VariableInputState> variables) {
        Map<String, String> values = new LinkedHashMap<>();
        Set<String> numeric = new LinkedHashSet<>();
        List<String> problems = new ArrayList<>();
        for (VariableInputState state : variables.values()) {
            if (state.type() == VariableType.NUMBER) {
                numeric.add(state.name());
                String value = ValueNormalizer.normalize(state.value());
                if (value == null) problems.add(state.name()); else values.put(state.name(), value);
            } else values.put(state.name(), state.value());
        }
        return new Result(values, numeric, problems);
    }
}
