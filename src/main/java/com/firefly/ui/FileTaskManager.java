package com.firefly.ui;

import javax.swing.*;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;

/** 执行并汇总通用后台文件任务；所有状态变更与回调都发生在 Swing 界面线程。 */
public final class FileTaskManager {
    public enum LockScope { NONE, TEMPLATE }

    @FunctionalInterface
    public interface Work<T> {
        T run(ProgressReporter reporter) throws Exception;
    }

    public interface ProgressReporter {
        void update(String phase, long completed, long total);
        boolean isCancelled();

        default void checkpoint() {
            if (isCancelled() || Thread.currentThread().isInterrupted()) {
                throw new CancellationException();
            }
        }
    }

    private final FileTaskProgressPanel panel;
    private final Map<Long, RunningTask> active = new LinkedHashMap<>();
    private long nextId;
    private Consumer<Boolean> templateLockListener = locked -> { };

    public FileTaskManager(FileTaskProgressPanel panel) {
        this.panel = panel;
        panel.setCancelAction(this::cancelPrimaryTask);
    }

    public void setTemplateLockListener(Consumer<Boolean> listener) {
        templateLockListener = listener == null ? locked -> { } : listener;
    }

    public boolean hasActiveTasks() { return !active.isEmpty(); }
    public boolean hasNonCancellableTasks() {
        return active.values().stream().anyMatch(task -> !task.cancellable);
    }
    public boolean hasTask(String kind) {
        return active.values().stream().anyMatch(task -> task.kind.equals(kind));
    }
    public boolean hasTemplateLock() {
        return active.values().stream().anyMatch(task -> task.scope == LockScope.TEMPLATE);
    }

    public void cancelKind(String kind) {
        new ArrayList<>(active.values()).stream()
                .filter(task -> task.kind.equals(kind) && task.cancellable)
                .forEach(task -> task.worker.cancel(true));
    }

    public void cancelAll() {
        new ArrayList<>(active.values()).stream()
                .filter(task -> task.cancellable)
                .forEach(task -> task.worker.cancel(true));
    }

    public <T> long submit(String kind, String name, LockScope scope, boolean cancellable,
                           Work<T> work, Consumer<T> onSuccess,
                           Consumer<Throwable> onFailure, Runnable onCancelled) {
        if (!SwingUtilities.isEventDispatchThread()) {
            throw new IllegalStateException("文件任务必须从 Swing 界面线程提交");
        }
        long id = ++nextId;
        RunningTask task = new RunningTask(id, kind, name, scope, cancellable);
        SwingWorker<T, Void> worker = new SwingWorker<>() {
            @Override protected T doInBackground() throws Exception {
                ProgressReporter reporter = new ProgressReporter() {
                    @Override public void update(String phase, long completed, long total) {
                        SwingUtilities.invokeLater(() -> updateProgress(id, phase, completed, total));
                    }
                    @Override public boolean isCancelled() {
                        return Thread.currentThread().isInterrupted();
                    }
                };
                return work.run(reporter);
            }

            @Override protected void done() {
                RunningTask removed = active.remove(id);
                if (removed == null) return;
                refreshTemplateLock();
                boolean succeeded = false;
                boolean cancelled = false;
                try {
                    if (isCancelled()) {
                        cancelled = true;
                        if (onCancelled != null) onCancelled.run();
                    } else {
                        T result = get();
                        succeeded = true;
                        if (onSuccess != null) onSuccess.accept(result);
                    }
                } catch (CancellationException e) {
                    cancelled = true;
                    if (onCancelled != null) onCancelled.run();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    cancelled = true;
                    if (onCancelled != null) onCancelled.run();
                } catch (ExecutionException e) {
                    if (onFailure != null) onFailure.accept(e.getCause());
                } finally {
                    if (active.isEmpty()) {
                        panel.showFinished(cancelled ? "文件任务已取消"
                                : succeeded ? name + "完成" : name + "失败", succeeded);
                    } else {
                        refreshPanel();
                    }
                }
            }
        };
        task.worker = worker;
        active.put(id, task);
        refreshTemplateLock();
        refreshPanel();
        worker.execute();
        return id;
    }

    private void updateProgress(long id, String phase, long completed, long total) {
        RunningTask task = active.get(id);
        if (task == null) return;
        task.phase = phase == null || phase.isBlank() ? task.name : phase;
        task.completed = Math.max(0, completed);
        task.total = Math.max(0, total);
        refreshPanel();
    }

    private void cancelPrimaryTask() {
        List<RunningTask> tasks = new ArrayList<>(active.values());
        for (int i = tasks.size() - 1; i >= 0; i--) {
            RunningTask task = tasks.get(i);
            if (task.cancellable) {
                task.worker.cancel(true);
                return;
            }
        }
    }

    private void refreshTemplateLock() {
        templateLockListener.accept(hasTemplateLock());
    }

    private void refreshPanel() {
        if (active.isEmpty()) {
            panel.showIdle();
            return;
        }
        List<RunningTask> tasks = new ArrayList<>(active.values());
        RunningTask primary = tasks.get(tasks.size() - 1);
        boolean allDeterminate = tasks.stream().allMatch(task -> task.total > 0);
        int percent = 0;
        if (allDeterminate) {
            double totalPercent = 0;
            for (RunningTask task : tasks) {
                totalPercent += Math.min(1.0, (double) task.completed / task.total) * 100.0;
            }
            percent = (int) Math.round(totalPercent / tasks.size());
        }
        boolean cancellable = tasks.stream().anyMatch(task -> task.cancellable);
        String label = tasks.size() == 1 ? primary.phase : "正在执行 " + tasks.size() + " 项任务";
        String tooltip = taskTooltip(tasks);
        panel.showProgress(label, allDeterminate, percent, cancellable, tooltip);
    }

    private static String taskTooltip(List<RunningTask> tasks) {
        StringBuilder text = new StringBuilder("<html>");
        for (RunningTask task : tasks) {
            text.append(task.phase);
            if (task.total > 0) {
                int percent = (int) Math.round(Math.min(1.0,
                        (double) task.completed / task.total) * 100.0);
                text.append("：").append(percent).append('%');
            }
            text.append("<br>");
        }
        return text.append("</html>").toString();
    }

    private static final class RunningTask {
        final long id;
        final String kind;
        final String name;
        final LockScope scope;
        final boolean cancellable;
        String phase;
        long completed;
        long total;
        SwingWorker<?, ?> worker;

        RunningTask(long id, String kind, String name, LockScope scope, boolean cancellable) {
            this.id = id;
            this.kind = kind;
            this.name = name;
            this.scope = scope;
            this.cancellable = cancellable;
            this.phase = name;
        }
    }
}
