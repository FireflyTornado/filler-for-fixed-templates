package com.firefly;

import com.firefly.core.*;
import com.firefly.ui.DatePickerPanel;
import com.firefly.ui.ResultPanel;
import com.firefly.ui.VariableInputPanel;

import javax.swing.*;
import javax.swing.Timer;
import javax.swing.border.TitledBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.util.List;
import java.util.*;

/** 主窗口：编排左右分栏，并协调模板、统一变量状态、生成结果与配置。 */
public final class TemplateToolApp extends JFrame {
    private static final Font UI_FONT = new Font("Microsoft YaHei UI", Font.PLAIN, 12);
    private final TemplateStore templateStore;
    private final AppConfigStore appConfigStore;
    private final TemplateConfigStore templateConfigStore;
    private final boolean appConfigExisted;
    private final AppConfig appConfig;
    private final Timer templateSyncTimer;
    private final Timer templateConfigSaveTimer;

    private JLabel templateNameLabel, statusLabel;
    private JTextArea templateText;
    private DatePickerPanel datePicker;
    private VariableInputPanel variablePanel;
    private ResultPanel resultPanel;
    private JButton newBtn, saveTplBtn, generateBtn, copyBtn, saveResultBtn;
    private TitledBorder templateBorder;
    private JPanel templatePanel;
    private JSplitPane mainSplit, previewResultSplit;

    private String currentTemplateName = "", currentTemplate = "", lastDiskContent = "";
    private boolean currentTemplateSaved, docxMode, resultValid, programmaticUpdate;
    private Path currentDocxResult;
    private Map<String, VariableInputState> variableStates = new LinkedHashMap<>();
    private TemplateConfig persistedTemplateConfig = new TemplateConfig("");

