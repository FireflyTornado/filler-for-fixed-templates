package com.firefly;

import com.firefly.core.AppConfig;
import com.firefly.core.AppConfigStore;
import com.firefly.core.TemplateConfig;
import com.firefly.core.TemplateConfigStore;
import com.firefly.core.DocxProcessor;
import com.firefly.core.ExpressionEvaluator;
import com.firefly.core.LegacyTemplateMigrator;
import com.firefly.core.TemplateParser;
import com.firefly.core.TemplateRenderer;
import com.firefly.core.TemplateStore;
import com.firefly.core.VariableInputState;
import com.firefly.core.VariableType;
import com.firefly.ui.FontScalePreset;
import com.firefly.ui.FileTaskProgressPanel;
import com.firefly.ui.FileTaskManager;
import com.firefly.ui.UiFontManager;

import javax.swing.UIManager;
import javax.swing.SwingUtilities;
import java.awt.Font;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** 零依赖回归测试，由根目录 build-test.bat 通过 AllTests 运行。 */
public final class TemplateFeatureTests {
    private static int tests;

    public static void main(String[] args) throws Exception {
        testAppearanceDefaultsAndValidation();
        testAppearanceRoundTrip();
        testFontScalingUsesOriginalFonts();
        testFileTaskProgressPanelKeepsFixedSize();
        testFileTaskManagerRunsOffTheUiThread();
        testPresetBoundaries();
        testSessionDrafts();
        testNestedTemplateAndConfigStorage();
        testTemplateConfigWritesOnlyCurrentValue();
        testDecimalPlacesConfigRoundTripAndFallback();
        testLegacyDraftMigration();
        testIncrementalUnusedVariableCleanup();
        testExplicitNumericVariables();
        testUnifiedDecimalFormatting();
        testPercentageExpressions();
        testAutomaticNumericAndMonthBoundaries();
        testConstantsRemainConstants();
        testLegacySyntaxIsIgnoredAndMigrated();
        testDocxLegacyMigrationAcrossRuns();
        testDocxUnifiedDecimalFormatting();
        System.out.println("All " + tests + " feature tests passed.");
    }

    private static void testAppearanceDefaultsAndValidation() throws Exception {
        Path dir = Files.createTempDirectory("template-app-config");
        AppConfigStore store = new AppConfigStore(dir);
        assertFloat(AppConfig.DEFAULT_FONT_SCALE, store.load().fontScale(), "missing config default");
        Files.writeString(dir.resolve("config.json"), "{\"appearance\":{\"fontScale\":\"large\"}}",
                StandardCharsets.UTF_8);
        assertFloat(AppConfig.DEFAULT_FONT_SCALE, store.load().fontScale(), "non-number fallback");
        Files.writeString(dir.resolve("config.json"), "{\"appearance\":{\"fontScale\":9}}",
                StandardCharsets.UTF_8);
        assertFloat(AppConfig.DEFAULT_FONT_SCALE, store.load().fontScale(), "range fallback");
    }

    private static void testAppearanceRoundTrip() throws Exception {
        Path dir = Files.createTempDirectory("template-app-scale");
        AppConfig config = new AppConfig();
        config.setFontScale(1.25f);
        AppConfigStore store = new AppConfigStore(dir);
        store.save(config);
        assertFloat(1.25f, store.load().fontScale(), "appearance round trip");
        assertTrue(Files.readString(dir.resolve("config.json")).contains("\"fontScale\": 1.25"),
                "font scale is stored as a number");
    }

    private static void testFontScalingUsesOriginalFonts() {
        UiFontManager.initialize();
        Font original = UIManager.getFont("Label.font");
        UiFontManager.applyScale(1.10f);
        UiFontManager.applyScale(1.25f);
        Font scaled = UIManager.getFont("Label.font");
        assertFloat(original.getSize2D() * 1.25f, scaled.getSize2D(), "font scale is not cumulative");
        UiFontManager.applyScale(1.0f);
    }

