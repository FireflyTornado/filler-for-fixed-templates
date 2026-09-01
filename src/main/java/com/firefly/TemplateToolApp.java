package com.firefly;

import com.firefly.core.DocxProcessor;
import com.firefly.core.LastValuesStore;
import com.firefly.core.TemplateParser;
import com.firefly.core.TemplateRenderer;
import com.firefly.core.TemplateStore;
import com.firefly.core.TextFileWriter;
import com.firefly.core.ValueNormalizer;
import com.firefly.ui.DatePickerPanel;
import com.firefly.ui.InputPanel;
import com.firefly.ui.ResultPanel;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.Timer;
import javax.swing.WindowConstants;
import javax.swing.border.TitledBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Desktop;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Toolkit;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.StringSelection;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 主窗口：负责界面布局、事件处理和数据同步。
 */
public final class TemplateToolApp extends JFrame {

    private static final Font UI_FONT = new Font("Microsoft YaHei UI", Font.PLAIN, 12);

    private final TemplateStore templateStore;
    private final LastValuesStore valuesStore;

    private JLabel templateNameLabel;
    private JTextArea templateText;
    private DatePickerPanel datePicker;
    private InputPanel variablePanel;
    private InputPanel stringPanel;
    private ResultPanel resultPanel;
    private JLabel statusLabel;

    private JButton newBtn;
    private JButton saveTplBtn;      // 保存模板（Word 模板模式下禁用）
    private JButton generateBtn;
    private JButton copyBtn;
    private JButton saveResultBtn;
    private TitledBorder tplBorder;  // 模板编辑区的边框标题（随模式切换文案）
    private JPanel tplPanel;         // 模板编辑区面板（标题变化后需要重绘）

    private String currentTemplateName = "";      // 当前使用的模板文件名
    private String currentTemplate = "";          // 上一次同步过输入框的模板
    private String lastDiskContent = "";          // 当前模板文件在磁盘上的内容（判断是否有未保存修改）
    private Map<String, String> currentValues = new LinkedHashMap<>(); // 当前模板上次保存的输入（用于回填）
    private boolean docxMode = false;             // 当前模板是否为 Word（.docx）文档
    private Path currentDocxResult;               // 最近一次生成的 Word 结果（临时文件）
    private boolean resultValid;
    private boolean programmaticUpdate;
    private boolean currentTemplateSaved;
    private final Timer templateSyncTimer;

