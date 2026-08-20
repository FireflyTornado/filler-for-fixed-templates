# 模板填充工具（Template Filler Tool）

> **语言切换：[English](README.en.md)**

一个基于 Java Swing 的桌面小工具：将写好的固定模板填充成完整文本，并支持复制到剪贴板或保存为文件。

## 功能特性

- **自动生成输入框** → 模板中每一个`{{变量名}}`均会自动生成数字输入框；留空按 0 处理，输入非数字会给出提示
- **可添加字符串** → `[[字符串]]` 使用大文本输入框，内容原样输出（含换行、空格、格式）
- **支持运算符** → 如 `{{变量1/变量2}}`，支持 `+ - * / **` 和括号，结果保留两位小数
- **自动获取日期** → `{{年}}`、`{{年月}}`、`{{年月日}}` 自动取系统日期
- **记忆上次输入** → 上次填的内容自动保存到 `last_values.json`，下次打开自动回填
- **模板即时修改** → 可直接在界面中编辑并保存，或用系统编辑器打开 `template.conf`

## 快速开始

**环境要求**：Windows + JDK 17 或更高版本。

**运行**（两种方式任选）：

1. 双击 `launcher.bat`
2. 命令行执行 `java -jar TemplateTool.jar`

**修改模板**：打开 `template.conf`，编辑 `template =` 内容即可（界面里也可以直接修改，完成后点"保存模板"）。

## 占位符语法

| 写法 | 说明 | 示例 |
| --- | --- | --- |
| `{{变量名}}` | 生成数字输入框，留空按 0 | `{{昨日数据}}` |
| `{{变量1*变量2}}` | 算术表达式，结果保留 2 位小数 | `{{本月累计/本月计划}}` |
| `{{年}} {{年月}} {{年月日}}` | 自动获取日期变量 | `{{年月日}}` |
| `[[字符串名]]` | 多行文本，原样输出 | `[[备注]]` |
| `\n` | 模板换行 | `第一行\n第二行` |

## 构建

双击 `build.bat`，或在命令行执行：

```bat
build.bat
```

构建需要 JDK（含 `javac` 和 `jar`），成功后会生成 `TemplateTool.jar`。

## 目录结构

```
├── build.bat               # 编译源码并打包 TemplateTool.jar（自动查找 JDK）
├── launcher.bat            # 一键运行（自动查找 java）
├── template.conf           # 模板配置文件（若缺失也会在首次运行时自动生成）
├── last_values.json        # 上次输入的记忆文件（每次使用完成后自动生成）
└── src/main/java/com/firefly/
    ├── Main.java                 # 程序入口：设置高 DPI / 系统外观，启动主窗口
    ├── TemplateToolApp.java      # 主窗口：界面布局、事件处理、数据同步
    ├── TemplateConstants.java    # 全局常量：文件名、自动日期变量、占位符/正则、默认配置
    ├── core/                     # 逻辑层：配置读写、解析、渲染、求值等
    │   ├── ConfigStore.java         # template.conf 读写
    │   ├── ExpressionEvaluator.java # 安全算术表达式求值（+ - * / ** 与括号，自写递归下降解析）
    │   ├── LastValuesStore.java     # last_values.json 读写（上次输入记忆，UTF-8 带 BOM）
    │   ├── MiniJson.java            # JSON 读写
    │   ├── TemplateParser.java      # 模板占位符解析：提取变量 / 自动日期 / 字符串变量
    │   ├── TemplateRenderer.java    # 把模板渲染成最终结果（替换、求值、原样输出字符串）
    │   ├── TextFileWriter.java      # 文本文件读写（UTF-8 带 BOM、换行转 CRLF，兼容记事本）
    │   └── ValueNormalizer.java     # 数字输入校验/规范化（留空按 0，非数字返回 null）
    └── ui/                     # Swing 界面组件
        ├── ResultPanel.java          # 「结果输出」区（只读多行文本）
        ├── ScrollablePanel.java      # 可滚动纵向表单面板（宽度自适应、高度随内容）
        ├── StringInputPanel.java     # 「字符串输入」区（每个 [[字符串]] 一个多行输入框）
        └── VariableInputPanel.java   # 「变量值输入」区（每个 {{变量}} 一行输入框，支持错误高亮）
```

## 许可证

[MIT License](LICENSE)
