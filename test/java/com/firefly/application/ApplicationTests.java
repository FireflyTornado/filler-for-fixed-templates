package com.firefly.application;

import com.firefly.TemplateToolApp;
import com.firefly.core.*;
import com.firefly.ui.VariableInputPanel;
import com.firefly.ui.AboutDialog;
import com.firefly.ui.TemplateHelpDialog;

import javax.swing.*;
import javax.swing.Timer;
import java.awt.Component;
import java.awt.Container;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.CancellationException;

/** 会话边界、生成服务和原有界面接线的回归检查。 */
public final class ApplicationTests {
    private static int checks;
    private static final LocalDate DATE = LocalDate.of(2026, 9, 3);

    public static void main(String[] args) throws Exception {
        sessionLifecycle();
        nestedSessionVariables();
        batchUpdatesAndSnapshots();
        validationAndGenerationSnapshots();
        wordGenerationAndCleanup();
        SwingUtilities.invokeAndWait(() -> {
            try { windowIntegration(); } catch (Exception e) { throw new RuntimeException(e); }
        });
        System.out.println("All " + checks + " application checks passed.");
    }

    private static TemplateSession session(String text) {
        TemplateSession session = new TemplateSession();
        session.load("example.txt", text, new TemplateConfig("example.txt"), TemplateParser.parse(text));
        return session;
    }

    private static void sessionLifecycle() throws Exception {
        TemplateSession session = session("{{数量}} {{备注}}");
        session.setValue("数量", "2.50");
        session.activateType("备注", VariableType.MULTILINE_TEXT, "第一行\n第二行");
        session.activateType("备注", VariableType.SHORT_TEXT, "摘要");
        session.setDecimalPlaces(3);
        session.synchronizeText("{{数量}}");
        check(!session.variables().containsKey("备注"), "removed variable is hidden");
        check(session.variablesForPersistence().containsKey("备注"), "removed variable remains in session");
        session.synchronizeText("{{备注}} {{数量}}");
        equal("备注", session.variables().keySet().iterator().next(), "template order is preserved");
        equal("摘要", session.variable("备注").value(), "temporary removal preserves active draft");
        session.activateType("备注", VariableType.MULTILINE_TEXT, "ignored");
        equal("第一行\n第二行", session.variable("备注").value(), "type switch restores original draft");
        session.clearOtherTypeValues("备注");
        check(!session.variable("备注").hasDraft(VariableType.SHORT_TEXT), "other draft can be cleared");

        Path dir = Files.createTempDirectory("session-config-");
        TemplateConfigStore store = new TemplateConfigStore(dir);
        store.save(session.templateName(), session.variablesForPersistence(), session.decimalPlaces());
        TemplateConfig saved = store.load(session.templateName());
        session.setValue("数量", "99");
        session.updatePersistedConfig(saved);
        equal("99", session.variable("数量").value(), "disk baseline does not overwrite current edits");
        session.load("example.txt", "{{数量}} {{备注}}", saved, TemplateParser.parse("{{数量}} {{备注}}"));
        equal("2.50", session.variable("数量").value(), "refresh restores disk values");
        check(session.decimalPlaces() == 3, "decimal places restore from config");
        check(!session.variable("备注").hasDraft(VariableType.NUMBER), "reload does not restore session-only drafts");
        session.load("other.txt", "{{数量}}", new TemplateConfig("other.txt"), TemplateParser.parse("{{数量}}"));
        equal("", session.variable("数量").value(), "switching templates isolates values");
        check(session.decimalPlaces() == 2, "switching templates isolates decimals");

        session.setValue("数量", "7");
        TemplateConfig migrated = new TemplateConfig("other.txt");
        migrated.variables().put("旧备注", new TemplateConfig.Entry(VariableType.MULTILINE_TEXT, "迁移值"));
        String migratedText = "{{数量}} {{旧备注}}";
        session.migrate(migratedText, migrated, TemplateParser.parse(migratedText));
        equal("7", session.variable("数量").value(), "migration preserves existing input");
        equal("迁移值", session.variable("旧备注").value(), "migration loads newly recognized variable");
        session.synchronizeText("{{=数量*2}} {{旧备注}}");
        session.activateType("数量", VariableType.SHORT_TEXT, "text");
        check(session.variable("数量").type() == VariableType.NUMBER, "expression variable remains numeric");
        session.rename("renamed.txt", new TemplateConfig("renamed.txt"));
        equal("7", session.variable("数量").value(), "rename preserves working values");
    }

