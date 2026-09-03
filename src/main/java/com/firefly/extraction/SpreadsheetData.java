package com.firefly.extraction;

import java.nio.file.Path;
import java.util.*;

/** 已关闭源文件的只读快照；稀疏存储避免为整个工作表建立空单元格。 */
public record SpreadsheetData(Path path, long modified, long size, List<Sheet> sheets) {
    public SpreadsheetData { sheets = List.copyOf(sheets); }
    public record Cell(String display, String numeric, boolean date, boolean formula, String error) {
        public static final Cell EMPTY = new Cell("", null, false, false, "");
        public boolean blank() { return display.isEmpty() && numeric == null && error.isEmpty(); }
    }
    public record Merge(int firstRow, int lastRow, int firstColumn, int lastColumn) {
        boolean contains(int row, int column) { return row >= firstRow && row <= lastRow && column >= firstColumn && column <= lastColumn; }
    }
    public record Sheet(String name, int rows, int columns, Map<Long, Cell> cells, List<Merge> merges) {
        public Sheet { cells = Map.copyOf(cells); merges = List.copyOf(merges); }
        public Cell cell(int row, int column) {
            if (row < 0 || column < 0 || row >= rows || column >= columns) return Cell.EMPTY;
            Cell direct = cells.get(key(row, column));
            if (direct != null && !direct.blank()) return direct;
            for (Merge merge : merges) if (merge.contains(row, column)) {
                return cells.getOrDefault(key(merge.firstRow(), merge.firstColumn()), Cell.EMPTY);
            }
            return Cell.EMPTY;
        }
        public List<String> headers(int row) {
            List<String> result = new ArrayList<>();
            for (int c = 0; c < columns; c++) result.add(normalize(cell(row, c).display()));
            return List.copyOf(result);
        }
        public List<String> rowTitles(int column) {
            List<String> result = new ArrayList<>();
            for (int r = 0; r < rows; r++) result.add(normalize(cell(r, column).display()));
            return List.copyOf(result);
        }
    }
    public static long key(int row, int column) { return ((long) row << 32) | (column & 0xffffffffL); }
    public static String normalize(String value) { return value == null ? "" : value.strip().replace('\u3000', ' '); }
    public static String address(int row, int column) {
        StringBuilder letters = new StringBuilder();
        for (int c = column + 1; c > 0; c = (c - 1) / 26) letters.append((char) ('A' + (c - 1) % 26));
        return letters.reverse().append(row + 1).toString();
    }
}