    private static void testFileTaskProgressPanelKeepsFixedSize() throws Exception {
        boolean[] stable = {false};
        SwingUtilities.invokeAndWait(() -> {
            FileTaskProgressPanel panel = new FileTaskProgressPanel();
            java.awt.Dimension initial = panel.getPreferredSize();
            panel.showProgress("正在加载模板", true, 35, true, "测试");
            java.awt.Dimension running = panel.getPreferredSize();
            panel.showProgress("正在保存模板", false, 0, false, "测试");
            java.awt.Dimension disabledCancel = panel.getPreferredSize();
            panel.showFinished("完成", true);
            stable[0] = initial.equals(running) && initial.equals(disabledCancel)
                    && initial.equals(panel.getPreferredSize());
        });
        assertTrue(stable[0], "file task progress area keeps fixed size");
    }

    private static void testFileTaskManagerRunsOffTheUiThread() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch completed = new CountDownLatch(1);
        boolean[] ranOnUiThread = {true};
        boolean[] lockWasVisible = {false};
        boolean[] succeeded = {false};
        SwingUtilities.invokeAndWait(() -> {
            FileTaskManager manager = new FileTaskManager(new FileTaskProgressPanel());
            manager.setTemplateLockListener(locked -> {
                if (locked) lockWasVisible[0] = true;
            });
            manager.submit("test", "测试任务", FileTaskManager.LockScope.TEMPLATE, true,
                    progress -> {
                        ranOnUiThread[0] = SwingUtilities.isEventDispatchThread();
                        started.countDown();
                        if (!release.await(5, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("test task timeout");
                        }
                        progress.update("完成", 1, 1);
                        return 7;
                    }, value -> {
                        succeeded[0] = value == 7;
                        completed.countDown();
                    }, error -> completed.countDown(), completed::countDown);
        });
        assertTrue(started.await(5, TimeUnit.SECONDS), "file task starts");
        release.countDown();
        assertTrue(completed.await(5, TimeUnit.SECONDS), "file task completes");
        assertTrue(!ranOnUiThread[0] && lockWasVisible[0] && succeeded[0],
                "file task runs in background and exposes template lock");
    }

    private static void testPresetBoundaries() {
        assertTrue(FontScalePreset.SYSTEM.smaller() == FontScalePreset.SYSTEM, "minimum preset boundary");
        assertTrue(FontScalePreset.EXTRA_LARGE.larger() == FontScalePreset.EXTRA_LARGE,
                "maximum preset boundary");
        assertTrue(FontScalePreset.COMFORTABLE.larger() == FontScalePreset.LARGE, "increase preset");
        assertTrue(FontScalePreset.LARGE.smaller() == FontScalePreset.COMFORTABLE, "decrease preset");
    }

    private static void testSessionDrafts() {
        VariableInputState state = new VariableInputState("备注", VariableType.MULTILINE_TEXT,
                "第一行\n第二行", false);
        state.activateType(VariableType.SHORT_TEXT, "第一行 第二行");
        state.setValue("摘要");
        state.activateType(VariableType.MULTILINE_TEXT, "");
        assertEquals("第一行\n第二行", state.value(), "switching restores session value");
        assertTrue(state.hasOtherTypeValues(), "other type value exists");
        state.clearOtherTypeValues();
        assertEquals("第一行\n第二行", state.value(), "clear others keeps current value");
        assertTrue(!state.hasOtherTypeValues(), "other type values cleared");
    }