    private static void nestedSessionVariables() {
        TemplateConfig config = new TemplateConfig("nested.txt");
        config.variables().put("正文", new TemplateConfig.Entry(
                VariableType.MULTILINE_TEXT, "客户：{{客户}}；详情：{{详情}}；示例：\\{{忽略}}"));
        config.variables().put("客户", new TemplateConfig.Entry(VariableType.SHORT_TEXT, "萤火公司"));
        config.variables().put("详情", new TemplateConfig.Entry(VariableType.SHORT_TEXT, "数量：{{数量}}"));
        TemplateSession session = new TemplateSession();
        session.load("nested.txt", "{{正文}}", config, TemplateParser.parse("{{正文}}"));

        check(session.variables().keySet().equals(new LinkedHashSet<>(
                        List.of("正文", "客户", "详情", "数量"))),
                "nested text variables become recursively reachable in display order");
        check(session.variableViews().stream().map(TemplateSession.VariableView::depth).toList()
                        .equals(List.of(0, 1, 1, 2)),
                "nested variable views retain hierarchy depth");
        check(!session.variables().containsKey("忽略"), "escaped nested placeholder stays inactive");

        session.activateType("数量", VariableType.SHORT_TEXT, "{{正文}}");
        check(session.hasDependencyErrors()
                        && session.dependencyErrorMessages().stream().anyMatch(text -> text.contains("正文")),
                "session detects cycles introduced by nested variable values");
    }

    private static void batchUpdatesAndSnapshots() {
        TemplateSession session = session("{{数量}} {{单价}} {{备注}}");
        session.activateType("备注", VariableType.SHORT_TEXT, "原备注");
        List<TemplateSession.Change> events = new ArrayList<>();
        session.setChangeListener(events::add);
        long before = session.inputRevision();
        session.applyValues(Map.of("数量", "3", "单价", "2.5"));
        check(events.equals(List.of(TemplateSession.Change.BATCH)), "batch sends one notification");
        check(session.inputRevision() == before + 1, "batch advances revision once");
        equal("原备注", session.variable("备注").value(), "unmapped variables remain unchanged");
        Map<String, String> invalid = new LinkedHashMap<>();
        invalid.put("数量", "100");
        invalid.put("单价", "bad");
        rejects(() -> session.applyValues(invalid), "invalid batch rejected");
        equal("3", session.variable("数量").value(), "invalid batch applies no partial values");
        check(events.size() == 1 && session.inputRevision() == before + 1, "rejected batch has no side effects");
        rejects(() -> session.applyValues(Map.of("未知", "1")), "unknown variable rejected");
        rejects(() -> session.applyValues(Map.of("数量", " ")), "external blank number is not silently zero");
        invalid.clear(); invalid.put("备注", null);
        rejects(() -> session.applyValues(invalid), "missing external value rejected");
        session.applyValues(Map.of());
        check(events.size() == 1, "empty update does not change input");
        session.variables().get("数量").setValue("999");
        session.variablesForPersistence().get("单价").setValue("999");
        equal("3", session.variable("数量").value(), "view snapshot cannot mutate session");
        equal("2.5", session.variable("单价").value(), "persistence snapshot cannot mutate session");
        session.activateType("备注", VariableType.MULTILINE_TEXT, "不能转成数字");
        session.clearOtherTypeValues("备注");
        session.synchronizeText("{{数量}} {{单价}} {{=备注*2}}");
        VariableInputState locked = session.variable("备注");
        check(locked.requiresNumericAttention(), "snapshot preserves numeric conversion warning");
        check(!locked.hasDraft(VariableType.NUMBER), "snapshot does not create a missing numeric draft");
        session.setValue("数量", "-");
        equal("-", session.variable("数量").value(), "manual editing retains incomplete number");
        check(events.get(events.size() - 1) == TemplateSession.Change.VALUE, "manual edit uses same event boundary");
    }

