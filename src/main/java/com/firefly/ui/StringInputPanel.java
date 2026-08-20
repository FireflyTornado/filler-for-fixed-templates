package com.firefly.ui;

import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 「字符串输入」区：为模板里每个 [[字符串]] 生成一个较大的多行输入框。
 * 输入内容原样输出（含换行、空格、格式），不参与数值校验。
 */
public final class StringInputPanel extends JPanel {

    private static final Color HINT_FG = new Color(0x99, 0x99, 0x99);

    private final ScrollablePanel inner = new ScrollablePanel(new GridBagLayout());
    private final Map<String, JTextArea> areas = new LinkedHashMap<>();

    public StringInputPanel() {
        super(new BorderLayout());
        setBorder(BorderFactory.createTitledBorder(
                "字符串输入（内容原样输出到模板的 [[字符串]]，支持换行/空格/格式）"));
        JScrollPane scroll = new JScrollPane(inner);
        scroll.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 0));
        scroll.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED);
        add(scroll, BorderLayout.CENTER);
    }

    /**
     * 按模板中的字符串变量列表重建输入框。
     *
     * @param names     字符串变量名（出现顺序去重）
     * @param current   当前输入框里已有的值（模板改动时保留）
     * @param persisted 上次保存的值（用 [[名字]] 作为键）
     */
    public void rebuild(List<String> names,
                        Map<String, String> current, Map<String, String> persisted) {
        inner.removeAll();
        areas.clear();

        GridBagConstraints gc = new GridBagConstraints();
        gc.insets = new Insets(4, 10, 4, 10);
        gc.anchor = GridBagConstraints.WEST;

        if (names.isEmpty()) {
            gc.gridx = 0;
            gc.gridy = 0;
            gc.weightx = 1;
            gc.fill = GridBagConstraints.HORIZONTAL;
            gc.gridwidth = GridBagConstraints.REMAINDER;
            JLabel hint = new JLabel("模板中没有 [[字符串]] 占位符，此处留空。");
            hint.setForeground(HINT_FG);
            inner.add(hint, gc);
        } else {
            int row = 0;
            for (String name : names) {
                JLabel label = new JLabel("[[" + name + "]]");
                label.setPreferredSize(new Dimension(170, 24));
                label.setForeground(new Color(0x44, 0x44, 0x44));
                gc.gridy = row;
                gc.gridx = 0;
                gc.gridwidth = 1;
                gc.weightx = 0;
                gc.fill = GridBagConstraints.NONE;
                inner.add(label, gc);

                JTextArea area = new JTextArea(4, 20);
                area.setLineWrap(true);
                area.setWrapStyleWord(true);
                JScrollPane areaScroll = new JScrollPane(area);
                areaScroll.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED);
                areaScroll.setPreferredSize(new Dimension(120, 82));
                gc.gridx = 1;
                gc.weightx = 1;
                gc.fill = GridBagConstraints.BOTH;
                inner.add(areaScroll, gc);

                String value = current.get(name);
                if (value == null) {
                    value = persisted.get("[[" + name + "]]");
                }
                if (value != null) {
                    area.setText(value);
                }
                areas.put(name, area);
                row++;
            }
        }
        inner.revalidate();
        inner.repaint();
    }

    /** 当前所有输入框的值（原始文本）。 */
    public Map<String, String> getValues() {
        Map<String, String> values = new LinkedHashMap<>();
        for (Map.Entry<String, JTextArea> e : areas.entrySet()) {
            values.put(e.getKey(), e.getValue().getText());
        }
        return values;
    }
}
