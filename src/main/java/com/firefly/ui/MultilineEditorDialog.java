package com.firefly.ui;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.undo.UndoManager;
import java.awt.BorderLayout;
import java.awt.Dialog;
import java.awt.FlowLayout;
import java.awt.Window;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;

/** 模态多行文本编辑器；只有明确确认才返回新内容。 */
public final class MultilineEditorDialog extends JDialog {
    private final JTextArea textArea = new JTextArea();
    private String result;

    private MultilineEditorDialog(Window owner, String variableName, String initialValue) {
        super(owner, "编辑多行文本：" + variableName, Dialog.ModalityType.APPLICATION_MODAL);
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        setLayout(new BorderLayout(8, 8));
        ((JComponent) getContentPane()).setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        textArea.setText(initialValue);
        textArea.setLineWrap(true);
        textArea.setWrapStyleWord(true);
        UndoManager undo = new UndoManager();
        textArea.getDocument().addUndoableEditListener(undo);
        textArea.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_Z,
                KeyEvent.CTRL_DOWN_MASK), "undo");
        textArea.getActionMap().put("undo", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) { if (undo.canUndo()) undo.undo(); }
        });
        textArea.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_Y,
                KeyEvent.CTRL_DOWN_MASK), "redo");
        textArea.getActionMap().put("redo", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) { if (undo.canRedo()) undo.redo(); }
        });
        add(new JScrollPane(textArea), BorderLayout.CENTER);

        JButton ok = new JButton("确定");
        JButton cancel = new JButton("取消");
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        buttons.add(ok);
        buttons.add(cancel);
        add(buttons, BorderLayout.SOUTH);
        ok.addActionListener(e -> accept());
        cancel.addActionListener(e -> dispose());
        getRootPane().setDefaultButton(ok);
        getRootPane().registerKeyboardAction(e -> accept(),
                KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, KeyEvent.CTRL_DOWN_MASK),
                JComponent.WHEN_IN_FOCUSED_WINDOW);
        getRootPane().registerKeyboardAction(e -> dispose(),
                KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
                JComponent.WHEN_IN_FOCUSED_WINDOW);

        setSize(650, 450);
        setLocationRelativeTo(owner);
        SwingUtilities.invokeLater(() -> textArea.requestFocusInWindow());
    }

    private void accept() {
        result = textArea.getText();
        dispose();
    }

    /** 返回确认后的完整文本；取消或关闭窗口返回 null。 */
    public static String edit(Window owner, String variableName, String initialValue) {
        MultilineEditorDialog dialog = new MultilineEditorDialog(owner, variableName, initialValue);
        dialog.setVisible(true);
        return dialog.result;
    }
}