    private static void validationAndGenerationSnapshots() throws Exception {
        TemplateSession session = session("{{数量}} / {{=数量*2}} / {{今日年月日}} / {{备注}}");
        session.activateType("备注", VariableType.MULTILINE_TEXT, "一\n二");
        equal("0", VariableValidation.validate(session.variables()).values().get("数量"), "manual blank remains zero");
        session.setValue("数量", "invalid");
        check(!VariableValidation.validate(session.variables()).valid(), "invalid number is reported");
        rejects(() -> GenerationRequest.capture(1, false, null, session, DATE), "invalid generation is blocked");
        session.setValue("数量", "1.2345");
        session.setDecimalPlaces(3);
        GenerationRequest request = GenerationRequest.capture(1, false, null, session, DATE);
        check(!request.isStale(session), "fresh request matches session");
        session.setValue("数量", "100");
        check(request.isStale(session), "input edit marks snapshot stale");
        GeneratedResult result = new GenerationService().generate(request, OperationProgress.NONE, () -> { }, p -> { });
        check(result.docxFile() == null && !result.renderResult().hasError(), "text generation needs no Word file");
        check(result.renderResult().result().contains("1.235 / 2.469"), "generation uses original values and decimals");
        check(result.renderResult().result().contains("一\n二"), "multiline content survives generation");
        check(result.renderResult().result().contains(request.autoValues().get("今日年月日")), "date uses captured baseline");
        GenerationRequest decimalRequest = GenerationRequest.capture(2, false, null, session, DATE);
        session.setDecimalPlaces(1);
        check(decimalRequest.isStale(session), "decimal edit marks snapshot stale");
        GenerationRequest dateRequest = GenerationRequest.capture(3, false, null, session, DATE);
        session.markInputChanged();
        check(dateRequest.isStale(session), "date edit marks snapshot stale");
        GenerationRequest textRequest = GenerationRequest.capture(4, false, null, session, DATE);
        session.markTemplateEdited();
        check(textRequest.isStale(session), "template edit invalidates before delayed parsing");
        GenerationRequest renameRequest = GenerationRequest.capture(5, false, null, session, DATE);
        session.rename("new.txt", new TemplateConfig("new.txt"));
        check(renameRequest.isStale(session), "rename makes old request stale");
        GenerationRequest reloadRequest = GenerationRequest.capture(6, false, null, session, DATE);
        session.load(session.templateName(), session.templateText(), new TemplateConfig(session.templateName()),
                TemplateParser.parse(session.templateText()));
        check(reloadRequest.isStale(session), "same-name reload makes old request stale");
    }

