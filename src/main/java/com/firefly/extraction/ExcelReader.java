package com.firefly.extraction;

import com.firefly.core.OperationProgress;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.NumberToTextConverter;
import org.apache.poi.xssf.usermodel.XSSFCell;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** 只读 xlsx：读取保存的公式结果，绝不执行公式或写回源文件。 */
public final class ExcelReader {
    public SpreadsheetData read(Path path, OperationProgress progress, Runnable checkpoint) throws IOException {
        if (!path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".xlsx")) throw new IOException("请选择 .xlsx 文件；旧版 .xls 请先另存为 .xlsx。");
        long modified = Files.getLastModifiedTime(path).toMillis(), size = Files.size(path);
        if (size > 128L * 1024 * 1024) throw new IOException("表格超过 128 MB，请先拆分文件。");
        DataFormatter formatter = new DataFormatter(Locale.getDefault());
        formatter.setUseCachedValuesForFormulaCells(true);
        List<SpreadsheetData.Sheet> sheets = new ArrayList<>();
        int cellCount = 0;
        checkpoint.run();
        try (var input = Files.newInputStream(path); XSSFWorkbook workbook = new XSSFWorkbook(input)) {
            for (int i = 0; i < workbook.getNumberOfSheets(); i++) {
                checkpoint.run();
                Sheet sheet = workbook.getSheetAt(i);
                Map<Long, SpreadsheetData.Cell> cells = new LinkedHashMap<>();
                int rows = 0, columns = 0;
                for (Row row : sheet) {
                    checkpoint.run();
                    for (Cell cell : row) {
                        if (++cellCount > 300_000) throw new IOException("表格包含超过 30 万个已定义单元格，请拆分后打开。");
                        cells.put(SpreadsheetData.key(row.getRowNum(), cell.getColumnIndex()), readCell((XSSFCell) cell, formatter));
                        columns = Math.max(columns, cell.getColumnIndex() + 1);
                    }
                    rows = Math.max(rows, row.getRowNum() + 1);
                }
                List<SpreadsheetData.Merge> merges = new ArrayList<>();
                for (var merge : sheet.getMergedRegions()) {
                    merges.add(new SpreadsheetData.Merge(merge.getFirstRow(), merge.getLastRow(), merge.getFirstColumn(), merge.getLastColumn()));
                    rows = Math.max(rows, merge.getLastRow() + 1); columns = Math.max(columns, merge.getLastColumn() + 1);
                }
                sheets.add(new SpreadsheetData.Sheet(sheet.getSheetName(), rows, columns, cells, merges));
                progress.update("读取工作表：" + sheet.getSheetName(), i + 1, workbook.getNumberOfSheets());
            }
        } catch (java.util.concurrent.CancellationException e) { throw e;
        } catch (RuntimeException e) { throw new IOException("无法读取表格，文件可能损坏、加密或格式不受支持：" + e.getMessage(), e); }
        checkpoint.run();
        if (Files.getLastModifiedTime(path).toMillis() != modified || Files.size(path) != size) throw new IOException("读取期间文件发生变化，请重新打开。");
        return new SpreadsheetData(path.toAbsolutePath(), modified, size, sheets);
    }

    private SpreadsheetData.Cell readCell(XSSFCell cell, DataFormatter formatter) {
        boolean formula = cell.getCellType() == CellType.FORMULA;
        if (formula && (!cell.getCTCell().isSetV() || cell.getCTCell().getV() == null
                || (cell.getCTCell().getV().isEmpty() && cell.getCachedFormulaResultType() != CellType.STRING))) {
            return new SpreadsheetData.Cell("", null, false, true, "公式没有已保存结果，请用 Excel 计算并保存后刷新");
        }
        CellType type = formula ? cell.getCachedFormulaResultType() : cell.getCellType();
        if (type == CellType.ERROR) return new SpreadsheetData.Cell("", null, false, formula, "单元格错误：" + FormulaError.forInt(cell.getErrorCellValue()).getString());
        try {
            boolean date = type == CellType.NUMERIC && DateUtil.isCellDateFormatted(cell);
            String number = type == CellType.NUMERIC ? NumberToTextConverter.toText(cell.getNumericCellValue()) : null;
            return new SpreadsheetData.Cell(formatter.formatCellValue(cell), number, date, formula, "");
        } catch (RuntimeException e) {
            return new SpreadsheetData.Cell("", null, false, formula, "单元格内容无法读取");
        }
    }
}
