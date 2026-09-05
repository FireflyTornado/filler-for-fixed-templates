package com.firefly.extraction;

import com.firefly.core.*;
import java.util.*;

/** 纯映射解析：只返回预览，不修改会话。重名、歧义、缺失都不猜测。 */
public final class MappingEngine {
    public record Match(SpreadsheetData.Sheet sheet, int row, int column,
                        String rowTitle, String columnTitle, String note) { }
    public record Preview(String variable, String source, String display, String value, String oldValue,
                          String status, String error, boolean apply, boolean formula, Match match) { }

    public List<Preview> preview(SpreadsheetData book, MappingProfile profile,
                                Map<String, VariableInputState> variables, String activeSheet, int recordRow, int recordColumn,
                                Set<MappingProfile.Binding> confirmedFixed, Runnable checkpoint) {
        MappingProfile.SheetSettings settings = profile.sheetSetting(activeSheet);
        int headerRow = settings == null ? -1 : settings.headerRow();
        int titleColumn = settings == null ? -1 : settings.titleColumn();
        return preview(book, profile, variables, activeSheet, headerRow, titleColumn, recordRow, recordColumn, confirmedFixed, checkpoint);
    }

    public List<Preview> preview(SpreadsheetData book, MappingProfile profile,
                                Map<String, VariableInputState> variables, String activeSheet, int headerRow, int titleColumn,
                                int recordRow, int recordColumn, Set<MappingProfile.Binding> confirmedFixed, Runnable checkpoint) {
        List<Preview> result = new ArrayList<>();
        for (var entry : variables.entrySet()) {
            checkpoint.run();
            String name = entry.getKey(); VariableInputState variable = entry.getValue();
            MappingProfile.Binding b = profile.get(name);
            if (b == null) {
                result.add(new Preview(name, "手工填写", "", "", variable.value(), "手工填写", "", false, false, null)); continue;
            }
            Match match = null;
            SpreadsheetData.Cell cell = SpreadsheetData.Cell.EMPTY;
            String source = "原绑定位置：" + sourceText(b.sheet(), b.row(), b.column(), b.rowTitle(), b.columnTitle());
            try {
                if (book == null) throw new IllegalArgumentException("请先打开 Excel 文件");
                match = resolve(book, b, activeSheet, headerRow, titleColumn, recordRow, recordColumn, confirmedFixed.contains(b), checkpoint);
                cell = match.sheet().cell(match.row(), match.column());
                source = sourceText(match.sheet().name(), match.row(), match.column(), match.rowTitle(), match.columnTitle());
                if (!cell.error().isEmpty()) throw new IllegalArgumentException(cell.error());
                if (cell.blank()) {
                    if (b.emptyPolicy() == MappingProfile.EmptyPolicy.KEEP) {
                        String note = variable.type() == VariableType.NUMBER ? "来源为空，已保留原值" : "提示：来源为空，已保留原文";
                        result.add(new Preview(name, source, "", "", variable.value(), note, "", false, cell.formula(), match)); continue;
                    }
                    if (variable.type() != VariableType.NUMBER) throw new IllegalArgumentException("来源为空，文本变量不能按“空值取0”处理；请选择“空值保留原值”");
                    result.add(new Preview(name, source, "", "0", variable.value(), "来源为空，将填入 0", "", true, cell.formula(), match)); continue;
                }
                String value = cell.display();
                if (variable.type() == VariableType.NUMBER) {
                    if (cell.date()) throw new IllegalArgumentException("日期不能直接写入数值变量，请先将目标设为文本类型");
                    value = cell.numeric() != null ? cell.numeric() : ValueNormalizer.normalize(cell.display());
                    if (value == null || cell.display().isBlank()) throw new IllegalArgumentException("来源不是有效数值");
                }
                String status = Objects.equals(value, variable.value()) ? "相同" : variable.value().isEmpty() ? "新增" : "将覆盖";
                if (cell.formula()) status += " · 已保存公式结果";
                if (!match.note().isEmpty()) status += " · " + match.note();
                result.add(new Preview(name, source, cell.display(), value, variable.value(), status, "", true, cell.formula(), match));
            } catch (IllegalArgumentException e) {
                result.add(new Preview(name, source, cell.display(), "", variable.value(), "需要处理", e.getMessage(), false, cell.formula(), match));
            }
        }
        return List.copyOf(result);
    }