    private static void testTemplateConfigWritesOnlyCurrentValue() throws Exception {
        Path dir = Files.createTempDirectory("template-config-v2");
        TemplateConfigStore store = new TemplateConfigStore(dir);
        store.ensureDirectory();
        VariableInputState state = new VariableInputState("备注", VariableType.MULTILINE_TEXT,
                "原文", false);
        state.activateType(VariableType.SHORT_TEXT, "摘要");
        store.save("demo.txt", Map.of("备注", state));
        String json = Files.readString(store.configFileForTemplate("demo.txt"));
        assertTrue(json.contains("\"version\": 4"), "template config version");
        assertTrue(json.contains("\"decimalPlaces\": 2"), "default decimal places persisted");
        assertTrue(json.contains("\"type\": \"SHORT_TEXT\"") && json.contains("\"value\": \"摘要\""),
                "active state persisted");
        assertTrue(!json.contains("drafts") && !json.contains("valuesByType") && !json.contains("原文"),
                "other session values are not persisted");
    }

    private static void testNestedTemplateAndConfigStorage() throws Exception {
        Path dir = Files.createTempDirectory("nested-template-storage");
        TemplateStore templates = new TemplateStore(dir);
        TemplateConfigStore configs = new TemplateConfigStore(dir);
        templates.writeTemplate("合同/采购/报价.txt", "金额：{{金额}}");
        templates.writeTemplate("合同/销售/报价.txt", "客户：{{客户}}");
        configs.save("合同/采购/报价.txt", Map.of("金额",
                new VariableInputState("金额", VariableType.NUMBER, "12", false)));

        assertTrue(templates.listTemplateNames().equals(java.util.List.of(
                "合同/采购/报价.txt", "合同/销售/报价.txt")),
                "nested templates are recursively listed by portable relative path");
        assertTrue(Files.isRegularFile(dir.resolve("Config/合同/采购/报价.txt.json")),
                "template config mirrors nested template folders");

        templates.renameTemplate("合同/采购/报价.txt", "合同/采购/新报价.txt");
        configs.rename("合同/采购/报价.txt", "合同/采购/新报价.txt");
        assertTrue(Files.isRegularFile(dir.resolve("Templates/合同/采购/新报价.txt"))
                        && !Files.exists(dir.resolve("Templates/合同/采购/报价.txt")),
                "nested template rename stays in its folder");
        assertEquals("12", configs.load("合同/采购/新报价.txt")
                .variables().get("金额").value(), "renamed config keeps values");
        assertTrue(Files.readString(configs.configFileForTemplate("合同/采购/新报价.txt"))
                        .contains("\"template\": \"合同/采购/新报价.txt\""),
                "renamed config updates its recorded template path");

        boolean rejected = false;
        try { templates.templateFile("../outside.txt"); }
        catch (IllegalArgumentException expected) { rejected = true; }
        assertTrue(rejected, "template traversal is rejected");
    }

    private static void testDecimalPlacesConfigRoundTripAndFallback() throws Exception {
        Path dir = Files.createTempDirectory("template-config-decimals");
        TemplateConfigStore store = new TemplateConfigStore(dir);
        store.ensureDirectory();
        store.save("demo.txt", Map.of(), 6);
        assertTrue(store.load("demo.txt").decimalPlaces() == 6, "decimal places round trip");

        Files.writeString(store.configFileForTemplate("demo.txt"),
                "{\"decimalPlaces\":99,\"variables\":{}}", StandardCharsets.UTF_8);
        assertTrue(store.load("demo.txt").decimalPlaces() == 2,
                "out-of-range decimal places use default");
        Files.writeString(store.configFileForTemplate("demo.txt"),
                "{\"variables\":{}}", StandardCharsets.UTF_8);
        assertTrue(store.load("demo.txt").decimalPlaces() == 2,
                "legacy config uses default decimal places");
    }