    private static void wordGenerationAndCleanup() throws Exception {
        Path dir = Files.createTempDirectory("generation-service-");
        Path source = dir.resolve("源 文档.docx");
        DocxProcessor.createDocx(source, """
                <?xml version="1.0" encoding="UTF-8"?>
                <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
                <w:body><w:p><w:r><w:rPr><w:b/></w:rPr><w:t>{{数量}} / {{=数量*2}}</w:t></w:r></w:p></w:body>
                </w:document>
                """);
        TemplateSession session = session(DocxProcessor.extractText(source));
        session.setValue("数量", "2.5");
        GenerationRequest request = GenerationRequest.capture(1, true, source, session, DATE);
        Path[] temporary = {null};
        GeneratedResult result = new GenerationService().generate(request, OperationProgress.NONE, () -> { }, p -> temporary[0] = p);
        check(Files.exists(result.docxFile()), "Word service returns generated file");
        check(DocxProcessor.extractText(result.docxFile()).contains("2.50 / 5.00"), "Word values and expression unchanged");
        try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(result.docxFile().toFile())) {
            String xml = new String(zip.getInputStream(zip.getEntry("word/document.xml")).readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            check(xml.contains("<w:b"), "Word character formatting preserved");
        }
        Files.delete(result.docxFile());
        int[] checkpoints = {0};
        try {
            new GenerationService().generate(request, OperationProgress.NONE, () -> {
                if (++checkpoints[0] == 2) throw new CancellationException();
            }, p -> temporary[0] = p);
            throw new AssertionError("cancellation should propagate");
        } catch (CancellationException expected) {
            check(!Files.exists(temporary[0]), "cancelled Word generation cleans temporary result");
        }
        GenerationRequest missing = GenerationRequest.capture(2, true, dir.resolve("missing.docx"), session, DATE);
        try {
            new GenerationService().generate(missing, OperationProgress.NONE, () -> { }, p -> temporary[0] = p);
            throw new AssertionError("missing source should fail");
        } catch (java.io.IOException expected) {
            check(!Files.exists(temporary[0]), "failed Word generation cleans temporary result");
        }
    }

