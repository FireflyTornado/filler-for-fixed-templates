package com.firefly;

import com.firefly.core.DocxProcessor;
import com.firefly.core.LastValuesStore;
import com.firefly.core.TemplateParser;
import com.firefly.core.TemplateRenderer;
import com.firefly.core.TemplateStore;
import com.firefly.core.TextFileWriter;
import com.firefly.core.ValueNormalizer;
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
import javax.swing.WindowConstants;
import javax.swing.border.TitledBorder;
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
    private InputPanel variablePanel;
    private InputPanel stringPanel;
    private ResultPanel resultPanel;
    private JLabel statusLabel;

    private JButton newBtn;          // 新建模板（Word 模板模式下禁用）
    private JButton saveTplBtn;      // 保存模板（Word 模板模式下禁用）
    private TitledBorder tplBorder;  // 模板编辑区的边框标题（随模式切换文案）
    private JPanel tplPanel;         // 模板编辑区面板（标题变化后需要重绘）

    private String currentTemplateName = "";      // 当前使用的模板文件名
    private String currentTemplate = "";          // 上一次同步过输入框的模板
    private String lastDiskContent = "";          // 当前模板文件在磁盘上的内容（判断是否有未保存修改）
    private Map<String, String> currentValues = new LinkedHashMap<>(); // 当前模板上次保存的输入（用于回填）
    private boolean docxMode = false;             // 当前模板是否为 Word（.docx）文档
    private Path currentDocxResult;               // 最近一次生成的 Word 结果（临时文件）

    public TemplateToolApp(Path appDir) {
        super("模板填充工具");
        this.templateStore = new TemplateStore(appDir);
        this.valuesStore = new LastValuesStore(appDir);

        setSize(1050, 800);
        setMinimumSize(new java.awt.Dimension(660, 660));
        setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                saveLastInputs();   // 关闭前保存当前输入
                dispose();
            }
        });

        buildUi();
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
        JPanel tplBottom = new JPanel(new BorderLayout(4, 0));
        tplBottom.setBorder(BorderFactory.createEmptyBorder(4, 8, 8, 8));
        JLabel tplHint = new JLabel(
                "提示：{{变量名}} 会生成输入框（内容为纯数字也照常，如{{0.9}}）；运算需加 = 前缀：{{=变量1*变量2}}；日期变量用{{今日年月日}}/{{昨日年月日}}；字符串用[[字符串]]");
        tplHint.setForeground(Color.GRAY);
        tplBottom.add(tplHint, BorderLayout.WEST);
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
        JButton genBtn = new JButton("生成结果");
        JButton copyBtn = new JButton("复制结果");
        JButton saveBtn = new JButton("保存结果到文件");
        btns.add(genBtn);
        btns.add(copyBtn);
        btns.add(saveBtn);
        JLabel btnNote = new JLabel("输入框只显示模板中出现的变量");
        btnNote.setForeground(Color.GRAY);
        btnRow.add(btns, BorderLayout.WEST);
        btnRow.add(btnNote, BorderLayout.EAST);
        addRow(gc, 4, 0, GridBagConstraints.HORIZONTAL, btnRow);
        genBtn.addActionListener(e -> generate());
        copyBtn.addActionListener(e -> copyResult());
        saveBtn.addActionListener(e -> saveResult());

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

    /** 界面里的模板如果被改动过，先重建变量/字符串输入框（尽量保留已有值）。 */
    private void syncTemplate() {
        String content = templateText.getText();
        if (!content.equals(currentTemplate)) {
            currentTemplate = content;
            rebuildInputs(content);
        }
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
        templateText.setText(content);
        templateNameLabel.setText(name);
        currentDocxResult = null;
        setDocxMode(docx);
        currentValues = valuesStore.loadFor(name);   // 读该模板上次的输入，用于回填
        rebuildInputs(content, true);
        rememberTemplate(name);
        setStatus("已加载模板：" + name + (docx ? "（Word 文档，只读预览）" : ""));
    }

    /** 切换模板编辑模式：Word 模板为只读预览，且禁用「新建模板 / 保存模板」。 */
    private void setDocxMode(boolean docx) {
        this.docxMode = docx;
        templateText.setEditable(!docx);
        newBtn.setEnabled(!docx);
        saveTplBtn.setEnabled(!docx);
        tplBorder.setTitle(docx
                ? "模板内容（Word 文档模板：只读预览，请用 Word 编辑保存后再重新选择该模板）"
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
        chooser.setAcceptAllFileFilterUsed(true);
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        File selected = chooser.getSelectedFile();
        File target = selected;
        Path templatesDir = templateStore.templatesDir();
        try {
            if (!selected.getParentFile().toPath().toAbsolutePath().normalize()
                    .equals(templatesDir.toAbsolutePath().normalize())) {
                // 选中的是目录外的文件：先复制进 Templates 文件夹再使用
                target = new File(dir, selected.getName());
                Files.copy(selected.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
                setStatus("已从外部导入模板：" + selected.getName());
            }
        } catch (IOException e) {
            JOptionPane.showMessageDialog(this, "无法导入模板文件：\n" + e,
                    "导入失败", JOptionPane.ERROR_MESSAGE);
            return;
        }
        if (!confirmDiscardUnsaved()) {
            return;
        }
        loadTemplate(target.getName());
    }

    /** 「新建模板」：输入任意名字，清空编辑框等待写入；文件在首次「保存模板」时创建。 */
    private void newTemplate() {
        if (!confirmDiscardUnsaved()) {
            return;
        }
        String name = JOptionPane.showInputDialog(this,
                "请输入新模板文件名（存放在 Templates 文件夹内，可任意命名）：",
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
        if (!name.contains(".")) {
            name = name + ".txt";               // 统一模板扩展名
        }
        if (templateStore.listTemplateNames().contains(name)) {
            loadTemplate(name);
            setStatus("该模板已存在，已为你打开：" + name);
            return;
        }
        currentTemplateName = name;
        currentTemplate = "";
        lastDiskContent = "";
        currentValues = new LinkedHashMap<>();
        currentDocxResult = null;
        setDocxMode(false);
        templateText.setText("");
        templateNameLabel.setText(name);
        rebuildInputs("");
        rememberTemplate(name);
        setStatus("新模板 " + name + " 尚未保存，请在下方输入内容后点“保存模板”。");
    }

    /** 「保存模板」：把编辑框内容写回当前模板文件（不存在则新建）。 */
    private void saveTemplate() {
        if (docxMode) {
            JOptionPane.showMessageDialog(this,
                    "Word 模板不支持在界面内保存，请用 Word 编辑模板文件后重新选择该模板。",
                    "提示", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        syncTemplate();
        String name = currentTemplateName;
        if (name.isEmpty()) {
            JOptionPane.showMessageDialog(this, "当前没有选中的模板文件。",
                    "提示", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        try {
            templateStore.writeTemplate(name, currentTemplate);
            lastDiskContent = currentTemplate;
        } catch (IOException e) {
            JOptionPane.showMessageDialog(this, "无法写入模板文件：\n" + e,
                    "保存失败", JOptionPane.ERROR_MESSAGE);
            return;
        }
        rememberTemplate(name);
        Path file = templateStore.templateFile(name);
        setStatus("模板已保存到：" + file);
        JOptionPane.showMessageDialog(this, "模板已保存到：" + file, "已保存",
                JOptionPane.INFORMATION_MESSAGE);
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

    /** 编辑框内容与磁盘不一致（有未保存修改）时询问是否丢弃；无修改直接返回 true。 */
    private boolean confirmDiscardUnsaved() {
        if (templateText.getText().equals(lastDiskContent)) {
            return true;
        }
        return JOptionPane.showConfirmDialog(this,
                "当前模板有未保存的修改，继续操作将丢弃这些修改。是否继续？",
                "未保存的修改", JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION;
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
        syncTemplate();
        Map<String, String> values = validatedValues();
        if (values == null) {
            return;
        }
        variablePanel.markAllValid();

        LocalDate today = LocalDate.now();
        Map<String, String> autoVals = TemplateConstants.autoValues(today);
        Map<String, String> stringValues = stringPanel.getValues();
        TemplateParser.ParsedTemplate parsed = TemplateParser.parse(currentTemplate);

        if (docxMode) {
            generateDocx(parsed, values, autoVals, stringValues, today);
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
        setStatus(buildSuccessMessage(parsed, today));
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
                              LocalDate today) {
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
            setStatus(buildSuccessMessage(parsed, today) + " 点「保存结果到文件」导出 Word 文档。");
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
    private static String buildSuccessMessage(TemplateParser.ParsedTemplate parsed, LocalDate today) {
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
                msg += " 自动填充 " + autoNames.size() + " 个日期变量（" + today.getYear() + "）。";
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

    /** 结果为空时弹出提示并返回 false，否则返回 true。 */
    private boolean requireResult() {
        if (resultPanel.getText().isEmpty()) {
            JOptionPane.showMessageDialog(this, "结果为空，请先点击“生成结果”。",
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
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        File file = chooser.getSelectedFile();
        if (file.getName().indexOf('.') < 0) {
            file = new File(file.getParentFile(), file.getName() + ".txt");   // 统一扩展名
        }
        try {
            TextFileWriter.writeText(file.toPath(), resultPanel.getText());
        } catch (IOException e) {
            JOptionPane.showMessageDialog(this, "无法写入文件：\n" + e,
                    "保存失败", JOptionPane.ERROR_MESSAGE);
            return;
        }
        setStatus("结果已保存到：" + file.getAbsolutePath());
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
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        File file = chooser.getSelectedFile();
        if (file.getName().indexOf('.') < 0) {
            file = new File(file.getParentFile(), file.getName() + ".docx");
        }
        try {
            Files.copy(currentDocxResult, file.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            JOptionPane.showMessageDialog(this, "无法写入文件：\n" + e,
                    "保存失败", JOptionPane.ERROR_MESSAGE);
            return;
        }
        setStatus("结果已保存到：" + file.getAbsolutePath());
    }

    private void setStatus(String text) {
        statusLabel.setText(text);
    }
}