    private static void testLegacyDraftMigration() throws Exception {
        Path dir = Files.createTempDirectory("template-config-legacy");
        TemplateConfigStore store = new TemplateConfigStore(dir);
        store.ensureDirectory();
        String legacy = "{\"variables\":{\"备注\":{\"type\":\"MULTILINE_TEXT\","
                + "\"value\":\"旧回退\",\"drafts\":{\"MULTILINE_TEXT\":\"草稿\",\"SHORT_TEXT\":\"短\"},"
                + "\"valuesByType\":{\"MULTILINE_TEXT\":\"优先值\"}}}}";
        Files.writeString(store.configFileForTemplate("legacy.txt"), legacy, StandardCharsets.UTF_8);
        TemplateConfig loaded = store.load("legacy.txt");
        TemplateConfig.Entry entry = loaded.variables().get("备注");
        assertEquals("优先值", entry.value(), "valuesByType active value priority");
        VariableInputState state = new VariableInputState("备注", entry.type(), entry.value(),
                entry.legacySessionValues(), false);
        state.activateType(VariableType.SHORT_TEXT, "");
        assertEquals("短", state.value(), "legacy other type available in first session");
        store.save("legacy.txt", Map.of("备注", state));
        String migrated = Files.readString(store.configFileForTemplate("legacy.txt"));
        assertTrue(!migrated.contains("drafts") && !migrated.contains("valuesByType"),
                "legacy fields removed only after successful write");
    }

    private static void testIncrementalUnusedVariableCleanup() throws Exception {
        Path dir = Files.createTempDirectory("template-config-cleanup");
        TemplateConfigStore store = new TemplateConfigStore(dir);
        store.ensureDirectory();
        store.save("a.txt", Map.of(
                "保留", new VariableInputState("保留", VariableType.SHORT_TEXT, "A", false),
                "删除", new VariableInputState("删除", VariableType.NUMBER, "12", false)));
        store.save("b.docx", Map.of(
                "旧变量", new VariableInputState("旧变量", VariableType.MULTILINE_TEXT, "旧数据", false)));

        Map<String, Set<String>> checked = new LinkedHashMap<>();
        checked.put("a.txt", Set.of("保留"));
        TemplateConfigStore.CleanupReport report = store.findUnusedVariables(checked);
        assertTrue(report.templateCount() == 1 && report.variableCount() == 1,
                "only checked templates are reported");
        assertEquals("删除", report.unusedVariables().get("a.txt").get(0),
                "unused variable is reported");
        store.pruneUnusedVariables(report);
        assertTrue(store.load("a.txt").variables().keySet().equals(Set.of("保留")),
                "unused variable is removed while active value remains");
        assertTrue(store.load("b.docx").variables().containsKey("旧变量"),
                "unchecked template config remains untouched");
    }

    private static void testExplicitNumericVariables() throws Exception {
        TemplateParser.ParsedTemplate parsed = TemplateParser.parse("{{=[1]*[2]}}");
        assertTrue(parsed.expressionVariables().containsAll(java.util.Set.of("1", "2")),
                "numeric variable dependencies");
        TemplateRenderer.RenderResult result = TemplateRenderer.renderUnified(
                "{{=[1]*[2]}}", Map.of("1", "3", "2", "4"), Map.of());
        assertEquals("12.00", result.result(), "explicit numeric variable rendering");
        assertFloat(512f, (float) ExpressionEvaluator.evaluate("[1]**[2]",
                Map.of("1", "2", "2", "9")), "explicit numeric variable power");
    }

    private static void testUnifiedDecimalFormatting() {
        TemplateRenderer.RenderResult twoPlaces = TemplateRenderer.renderUnified(
                "{{数量}} / {{=数量*10}} / {{备注}}",
                Map.of("数量", "1.239", "备注", "3.456"), Map.of(),
                java.util.Set.of("数量"), 2);
        assertEquals("1.24 / 12.39 / 3.456", twoPlaces.result(),
                "numeric replacement and expression share precision without pre-rounding inputs");

        TemplateRenderer.RenderResult zeroPlaces = TemplateRenderer.renderUnified(
                "{{数量}} / {{=2.5}}", Map.of("数量", "1e3"), Map.of(),
                java.util.Set.of("数量"), 0);
        assertEquals("1000 / 3", zeroPlaces.result(),
                "zero places, scientific notation and half-up rounding");

        TemplateRenderer.RenderResult trailingZeros = TemplateRenderer.renderUnified(
                "{{数量}} / {{=1/4}}", Map.of("数量", "12"), Map.of(),
                java.util.Set.of("数量"), 4);
        assertEquals("12.0000 / 0.2500", trailingZeros.result(), "trailing zeros are preserved");
    }

