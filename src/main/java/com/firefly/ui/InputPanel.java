package com.firefly.ui;

import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.text.JTextComponent;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 「变量值输入」/「字符串输入」区的通用面板：
 * 变量模式为每个 {{变量名}} 生成一行输入框并做数字校验；
 * 字符串模式为每个 [[字符串]] 生成一个多行输入框，内容原样输出。
 */
public final class InputPanel extends JPanel {

    private static final Color ERROR_BG = new Color(0xff, 0xe3, 0xe3);
    private static final Color HINT_FG = new Color(0x88, 0x88, 0x88);
    private static final Color STRING_HINT_FG = new Color(0x99, 0x99, 0x99);
    private static final Color STRING_LABEL_FG = new Color(0x44, 0x44, 0x44);

    private final boolean stringMode;
    private final ScrollablePanel inner = new ScrollablePanel(new GridBagLayout());
    private final Map<String, JTextComponent> inputs = new LinkedHashMap<>();

    public InputPanel(String title, boolean stringMode) {
        super(new BorderLayout());
        this.stringMode = stringMode;
        setBorder(BorderFactory.createTitledBorder(title));
        JScrollPane scroll = new JScrollPane(inner);
        scroll.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 0));
        scroll.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED);
        add(scroll, BorderLayout.CENTER);
    }

    /**
     * 按模板解析出的名字列表重建输入框。
     *
     * @param names     名字（出现顺序去重）
     * @param hasAuto   模板里是否含自动日期变量（决定变量模式空列表时的提示文案）
     * @param current   当前输入框里已有的值（模板改动时保留）
     * @param persisted 上次保存的值（新出现的名字用它回填；字符串模式用 [[名字]] 作键）
     */
    public void rebuild(List<String> names, boolean hasAuto,
                        Map<String, String> current, Map<String, String> persisted) {
        inner.removeAll();
        inputs.clear();

        GridBagConstraints gc = new GridBagConstraints();
        gc.insets = stringMode ? new Insets(4, 10, 4, 10) : new Insets(3, 10, 3, 10);
        gc.anchor = GridBagConstraints.WEST;

        if (names.isEmpty()) {
            gc.gridx = 0;
            gc.gridy = 0;
            gc.weightx = 1;
            gc.fill = GridBagConstraints.HORIZONTAL;
            gc.gridwidth = GridBagConstraints.REMAINDER;
            String text = stringMode
                    ? "模板中没有 [[字符串]] 占位符，此处留空。"
                    : (hasAuto
                            ? "模板中没有需要填写的变量，但包含自动日期变量，选择所需日期后直接点“生成结果”即可。"
                            : "模板中没有找到 {{变量名}} 占位符，点“生成结果”会输出模板原文。");
            JLabel hint = new JLabel(text);
            hint.setForeground(stringMode ? STRING_HINT_FG : HINT_FG);
            inner.add(hint, gc);
        } else {
            int row = 0;
            for (String name : names) {
                JLabel label = new JLabel(stringMode ? "[[" + name + "]]" : name);
                label.setPreferredSize(new Dimension(stringMode ? 170 : 150, 24));
                if (stringMode) {
                    label.setForeground(STRING_LABEL_FG);
                }
                gc.gridy = row;
                gc.gridx = 0;
                gc.gridwidth = 1;
                gc.weightx = 0;
                gc.fill = GridBagConstraints.NONE;
                inner.add(label, gc);

                JTextComponent field = newComponent();
                gc.gridx = 1;
                gc.weightx = 1;
                if (stringMode) {
                    JTextArea area = (JTextArea) field;
                    area.setLineWrap(true);
                    area.setWrapStyleWord(true);
                    JScrollPane areaScroll = new JScrollPane(area);
                    areaScroll.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED);
                    areaScroll.setPreferredSize(new Dimension(120, 82));
                    gc.fill = GridBagConstraints.BOTH;
                    inner.add(areaScroll, gc);
                } else {
                    gc.fill = GridBagConstraints.HORIZONTAL;
                    inner.add(field, gc);
                }

                String value = current.get(name);
                if (value == null) {
                    value = persisted.get(stringMode ? "[[" + name + "]]" : name);
                }
                if (value != null) {
                    field.setText(value);
                }
                inputs.put(name, field);
                row++;
            }
            if (!stringMode) {
                JLabel hint = new JLabel("请填写数字（可含小数）；留空按 0 处理。");
                hint.setForeground(HINT_FG);
                gc.gridy = row;
                gc.gridx = 0;
                gc.weightx = 1;
                gc.gridwidth = GridBagConstraints.REMAINDER;
                gc.fill = GridBagConstraints.HORIZONTAL;
                inner.add(hint, gc);
            }
        }
        inner.revalidate();
        inner.repaint();
    }

    /** 字符串模式的便捷重载（无 hasAuto）。 */
    public void rebuild(List<String> names, Map<String, String> current, Map<String, String> persisted) {
        rebuild(names, false, current, persisted);
    }

    private JTextComponent newComponent() {
        return stringMode ? new JTextArea(4, 20) : new JTextField();
    }

    /** 当前所有输入框的值（原始文本）。 */
    public Map<String, String> getValues() {
        Map<String, String> values = new LinkedHashMap<>();
        for (Map.Entry<String, JTextComponent> e : inputs.entrySet()) {
            values.put(e.getKey(), e.getValue().getText());
        }
        return values;
    }

    /** 把给定变量对应的输入框标成浅红底（仅变量模式）。 */
    public void markInvalid(Collection<String> names) {
        if (stringMode) {
            return;
        }
        for (Map.Entry<String, JTextComponent> e : inputs.entrySet()) {
            JTextComponent field = e.getValue();
            field.setBackground(names.contains(e.getKey()) ? ERROR_BG : Color.WHITE);
            field.setOpaque(true);
        }
    }

    /** 清除所有错误高亮。 */
    public void markAllValid() {
        if (stringMode) {
            return;
        }
        for (JTextComponent field : inputs.values()) {
            field.setBackground(Color.WHITE);
            field.setOpaque(true);
        }
    }
}
