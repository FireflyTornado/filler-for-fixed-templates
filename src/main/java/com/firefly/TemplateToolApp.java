package com.firefly;

import com.firefly.core.*;
import com.firefly.application.*;
import com.firefly.ui.DatePickerPanel;
import com.firefly.ui.ResultPanel;
import com.firefly.ui.VariableInputPanel;
import com.firefly.ui.IssueSeverity;
import com.firefly.ui.TemplateHelpDialog;
import com.firefly.ui.AboutDialog;
import com.firefly.ui.ValidationIssue;
import com.firefly.ui.ValidationIssueManager;
import com.firefly.ui.FontScalePreset;
import com.firefly.ui.UiFontManager;
import com.firefly.ui.FileTaskManager;
import com.firefly.ui.FileTaskProgressPanel;
import com.firefly.ui.TemplateBusyLayerUI;
import com.firefly.ui.DataExtractionPanel;

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
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.util.List;
import java.util.*;

/** 主窗口：编排左右分栏，并协调模板、统一变量状态、生成结果与配置。 */
public final class TemplateToolApp extends JFrame {
    private final Path appDir;
    private final TemplateStore templateStore;
    private final AppConfigStore appConfigStore;
    private final TemplateConfigStore templateConfigStore;
    private final boolean appConfigExisted;
    private final AppConfig appConfig;
    private final Timer templateSyncTimer;
    private final Timer templateConfigSaveTimer;
    private final ValidationIssueManager issueManager = new ValidationIssueManager();

    private JLabel templateNameLabel, statusLabel;
    private JTextArea templateText;
    private DatePickerPanel datePicker;
    private VariableInputPanel variablePanel;
    private ResultPanel resultPanel;
    private JButton chooseBtn, openFolderBtn, newBtn, saveTplBtn, generateBtn, copyBtn, saveResultBtn, helpBtn;
    private JButton refreshTemplateBtn, fontScaleBtn, aboutBtn;
    private TitledBorder templateBorder;
    private JPanel templatePanel;
    private JSplitPane mainSplit, previewResultSplit;
    private FileTaskProgressPanel fileTaskProgress;
    private FileTaskManager fileTasks;
    private TemplateBusyLayerUI templateBusyLayerUI;
    private JLayer<JComponent> templateBusyLayer;
    private JTabbedPane tabs;
    private DataExtractionPanel extractionPanel;

    private String lastDiskContent = "";
    private boolean currentTemplateSaved, docxMode, resultValid, currentResultDocx, programmaticUpdate;
    private Path currentDocxResult;
    private final TemplateSession session = new TemplateSession();
    private final GenerationService generationService = new GenerationService();
    /** 本次运行中已从模板文件确认过的变量集合；退出时只检查这些模板。 */
    private final Map<String, Set<String>> checkedTemplateVariables = new LinkedHashMap<>();
    private TemplateHelpDialog helpDialog;
    private AboutDialog aboutDialog;
    private String fullStatusText = " ";
    private FontScalePreset fontScalePreset;
    private boolean initializationScheduled;
    private long generationSequence;
    private Long activeGenerationId;

    private static final String TASK_INITIALIZE = "initialize";
    private static final String TASK_LOAD_TEMPLATE = "load-template";
    private static final String TASK_SAVE_TEMPLATE = "save-template";
    private static final String TASK_RENAME_TEMPLATE = "rename-template";
    private static final String TASK_MIGRATE_TEMPLATE = "migrate-template";
    private static final String TASK_GENERATE = "generate-result";
    private static final String TASK_EXPORT = "export-result";

    public TemplateToolApp(Path appDir) {
        super("模板填充工具");
        this.appDir = appDir;
        templateStore = new TemplateStore(appDir);
        appConfigStore = new AppConfigStore(appDir);
        templateConfigStore = new TemplateConfigStore(appDir);
        appConfigExisted = appConfigStore.exists();
        appConfig = appConfigStore.load();
        fontScalePreset = FontScalePreset.closest(appConfig.fontScale());
        appConfig.setFontScale(fontScalePreset.scale());
        UiFontManager.applyScale(fontScalePreset.scale());
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
        installKeyboardActions();
        setLocationRelativeTo(null);
    }

    /** 主窗口 setVisible(true) 后调用；延后一轮以便窗口先完成打开与首帧绘制。 */
    void initializeAfterShowing() {
        if (initializationScheduled) return;
        initializationScheduled = true;
        SwingUtilities.invokeLater(() -> {
            restoreDividerLocations();
            initTemplates(appDir);
        });
    }

