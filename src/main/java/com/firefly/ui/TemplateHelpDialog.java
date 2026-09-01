package com.firefly.ui;

import com.firefly.TemplateConstants;
import com.firefly.core.TemplateParser;
import com.firefly.core.VariableInputState;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.regex.Matcher;

/** 可复用、非模态的模板语法与当前变量帮助窗口。 */
public final class TemplateHelpDialog extends JDialog {
    private final Supplier<String> templateSupplier;
    private final Supplier<Map<String, VariableInputState>> statesSupplier;
    private final DefaultTableModel currentModel = new DefaultTableModel(
            new String[]{"名称/表达式", "类型", "语法", "表达式", "锁定", "次数"}, 0) {
        @Override public boolean isCellEditable(int row, int column) { return false; }
    };
    private boolean positioned;
    private Component previousFocus;

    public TemplateHelpDialog(Window owner, Supplier<String> templateSupplier,
                              Supplier<Map<String, VariableInputState>> statesSupplier) {
        super(owner, "模板变量帮助", Dialog.ModalityType.MODELESS);
        this.templateSupplier = templateSupplier;
        this.statesSupplier = statesSupplier;
        setDefaultCloseOperation(HIDE_ON_CLOSE);
        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("语法说明", textPage(syntaxText()));
        tabs.addTab("日期变量", textPage(dateHelpText()));
        JTable current = new JTable(currentModel) {
            @Override public String getToolTipText(java.awt.event.MouseEvent event) {
                int row = rowAtPoint(event.getPoint()), column = columnAtPoint(event.getPoint());
                if (row < 0 || column < 0) return null;
                Object value = getValueAt(row, column);
                return value == null ? null : value.toString();
            }
        };
        current.setAutoCreateRowSorter(true);
        current.setFillsViewportHeight(true);
        current.setAutoResizeMode(JTable.AUTO_RESIZE_LAST_COLUMN);
        tabs.addTab("当前模板变量", new JScrollPane(current));
        add(tabs);
        getRootPane().registerKeyboardAction(e -> setVisible(false),
                KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
                JComponent.WHEN_IN_FOCUSED_WINDOW);
        setSize(720, 560);
        setResizable(true);
        addComponentListener(new java.awt.event.ComponentAdapter() {
            @Override public void componentHidden(java.awt.event.ComponentEvent e) {
                if (previousFocus != null) previousFocus.requestFocusInWindow();
            }
        });
    }

    public void showOrRefresh() {
        previousFocus = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
        refreshCurrentTemplate();
        if (!positioned) {
            setLocationRelativeTo(getOwner());
            positioned = true;
        }
        setVisible(true);
        toFront();
        requestFocus();
    }

    public void refreshIfVisible() { if (isVisible()) refreshCurrentTemplate(); }

    public void refreshCurrentTemplate() {
        String template = templateSupplier.get();
        TemplateParser.ParsedTemplate parsed = TemplateParser.parse(template);
        Map<String, VariableInputState> states = statesSupplier.get();
        Map<String, Integer> counts = occurrenceCounts(template);
        currentModel.setRowCount(0);
        for (TemplateParser.VariableSpec spec : parsed.variables()) {
            VariableInputState state = states.get(spec.name());
            String syntax = spec.braceSyntax() && spec.legacySyntax() ? "{{ }} + [[ ]]"
                    : (spec.legacySyntax() ? "[[ ]]" : "{{ }}");
            currentModel.addRow(new Object[]{spec.name(), state == null ? spec.defaultType() : state.type(),
                    syntax, spec.numericLocked() ? "是" : "否", spec.numericLocked() ? "数值" : "—",
                    counts.getOrDefault(spec.name(), 0)});
        }
        for (String auto : parsed.autoVariables()) {
            currentModel.addRow(new Object[]{auto, "自动填充", "{{ }}", "否", "—",
                    counts.getOrDefault(auto, 0)});
        }
        Matcher matcher = TemplateConstants.PLACEHOLDER_RE.matcher(template);
        while (matcher.find()) {
            String content = matcher.group(1).trim();
            if (!TemplateParser.isExpression(content)) continue;
            String expression = content.substring(1).trim();
            List<String> dependencies = new ArrayList<>();
            Matcher identifiers = TemplateConstants.IDENT_RE.matcher(expression);
            while (identifiers.find()) dependencies.add(identifiers.group());
            currentModel.addRow(new Object[]{"=" + expression, "表达式", "{{= }}",
                    String.join("、", dependencies), "依赖项锁定", 1});
        }
        if (currentModel.getRowCount() == 0) {
            currentModel.addRow(new Object[]{"当前模板没有变量", "—", "—", "—", "—", 0});
        }
    }

