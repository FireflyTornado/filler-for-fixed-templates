package com.firefly;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 全局常量：文件名、日期、占位符正则等。
 */
public final class TemplateConstants {

    private TemplateConstants() {
    }

    /** 结果文件 / 上次输入存档 的文件名 */
    public static final String RESULT_FILENAME = "result.txt";
    public static final String VALUES_FILENAME = "last_values.json";

    /** 模板文件夹名 / 示例模板文件名 / 记忆上次使用的模板时写入 last_values.json 的保留键 */
    public static final String TEMPLATES_DIR_NAME = "Templates";
    public static final String EXAMPLE_TEMPLATE_NAME = "example.txt";
    public static final String EXAMPLE_DOCX_NAME = "example.docx";
    public static final String LAST_TEMPLATE_KEY = "@@last_template@@";

    /** 自动日期变量：全部以界面日历选中的日期为基准，不能参与算术运算。 */
    public static final Set<String> AUTO_DATE_VAR_SET =
            Set.of("今日年", "今日年月", "今日年月日",
                    "昨日年", "昨日年月", "昨日年月日",
                    "明日年", "明日年月", "明日年月日",
                    "今日", "昨日", "明日",
                    "本月年", "本月年月",
                    "上月年", "上月年月",
                    "下月年", "下月年月",
                    "本月", "上月", "下月",
                    "本年", "上年", "下年",
                    "本月月首", "本月月末",
                    "上月月首", "上月月末",
                    "下月月首", "下月月末");

    /** 自动数值变量：由基准日期计算，无需输入，并可参与算术运算。 */
    public static final Set<String> AUTO_NUMERIC_VAR_SET =
            Set.of("本月天数", "上月天数", "下月天数");

    /** 所有无需用户输入的自动变量。 */
    public static final Set<String> AUTO_VAR_SET = union(AUTO_DATE_VAR_SET, AUTO_NUMERIC_VAR_SET);

    /** 匹配 {{...}} 占位符；以 = 开头表示算术表达式（如 {{=数量*单价}}），否则内容按变量名处理 */
    public static final Pattern PLACEHOLDER_RE = Pattern.compile("\\{\\{([^{}]*)\\}\\}");
    /** 已弃用的 [[变量]] 语法：只用于读取模板时检测并执行一次性迁移。 */
    public static final Pattern LEGACY_PLACEHOLDER_RE =
            Pattern.compile("\\[\\[([^\\[\\]]+)\\]\\]");

    /** 根据日历选中日期生成全部自动变量；键与 {@link #AUTO_VAR_SET} 一一对应。 */
    public static Map<String, String> autoValues(LocalDate selectedDay) {
        LocalDate yesterday = selectedDay.minusDays(1);
        LocalDate tomorrow = selectedDay.plusDays(1);
        LocalDate previousMonth = selectedDay.minusMonths(1);
        LocalDate nextMonth = selectedDay.plusMonths(1);
        LocalDate firstDayOfMonth = selectedDay.withDayOfMonth(1);
        LocalDate lastDayOfMonth = selectedDay.withDayOfMonth(selectedDay.lengthOfMonth());
        LocalDate firstDayOfPreviousMonth = previousMonth.withDayOfMonth(1);
        LocalDate lastDayOfPreviousMonth = previousMonth.withDayOfMonth(previousMonth.lengthOfMonth());
        LocalDate firstDayOfNextMonth = nextMonth.withDayOfMonth(1);
        LocalDate lastDayOfNextMonth = nextMonth.withDayOfMonth(nextMonth.lengthOfMonth());

        Map<String, String> map = new LinkedHashMap<>();
        map.put("今日年", year(selectedDay));
        map.put("今日年月", yearMonth(selectedDay));
        map.put("今日年月日", yearMonthDay(selectedDay));
        map.put("昨日年", year(yesterday));
        map.put("昨日年月", yearMonth(yesterday));
        map.put("昨日年月日", yearMonthDay(yesterday));
        map.put("明日年", year(tomorrow));
        map.put("明日年月", yearMonth(tomorrow));
        map.put("明日年月日", yearMonthDay(tomorrow));
        map.put("今日", day(selectedDay));
        map.put("昨日", day(yesterday));
        map.put("明日", day(tomorrow));
        map.put("本月年", year(selectedDay));
        map.put("本月年月", yearMonth(selectedDay));
        map.put("上月年", year(previousMonth));
        map.put("上月年月", yearMonth(previousMonth));
        map.put("下月年", year(nextMonth));
        map.put("下月年月", yearMonth(nextMonth));
        map.put("本月", month(selectedDay));
        map.put("上月", month(previousMonth));
        map.put("下月", month(nextMonth));
        map.put("本月天数", Integer.toString(selectedDay.lengthOfMonth()));
        map.put("上月天数", Integer.toString(previousMonth.lengthOfMonth()));
        map.put("下月天数", Integer.toString(nextMonth.lengthOfMonth()));
        map.put("本年", year(selectedDay));
        map.put("上年", year(selectedDay.minusYears(1)));
        map.put("下年", year(selectedDay.plusYears(1)));
        map.put("本月月首", monthDay(firstDayOfMonth));
        map.put("本月月末", monthDay(lastDayOfMonth));
        map.put("上月月首", monthDay(firstDayOfPreviousMonth));
        map.put("上月月末", monthDay(lastDayOfPreviousMonth));
        map.put("下月月首", monthDay(firstDayOfNextMonth));
        map.put("下月月末", monthDay(lastDayOfNextMonth));
        return map;
    }

    private static Set<String> union(Set<String> first, Set<String> second) {
        Set<String> result = new LinkedHashSet<>(first);
        result.addAll(second);
        return Set.copyOf(result);
    }

    private static String day(LocalDate date) {
        return date.getDayOfMonth() + "日";
    }

    private static String month(LocalDate date) {
        return date.getMonthValue() + "月";
    }

    private static String year(LocalDate date) {
        return date.getYear() + "年";
    }

    private static String yearMonth(LocalDate date) {
        return date.getYear() + "年" + date.getMonthValue() + "月";
    }

    private static String yearMonthDay(LocalDate date) {
        return yearMonth(date) + date.getDayOfMonth() + "日";
    }

    private static String monthDay(LocalDate date) {
        return month(date) + day(date);
    }

    /**
     * 默认示例模板（Templates 文件夹为空时自动生成 example.txt）。
     * 使用真实换行（模板文件是纯文本）；演示数字变量、算术表达式、日期变量与字符串。
     */
    public static final String DEFAULT_TEMPLATE =
            "{{今日年月日}} 客户编号 {{编号}}：购买 {{数量}} 件，单价 {{单价}} 元，应付 {{=数量*单价}} 元。\n"
            + "备注：{{备注}}\n";
}