    public MappingProfile.Binding bind(String variable, SpreadsheetData.Sheet sheet, MappingProfile.Mode mode,
                                       int headerRow, int titleColumn, int row, int column, MappingProfile.EmptyPolicy policy) {
        return bind(variable, sheet, mode, headerRow, titleColumn, row, column, policy, MappingProfile.SelectionScope.GLOBAL);
    }
    public MappingProfile.Binding bind(String variable, SpreadsheetData.Sheet sheet, MappingProfile.Mode mode,
                                       int headerRow, int titleColumn, int row, int column, MappingProfile.EmptyPolicy policy,
                                       MappingProfile.SelectionScope selectionScope) {
        if (row < 0 || column < 0 || row >= sheet.rows() || column >= sheet.columns()) throw new IllegalArgumentException("请选择有效单元格");
        String rowTitle = SpreadsheetData.normalize(sheet.cell(row, titleColumn).display());
        String colTitle = SpreadsheetData.normalize(sheet.cell(headerRow, column).display());
        if ((mode == MappingProfile.Mode.TITLES || mode == MappingProfile.Mode.RECORD) && colTitle.isBlank()) throw new IllegalArgumentException("所选列没有标题，请检查“列标题所在行”设置");
        if (mode == MappingProfile.Mode.TITLES && (rowTitle.isBlank() || row == headerRow || column == titleColumn)) throw new IllegalArgumentException("请选择行标题、列标题交叉处的数据单元格");
        if (mode == MappingProfile.Mode.RECORD && row <= headerRow) throw new IllegalArgumentException("选定行必须位于表头之后");
        if (mode == MappingProfile.Mode.COLUMN_RECORD && (rowTitle.isBlank() || column <= titleColumn)) throw new IllegalArgumentException("请检查“行标题所在列”设置；所选行需要标题，选定列必须位于标题右侧");
        return new MappingProfile.Binding(variable, sheet.name(), mode, headerRow, titleColumn, row, column,
                rowTitle, colTitle, mode == MappingProfile.Mode.COLUMN_RECORD ? sheet.rowTitles(titleColumn) : sheet.headers(headerRow),
                column == titleColumn ? "" : rowTitle, policy, selectionScope);
    }

    public Match resolve(SpreadsheetData book, MappingProfile.Binding binding, String activeSheet, int recordRow, int recordColumn,
                         boolean confirmedFixed, Runnable checkpoint) {
        return resolve(book, binding, activeSheet, -1, -1, recordRow, recordColumn, confirmedFixed, checkpoint);
    }

