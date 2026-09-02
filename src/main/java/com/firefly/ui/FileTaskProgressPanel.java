package com.firefly.ui;

import javax.swing.*;
import java.awt.*;

/** 状态栏中固定尺寸的通用文件任务进度区。 */
public final class FileTaskProgressPanel extends JPanel {
    private static final Dimension PANEL_SIZE = new Dimension(390, 30);
    private static final Dimension LABEL_SIZE = new Dimension(150, 24);
    private static final Dimension BAR_SIZE = new Dimension(145, 18);
    private static final Dimension CANCEL_SIZE = new Dimension(76, 24);

    private final JLabel taskLabel = new JLabel("文件任务：空闲");
    private final JProgressBar progressBar = new JProgressBar(0, 100);
    private final JButton cancelButton = new JButton("取消");
    private final Timer idleTimer;
    private Runnable cancelAction = () -> { };

    public FileTaskProgressPanel() {
        super(new FlowLayout(FlowLayout.LEFT, 5, 1));
        setPreferredSize(PANEL_SIZE);
        setMinimumSize(PANEL_SIZE);
        setMaximumSize(PANEL_SIZE);

        fixSize(taskLabel, LABEL_SIZE);
        fixSize(progressBar, BAR_SIZE);
        fixSize(cancelButton, CANCEL_SIZE);
        progressBar.setStringPainted(true);
        progressBar.setString("空闲");
        cancelButton.setMargin(new Insets(1, 8, 1, 8));
        cancelButton.setEnabled(false);
        cancelButton.setToolTipText("取消当前文件任务");
        cancelButton.addActionListener(e -> cancelAction.run());
        add(taskLabel);
        add(progressBar);
        add(cancelButton);

        idleTimer = new Timer(1200, e -> showIdle());
        idleTimer.setRepeats(false);
    }

    public void setCancelAction(Runnable action) {
        cancelAction = action == null ? () -> { } : action;
    }

    public void showProgress(String label, boolean determinate, int percent,
                             boolean cancellable, String tooltip) {
        idleTimer.stop();
        taskLabel.setText(label == null || label.isBlank() ? "文件任务" : label);
        taskLabel.setToolTipText(tooltip);
        progressBar.setToolTipText(tooltip);
        progressBar.setIndeterminate(!determinate);
        if (determinate) {
            int value = Math.max(0, Math.min(100, percent));
            progressBar.setValue(value);
            progressBar.setString(value + "%");
        } else {
            progressBar.setString("处理中");
        }
        cancelButton.setEnabled(cancellable);
    }

    public void showFinished(String text, boolean success) {
        taskLabel.setText(text == null || text.isBlank() ? "文件任务完成" : text);
        taskLabel.setToolTipText(taskLabel.getText());
        progressBar.setIndeterminate(false);
        progressBar.setValue(success ? 100 : 0);
        progressBar.setString(success ? "完成" : "未完成");
        cancelButton.setEnabled(false);
        idleTimer.restart();
    }

    public void showIdle() {
        idleTimer.stop();
        taskLabel.setText("文件任务：空闲");
        taskLabel.setToolTipText(null);
        progressBar.setToolTipText(null);
        progressBar.setIndeterminate(false);
        progressBar.setValue(0);
        progressBar.setString("空闲");
        cancelButton.setEnabled(false);
    }

    private static void fixSize(JComponent component, Dimension size) {
        component.setPreferredSize(size);
        component.setMinimumSize(size);
        component.setMaximumSize(size);
    }
}
