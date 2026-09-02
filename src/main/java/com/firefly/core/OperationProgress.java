package com.firefly.core;

/** 核心文件处理向界面报告阶段与完成量，不依赖 Swing。 */
@FunctionalInterface
public interface OperationProgress {
    OperationProgress NONE = (phase, completed, total) -> { };

    void update(String phase, long completed, long total);
}
