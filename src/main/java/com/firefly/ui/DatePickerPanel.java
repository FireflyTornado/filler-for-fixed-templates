package com.firefly.ui;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JTextField;
import javax.swing.SwingConstants;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.time.temporal.WeekFields;

/** 可手动输入日期，并通过弹出的自定义日历进行选择。 */
public final class DatePickerPanel extends JPanel {

    private static final DateTimeFormatter DISPLAY_FORMAT = DateTimeFormatter.ofPattern("uuuu-MM-dd");
    private static final DateTimeFormatter INPUT_FORMAT = new DateTimeFormatterBuilder()
            .appendPattern("uuuu-M-d")
            .toFormatter()
            .withResolverStyle(ResolverStyle.STRICT);
    private static final Color ERROR_COLOR = new Color(205, 55, 55);
    private static final Color HINT_COLOR = new Color(110, 110, 110);

    private final JTextField dateField = new JTextField(12);
    private final JButton calendarButton = new JButton("选择日期 ▾");
    private final JButton todayButton = new JButton("回到今日");
    private final JPopupMenu popup = new JPopupMenu();
    private final PopupCalendar calendar;
    private final javax.swing.border.Border normalFieldBorder;
    private LocalDate selectedDate;
    private Runnable changeListener = () -> { };

    public DatePickerPanel() {
        super(new BorderLayout(10, 0));
        // 每次创建应用窗口时重新读取系统日期，避免沿用上次运行时的选择。
        selectedDate = LocalDate.now();
        setBorder(BorderFactory.createEmptyBorder(2, 0, 2, 0));

        JPanel controls = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        controls.add(new JLabel("日期基准："));
        dateField.setText(DISPLAY_FORMAT.format(selectedDate));
        normalFieldBorder = dateField.getBorder();
        markInputValid();
        controls.add(dateField);
        controls.add(calendarButton);
        controls.add(todayButton);
        add(controls, BorderLayout.WEST);

        JLabel hint = new JLabel("所有内置日期变量均以此日期为基准");
        hint.setForeground(HINT_COLOR);
        add(hint, BorderLayout.CENTER);

        calendar = new PopupCalendar(selectedDate, this::selectFromCalendar);
        popup.setBorder(BorderFactory.createLineBorder(new Color(170, 175, 182)));
        popup.add(calendar);

        calendarButton.addActionListener(e -> togglePopup());
        todayButton.addActionListener(e -> {
            setSelectedDate(LocalDate.now());
            popup.setVisible(false);
        });
        dateField.addActionListener(e -> commitInput());
        dateField.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                changeListener.run();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                changeListener.run();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                changeListener.run();
            }
        });
        dateField.addFocusListener(new FocusAdapter() {
            @Override
            public void focusLost(FocusEvent e) {
                commitInput();
            }
        });
    }

    /** 返回有效日期；输入非法时返回 null，并将日期框标红。 */
    public LocalDate getSelectedDate() {
        return commitInput() ? selectedDate : null;
    }

    public void setSelectedDate(LocalDate date) {
        selectedDate = date;
        dateField.setText(DISPLAY_FORMAT.format(date));
        markInputValid();
        calendar.setSelectedDate(date);
    }

    /** 日期文本、日历选择或“回到今日”导致日期变化时调用。 */
    public void setChangeListener(Runnable listener) {
        changeListener = listener == null ? () -> { } : listener;
    }

    private boolean commitInput() {
        String text = dateField.getText().trim();
        try {
            LocalDate date = LocalDate.parse(text, INPUT_FORMAT);
            selectedDate = date;
            dateField.setText(DISPLAY_FORMAT.format(date));
            markInputValid();
            calendar.setSelectedDate(date);
            return true;
        } catch (DateTimeParseException e) {
            dateField.setBorder(BorderFactory.createLineBorder(ERROR_COLOR, 2));
            dateField.setToolTipText("请输入有效日期，例如 2026-09-01");
            return false;
        }
    }

    private void markInputValid() {
        dateField.setBorder(normalFieldBorder);
        dateField.setToolTipText("可输入 yyyy-MM-dd，也可点击右侧按钮选择日期");
    }

    private void selectFromCalendar(LocalDate date) {
        setSelectedDate(date);
        popup.setVisible(false);
    }

    private void togglePopup() {
        if (popup.isVisible()) {
            popup.setVisible(false);
            return;
        }
        if (!commitInput()) {
            // 输入非法时仍允许打开日历来纠正：恢复最近一次有效日期作为弹窗起点。
            dateField.setText(DISPLAY_FORMAT.format(selectedDate));
            markInputValid();
        }
        calendar.setSelectedDate(selectedDate);
        popup.show(dateField, 0, dateField.getHeight() + 2);
    }

    /** 弹出层里的三级日期/月/年选择器。 */
    private static final class PopupCalendar extends JPanel {

        private enum View {
            DAY, MONTH, YEAR
        }

        private static final Color SELECTED_FG = new Color(25, 95, 210);
        private static final Color SELECTED_BG = new Color(220, 234, 255);
        private static final Color SUNDAY_FG = new Color(205, 55, 55);
        private static final Color TODAY_BORDER = new Color(52, 120, 246);
        private static final Color WEEK_FG = new Color(125, 125, 125);

        private final JButton previousYearButton = new JButton("«");
        private final JButton previousMonthButton = new JButton("‹");
        private final JButton titleButton = new JButton();
        private final JButton nextMonthButton = new JButton("›");
        private final JButton nextYearButton = new JButton("»");
        private final JPanel calendarPanel = new JPanel();
        private final java.util.function.Consumer<LocalDate> onDateSelected;
        private LocalDate selectedDate;
        private YearMonth displayedMonth;
        private View view = View.DAY;
        private int yearPageStart;

        private PopupCalendar(LocalDate selectedDate,
                              java.util.function.Consumer<LocalDate> onDateSelected) {
            super(new BorderLayout(5, 5));
            this.selectedDate = selectedDate;
            this.displayedMonth = YearMonth.from(selectedDate);
            this.onDateSelected = onDateSelected;
            setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
            setPreferredSize(new Dimension(340, 245));

            JPanel header = new JPanel(new BorderLayout(4, 0));
            JPanel leftButtons = new JPanel(new GridLayout(1, 2, 2, 0));
            JPanel rightButtons = new JPanel(new GridLayout(1, 2, 2, 0));
            configureNavigationButton(previousYearButton);
            configureNavigationButton(previousMonthButton);
            configureNavigationButton(nextMonthButton);
            configureNavigationButton(nextYearButton);
            leftButtons.add(previousYearButton);
            leftButtons.add(previousMonthButton);
            rightButtons.add(nextMonthButton);
            rightButtons.add(nextYearButton);

            titleButton.setFont(titleButton.getFont().deriveFont(Font.BOLD));
            titleButton.setBorderPainted(false);
            titleButton.setContentAreaFilled(false);
            titleButton.setFocusPainted(false);
            header.add(leftButtons, BorderLayout.WEST);
            header.add(titleButton, BorderLayout.CENTER);
            header.add(rightButtons, BorderLayout.EAST);
            add(header, BorderLayout.NORTH);
            add(calendarPanel, BorderLayout.CENTER);

            previousYearButton.addActionListener(e -> movePreviousYear());
            previousMonthButton.addActionListener(e -> movePreviousMonth());
            nextMonthButton.addActionListener(e -> moveNextMonth());
            nextYearButton.addActionListener(e -> moveNextYear());
            titleButton.addActionListener(e -> openHigherLevel());
            rebuildCalendar();
        }

        private void setSelectedDate(LocalDate date) {
            selectedDate = date;
            displayedMonth = YearMonth.from(date);
            view = View.DAY;
            rebuildCalendar();
        }

        private void rebuildCalendar() {
            calendarPanel.removeAll();
            switch (view) {
                case DAY -> rebuildDayView();
                case MONTH -> rebuildMonthView();
                case YEAR -> rebuildYearView();
            }
            calendarPanel.revalidate();
            calendarPanel.repaint();
        }

        private void rebuildDayView() {
            calendarPanel.setLayout(new GridLayout(7, 8, 2, 2));
            titleButton.setText(displayedMonth.getYear() + "年 " + displayedMonth.getMonthValue() + "月  ▾");
            titleButton.setToolTipText("点击选择月份");
            setNavigationTooltips("上一年", "上个月", "下个月", "下一年");

            String[] weekdays = {"周", "一", "二", "三", "四", "五", "六", "日"};
            for (int i = 0; i < weekdays.length; i++) {
                JLabel label = new JLabel(weekdays[i], SwingConstants.CENTER);
                label.setForeground(i == 7 ? SUNDAY_FG : (i == 0 ? WEEK_FG : Color.GRAY));
                calendarPanel.add(label);
            }

            LocalDate first = displayedMonth.atDay(1);
            int leading = first.getDayOfWeek().getValue() - 1;
            LocalDate gridStart = first.minusDays(leading);
            int length = displayedMonth.lengthOfMonth();
            for (int row = 0; row < 6; row++) {
                LocalDate weekStart = gridStart.plusWeeks(row);
                int weekNumber = weekStart.get(WeekFields.ISO.weekOfWeekBasedYear());
                JLabel weekLabel = new JLabel(Integer.toString(weekNumber), SwingConstants.CENTER);
                weekLabel.setForeground(WEEK_FG);
                weekLabel.setToolTipText("当年第 " + weekNumber + " 周");
                calendarPanel.add(weekLabel);

                for (int column = 0; column < 7; column++) {
                    int day = row * 7 + column - leading + 1;
                    if (day < 1 || day > length) {
                        calendarPanel.add(new JLabel());
                        continue;
                    }
                    LocalDate date = displayedMonth.atDay(day);
                    JButton button = calendarButton(Integer.toString(day));
                    if (date.getDayOfWeek() == DayOfWeek.SUNDAY) {
                        button.setForeground(SUNDAY_FG);
                    }
                    if (date.equals(selectedDate)) {
                        markSelected(button);
                    }
                    if (date.equals(LocalDate.now())) {
                        button.setBorder(BorderFactory.createLineBorder(TODAY_BORDER, 2));
                        button.setToolTipText("今天");
                    }
                    button.addActionListener(e -> onDateSelected.accept(date));
                    calendarPanel.add(button);
                }
            }
        }

        private void rebuildMonthView() {
            calendarPanel.setLayout(new GridLayout(3, 4, 5, 5));
            int year = displayedMonth.getYear();
            titleButton.setText(year + "年  ▾");
            titleButton.setToolTipText("点击选择年份");
            setNavigationTooltips("前 10 年", "上一年", "下一年", "后 10 年");

            for (int month = 1; month <= 12; month++) {
                JButton button = calendarButton(month + "月");
                if (selectedDate.getYear() == year && selectedDate.getMonthValue() == month) {
                    markSelected(button);
                } else if (LocalDate.now().getYear() == year
                        && LocalDate.now().getMonthValue() == month) {
                    button.setBorder(BorderFactory.createLineBorder(TODAY_BORDER, 2));
                }
                int chosenMonth = month;
                button.addActionListener(e -> {
                    displayedMonth = YearMonth.of(year, chosenMonth);
                    view = View.DAY;
                    rebuildCalendar();
                });
                calendarPanel.add(button);
            }
        }

        private void rebuildYearView() {
            calendarPanel.setLayout(new GridLayout(3, 4, 5, 5));
            titleButton.setText(yearPageStart + "—" + (yearPageStart + 11));
            titleButton.setToolTipText("请选择年份");
            setNavigationTooltips("前 120 年", "前 12 年", "后 12 年", "后 120 年");

            for (int offset = 0; offset < 12; offset++) {
                int year = yearPageStart + offset;
                JButton button = calendarButton(Integer.toString(year));
                if (selectedDate.getYear() == year) {
                    markSelected(button);
                } else if (LocalDate.now().getYear() == year) {
                    button.setBorder(BorderFactory.createLineBorder(TODAY_BORDER, 2));
                }
                button.addActionListener(e -> {
                    displayedMonth = YearMonth.of(year, displayedMonth.getMonthValue());
                    view = View.MONTH;
                    rebuildCalendar();
                });
                calendarPanel.add(button);
            }
        }

        private void openHigherLevel() {
            if (view == View.DAY) {
                view = View.MONTH;
            } else if (view == View.MONTH) {
                yearPageStart = decadePageStart(displayedMonth.getYear());
                view = View.YEAR;
            }
            rebuildCalendar();
        }

        private void movePreviousYear() {
            switch (view) {
                case DAY -> displayedMonth = displayedMonth.minusYears(1);
                case MONTH -> displayedMonth = displayedMonth.minusYears(10);
                case YEAR -> yearPageStart -= 120;
            }
            rebuildCalendar();
        }

        private void movePreviousMonth() {
            switch (view) {
                case DAY -> displayedMonth = displayedMonth.minusMonths(1);
                case MONTH -> displayedMonth = displayedMonth.minusYears(1);
                case YEAR -> yearPageStart -= 12;
            }
            rebuildCalendar();
        }

        private void moveNextMonth() {
            switch (view) {
                case DAY -> displayedMonth = displayedMonth.plusMonths(1);
                case MONTH -> displayedMonth = displayedMonth.plusYears(1);
                case YEAR -> yearPageStart += 12;
            }
            rebuildCalendar();
        }

        private void moveNextYear() {
            switch (view) {
                case DAY -> displayedMonth = displayedMonth.plusYears(1);
                case MONTH -> displayedMonth = displayedMonth.plusYears(10);
                case YEAR -> yearPageStart += 120;
            }
            rebuildCalendar();
        }

        private void setNavigationTooltips(String previousYear, String previousMonth,
                                           String nextMonth, String nextYear) {
            previousYearButton.setToolTipText(previousYear);
            previousMonthButton.setToolTipText(previousMonth);
            nextMonthButton.setToolTipText(nextMonth);
            nextYearButton.setToolTipText(nextYear);
        }

        private static void configureNavigationButton(JButton button) {
            button.setMargin(new Insets(1, 7, 1, 7));
            button.setFocusPainted(false);
        }

        private static JButton calendarButton(String text) {
            JButton button = new JButton(text);
            button.setMargin(new Insets(0, 0, 0, 0));
            button.setFocusPainted(false);
            return button;
        }

        private static void markSelected(JButton button) {
            button.setOpaque(true);
            button.setBackground(SELECTED_BG);
            button.setForeground(SELECTED_FG);
            button.setFont(button.getFont().deriveFont(Font.BOLD));
        }

        private static int decadePageStart(int year) {
            return Math.floorDiv(year, 10) * 10;
        }
    }
}
