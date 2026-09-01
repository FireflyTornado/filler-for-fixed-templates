package com.firefly.ui;

import com.firefly.core.ValueNormalizer;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.BorderLayout;
import java.awt.Dialog;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.awt.Window;
import java.awt.event.KeyEvent;

/** 有损类型转换必须经用户确认；取消和关闭均不改变变量。 */
public final class VariableTypeConversionDialog extends JDialog {
    private String result;

    private VariableTypeConversionDialog(Window owner, String title) {
        super(owner, title, Dialog.ModalityType.APPLICATION_MODAL);
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        getRootPane().registerKeyboardAction(e -> dispose(),
                KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
                JComponent.WHEN_IN_FOCUSED_WINDOW);
    }

    public static String toShortText(Window owner, String variable, String original, String proposed) {
        VariableTypeConversionDialog dialog = new VariableTypeConversionDialog(owner,
                "转换为短字符串：" + variable);
        JTextField target = new JTextField(proposed, 35);
        JPanel input = new JPanel(new BorderLayout(4, 4));
        input.add(new JLabel("转换后的单行内容："), BorderLayout.NORTH);
        input.add(target);
        dialog.build(original, input, "使用转换结果", "保持多行文本",
                true, () -> target.getText());
        dialog.setVisible(true);
        return dialog.result;
    }

    public static String toNumber(Window owner, String variable, String original) {
        VariableTypeConversionDialog dialog = new VariableTypeConversionDialog(owner,
                "转换为数值：" + variable);
        JTextField target = new JTextField(24);
        JPanel input = new JPanel(new BorderLayout(5, 5));
        input.add(new JLabel("请输入要用于计算的数值："), BorderLayout.NORTH);
        input.add(target);
        JButton use = dialog.build(original, input, "使用该数值", "保持原类型", false,
                () -> target.getText().trim());
        DocumentListener validation = new DocumentListener() {
            private void update() { use.setEnabled(ValueNormalizer.normalize(target.getText()) != null); }
            public void insertUpdate(DocumentEvent e) { update(); }
            public void removeUpdate(DocumentEvent e) { update(); }
            public void changedUpdate(DocumentEvent e) { update(); }
        };
        target.getDocument().addDocumentListener(validation);
        use.setEnabled(true); // 空值合法，生成时按 0。
        dialog.setVisible(true);
        return dialog.result;
    }

    private JButton build(String original, JComponent target, String useText, String keepText,
                          boolean useInitiallyEnabled,
                          java.util.function.Supplier<String> resultSupplier) {
        setLayout(new BorderLayout(8, 8));
        ((JComponent) getContentPane()).setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        JTextArea source = new JTextArea(original, 8, 42);
        source.setEditable(false);
        source.setLineWrap(true);
        source.setWrapStyleWord(true);
        UiFontManager.registerReadingComponent(source, "TextArea.font");
        JPanel content = new JPanel(new GridLayout(2, 1, 6, 6));
        JPanel sourcePanel = new JPanel(new BorderLayout(4, 4));
        sourcePanel.add(new JLabel("原始内容（将继续保留）："), BorderLayout.NORTH);
        sourcePanel.add(new JScrollPane(source));
        content.add(sourcePanel);
        content.add(target);
        add(content);
        JButton use = new JButton(useText);
        use.setEnabled(useInitiallyEnabled);
        JButton keep = new JButton(keepText);
        JButton cancel = new JButton("取消");
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        buttons.add(use); buttons.add(keep); buttons.add(cancel);
        add(buttons, BorderLayout.SOUTH);
        use.addActionListener(e -> { result = resultSupplier.get(); dispose(); });
        keep.addActionListener(e -> dispose());
        cancel.addActionListener(e -> dispose());
        setSize(620, 430);
        setLocationRelativeTo(getOwner());
        return use;
    }
}