    public TemplateToolApp(Path appDir) {
        super("模板填充工具");
        templateStore = new TemplateStore(appDir);
        appConfigStore = new AppConfigStore(appDir);
        templateConfigStore = new TemplateConfigStore(appDir);
        appConfigExisted = appConfigStore.exists();
        appConfig = appConfigStore.load();
        templateSyncTimer = new Timer(400, e -> synchronizeEditedTemplate());
        templateSyncTimer.setRepeats(false);
        templateConfigSaveTimer = new Timer(700, e -> saveCurrentTemplateConfig(false));
        templateConfigSaveTimer.setRepeats(false);
        setSize(1100, 800);
        setMinimumSize(new Dimension(860, 620));
        setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent e) { closeApplication(); }
        });
        buildUi();
        installChangeListeners();
        initTemplates(appDir);
        setLocationRelativeTo(null);
        SwingUtilities.invokeLater(this::restoreDividerLocations);
    }

    private void buildUi() {
        JPanel root = new JPanel(new BorderLayout(6, 6));
        root.setBorder(BorderFactory.createEmptyBorder(8, 8, 6, 8));
        setContentPane(root);
        JPanel left = buildLeftPane(), right = buildRightPane();
        left.setMinimumSize(new Dimension(300, 500));
        right.setMinimumSize(new Dimension(340, 500));
        mainSplit = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, left, right);
        mainSplit.setResizeWeight(0.58);
        mainSplit.setContinuousLayout(true);
        mainSplit.setOneTouchExpandable(true);
        root.add(mainSplit, BorderLayout.CENTER);
        statusLabel = new JLabel(" ");
        statusLabel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, Color.LIGHT_GRAY),
                BorderFactory.createEmptyBorder(4, 8, 2, 8)));
        statusLabel.setPreferredSize(new Dimension(10, 27));
        statusLabel.setMinimumSize(new Dimension(0, 27));
        root.add(statusLabel, BorderLayout.SOUTH);
    }

    private JPanel buildLeftPane() {
        JPanel left = new JPanel(new BorderLayout(5, 5));
        left.add(buildTemplateToolbar(), BorderLayout.NORTH);
        templatePanel = new JPanel(new BorderLayout());
        templateBorder = BorderFactory.createTitledBorder("模板内容（{{变量}} 为推荐格式）");
        templatePanel.setBorder(templateBorder);
        templatePanel.setMinimumSize(new Dimension(300, 170));
        templateText = new JTextArea();
        templateText.setFont(UI_FONT);
        templateText.setLineWrap(true);
        templateText.setWrapStyleWord(true);
        templatePanel.add(new JScrollPane(templateText));

        JPanel resultArea = new JPanel(new BorderLayout(4, 4));
        resultArea.setMinimumSize(new Dimension(300, 180));
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        generateBtn = new JButton("生成结果");
        copyBtn = new JButton("复制结果");
        saveResultBtn = new JButton("保存结果到文件");
        copyBtn.setEnabled(false);
        saveResultBtn.setEnabled(false);
        actions.add(generateBtn); actions.add(copyBtn); actions.add(saveResultBtn);
        resultArea.add(actions, BorderLayout.NORTH);
        resultPanel = new ResultPanel();
        resultArea.add(resultPanel);
        previewResultSplit = new JSplitPane(JSplitPane.VERTICAL_SPLIT, templatePanel, resultArea);
        previewResultSplit.setResizeWeight(0.45);
        previewResultSplit.setContinuousLayout(true);
        previewResultSplit.setOneTouchExpandable(true);
        left.add(previewResultSplit);
        generateBtn.addActionListener(e -> generate());
        copyBtn.addActionListener(e -> copyResult());
        saveResultBtn.addActionListener(e -> saveResult());
        return left;
    }

    private JPanel buildTemplateToolbar() {
        JPanel toolbar = new JPanel(new BorderLayout(4, 4));
        JPanel info = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0));
        info.add(new JLabel("模板文件："));
        templateNameLabel = new JLabel("—");
        templateNameLabel.setForeground(Color.GRAY);
        info.add(templateNameLabel);
        toolbar.add(info, BorderLayout.NORTH);
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0));
        JButton choose = new JButton("选择模板文件…"), open = new JButton("打开文件夹");
        newBtn = new JButton("新建模板"); saveTplBtn = new JButton("保存模板");
        buttons.add(choose); buttons.add(newBtn); buttons.add(saveTplBtn); buttons.add(open);
        toolbar.add(buttons, BorderLayout.SOUTH);
        choose.addActionListener(e -> chooseTemplate());
        newBtn.addActionListener(e -> newTemplate());
        saveTplBtn.addActionListener(e -> saveTemplate());
        open.addActionListener(e -> openTemplatesFolder());
        return toolbar;
    }

    private JPanel buildRightPane() {
        JPanel right = new JPanel(new BorderLayout(5, 5));
        datePicker = new DatePickerPanel();
        datePicker.setBorder(BorderFactory.createTitledBorder("日期基准"));
        right.add(datePicker, BorderLayout.NORTH);
        variablePanel = new VariableInputPanel();
        right.add(variablePanel);
        return right;
    }

    private void installChangeListeners() {
        templateText.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e) { templateEdited(); }
            public void removeUpdate(DocumentEvent e) { templateEdited(); }
            public void changedUpdate(DocumentEvent e) { templateEdited(); }
        });
        variablePanel.setChangeListener(() -> {
            if (!programmaticUpdate) {
                invalidateResult("内容已修改，请重新生成。");
                templateConfigSaveTimer.restart();
            }
        });
        datePicker.setChangeListener(() -> {
            if (!programmaticUpdate) invalidateResult("内容已修改，请重新生成。");
        });
    }

    private void initTemplates(Path appDir) {
        try {
            templateStore.ensureTemplatesExist();
            templateConfigStore.ensureDirectory();
            new LegacyConfigMigrator(appDir, templateStore, templateConfigStore)
                    .migrateIfNeeded(appConfig, appConfigExisted);
            appConfigStore.save(appConfig);
        } catch (IOException e) {
            showError("无法初始化配置或模板文件夹：\n" + e, "初始化失败");
        }
        List<String> names = templateStore.listTemplateNames();
        String load = findNameIgnoreCase(names, appConfig.lastTemplate());
        if (load == null && !names.isEmpty()) load = names.get(0);
        if (load == null) setStatus("Templates 文件夹为空，无法加载模板。");
        else loadTemplate(load);
    }

    private void restoreDividerLocations() {
        mainSplit.setDividerLocation(appConfig.mainDividerLocation());
        previewResultSplit.setDividerLocation(appConfig.previewResultDividerLocation());
    }

    private void templateEdited() {
        if (programmaticUpdate) return;
        invalidateResult("内容已修改，请重新生成。");
        updateDirtyIndicator();
        templateSyncTimer.restart();
    }

    private void synchronizeEditedTemplate() {
        String text = templateText.getText();
        if (!text.equals(currentTemplate)) {
            currentTemplate = text;
            rebuildVariableStates(TemplateParser.parse(text));
        }
    }

    private boolean syncTemplate() {
        templateSyncTimer.stop();
        String text = templateText.getText();
        if (text.equals(currentTemplate)) return false;
        List<String> old = new ArrayList<>(variableStates.keySet());
        currentTemplate = text;
        rebuildVariableStates(TemplateParser.parse(text));
        return !old.equals(new ArrayList<>(variableStates.keySet()));
    }

    private void rebuildVariableStates(TemplateParser.ParsedTemplate parsed) {
        Map<String, VariableInputState> next = new LinkedHashMap<>();
        for (TemplateParser.VariableSpec spec : parsed.variables()) {
            VariableInputState current = variableStates.get(spec.name());
            if (current != null) {
                next.put(spec.name(), current.copyFor(spec));
            } else {
                TemplateConfig.Entry saved = persistedTemplateConfig.variables().get(spec.name());
                VariableType type = saved == null || saved.type() == null
                        ? spec.defaultType() : saved.type();
                if (spec.numericLocked()) type = VariableType.NUMBER;
                next.put(spec.name(), new VariableInputState(spec.name(), type,
                        saved == null ? "" : saved.value(), spec.numericLocked(),
                        spec.legacySyntax(), spec.braceSyntax()));
            }
        }
        variableStates = next;
        programmaticUpdate = true;
        try { variablePanel.rebuild(variableStates); }
        finally { programmaticUpdate = false; }
    }

    private void loadTemplate(String name) {
        templateConfigSaveTimer.stop();
        saveCurrentTemplateConfig(false);
        boolean word = DocxProcessor.isDocxName(name);
        String text;
        try {
            text = word ? DocxProcessor.extractText(templateStore.templateFile(name))
                    : templateStore.readTemplate(name);
        } catch (IOException e) { showError("无法读取模板文件：\n" + e, "读取失败"); return; }
        currentTemplateName = name; currentTemplate = text; lastDiskContent = text;
        currentTemplateSaved = true;
        setTemplateText(text);
        setDocxMode(word);
        persistedTemplateConfig = templateConfigStore.load(name);
        variableStates = new LinkedHashMap<>();
        rebuildVariableStates(TemplateParser.parse(text));
        invalidateResult(null);
        updateDirtyIndicator();
        rememberTemplate(name);
        setStatus("已加载模板：" + name + (word ? "（Word 模板，只读预览）" : ""));
    }

    private void setDocxMode(boolean word) {
        docxMode = word;
        templateText.setEditable(!word);
        newBtn.setEnabled(true);
        saveTplBtn.setEnabled(!word);
        templateBorder.setTitle(word
                ? "Word 模板为只读预览，请使用 Word 编辑后重新打开或重新加载"
                : "模板内容（{{变量}} 为推荐格式，可直接编辑并保存）");
        templatePanel.repaint();
    }

    private void chooseTemplate() {
        File dir = templateStore.templatesDir().toFile();
        if (!dir.exists()) dir.mkdirs();
        JFileChooser chooser = new JFileChooser(dir);
        chooser.setDialogTitle("选择模板文件");
        chooser.setFileFilter(new FileNameExtensionFilter("模板文件 (*.txt; *.docx)", "txt", "docx"));
        chooser.setAcceptAllFileFilterUsed(false);
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
        File selected = chooser.getSelectedFile();
        if (!isSupportedTemplateName(selected.getName())) { showWarning("仅支持 .txt 和 .docx 模板。", "文件类型不支持"); return; }
        if (!confirmUnsaved("继续")) return;
        File target = selected;
        try {
            Path parent = selected.getParentFile().toPath().toAbsolutePath().normalize();
            if (!parent.equals(templateStore.templatesDir().toAbsolutePath().normalize())) {
                target = chooseImportTarget(selected, dir);
                if (target == null) return;
                Files.copy(selected.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) { showError("无法导入模板文件：\n" + e, "导入失败"); return; }
        loadTemplate(target.getName());
    }

    private void newTemplate() {
        if (!confirmUnsaved("继续")) return;
        String name = JOptionPane.showInputDialog(this, "请输入新模板文件名（仅支持 .txt）：", "新建模板", JOptionPane.PLAIN_MESSAGE);
        if (name == null) return;
        name = name.trim();
        if (!isValidTemplateName(name)) { showWarning("文件名不能为空，且不能包含 \\ / : * ? \" < > | 等字符，也不能以 . 开头。", "文件名不合法"); return; }
        String ext = extensionOf(name);
        if (ext.isEmpty()) name += ".txt";
        else if (!".txt".equalsIgnoreCase(ext)) { showWarning("界面内新建仅支持 .txt 模板；Word 模板请从外部导入。", "文件类型不支持"); return; }
        String existing = findNameIgnoreCase(templateStore.listTemplateNames(), name);
        if (existing != null) { loadTemplate(existing); setStatus("该模板已存在，已为你打开：" + existing); return; }
        templateConfigSaveTimer.stop();
        saveCurrentTemplateConfig(false);
        currentTemplateName = name; currentTemplate = ""; lastDiskContent = "";
        currentTemplateSaved = false; persistedTemplateConfig = templateConfigStore.load(name);
        variableStates = new LinkedHashMap<>();
        setDocxMode(false); setTemplateText(""); rebuildVariableStates(TemplateParser.parse(""));
        invalidateResult(null); updateDirtyIndicator(); rememberTemplate(name);
        setStatus("新模板 " + name + " 尚未保存。");
    }

    private void saveTemplate() { saveTemplateInternal(true); }
    private boolean saveTemplateInternal(boolean showSuccess) {
        if (docxMode) { showWarning("Word 模板为只读预览，请使用 Word 编辑后重新打开或重新加载。", "提示"); return false; }
        syncTemplate();
        if (currentTemplateName.isEmpty() || !isTxtName(currentTemplateName)) { showWarning("文本模板只能保存为 .txt 文件。", "文件类型不支持"); return false; }
        try {
            templateStore.writeTemplate(currentTemplateName, currentTemplate);
            lastDiskContent = currentTemplate; currentTemplateSaved = true;
        } catch (IOException e) { showError("无法写入模板文件：\n" + e, "保存失败"); return false; }
        updateDirtyIndicator(); rememberTemplate(currentTemplateName);
        setStatus("模板已保存到：" + templateStore.templateFile(currentTemplateName));
        if (showSuccess) JOptionPane.showMessageDialog(this, "模板已保存。", "已保存", JOptionPane.INFORMATION_MESSAGE);
        return true;
    }

    private boolean confirmUnsaved(String action) {
        if (!hasUnsavedTemplateChanges()) return true;
        Object[] options = {"保存并" + action, "不保存并" + action, "取消"};
        int choice = JOptionPane.showOptionDialog(this, "当前模板有未保存的修改。", "未保存的修改",
                JOptionPane.DEFAULT_OPTION, JOptionPane.WARNING_MESSAGE, null, options, options[0]);
        return choice == 0 ? saveTemplateInternal(false) : choice == 1;
    }

    private boolean hasUnsavedTemplateChanges() {
        return !docxMode && (!currentTemplateSaved || !templateText.getText().equals(lastDiskContent));
    }

    private void closeApplication() {
        if (!confirmUnsaved("退出")) return;
        templateConfigSaveTimer.stop(); saveCurrentTemplateConfig(true);
        appConfig.setMainDividerLocation(mainSplit.getDividerLocation());
        appConfig.setPreviewResultDividerLocation(previewResultSplit.getDividerLocation());
        saveAppConfig(true); dispose(); System.exit(0);
    }

    private void setTemplateText(String text) {
        templateSyncTimer.stop(); programmaticUpdate = true;
        try { templateText.setText(text); } finally { programmaticUpdate = false; }
    }

    private void updateDirtyIndicator() {
        boolean dirty = hasUnsavedTemplateChanges();
        templateNameLabel.setText((currentTemplateName.isEmpty() ? "—" : currentTemplateName) + (dirty ? " *" : ""));
        setTitle("模板填充工具" + (dirty ? " *" : ""));
    }

    private void rememberTemplate(String name) {
        if (name == null || name.isEmpty()) return;
        appConfig.setLastTemplate(name); saveAppConfig(false);
    }

    private void saveAppConfig(boolean notify) {
        try { appConfigStore.save(appConfig); }
        catch (IOException e) { setStatus("程序配置保存失败：" + e.getMessage()); if (notify) showWarning("程序配置保存失败：\n" + e, "配置未保存"); }
    }

    private void saveCurrentTemplateConfig(boolean notify) {
        if (currentTemplateName.isEmpty()) return;
        try {
            templateConfigStore.save(currentTemplateName, variableStates);
            persistedTemplateConfig = templateConfigStore.load(currentTemplateName);
        } catch (IOException | IllegalArgumentException e) {
            setStatus("模板变量配置保存失败：" + e.getMessage());
            if (notify) showWarning("模板变量配置保存失败：\n" + e, "配置未保存");
        }
    }

    private void generate() {
        invalidateResult(null);
        if (syncTemplate()) { setStatus("输入项已更新，请填写后再次生成。"); return; }
        Map<String, String> values = validatedReplacementValues();
        if (values == null) return;
        LocalDate date = datePicker.getSelectedDate();
        if (date == null) { showWarning("基准日期格式不正确，请输入有效日期，例如 2026-09-01。", "日期格式错误"); return; }
        TemplateParser.ParsedTemplate parsed = TemplateParser.parse(currentTemplate);
        Map<String, String> auto = TemplateConstants.autoValues(date);
        if (docxMode) { generateDocx(parsed, values, auto, date); return; }
        TemplateRenderer.RenderResult result = TemplateRenderer.renderUnified(currentTemplate, values, auto);
        if (result.hasError()) { showWarning(result.error(), "表达式计算失败"); setStatus("表达式计算失败，未生成结果。"); return; }
        resultPanel.setText(result.result()); markResultValid();
        setStatus(buildSuccessMessage(parsed, date)); saveCurrentTemplateConfig(false);
    }

    private Map<String, String> validatedReplacementValues() {
        Map<String, String> values = new LinkedHashMap<>();
        List<String> problems = new ArrayList<>();
        for (VariableInputState state : variableStates.values()) {
            if (state.type() == VariableType.NUMBER) {
                String value = ValueNormalizer.normalize(state.value());
                if (value == null) problems.add(state.name()); else values.put(state.name(), value);
            } else values.put(state.name(), state.value());
        }
        if (!problems.isEmpty()) {
            variablePanel.markInvalid(problems);
            showWarning("以下数值变量格式不正确：\n\n" + String.join("、", problems) + "\n\n内容已保留，请修改后重新生成。", "输入格式错误");
            setStatus("存在无效数值，未生成结果。"); return null;
        }
        variablePanel.markAllValid(); return values;
    }

    private void generateDocx(TemplateParser.ParsedTemplate parsed, Map<String, String> values,
                              Map<String, String> auto, LocalDate date) {
        Path temp = null;
        try {
            temp = Files.createTempFile("tt_result", ".docx"); temp.toFile().deleteOnExit();
            TemplateRenderer.RenderResult result = DocxProcessor.renderUnified(
                    templateStore.templateFile(currentTemplateName), temp, values, auto);
            if (result.hasError()) { Files.deleteIfExists(temp); showWarning(result.error(), "表达式计算失败"); return; }
            currentDocxResult = temp; resultPanel.setText(result.result()); markResultValid();
            setStatus(buildSuccessMessage(parsed, date) + " 可导出 Word 文档。"); saveCurrentTemplateConfig(false);
        } catch (IOException e) {
            if (temp != null) try { Files.deleteIfExists(temp); } catch (IOException ignored) { }
            showError("无法生成 Word 结果：\n" + e, "生成失败"); setStatus("Word 生成失败。");
        }
    }

    private static String buildSuccessMessage(TemplateParser.ParsedTemplate parsed, LocalDate date) {
        String message = "生成成功：已填入 " + parsed.variables().size() + " 个变量。";
        if (!parsed.autoVariables().isEmpty()) message += " 自动填充 " + parsed.autoVariables().size() + " 个日期变量（基准 " + date + "）。";
        if (parsed.expressionCount() > 0) message += " 计算 " + parsed.expressionCount() + " 个表达式。";
        return message;
    }

    private void invalidateResult(String reason) {
        resultValid = false; currentDocxResult = null;
        if (resultPanel != null) resultPanel.setText("");
        if (copyBtn != null) { copyBtn.setEnabled(false); saveResultBtn.setEnabled(false); }
        if (reason != null && !reason.isEmpty()) setStatus(reason);
    }

    private void markResultValid() { resultValid = true; copyBtn.setEnabled(true); saveResultBtn.setEnabled(true); }
    private boolean requireResult() {
        if (resultValid) return true;
        JOptionPane.showMessageDialog(this, "结果已失效，请重新点击“生成结果”。", "提示", JOptionPane.INFORMATION_MESSAGE);
        return false;
    }
    private void copyResult() {
        if (!requireResult()) return;
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(resultPanel.getText()), null);
        setStatus("结果已复制到剪贴板。");
    }
    private void saveResult() { if (requireResult()) { if (docxMode) saveDocxResult(); else saveTextResult(); } }

    private void saveTextResult() {
        JFileChooser chooser = resultChooser("保存结果", TemplateConstants.RESULT_FILENAME, "文本文件 (*.txt)", "txt");
        while (chooser.showSaveDialog(this) == JFileChooser.APPROVE_OPTION) {
            File file = withRequiredExtension(chooser.getSelectedFile(), ".txt");
            if (file == null || !confirmFileOverwrite(file)) continue;
            try { TextFileWriter.writeText(file.toPath(), resultPanel.getText()); }
            catch (IOException e) { showError("无法写入文件：\n" + e, "保存失败"); return; }
            setStatus("结果已保存到：" + file.getAbsolutePath()); return;
        }
    }
    private void saveDocxResult() {
        if (currentDocxResult == null) return;
        JFileChooser chooser = resultChooser("保存 Word 结果", "result.docx", "Word 文档 (*.docx)", "docx");
        while (chooser.showSaveDialog(this) == JFileChooser.APPROVE_OPTION) {
            File file = withRequiredExtension(chooser.getSelectedFile(), ".docx");
            if (file == null || !confirmFileOverwrite(file)) continue;
            try { Files.copy(currentDocxResult, file.toPath(), StandardCopyOption.REPLACE_EXISTING); }
            catch (IOException e) { showError("无法写入文件：\n" + e, "保存失败"); return; }
            setStatus("结果已保存到：" + file.getAbsolutePath()); return;
        }
    }

    private static JFileChooser resultChooser(String title, String name, String description, String ext) {
        JFileChooser chooser = new JFileChooser(Path.of("").toAbsolutePath().toFile());
        chooser.setDialogTitle(title); chooser.setSelectedFile(new File(name));
        chooser.setFileFilter(new FileNameExtensionFilter(description, ext)); chooser.setAcceptAllFileFilterUsed(false);
        return chooser;
    }
    private File withRequiredExtension(File file, String required) {
        String ext = extensionOf(file.getName());
        if (ext.isEmpty()) return new File(file.getParentFile(), file.getName() + required);
        if (!required.equalsIgnoreCase(ext)) { showWarning("文件名必须使用 " + required + " 扩展名，请修改后重试。", "扩展名不正确"); return null; }
        return file;
    }
    private boolean confirmFileOverwrite(File file) {
        return !file.exists() || JOptionPane.showConfirmDialog(this, "文件已存在，是否覆盖？\n" + file.getAbsolutePath(),
                "确认覆盖", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE) == JOptionPane.YES_OPTION;
    }
    private File chooseImportTarget(File source, File dir) {
        File target = new File(dir, source.getName());
        if (!target.exists()) return target;
        Object[] options = {"覆盖现有模板", "使用新名称导入", "取消"};
        int choice = JOptionPane.showOptionDialog(this, "Templates 文件夹中已存在同名模板：\n" + source.getName(),
                "模板已存在", JOptionPane.DEFAULT_OPTION, JOptionPane.WARNING_MESSAGE, null, options, options[1]);
        if (choice == 0) return target;
        if (choice != 1) return null;
        String ext = extensionOf(source.getName()), base = source.getName().substring(0, source.getName().length() - ext.length());
        for (int n = 2; ; n++) { File candidate = new File(dir, base + " (" + n + ")" + ext); if (!candidate.exists()) return candidate; }
    }

    private void openTemplatesFolder() {
        File dir = templateStore.templatesDir().toFile(); if (!dir.exists()) dir.mkdirs();
        try { if (!Desktop.isDesktopSupported()) throw new IOException("Desktop 不可用"); Desktop.getDesktop().open(dir); }
        catch (Exception e) {
            try {
                String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
                if (os.contains("win")) new ProcessBuilder("explorer.exe", dir.getAbsolutePath()).start();
                else if (os.contains("mac")) new ProcessBuilder("open", dir.getAbsolutePath()).start();
                else new ProcessBuilder("xdg-open", dir.getAbsolutePath()).start();
            } catch (Exception failure) { showError("无法打开模板文件夹：\n" + failure, "无法打开"); }
        }
    }

    private static boolean isValidTemplateName(String name) {
        if (name.isEmpty() || name.startsWith(".") || name.equals("..")) return false;
        for (char c : name.toCharArray()) if ("\\/:*?\"<>|".indexOf(c) >= 0) return false;
        return true;
    }
    private static boolean isSupportedTemplateName(String name) { return isTxtName(name) || DocxProcessor.isDocxName(name); }
    private static boolean isTxtName(String name) { return name != null && name.toLowerCase(Locale.ROOT).endsWith(".txt"); }
    private static String extensionOf(String name) { int dot = name.lastIndexOf('.'); return dot <= 0 ? "" : name.substring(dot); }
    private static String findNameIgnoreCase(List<String> names, String wanted) {
        if (wanted != null) for (String name : names) if (name.equalsIgnoreCase(wanted)) return name;
        return null;
    }
    private void setStatus(String text) { statusLabel.setText(text); statusLabel.setToolTipText(text); }
    private void showWarning(String text, String title) { JOptionPane.showMessageDialog(this, text, title, JOptionPane.WARNING_MESSAGE); }
    private void showError(String text, String title) { JOptionPane.showMessageDialog(this, text, title, JOptionPane.ERROR_MESSAGE); }
}
