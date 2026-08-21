package com.firefly;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 全局常量：文件名、日期、占位符正则等。
 */
public final class TemplateConstants {

    private TemplateConstants() {
    }

    /** 配置文件 / 结果文件 / 上次输入存档 的文件名 */
    public static final String CONFIG_FILENAME = "template.conf";
    public static final String RESULT_FILENAME = "result.txt";
    public static final String VALUES_FILENAME = "last_values.json";

    /** 自动日期：出现在模板中时，由系统日期自动填充，无需手动输入。
     *  今日… 取当天日期；昨日… 取前一天日期。 */
    public static final List<String> AUTO_VARS =
            Collections.unmodifiableList(Arrays.asList(
                    "今日年", "今日年月", "今日年月日",
                    "昨日年", "昨日年月", "昨日年月日"));
    public static final Set<String> AUTO_VAR_SET =
            Collections.unmodifiableSet(new HashSet<>(AUTO_VARS));

    /** 匹配 {{...}} 占位符；里面可以是变量名，也可以是算术表达式（如 {{数量*单价}}） */
    public static final Pattern PLACEHOLDER_RE = Pattern.compile("\\{\\{([^{}]*)\\}\\}");
    /** 匹配 [[...]] 字符串占位符：内容原样输出，不参与数值校验 */
    public static final Pattern STRING_RE = Pattern.compile("\\[\\[([^\\[\\]]*)\\]\\]");
    /** 两种占位符的合集（用于一次性渲染）：group(1)=普通 {{...}}，group(2)=字符串 [[...]] */
    public static final Pattern ALL_PLACEHOLDER_RE =
            Pattern.compile("(\\{\\{[^{}]*\\}\\})|(\\[\\[[^\\[\\]]*\\]\\])");
    /**
     * 从表达式里提取变量名：以字母/中文开头，后接字母数字下划线；前面不能紧跟数字。
     */
    public static final Pattern IDENT_RE =
            Pattern.compile("(?<![\\p{L}\\p{Nd}_])[\\p{L}\\p{M}][\\p{L}\\p{M}\\p{Nd}_]*");
    /** 判断一个占位符内容是否为算术表达式（含 + - * / 或括号） */
    public static final Pattern OPERATOR_RE = Pattern.compile("[+\\-*/()]");
    /** 识别配置文件中 “template = ...” 这一行 */
    public static final Pattern TPL_LINE =
            Pattern.compile("^\\s*template\\s*=\\s*(.*)$", Pattern.CASE_INSENSITIVE);

    /**
     * 默认配置（配置文件不存在时自动生成）。
     * 注意：模板里的 \n 按规则写成两个字符「\n」，读取时再还原成真实换行，
     * 这样默认示例也与文档规则一致。
     */
    public static final String DEFAULT_CONFIG =
            "# ==================================================\n"
            + "# 模板填充工具 —— 配置文件\n"
            + "# --------------------------------------------------\n"
            + "# 修改下面 template 一行的内容，即可更换模板。\n"
            + "# 规则：\n"
            + "#   * 用 {{变量名}} 在模板中标注占位符\n"
            + "#   * 打开工具后，会为每个变量自动生成一个输入框\n"
            + "#   * 输入留空时按 0 处理；输入非数字会给出友好提示\n"
            + "#   * {{今日年}}、{{今日年月}}、{{今日年月日}} 自动取当天日期；{{昨日年}}、{{昨日年月}}、{{昨日年月日}} 自动取前一天日期，均无需输入\n"
            + "#   * 支持算术表达式，如 {{数量*单价}}，结果保留两位小数\n"
            + "#   * 用 [[字符串名]] 标注字符串占位符，输入内容原样输出（含换行、空格、格式）\n"
            + "#   * 模板需要换行时，请使用 \\n\n"
            + "# ==================================================\n"
            + "template = {{今日年月日}} 客户编号 {{编号}}：购买 {{数量}} 件，单价 {{单价}} 元，应付 {{金额}} 元。\\n备注：[[备注]]\n";
}
