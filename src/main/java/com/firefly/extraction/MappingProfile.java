package com.firefly.extraction;

import java.util.*;

/** 映射绑定模板变量；不保存工作簿文件名或路径。坐标均为 0 起始。 */
public record MappingProfile(List<Binding> bindings, List<SheetSettings> sheetSettings) {
    public static final MappingProfile EMPTY = new MappingProfile(List.of(), List.of());
    public MappingProfile(List<Binding> bindings) { this(bindings, List.of()); }
    public MappingProfile { bindings = List.copyOf(bindings); sheetSettings = List.copyOf(sheetSettings); }
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
    public enum SelectionScope {
        GLOBAL("全部同类映射"), LOCAL("仅此映射");
        private final String label;
        SelectionScope(String label) { this.label = label; }
        public String toString() { return label; }
    }
    /** 每张逻辑工作表的结构骨架和标题位置；全局取值行列属于当前打开的工作簿，不写入映射。 */
    public record SheetSettings(String sheet, int headerRow, int titleColumn,
                                List<String> columnHeaders, List<String> rowTitles) {
        public SheetSettings(String sheet, int headerRow, int titleColumn) {
            this(sheet, headerRow, titleColumn, List.of(), List.of());
        }
        public SheetSettings { columnHeaders = structureSample(columnHeaders); rowTitles = structureSample(rowTitles); }
    }
    public record Binding(String variable, String sheet, Mode mode, int headerRow, int titleColumn,
                          int row, int column, String rowTitle, String columnTitle,
                          List<String> headers, String fixedRowTitle, EmptyPolicy emptyPolicy, SelectionScope selectionScope) {
        public Binding { headers = List.copyOf(headers); }
        public Binding withSettings(EmptyPolicy policy, SelectionScope scope, int selectedRow, int selectedColumn) {
            return new Binding(variable, sheet, mode, headerRow, titleColumn, selectedRow, selectedColumn,
                    rowTitle, columnTitle, headers, fixedRowTitle, policy, scope);
        }
        public Binding withEmptyPolicy(EmptyPolicy value) { return withSettings(value, selectionScope, row, column); }
    }
    public MappingProfile put(Binding binding) {
        List<Binding> next = new ArrayList<>(bindings);
        next.removeIf(old -> old.variable().equals(binding.variable())); next.add(binding);
        return new MappingProfile(next, sheetSettings);
    }
    public MappingProfile remove(String variable) { return new MappingProfile(bindings.stream().filter(b -> !b.variable().equals(variable)).toList(), sheetSettings); }
    public Binding get(String variable) { return bindings.stream().filter(b -> b.variable().equals(variable)).findFirst().orElse(null); }
    public SheetSettings sheetSetting(String sheet) { return sheetSettings.stream().filter(s -> s.sheet().equals(sheet)).findFirst().orElse(null); }
    /** 为版本 5 及更早配置从绑定已有的标题上下文补出运行时结构骨架。 */
    public SheetSettings structuralSetting(String sheet) {
        SheetSettings saved = sheetSetting(sheet);
        List<Binding> related = bindings.stream().filter(binding -> binding.sheet().equals(sheet)).toList();
        if (saved == null && related.isEmpty()) return null;
        Binding first = related.isEmpty() ? null : related.get(0);
        int headerRow = saved != null ? saved.headerRow() : first.headerRow();
        int titleColumn = saved != null ? saved.titleColumn() : first.titleColumn();
        List<String> columns = saved == null ? List.of() : saved.columnHeaders();
        List<String> rows = saved == null ? List.of() : saved.rowTitles();
        if (columns.isEmpty()) columns = related.stream().filter(binding -> binding.mode() != Mode.COLUMN_RECORD)
                .map(Binding::headers).max(Comparator.comparingInt(List::size)).orElse(List.of());
        if (rows.isEmpty()) rows = related.stream().filter(binding -> binding.mode() == Mode.COLUMN_RECORD)
                .map(Binding::headers).max(Comparator.comparingInt(List::size)).orElse(List.of());
        return new SheetSettings(sheet, headerRow, titleColumn, columns, rows);
    }
    public MappingProfile withSheetSetting(SheetSettings setting) {
        List<SheetSettings> next = new ArrayList<>(sheetSettings);
        next.removeIf(old -> old.sheet().equals(setting.sheet())); next.add(setting);
        return new MappingProfile(bindings, next);
    }
    public MappingProfile retainSheetSettings(Set<String> sheets) {
        return new MappingProfile(bindings, sheetSettings.stream().filter(setting -> sheets.contains(setting.sheet())).toList());
    }