    private static void testPercentageExpressions() throws Exception {
        assertFloat(0.05f, (float) ExpressionEvaluator.evaluate("5%", Map.of()),
                "percentage constant");
        assertFloat(20f, (float) ExpressionEvaluator.evaluate("金额*税率%",
                Map.of("金额", "200", "税率", "10")), "percentage variable");
        assertFloat(0.25f, (float) ExpressionEvaluator.evaluate("(20+5)%", Map.of()),
                "percentage parenthesized expression");
        assertEquals("80.00", TemplateRenderer.renderUnified(
                "{{=原价*(1-折扣率%)}}", Map.of("原价", "100", "折扣率", "20"), Map.of())
                .result(), "percentage rendering");
        assertTrue(TemplateParser.parse("{{=金额*税率%}}")
                        .expressionVariables().containsAll(Set.of("金额", "税率")),
                "percentage expression dependencies");
    }

    private static void testAutomaticNumericAndMonthBoundaries() {
        Map<String, String> leapFebruary = TemplateConstants.autoValues(LocalDate.of(2024, 2, 15));
        assertEquals("29", leapFebruary.get("本月天数"), "leap February day count");
        assertEquals("31", leapFebruary.get("上月天数"), "previous month day count");
        assertEquals("31", leapFebruary.get("下月天数"), "next month day count");
        assertEquals("1月1日", leapFebruary.get("上月月首"), "previous month first day");
        assertEquals("1月31日", leapFebruary.get("上月月末"), "previous month last day");
        assertEquals("3月1日", leapFebruary.get("下月月首"), "next month first day");
        assertEquals("3月31日", leapFebruary.get("下月月末"), "next month last day");
        assertTrue(TemplateConstants.AUTO_NUMERIC_VAR_SET.contains("本月天数")
                        && !TemplateConstants.AUTO_DATE_VAR_SET.contains("本月天数"),
                "month day count is automatic numeric, not date text");

        TemplateParser.ParsedTemplate parsed = TemplateParser.parse(
                "{{本月天数}} / {{=本月天数*2}} / {{本月月末}}");
        assertTrue(parsed.inputVariables().isEmpty()
                        && parsed.autoVariables().contains("本月天数"),
                "automatic numeric variable creates no input field");
        TemplateRenderer.RenderResult rendered = TemplateRenderer.renderUnified(
                "{{本月天数}} / {{=本月天数*2}} / {{本月月末}}",
                Map.of(), leapFebruary);
        assertTrue(!rendered.hasError(), "automatic numeric expression renders");
        assertEquals("29 / 58.00 / 2月29日", rendered.result(),
                "automatic numeric expression and month boundary output");
        assertEquals("91.00", TemplateRenderer.renderUnified(
                "{{=上月天数+本月天数+下月天数}}", Map.of(), leapFebruary).result(),
                "relative month day counts participate in one expression");

        Map<String, String> january = TemplateConstants.autoValues(LocalDate.of(2027, 1, 10));
        assertEquals("31", january.get("上月天数"), "previous year month day count");
        assertEquals("28", january.get("下月天数"), "ordinary February day count");
        assertEquals("12月1日", january.get("上月月首"), "previous year month first day");
        assertEquals("12月31日", january.get("上月月末"), "previous year month last day");
    }

    private static void testConstantsRemainConstants() {
        TemplateParser.ParsedTemplate parsed = TemplateParser.parse("{{=1*2}} {{=数量*2}}");
        assertTrue(!parsed.expressionVariables().contains("1")
                        && !parsed.expressionVariables().contains("2"),
                "numeric literals do not become variables");
        TemplateRenderer.RenderResult constants = TemplateRenderer.renderUnified(
                "{{=1*2}}", Map.of(), Map.of());
        assertEquals("2.00", constants.result(), "constant expression compatibility");
        TemplateRenderer.RenderResult scientific = TemplateRenderer.renderUnified(
                "{{=2e3/4}}", Map.of(), Map.of());
        assertEquals("500.00", scientific.result(), "scientific notation compatibility");
    }