    public Match resolve(SpreadsheetData book, MappingProfile.Binding binding, String activeSheet, int headerRow, int titleColumn,
                         int recordRow, int recordColumn, boolean confirmedFixed, Runnable checkpoint) {
        if (binding.mode() != MappingProfile.Mode.FIXED && headerRow >= 0 && titleColumn >= 0)
            return resolveOnActiveSheet(book, binding, activeSheet, headerRow, titleColumn, recordRow, recordColumn);
        List<Match> candidates = new ArrayList<>();
        for (SpreadsheetData.Sheet sheet : book.sheets()) {
            checkpoint.run();
            if (binding.mode() == MappingProfile.Mode.TITLES && !sheet.name().equals(activeSheet)) continue;
            if (binding.mode() == MappingProfile.Mode.COLUMN_RECORD) {
                int selectedColumn = binding.selectionScope() == MappingProfile.SelectionScope.LOCAL ? binding.column() : recordColumn;
                if (!sheet.name().equals(activeSheet) || selectedColumn < 0 || selectedColumn >= sheet.columns()) continue;
                Map<Integer, List<String>> titleContexts = new HashMap<>();
                for (var entry : sheet.cells().entrySet()) {
                    checkpoint.run();
                    if (!SpreadsheetData.normalize(entry.getValue().display()).equals(binding.rowTitle())) continue;
                    int r = (int) (entry.getKey() >> 32), c = (int) (long) entry.getKey();
                    if (selectedColumn <= c) continue;
                    List<String> titles = titleContexts.computeIfAbsent(c, sheet::rowTitles);
                    // 纵向合并或重复行标题都不能唯一指向一行。
                    if (titles.stream().filter(binding.rowTitle()::equals).count() != 1) continue;
                    Set<String> context = new HashSet<>(binding.headers());
                    context.remove(""); context.remove(binding.rowTitle());
                    if (!context.isEmpty() && titles.stream().noneMatch(context::contains)) continue;
                    int titleRow = findTitleRow(sheet, binding, c);
                    candidates.add(match(sheet, r, selectedColumn, titleRow, c, moved(binding, r, selectedColumn)));
                }
                continue;
            }
            if (binding.mode() == MappingProfile.Mode.FIXED) {
                boolean anchors = binding.headers().stream().anyMatch(s -> !s.isBlank()) || !binding.fixedRowTitle().isBlank();
                boolean same = sheet.headers(binding.headerRow()).equals(binding.headers())
                        && (binding.fixedRowTitle().isBlank() || SpreadsheetData.normalize(sheet.cell(binding.row(), binding.titleColumn()).display()).equals(binding.fixedRowTitle()));
                if (binding.row() < sheet.rows() && binding.column() < sheet.columns()
                        && ((confirmedFixed && sheet.name().equals(binding.sheet())) || (anchors && same))) {
                    candidates.add(match(sheet, binding.row(), binding.column(), binding.headerRow(), binding.titleColumn(), "固定坐标"));
                }
                continue;
            }
            // 找列标题的实际单元格，支持整张报表上下移动和新增/调整列顺序。
            List<int[]> headers = new ArrayList<>();
            for (var cell : sheet.cells().entrySet()) {
                if (SpreadsheetData.normalize(cell.getValue().display()).equals(binding.columnTitle())) {
                    int row = (int) (cell.getKey() >> 32), column = (int) (long) cell.getKey();
                    if (matchesHeaderContext(sheet, row, binding)) headers.add(new int[]{row, column});
                }
            }
            for (int[] header : headers) {
                checkpoint.run();
                // 合并表头覆盖多个数据列时不能把任一列静默绑定到左上角。
                if (sheet.headers(header[0]).stream().filter(binding.columnTitle()::equals).count() != 1) continue;
                if (binding.mode() == MappingProfile.Mode.RECORD) {
                    int selectedRow = binding.selectionScope() == MappingProfile.SelectionScope.LOCAL ? binding.row() : recordRow;
                    if (!sheet.name().equals(activeSheet) || selectedRow <= header[0] || selectedRow >= sheet.rows()) continue;
                    int actualTitleColumn = findTitleColumn(sheet, header[0], binding);
                    candidates.add(match(sheet, selectedRow, header[1], header[0], actualTitleColumn, moved(binding, selectedRow, header[1])));
                } else {
                    // 行标题列也可能移动：有标题时按其标题找回，否则沿用原列。
                    List<Integer> titleColumns = new ArrayList<>();
                    String titleHeader = binding.titleColumn() < binding.headers().size() ? binding.headers().get(binding.titleColumn()) : "";
                    if (titleHeader.isBlank()) titleColumns.add(binding.titleColumn());
                    else for (int c = 0; c < sheet.columns(); c++) if (SpreadsheetData.normalize(sheet.cell(header[0], c).display()).equals(titleHeader)) titleColumns.add(c);
                    for (int foundTitleColumn : titleColumns) {
                        for (var cell : sheet.cells().entrySet()) {
                            int r = (int) (cell.getKey() >> 32), c = (int) (long) cell.getKey();
                            if (c == foundTitleColumn && r > header[0] && SpreadsheetData.normalize(cell.getValue().display()).equals(binding.rowTitle())) {
                                boolean mergedRows = sheet.merges().stream().anyMatch(m -> m.firstRow() == r && m.lastRow() > r && foundTitleColumn >= m.firstColumn() && foundTitleColumn <= m.lastColumn());
                                if (!mergedRows) candidates.add(match(sheet, r, header[1], header[0], foundTitleColumn, moved(binding, r, header[1])));
                            }
                        }
                    }
                }
            }
        }
        List<Match> named = candidates.stream().filter(m -> m.sheet().name().equals(binding.sheet())).toList();
        if (!named.isEmpty()) candidates = named;
        if (candidates.size() != 1) throw new IllegalArgumentException(candidates.isEmpty()
                ? "结构或标题不匹配，请重新绑定；锁定行／列模式还需选择对应工作表及有效的选定行列"
                : "找到多个同名标题或相似工作表，请重新指定来源");
        return candidates.get(0);
    }

    private Match resolveOnActiveSheet(SpreadsheetData book, MappingProfile.Binding binding, String activeSheet,
                                       int headerRow, int titleColumn, int recordRow, int recordColumn) {
        SpreadsheetData.Sheet sheet = book.sheets().stream().filter(s -> s.name().equals(activeSheet)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("请选择映射所使用的工作表"));
        if (headerRow >= sheet.rows() || titleColumn >= sheet.columns())
            throw new IllegalArgumentException("当前工作表的列标题所在行或行标题所在列超出范围");
        int row, column;
        if (binding.mode() == MappingProfile.Mode.COLUMN_RECORD) {
            row = uniqueRow(sheet, titleColumn, binding.rowTitle(), 0);
            column = binding.selectionScope() == MappingProfile.SelectionScope.LOCAL ? binding.column() : recordColumn;
            if (column <= titleColumn || column >= sheet.columns()) throw new IllegalArgumentException("当前工作表的选定列无效，请调整取值列");
        } else {
            column = uniqueColumn(sheet, headerRow, binding.columnTitle());
            if (binding.mode() == MappingProfile.Mode.RECORD) {
                row = binding.selectionScope() == MappingProfile.SelectionScope.LOCAL ? binding.row() : recordRow;
                if (row <= headerRow || row >= sheet.rows()) throw new IllegalArgumentException("当前工作表的选定行无效，请调整取值行");
            } else {
                row = uniqueRow(sheet, titleColumn, binding.rowTitle(), headerRow + 1);
                if (column == titleColumn) throw new IllegalArgumentException("行标题与列标题的交叉位置不是数据单元格");
            }
        }
        return match(sheet, row, column, headerRow, titleColumn, moved(binding, row, column));
    }

