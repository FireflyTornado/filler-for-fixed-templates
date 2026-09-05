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
        columnRecords(a, b);
        emptyPolicies(a);
        configuration(a, dir);
        mappingStateMigration(a, dir);
        undoAndSources();
        uiIntegration(original, a, dir);
        columnUiIntegration(dir);
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
    @SuppressWarnings("unchecked")
    private static void mappingRules(SpreadsheetData a, SpreadsheetData b, Path dir) throws Exception {
        TemplateSession session = session(); var sheet = a.sheets().get(0);
        var quantity = ENGINE.bind("数量", sheet, MappingProfile.Mode.TITLES, 0, 0, 1, 1, MappingProfile.EmptyPolicy.KEEP);
        var amount = ENGINE.bind("金额", sheet, MappingProfile.Mode.TITLES, 0, 0, 1, 3, MappingProfile.EmptyPolicy.KEEP);
        MappingProfile rules = MappingProfile.EMPTY.put(quantity).put(amount);
        rules = rules.withSheetSetting(new MappingProfile.SheetSettings("8月统计", 0, 0, 2, 1))
                .withSheetSetting(new MappingProfile.SheetSettings("9月统计", 2, 1, 4, 0));
        List<MappingEngine.Preview> result = ENGINE.preview(b, rules, session.variables(), "9月统计", 3, 1, Set.of(), CHECKPOINT);
        var q = find(result, "数量"); check(q.error().isEmpty(), "renamed workbook/sheet and reordered columns reused");
        equal("3", q.value(), "row title selects correct record after layout change");
        check(q.source().contains("D4"), "new address shown");
        check(quantity.rowTitle().equals("华东") && quantity.columnTitle().equals("数量"), "title mapping stores both title texts when it is created");
        check(rules.sheetSetting("8月统计").recordRow() == 2 && rules.sheetSetting("9月统计").headerRow() == 2,
                "each worksheet keeps independent structure and global record positions");
        check(find(result, "金额").formula(), "mapped cached formula identified");
        check(!find(result, "备注").apply(), "manual variable excluded");
        var whileViewingOtherSheet = find(ENGINE.preview(a, rules, session.variables(), "类型与公式", 0, 0, Set.of(), CHECKPOINT), "数量");
        equal("3", whileViewingOtherSheet.value(), "switching the viewed worksheet does not redirect a saved mapping");
        check(whileViewingOtherSheet.source().startsWith("8月统计 /"), "saved mapping continues to report its own source worksheet");
        var septemberAmount = ENGINE.bind("金额", b.sheets().get(0), MappingProfile.Mode.TITLES, 2, 1, 3, 0, MappingProfile.EmptyPolicy.KEEP);
        MappingProfile multiRules = MappingProfile.EMPTY.put(quantity).put(septemberAmount)
                .withSheetSetting(new MappingProfile.SheetSettings("8月统计", 0, 0, 2, 1))
                .withSheetSetting(new MappingProfile.SheetSettings("9月统计", 2, 1, 4, 0));
        SpreadsheetData multiBook = new SpreadsheetData(a.path(), a.modified(), a.size(), List.of(a.sheets().get(0), b.sheets().get(0)));
        for (String viewed : List.of("8月统计", "9月统计")) {
            var multi = ENGINE.preview(multiBook, multiRules, session.variables(), viewed, 1, 1, Set.of(), CHECKPOINT);
            equal("3", find(multi, "数量").value(), "batch preview reads quantity from August while viewing " + viewed);
            equal("7.035", find(multi, "金额").value(), "batch preview reads amount from September while viewing " + viewed);
            check(find(multi, "数量").source().startsWith("8月统计 /") && find(multi, "金额").source().startsWith("9月统计 /"),
                    "batch preview preserves both source worksheets while viewing " + viewed);
        }
        var record = ENGINE.bind("数量", sheet, MappingProfile.Mode.RECORD, 0, 0, 1, 1, MappingProfile.EmptyPolicy.KEEP);
        result = ENGINE.preview(a, MappingProfile.EMPTY.put(record), session.variables(), sheet.name(), 2, 1, Set.of(), CHECKPOINT);
        equal("8", find(result, "数量").value(), "record selector changes data without rebinding");
        check(find(result, "数量").source().contains("B3 / 行标题：华南 / 列标题：数量"), "record source includes resolved row and column titles");
        var localAmount = ENGINE.bind("金额", sheet, MappingProfile.Mode.RECORD, 0, 0, 1, 3,
                MappingProfile.EmptyPolicy.KEEP, MappingProfile.SelectionScope.LOCAL);
        result = ENGINE.preview(a, MappingProfile.EMPTY.put(record).put(localAmount), session.variables(), sheet.name(), 2, 1, Set.of(), CHECKPOINT);
        equal("8", find(result, "数量").value(), "global row applies to global locked-column mapping");
        equal("7.035", find(result, "金额").value(), "local locked-column mapping retains its own row");
        result = ENGINE.preview(a, MappingProfile.EMPTY.put(record).put(localAmount), session.variables(), sheet.name(), 1, 1, Set.of(), CHECKPOINT);
        equal("3", find(result, "数量").value(), "changing global row updates global mapping");
        equal("7.035", find(result, "金额").value(), "changing global row does not update local mapping");
        Path duplicate = dir.resolve("duplicate.xlsx"); fixture(duplicate, false, true);
        check(!find(ENGINE.preview(read(duplicate), rules, session.variables(), sheet.name(), 1, 1, Set.of(), CHECKPOINT), "数量").error().isEmpty(), "duplicate row titles rejected");
        var fixed = ENGINE.bind("数量", sheet, MappingProfile.Mode.FIXED, 0, 0, 1, 1, MappingProfile.EmptyPolicy.KEEP);
        check(!find(ENGINE.preview(b, MappingProfile.EMPTY.put(fixed), session.variables(), "9月统计", 3, 1, Set.of(), CHECKPOINT), "数量").error().isEmpty(), "fixed mapping rejects changed columns");
        var empty = ENGINE.bind("备注", sheet, MappingProfile.Mode.FIXED, 0, 0, 2, 4, MappingProfile.EmptyPolicy.KEEP);
        check(!find(ENGINE.preview(a, MappingProfile.EMPTY.put(empty), session.variables(), sheet.name(), 2, 1, Set.of(empty), CHECKPOINT), "备注").apply(), "blank keep policy preserves manual value");
        var zeroText = empty.withEmptyPolicy(MappingProfile.EmptyPolicy.ZERO);
        check(!find(ENGINE.preview(a, MappingProfile.EMPTY.put(zeroText), session.variables(), sheet.name(), 2, 1, Set.of(zeroText), CHECKPOINT), "备注").error().isEmpty(), "blank text cannot be replaced with zero");
        var date = ENGINE.bind("数量", a.sheets().get(1), MappingProfile.Mode.FIXED, 0, 0, 0, 1, MappingProfile.EmptyPolicy.KEEP);
        check(find(ENGINE.preview(a, MappingProfile.EMPTY.put(date), session.variables(), sheet.name(), 1, 1, Set.of(date), CHECKPOINT), "数量").error().contains("日期"), "date serial never silently fills numeric variable");
        MappingProfile roundTrip = MappingProfile.fromJson(rules.toJson());
        check(roundTrip.equals(rules), "mapping profile round trips");
        check(roundTrip.sheetSettings().size() == 2, "worksheet settings round trip with mappings");
        Map<String, Object> legacyRoot = new LinkedHashMap<>((Map<String, Object>) rules.toJson());
        List<Map<String, Object>> legacyBindings = new ArrayList<>();
        for (Object raw : (List<?>) legacyRoot.get("bindings")) {
            Map<String, Object> old = new LinkedHashMap<>((Map<String, Object>) raw); old.remove("selectionScope"); legacyBindings.add(old);
        }
        legacyRoot.put("bindings", legacyBindings);
        check(MappingProfile.fromJson(legacyRoot).bindings().stream().allMatch(binding -> binding.selectionScope() == MappingProfile.SelectionScope.GLOBAL),
                "mappings saved before scope support default to global selection");
        legacyRoot.remove("sheets");
        check(MappingProfile.fromJson(legacyRoot).sheetSettings().isEmpty(), "mappings saved before worksheet settings remain readable");
        check(rules.retainSheetSettings(Set.of("9月统计")).sheetSettings().stream().map(MappingProfile.SheetSettings::sheet).toList().equals(List.of("9月统计")),
                "stale worksheet settings can be removed without changing bindings");
        check(!JsonData.stringify(rules.toJson()).contains(".xlsx"), "workbook name not bound in mapping config");
        Map<Long, SpreadsheetData.Cell> reducedCells = new LinkedHashMap<>(sheet.cells()); reducedCells.remove(SpreadsheetData.key(0, 2));
        SpreadsheetData reduced = new SpreadsheetData(a.path(), a.modified(), a.size(), List.of(new SpreadsheetData.Sheet(sheet.name(), sheet.rows(), sheet.columns(), reducedCells, List.of())));
        check(find(ENGINE.preview(reduced, rules, session.variables(), sheet.name(), 1, 1, Set.of(), CHECKPOINT), "数量").error().isEmpty(), "unrelated missing header does not discard valid mapping");
        Map<Long, SpreadsheetData.Cell> cells = new LinkedHashMap<>(sheet.cells());
        cells.put(SpreadsheetData.key(0, 5), new SpreadsheetData.Cell("数量", null, false, false, ""));
        SpreadsheetData repeated = new SpreadsheetData(a.path(), a.modified(), a.size(), List.of(new SpreadsheetData.Sheet(sheet.name(), sheet.rows(), 6, cells, List.of())));
        check(!find(ENGINE.preview(repeated, rules, session.variables(), sheet.name(), 1, 1, Set.of(), CHECKPOINT), "数量").error().isEmpty(), "duplicate column title is not guessed");
    }
    private static void configuration(SpreadsheetData book, Path dir) throws Exception {
        TemplateConfigStore store = new TemplateConfigStore(dir.resolve("config-test"));
        var binding = ENGINE.bind("数量", book.sheets().get(0), MappingProfile.Mode.TITLES, 0, 0, 1, 1, MappingProfile.EmptyPolicy.KEEP);
        MappingProfile profile = MappingProfile.EMPTY.put(binding);
        store.saveMapping("目录/模板.txt", profile);
        store.save("目录/模板.txt", Map.of("数量", new VariableInputState("数量", VariableType.NUMBER, "7", false)), 3);
        check(store.load("目录/模板.txt").dataExtraction().equals(profile), "variable save preserves mappings");
        store.saveMapping("目录/模板.txt", profile.put(binding.withEmptyPolicy(MappingProfile.EmptyPolicy.ZERO)));
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
        for (String legacyPolicy : List.of("ERROR", "CLEAR", "KEEP")) {
            String json = JsonData.stringify(profile.toJson()).replace("\"KEEP\"", "\"" + legacyPolicy + "\"");
            MappingProfile restored = MappingProfile.fromJson(JsonData.parse(json));
            check(restored.bindings().size() == 1 && restored.bindings().get(0).emptyPolicy() == MappingProfile.EmptyPolicy.KEEP,
                    "legacy " + legacyPolicy + " policy migrates without losing mappings");
            check(restored.bindings().get(0).row() == binding.row(), "legacy coordinates retained");
        }
    }
    private static SpreadsheetData transpose(SpreadsheetData book) {
        var sheet = book.sheets().get(0);
        Map<Long, SpreadsheetData.Cell> cells = new LinkedHashMap<>();
        sheet.cells().forEach((key, cell) -> cells.put(SpreadsheetData.key((int) (long) key, (int) (key >> 32)), cell));
        return new SpreadsheetData(book.path(), book.modified(), book.size(), List.of(new SpreadsheetData.Sheet(sheet.name(), sheet.columns(), sheet.rows(), cells, List.of())));
    }
    private static void mappingStateMigration(SpreadsheetData book, Path dir) throws Exception {
        Path root = dir.resolve("mapping-migration");
        TemplateConfigStore store = new TemplateConfigStore(root);
        var binding = ENGINE.bind("数量", book.sheets().get(0), MappingProfile.Mode.RECORD, 0, 0, 1, 1, MappingProfile.EmptyPolicy.KEEP);
        Map<?, ?> item = (Map<?, ?>) ((List<?>) MappingProfile.EMPTY.put(binding).toJson().get("bindings")).get(0);
        Map<Object, Object> active = new LinkedHashMap<>(item); active.put("enabled", true);
        Map<Object, Object> disabled = new LinkedHashMap<>(item); disabled.put("variable", "备注"); disabled.put("enabled", false);
        Map<String, Object> oldRoot = new LinkedHashMap<>();
        oldRoot.put("version", 4); oldRoot.put("decimalPlaces", 3); oldRoot.put("custom", "保留扩展字段");
        oldRoot.put("variables", Map.of("备注", Map.of("type", "SHORT_TEXT", "value", "原备注")));
        oldRoot.put("dataExtraction", Map.of("version", 2, "bindings", List.of(active, disabled)));
        Path file = store.configFileForTemplate("子目录/旧模板.txt"); Files.createDirectories(file.getParent()); Files.writeString(file, JsonData.stringify(oldRoot));
        store.migrateLegacyMappingStates();
        String converted = Files.readString(file);
        check(!converted.contains("enabled"), "migration physically removes enabled flags from all saved bindings");
        check(converted.contains("保留扩展字段"), "migration preserves unknown configuration fields");
        TemplateConfig config = store.load("子目录/旧模板.txt");
        check(config.dataExtraction().get("数量") != null && config.dataExtraction().get("备注") == null, "disabled mapping converted to manual while active mapping retained");
        equal("原备注", config.variables().get("备注").value(), "migration preserves manual text");
        check(config.decimalPlaces() == 3, "migration preserves numeric precision");
        var modified = Files.getLastModifiedTime(file);
        store.migrateLegacyMappingStates(); store.load("子目录/旧模板.txt");
        check(Files.getLastModifiedTime(file).equals(modified) && Files.readString(file).equals(converted), "converted configuration is not rewritten on subsequent reads");
        Path onLoad = store.configFileForTemplate("读取时转换.txt"); Files.writeString(onLoad, JsonData.stringify(oldRoot));
        check(store.load("读取时转换.txt").dataExtraction().get("备注") == null && !Files.readString(onLoad).contains("enabled"), "individual loads convert imported legacy configuration immediately");
        check(!JsonData.stringify(config.dataExtraction().toJson()).contains("enabled"), "new configuration never writes runtime enabled state");
    }
    private static void columnRecords(SpreadsheetData a, SpreadsheetData b) {
        SpreadsheetData horizontal = transpose(a), moved = transpose(b);
        var sheet = horizontal.sheets().get(0); var session = session();
        var quantity = ENGINE.bind("数量", sheet, MappingProfile.Mode.COLUMN_RECORD, 0, 0, 1, 1, MappingProfile.EmptyPolicy.KEEP);
        var client = ENGINE.bind("客户", sheet, MappingProfile.Mode.COLUMN_RECORD, 0, 0, 0, 1,
                MappingProfile.EmptyPolicy.KEEP, MappingProfile.SelectionScope.LOCAL);
        var rules = MappingProfile.EMPTY.put(quantity).put(client);
        var first = ENGINE.preview(horizontal, rules, session.variables(), sheet.name(), 1, 1, Set.of(), CHECKPOINT);
        equal("3", find(first, "数量").value(), "locked row reads first selected column");
        equal("华东", find(first, "客户").value(), "locked row can include first worksheet row");
        var configured = rules.withSheetSetting(new MappingProfile.SheetSettings(sheet.name(), 0, 0, 1, 1));
        equal("华东", find(ENGINE.preview(horizontal, configured, session.variables(), sheet.name(), 1, 1, Set.of(), CHECKPOINT), "客户").value(),
                "worksheet-specific title-column lookup supports a locked row on the header row");
        var second = ENGINE.preview(horizontal, rules, session.variables(), sheet.name(), 4, 2, Set.of(), CHECKPOINT);
        equal("8", find(second, "数量").value(), "column selector changes numeric record independently of row selector");
        equal("华东", find(second, "客户").value(), "global column selector does not change local mapping");
        check(find(second, "数量").source().contains("C2 / 行标题：数量 / 列标题：华南"), "horizontal source includes resolved row and column titles");
        var relocated = find(ENGINE.preview(moved, rules, session.variables(), moved.sheets().get(0).name(), 0, 4, Set.of(), CHECKPOINT), "数量");
        equal("8", relocated.value(), "row and title-column movement follows row title");
        check(relocated.source().contains("E4"), "horizontal relocated address shown");
        for (int col : List.of(0, 3, -1)) check(!find(ENGINE.preview(horizontal, rules, session.variables(), sheet.name(), 1, col, Set.of(), CHECKPOINT), "数量").error().isEmpty(), "invalid column is not treated as blank: " + col);
        equal("3", find(ENGINE.preview(horizontal, rules, session.variables(), "wrong sheet", 1, 1, Set.of(), CHECKPOINT), "数量").value(),
                "locked-row mapping is independent of the worksheet currently shown in the editor");
        check(MappingProfile.fromJson(rules.toJson()).equals(rules), "horizontal mapping round trips");
        Map<Long, SpreadsheetData.Cell> duplicated = new LinkedHashMap<>(sheet.cells());
        duplicated.put(SpreadsheetData.key(5, 0), new SpreadsheetData.Cell("数量", null, false, false, ""));
        SpreadsheetData repeated = new SpreadsheetData(a.path(), a.modified(), a.size(), List.of(new SpreadsheetData.Sheet(sheet.name(), 6, 3, duplicated, List.of())));
        check(!find(ENGINE.preview(repeated, rules, session.variables(), sheet.name(), 1, 1, Set.of(), CHECKPOINT), "数量").error().isEmpty(), "duplicate row title rejected");
        SpreadsheetData merged = new SpreadsheetData(a.path(), a.modified(), a.size(), List.of(new SpreadsheetData.Sheet(sheet.name(), 6, 3, sheet.cells(), List.of(new SpreadsheetData.Merge(1, 2, 0, 0)))));
        Map<Long, SpreadsheetData.Cell> mergedCells = new LinkedHashMap<>(sheet.cells()); mergedCells.remove(SpreadsheetData.key(2, 0));
        merged = new SpreadsheetData(a.path(), a.modified(), a.size(), List.of(new SpreadsheetData.Sheet(sheet.name(), 6, 3, mergedCells, merged.sheets().get(0).merges())));
        check(!find(ENGINE.preview(merged, rules, session.variables(), sheet.name(), 1, 1, Set.of(), CHECKPOINT), "数量").error().isEmpty(), "merged row title spanning rows rejected");
        var vertical = ENGINE.bind("金额", sheet, MappingProfile.Mode.RECORD, 0, 0, 3, 1, MappingProfile.EmptyPolicy.KEEP);
        var mixed = ENGINE.preview(horizontal, rules.put(vertical), session.variables(), sheet.name(), 3, 2, Set.of(), CHECKPOINT);
        equal("8", find(mixed, "数量").value(), "horizontal selector independent in mixed mappings");
        equal("7.035", find(mixed, "金额").value(), "vertical selector independent in mixed mappings");
    }
    private static void emptyPolicies(SpreadsheetData book) {
        var session = session(); session.setValue("数量", "500");
        var sheet = book.sheets().get(0);
        for (String variable : List.of("数量", "客户", "备注")) {
            for (MappingProfile.EmptyPolicy policy : MappingProfile.EmptyPolicy.values()) {
                var binding = ENGINE.bind(variable, sheet, MappingProfile.Mode.FIXED, 0, 0, 2, 4, policy);
                var p = find(ENGINE.preview(book, MappingProfile.EMPTY.put(binding), session.variables(), sheet.name(), 2, 1, Set.of(binding), CHECKPOINT), variable);
                if (policy == MappingProfile.EmptyPolicy.KEEP) {
                    check(!p.apply() && p.error().isEmpty(), "blank keep skips " + variable);
                    equal(session.variable(variable).value(), p.oldValue(), "blank keep retains old value");
                    if (!variable.equals("数量")) check(p.status().contains("提示") && p.status().contains("原文"), "blank text keep displays explicit prompt");
                } else if (variable.equals("数量")) {
                    check(p.apply() && p.error().isEmpty(), "blank numeric zero can apply"); equal("0", p.value(), "blank numeric becomes zero");
                } else check(!p.apply() && p.error().contains("文本变量"), "blank zero blocks both text types");
                check(p.match() != null && p.source().contains("E3"), "empty policy preserves actual source in preview");
            }
        }
        for (int col : List.of(2, 5, 6)) {
            var binding = ENGINE.bind("数量", book.sheets().get(1), MappingProfile.Mode.FIXED, 0, 0, 0, col, MappingProfile.EmptyPolicy.ZERO);
            check(!find(ENGINE.preview(book, MappingProfile.EMPTY.put(binding), session.variables(), sheet.name(), 1, 1, Set.of(binding), CHECKPOINT), "数量").error().isEmpty(), "formula errors or missing cache never converted to zero");
        }
        var zeroCell = new SpreadsheetData.Cell("0", "0", false, false, "");
        var zeroSheet = new SpreadsheetData.Sheet("zero", 1, 1, Map.of(SpreadsheetData.key(0, 0), zeroCell), List.of());
        var zeroBook = new SpreadsheetData(book.path(), book.modified(), book.size(), List.of(zeroSheet));
        var zero = ENGINE.bind("数量", zeroSheet, MappingProfile.Mode.FIXED, 0, 0, 0, 0, MappingProfile.EmptyPolicy.KEEP);
        var p = find(ENGINE.preview(zeroBook, MappingProfile.EMPTY.put(zero), session.variables(), "zero", 0, 0, Set.of(zero), CHECKPOINT), "数量");
        check(p.apply() && p.value().equals("0"), "explicit zero is not a blank and overwrites even under KEEP");
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
                var profile = MappingProfile.EMPTY.put(ENGINE.bind("数量", data.sheets().get(0), MappingProfile.Mode.RECORD, 0, 0, 1, 1, MappingProfile.EmptyPolicy.KEEP));
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
    private static void columnUiIntegration(Path dir) throws Exception {
        Path horizontalFile = dir.resolve("horizontal.xlsx");
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("横向人员表");
            String[] titles = {"姓名", "数量", "金额", "备注"};
            for (int r = 0; r < titles.length; r++) sheet.createRow(r).createCell(0).setCellValue(titles[r]);
            sheet.getRow(0).createCell(1).setCellValue("张三"); sheet.getRow(0).createCell(2).setCellValue("李四");
            sheet.getRow(1).createCell(1).setCellValue(3); sheet.getRow(1).createCell(2).setCellValue(8);
            sheet.getRow(2).createCell(1).setCellValue(100); sheet.getRow(2).createCell(2).setCellValue(200);
            Sheet alternate = workbook.createSheet("备用人员表");
            alternate.createRow(1).createCell(1).setCellValue("姓名");
            alternate.getRow(1).createCell(2).setCellValue("张三"); alternate.getRow(1).createCell(3).setCellValue("李四");
            alternate.createRow(2).createCell(1).setCellValue("数量");
            alternate.getRow(2).createCell(2).setCellValue(30); alternate.getRow(2).createCell(3).setCellValue(80);
            alternate.createRow(3).createCell(1).setCellValue("金额");
            alternate.createRow(4).createCell(1).setCellValue("备注");
            try (var output = Files.newOutputStream(horizontalFile)) { workbook.write(output); }
        }
        SpreadsheetData horizontal = read(horizontalFile);
        AtomicReference<TemplateToolApp> owner = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            try {
                TemplateToolApp app = new TemplateToolApp(dir.resolve("horizontal-ui")); owner.set(app);
                TemplateSession session = field(app, "session", TemplateSession.class);
                String template = "{{数量}} {{备注}}";
                var rules = MappingProfile.EMPTY.put(ENGINE.bind("数量", horizontal.sheets().get(0), MappingProfile.Mode.COLUMN_RECORD, 0, 0, 1, 1, MappingProfile.EmptyPolicy.KEEP));
                TemplateConfigStore store = field(app, "templateConfigStore", TemplateConfigStore.class); store.saveMapping("横向模板.txt", rules);
                session.load("横向模板.txt", template, store.load("横向模板.txt"), TemplateParser.parse(template));
                session.setValue("数量", "500"); session.activateType("备注", VariableType.SHORT_TEXT, "待确认");
                invoke(app, "setTemplateText", new Class<?>[]{String.class}, template); invoke(app, "refreshVariablePanel", new Class<?>[]{});
                field(app, "tabs", JTabbedPane.class).setSelectedIndex(1);
                field(app, "extractionPanel", DataExtractionPanel.class).installWorkbook(horizontal);
            } catch (Exception e) { throw new RuntimeException(e); }
        });
        try {
            awaitPreview(owner.get());
            SwingUtilities.invokeAndWait(() -> {
                try {
                    var panel = field(owner.get(), "extractionPanel", DataExtractionPanel.class);
                    check(!field(panel, "recordSelector", JPanel.class).isVisible(), "global mode uses the always-visible worksheet selector");
                    check(field(panel, "globalColumn", JSpinner.class).isVisible() && field(panel, "globalRow", JSpinner.class).isVisible(), "both worksheet-wide selectors are always visible");
                    check(field(panel, "recordSelector", JPanel.class).getParent() == field(panel, "mode", JComboBox.class).getParent(), "record selector is adjacent to positioning mode");
                    check(field(panel, "target", JLabel.class).getText().contains("数量"), "selected variable is displayed as a label");
                    var preview = field(panel, "gridScroll", JScrollPane.class);
                    var rows = field(preview, "rowTitles", JTable.class); var columns = field(preview, "columnTitles", JTable.class);
                    equal("A", field(panel, "grid", JTable.class).getColumnName(0), "column letters are separate from titles");
                    equal("1", rows.getValueAt(0, 0).toString(), "row numbers displayed independently");
                    equal("数量", rows.getValueAt(1, 1).toString(), "frozen row header shows selected row-title column");
                    equal("张三", columns.getValueAt(0, 1).toString(), "frozen column header shows selected header row");
                    field(panel, "headerRow", JSpinner.class).setValue(2);
                    equal("3", columns.getValueAt(0, 1).toString(), "column title display updates when source row changes");
                    field(panel, "titleColumn", JSpinner.class).setValue(2);
                    equal("100", rows.getValueAt(2, 1).toString(), "row title display updates when source column changes");
                    field(panel, "headerRow", JSpinner.class).setValue(1); field(panel, "titleColumn", JSpinner.class).setValue(1);
                    var editor = ((JSpinner.DefaultEditor) field(panel, "titleColumn", JSpinner.class).getEditor()).getTextField();
                    editor.setText("B"); field(panel, "titleColumn", JSpinner.class).commitEdit();
                    equal("2", field(panel, "titleColumn", JSpinner.class).getValue().toString(), "title-column selector accepts letters");
                    editor.setText("A 列"); field(panel, "titleColumn", JSpinner.class).commitEdit();
                    preview.setSize(380, 105); layout(preview);
                    preview.getViewport().setViewPosition(new Point(60, 20));
                    check(preview.getColumnHeader().getViewPosition().x == preview.getViewport().getViewPosition().x,
                            "frozen column titles track horizontal scrolling");
                    check(preview.getRowHeader().getViewPosition().y == preview.getViewport().getViewPosition().y,
                            "frozen row titles track vertical scrolling");
                    check(rows.getRowHeight() == field(panel, "grid", JTable.class).getRowHeight(), "row titles align with data rows");
                    equal("3", find(panel.previews(), "数量").value(), "initial horizontal UI value");
                    JTable data = field(panel, "grid", JTable.class); data.changeSelection(1, 1, false, false);
                    check(data.getSelectedRowCount() == 1 && data.getSelectedColumnCount() == 1, "spreadsheet preview selects one cell only");
                    check(data.getRowSelectionAllowed() && data.getColumnSelectionAllowed(), "spreadsheet preview keeps both row and column selection enabled");
                    check(data.isCellSelected(1, 1) && !data.isCellSelected(1, 0) && !data.isCellSelected(1, 2), "only the row-column intersection is painted as selected");
                    check(rows.getSelectedRow() == -1, "selecting a data cell does not highlight its row header");
                    check(!columns.isCellSelected(0, 1), "column title display does not mirror the data-cell highlight");
                    field(panel, "globalColumn", JSpinner.class).setValue(3);
                } catch (Exception e) { throw new RuntimeException(e); }
            });
            awaitPreview(owner.get());
            SwingUtilities.invokeAndWait(() -> {
                try {
                    var app = owner.get(); var panel = field(app, "extractionPanel", DataExtractionPanel.class);
                    equal("8", find(panel.previews(), "数量").value(), "column selection refreshes saved mappings");
                    check(!field(panel, "draftPending", Boolean.class), "global selector changes apply immediately without creating an unsaved mapping draft");
                    equal("500", field(app, "session", TemplateSession.class).variable("数量").value(), "preview does not modify old value");
                    String explanation = field(panel, "selection", JTextArea.class).getText();
                    check(explanation.contains("C2") && explanation.contains("行标题：数量") && explanation.contains("列标题：李四")
                            && explanation.contains("横向模板.txt") && explanation.contains("500") && explanation.contains("「8」"), "replacement explanation names titles, coordinates, template, old and new values");
                    JTable table = field(panel, "mappings", JTable.class);
                    check(table.getColumnName(1).equals("定位方式") && table.getColumnName(2).equals("选定范围"), "mapping table displays mode and selection scope columns");
                    check(table.getValueAt(table.getSelectedRow(), 1).toString().contains("锁定行"), "mapping row shows current positioning mode");
                    check(table.getValueAt(table.getSelectedRow(), 2).toString().contains("全部同类映射：C 列"), "mapping row shows global selected column");
                    JComboBox<?> sheetSelector = field(panel, "sheets", JComboBox.class);
                    sheetSelector.setSelectedItem("备用人员表");
                    field(panel, "headerRow", JSpinner.class).setValue(2); field(panel, "titleColumn", JSpinner.class).setValue(2);
                    field(panel, "globalRow", JSpinner.class).setValue(3); field(panel, "globalColumn", JSpinner.class).setValue(4);
                    MappingProfile.SheetSettings alternate = panel.profile().sheetSetting("备用人员表");
                    check(alternate.headerRow() == 1 && alternate.titleColumn() == 1 && alternate.recordRow() == 2 && alternate.recordColumn() == 3,
                            "worksheet UI stores all four positions independently");
                    sheetSelector.setSelectedItem("横向人员表");
                    check(field(panel, "headerRow", JSpinner.class).getValue().equals(1)
                                    && field(panel, "titleColumn", JSpinner.class).getValue().equals(1)
                                    && field(panel, "globalColumn", JSpinner.class).getValue().equals(3),
                            "switching back restores that worksheet's structure and global positions");
                    field(panel, "grid", JTable.class).changeSelection(2, 1, false, false);
                    field(panel, "grid", JTable.class).changeSelection(1, 1, false, false); // 重新点击 B2，选定列仍为 C。
                } catch (Exception e) { throw new RuntimeException(e); }
            });
            awaitPreview(owner.get());
            SwingUtilities.invokeAndWait(() -> {
                try {
                    var panel = field(owner.get(), "extractionPanel", DataExtractionPanel.class);
                    check(field(panel, "selection", JTextArea.class).getText().contains("C2"), "draft follows selected column, not clicked cell column");
                    check(!field(panel, "apply", JButton.class).isEnabled(), "unsaved draft cannot accidentally apply old mapping");
                    invoke(panel, "bindSelected", new Class<?>[]{});
                } catch (Exception e) { throw new RuntimeException(e); }
            });
            awaitPreview(owner.get());
            SwingUtilities.invokeAndWait(() -> {
                try {
                    var app = owner.get(); var panel = field(app, "extractionPanel", DataExtractionPanel.class);
                    panel.applyPreview(); equal("8", field(app, "session", TemplateSession.class).variable("数量").value(), "horizontal application matches preview");
                    selectVariable(panel, "备注");
                    field(panel, "emptyPolicy", JComboBox.class).setSelectedItem(MappingProfile.EmptyPolicy.ZERO);
                    field(panel, "grid", JTable.class).changeSelection(3, 1, false, false);
                } catch (Exception e) { throw new RuntimeException(e); }
            });
            awaitPreview(owner.get());
            SwingUtilities.invokeAndWait(() -> {
                try {
                    var panel = field(owner.get(), "extractionPanel", DataExtractionPanel.class);
                    String explanation = field(panel, "selection", JTextArea.class).getText();
                    check(explanation.contains("C4") && explanation.contains("文本变量") && explanation.contains("待确认"), "blank text zero error shows actual source and old value");
                    invoke(panel, "bindSelected", new Class<?>[]{});
                } catch (Exception e) { throw new RuntimeException(e); }
            });
            awaitPreview(owner.get());
            SwingUtilities.invokeAndWait(() -> {
                try {
                    var panel = field(owner.get(), "extractionPanel", DataExtractionPanel.class);
                    check(!field(panel, "apply", JButton.class).isEnabled(), "saved blank text zero blocks application");
                    selectVariable(panel, "数量");
                } catch (Exception e) { throw new RuntimeException(e); }
            });
            awaitPreview(owner.get());
            SwingUtilities.invokeAndWait(() -> {
                try {
                    var panel = field(owner.get(), "extractionPanel", DataExtractionPanel.class);
                    check(field(panel, "applyCurrent", JButton.class).isEnabled(), "current-variable application remains available when another mapping has an error");
                    invoke(panel, "applyCurrentPreview", new Class<?>[]{});
                    equal("8", field(owner.get(), "session", TemplateSession.class).variable("数量").value(), "current-variable application succeeds despite another mapping error");
                    field(panel, "scope", JComboBox.class).setSelectedItem(MappingProfile.SelectionScope.LOCAL);
                    check(field(panel, "recordColumn", JSpinner.class).isVisible() && !field(panel, "recordRow", JSpinner.class).isVisible(), "local locked-row mode shows only its own column selector");
                    field(panel, "grid", JTable.class).changeSelection(1, 2, false, false);
                    equal("3", field(panel, "recordColumn", JSpinner.class).getValue().toString(), "clicking a cell updates the local locked-row column selector");
                    field(panel, "recordColumn", JSpinner.class).setValue(2);
                } catch (Exception e) { throw new RuntimeException(e); }
            });
            awaitPreview(owner.get());
            SwingUtilities.invokeAndWait(() -> {
                try {
                    var panel = field(owner.get(), "extractionPanel", DataExtractionPanel.class);
                    check(!field(panel, "applyCurrent", JButton.class).isEnabled(), "scope change must be saved before applying");
                    invoke(panel, "bindSelected", new Class<?>[]{});
                } catch (Exception e) { throw new RuntimeException(e); }
            });
            awaitPreview(owner.get());
            SwingUtilities.invokeAndWait(() -> {
                try {
                    var panel = field(owner.get(), "extractionPanel", DataExtractionPanel.class);
                    var savedBinding = panel.profile().get("数量");
                    check(savedBinding.selectionScope() == MappingProfile.SelectionScope.LOCAL && savedBinding.column() == 1, "local scope persists its own selected column");
                    equal("3", find(panel.previews(), "数量").value(), "local selected column overrides global column for one mapping");
                    selectVariable(panel, "备注");
                    field(panel, "emptyPolicy", JComboBox.class).setSelectedItem(MappingProfile.EmptyPolicy.KEEP);
                    invoke(panel, "bindSelected", new Class<?>[]{});
                } catch (Exception e) { throw new RuntimeException(e); }
            });
            awaitPreview(owner.get());
            SwingUtilities.invokeAndWait(() -> {
                try {
                    var app = owner.get(); var panel = field(app, "extractionPanel", DataExtractionPanel.class);
                    check(field(panel, "selection", JTextArea.class).getText().contains("已保留原文"), "blank text keep prompt is shown below");
                    panel.applyPreview(); equal("待确认", field(app, "session", TemplateSession.class).variable("备注").value(), "blank text keep never clears text");
                    var mode = field(panel, "mode", JComboBox.class); mode.setSelectedItem(MappingProfile.Mode.RECORD);
                    field(panel, "scope", JComboBox.class).setSelectedItem(MappingProfile.SelectionScope.LOCAL);
                    check(field(panel, "recordRow", JSpinner.class).isVisible() && !field(panel, "recordColumn", JSpinner.class).isVisible(), "vertical mode shows row selector");
                    field(panel, "grid", JTable.class).changeSelection(2, 1, false, false);
                    equal("3", field(panel, "recordRow", JSpinner.class).getValue().toString(), "clicking a cell updates the local locked-column row selector");
                    mode.setSelectedItem(MappingProfile.Mode.COLUMN_RECORD);
                    equal("3", field(panel, "recordColumn", JSpinner.class).getValue().toString(), "column selection survives mode changes");
                    mode.setSelectedItem(MappingProfile.Mode.FIXED);
                    check(!field(panel, "recordSelector", JPanel.class).isVisible(), "fixed mode hides record selector");
                    selectVariable(panel, "数量");
                } catch (Exception e) { throw new RuntimeException(e); }
            });
            awaitPreview(owner.get());
            SwingUtilities.invokeAndWait(() -> {
                try {
                    TemplateToolApp app = owner.get();
                    Rectangle usable = GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
                    check(app.getWidth() == Math.min(1440, Math.max(1, usable.width - 24)) && app.getHeight() == Math.min(1000, Math.max(1, usable.height - 24)), "window uses screen-bounded default size");
                    app.addNotify();
                    Container content = app.getContentPane(); content.setSize(app.getWidth() - 16, app.getHeight() - 56); layout(content);
                    var panel = field(app, "extractionPanel", DataExtractionPanel.class);
                    var split = field(panel, "extractionSplit", JSplitPane.class);
                    split.setDividerLocation(190); layout(content);
                    JTable variables = field(panel, "mappings", JTable.class);
                    check(((JViewport) variables.getParent()).getHeight() >= variables.getRowHeight() * 4, "default layout reserves at least four visible variable rows");
                    JTable dataGrid = field(panel, "grid", JTable.class);
                    check(dataGrid.getColumnModel().getTotalColumnWidth() <= dataGrid.getWidth(), "title display cannot stretch shared columns beyond data bounds");
                    JScrollPane sourceScroll = field(panel, "gridScroll", JScrollPane.class);
                    check(sourceScroll.getColumnHeader().getView() instanceof JPanel, "native window creation preserves the two-level column header");
                    BufferedImage image = new BufferedImage(content.getWidth(), content.getHeight(), BufferedImage.TYPE_INT_RGB);
                    Graphics2D graphics = image.createGraphics(); content.printAll(graphics); graphics.dispose();
                    String screenshot = System.getProperty("extraction.preview");
                    if (screenshot != null) javax.imageio.ImageIO.write(image, "png", Path.of(screenshot).toFile());
                    selectVariable(panel, "备注");
                    invoke(panel, "removeSelected", new Class<?>[]{});
                } catch (Exception e) { throw new RuntimeException(e); }
            });
            awaitPreview(owner.get());
            SwingUtilities.invokeAndWait(() -> {
                try {
                    var panel = field(owner.get(), "extractionPanel", DataExtractionPanel.class);
                    check(panel.profile().get("备注") == null && !find(panel.previews(), "备注").apply(), "manual mode removes selected mapping");
                    check(field(panel, "apply", JButton.class).isEnabled(), "switching to manual does not leave a draft blocking other mappings");
                } catch (Exception e) { throw new RuntimeException(e); }
            });
        } finally {
            SwingUtilities.invokeAndWait(() -> {
                try { field(owner.get(), "templateConfigSaveTimer", Timer.class).stop(); field(owner.get(), "templateSyncTimer", Timer.class).stop(); owner.get().dispose(); }
                catch (Exception e) { throw new RuntimeException(e); }
            });
        }
        AppConfig saved = new AppConfigStore(dir.resolve("horizontal-ui")).load();
        check(saved.extractionDividerLocation() == 190, "extraction divider adjustment persisted");
        String savedJson = Files.readString(dir.resolve("horizontal-ui/config.json"));
        check(!savedJson.contains("windowWidth") && !savedJson.contains("windowHeight"), "window size is not persisted");
    }
    private static void selectVariable(DataExtractionPanel panel, String variable) throws Exception {
        JTable table = field(panel, "mappings", JTable.class);
        for (int row = 0; row < table.getRowCount(); row++) if (variable.equals(table.getValueAt(row, 0))) {
            table.setRowSelectionInterval(row, row); return;
        }
        throw new AssertionError("variable absent from selection table: " + variable);
    }
    private static void layout(Container container) { container.doLayout(); for (Component child : container.getComponents()) if (child instanceof Container nested) layout(nested); }
    private static MappingEngine.Preview find(List<MappingEngine.Preview> previews, String variable) { return previews.stream().filter(p -> p.variable().equals(variable)).findFirst().orElseThrow(); }
    private static <T> T field(Object owner, String name, Class<T> type) throws Exception { Field f = owner.getClass().getDeclaredField(name); f.setAccessible(true); return type.cast(f.get(owner)); }
    private static Object invoke(Object owner, String name, Class<?>[] types, Object... args) throws Exception { Method m = owner.getClass().getDeclaredMethod(name, types); m.setAccessible(true); return m.invoke(owner, args); }
    private static void equal(String expected, String actual, String message) { check(expected.equals(actual), message + ": " + actual); }
    private static void check(boolean condition, String message) { checks++; if (!condition) throw new AssertionError(message); }
}
