package com.firefly.extraction;

import com.firefly.TemplateToolApp;
import com.firefly.application.TemplateSession;
import com.firefly.core.*;
import com.firefly.ui.DataExtractionPanel;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;

import javax.swing.*;
import javax.swing.Timer;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.List;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

/** 真实 xlsx 的读取、结构重定位、配置合并、撤销与双选项卡集成回归。 */
public final class ExtractionTests {
    private static int checks;
    private static final MappingEngine ENGINE = new MappingEngine();
    private static final Runnable CHECKPOINT = () -> { };

    public static void main(String[] args) throws Exception {
        Path dir = Files.createTempDirectory("excel-extraction-");
        Path original = dir.resolve("8月.xlsx"), moved = dir.resolve("9月改名.xlsx");
        fixture(original, false, false); fixture(moved, true, false);
        SpreadsheetData a = read(original), b = read(moved);
        readResults(a);
        mappingRules(a, b, dir);
        configuration(a, dir);
        undoAndSources();
        uiIntegration(original, a, dir);
        System.out.println("All " + checks + " extraction checks passed.");
    }
    private static SpreadsheetData read(Path path) throws Exception { return new ExcelReader().read(path, OperationProgress.NONE, CHECKPOINT); }
    private static TemplateSession session() {
        TemplateSession session = new TemplateSession(); String text = "{{客户}} {{数量}} {{单价}} {{金额}} {{备注}}";
        session.load("example.txt", text, new TemplateConfig("example.txt"), TemplateParser.parse(text));
        session.activateType("客户", VariableType.SHORT_TEXT, "原客户");
        session.activateType("备注", VariableType.MULTILINE_TEXT, "原备注");
        return session;
    }
    private static void fixture(Path path, boolean moved, boolean duplicate) throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet(moved ? "9月统计" : "8月统计");
            int h = moved ? 2 : 0;
            String[] headers = moved ? new String[]{"金额", "客户", "单价", "数量", "备注"} : new String[]{"客户", "数量", "单价", "金额", "备注"};
            Row header = sheet.createRow(h);
            for (int c = 0; c < headers.length; c++) header.createCell(c).setCellValue(headers[c]);
            int client = moved ? 1 : 0, qty = moved ? 3 : 1, price = 2, amount = moved ? 0 : 3;
            Row row = sheet.createRow(h + 1);
            row.createCell(client).setCellValue("华东"); row.createCell(qty).setCellValue(3); row.createCell(price).setCellValue(2.345);
            row.createCell(amount).setCellFormula(SpreadsheetData.address(h + 1, qty) + "*" + SpreadsheetData.address(h + 1, price));
            row.createCell(4).setCellValue("第一行\n第二行");
            CellStyle money = workbook.createCellStyle(); money.setDataFormat(workbook.createDataFormat().getFormat("#,##0.00"));
            row.getCell(price).setCellStyle(money); row.getCell(amount).setCellStyle(money);
            Row second = sheet.createRow(h + 2); second.createCell(client).setCellValue(duplicate ? "华东" : "华南");
            second.createCell(qty).setCellValue(8); second.createCell(price).setCellValue(10); second.createCell(amount).setCellValue(80);
            Sheet types = workbook.createSheet("类型与公式");
            Row typesRow = types.createRow(0);
            typesRow.createCell(0).setCellValue(123);
            CellStyle id = workbook.createCellStyle(); id.setDataFormat(workbook.createDataFormat().getFormat("00000")); typesRow.getCell(0).setCellStyle(id);
            typesRow.createCell(1).setCellValue(java.time.LocalDate.of(2026, 9, 3));
            CellStyle date = workbook.createCellStyle(); date.setDataFormat(workbook.createDataFormat().getFormat("yyyy-mm-dd")); typesRow.getCell(1).setCellStyle(date);
            typesRow.createCell(2).setCellFormula("1/0");
            typesRow.createCell(3).setCellValue("合并标题"); types.addMergedRegion(new CellRangeAddress(0, 0, 3, 4));
            workbook.getCreationHelper().createFormulaEvaluator().evaluateAll();
            typesRow.createCell(5).setCellFormula("1+2"); // 故意不保存计算结果。
            ((org.apache.poi.xssf.usermodel.XSSFCell) typesRow.createCell(6)).setCellFormula("1+3");
            ((org.apache.poi.xssf.usermodel.XSSFCell) typesRow.getCell(6)).getCTCell().setV("");
            try (var output = Files.newOutputStream(path)) { workbook.write(output); }
        }
    }
    private static void readResults(SpreadsheetData book) {
        var sheet = book.sheets().get(0);
        equal("2.35", sheet.cell(1, 2).display(), "Excel display format retained");
        equal("2.345", sheet.cell(1, 2).numeric(), "underlying precision retained");
        check(sheet.cell(1, 3).formula(), "formula origin identified");
        check(!sheet.cell(1, 3).display().contains("*"), "formula expression is not used as value");
        equal("00123", book.sheets().get(1).cell(0, 0).display(), "leading zeros retained for text");
        check(book.sheets().get(1).cell(0, 1).date(), "Excel date identified");
        check(!book.sheets().get(1).cell(0, 2).error().isEmpty(), "formula error reported");
        equal("合并标题", book.sheets().get(1).cell(0, 4).display(), "merged header resolves to anchor");
        check(book.sheets().get(1).cell(0, 5).error().contains("没有已保存结果"), "missing formula cache never becomes zero");
        check(book.sheets().get(1).cell(0, 6).error().contains("没有已保存结果"), "empty numeric formula cache never becomes zero");
    }
    private static void mappingRules(SpreadsheetData a, SpreadsheetData b, Path dir) throws Exception {
        TemplateSession session = session(); var sheet = a.sheets().get(0);
        var quantity = ENGINE.bind("数量", sheet, MappingProfile.Mode.TITLES, 0, 0, 1, 1, MappingProfile.EmptyPolicy.ERROR);
        var amount = ENGINE.bind("金额", sheet, MappingProfile.Mode.TITLES, 0, 0, 1, 3, MappingProfile.EmptyPolicy.ERROR);
        MappingProfile rules = MappingProfile.EMPTY.put(quantity).put(amount);
        List<MappingEngine.Preview> result = ENGINE.preview(b, rules, session.variables(), "9月统计", 3, Set.of(), CHECKPOINT);
        var q = find(result, "数量"); check(q.error().isEmpty(), "renamed workbook/sheet and reordered columns reused");
        equal("3", q.value(), "row title selects correct record after layout change");
        check(q.source().contains("D4"), "new address shown");
        check(find(result, "金额").formula(), "mapped cached formula identified");
        check(!find(result, "备注").apply(), "manual variable excluded");
        var record = ENGINE.bind("数量", sheet, MappingProfile.Mode.RECORD, 0, 0, 1, 1, MappingProfile.EmptyPolicy.ERROR);
        result = ENGINE.preview(a, MappingProfile.EMPTY.put(record), session.variables(), sheet.name(), 2, Set.of(), CHECKPOINT);
        equal("8", find(result, "数量").value(), "record selector changes data without rebinding");
        Path duplicate = dir.resolve("duplicate.xlsx"); fixture(duplicate, false, true);
        check(!find(ENGINE.preview(read(duplicate), rules, session.variables(), sheet.name(), 1, Set.of(), CHECKPOINT), "数量").error().isEmpty(), "duplicate row titles rejected");
        var fixed = ENGINE.bind("数量", sheet, MappingProfile.Mode.FIXED, 0, 0, 1, 1, MappingProfile.EmptyPolicy.ERROR);
        check(!find(ENGINE.preview(b, MappingProfile.EMPTY.put(fixed), session.variables(), "9月统计", 3, Set.of(), CHECKPOINT), "数量").error().isEmpty(), "fixed mapping rejects changed columns");
        var empty = ENGINE.bind("备注", sheet, MappingProfile.Mode.FIXED, 0, 0, 2, 4, MappingProfile.EmptyPolicy.KEEP);
        check(!find(ENGINE.preview(a, MappingProfile.EMPTY.put(empty), session.variables(), sheet.name(), 2, Set.of(empty), CHECKPOINT), "备注").apply(), "blank keep policy preserves manual value");
        var clear = new MappingProfile.Binding(empty.variable(), empty.sheet(), empty.mode(), empty.headerRow(), empty.titleColumn(), empty.row(), empty.column(), empty.rowTitle(), empty.columnTitle(), empty.headers(), empty.fixedRowTitle(), MappingProfile.EmptyPolicy.CLEAR, true);
        check(find(ENGINE.preview(a, MappingProfile.EMPTY.put(clear), session.variables(), sheet.name(), 2, Set.of(clear), CHECKPOINT), "备注").apply(), "explicit blank text can clear target");
        var date = ENGINE.bind("数量", a.sheets().get(1), MappingProfile.Mode.FIXED, 0, 0, 0, 1, MappingProfile.EmptyPolicy.ERROR);
        check(find(ENGINE.preview(a, MappingProfile.EMPTY.put(date), session.variables(), sheet.name(), 1, Set.of(date), CHECKPOINT), "数量").error().contains("日期"), "date serial never silently fills numeric variable");
        MappingProfile roundTrip = MappingProfile.fromJson(rules.toJson());
        check(roundTrip.equals(rules), "mapping profile round trips");
        check(!JsonData.stringify(rules.toJson()).contains(".xlsx"), "workbook name not bound in mapping config");
        Map<Long, SpreadsheetData.Cell> reducedCells = new LinkedHashMap<>(sheet.cells()); reducedCells.remove(SpreadsheetData.key(0, 2));
        SpreadsheetData reduced = new SpreadsheetData(a.path(), a.modified(), a.size(), List.of(new SpreadsheetData.Sheet(sheet.name(), sheet.rows(), sheet.columns(), reducedCells, List.of())));
        check(find(ENGINE.preview(reduced, rules, session.variables(), sheet.name(), 1, Set.of(), CHECKPOINT), "数量").error().isEmpty(), "unrelated missing header does not discard valid mapping");
        Map<Long, SpreadsheetData.Cell> cells = new LinkedHashMap<>(sheet.cells());
        cells.put(SpreadsheetData.key(0, 5), new SpreadsheetData.Cell("数量", null, false, false, ""));
        SpreadsheetData repeated = new SpreadsheetData(a.path(), a.modified(), a.size(), List.of(new SpreadsheetData.Sheet(sheet.name(), sheet.rows(), 6, cells, List.of())));
        check(!find(ENGINE.preview(repeated, rules, session.variables(), sheet.name(), 1, Set.of(), CHECKPOINT), "数量").error().isEmpty(), "duplicate column title is not guessed");
    }
    private static void configuration(SpreadsheetData book, Path dir) throws Exception {
        TemplateConfigStore store = new TemplateConfigStore(dir.resolve("config-test"));
        var binding = ENGINE.bind("数量", book.sheets().get(0), MappingProfile.Mode.TITLES, 0, 0, 1, 1, MappingProfile.EmptyPolicy.ERROR);
        MappingProfile profile = MappingProfile.EMPTY.put(binding);
        store.saveMapping("目录/模板.txt", profile);
        store.save("目录/模板.txt", Map.of("数量", new VariableInputState("数量", VariableType.NUMBER, "7", false)), 3);
        check(store.load("目录/模板.txt").dataExtraction().equals(profile), "variable save preserves mappings");
        store.saveMapping("目录/模板.txt", profile.put(binding.enabled(false)));
        equal("7", store.load("目录/模板.txt").variables().get("数量").value(), "mapping save preserves variable edits");
        store.rename("目录/模板.txt", "目录/改名.txt");
        check(store.load("目录/改名.txt").dataExtraction().bindings().size() == 1, "template rename carries mapping");
        store.pruneUnusedVariables(store.findUnusedVariables(Map.of("目录/改名.txt", Set.of())));
        check(store.load("目录/改名.txt").dataExtraction().bindings().size() == 1, "unused-variable cleanup preserves mapping");
        AppConfig config = new AppConfig(); config.setLastExcelDirectory(dir.toString()); config.setLastExportDirectory(dir.resolve("results").toString());
        AppConfigStore appStore = new AppConfigStore(dir); appStore.save(config);
        equal(config.lastExcelDirectory(), appStore.load().lastExcelDirectory(), "Excel directory remembered");
        equal(config.lastExportDirectory(), appStore.load().lastExportDirectory(), "export directory remembered separately");
        Path legacy = store.configFileForTemplate("old.txt"); Files.createDirectories(legacy.getParent());
        Files.writeString(legacy, "{\"version\":3,\"variables\":{\"数量\":{\"type\":\"NUMBER\",\"value\":\"12\"}}}");
        equal("12", store.load("old.txt").variables().get("数量").value(), "old config values still read");
        check(store.load("old.txt").dataExtraction().bindings().isEmpty(), "old config gets empty mapping default");
    }
    private static void undoAndSources() {
        TemplateSession session = session(); session.setValue("数量", "1"); session.setValue("金额", "2");
        session.applyImportedValues(Map.of("数量", "3", "金额", "7.035"), Map.of("数量", "表格 / B2", "金额", "表格 / D2"));
        check(session.sourceDescription("数量").contains("B2"), "source tooltip retained");
        session.setValue("数量", "100");
        check(session.sourceDescription("数量").contains("已手工修改"), "manual override identified");
        Set<String> conflicts = session.undoImport();
        check(conflicts.equals(Set.of("数量")), "undo reports only edited conflicts");
        equal("100", session.variable("数量").value(), "undo protects later manual edit");
        equal("2", session.variable("金额").value(), "undo restores untouched imported variable");
        session.applyImportedValues(Map.of("金额", "4"), Map.of("金额", "表格 / D2"));
        session.synchronizeText("{{数量}} {{=金额*2}}");
        check(session.undoImport().contains("金额") && session.variable("金额").numericLocked(), "undo preserves new expression lock");
        session.load("other.txt", "{{数量}}", new TemplateConfig("other.txt"), TemplateParser.parse("{{数量}}"));
        check(!session.canUndoImport() && session.sourceDescription("数量").isEmpty(), "template switch clears provenance and undo");
    }
    private static void uiIntegration(Path original, SpreadsheetData data, Path dir) throws Exception {
        AtomicReference<TemplateToolApp> owner = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            try {
                TemplateToolApp app = new TemplateToolApp(dir.resolve("ui")); owner.set(app);
                TemplateSession session = field(app, "session", TemplateSession.class);
                String template = "{{数量}} {{金额}}";
                var profile = MappingProfile.EMPTY.put(ENGINE.bind("数量", data.sheets().get(0), MappingProfile.Mode.RECORD, 0, 0, 1, 1, MappingProfile.EmptyPolicy.ERROR));
                TemplateConfigStore store = field(app, "templateConfigStore", TemplateConfigStore.class); store.saveMapping("example.txt", profile);
                session.load("example.txt", template, store.load("example.txt"), TemplateParser.parse(template));
                invoke(app, "setTemplateText", new Class<?>[]{String.class}, template);
                invoke(app, "refreshVariablePanel", new Class<?>[]{});
                JTabbedPane tabs = field(app, "tabs", JTabbedPane.class); tabs.setSelectedIndex(1);
                check(tabs.getTitleAt(0).equals("模板填充") && tabs.getTitleAt(1).equals("数据提取"), "two named tabs available");
                check(app.getRootPane().getDefaultButton() == null, "extraction tab has no generation default button");
                invoke(app, "generate", new Class<?>[]{});
                check(field(app, "generationSequence", Long.class) == 0, "generation blocked in extraction tab");
                DataExtractionPanel panel = field(app, "extractionPanel", DataExtractionPanel.class);
                panel.openWorkbook(original);
            } catch (Exception e) { throw new RuntimeException(e); }
        });
        try {
            awaitPreview(owner.get());
            SwingUtilities.invokeAndWait(() -> {
                try {
                    TemplateToolApp app = owner.get(); DataExtractionPanel panel = field(app, "extractionPanel", DataExtractionPanel.class);
                    TemplateSession session = field(app, "session", TemplateSession.class);
                    equal(original.getParent().toString(), field(app, "appConfig", AppConfig.class).lastExcelDirectory(), "successful UI open remembers directory");
                    panel.applyPreview(); equal("3", session.variable("数量").value(), "panel applies preview through shared session");
                    check(!field(app, "saveResultBtn", JButton.class).isEnabled(), "application invalidates old export");
                    JTabbedPane tabs = field(app, "tabs", JTabbedPane.class); tabs.setSelectedIndex(0);
                    check(app.getRootPane().getDefaultButton() == field(app, "generateBtn", JButton.class), "generation default restored in fill tab");
                    tabs.setSelectedIndex(1);
                } catch (Exception e) { throw new RuntimeException(e); }
            });
            awaitPreview(owner.get());
            SwingUtilities.invokeAndWait(() -> {
                try {
                    TemplateToolApp app = owner.get(); app.setSize(1280, 920);
                    Container content = app.getContentPane(); content.setSize(1280, 870); layout(content);
                    BufferedImage image = new BufferedImage(1280, 870, BufferedImage.TYPE_INT_RGB);
                    Graphics2D graphics = image.createGraphics(); content.printAll(graphics); graphics.dispose();
                    Path preview = dir.resolve("extraction-preview.png");
                    javax.imageio.ImageIO.write(image, "png", preview.toFile());
                } catch (Exception e) { throw new RuntimeException(e); }
            });
            SwingUtilities.invokeAndWait(() -> {
                try { field(owner.get(), "session", TemplateSession.class).setValue("数量", "99"); }
                catch (Exception e) { throw new RuntimeException(e); }
            });
            awaitPreview(owner.get());
            Files.setLastModifiedTime(original, java.nio.file.attribute.FileTime.fromMillis(data.modified() + 5000));
            SwingUtilities.invokeAndWait(() -> {
                try {
                    var app = owner.get(); var panel = field(app, "extractionPanel", DataExtractionPanel.class);
                    panel.applyPreview();
                    equal("99", field(app, "session", TemplateSession.class).variable("数量").value(), "external file changes block stale application");
                    check(!field(panel, "apply", JButton.class).isEnabled(), "refresh required after external change");
                    Path exported = dir.resolve("result.txt"); Files.writeString(exported, "结果");
                    invoke(app, "resultExported", new Class<?>[]{java.io.File.class}, exported.toFile());
                    JFileChooser chooser = (JFileChooser) invoke(app, "resultChooser", new Class<?>[]{String.class, String.class, String.class, String.class}, "保存", "result.txt", "文本", "txt");
                    equal(dir.toString(), chooser.getCurrentDirectory().toPath().toString(), "result chooser starts in last successful export directory");
                } catch (Exception e) { throw new RuntimeException(e); }
            });
        } finally {
            SwingUtilities.invokeAndWait(() -> {
                try { field(owner.get(), "templateConfigSaveTimer", Timer.class).stop(); field(owner.get(), "templateSyncTimer", Timer.class).stop(); owner.get().dispose(); }
                catch (Exception e) { throw new RuntimeException(e); }
            });
        }
    }
    private static void awaitPreview(TemplateToolApp app) throws Exception {
        long deadline = System.nanoTime() + 8_000_000_000L;
        while (System.nanoTime() < deadline) {
            boolean[] ready = {false};
            SwingUtilities.invokeAndWait(() -> {
                try { var panel = field(app, "extractionPanel", DataExtractionPanel.class); ready[0] = !field(panel, "previewPending", Boolean.class) && !panel.previews().isEmpty(); }
                catch (Exception e) { throw new RuntimeException(e); }
            });
            if (ready[0]) return; Thread.sleep(30);
        }
        throw new AssertionError("preview did not complete");
    }
    private static void layout(Container container) { container.doLayout(); for (Component child : container.getComponents()) if (child instanceof Container nested) layout(nested); }
    private static MappingEngine.Preview find(List<MappingEngine.Preview> previews, String variable) { return previews.stream().filter(p -> p.variable().equals(variable)).findFirst().orElseThrow(); }
    private static <T> T field(Object owner, String name, Class<T> type) throws Exception { Field f = owner.getClass().getDeclaredField(name); f.setAccessible(true); return type.cast(f.get(owner)); }
    private static Object invoke(Object owner, String name, Class<?>[] types, Object... args) throws Exception { Method m = owner.getClass().getDeclaredMethod(name, types); m.setAccessible(true); return m.invoke(owner, args); }
    private static void equal(String expected, String actual, String message) { check(expected.equals(actual), message + ": " + actual); }
    private static void check(boolean condition, String message) { checks++; if (!condition) throw new AssertionError(message); }
}