    private int uniqueColumn(SpreadsheetData.Sheet sheet, int headerRow, String title) {
        List<Integer> matches = new ArrayList<>();
        for (int column = 0; column < sheet.columns(); column++)
            if (SpreadsheetData.normalize(sheet.cell(headerRow, column).display()).equals(title)) matches.add(column);
        if (matches.size() != 1) throw new IllegalArgumentException(matches.isEmpty()
                ? "当前工作表的列标题所在行未找到“" + shownTitle(title) + "”"
                : "当前工作表的列标题所在行存在多个“" + shownTitle(title) + "”");
        return matches.get(0);
    }

    private int uniqueRow(SpreadsheetData.Sheet sheet, int titleColumn, String title, int firstRow) {
        List<Integer> matches = new ArrayList<>();
        for (int row = firstRow; row < sheet.rows(); row++)
            if (SpreadsheetData.normalize(sheet.cell(row, titleColumn).display()).equals(title)) matches.add(row);
        if (matches.size() != 1) throw new IllegalArgumentException(matches.isEmpty()
                ? "当前工作表的行标题所在列未找到“" + shownTitle(title) + "”"
                : "当前工作表的行标题所在列存在多个“" + shownTitle(title) + "”");
        return matches.get(0);
    }
    private Match match(SpreadsheetData.Sheet sheet, int row, int column, int titleRow, int titleColumn, String note) {
        return new Match(sheet, row, column, sheet.cell(row, titleColumn).display(), sheet.cell(titleRow, column).display(), note);
    }
    private int findTitleColumn(SpreadsheetData.Sheet sheet, int headerRow, MappingProfile.Binding binding) {
        String titleHeader = binding.titleColumn() < binding.headers().size() ? binding.headers().get(binding.titleColumn()) : "";
        if (!titleHeader.isBlank()) for (int c = 0; c < sheet.columns(); c++)
            if (SpreadsheetData.normalize(sheet.cell(headerRow, c).display()).equals(titleHeader)) return c;
        return Math.min(binding.titleColumn(), Math.max(0, sheet.columns() - 1));
    }
    private int findTitleRow(SpreadsheetData.Sheet sheet, MappingProfile.Binding binding, int titleColumn) {
        String title = binding.headerRow() < binding.headers().size() ? binding.headers().get(binding.headerRow()) : "";
        if (!title.isBlank()) for (int r = 0; r < sheet.rows(); r++)
            if (SpreadsheetData.normalize(sheet.cell(r, titleColumn).display()).equals(title)) return r;
        return Math.min(binding.headerRow(), Math.max(0, sheet.rows() - 1));
    }
    private String sourceText(String sheet, int row, int column, String rowTitle, String columnTitle) {
        return sheet + " / " + SpreadsheetData.address(row, column) + " / 行标题：" + shownTitle(rowTitle)
                + " / 列标题：" + shownTitle(columnTitle);
    }
    private String shownTitle(String value) { return value == null || value.isBlank() ? "（空）" : value; }
    private boolean matchesHeaderContext(SpreadsheetData.Sheet sheet, int row, MappingProfile.Binding binding) {
        Set<String> actual = new HashSet<>(sheet.headers(row));
        String rowHeader = binding.titleColumn() < binding.headers().size() ? binding.headers().get(binding.titleColumn()) : "";
        if (binding.mode() == MappingProfile.Mode.TITLES && !rowHeader.isBlank()) return actual.contains(rowHeader);
        // 无关列被增删不应废弃所有映射；除目标标题外，保留至少一个可用上下文锚点。
        Set<String> context = new HashSet<>(binding.headers()); context.remove(""); context.remove(binding.columnTitle());
        return context.isEmpty() || context.stream().anyMatch(actual::contains);
    }
    private String moved(MappingProfile.Binding b, int row, int column) {
        return row == b.row() && column == b.column() ? "" : "已定位到 " + SpreadsheetData.address(row, column);
    }
}