    public Map<String, Object> toJson() {
        List<Map<String, Object>> items = new ArrayList<>();
        for (Binding b : bindings) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("variable", b.variable()); item.put("sheet", b.sheet()); item.put("mode", b.mode().name());
            item.put("headerRow", b.headerRow()); item.put("titleColumn", b.titleColumn());
            item.put("row", b.row()); item.put("column", b.column()); item.put("rowTitle", b.rowTitle());
            item.put("columnTitle", b.columnTitle()); item.put("headers", b.headers()); item.put("fixedRowTitle", b.fixedRowTitle());
            item.put("emptyPolicy", b.emptyPolicy().name()); item.put("selectionScope", b.selectionScope().name()); items.add(item);
        }
        List<Map<String, Object>> sheets = new ArrayList<>();
        for (SheetSettings s : sheetSettings) sheets.add(Map.of("sheet", s.sheet(), "headerRow", s.headerRow(),
                "titleColumn", s.titleColumn(),
                "columnHeaders", s.columnHeaders(), "rowTitles", s.rowTitles()));
        return Map.of("version", 7, "bindings", items, "sheets", sheets);
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
                        readEmptyPolicy(string(m, "emptyPolicy")), readSelectionScope(string(m, "selectionScope"))));
            } catch (IllegalArgumentException ignored) { /* 一条损坏的配置不影响其他映射。 */ }
        }
        List<SheetSettings> sheets = new ArrayList<>(); Set<String> sheetNames = new HashSet<>();
        if (root.get("sheets") instanceof List<?> settings) for (Object item : settings) {
            if (!(item instanceof Map<?, ?> m)) continue;
            try {
                String sheet = string(m, "sheet");
                List<String> columnHeaders = strings(m.get("columnHeaders"));
                List<String> rowTitles = strings(m.get("rowTitles"));
                if (!sheet.isBlank() && sheetNames.add(sheet)) sheets.add(new SheetSettings(sheet, number(m, "headerRow"),
                        number(m, "titleColumn"), columnHeaders, rowTitles));
            } catch (IllegalArgumentException ignored) { /* 一张工作表的设置损坏不影响其他设置。 */ }
        }
        return new MappingProfile(bindings, sheets);
    }
    private static SelectionScope readSelectionScope(String value) {
        return value.isBlank() ? SelectionScope.GLOBAL : SelectionScope.valueOf(value);
    }
    private static EmptyPolicy readEmptyPolicy(String value) {
        // 旧规则不再清空文本，也不将原本要求报错的空白静默转换为 0。
        return switch (value) {
            case "ERROR", "CLEAR" -> EmptyPolicy.KEEP;
            default -> EmptyPolicy.valueOf(value);
        };
    }
    private static String string(Map<?, ?> m, String key) { return m.get(key) instanceof String s ? s : ""; }
    private static List<String> strings(Object value) {
        return value instanceof List<?> list ? list.stream().map(item -> item == null ? "" : item.toString()).toList() : List.of();
    }
    /** 保留最多 32 个非空结构锚点及其中间位置，避免大型工作表令配置膨胀。 */
    private static List<String> structureSample(List<String> values) {
        List<String> result = new ArrayList<>(); int anchors = 0;
        for (String raw : values) {
            String value = SpreadsheetData.normalize(raw); result.add(value);
            if (!value.isBlank() && ++anchors >= 32) break;
        }
        while (!result.isEmpty() && result.get(result.size() - 1).isBlank()) result.remove(result.size() - 1);
        return List.copyOf(result);
    }
    private static int number(Map<?, ?> m, String key) {
        if (!(m.get(key) instanceof Number n) || n.intValue() < 0 || n.intValue() > 1_048_575) throw new IllegalArgumentException(key);
        return n.intValue();
    }
}