    public TemplateToolApp(Path appDir) {
        super("模板填充工具");
        this.templateStore = new TemplateStore(appDir);
        this.valuesStore = new LastValuesStore(appDir);
        this.templateSyncTimer = new Timer(400, e -> synchronizeEditedTemplate());
        this.templateSyncTimer.setRepeats(false);

        setSize(1050, 800);
        setMinimumSize(new java.awt.Dimension(660, 660));
        setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                closeApplication();
            }
        });

        buildUi();
        installChangeListeners();
        initTemplates();
        setLocationRelativeTo(null);               // 窗口居中
    }

    // ---------- 界面搭建 ----------

    private void buildUi() {
        JPanel root = new JPanel(new GridBagLayout());
        setContentPane(root);
        GridBagConstraints gc = new GridBagConstraints();
        gc.gridx = 0;
        gc.insets = new Insets(6, 10, 6, 10);
        gc.anchor = GridBagConstraints.WEST;

        // 顶栏：当前模板文件 + 选择/新建/保存/打开文件夹
        JPanel top = new JPanel(new BorderLayout(6, 0));
        JPanel topLeft = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        topLeft.add(new JLabel("模板文件："));
        templateNameLabel = new JLabel("—");
        templateNameLabel.setForeground(Color.GRAY);
        topLeft.add(templateNameLabel);
        top.add(topLeft, BorderLayout.WEST);
        JPanel topRight = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        JButton chooseBtn = new JButton("选择模板文件…");
        newBtn = new JButton("新建模板");
        saveTplBtn = new JButton("保存模板");
        JButton openDirBtn = new JButton("打开文件夹");
        topRight.add(chooseBtn);
        topRight.add(newBtn);
        topRight.add(saveTplBtn);
        topRight.add(openDirBtn);
        top.add(topRight, BorderLayout.EAST);
        addRow(gc, 0, 0, GridBagConstraints.HORIZONTAL, top);
        chooseBtn.addActionListener(e -> chooseTemplate());
        newBtn.addActionListener(e -> newTemplate());
        saveTplBtn.addActionListener(e -> saveTemplate());
        openDirBtn.addActionListener(e -> openTemplatesFolder());

        // 模板编辑区
        tplPanel = new JPanel(new BorderLayout(8, 4));
        tplBorder = BorderFactory.createTitledBorder(
                "模板内容（可直接在下方修改，点“保存模板”写回当前模板文件）");
        tplPanel.setBorder(tplBorder);
        templateText = new JTextArea(5, 40);
        templateText.setFont(UI_FONT);
        templateText.setLineWrap(true);
        templateText.setWrapStyleWord(true);
        JScrollPane tplScroll = new JScrollPane(templateText);
        tplScroll.setBorder(BorderFactory.createEmptyBorder(8, 8, 0, 8));
        tplPanel.add(tplScroll, BorderLayout.CENTER);
        JPanel tplBottom = new JPanel(new BorderLayout(4, 4));
        tplBottom.setBorder(BorderFactory.createEmptyBorder(4, 8, 8, 8));
        datePicker = new DatePickerPanel();
        tplBottom.add(datePicker, BorderLayout.NORTH);
        JLabel tplHint = new JLabel(
                "提示：所有日期变量均以日历日期为基准，如{{昨日年月日}}/{{今日年月日}}/{{明日年月日}}；字符串用[[字符串]]");
        tplHint.setForeground(Color.GRAY);
        tplBottom.add(tplHint, BorderLayout.SOUTH);
        tplPanel.add(tplBottom, BorderLayout.SOUTH);
        addRow(gc, 1, 0, GridBagConstraints.HORIZONTAL, tplPanel);

        // 变量输入区
        variablePanel = new InputPanel("变量值输入（所有 {{变量名}} 均在此填数字，留空按 0 处理）", false);
        addRow(gc, 2, 1, GridBagConstraints.BOTH, variablePanel);

        // 字符串输入区
        stringPanel = new InputPanel(
                "字符串输入（内容原样输出到模板的 [[字符串]]，支持换行/空格/格式）", true);
        addRow(gc, 3, 1, GridBagConstraints.BOTH, stringPanel);

        // 操作按钮
        JPanel btnRow = new JPanel(new BorderLayout(6, 0));
        JPanel btns = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        generateBtn = new JButton("生成结果");
        copyBtn = new JButton("复制结果");
        saveResultBtn = new JButton("保存结果到文件");
        copyBtn.setEnabled(false);
        saveResultBtn.setEnabled(false);
        btns.add(generateBtn);
        btns.add(copyBtn);
        btns.add(saveResultBtn);
        JLabel btnNote = new JLabel("输入框只显示模板中出现的变量");
        btnNote.setForeground(Color.GRAY);
        btnRow.add(btns, BorderLayout.WEST);
        btnRow.add(btnNote, BorderLayout.EAST);
        addRow(gc, 4, 0, GridBagConstraints.HORIZONTAL, btnRow);
        generateBtn.addActionListener(e -> generate());
        copyBtn.addActionListener(e -> copyResult());
        saveResultBtn.addActionListener(e -> saveResult());

        // 结果输出区
        resultPanel = new ResultPanel();
        addRow(gc, 5, 1, GridBagConstraints.BOTH, resultPanel);

        // 状态栏
        statusLabel = new JLabel(" ");
        statusLabel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, Color.LIGHT_GRAY),
                BorderFactory.createEmptyBorder(4, 10, 4, 10)));
        addRow(gc, 6, 0, GridBagConstraints.HORIZONTAL, statusLabel);
    }

    /** 一行固定属性（gridy/weighty/fill）的添加；gridx 恒为 0，anchor 已在启动时设定。 */
    private void addRow(GridBagConstraints gc, int gridy, double weighty, int fill, JComponent comp) {
        gc.gridy = gridy;
        gc.weighty = weighty;
        gc.fill = fill;
        add(comp, gc);
    }

    // ---------- 数据同步 ----------

    private void installChangeListeners() {
        templateText.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                templateEdited();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                templateEdited();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                templateEdited();
            }
        });
        variablePanel.setChangeListener(() -> invalidateResult("内容已修改，请重新生成。"));
        stringPanel.setChangeListener(() -> invalidateResult("内容已修改，请重新生成。"));
        datePicker.setChangeListener(() -> {
            if (!programmaticUpdate) {
                invalidateResult("内容已修改，请重新生成。");
            }
        });
    }

    private void templateEdited() {
        if (programmaticUpdate) {
            return;
        }
        invalidateResult("内容已修改，请重新生成。");
        updateDirtyIndicator();
        templateSyncTimer.restart();
    }

    private void synchronizeEditedTemplate() {
        String content = templateText.getText();
        if (!content.equals(currentTemplate)) {
            currentTemplate = content;
            rebuildInputs(content);
        }
    }

    /** 界面里的模板如果被改动过，先重建变量/字符串输入框（尽量保留已有值）。 */
    private boolean syncTemplate() {
        templateSyncTimer.stop();
        String content = templateText.getText();
        if (!content.equals(currentTemplate)) {
            List<String> oldVariables = variablePanel.getInputNames();
            List<String> oldStrings = stringPanel.getInputNames();
            currentTemplate = content;
            rebuildInputs(content);
            return !oldVariables.equals(variablePanel.getInputNames())
                    || !oldStrings.equals(stringPanel.getInputNames());
        }
        return false;
    }

    private void invalidateResult(String reason) {
        resultValid = false;
        currentDocxResult = null;
        if (resultPanel != null) {
            resultPanel.setText("");
        }
        if (copyBtn != null) {
            copyBtn.setEnabled(false);
            saveResultBtn.setEnabled(false);
        }
        if (statusLabel != null && reason != null && !reason.isEmpty()) {
            setStatus(reason);
        }
    }

    private void markResultValid() {
        resultValid = true;
        copyBtn.setEnabled(true);
        saveResultBtn.setEnabled(true);
    }

    // ---------- 模板文件管理 ----------

    /** 启动初始化：确保 Templates 目录有模板，然后加载上次使用的模板（没有则取第一个）。 */
    private void initTemplates() {
        try {
            templateStore.ensureTemplatesExist();
        } catch (IOException e) {
            JOptionPane.showMessageDialog(this, "无法初始化模板文件夹：\n" + e,
                    "错误", JOptionPane.ERROR_MESSAGE);
            setStatus("无法初始化模板文件夹。");
            return;
        }
        List<String> names = templateStore.listTemplateNames();
        String lastUsed = valuesStore.loadLastTemplate();
        String toLoad = (lastUsed != null && names.contains(lastUsed))
                ? lastUsed
                : (names.isEmpty() ? null : names.get(0));
        if (toLoad == null) {
            setStatus("Templates 文件夹为空，无法加载模板。");
            return;
        }
        loadTemplate(toLoad);
    }

    /** 把指定模板文件的内容加载到编辑框，并作为当前模板；切换前先把上一个模板的输入存起来。 */
    private void loadTemplate(String name) {
        saveLastInputs();                        // 切换模板前，把当前输入存回当前模板
        boolean docx = DocxProcessor.isDocxName(name);
        String content;
        try {
            content = docx
                    ? DocxProcessor.extractText(templateStore.templateFile(name))
                    : templateStore.readTemplate(name);
        } catch (IOException e) {
            JOptionPane.showMessageDialog(this, "无法读取模板文件：\n" + e,
                    "读取失败", JOptionPane.ERROR_MESSAGE);
            return;
        }
        currentTemplateName = name;
        currentTemplate = content;
        lastDiskContent = content;
        currentTemplateSaved = true;
        setTemplateText(content);
        setDocxMode(docx);
        currentValues = valuesStore.loadFor(name);   // 读该模板上次的输入，用于回填
        rebuildInputs(content, true);
        invalidateResult(null);
        updateDirtyIndicator();
        rememberTemplate(name);
        setStatus("已加载模板：" + name + (docx ? "（Word 文档，只读预览）" : ""));
    }

    /** 切换模板编辑模式：Word 模板仅禁用模板编辑与保存，新建文本模板始终可用。 */
    private void setDocxMode(boolean docx) {
        this.docxMode = docx;
        templateText.setEditable(!docx);
        newBtn.setEnabled(true);
        saveTplBtn.setEnabled(!docx);
        tplBorder.setTitle(docx
                ? "Word 模板为只读预览，请使用 Word 编辑后重新打开或重新加载"
                : "模板内容（可直接在下方修改，点“保存模板”写回当前模板文件）");
        tplPanel.revalidate();
        tplPanel.repaint();
    }

    /** 「选择模板文件…」：从 Templates 文件夹选一个模板加载；目录外的文件先导入文件夹。 */
    private void chooseTemplate() {
        File dir = templateStore.templatesDir().toFile();
        if (!dir.exists()) {
            dir.mkdirs();
        }
        JFileChooser chooser = new JFileChooser();
        chooser.setFileSelectionMode(JFileChooser.FILES_ONLY);
        chooser.setCurrentDirectory(dir);
        chooser.setDialogTitle("选择模板文件（Templates 文件夹）");
        chooser.setFileFilter(new FileNameExtensionFilter("模板文件 (*.txt; *.docx)", "txt", "docx"));
        chooser.setAcceptAllFileFilterUsed(false);
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        File selected = chooser.getSelectedFile();
        if (!isSupportedTemplateName(selected.getName())) {
            JOptionPane.showMessageDialog(this, "仅支持 .txt 和 .docx 模板。",
                    "文件类型不支持", JOptionPane.WARNING_MESSAGE);
            return;
        }
        if (!confirmUnsaved("继续")) {
            return;
        }
        File target = selected;
        Path templatesDir = templateStore.templatesDir();
        try {
            if (!selected.getParentFile().toPath().toAbsolutePath().normalize()
                    .equals(templatesDir.toAbsolutePath().normalize())) {
                target = chooseImportTarget(selected, dir);
                if (target == null) {
                    return;
                }
                Files.copy(selected.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
                setStatus("已从外部导入模板：" + target.getName());
            }
        } catch (IOException e) {
            JOptionPane.showMessageDialog(this, "无法导入模板文件：\n" + e,
                    "导入失败", JOptionPane.ERROR_MESSAGE);
            return;
        }
        loadTemplate(target.getName());
    }

    /** 「新建模板」：输入任意名字，清空编辑框等待写入；文件在首次「保存模板」时创建。 */
    private void newTemplate() {
        if (!confirmUnsaved("继续")) {
            return;
        }
        String name = JOptionPane.showInputDialog(this,
                "请输入新模板文件名（仅支持 .txt）：",
                "新建模板", JOptionPane.PLAIN_MESSAGE);
        if (name == null) {
            return;
        }
        name = name.trim();
        if (!isValidTemplateName(name)) {
            JOptionPane.showMessageDialog(this,
                    "文件名不能为空，且不能包含 \\ / : * ? \" < > | 等字符，也不能以 . 开头。",
                    "文件名不合法", JOptionPane.ERROR_MESSAGE);
            return;
        }
        String extension = extensionOf(name);
        if (extension.isEmpty()) {
            name += ".txt";
        } else if (!".txt".equalsIgnoreCase(extension)) {
            JOptionPane.showMessageDialog(this,
                    "界面内新建仅支持 .txt 模板；Word 模板请从外部导入。",
                    "文件类型不支持", JOptionPane.WARNING_MESSAGE);
            return;
        }
        String existingName = findExistingTemplateName(name);
        if (existingName != null) {
            loadTemplate(existingName);
            setStatus("该模板已存在，已为你打开：" + existingName);
            return;
        }
        currentTemplateName = name;
        currentTemplate = "";
        lastDiskContent = "";
        currentTemplateSaved = false;
        currentValues = new LinkedHashMap<>();
        setDocxMode(false);
        setTemplateText("");
        rebuildInputs("");
        invalidateResult(null);
        updateDirtyIndicator();
        rememberTemplate(name);
        setStatus("新模板 " + name + " 尚未保存，请在下方输入内容后点“保存模板”。");
    }

    /** 「保存模板」：把编辑框内容写回当前模板文件（不存在则新建）。 */
    private void saveTemplate() {
        saveTemplateInternal(true);
    }

    private boolean saveTemplateInternal(boolean showSuccessDialog) {
        if (docxMode) {
            JOptionPane.showMessageDialog(this,
                    "Word 模板不支持在界面内保存，请用 Word 编辑模板文件后重新选择该模板。",
                    "提示", JOptionPane.INFORMATION_MESSAGE);
            return false;
        }
        syncTemplate();
        String name = currentTemplateName;
        if (name.isEmpty()) {
            JOptionPane.showMessageDialog(this, "当前没有选中的模板文件。",
                    "提示", JOptionPane.INFORMATION_MESSAGE);
            return false;
        }
        if (!isTxtName(name)) {
            JOptionPane.showMessageDialog(this, "文本模板只能保存为 .txt 文件。",
                    "文件类型不支持", JOptionPane.WARNING_MESSAGE);
            return false;
        }
        try {
            templateStore.writeTemplate(name, currentTemplate);
            lastDiskContent = currentTemplate;
            currentTemplateSaved = true;
        } catch (IOException e) {
            JOptionPane.showMessageDialog(this, "无法写入模板文件：\n" + e,
                    "保存失败", JOptionPane.ERROR_MESSAGE);
            return false;
        }
        rememberTemplate(name);
        updateDirtyIndicator();
        Path file = templateStore.templateFile(name);
        setStatus("模板已保存到：" + file);
        if (showSuccessDialog) {
            JOptionPane.showMessageDialog(this, "模板已保存到：" + file, "已保存",
                    JOptionPane.INFORMATION_MESSAGE);
        }
        return true;
    }

    /** 「打开文件夹」：用系统文件管理器打开 Templates 文件夹。 */
    private void openTemplatesFolder() {
        File dir = templateStore.templatesDir().toFile();
        if (!dir.exists()) {
            dir.mkdirs();
        }
        try {
            if (Desktop.isDesktopSupported()) {
                Desktop.getDesktop().open(dir);
                return;
            }
            throw new IOException("Desktop 不可用");
        } catch (Exception e) {
            // 打开失败时回退到 Windows 资源管理器
            try {
                String os = System.getProperty("os.name", "").toLowerCase();
                if (os.contains("win")) {
                    new ProcessBuilder("explorer.exe", dir.getAbsolutePath()).start();
                } else if (os.contains("mac")) {
                    new ProcessBuilder("open", dir.getAbsolutePath()).start();
                } else {
                    new ProcessBuilder("xdg-open", dir.getAbsolutePath()).start();
                }
            } catch (Exception e2) {
                JOptionPane.showMessageDialog(this, "无法打开模板文件夹：\n" + e2,
                        "无法打开", JOptionPane.ERROR_MESSAGE);
            }
        }
    }

    /** 保存/放弃/取消三选一，供关闭、切换和新建共同使用。 */
    private boolean confirmUnsaved(String continueText) {
        if (!hasUnsavedTemplateChanges()) {
            return true;
        }
        Object[] options = {"保存并" + continueText, "不保存并" + continueText, "取消"};
        int choice = JOptionPane.showOptionDialog(this,
                "当前模板有未保存的修改。",
                "未保存的修改", JOptionPane.DEFAULT_OPTION, JOptionPane.WARNING_MESSAGE,
                null, options, options[0]);
        if (choice == 0) {
            return saveTemplateInternal(false);
        }
        return choice == 1;
    }

    private boolean hasUnsavedTemplateChanges() {
        return !docxMode && (!currentTemplateSaved || !templateText.getText().equals(lastDiskContent));
    }

    private void closeApplication() {
        if (!confirmUnsaved("退出")) {
            return;
        }
        saveLastInputs();
        dispose();
        System.exit(0);
    }

    private void setTemplateText(String text) {
        templateSyncTimer.stop();
        programmaticUpdate = true;
        try {
            templateText.setText(text);
        } finally {
            programmaticUpdate = false;
        }
    }

    private void updateDirtyIndicator() {
        String shown = currentTemplateName.isEmpty() ? "—" : currentTemplateName;
        if (hasUnsavedTemplateChanges()) {
            shown += " *";
        }
        templateNameLabel.setText(shown);
        setTitle("模板填充工具" + (hasUnsavedTemplateChanges() ? " *" : ""));
    }

    /** 记住当前使用的模板文件名（写进 last_values.json，下次启动恢复）。 */
    private void rememberTemplate(String name) {
        if (name == null || name.isEmpty()) {
            return;
        }
        valuesStore.saveLastTemplate(name);
    }

    /** 新模板文件名校验：非空、不以 . 开头（避免隐藏文件），且不能包含路径分隔符等非法字符。 */
    private static boolean isValidTemplateName(String name) {
        if (name.isEmpty() || name.startsWith(".") || name.equals(".") || name.equals("..")) {
            return false;
        }
        for (char c : name.toCharArray()) {
            if ("\\/:*?\"<>|".indexOf(c) >= 0) {
                return false;
            }
        }
        return true;
    }

    private File chooseImportTarget(File source, File templatesDir) {
        File target = new File(templatesDir, source.getName());
        if (!target.exists()) {
            return target;
        }
        Object[] options = {"覆盖现有模板", "使用新名称导入", "取消"};
        int choice = JOptionPane.showOptionDialog(this,
                "Templates 文件夹中已存在同名模板：\n" + source.getName(),
                "模板已存在", JOptionPane.DEFAULT_OPTION, JOptionPane.WARNING_MESSAGE,
                null, options, options[1]);
        if (choice == 0) {
            return target;
        }
        if (choice != 1) {
            return null;
        }
        return nextAvailableImportName(source.getName(), templatesDir);
    }

    private static File nextAvailableImportName(String originalName, File dir) {
        String extension = extensionOf(originalName);
        String base = originalName.substring(0, originalName.length() - extension.length());
        for (int number = 2; ; number++) {
            String candidate = base + " (" + number + ")" + extension;
            if (isValidTemplateName(candidate) && isSupportedTemplateName(candidate)) {
                File file = new File(dir, candidate);
                if (!file.exists()) {
                    return file;
                }
            }
        }
    }

    private static boolean isSupportedTemplateName(String name) {
        return isTxtName(name) || DocxProcessor.isDocxName(name);
    }

    private static boolean isTxtName(String name) {
        return name != null && name.toLowerCase(Locale.ROOT).endsWith(".txt");
    }

    private static String extensionOf(String name) {
        int dot = name.lastIndexOf('.');
        return dot <= 0 ? "" : name.substring(dot);
    }

    private String findExistingTemplateName(String name) {
        for (String existing : templateStore.listTemplateNames()) {
            if (existing.equalsIgnoreCase(name)) {
                return existing;
            }
        }
        return null;
    }

    /** 按模板一次性重建变量/字符串输入框（尽量保留已有输入、用该模板上次保存的值回填）。 */
    private void rebuildInputs(String template) {
        rebuildInputs(template, false);
    }

    /** fresh=true 时忽略当前输入框里的旧值，直接用该模板上次保存的值回填（用于切换模板）。 */
    private void rebuildInputs(String template, boolean fresh) {
        TemplateParser.ParsedTemplate parsed = TemplateParser.parse(template);
        Map<String, String> currentVars = fresh ? Map.of() : variablePanel.getValues();
        Map<String, String> currentStrs = fresh ? Map.of() : stringPanel.getValues();
        variablePanel.rebuild(parsed.inputVariables(), !parsed.autoVariables().isEmpty(),
                currentVars, currentValues);
        stringPanel.rebuild(parsed.stringVariables(), currentStrs, currentValues);
    }

    /** 收集当前所有输入的值（变量 + 字符串；字符串用 [[名字]] 作键避免冲突）。 */
    private Map<String, String> currentInputs() {
        Map<String, String> data = new LinkedHashMap<>(variablePanel.getValues());
        for (Map.Entry<String, String> e : stringPanel.getValues().entrySet()) {
            data.put("[[" + e.getKey() + "]]", e.getValue());
        }
        return data;
    }

    /** 把当前输入存到当前模板名下（模板名空则跳过）。 */
    private void saveLastInputs() {
        try {
            if (!currentTemplateName.isEmpty()) {
                valuesStore.saveFor(currentTemplateName, currentInputs());
            }
        } catch (Exception e) {
            // 忽略：存档失败不影响主流程
        }
    }

    // ---------- 动作 ----------

    private void generate() {
        invalidateResult(null);
        if (syncTemplate()) {
            setStatus("输入项已更新，请填写后再次生成。");
            return;
        }
        Map<String, String> values = validatedValues();
        if (values == null) {
            return;
        }
        variablePanel.markAllValid();

        LocalDate selectedDate = datePicker.getSelectedDate();
        if (selectedDate == null) {
            JOptionPane.showMessageDialog(this,
                    "基准日期格式不正确，请输入有效日期，例如 2026-09-01。",
                    "日期格式错误", JOptionPane.WARNING_MESSAGE);
            setStatus("基准日期格式错误，未生成结果。");
            return;
        }
        Map<String, String> autoVals = TemplateConstants.autoValues(selectedDate);
        Map<String, String> stringValues = stringPanel.getValues();
        TemplateParser.ParsedTemplate parsed = TemplateParser.parse(currentTemplate);

        if (docxMode) {
            generateDocx(parsed, values, autoVals, stringValues, selectedDate);
            return;
        }

        TemplateRenderer.RenderResult result =
                TemplateRenderer.render(currentTemplate, values, autoVals, stringValues);
        if (result.hasError()) {
            JOptionPane.showMessageDialog(this, result.error(),
                    "表达式计算失败", JOptionPane.WARNING_MESSAGE);
            setStatus("表达式计算失败，未生成结果。");
            return;
        }
        resultPanel.setText(result.result());
        markResultValid();
        setStatus(buildSuccessMessage(parsed, selectedDate));
        saveLastInputs();
    }

    /** 收集变量输入并校验；有问题时标红并弹窗，返回 null。 */
    private Map<String, String> validatedValues() {
        Map<String, String> values = new LinkedHashMap<>();
        List<String> problems = new ArrayList<>();
        for (Map.Entry<String, String> e : variablePanel.getValues().entrySet()) {
            String normalized = ValueNormalizer.normalize(e.getValue());
            if (normalized == null) {
                problems.add(e.getKey());
            } else {
                values.put(e.getKey(), normalized);
            }
        }
        if (!problems.isEmpty()) {
            variablePanel.markInvalid(problems);
            JOptionPane.showMessageDialog(this,
                    "以下变量需要填写数字（可含小数、负号）：\n\n    "
                            + String.join("、", problems)
                            + "\n\n（留空则按 0 处理）请修改后重新生成。",
                    "输入格式错误", JOptionPane.WARNING_MESSAGE);
            setStatus("存在非数字输入，未生成结果。");
            return null;
        }
        return values;
    }

    /** 生成 Word 结果：渲染到临时 .docx 并预览纯文本；点「保存结果到文件」再导出。 */
    private void generateDocx(TemplateParser.ParsedTemplate parsed,
                              Map<String, String> values,
                              Map<String, String> autoVals,
                              Map<String, String> stringValues,
                              LocalDate selectedDate) {
        Path src = templateStore.templateFile(currentTemplateName);
        Path tmp = null;
        try {
            tmp = Files.createTempFile("tt_result", ".docx");
            tmp.toFile().deleteOnExit();
            TemplateRenderer.RenderResult rr =
                    DocxProcessor.render(src, tmp, values, autoVals, stringValues);
            if (rr.hasError()) {
                Files.deleteIfExists(tmp);
                JOptionPane.showMessageDialog(this, rr.error(),
                        "表达式计算失败", JOptionPane.WARNING_MESSAGE);
                setStatus("表达式计算失败，未生成结果。");
                return;
            }
            currentDocxResult = tmp;
            resultPanel.setText(rr.result());
            markResultValid();
            setStatus(buildSuccessMessage(parsed, selectedDate) + " 点「保存结果到文件」导出 Word 文档。");
        } catch (IOException e) {
            if (tmp != null) {
                try {
                    Files.deleteIfExists(tmp);
                } catch (IOException ignored) {
                    // 忽略清理失败
                }
            }
            JOptionPane.showMessageDialog(this, "无法生成 Word 结果：\n" + e,
                    "生成失败", JOptionPane.ERROR_MESSAGE);
            setStatus("Word 生成失败。");
        } finally {
            saveLastInputs();
        }
    }

    /** 生成成功后的状态栏文案（文本与 Word 两种模式共用）。 */
    private static String buildSuccessMessage(TemplateParser.ParsedTemplate parsed,
                                              LocalDate selectedDate) {
        List<String> names = parsed.inputVariables();
        List<String> autoNames = parsed.autoVariables();
        int exprCount = parsed.expressionCount();
        List<String> strNames = parsed.stringVariables();
        String msg;
        if (names.isEmpty() && autoNames.isEmpty() && strNames.isEmpty()) {
            msg = "生成成功：模板中没有变量，输出的是原文。";
        } else {
            msg = "生成成功：已替换 " + names.size() + " 个变量。";
            if (!autoNames.isEmpty()) {
                msg += " 自动填充 " + autoNames.size() + " 个日期变量"
                        + "（日历基准日期 " + selectedDate + "）。";
            }
            if (exprCount > 0) {
                msg += " 计算 " + exprCount + " 个表达式。";
            }
            if (!strNames.isEmpty()) {
                msg += " 填入 " + strNames.size() + " 段字符串。";
            }
        }
        return msg;
    }

    /** 只允许使用最近一次成功生成、且生成后未发生任何输入变化的结果。 */
    private boolean requireResult() {
        if (!resultValid) {
            JOptionPane.showMessageDialog(this, "结果已失效，请重新点击“生成结果”。",
                    "提示", JOptionPane.INFORMATION_MESSAGE);
            return false;
        }
        return true;
    }

    private void copyResult() {
        if (!requireResult()) {
            return;
        }
        Clipboard clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
        clipboard.setContents(new StringSelection(resultPanel.getText()), null);
        setStatus("结果已复制到剪贴板。");
    }

    private void saveResult() {
        if (!requireResult()) {
            return;
        }
        if (docxMode) {
            saveDocxResult();
            return;
        }
        JFileChooser chooser = new JFileChooser();
        chooser.setCurrentDirectory(java.nio.file.Paths.get("").toAbsolutePath().toFile());
        chooser.setDialogTitle("保存结果");
        chooser.setSelectedFile(new File(TemplateConstants.RESULT_FILENAME));
        chooser.setFileFilter(new FileNameExtensionFilter("文本文件 (*.txt)", "txt"));
        chooser.setAcceptAllFileFilterUsed(false);
        while (chooser.showSaveDialog(this) == JFileChooser.APPROVE_OPTION) {
            File file = withRequiredExtension(chooser.getSelectedFile(), ".txt");
            if (file == null) {
                continue;
            }
            if (!confirmFileOverwrite(file)) {
                continue;
            }
            try {
                TextFileWriter.writeText(file.toPath(), resultPanel.getText());
            } catch (IOException e) {
                JOptionPane.showMessageDialog(this, "无法写入文件：\n" + e,
                        "保存失败", JOptionPane.ERROR_MESSAGE);
                return;
            }
            setStatus("结果已保存到：" + file.getAbsolutePath());
            return;
        }
    }

    /** 把最近生成的 Word 结果（临时 .docx）复制到用户选择的文件。 */
    private void saveDocxResult() {
        if (currentDocxResult == null) {
            JOptionPane.showMessageDialog(this, "Word 结果尚未生成，请先点击“生成结果”。",
                    "提示", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        JFileChooser chooser = new JFileChooser();
        chooser.setCurrentDirectory(java.nio.file.Paths.get("").toAbsolutePath().toFile());
        chooser.setDialogTitle("保存 Word 结果");
        chooser.setSelectedFile(new File("result.docx"));
        chooser.setFileFilter(new FileNameExtensionFilter("Word 文档 (*.docx)", "docx"));
        chooser.setAcceptAllFileFilterUsed(false);
        while (chooser.showSaveDialog(this) == JFileChooser.APPROVE_OPTION) {
            File file = withRequiredExtension(chooser.getSelectedFile(), ".docx");
            if (file == null) {
                continue;
            }
            if (!confirmFileOverwrite(file)) {
                continue;
            }
            try {
                Files.copy(currentDocxResult, file.toPath(), StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                JOptionPane.showMessageDialog(this, "无法写入文件：\n" + e,
                        "保存失败", JOptionPane.ERROR_MESSAGE);
                return;
            }
            setStatus("结果已保存到：" + file.getAbsolutePath());
            return;
        }
    }

    private File withRequiredExtension(File selected, String requiredExtension) {
        String extension = extensionOf(selected.getName());
        if (extension.isEmpty()) {
            return new File(selected.getParentFile(), selected.getName() + requiredExtension);
        }
        if (!requiredExtension.equalsIgnoreCase(extension)) {
            JOptionPane.showMessageDialog(this,
                    "文件名必须使用 " + requiredExtension + " 扩展名，请修改后重试。",
                    "扩展名不正确", JOptionPane.WARNING_MESSAGE);
            return null;
        }
        return selected;
    }

    private boolean confirmFileOverwrite(File file) {
        if (!file.exists()) {
            return true;
        }
        return JOptionPane.showConfirmDialog(this,
                "文件已存在，是否覆盖？\n" + file.getAbsolutePath(),
                "确认覆盖", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE)
                == JOptionPane.YES_OPTION;
    }

    private void setStatus(String text) {
        statusLabel.setText(text);
    }
}