    private static JComponent textPage(String text) {
        JTextArea area = new JTextArea(text);
        area.setEditable(false);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        area.setCaretPosition(0);
        return new JScrollPane(area);
    }

    private static String syntaxText() {
        return "模板变量语法\n\n"
                + "{{变量}}    推荐的普通变量格式，类型在主界面右侧选择。示例：{{天气}}\n\n"
                + "[[变量]]    旧模板兼容格式，未保存类型时默认多行文本。示例：[[备注]]\n\n"
                + "{{=表达式}} 数值表达式。示例：{{=数量*单价}}\n\n"
                + "• 数值变量留空按 0 处理。\n"
                + "• 短字符串原样替换。\n"
                + "• 多行文本保留换行。\n"
                + "• 表达式引用的变量会锁定为数值。\n"
                + "• 同名变量只需填写一次。";
    }

    public static String dateHelpText() {
        List<String> names = new ArrayList<>(TemplateConstants.AUTO_VAR_SET);
        names.sort(Comparator.comparingInt(TemplateHelpDialog::dateCategory).thenComparing(String::compareTo));
        StringBuilder text = new StringBuilder("所有日期变量均以主界面选择的基准日期为准。\n");
        int lastCategory = -1;
        for (String name : names) {
            int category = dateCategory(name);
            if (category != lastCategory) {
                text.append("\n").append(categoryTitle(category)).append("\n");
                lastCategory = category;
            }
            text.append("{{").append(name).append("}}  —  ").append(dateMeaning(name)).append("\n");
        }
        return text.toString();
    }

    private static int dateCategory(String name) {
        if (name.contains("今日") || name.contains("昨日") || name.contains("明日")) return 0;
        if (name.contains("本月") || name.contains("上月") || name.contains("下月"))
            return name.contains("月首") || name.contains("月末") ? 3 : 1;
        return 2;
    }
    private static String categoryTitle(int category) {
        return switch (category) { case 0 -> "今日 / 昨日 / 明日"; case 1 -> "本月 / 上月 / 下月";
            case 2 -> "本年 / 上年 / 下年"; default -> "月首 / 月末"; };
    }
    private static String dateMeaning(String name) {
        if (name.endsWith("年月日")) return "对应日期（年-月-日）";
        if (name.endsWith("年月")) return "对应月份（年-月）";
        if (name.endsWith("月首")) return "基准月份第一天";
        if (name.endsWith("月末")) return "基准月份最后一天";
        if (name.endsWith("年") || name.equals("本年") || name.equals("上年") || name.equals("下年")) return "对应年份";
        return "由基准日期自动计算";
    }

    private static Map<String, Integer> occurrenceCounts(String template) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        Matcher matcher = TemplateConstants.ALL_PLACEHOLDER_RE.matcher(template);
        while (matcher.find()) {
            String whole = matcher.group(2) != null ? matcher.group(2) : matcher.group(1);
            String content = whole.substring(2, whole.length() - 2).trim();
            if (content.isEmpty() || TemplateParser.isExpression(content)) continue;
            counts.merge(content, 1, Integer::sum);
        }
        return counts;
    }
}
