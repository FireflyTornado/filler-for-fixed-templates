package com.firefly.ui;

import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
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
 * 「变量值输入」区：为模板里每个 {{变量名}} 生成一行（标签 + 输入框）。
 * 支持模板改动后重建（尽量保留已有输入）、错误高亮。
 */
public final class VariableInputPanel extends JPanel {

    private static final Color ERROR_BG = new Color(0xff, 0xe3, 0xe3);
    private static final Color HINT_FG = new Color(0x88, 0x88, 0x88);

    private final ScrollablePanel inner = new ScrollablePanel(new GridBagLayout());
    private final Map<String, JTextField> fields = new LinkedHashMap<>();

    public VariableInputPanel() {
        super(new BorderLayout());
        setBorder(BorderFactory.createTitledBorder("变量值输入（留空按 0 处理）"));
        JScrollPane scroll = new JScrollPane(inner);
        scroll.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 0));
        scroll.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED);
        add(scroll, BorderLayout.CENTER);
    }

    /**
     * 按模板中的变量列表重建输入框。
     *
     * @param names     需要填写的变量名（出现顺序去重）
     * @param hasAuto   模板里是否含自动日期变量（决定空列表时的提示文案）
     * @param current   当前输入框里已有的值（模板改动时保留）
     * @param persisted 上次保存的值（新出现的变量用它回填）
     */
    public void rebuild(List<String> names, boolean hasAuto,
                        Map<String, String> current, Map<String, String> persisted) {
        inner.removeAll();
        fields.clear();

        GridBagConstraints gc = new GridBagConstraints();
        gc.insets = new Insets(3, 10, 3, 10);
        gc.anchor = GridBagConstraints.WEST;

        if (names.isEmpty()) {
            gc.gridx = 0;
            gc.gridy = 0;
            gc.weightx = 1;
            gc.fill = GridBagConstraints.HORIZONTAL;
            gc.gridwidth = GridBagConstraints.REMAINDER;
            String text = hasAuto
                    ? "模板中没有需要填写的变量，但包含自动日期变量（{{今日年月日}}/{{昨日年月日}}），直接点“生成结果”即可。"
                    : "模板中没有找到 {{变量名}} 占位符，点“生成结果”会输出模板原文。";
            JLabel hint = new JLabel(text);
            hint.setForeground(HINT_FG);
            inner.add(hint, gc);
        } else {
            int row = 0;
            for (String name : names) {
                JLabel label = new JLabel(name);
                label.setPreferredSize(new Dimension(150, 24));
                gc.gridy = row;
                gc.gridx = 0;
                gc.gridwidth = 1;
                gc.weightx = 0;
                gc.fill = GridBagConstraints.NONE;
                inner.add(label, gc);

                JTextField field = new JTextField();
                gc.gridx = 1;
                gc.weightx = 1;
                gc.fill = GridBagConstraints.HORIZONTAL;
                inner.add(field, gc);

                String value = current.get(name);
                if (value == null) {
                    value = persisted.get(name);
                }
                if (value != null) {
                    field.setText(value);
                }
                fields.put(name, field);
                row++;
            }
            JLabel hint = new JLabel("请填写数字（可含小数）；留空按 0 处理。");
            hint.setForeground(HINT_FG);
            gc.gridy = row;
            gc.gridx = 0;
            gc.weightx = 1;
            gc.gridwidth = GridBagConstraints.REMAINDER;
            gc.fill = GridBagConstraints.HORIZONTAL;
            inner.add(hint, gc);
        }
        inner.revalidate();
        inner.repaint();
    }

    /** 当前所有输入框的值（原始文本）。 */
    public Map<String, String> getValues() {
        Map<String, String> values = new LinkedHashMap<>();
        for (Map.Entry<String, JTextField> e : fields.entrySet()) {
            values.put(e.getKey(), e.getValue().getText());
        }
        return values;
    }

    /** 把给定变量对应的输入框标成浅红底 */
    public void markInvalid(Collection<String> names) {
        for (Map.Entry<String, JTextField> e : fields.entrySet()) {
            JTextField field = e.getValue();
            field.setBackground(names.contains(e.getKey()) ? ERROR_BG : Color.WHITE);
            field.setOpaque(true);
        }
    }

    /** 清除所有错误高亮。 */
    public void markAllValid() {
        for (JTextField field : fields.values()) {
            field.setBackground(Color.WHITE);
            field.setOpaque(true);
        }
    }
}