    private void buildUi() {
        JPanel root = new JPanel(new BorderLayout(6, 6));
        root.setBorder(BorderFactory.createEmptyBorder(8, 8, 6, 8));
        setContentPane(root);
        aboutBtn = new JButton("关于");
        aboutBtn.setMargin(new Insets(2, 8, 2, 8));
        aboutBtn.setToolTipText("关于本程序、许可证及第三方依赖");
        aboutBtn.getAccessibleContext().setAccessibleName("关于程序与许可证");
        aboutBtn.addActionListener(e -> showAbout());
        JPanel left = buildLeftPane(), right = buildRightPane();
        left.setMinimumSize(new Dimension(300, 500));
        right.setMinimumSize(new Dimension(340, 500));
        mainSplit = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, left, right);
        mainSplit.setResizeWeight(0.58);
        mainSplit.setContinuousLayout(true);
        mainSplit.setOneTouchExpandable(true);
        templateBusyLayerUI = new TemplateBusyLayerUI();
        WorkspaceTabs workspaceTabs = new WorkspaceTabs();
        tabs = workspaceTabs;
        tabs.addTab("模板填充", mainSplit);
        templateBusyLayer = new JLayer<>(tabs, templateBusyLayerUI);
        JLayeredPane workspace = new JLayeredPane() {
            @Override public void doLayout() {
                Dimension buttonSize = aboutBtn.getPreferredSize();
                workspaceTabs.reserveHeaderWidth(buttonSize.width + 8);
                templateBusyLayer.setBounds(0, 0, getWidth(), getHeight());
                templateBusyLayer.doLayout();
                tabs.doLayout();
                Rectangle selectedTab = tabs.getBoundsAt(tabs.getSelectedIndex());
                int height = Math.min(buttonSize.height, selectedTab.height);
                aboutBtn.setBounds(getWidth() - buttonSize.width - 2,
                        selectedTab.y + (selectedTab.height - height) / 2, buttonSize.width, height);
            }
        };
        workspace.add(templateBusyLayer, JLayeredPane.DEFAULT_LAYER);
        workspace.add(aboutBtn, JLayeredPane.PALETTE_LAYER);
        root.add(workspace, BorderLayout.CENTER);
        JPanel statusBar = new JPanel(new BorderLayout(6, 0));
        statusBar.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, Color.LIGHT_GRAY),
                BorderFactory.createEmptyBorder(3, 8, 2, 2)));
        statusLabel = new JLabel(" ");
        statusLabel.setMinimumSize(new Dimension(0, statusLabel.getPreferredSize().height));
        statusLabel.addComponentListener(new java.awt.event.ComponentAdapter() {
            @Override public void componentResized(java.awt.event.ComponentEvent e) { refreshStatusLabel(); }
        });
        statusBar.add(statusLabel, BorderLayout.CENTER);
        JPanel statusActions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 5, 0));
        fileTaskProgress = new FileTaskProgressPanel();
        fileTasks = new FileTaskManager(fileTaskProgress);
        fileTasks.setTemplateLockListener(locked -> templateBusyLayerUI.setBusy(
                templateBusyLayer, locked, "正在处理模板，请稍候…"));
        statusActions.add(fileTaskProgress);
        fontScaleBtn = new JButton(fontScaleButtonText());
        fontScaleBtn.setMnemonic('Z');
        fontScaleBtn.setMargin(new Insets(1, 7, 1, 7));
        fontScaleBtn.setToolTipText("调节界面字号（Ctrl+加号/减号，Ctrl+0 跟随系统）");
        fontScaleBtn.getAccessibleContext().setAccessibleName("调节界面字号");
        fontScaleBtn.addActionListener(e -> showFontScaleMenu());
        statusActions.add(fontScaleBtn);
        statusBar.add(statusActions, BorderLayout.EAST);
        root.add(statusBar, BorderLayout.SOUTH);
        extractionPanel = new DataExtractionPanel(session, templateConfigStore, appConfig, fileTasks,
                () -> saveAppConfig(false), this::chooseTemplate, this::syncTemplate,
                () -> tabs.setSelectedIndex(0));
        tabs.addTab("数据提取", extractionPanel);
        tabs.addChangeListener(e -> {
            getRootPane().setDefaultButton(tabs.getSelectedIndex() == 0 ? generateBtn : null);
            if (tabs.getSelectedIndex() == 1) { syncTemplate(); extractionPanel.templateChanged(); }
        });
    }

    /** Reserve only the tab strip's trailing space while retaining the native tab appearance. */
    private static final class WorkspaceTabs extends JTabbedPane {
        private int headerReserve;
        private boolean layingOutHeader;

        WorkspaceTabs() { super(TOP, SCROLL_TAB_LAYOUT); }

        @Override public Insets getInsets() {
            Insets insets = super.getInsets();
            if (layingOutHeader) insets.right += headerReserve;
            return insets;
        }

        @Override public void doLayout() {
            // Let the native layout position tabs and overflow arrows in the available strip.
            layingOutHeader = true;
            try { super.doLayout(); }
            finally { layingOutHeader = false; }
            // Swing anchors overflow buttons to the outer edge, ignoring the right inset.
            for (Component child : getComponents()) {
                if (child instanceof JButton && indexOfComponent(child) < 0 && child.isVisible()) {
                    child.setLocation(child.getX() - headerReserve, child.getY());
                }
            }
            // Content and its painted border still occupy the entire workspace width.
            for (int i = 0; i < getTabCount(); i++) {
                Component page = getComponentAt(i);
                if (page != null) page.setSize(page.getWidth() + headerReserve, page.getHeight());
            }
        }

        void reserveHeaderWidth(int width) { headerReserve = width; }
    }

    private JPanel buildLeftPane() {
        JPanel left = new JPanel(new BorderLayout(5, 5));
        left.add(buildTemplateToolbar(), BorderLayout.NORTH);
        templatePanel = new JPanel(new BorderLayout());
        templateBorder = BorderFactory.createTitledBorder("模板");
        templatePanel.setBorder(templateBorder);
        templatePanel.setMinimumSize(new Dimension(300, 170));
        templateText = new JTextArea();
        templateText.setLineWrap(true);
        templateText.setWrapStyleWord(true);
        UiFontManager.registerReadingComponent(templateText, "TextArea.font");
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
        templateNameLabel.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        templateNameLabel.setToolTipText("点击重命名当前模板（配置文件会同步重命名）");
        templateNameLabel.getAccessibleContext().setAccessibleName("当前模板文件名，点击可重命名");
        templateNameLabel.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override public void mouseClicked(java.awt.event.MouseEvent e) {
                if (SwingUtilities.isLeftMouseButton(e)) promptRenameCurrentTemplate();
            }
        });
        info.add(templateNameLabel);
        toolbar.add(info, BorderLayout.NORTH);
        JPanel buttons = new JPanel(new GridLayout(0, 3, 5, 4));
        chooseBtn = new JButton("选择模板文件…");
        openFolderBtn = new JButton("打开文件夹");
        newBtn = new JButton("新建模板"); saveTplBtn = new JButton("保存模板");
        helpBtn = new JButton("帮助");
        refreshTemplateBtn = new JButton("刷新模板");
        refreshTemplateBtn.setMnemonic('R');
        refreshTemplateBtn.setToolTipText("重新读取当前模板文件中的内容");
        refreshTemplateBtn.getAccessibleContext().setAccessibleName("从磁盘刷新当前模板");
        helpBtn.setMnemonic('H');
        helpBtn.setToolTipText("模板变量语法和当前模板变量（F1）");
        helpBtn.getAccessibleContext().setAccessibleName("打开模板变量帮助");
        buttons.add(chooseBtn); buttons.add(newBtn); buttons.add(saveTplBtn);
        buttons.add(openFolderBtn); buttons.add(refreshTemplateBtn); buttons.add(helpBtn);
        toolbar.add(buttons, BorderLayout.SOUTH);
        chooseBtn.addActionListener(e -> chooseTemplate());
        newBtn.addActionListener(e -> newTemplate());
        saveTplBtn.addActionListener(e -> saveTemplate());
        openFolderBtn.addActionListener(e -> openTemplatesFolder());
        refreshTemplateBtn.addActionListener(e -> refreshCurrentTemplate());
        helpBtn.addActionListener(e -> showHelp());
        return toolbar;
    }

    private JPanel buildRightPane() {
        JPanel right = new JPanel(new BorderLayout(5, 5));
        datePicker = new DatePickerPanel();
        datePicker.setBorder(BorderFactory.createTitledBorder("日期基准"));
        right.add(datePicker, BorderLayout.NORTH);
        variablePanel = new VariableInputPanel(issueManager, session);
        variablePanel.setStatusListener(this::setStatus);
        variablePanel.setCommitListener(() -> saveCurrentTemplateConfig(false));
        variablePanel.setDecimalPlacesListener(session::setDecimalPlaces);
        right.add(variablePanel);
        return right;
    }

    private void installChangeListeners() {
        templateText.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e) { templateEdited(); }
            public void removeUpdate(DocumentEvent e) { templateEdited(); }
            public void changedUpdate(DocumentEvent e) { templateEdited(); }
        });
        session.setChangeListener(change -> {
            if (change == TemplateSession.Change.BATCH) variablePanel.refreshValues();
            if (change == TemplateSession.Change.DECIMAL_PLACES) {
                variablePanel.setDecimalPlaces(session.decimalPlaces());
            }
            invalidateResult(change == TemplateSession.Change.DECIMAL_PLACES
                    ? "小数位数已改为 " + session.decimalPlaces() + " 位，请重新生成。"
                    : "内容已修改，请重新生成。");
            templateConfigSaveTimer.restart();
            if (extractionPanel != null) extractionPanel.variablesChanged();
            if (change != TemplateSession.Change.DECIMAL_PLACES && helpDialog != null) {
                helpDialog.refreshIfVisible();
            }
        });
        datePicker.setChangeListener(() -> {
            if (!programmaticUpdate) {
                session.markInputChanged();
                refreshDateValidation();
                invalidateResult("内容已修改，请重新生成。");
            }
        });
        refreshDateValidation();
    }

    private void installKeyboardActions() {
        JRootPane root = getRootPane();
        root.setDefaultButton(generateBtn);
        bind(root, KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_ENTER,
                java.awt.event.KeyEvent.CTRL_DOWN_MASK), "generate", this::generate);
        bind(root, KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_S,
                java.awt.event.KeyEvent.CTRL_DOWN_MASK), "saveTemplate", () -> {
                    if (tabs.getSelectedIndex() == 0) saveTemplate(); else extractionPanel.flushMappings();
                });
        bind(root, KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_F1, 0), "help", this::showHelp);
        bind(root, KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_F4, 0),
                "nextError", variablePanel::locateNextIssue);
        bind(root, KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_EQUALS,
                java.awt.event.KeyEvent.CTRL_DOWN_MASK), "fontLarger", this::increaseFontScale);
        bind(root, KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_EQUALS,
                java.awt.event.KeyEvent.CTRL_DOWN_MASK | java.awt.event.KeyEvent.SHIFT_DOWN_MASK),
                "fontLargerShift", this::increaseFontScale);
        bind(root, KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_ADD,
                java.awt.event.KeyEvent.CTRL_DOWN_MASK), "fontLargerNumpad", this::increaseFontScale);
        bind(root, KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_PLUS,
                java.awt.event.KeyEvent.CTRL_DOWN_MASK), "fontLargerPlus", this::increaseFontScale);
        bind(root, KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_MINUS,
                java.awt.event.KeyEvent.CTRL_DOWN_MASK), "fontSmaller", this::decreaseFontScale);
        bind(root, KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_SUBTRACT,
                java.awt.event.KeyEvent.CTRL_DOWN_MASK), "fontSmallerNumpad", this::decreaseFontScale);
        bind(root, KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_0,
                java.awt.event.KeyEvent.CTRL_DOWN_MASK), "fontSystem",
                () -> setFontScalePreset(FontScalePreset.SYSTEM));
    }

    private static void bind(JRootPane root, KeyStroke key, String name, Runnable action) {
        root.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(key, name);
        root.getActionMap().put(name, new AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent e) { action.run(); }
        });
    }

    private void initTemplates(Path appDir) {
        fileTasks.submit(TASK_INITIALIZE, FileOperationText.INITIALIZE.taskName(),
                FileTaskManager.LockScope.TEMPLATE, false,
                progress -> {
                    progress.update("正在准备模板文件夹", 5, 100);
                    templateStore.ensureTemplatesExist();
                    progress.checkpoint();
                    progress.update("正在准备配置文件夹", 35, 100);
                    templateConfigStore.ensureDirectory();
                    progress.update("正在迁移旧配置", 55, 100);
                    new LegacyConfigMigrator(appDir, templateStore, templateConfigStore)
                            .migrateIfNeeded(appConfig, appConfigExisted);
                    progress.checkpoint();
                    appConfigStore.save(appConfig);
                    progress.update("正在扫描模板", 85, 100);
                    List<String> names = templateStore.listTemplateNames();
                    String load = findNameIgnoreCase(names, appConfig.lastTemplate());
                    if (load == null && !names.isEmpty()) load = names.get(0);
                    progress.update("初始化完成", 100, 100);
                    return load;
                }, load -> {
                    if (load == null) setStatus("Templates 文件夹为空，无法加载模板。");
                    else loadTemplateAsync(load, null);
                }, error -> showError("无法初始化配置或模板文件夹：\n" + error, "初始化失败"),
                () -> setStatus("初始化已取消。"));
    }

    private void restoreDividerLocations() {
        mainSplit.setDividerLocation(appConfig.mainDividerLocation());
        previewResultSplit.setDividerLocation(appConfig.previewResultDividerLocation());
    }

    private void templateEdited() {
        if (programmaticUpdate) return;
        session.markTemplateEdited();
        invalidateResult("内容已修改，请重新生成。");
        updateDirtyIndicator();
        templateSyncTimer.restart();
    }

    private void synchronizeEditedTemplate() {
        String text = templateText.getText();
        if (!text.equals(session.templateText())) {
            session.synchronizeText(text);
            refreshVariablePanel();
            if (helpDialog != null) helpDialog.refreshIfVisible();
        }
    }

    private boolean syncTemplate() {
        templateSyncTimer.stop();
        String text = templateText.getText();
        if (text.equals(session.templateText())) return false;
        List<String> old = new ArrayList<>(session.variables().keySet());
        session.synchronizeText(text);
        refreshVariablePanel();
        return !old.equals(new ArrayList<>(session.variables().keySet()));
    }

    private void refreshVariablePanel() {
        programmaticUpdate = true;
        try { variablePanel.rebuild(session.variables()); }
        finally { programmaticUpdate = false; }
        if (helpDialog != null) helpDialog.refreshIfVisible();
        if (extractionPanel != null) extractionPanel.templateChanged();
    }

    private record LoadedTemplate(String name, boolean word, String text,
                                  TemplateConfig config,
                                  TemplateParser.ParsedTemplate parsed,
                                  LegacyTemplateMigrator.Scan legacy) { }

    private void loadTemplateAsync(String name, Runnable afterSuccess) {
        if (name == null || name.isBlank()) return;
        if (!extractionPanel.flushMappings()) return;
        templateConfigSaveTimer.stop();
        if (!session.templateName().isEmpty() && !saveCurrentTemplateConfig(true)) return;
        boolean word = DocxProcessor.isDocxName(name);
        fileTasks.submit(TASK_LOAD_TEMPLATE, FileOperationText.LOAD_TEMPLATE.taskName(),
                FileTaskManager.LockScope.TEMPLATE, true,
                progress -> {
                    Path file = templateStore.templateFile(name);
                    progress.update(FileOperationText.LOAD_TEMPLATE.inProgress(), 0, word ? 0 : 100);
                    String text;
                    if (word) {
                        text = DocxProcessor.extractText(file,
                                (phase, completed, total) -> progress.update(phase,
                                        total <= 0 ? 0 : completed * 75 / total, 100));
                    } else {
                        text = templateStore.readTemplate(name);
                        progress.update(FileOperationText.LOAD_TEMPLATE.inProgress(), 65, 100);
                    }
                    progress.checkpoint();
                    TemplateConfig config = templateConfigStore.load(name);
                    progress.update("正在解析模板变量", 85, 100);
                    TemplateParser.ParsedTemplate parsed = TemplateParser.parse(text);
                    LegacyTemplateMigrator.Scan legacy = LegacyTemplateMigrator.scan(text);
                    progress.update("模板加载完成", 100, 100);
                    return new LoadedTemplate(name, word, text, config, parsed, legacy);
                }, loaded -> {
                    applyLoadedTemplate(loaded);
                    if (afterSuccess != null) afterSuccess.run();
                }, error -> {
                    showError("无法读取模板文件：\n" + error, "读取失败");
                    setStatus("模板加载失败，已保留原模板。");
                }, () -> setStatus("模板加载已取消，已保留原模板。"));
    }

    private void applyLoadedTemplate(LoadedTemplate loaded) {
        session.load(loaded.name(), loaded.text(), loaded.config(), loaded.parsed());
        lastDiskContent = loaded.text();
        issueManager.clear();
        currentTemplateSaved = true;
        setTemplateText(loaded.text());
        setDocxMode(loaded.word());
        setDecimalPlacesFromConfig();
        refreshVariablePanel();
        rememberCheckedVariables(loaded.name(), loaded.parsed());
        invalidateResult(null);
        updateDirtyIndicator();
        rememberTemplate(loaded.name());
        setStatus("已加载模板：" + loaded.name()
                + (loaded.word() ? "（Word 模板，只读预览）" : ""));

        // 先让模板名称、正文和变量面板完成一次绘制，再询问是否迁移当前模板。
        if (loaded.legacy().found()) {
            String displayedText = loaded.text();
            SwingUtilities.invokeLater(() -> {
                if (!loaded.name().equals(session.templateName())
                        || !displayedText.equals(session.templateText())
                        || !displayedText.equals(lastDiskContent)) return;
                templateNameLabel.paintImmediately(templateNameLabel.getVisibleRect());
                templateText.paintImmediately(templateText.getVisibleRect());
                promptLegacyTemplateMigration(loaded.name(), loaded.word(), displayedText,
                        loaded.legacy());
            });
        }
    }

    private void promptLegacyTemplateMigration(String name, boolean word, String originalText,
                                               LegacyTemplateMigrator.Scan legacy) {
        Object[] options = {"一键替换", "取消"};
        String names = String.join("、", legacy.variableNames());
        String message = "当前显示的模板“" + name + "”包含 " + legacy.count()
                + " 处已弃用的 [[变量]] 写法。\n"
                + "是否将它们转换为 {{变量}}？\n\n"
                + "变量：" + names + "\n\n替换前会自动备份原模板。";
        int choice = JOptionPane.showOptionDialog(this, message, "转换当前模板的旧语法",
                JOptionPane.DEFAULT_OPTION, JOptionPane.WARNING_MESSAGE,
                null, options, options[0]);
        if (choice != 0) {
            setStatus("模板“" + name + "”仍包含已弃用的 [[变量]] 写法，本次未转换。");
            return;
        }

        fileTasks.submit(TASK_MIGRATE_TEMPLATE, FileOperationText.CONVERT_TEMPLATE.taskName(),
                FileTaskManager.LockScope.TEMPLATE, false,
                progress -> {
                    LegacyTemplateMigrator.MigrationResult migrated = LegacyTemplateMigrator.migrate(
                            templateStore.templateFile(name), word, originalText,
                            (phase, completed, total) -> progress.update(phase, completed, total));
                    progress.checkpoint();
                    String migratedText = word
                            ? DocxProcessor.extractText(templateStore.templateFile(name),
                                (phase, completed, total) -> progress.update(
                                        FileOperationText.LOAD_TEMPLATE.inProgress(),
                                        80 + (total <= 0 ? 0 : completed * 15 / total), 100))
                            : templateStore.readTemplate(name);
                    preserveMigratedVariableTypes(name, originalText, migrated.scan().variableNames());
                    TemplateConfig config = templateConfigStore.load(name);
                    TemplateParser.ParsedTemplate parsed = TemplateParser.parse(migratedText);
                    return new MigratedTemplate(migrated, migratedText, config, parsed);
                }, result -> applyMigratedTemplate(name, result), error -> {
                    showError("旧模板语法转换失败，原模板未被替换：\n" + error, "转换失败");
                    setStatus("模板“" + name + "”的旧语法转换失败。");
                }, () -> setStatus("模板语法转换已取消。"));
    }

    private record MigratedTemplate(LegacyTemplateMigrator.MigrationResult migration,
                                    String text, TemplateConfig config,
                                    TemplateParser.ParsedTemplate parsed) { }

    private void applyMigratedTemplate(String name, MigratedTemplate result) {
        if (!name.equals(session.templateName())) return;
        session.migrate(result.text(), result.config(), result.parsed());
        lastDiskContent = result.text();
        currentTemplateSaved = true;
        issueManager.clear();
        setTemplateText(result.text());
        setDecimalPlacesFromConfig();
        refreshVariablePanel();
        rememberCheckedVariables(name, result.parsed());
        invalidateResult(null);
        updateDirtyIndicator();
        setStatus("已转换 " + result.migration().scan().count() + " 处旧写法；备份："
                + result.migration().backup().getFileName());
    }

    /** 仅为过去只使用 [[变量]] 且没有保存类型的变量保留多行文本默认值。 */
    private void preserveMigratedVariableTypes(String templateName, String originalText,
                                                List<String> migratedNames) throws IOException {
        Set<String> existingNewVariables = new HashSet<>();
        for (TemplateParser.VariableSpec spec : TemplateParser.parse(originalText).variables()) {
            existingNewVariables.add(spec.name());
        }
        TemplateConfig config = templateConfigStore.load(templateName);
        boolean changed = false;
        for (String name : migratedNames) {
            if (!existingNewVariables.contains(name) && !config.variables().containsKey(name)) {
                config.variables().put(name, new TemplateConfig.Entry(VariableType.MULTILINE_TEXT, ""));
                changed = true;
            }
        }
        if (changed) templateConfigStore.saveConfig(config);
    }

    private void setDocxMode(boolean word) {
        docxMode = word;
        templateText.setEditable(!word);
        newBtn.setEnabled(true);
        saveTplBtn.setEnabled(!word);
        templateBorder.setTitle(word ? "模板（Word 只读）" : "模板");
        templatePanel.repaint();
        if (word) setStatus("Word 模板为只读预览。",
                "Word 模板为只读预览，请使用 Word 编辑后重新打开或重新加载。");
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
        Path selectedPath = selected.toPath().toAbsolutePath().normalize();
        Path templatesRoot = templateStore.templatesDir().toAbsolutePath().normalize();
        File target = selected;
        if (!selectedPath.startsWith(templatesRoot)) {
            target = chooseImportTarget(selected, dir);
            if (target == null) return;
        }
        File finalTarget = target;
        confirmUnsavedThen("继续", () -> {
            if (!confirmCancelGeneration("切换模板")) return;
            Path finalPath = finalTarget.toPath().toAbsolutePath().normalize();
            if (selectedPath.equals(finalPath)) {
                loadTemplateAsync(templateStore.templateName(finalPath), null);
            } else {
                importTemplateAsync(selected.toPath(), finalTarget.toPath());
            }
        });
    }

    private void importTemplateAsync(Path source, Path target) {
        fileTasks.submit("import-template", FileOperationText.IMPORT_TEMPLATE.taskName(),
                FileTaskManager.LockScope.TEMPLATE, true,
                progress -> {
                    copyWithProgress(source, target, progress,
                            FileOperationText.IMPORT_TEMPLATE.inProgress());
                    return templateStore.templateName(target);
                }, name -> loadTemplateAsync(name, null), error ->
                        showError("无法导入模板文件：\n" + error, "导入失败"),
                () -> setStatus("模板导入已取消。"));
    }

    private void newTemplate() {
        confirmUnsavedThen("继续", this::promptNewTemplateName);
    }

    private void promptRenameCurrentTemplate() {
        if (!extractionPanel.flushMappings()) return;
        if (session.templateName().isEmpty()) return;
        String oldName = session.templateName();
        String oldFileName = templateStore.templateFile(oldName).getFileName().toString();
        String oldExtension = extensionOf(oldFileName);
        String oldBaseName = oldFileName.substring(0, oldFileName.length() - oldExtension.length());
        String entered = (String) JOptionPane.showInputDialog(this,
                "请输入新的模板名称（无需输入扩展名）：\n"
                        + "文件仍保留在当前子文件夹中，扩展名 " + oldExtension + " 会自动沿用。",
                "重命名模板", JOptionPane.PLAIN_MESSAGE, null, null, oldBaseName);
        if (entered == null) return;
        String newBaseName = entered.trim();
        // 输入框只编辑主文件名；即使用户习惯性输入支持的扩展名，也会先移除再沿用原扩展名。
        if (isSupportedTemplateName(newBaseName)) {
            String enteredExtension = extensionOf(newBaseName);
            newBaseName = newBaseName.substring(0,
                    newBaseName.length() - enteredExtension.length()).trim();
        }
        if (!isValidTemplateName(newBaseName)) {
            showWarning("文件名不能为空，且不能包含 \\ / : * ? \" < > | 等字符，也不能以 . 开头。",
                    "文件名不合法");
            return;
        }
        String newFileName = newBaseName + oldExtension;
        int slash = oldName.lastIndexOf('/');
        String newName = slash < 0 ? newFileName : oldName.substring(0, slash + 1) + newFileName;
        if (newName.equals(oldName)) return;
        String existing = findNameIgnoreCase(templateStore.listTemplateNames(), newName);
        if (existing != null && !existing.equals(oldName)) {
            showWarning("当前文件夹中已存在同名模板：\n" + existing, "无法重命名");
            return;
        }
        if (templateConfigStore.exists(newName) && !newName.equalsIgnoreCase(oldName)) {
            showWarning("对应的新配置文件已存在，未执行重命名。", "无法重命名");
            return;
        }
        if (!confirmCancelGeneration("重命名模板") || !saveCurrentTemplateConfig(true)) return;
        renameCurrentTemplateAsync(oldName, newName);
    }

    private record RenameTemplateResult(String oldName, String newName) { }

    private void renameCurrentTemplateAsync(String oldName, String newName) {
        fileTasks.submit(TASK_RENAME_TEMPLATE, "重命名模板",
                FileTaskManager.LockScope.TEMPLATE, false,
                progress -> {
                    progress.update("正在重命名模板", 20, 100);
                    boolean templateMoved = Files.isRegularFile(templateStore.templateFile(oldName));
                    if (templateMoved) templateStore.renameTemplate(oldName, newName);
                    try {
                        progress.update("正在同步重命名配置", 70, 100);
                        templateConfigStore.rename(oldName, newName);
                    } catch (IOException | RuntimeException configError) {
                        if (templateMoved) {
                            try { templateStore.renameTemplate(newName, oldName); }
                            catch (Exception rollbackError) { configError.addSuppressed(rollbackError); }
                        }
                        throw configError;
                    }
                    progress.update("重命名完成", 100, 100);
                    return new RenameTemplateResult(oldName, newName);
                }, renamed -> {
                    if (!renamed.oldName().equals(session.templateName())) return;
                    Set<String> checked = checkedTemplateVariables.remove(renamed.oldName());
                    if (checked != null) checkedTemplateVariables.put(renamed.newName(), checked);
                    session.rename(renamed.newName(), templateConfigStore.load(renamed.newName()));
                    extractionPanel.templateChanged();
                    rememberTemplate(renamed.newName());
                    updateDirtyIndicator();
                    setStatus("模板已重命名为：" + renamed.newName());
                }, error -> showError("无法重命名模板及其配置文件：\n" + error, "重命名失败"),
                () -> setStatus("模板重命名已取消。"));
    }

    private void promptNewTemplateName() {
        if (!extractionPanel.flushMappings()) return;
        if (!confirmCancelGeneration("新建模板")) return;
        String name = JOptionPane.showInputDialog(this, "请输入新模板文件名（仅支持 .txt）：", "新建模板", JOptionPane.PLAIN_MESSAGE);
        if (name == null) return;
        name = name.trim();
        if (!isValidTemplateName(name)) { showWarning("文件名不能为空，且不能包含 \\ / : * ? \" < > | 等字符，也不能以 . 开头。", "文件名不合法"); return; }
        String ext = extensionOf(name);
        if (ext.isEmpty()) name += ".txt";
        else if (!".txt".equalsIgnoreCase(ext)) { showWarning("界面内新建仅支持 .txt 模板；Word 模板请从外部导入。", "文件类型不支持"); return; }
        String existing = findNameIgnoreCase(templateStore.listTemplateNames(), name);
        if (existing != null) {
            loadTemplateAsync(existing, () -> setStatus("该模板已存在，已为你打开：" + existing));
            return;
        }
        templateConfigSaveTimer.stop();
        if (!saveCurrentTemplateConfig(true)) return;
        session.load(name, "", templateConfigStore.load(name), TemplateParser.parse(""));
        lastDiskContent = "";
        issueManager.clear();
        currentTemplateSaved = false;
        setDecimalPlacesFromConfig();
        setDocxMode(false); setTemplateText(""); refreshVariablePanel();
        checkedTemplateVariables.put(name, Set.of());
        invalidateResult(null);
        updateDirtyIndicator(); rememberTemplate(name);
        setStatus("新模板 " + name + " 尚未保存。");
    }

    private void saveTemplate() { saveTemplateAsync(true, null); }

    private record SaveTemplateRequest(String name, String text,
                                       TemplateParser.ParsedTemplate parsed) { }

    private void saveTemplateAsync(boolean showSuccess, Runnable afterSuccess) {
        if (docxMode) {
            showWarning("Word 模板为只读预览，请使用 Word 编辑后重新打开或重新加载。", "提示");
            return;
        }
        syncTemplate();
        if (session.templateName().isEmpty() || !isTxtName(session.templateName())) {
            showWarning("文本模板只能保存为 .txt 文件。", "文件类型不支持");
            return;
        }
        SaveTemplateRequest request = new SaveTemplateRequest(session.templateName(),
                session.templateText(), TemplateParser.parse(session.templateText()));
        fileTasks.submit(TASK_SAVE_TEMPLATE, FileOperationText.SAVE_TEMPLATE.taskName(),
                FileTaskManager.LockScope.TEMPLATE, false,
                progress -> {
                    progress.update(FileOperationText.SAVE_TEMPLATE.inProgress(), 20, 100);
                    templateStore.writeTemplate(request.name(), request.text());
                    progress.update(FileOperationText.SAVE_TEMPLATE.inProgress(), 100, 100);
                    return request;
                }, saved -> {
                    if (saved.name().equals(session.templateName())) {
                        lastDiskContent = saved.text();
                        currentTemplateSaved = templateText.getText().equals(saved.text());
                        rememberCheckedVariables(saved.name(), saved.parsed());
                        updateDirtyIndicator();
                        rememberTemplate(saved.name());
                    }
                    setStatus("模板已保存。", "模板已保存到：" + templateStore.templateFile(saved.name()));
                    if (showSuccess) JOptionPane.showMessageDialog(this,
                            "模板已保存。", "已保存", JOptionPane.INFORMATION_MESSAGE);
                    if (afterSuccess != null) afterSuccess.run();
                }, error -> showError("无法写入模板文件：\n" + error, "保存失败"),
                () -> setStatus("模板保存已取消。"));
    }
    private boolean saveTemplateInternal(boolean showSuccess) {
        if (docxMode) { showWarning("Word 模板为只读预览，请使用 Word 编辑后重新打开或重新加载。", "提示"); return false; }
        syncTemplate();
        if (session.templateName().isEmpty() || !isTxtName(session.templateName())) { showWarning("文本模板只能保存为 .txt 文件。", "文件类型不支持"); return false; }
        try {
            templateStore.writeTemplate(session.templateName(), session.templateText());
            lastDiskContent = session.templateText(); currentTemplateSaved = true;
            rememberCheckedVariables(session.templateName(), TemplateParser.parse(session.templateText()));
        } catch (IOException e) { showError("无法写入模板文件：\n" + e, "保存失败"); return false; }
        updateDirtyIndicator(); rememberTemplate(session.templateName());
        setStatus("模板已保存。", "模板已保存到：" + templateStore.templateFile(session.templateName()));
        if (showSuccess) JOptionPane.showMessageDialog(this, "模板已保存。", "已保存", JOptionPane.INFORMATION_MESSAGE);
        return true;
    }

    private void confirmUnsavedThen(String action, Runnable continuation) {
        if (!hasUnsavedTemplateChanges()) {
            continuation.run();
            return;
        }
        Object[] options = {"保存并" + action, "不保存并" + action, "取消"};
        int choice = JOptionPane.showOptionDialog(this, "当前模板有未保存的修改。", "未保存的修改",
                JOptionPane.DEFAULT_OPTION, JOptionPane.WARNING_MESSAGE, null, options, options[0]);
        if (choice == 0) saveTemplateAsync(false, continuation);
        else if (choice == 1) continuation.run();
    }

    private boolean hasUnsavedTemplateChanges() {
        return !docxMode && (!currentTemplateSaved || !templateText.getText().equals(lastDiskContent));
    }

    private void closeApplication() {
        if (!extractionPanel.flushMappings()) return;
        if (fileTasks.hasActiveTasks()) {
            if (fileTasks.hasNonCancellableTasks()) {
                showWarning("当前文件任务正在完成不可中断的写入，请等待进度条完成后再退出。",
                        "文件任务正在完成");
                return;
            }
            int taskChoice = JOptionPane.showConfirmDialog(this,
                    "当前仍有文件任务正在执行。\n\n是否取消这些任务并继续退出？",
                    "文件任务尚未完成", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
            if (taskChoice != JOptionPane.YES_OPTION) return;
            fileTasks.cancelAll();
            setStatus("正在取消文件任务；任务结束后请再次关闭程序。");
            return;
        }
        templateSyncTimer.stop();
        syncTemplate();
        templateConfigSaveTimer.stop();
        if (hasUnsavedTemplateChanges()) {
            int choice = showUnsavedExitDialog();
            if (choice == 2 || choice == JOptionPane.CLOSED_OPTION) {
                templateConfigSaveTimer.restart();
                return;
            }
            if (choice == 0) {
                if (!saveTemplateInternal(false) || !saveCurrentTemplateConfig(true)) return;
                if (!cleanCheckedTemplateConfigs()) return;
            } else {
                if (!saveCurrentTemplateConfig(true)) return;
            }
            finishExit();
            return;
        }

        if (!saveCurrentTemplateConfig(true)) return;
        TemplateConfigStore.CleanupReport report =
                templateConfigStore.findUnusedVariables(checkedTemplateVariables);
        if (!report.isEmpty()) {
            int choice = showCleanupExitDialog(report);
            if (choice == 2 || choice == JOptionPane.CLOSED_OPTION) return;
            if (choice == 0 && !pruneTemplateConfigs(report)) return;
        }
        finishExit();
    }

    private int showUnsavedExitDialog() {
        TemplateConfigStore.CleanupReport estimated = templateConfigStore.findUnusedVariables(
                checkedVariablesIncludingCurrentText());
        String count = estimated.isEmpty() ? "保存后将再次核对已检查模板的变量配置。"
                : "当前发现 " + estimated.templateCount() + " 个模板包含 "
                + estimated.variableCount() + " 个未使用变量。";
        Object[] options = {"保存并退出（清理未使用变量）", "不保存并退出", "取消"};
        return JOptionPane.showOptionDialog(this,
                "当前模板有未保存的修改。\n\n"
                        + "点击“保存并退出”后，将清理本次运行中已检查模板内所有未使用变量及其保存数据。\n"
                        + count + "\n\n“不保存并退出”不会清理变量配置。",
                "保存并退出", JOptionPane.DEFAULT_OPTION, JOptionPane.WARNING_MESSAGE,
                null, options, options[0]);
    }

    private int showCleanupExitDialog(TemplateConfigStore.CleanupReport report) {
        JTextArea details = new JTextArea(cleanupDetails(report), 8, 42);
        details.setEditable(false);
        details.setLineWrap(true);
        details.setWrapStyleWord(true);
        details.setCaretPosition(0);
        JPanel panel = new JPanel(new BorderLayout(0, 8));
        panel.add(new JLabel("本次运行中已检查的模板存在未使用变量，是否清理后退出？"), BorderLayout.NORTH);
        panel.add(new JScrollPane(details), BorderLayout.CENTER);
        Object[] options = {"清理并退出", "直接退出", "取消"};
        return JOptionPane.showOptionDialog(this, panel, "清理未使用变量",
                JOptionPane.DEFAULT_OPTION, JOptionPane.WARNING_MESSAGE,
                null, options, options[0]);
    }

    private String cleanupDetails(TemplateConfigStore.CleanupReport report) {
        StringBuilder text = new StringBuilder("将删除 ")
                .append(report.templateCount()).append(" 个模板中的 ")
                .append(report.variableCount()).append(" 个未使用变量及其保存数据：\n\n");
        report.unusedVariables().forEach((template, names) -> text.append(template)
                .append("：").append(String.join("、", names)).append('\n'));
        return text.toString();
    }

    private Map<String, Set<String>> checkedVariablesIncludingCurrentText() {
        Map<String, Set<String>> checked = new LinkedHashMap<>(checkedTemplateVariables);
        if (!session.templateName().isEmpty()) {
            checked.put(session.templateName(),
                    activeVariableNames(TemplateParser.parse(templateText.getText())));
        }
        return checked;
    }

    private boolean cleanCheckedTemplateConfigs() {
        return pruneTemplateConfigs(templateConfigStore.findUnusedVariables(checkedTemplateVariables));
    }

    private boolean pruneTemplateConfigs(TemplateConfigStore.CleanupReport report) {
        if (report.isEmpty()) return true;
        try {
            templateConfigStore.pruneUnusedVariables(report);
            if (!session.templateName().isEmpty()) {
                session.updatePersistedConfig(templateConfigStore.load(session.templateName()));
            }
            return true;
        } catch (IOException | IllegalArgumentException e) {
            showWarning("未使用变量清理失败，程序将保持打开：\n" + e, "清理失败");
            setStatus("未使用变量清理失败，尚未退出。");
            return false;
        }
    }

    private void finishExit() {
        appConfig.setMainDividerLocation(mainSplit.getDividerLocation());
        appConfig.setPreviewResultDividerLocation(previewResultSplit.getDividerLocation());
        saveAppConfig(true);
        deleteQuietly(currentDocxResult);
        dispose(); System.exit(0);
    }

    private void rememberCheckedVariables(String templateName, TemplateParser.ParsedTemplate parsed) {
        if (templateName == null || templateName.isEmpty()) return;
        checkedTemplateVariables.put(templateName, activeVariableNames(parsed));
    }

    private static Set<String> activeVariableNames(TemplateParser.ParsedTemplate parsed) {
        Set<String> names = new LinkedHashSet<>();
        for (TemplateParser.VariableSpec variable : parsed.variables()) names.add(variable.name());
        return Set.copyOf(names);
    }

    private void setTemplateText(String text) {
        templateSyncTimer.stop(); programmaticUpdate = true;
        try { templateText.setText(text); } finally { programmaticUpdate = false; }
    }

    private void updateDirtyIndicator() {
        boolean dirty = hasUnsavedTemplateChanges();
        templateNameLabel.setText((session.templateName().isEmpty() ? "—" : session.templateName()) + (dirty ? " *" : ""));
        templateNameLabel.setEnabled(!session.templateName().isEmpty());
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

    private boolean saveCurrentTemplateConfig(boolean notify) {
        if (session.templateName().isEmpty()) return true;
        try {
            templateConfigStore.save(session.templateName(), session.variablesForPersistence(),
                    session.decimalPlaces());
            session.updatePersistedConfig(templateConfigStore.load(session.templateName()));
            return true;
        } catch (IOException | IllegalArgumentException e) {
            setStatus("模板变量配置保存失败：" + e.getMessage());
            if (notify) showWarning("模板变量配置保存失败：\n" + e, "配置未保存");
            return false;
        }
    }

    private void generate() {
        if (tabs != null && tabs.getSelectedIndex() != 0) return;
        if (fileTasks.hasTask(TASK_GENERATE)) {
            int choice = JOptionPane.showConfirmDialog(this,
                    "已有结果正在生成。是否取消当前任务，并使用现在的内容重新生成？",
                    "重新生成", JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE);
            if (choice != JOptionPane.YES_OPTION) return;
            fileTasks.cancelKind(TASK_GENERATE);
        }
        if (syncTemplate()) { setStatus("输入项已更新，请填写后再次生成。"); return; }
        refreshAllValidation();
        Map<String, String> values = validatedReplacementValues();
        if (values == null) return;
        LocalDate date = datePicker.getSelectedDate();
        if (date == null) { refreshDateValidation(); showWarning("基准日期格式不正确，请输入有效日期，例如 2026-09-01。", "日期格式错误"); return; }
        long sequence = ++generationSequence;
        activeGenerationId = sequence;
        GenerationRequest request = GenerationRequest.capture(sequence, docxMode,
                docxMode ? templateStore.templateFile(session.templateName()) : null, session, date);
        Path[] temporary = new Path[1];
        fileTasks.submit(TASK_GENERATE, FileOperationText.GENERATE_RESULT.taskName(),
                FileTaskManager.LockScope.NONE, true,
                progress -> generationService.generate(request, progress::update, progress::checkpoint,
                        path -> temporary[0] = path),
                this::finishGeneration,
                error -> {
                    deleteQuietly(temporary[0]);
                    if (Objects.equals(activeGenerationId, request.sequence())) activeGenerationId = null;
                    showError("无法生成结果：\n" + error, "生成失败");
                    setStatus("结果生成失败。");
                }, () -> {
                    deleteQuietly(temporary[0]);
                    if (Objects.equals(activeGenerationId, request.sequence())) activeGenerationId = null;
                    setStatus("结果生成已取消。以前的有效结果未被替换。");
                });
    }

    private void finishGeneration(GeneratedResult generated) {
        GenerationRequest request = generated.request();
        if (Objects.equals(activeGenerationId, request.sequence())) activeGenerationId = null;
        if (generated.renderResult().hasError()) {
            deleteQuietly(generated.docxFile());
            showWarning(generated.renderResult().error(), "表达式计算失败");
            setStatus("表达式计算失败，未生成结果。以前的有效结果未被替换。");
            return;
        }
        boolean stale = request.isStale(session);
        if (stale) {
            handleStaleGeneration(generated);
            return;
        }
        acceptGeneratedResult(generated, false);
    }

    private void handleStaleGeneration(GeneratedResult generated) {
        Object[] options = {"按最新内容重新生成", "保留本次结果", "丢弃本次结果"};
        int choice = JOptionPane.showOptionDialog(this,
                "文件已经生成，但生成期间模板、变量、日期或小数位数发生了变化。\n\n"
                        + "本次文件使用的是生成开始时的数据。",
                "生成内容已经变化", JOptionPane.DEFAULT_OPTION, JOptionPane.WARNING_MESSAGE,
                null, options, options[0]);
        if (choice == 0) {
            deleteQuietly(generated.docxFile());
            SwingUtilities.invokeLater(this::generate);
        } else if (choice == 1) {
            acceptGeneratedResult(generated, true);
        } else {
            deleteQuietly(generated.docxFile());
            setStatus("已丢弃基于旧输入生成的结果。");
        }
    }

    private void acceptGeneratedResult(GeneratedResult generated, boolean stale) {
        Path oldDocx = currentDocxResult;
        currentDocxResult = generated.docxFile();
        currentResultDocx = generated.request().word();
        if (oldDocx != null && !oldDocx.equals(currentDocxResult)) retireTemporaryResult(oldDocx);
        resultPanel.setText(generated.renderResult().result());
        markResultValid();
        setStatus(stale
                ? "已保留本次结果；它基于生成开始时的数据，与当前输入不一致。"
                : buildSuccessMessage(generated.request().parsed(), generated.request().date()));
        saveCurrentTemplateConfig(false);
    }

    private Map<String, String> validatedReplacementValues() {
        VariableValidation.Result validation = VariableValidation.validate(session.variables());
        List<String> problems = validation.invalidNames();
        if (!problems.isEmpty()) {
            variablePanel.refreshAllValidation();
            showWarning("以下数值变量格式不正确：\n\n" + String.join("、", problems) + "\n\n内容已保留，请修改后重新生成。", "输入格式错误");
            setStatus("存在无效数值，未生成结果。"); return null;
        }
        return validation.values();
    }

    private static String buildSuccessMessage(TemplateParser.ParsedTemplate parsed, LocalDate date) {
        return "生成成功：" + parsed.variables().size() + " 个变量，"
                + parsed.expressionCount() + " 个表达式。";
    }

    private void invalidateResult(String reason) {
        resultValid = false;
        retireTemporaryResult(currentDocxResult);
        currentDocxResult = null;
        currentResultDocx = false;
        if (resultPanel != null) resultPanel.setText("");
        if (copyBtn != null) { copyBtn.setEnabled(false); saveResultBtn.setEnabled(false); }
        if (reason != null && !reason.isEmpty()) setStatus(reason);
    }

    private void markResultValid() { resultValid = true; copyBtn.setEnabled(true); saveResultBtn.setEnabled(true); }

    private void setDecimalPlacesFromConfig() {
        programmaticUpdate = true;
        try { variablePanel.setDecimalPlaces(session.decimalPlaces()); }
        finally { programmaticUpdate = false; }
    }
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
    private void saveResult() { if (requireResult()) { if (currentResultDocx) saveDocxResult(); else saveTextResult(); } }

    private void saveTextResult() {
        JFileChooser chooser = resultChooser("保存结果", TemplateConstants.RESULT_FILENAME, "文本文件 (*.txt)", "txt");
        while (chooser.showSaveDialog(this) == JFileChooser.APPROVE_OPTION) {
            File file = withRequiredExtension(chooser.getSelectedFile(), ".txt");
            if (file == null || !confirmFileOverwrite(file)) continue;
            String text = resultPanel.getText();
            fileTasks.submit(TASK_EXPORT, FileOperationText.SAVE_RESULT.taskName(),
                    FileTaskManager.LockScope.NONE, false,
                    progress -> {
                        progress.update(FileOperationText.SAVE_RESULT.inProgress(), 20, 100);
                        TextFileWriter.writeText(file.toPath(), text);
                        progress.update(FileOperationText.SAVE_RESULT.inProgress(), 100, 100);
                        return file;
                    }, this::resultExported,
                    error -> showError("无法写入文件：\n" + error, "保存失败"),
                    () -> setStatus("保存结果已取消。"));
            return;
        }
    }
    private void saveDocxResult() {
        if (currentDocxResult == null) return;
        JFileChooser chooser = resultChooser("保存 Word 结果", "result.docx", "Word 文档 (*.docx)", "docx");
        while (chooser.showSaveDialog(this) == JFileChooser.APPROVE_OPTION) {
            File file = withRequiredExtension(chooser.getSelectedFile(), ".docx");
            if (file == null || !confirmFileOverwrite(file)) continue;
            Path source = currentDocxResult;
            fileTasks.submit(TASK_EXPORT, FileOperationText.SAVE_RESULT.taskName(),
                    FileTaskManager.LockScope.NONE, true,
                    progress -> {
                        copyWithProgress(source, file.toPath(), progress,
                                FileOperationText.SAVE_RESULT.inProgress());
                        return file;
                    }, this::resultExported,
                    error -> showError("无法写入文件：\n" + error, "保存失败"),
                    () -> setStatus("保存结果已取消。"));
            return;
        }
    }

    private JFileChooser resultChooser(String title, String name, String description, String ext) {
        Path directory = Path.of("").toAbsolutePath();
        try { if (appConfig.lastExportDirectory() != null && Files.isDirectory(Path.of(appConfig.lastExportDirectory()))) directory = Path.of(appConfig.lastExportDirectory()); }
        catch (RuntimeException ignored) { }
        JFileChooser chooser = new JFileChooser(directory.toFile());
        chooser.setDialogTitle(title); chooser.setSelectedFile(directory.resolve(name).toFile());
        chooser.setFileFilter(new FileNameExtensionFilter(description, ext)); chooser.setAcceptAllFileFilterUsed(false);
        return chooser;
    }

    private void resultExported(File file) {
        appConfig.setLastExportDirectory(file.toPath().toAbsolutePath().getParent().toString());
        saveAppConfig(false);
        setStatus("结果已保存。", "结果已保存到：" + file.getAbsolutePath());
    }

    @Override public void dispose() {
        if (extractionPanel != null) extractionPanel.disposePanel();
        super.dispose();
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

    private void refreshCurrentTemplate() {
        if (session.templateName().isEmpty()) {
            showWarning("当前没有可刷新的模板。", "无法刷新");
            return;
        }
        Path templateFile = templateStore.templateFile(session.templateName());
        if (!Files.isRegularFile(templateFile)) {
            showWarning("当前模板文件尚未保存或已被移除：\n" + templateFile,
                    "无法刷新");
            return;
        }
        if (hasUnsavedTemplateChanges()) {
            int choice = JOptionPane.showConfirmDialog(this,
                    "刷新将放弃界面中尚未保存的模板修改，并重新读取磁盘文件。\n\n是否继续？",
                    "刷新模板", JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
            if (choice != JOptionPane.OK_OPTION) return;
        }
        if (!confirmCancelGeneration("刷新模板")) return;
        String name = session.templateName();
        loadTemplateAsync(name, () -> setStatus("已从磁盘刷新模板：" + name));
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

    private boolean confirmCancelGeneration(String action) {
        if (!fileTasks.hasTask(TASK_GENERATE)) return true;
        int choice = JOptionPane.showConfirmDialog(this,
                "当前正在生成结果。\n\n继续“" + action + "”需要取消正在进行的生成任务。是否继续？",
                "取消当前生成", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
        if (choice != JOptionPane.YES_OPTION) return false;
        fileTasks.cancelKind(TASK_GENERATE);
        return true;
    }

    private static void copyWithProgress(Path source, Path target,
                                         FileTaskManager.ProgressReporter progress,
                                         String phase) throws IOException {
        Path absoluteTarget = target.toAbsolutePath().normalize();
        Path parent = absoluteTarget.getParent();
        if (parent == null) throw new IOException("目标文件没有有效的父文件夹");
        Files.createDirectories(parent);
        Path temp = Files.createTempFile(parent, absoluteTarget.getFileName().toString(), ".copy.tmp");
        boolean committed = false;
        long total = Math.max(1, Files.size(source)), completed = 0;
        byte[] buffer = new byte[64 * 1024];
        try {
            progress.update(phase, 0, total);
            try (InputStream in = Files.newInputStream(source);
                 OutputStream out = Files.newOutputStream(temp)) {
                int read;
                while ((read = in.read(buffer)) >= 0) {
                    progress.checkpoint();
                    if (read == 0) continue;
                    out.write(buffer, 0, read);
                    completed += read;
                    progress.update(phase, completed, total);
                }
            }
            progress.checkpoint();
            try {
                Files.move(temp, absoluteTarget, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, absoluteTarget, StandardCopyOption.REPLACE_EXISTING);
            }
            committed = true;
            progress.update(phase, total, total);
        } finally {
            if (!committed) Files.deleteIfExists(temp);
        }
    }

    private static void deleteQuietly(Path path) {
        if (path == null) return;
        try { Files.deleteIfExists(path); } catch (IOException ignored) { }
    }

    private static void retireTemporaryResult(Path path) {
        if (path != null) path.toFile().deleteOnExit();
    }
    private void showAbout() {
        if (aboutDialog == null) aboutDialog = new AboutDialog(this);
        aboutDialog.showDialog();
    }

    private void showHelp() {
        if (helpDialog == null) {
            helpDialog = new TemplateHelpDialog(this, () -> templateText.getText(),
                    () -> Map.copyOf(session.variables()));
        }
        helpDialog.showOrRefresh();
    }

    private void showFontScaleMenu() {
        JPopupMenu menu = new JPopupMenu();
        ButtonGroup group = new ButtonGroup();
        for (FontScalePreset preset : FontScalePreset.values()) {
            JRadioButtonMenuItem item = new JRadioButtonMenuItem(preset.toString(), preset == fontScalePreset);
            item.addActionListener(e -> setFontScalePreset(preset));
            group.add(item);
            menu.add(item);
        }
        menu.show(fontScaleBtn, 0, fontScaleBtn.getHeight());
    }

    private void increaseFontScale() { setFontScalePreset(fontScalePreset.larger()); }
    private void decreaseFontScale() { setFontScalePreset(fontScalePreset.smaller()); }

    private String fontScaleButtonText() { return "字号：" + fontScalePreset + " ▾"; }

    private void setFontScalePreset(FontScalePreset preset) {
        if (preset == null || preset == fontScalePreset) return;
        fontScalePreset = preset;
        fontScaleBtn.setText(fontScaleButtonText());
        appConfig.setFontScale(preset.scale());
        UiFontManager.applyScale(preset.scale());
        UiFontManager.refreshOpenWindows();
        datePicker.refreshForFont();
        refreshStatusLabel();
        revalidate();
        repaint();
        saveAppConfig(false);
        setStatus("界面字号已设为“" + preset + "”。");
    }

    private void refreshAllValidation() {
        variablePanel.refreshAllValidation();
        refreshDateValidation();
    }

    private void refreshDateValidation() {
        boolean invalid = !datePicker.isInputValid();
        String message = "基准日期格式不正确，请输入有效日期，例如 2026-09-01。";
        datePicker.showValidationError(invalid, message);
        if (invalid) issueManager.put(new ValidationIssue("date", null, message,
                datePicker.inputComponent(), IssueSeverity.ERROR, 0));
        else issueManager.remove("date");
    }

    private void setStatus(String text) { setStatus(text, text); }
    private void setStatus(String shortText, String fullText) {
        fullStatusText = fullText == null ? shortText : fullText;
        statusLabel.putClientProperty("shortStatus", shortText == null ? " " : shortText);
        statusLabel.setToolTipText(fullStatusText);
        refreshStatusLabel();
    }

    private void refreshStatusLabel() {
        Object value = statusLabel.getClientProperty("shortStatus");
        String text = value instanceof String string ? string : fullStatusText;
        int width = statusLabel.getWidth() - 16;
        if (width <= 20) { statusLabel.setText(text); return; }
        statusLabel.setText(ellipsize(text, statusLabel.getFontMetrics(statusLabel.getFont()), width));
    }

    static String ellipsize(String text, FontMetrics metrics, int width) {
        if (text == null || metrics.stringWidth(text) <= width) return text;
        String suffix = "…";
        int low = 0, high = text.length();
        while (low < high) {
            int mid = (low + high + 1) >>> 1;
            if (metrics.stringWidth(text.substring(0, mid) + suffix) <= width) low = mid;
            else high = mid - 1;
        }
        return text.substring(0, low) + suffix;
    }
    private void showWarning(String text, String title) { JOptionPane.showMessageDialog(this, text, title, JOptionPane.WARNING_MESSAGE); }
    private void showError(String text, String title) { JOptionPane.showMessageDialog(this, text, title, JOptionPane.ERROR_MESSAGE); }
}
