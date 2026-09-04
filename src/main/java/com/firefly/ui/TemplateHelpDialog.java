package com.firefly.ui;

import com.firefly.TemplateConstants;
import com.firefly.core.ExpressionEvaluator;
import com.firefly.core.TemplateParser;
import com.firefly.core.TemplateSyntax;
import com.firefly.core.VariableInputState;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableCellRenderer;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

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
        tabs.addTab("运算说明", textPage(calculationHelpText()));
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
        current.setDefaultRenderer(Object.class, new WrappingCellRenderer());
        UiFontManager.updateTableRowHeight(current);
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
        Map<String, Integer> counts = occurrenceCounts(template, states);
        currentModel.setRowCount(0);
        for (VariableInputState state : states.values()) {
            currentModel.addRow(new Object[]{state.name(), state.type(),
                    "{{ }}", state.numericLocked() ? "是" : "否", state.numericLocked() ? "数值" : "—",
                    counts.getOrDefault(state.name(), 0)});
        }
        for (String auto : parsed.autoVariables()) {
            boolean numeric = TemplateConstants.AUTO_NUMERIC_VAR_SET.contains(auto);
            currentModel.addRow(new Object[]{auto, numeric ? "自动数值" : "自动日期", "{{ }}",
                    numeric ? "可参与" : "不可参与", "自动",
                    counts.getOrDefault(auto, 0)});
        }
        for (TemplateSyntax.Placeholder placeholder : TemplateSyntax.placeholders(template)) {
            if (placeholder.escaped()) continue;
            String content = placeholder.content();
            if (!TemplateParser.isExpression(content)) continue;
            String expression = content.substring(1).trim();
            List<String> dependencies;
            try { dependencies = ExpressionEvaluator.referencedVariables(expression); }
            catch (ExpressionEvaluator.EvalException e) { dependencies = List.of("语法错误"); }
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
        UiFontManager.registerReadingComponent(area, "TextArea.font");
        return new JScrollPane(area);
    }

    private static final class WrappingCellRenderer extends JTextArea implements TableCellRenderer {
        private WrappingCellRenderer() {
            setLineWrap(true);
            setWrapStyleWord(true);
            setOpaque(true);
            setBorder(BorderFactory.createEmptyBorder(3, 4, 3, 4));
        }

        @Override public Component getTableCellRendererComponent(JTable table, Object value,
                boolean selected, boolean focused, int row, int column) {
            setText(value == null ? "" : value.toString());
            setFont(table.getFont());
            setForeground(selected ? table.getSelectionForeground() : table.getForeground());
            setBackground(selected ? table.getSelectionBackground() : table.getBackground());
            setSize(Math.max(1, table.getColumnModel().getColumn(column).getWidth()), Short.MAX_VALUE);
            int height = Math.max(table.getFontMetrics(table.getFont()).getHeight() + 8,
                    getPreferredSize().height);
            if (table.getRowHeight(row) != height) table.setRowHeight(row, height);
            return this;
        }
    }

    private static String syntaxText() {
        return "模板变量语法\n\n"
                + "{{变量}}    推荐的普通变量格式，类型在主界面右侧选择。示例：{{天气}}\n\n"
                + "{{=表达式}} 数值表达式。示例：{{=数量*单价}}\n\n"
                + "\\{{变量}}   转义后的纯文本，最终输出 {{变量}}，不会创建或引用变量。\n\n"
                + "[变量名]    表达式内的显式变量引用；纯数字或特殊名称必须使用。示例：{{=[1]*[2]}}\n\n"
                + "• 数值变量留空按 0 处理。\n"
                + "• 短字符串原样替换。\n"
                + "• 多行文本保留换行。\n"
                + "• 短字符串和多行文本可以继续包含变量，并通过箭头展开下级变量。\n"
                + "• 表达式引用的变量会锁定为数值。\n"
                + "• {{上月天数}}、{{本月天数}}、{{下月天数}} 是自动数值变量，无需填写且可以参与运算。\n"
                + "• 在变量值输入框按 Tab 可跳到下一变量值。\n"
                + "• 同名变量只需填写一次。";
    }

    private static String calculationHelpText() {
        return "常用表达式写法\n\n"
                + "表达式写在 {{= 和 }} 之间，例如：{{=数量*单价}}\n\n"
                + "基本运算\n"
                + "{{=数量+赠品数量}}       加法\n"
                + "{{=原价-优惠金额}}       减法\n"
                + "{{=数量*单价}}           乘法\n"
                + "{{=总金额/人数}}         除法\n"
                + "{{=长度**2}}             乘方（长度的平方）\n"
                + "{{=(原价-优惠金额)*数量}} 使用括号指定先算的部分\n\n"
                + "百分数\n"
                + "{{=金额*5%}}             金额的 5%\n"
                + "{{=原价*(1-折扣率%)}}    输入 20 时表示打八折\n"
                + "{{=(数量+赠品数量)*10%}} 括号结果的 10%\n"
                + "百分号是后缀运算符，5% 等于 0.05；为避免歧义，复杂写法建议加括号。\n\n"
                + "如果从 Excel 取得的税率值是 0.2，应直接写 税率；如果填写的是 20，才写 税率%。\n\n"
                + "变量名称\n"
                + "{{=数量*单价}}           普通名称可直接引用\n"
                + "{{=[1]*[2]}}             纯数字变量名必须放在方括号内\n"
                + "{{=[销售 数量]*[含税单价]}} 含空格或特殊字符的名称使用方括号\n\n"
                + "当前计算规则\n"
                + "• 先算括号内的内容，再依次处理百分号、乘方、乘除和加减。\n"
                + "• 同一优先级的加减、乘除按从左到右计算。\n"
                + "• 乘方使用 **，例如 2**3 的结果是 8。\n"
                + "• 百分号直接作用于它前面的数值、变量或括号结果。\n"
                + "• 表达式引用的变量固定为数值类型，留空按 0 处理。\n"
                + "• 自动数值变量 {{上月天数}}、{{本月天数}}、{{下月天数}} 可直接参与运算，例如 {{=上月天数+本月天数+下月天数}}。\n"
                + "• 除数为 0、变量内容不是有效数字或表达式语法错误时，不会生成结果。\n"
                + "• 最终结果按照主界面设置的小数位数统一四舍五入并补足末尾零。";
    }

    public static String dateHelpText() {
        List<String> names = new ArrayList<>(TemplateConstants.AUTO_DATE_VAR_SET);
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
        if (name.endsWith("年月日")) return "对应日期（X年X月X日）";
        if (name.endsWith("年月")) return "对应月份（X年X月）";
        if (name.endsWith("月首")) return "对应月份第一天（X月X日）";
        if (name.endsWith("月末")) return "对应月份最后一天（X月X日）";
        if (name.equals("今日") || name.equals("昨日") || name.equals("明日"))
            return "对应日期的日（X日）";
        if (name.equals("本月") || name.equals("上月") || name.equals("下月"))
            return "对应月份（X月）";
        if (name.endsWith("年") || name.equals("本年") || name.equals("上年") || name.equals("下年")) return "对应年份（X年）";
        return "由基准日期自动计算";
    }

    private static Map<String, Integer> occurrenceCounts(String template,
                                                         Map<String, VariableInputState> states) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        countOccurrences(template, counts);
        for (VariableInputState state : states.values()) {
            if (state.type() != com.firefly.core.VariableType.NUMBER) countOccurrences(state.value(), counts);
        }
        return counts;
    }

    private static void countOccurrences(String template, Map<String, Integer> counts) {
        for (TemplateSyntax.Placeholder placeholder : TemplateSyntax.placeholders(template)) {
            if (placeholder.escaped()) continue;
            String content = placeholder.content();
            if (content.isEmpty() || TemplateParser.isExpression(content)) continue;
            counts.merge(content, 1, Integer::sum);
        }
    }
}
