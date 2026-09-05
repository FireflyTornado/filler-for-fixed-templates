package com.firefly.extraction;

import java.util.*;

/** 用标题骨架识别逻辑工作表，并计算表格整体移动后的行列位置。 */
public final class WorksheetStructureMatcher {
    public record Match(SpreadsheetData.Sheet sheet, int headerRow, int titleColumn,
                        int rowShift, int columnShift) { }
    private record Token(int index, String value) { }
    private record Axis(int position, int shift) { }

    private WorksheetStructureMatcher() { }

    public static Match match(MappingProfile.SheetSettings settings, SpreadsheetData.Sheet sheet) {
        List<Token> savedHeaders = tokens(settings.columnHeaders());
        List<Token> savedRows = tokens(settings.rowTitles());
        Axis header = savedHeaders.size() >= 2 ? uniqueRow(sheet, savedHeaders) : null;
        Axis titles = savedRows.size() >= 2 ? uniqueColumn(sheet, savedRows) : null;
        if (header == null && titles == null) return null;
        int rowShift = titles == null ? 0 : titles.shift();
        int columnShift = header == null ? 0 : header.shift();
        int headerRow = header == null ? settings.headerRow() + rowShift : header.position();
        int titleColumn = titles == null ? settings.titleColumn() + columnShift : titles.position();
        if (headerRow < 0 || headerRow >= sheet.rows() || titleColumn < 0 || titleColumn >= sheet.columns()) return null;
        return new Match(sheet, headerRow, titleColumn, rowShift, columnShift);
    }

    public static boolean hasStructure(MappingProfile.SheetSettings settings) {
        return tokens(settings.columnHeaders()).size() >= 2 || tokens(settings.rowTitles()).size() >= 2;
    }

    private static Axis uniqueRow(SpreadsheetData.Sheet sheet, List<Token> saved) {
        Axis found = null;
        for (int row = 0; row < sheet.rows(); row++) {
            Integer shift = samePattern(saved, tokens(sheet.headers(row)));
            if (shift == null) continue;
            if (found != null) return null;
            found = new Axis(row, shift);
        }
        return found;
    }

    private static Axis uniqueColumn(SpreadsheetData.Sheet sheet, List<Token> saved) {
        Axis found = null;
        for (int column = 0; column < sheet.columns(); column++) {
            Integer shift = samePattern(saved, tokens(sheet.rowTitles(column)));
            if (shift == null) continue;
            if (found != null) return null;
            found = new Axis(column, shift);
        }
        return found;
    }

    private static Integer samePattern(List<Token> saved, List<Token> actual) {
        if (saved.isEmpty() || actual.size() < saved.size()) return null;
        Set<Integer> shifts = new LinkedHashSet<>();
        Map<Integer, String> actualByIndex = new HashMap<>();
        for (Token token : actual) actualByIndex.put(token.index(), token.value());
        Token first = saved.get(0);
        for (Token candidate : actual) {
            if (!candidate.value().equals(first.value())) continue;
            int shift = candidate.index() - first.index(); boolean matches = true;
            for (Token token : saved) if (!token.value().equals(actualByIndex.get(token.index() + shift))) { matches = false; break; }
            if (matches) shifts.add(shift);
        }
        return shifts.size() == 1 ? shifts.iterator().next() : null;
    }

    private static List<Token> tokens(List<String> values) {
        List<Token> result = new ArrayList<>();
        for (int i = 0; i < values.size(); i++) {
            String value = SpreadsheetData.normalize(values.get(i));
            if (!value.isBlank()) result.add(new Token(i, value));
        }
        return result;
    }
}