    private static void windowIntegration() throws Exception {
        Path dir = Files.createTempDirectory("window-session-");
        TemplateToolApp app = new TemplateToolApp(dir);
        try {
            JButton aboutButton = field(app, "aboutBtn", JButton.class);
            JTabbedPane appTabs = field(app, "tabs", JTabbedPane.class);
            check(!SwingUtilities.isDescendingFrom(aboutButton, appTabs), "About button sits outside both workspace tabs");
            check(aboutButton.isEnabled(), "About is available before template initialization");
            aboutButton.doClick();
            AboutDialog about = field(app, "aboutDialog", AboutDialog.class);
            check(about.isVisible() && about.getOwner() == app, "About button opens an owned dialog");
            JTabbedPane pages = field(about, "pages", JTabbedPane.class);
            equal("本项目许可", pages.getTitleAt(0), "project license has its own page");
            JTextArea projectLicense = (JTextArea) ((JScrollPane) pages.getComponentAt(0)).getViewport().getView();
            check(!projectLicense.isEditable() && projectLicense.getText().contains("MIT License")
                    && projectLicense.getText().contains("FireflyTornado"), "About displays the project's copyright and MIT license");
            JTextArea thirdParty = (JTextArea) ((JScrollPane) pages.getComponentAt(1)).getViewport().getView();
            check(thirdParty.getText().contains("curvesapi") && !thirdParty.getText().contains("**"), "About shows plain-text third-party overview");
            JTextArea original = (JTextArea) ((JScrollPane) pages.getComponentAt(2)).getViewport().getView();
            check(original.getText().contains("Component: SparseBitSet-1.3.jar")
                    && original.getText().contains("Copyright (c) 2005, Graph Builder"), "About retains supplemental original licenses");
            about.setVisible(false);
            appTabs.setSelectedIndex(1);
            aboutButton.doClick();
            check(field(app, "aboutDialog", AboutDialog.class) == about && about.isVisible(), "About works from extraction and reuses its dialog");
            about.setVisible(false);
            appTabs.setSelectedIndex(0);
            TemplateHelpDialog help = new TemplateHelpDialog(app, () -> "", Map::of);
            try {
                JTabbedPane helpTabs = (JTabbedPane) help.getContentPane().getComponent(0);
                check(helpTabs.getTabCount() == 4, "Help contains only syntax, calculations, dates, and current variables");
            } finally { help.dispose(); }
            TemplateSession session = field(app, "session", TemplateSession.class);
            String text = "{{数量}} {{备注}}";
            session.load("example.txt", text, new TemplateConfig("example.txt"), TemplateParser.parse(text));
            invoke(app, "setTemplateText", new Class<?>[]{String.class}, text);
            invoke(app, "refreshVariablePanel", new Class<?>[]{});
            VariableInputPanel panel = field(app, "variablePanel", VariableInputPanel.class);
            GenerationRequest request = GenerationRequest.capture(1, false, null, session, DATE);
            GeneratedResult result = new GenerationService().generate(request, OperationProgress.NONE, () -> { }, p -> { });
            invoke(app, "acceptGeneratedResult", new Class<?>[]{GeneratedResult.class, boolean.class}, result, false);
            check(field(app, "copyBtn", JButton.class).isEnabled(), "generated result can be copied");
            session.applyValues(Map.of("数量", "3"));
            check(!field(app, "copyBtn", JButton.class).isEnabled(), "batch invalidates old result");
            check(!field(app, "saveResultBtn", JButton.class).isEnabled(), "batch disables stale export");
            JTextField number = input(panel, "变量“数量”的值");
            equal("3", number.getText(), "batch refreshes existing input field");
            Timer saveTimer = field(app, "templateConfigSaveTimer", Timer.class);
            check(saveTimer.isRunning(), "batch schedules configuration save");
            saveTimer.stop();
            for (var listener : saveTimer.getActionListeners()) listener.actionPerformed(null);
            equal("3", new TemplateConfigStore(dir).load("example.txt").variables().get("数量").value(), "batch persists through existing config flow");
            number.setText("bad");
            equal("bad", session.variable("数量").value(), "manual field edit updates session");
            check(panel.hasVisibleErrorIndicator("数量"), "manual invalid number still shows error");
            number.setText("4");
            check(!panel.hasVisibleErrorIndicator("数量"), "valid edit clears error");
            JComboBox<?> type = combo(panel, "变量“备注”的类型");
            type.setSelectedItem(VariableType.SHORT_TEXT);
            input(panel, "变量“备注”的值").setText("00123");
            type.setSelectedItem(VariableType.NUMBER);
            type.setSelectedItem(VariableType.SHORT_TEXT);
            equal("00123", input(panel, "变量“备注”的值").getText(), "UI type switching preserves text draft");
            session.setDecimalPlaces(4);
            check(panel.decimalPlaces() == 4, "decimal state and display stay synchronized");
        } finally {
            field(app, "templateConfigSaveTimer", Timer.class).stop();
            field(app, "templateSyncTimer", Timer.class).stop();
            app.dispose();
        }
    }

    private static JTextField input(Container parent, String name) { return (JTextField) component(parent, name); }
    private static JComboBox<?> combo(Container parent, String name) { return (JComboBox<?>) component(parent, name); }
    private static Component component(Container parent, String name) {
        for (Component child : parent.getComponents()) {
            if (child.getAccessibleContext() != null && name.equals(child.getAccessibleContext().getAccessibleName())) return child;
            if (child instanceof Container nested) {
                Component found = component(nested, name);
                if (found != null) return found;
            }
        }
        return null;
    }
    private static <T> T field(Object owner, String name, Class<T> type) throws Exception {
        Field field = owner.getClass().getDeclaredField(name); field.setAccessible(true);
        return type.cast(field.get(owner));
    }
    private static Object invoke(Object owner, String name, Class<?>[] types, Object... values) throws Exception {
        Method method = owner.getClass().getDeclaredMethod(name, types); method.setAccessible(true);
        return method.invoke(owner, values);
    }
    private static void rejects(Runnable action, String message) {
        try { action.run(); } catch (IllegalArgumentException expected) { checks++; return; }
        throw new AssertionError(message);
    }
    private static void equal(String expected, String actual, String message) { check(expected.equals(actual), message + ": " + actual); }
    private static void check(boolean condition, String message) { checks++; if (!condition) throw new AssertionError(message); }
}
