package com.firefly.application;

import com.firefly.core.*;

import java.util.*;
import java.util.function.Consumer;

/**
 * 当前模板的工作会话；不依赖 Swing 或文件存储。
 * 由调用线程串行使用（桌面应用为界面线程），后台任务只接收独立快照。
 * 加载/同步由调用方刷新整个视图；变量编辑通过 Change 通知调用方。
 */
public final class TemplateSession {
    public enum Change { VALUE, TYPE, BATCH, DECIMAL_PLACES }

    private String templateName = "", templateText = "";
    private TemplateConfig persistedConfig = new TemplateConfig("");
    private Map<String, VariableInputState> variables = new LinkedHashMap<>();
    private final Map<String, VariableInputState> sessionVariables = new LinkedHashMap<>();
    private int decimalPlaces = NumericFormatter.DEFAULT_DECIMAL_PLACES;
    private long templateRevision, inputRevision;
    private Consumer<Change> changeListener = change -> { };

    public String templateName() { return templateName; }
    public String templateText() { return templateText; }
    public int decimalPlaces() { return decimalPlaces; }
    public long templateRevision() { return templateRevision; }
    public long inputRevision() { return inputRevision; }

    public void setChangeListener(Consumer<Change> listener) {
        changeListener = Objects.requireNonNull(listener);
    }

    /** 成功加载或新建时才调用；刷新同名模板也开启新会话。 */
    public void load(String name, String text, TemplateConfig config, TemplateParser.ParsedTemplate parsed) {
        sessionVariables.clear();
        templateName = name;
        replaceTemplate(text, config, parsed);
    }

    /** 旧语法迁移保留当前会话中已经填写的变量。 */
    public void migrate(String text, TemplateConfig config, TemplateParser.ParsedTemplate parsed) {
        replaceTemplate(text, config, parsed);
    }

    private void replaceTemplate(String text, TemplateConfig config, TemplateParser.ParsedTemplate parsed) {
        templateText = text;
        updatePersistedConfig(config);
        decimalPlaces = config.decimalPlaces();
        rebuildVariables(parsed);
        templateRevision++;
        inputRevision++;
    }

    public void rename(String name, TemplateConfig config) {
        templateName = name;
        updatePersistedConfig(config);
    }

    /** 保存/清理配置后的磁盘基线；不覆盖当前草稿或小数位数。 */
    public void updatePersistedConfig(TemplateConfig config) {
        TemplateConfig copy = new TemplateConfig(config.templateName());
        copy.setDecimalPlaces(config.decimalPlaces());
        copy.variables().putAll(config.variables());
        persistedConfig = copy;
    }

    /** 模板输入发生变化时立即标记；解析可继续沿用界面的延迟合并。 */
    public void markTemplateEdited() { templateRevision++; }
    public void markInputChanged() { inputRevision++; }

    public void synchronizeText(String text) {
        templateText = text;
        rebuildVariables(TemplateParser.parse(text));
    }

    private void rebuildVariables(TemplateParser.ParsedTemplate parsed) {
        Map<String, VariableInputState> next = new LinkedHashMap<>();
        for (TemplateParser.VariableSpec spec : parsed.variables()) {
            VariableInputState current = sessionVariables.get(spec.name());
            if (current != null) {
                next.put(spec.name(), current.copyFor(spec));
            } else {
                TemplateConfig.Entry saved = persistedConfig.variables().get(spec.name());
                VariableType type = saved == null || saved.type() == null ? spec.defaultType() : saved.type();
                if (spec.numericLocked()) type = VariableType.NUMBER;
                next.put(spec.name(), new VariableInputState(spec.name(), type,
                        saved == null ? "" : saved.value(),
                        saved == null ? Map.of() : saved.legacySessionValues(), spec.numericLocked()));
            }
        }
        sessionVariables.putAll(next);
        variables = next;
    }

    /** 返回独立快照，调用方不能绕过更新入口修改会话。 */
    public Map<String, VariableInputState> variables() { return snapshot(variables); }
    public Map<String, VariableInputState> variablesForPersistence() { return snapshot(sessionVariables); }
    public VariableInputState variable(String name) { return requireVariable(name).copy(); }

    private static Map<String, VariableInputState> snapshot(Map<String, VariableInputState> source) {
        Map<String, VariableInputState> copy = new LinkedHashMap<>();
        source.forEach((name, state) -> copy.put(name, state.copy()));
        return Collections.unmodifiableMap(copy);
    }

    /** 手工编辑允许暂时无效的输入，生成时再校验，保持原有输入体验。 */
    public void setValue(String name, String value) {
        requireVariable(name).setValue(value);
        changed(Change.VALUE);
    }

    /** 界面负责询问类型转换方式；会话负责草稿和表达式类型锁定。 */
    public void activateType(String name, VariableType type, String initialDraft) {
        VariableInputState state = requireVariable(name);
        Objects.requireNonNull(type);
        if (state.type() == type || (state.numericLocked() && type != VariableType.NUMBER)) return;
        state.activateType(type, initialDraft);
        changed(Change.TYPE);
    }

    /** 只清理非当前类型草稿，不影响当前生成结果或磁盘配置。 */
    public void clearOtherTypeValues(String name) { requireVariable(name).clearOtherTypeValues(); }

    public void setDecimalPlaces(int value) {
        int next = NumericFormatter.clampDecimalPlaces(value);
        if (decimalPlaces == next) return;
        decimalPlaces = next;
        changed(Change.DECIMAL_PLACES);
    }

    /**
     * 外部数据入口：按当前类型先检查整批数据，成功后一次应用、一次通知。
     * 未提供的变量保留原值；未知变量、null 和空白/无效数值拒绝整批更新。
     * 本方法不推断类型、不执行 Excel 映射，也不覆盖内置日期变量。
     */
    public void applyValues(Map<String, String> incoming) {
        Map<String, String> updates = new LinkedHashMap<>(Objects.requireNonNull(incoming));
        for (Map.Entry<String, String> entry : updates.entrySet()) {
            VariableInputState state = requireVariable(entry.getKey());
            String value = entry.getValue();
            if (value == null || (state.type() == VariableType.NUMBER
                    && (value.isBlank() || ValueNormalizer.normalize(value) == null))) {
                throw new IllegalArgumentException("变量“" + entry.getKey() + "”的值无效");
            }
        }
        if (updates.isEmpty()) return;
        updates.forEach((name, value) -> variables.get(name).setValue(value));
        changed(Change.BATCH);
    }

    private VariableInputState requireVariable(String name) {
        VariableInputState state = variables.get(name);
        if (state == null) throw new IllegalArgumentException("当前模板不存在变量：" + name);
        return state;
    }

    private void changed(Change change) {
        inputRevision++;
        changeListener.accept(change);
    }
}