    private static void testLegacySyntaxIsIgnoredAndMigrated() throws Exception {
        String old = "备注：[[备注]]，复核：[[备注]]";
        assertTrue(TemplateParser.parse(old).variables().isEmpty(), "legacy syntax is not parsed");
        assertEquals(old, TemplateRenderer.renderUnified(old, Map.of("备注", "内容"), Map.of()).result(),
                "legacy syntax is preserved during rendering");
        LegacyTemplateMigrator.Scan scan = LegacyTemplateMigrator.scan(old);
        assertTrue(scan.count() == 2 && scan.variableNames().equals(java.util.List.of("备注")),
                "legacy syntax scan");
        assertEquals("备注：{{备注}}，复核：{{备注}}", LegacyTemplateMigrator.migrateText(old),
                "legacy text migration");

        Path dir = Files.createTempDirectory("template-legacy-migration");
        Path file = dir.resolve("demo.txt");
        Files.writeString(file, old, StandardCharsets.UTF_8);
        LegacyTemplateMigrator.MigrationResult migrated = LegacyTemplateMigrator.migrate(file, false, old);
        assertTrue(Files.isRegularFile(migrated.backup()), "migration creates backup");
        assertTrue(com.firefly.core.TextFileWriter.readText(file).contains("{{备注}}"),
                "migration replaces original atomically");
    }

    private static void testDocxLegacyMigrationAcrossRuns() throws Exception {
        Path dir = Files.createTempDirectory("template-docx-migration");
        Path source = dir.resolve("source.docx"), migrated = dir.resolve("migrated.docx");
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
                  <w:body><w:p><w:r><w:t>[[备</w:t></w:r><w:r><w:t>注]]</w:t></w:r></w:p></w:body>
                </w:document>
                """;
        DocxProcessor.createDocx(source, xml);
        DocxProcessor.migrateLegacyPlaceholders(source, migrated);
        assertTrue(DocxProcessor.extractText(migrated).contains("{{备注}}"),
                "docx migration handles split runs");
    }

    private static void testDocxUnifiedDecimalFormatting() throws Exception {
        Path dir = Files.createTempDirectory("template-docx-decimals");
        Path source = dir.resolve("source.docx"), rendered = dir.resolve("rendered.docx");
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
                  <w:body><w:p><w:r><w:t>{{数量}} / {{=数量*2}}</w:t></w:r></w:p></w:body>
                </w:document>
                """;
        DocxProcessor.createDocx(source, xml);
        int[] progressUpdates = {0};
        TemplateRenderer.RenderResult result = DocxProcessor.renderUnified(
                source, rendered, Map.of("数量", "1.2345"), Map.of(),
                java.util.Set.of("数量"), 3,
                (phase, completed, total) -> progressUpdates[0]++);
        assertTrue(!result.hasError(), "docx decimal rendering succeeds");
        assertTrue(progressUpdates[0] > 0, "docx rendering reports progress");
        assertTrue(DocxProcessor.extractText(rendered).contains("1.235 / 2.469"),
                "docx numeric replacement and expression use unified decimal places");
    }

    private static void assertFloat(float expected, float actual, String message) {
        tests++;
        if (Math.abs(expected - actual) > 0.01f) throw new AssertionError(message + ": " + actual);
    }

    private static void assertEquals(String expected, String actual, String message) {
        tests++;
        if (!expected.equals(actual)) throw new AssertionError(message + ": " + actual);
    }

    private static void assertTrue(boolean condition, String message) {
        tests++;
        if (!condition) throw new AssertionError(message);
    }
}
