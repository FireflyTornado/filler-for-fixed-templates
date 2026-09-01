package com.firefly.ui;

import javax.swing.BorderFactory;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import java.awt.BorderLayout;

/**
 * 「结果输出」区：只读的多行文本，展示生成结果。
 */
public final class ResultPanel extends JPanel {

    private final JTextArea area = new JTextArea();

    public ResultPanel() {
        super(new BorderLayout());
        setBorder(BorderFactory.createTitledBorder("生成结果"));
        area.setEditable(false);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        JScrollPane scroll = new JScrollPane(area);
        add(scroll, BorderLayout.CENTER);
    }

    public String getText() {
        return area.getText();
    }

    public void setText(String text) {
        area.setText(text);
        area.setCaretPosition(0);
    }
}
