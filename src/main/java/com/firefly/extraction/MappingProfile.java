package com.firefly.extraction;

import java.util.*;

/** 映射绑定模板变量；不保存工作簿文件名或路径。坐标均为 0 起始。 */
public record MappingProfile(List<Binding> bindings) {
    public static final MappingProfile EMPTY = new MappingProfile(List.of());
    public MappingProfile { bindings = List.copyOf(bindings); }
    public enum Mode {
        FIXED("固定单元格"), TITLES("按行列标题"), RECORD("锁定列／更改选定行"), COLUMN_RECORD("锁定行／更改选定列");
        private final String label;
        Mode(String label) { this.label = label; }
        public String toString() { return label; }
    }
    public enum EmptyPolicy {
        KEEP("空值保留原值"), ZERO("空值取0");
        private final String label;
        EmptyPolicy(String label) { this.label = label; }
        public String toString() { return label; }
    }
    public record Binding(String variable, String sheet, Mode mode, int headerRow, int titleColumn,
                          int row, int column, String rowTitle, String columnTitle,
                          List<String> headers, String fixedRowTitle, EmptyPolicy emptyPolicy) {
        public Binding { headers = List.copyOf(headers); }
        public Binding withEmptyPolicy(EmptyPolicy value) { return new Binding(variable, sheet, mode, headerRow, titleColumn, row, column, rowTitle, columnTitle, headers, fixedRowTitle, value); }
    }
    public MappingProfile put(Binding binding) {
        List<Binding> next = new ArrayList<>(bindings);
        next.removeIf(old -> old.variable().equals(binding.variable())); next.add(binding);
        return new MappingProfile(next);
    }
    public MappingProfile remove(String variable) { return new MappingProfile(bindings.stream().filter(b -> !b.variable().equals(variable)).toList()); }
    public Binding get(String variable) { return bindings.stream().filter(b -> b.variable().equals(variable)).findFirst().orElse(null); }

    public Map<String, Object> toJson() {
        List<Map<String, Object>> items = new ArrayList<>();
        for (Binding b : bindings) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("variable", b.variable()); item.put("sheet", b.sheet()); item.put("mode", b.mode().name());
            item.put("headerRow", b.headerRow()); item.put("titleColumn", b.titleColumn());
            item.put("row", b.row()); item.put("column", b.column()); item.put("rowTitle", b.rowTitle());
            item.put("columnTitle", b.columnTitle()); item.put("headers", b.headers()); item.put("fixedRowTitle", b.fixedRowTitle());
            item.put("emptyPolicy", b.emptyPolicy().name()); items.add(item);
        }
        return Map.of("version", 3, "bindings", items);
    }
    public static MappingProfile fromJson(Object value) {
        if (!(value instanceof Map<?, ?> root) || !(root.get("bindings") instanceof List<?> items)) return EMPTY;
        List<Binding> bindings = new ArrayList<>(); Set<String> seen = new HashSet<>();
        for (Object item : items) {
            if (!(item instanceof Map<?, ?> m)) continue;
            try {
                String variable = string(m, "variable");
                if (variable.isBlank() || !seen.add(variable)) continue;
                List<String> headers = m.get("headers") instanceof List<?> list ? list.stream().map(Object::toString).toList() : List.of();
                bindings.add(new Binding(variable, string(m, "sheet"), Mode.valueOf(string(m, "mode")),
                        number(m, "headerRow"), number(m, "titleColumn"), number(m, "row"), number(m, "column"),
                        string(m, "rowTitle"), string(m, "columnTitle"), headers, string(m, "fixedRowTitle"),
                        readEmptyPolicy(string(m, "emptyPolicy"))));
            } catch (IllegalArgumentException ignored) { /* 一条损坏的配置不影响其他映射。 */ }
        }
        return new MappingProfile(bindings);
    }
    private static EmptyPolicy readEmptyPolicy(String value) {
        // 旧规则不再清空文本，也不将原本要求报错的空白静默转换为 0。
        return switch (value) {
            case "ERROR", "CLEAR" -> EmptyPolicy.KEEP;
            default -> EmptyPolicy.valueOf(value);
        };
    }
    private static String string(Map<?, ?> m, String key) { return m.get(key) instanceof String s ? s : ""; }
    private static int number(Map<?, ?> m, String key) {
        if (!(m.get(key) instanceof Number n) || n.intValue() < 0 || n.intValue() > 1_048_575) throw new IllegalArgumentException(key);
        return n.intValue();
    }
}
