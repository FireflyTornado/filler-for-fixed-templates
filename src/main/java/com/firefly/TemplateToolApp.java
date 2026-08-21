package com.firefly;

import com.firefly.core.ConfigStore;
import com.firefly.core.LastValuesStore;
import com.firefly.core.TemplateParser;
import com.firefly.core.TemplateRenderer;
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
import java.nio.file.Path;
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

    private final ConfigStore configStore;
    private final LastValuesStore valuesStore;

    private JLabel configPathLabel;
    private JTextArea templateText;
    private InputPanel variablePanel;
    private InputPanel stringPanel;
    private ResultPanel resultPanel;
    private JLabel statusLabel;

    private String currentTemplate = "";          // 上一次同步过输入框的模板
    private Map<String, String> lastValues = new LinkedHashMap<>();

    public TemplateToolApp(Path appDir) {
        super("模板填充工具");
        this.configStore = new ConfigStore(appDir);
        this.valuesStore = new LastValuesStore(appDir);

        setSize(950, 1000);
        setMinimumSize(new java.awt.Dimension(660, 660));
        setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                saveLastInputs();   // 关闭前保存当前输入
                dispose();
            }
        });

        this.lastValues = valuesStore.load();     // 在重建输入框之前载入
        buildUi();
        loadConfig();
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

        // 顶栏：配置文件位置 + 打开/重新加载
        JPanel top = new JPanel(new BorderLayout(6, 0));
        JPanel topLeft = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        topLeft.add(new JLabel("配置文件："));
        configPathLabel = new JLabel(configStore.configFile().toString());
        configPathLabel.setForeground(Color.GRAY);
        topLeft.add(configPathLabel);
        top.add(topLeft, BorderLayout.WEST);
        JPanel topRight = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        JButton reloadBtn = new JButton("重新加载");
        JButton openBtn = new JButton("用系统编辑器打开");
        topRight.add(reloadBtn);
        topRight.add(openBtn);
        top.add(topRight, BorderLayout.EAST);
        addRow(gc, 0, 0, GridBagConstraints.HORIZONTAL, top);
        reloadBtn.addActionListener(e -> loadConfig());
        openBtn.addActionListener(e -> openConfigExternal());

        // 模板编辑区
        JPanel tpl = new JPanel(new BorderLayout(8, 4));
        tpl.setBorder(BorderFactory.createTitledBorder(
                "模板内容（可直接在下方修改，点“保存模板”写入配置文件）"));
        templateText = new JTextArea(5, 40);
        templateText.setFont(UI_FONT);
        templateText.setLineWrap(true);
        templateText.setWrapStyleWord(true);
        JScrollPane tplScroll = new JScrollPane(templateText);
        tplScroll.setBorder(BorderFactory.createEmptyBorder(8, 8, 0, 8));
        tpl.add(tplScroll, BorderLayout.CENTER);
        JPanel tplBottom = new JPanel(new BorderLayout(4, 0));
        tplBottom.setBorder(BorderFactory.createEmptyBorder(4, 8, 8, 8));
        JButton saveTplBtn = new JButton("保存模板");
        JLabel tplHint = new JLabel(
                "提示：数字用{{变量名}}，支持运算符{{变量1*变量2}}；日期变量用{{今日年月日}}/{{昨日年月日}}；字符串用[[字符串]]");
        tplHint.setForeground(Color.GRAY);
        tplBottom.add(tplHint, BorderLayout.WEST);
        tplBottom.add(saveTplBtn, BorderLayout.EAST);
        tpl.add(tplBottom, BorderLayout.SOUTH);
        addRow(gc, 1, 0, GridBagConstraints.HORIZONTAL, tpl);
        saveTplBtn.addActionListener(e -> saveTemplate());

        // 变量输入区
        variablePanel = new InputPanel("变量值输入（留空按 0 处理）", false);
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

    private void loadConfig() {
        try {
            configStore.ensureDefaultConfig();
        } catch (IOException e) {
            // 忽略：配置目录可能不可写，后续读取/保存再报错
        }
        String template = configStore.readTemplate();
        if (template == null) {
            template = "";
            setStatus("配置里没有找到 template 行，模板为空。");
        } else {
            setStatus("配置已加载：" + configStore.configFile());
        }
        currentTemplate = template;
        templateText.setText(template);
        rebuildInputs(template);
    }

    private void saveTemplate() {
        syncTemplate();
        try {
            configStore.writeTemplate(currentTemplate);
        } catch (IOException e) {
            JOptionPane.showMessageDialog(this, "无法写入配置文件：\n" + e,
                    "保存失败", JOptionPane.ERROR_MESSAGE);
            return;
        }
        setStatus("模板已保存到配置文件。");
        JOptionPane.showMessageDialog(this, "模板已保存到配置文件。", "已保存",
                JOptionPane.INFORMATION_MESSAGE);
    }

    /** 按模板一次性重建变量/字符串输入框（尽量保留已有输入、用上次保存值回填）。 */
    private void rebuildInputs(String template) {
        TemplateParser.ParsedTemplate parsed = TemplateParser.parse(template);
        variablePanel.rebuild(parsed.inputVariables(), !parsed.autoVariables().isEmpty(),
                variablePanel.getValues(), lastValues);
        stringPanel.rebuild(parsed.stringVariables(), stringPanel.getValues(), lastValues);
    }

    /** 收集当前所有输入的值（变量 + 字符串；字符串用 [[名字]] 作键避免冲突）。 */
    private Map<String, String> currentInputs() {
        Map<String, String> data = new LinkedHashMap<>(variablePanel.getValues());
        for (Map.Entry<String, String> e : stringPanel.getValues().entrySet()) {
            data.put("[[" + e.getKey() + "]]", e.getValue());
        }
        return data;
    }

    private void saveLastInputs() {
        try {
            lastValues = currentInputs();
            valuesStore.save(lastValues);
        } catch (Exception e) {
            // 忽略：存档失败不影响主流程
        }
    }

    // ---------- 动作 ----------

    private void generate() {
        syncTemplate();
        String template = currentTemplate;
        TemplateParser.ParsedTemplate parsed = TemplateParser.parse(template);
        List<String> names = parsed.inputVariables();
        List<String> autoNames = parsed.autoVariables();
        int exprCount = parsed.expressionCount();
        List<String> strNames = parsed.stringVariables();

        LocalDate today = LocalDate.now();
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
            return;
        }
        variablePanel.markAllValid();

        Map<String, String> autoVals = autoValues(today);
        Map<String, String> stringValues = stringPanel.getValues();
        TemplateRenderer.RenderResult result =
                TemplateRenderer.render(template, values, autoVals, stringValues);
        if (result.hasError()) {
            JOptionPane.showMessageDialog(this, result.error(),
                    "表达式计算失败", JOptionPane.WARNING_MESSAGE);
            setStatus("表达式计算失败，未生成结果。");
            return;
        }
        resultPanel.setText(result.result());

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
        setStatus(msg);
        saveLastInputs();
    }

    /** 根据系统日期生成自动变量的替换值。今日… 取当天，昨日… 取前一天。 */
    private static Map<String, String> autoValues(LocalDate now) {
        LocalDate yesterday = now.minusDays(1);
        Map<String, String> map = new LinkedHashMap<>();
        map.put("今日年", now.getYear() + "年");
        map.put("今日年月", now.getYear() + "年" + now.getMonthValue() + "月");
        map.put("今日年月日", now.getYear() + "年" + now.getMonthValue() + "月" + now.getDayOfMonth() + "日");
        map.put("昨日年", yesterday.getYear() + "年");
        map.put("昨日年月", yesterday.getYear() + "年" + yesterday.getMonthValue() + "月");
        map.put("昨日年月日", yesterday.getYear() + "年" + yesterday.getMonthValue() + "月" + yesterday.getDayOfMonth() + "日");
        return map;
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
        JFileChooser chooser = new JFileChooser();
        chooser.setCurrentDirectory(java.nio.file.Paths.get("").toAbsolutePath().toFile());
        chooser.setDialogTitle("保存结果");
        chooser.setSelectedFile(new File(TemplateConstants.RESULT_FILENAME));
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        File file = chooser.getSelectedFile();
        try {
            TextFileWriter.writeText(file.toPath(), resultPanel.getText());
        } catch (IOException e) {
            JOptionPane.showMessageDialog(this, "无法写入文件：\n" + e,
                    "保存失败", JOptionPane.ERROR_MESSAGE);
            return;
        }
        setStatus("结果已保存到：" + file.getAbsolutePath());
    }

    private void openConfigExternal() {
        try {
            configStore.ensureDefaultConfig();
        } catch (IOException e) {
            JOptionPane.showMessageDialog(this, "无法打开配置文件：\n" + e,
                    "无法打开", JOptionPane.ERROR_MESSAGE);
            return;
        }
        File config = configStore.configFile().toFile();
        try {
            if (Desktop.isDesktopSupported()) {
                Desktop.getDesktop().open(config);
            } else {
                throw new IOException("Desktop 不可用");
            }
        } catch (Exception e) {
            // Desktop 打开失败时回退到系统自带的文本编辑器
            try {
                String os = System.getProperty("os.name", "").toLowerCase();
                if (os.contains("win")) {
                    new ProcessBuilder("notepad.exe", config.getAbsolutePath()).start();
                } else if (os.contains("mac")) {
                    new ProcessBuilder("open", config.getAbsolutePath()).start();
                } else {
                    new ProcessBuilder("xdg-open", config.getAbsolutePath()).start();
                }
            } catch (Exception e2) {
                JOptionPane.showMessageDialog(this, "无法打开配置文件：\n" + e2,
                        "无法打开", JOptionPane.ERROR_MESSAGE);
                return;
            }
        }
        setStatus("已在系统编辑器中打开配置文件，修改保存后点“重新加载”。");
    }

    private void setStatus(String text) {
        statusLabel.setText(text);
    }
}
