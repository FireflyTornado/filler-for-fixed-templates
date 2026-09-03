package com.firefly.bootstrap;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

/** The application window is created only by the successful completion callback. */
final class DependencyDialog extends JDialog {
    private final DependencyManager manager;
    private final Runnable ready;
    private final JLabel status = new JLabel("正在检查运行依赖…");
    private final JProgressBar total = new JProgressBar(0, 100);
    private final JProgressBar current = new JProgressBar(0, 100);
    private final JTextArea details = new JTextArea(4, 48);
    private final JButton retry = new JButton("重试");
    private final JButton cancel = new JButton("取消并退出");
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private boolean running;

    DependencyDialog(DependencyManager manager, Runnable ready) {
        super((Frame) null, "准备运行依赖", false);
        this.manager = manager;
        this.ready = ready;
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBorder(BorderFactory.createEmptyBorder(18, 20, 18, 20));
        panel.add(new JLabel("运行依赖尚未就绪，完成下载和校验后将自动打开程序。"));
        panel.add(Box.createVerticalStrut(14));
        panel.add(status);
        total.setStringPainted(true); current.setStringPainted(true);
        panel.add(Box.createVerticalStrut(8)); panel.add(total);
        panel.add(Box.createVerticalStrut(8)); panel.add(current);
        details.setEditable(false); details.setLineWrap(true); details.setWrapStyleWord(true);
        details.setOpaque(false); details.setText("下载来源：Maven Central\n本地依赖完整后可离线使用。已完成的下载会保留，取消不会打开主程序。");
        panel.add(Box.createVerticalStrut(12)); panel.add(new JScrollPane(details));
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        buttons.add(retry); buttons.add(cancel); panel.add(buttons);
        retry.addActionListener(e -> start());
        cancel.addActionListener(e -> cancel());
        setContentPane(panel); setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() { @Override public void windowClosing(WindowEvent e) { cancel(); } });
        pack(); setMinimumSize(getSize()); setLocationRelativeTo(null);
    }

    void start() {
        cancelled.set(false); running = true;
        retry.setEnabled(false); cancel.setEnabled(true);
        status.setText("正在检查运行依赖…"); current.setIndeterminate(true);
        new SwingWorker<Void, DependencyManager.Progress>() {
            private long lastUpdate;
            @Override protected Void doInBackground() throws Exception {
                manager.ensure(progress -> {
                    long now = System.nanoTime();
                    if (progress.bytes() == 0 || now - lastUpdate > 75_000_000) {
                        publish(progress); lastUpdate = now;
                    }
                }, cancelled::get);
                return null;
            }
            @Override protected void process(List<DependencyManager.Progress> updates) {
                if (cancelled.get()) return;
                DependencyManager.Progress p = updates.get(updates.size() - 1);
                status.setText(p.message());
                total.setValue(p.total() == 0 ? 0 : p.completed() * 100 / p.total());
                total.setString("已就绪 " + p.completed() + " / " + p.total());
                current.setIndeterminate(p.size() < 0);
                current.setValue(p.size() > 0 ? (int) Math.min(100, p.bytes() * 100 / p.size()) : 100);
                current.setString(p.size() > 0 ? String.format("%.1f / %.1f MB", p.bytes() / 1048576.0, p.size() / 1048576.0)
                        : p.bytes() > 0 ? String.format("已下载 %.1f MB", p.bytes() / 1048576.0) : "");
            }
            @Override protected void done() {
                running = false;
                if (cancelled.get()) { dispose(); return; }
                try { get(); dispose(); ready.run(); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); dispose(); }
                catch (CancellationException e) { dispose(); }
                catch (ExecutionException e) {
                    status.setText("依赖准备失败，程序尚未启动");
                    current.setIndeterminate(false); current.setString("未完成");
                    details.setText("原因：" + e.getCause().getMessage()
                            + "\n请检查网络连接及程序目录写入权限，然后重试。也可复制完整的 lib 文件夹后重试。");
                    retry.setEnabled(true);
                }
            }
        }.execute();
    }

    private void cancel() {
        if (!running) { dispose(); return; }
        cancelled.set(true); cancel.setEnabled(false);
        status.setText("正在取消并清理未完成的下载，请稍候…");
    }
}
