package com.firefly.core;

/**
 * 文件后台任务在状态栏中使用的统一文案。
 * 业务代码只选择操作类型，不再自行拼写文件格式相关的状态文本。
 */
public enum FileOperationText {
    INITIALIZE("初始化文件"),
    LOAD_TEMPLATE("加载模板"),
    IMPORT_TEMPLATE("导入模板"),
    SAVE_TEMPLATE("保存模板"),
    CONVERT_TEMPLATE("转换模板"),
    GENERATE_RESULT("生成结果"),
    SAVE_RESULT("保存结果");

    private final String action;

    FileOperationText(String action) {
        this.action = action;
    }

    /** 任务列表和完成提示使用的简短名称。 */
    public String taskName() {
        return action;
    }

    /** 任务执行期间显示在进度条旁的状态。 */
    public String inProgress() {
        return "正在" + action;
    }
}
