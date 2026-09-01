package com.firefly.ui;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridLayout;
import java.time.LocalDate;
import java.time.YearMonth;

/** 模板旁的轻量日历：选择日期，或一键回到系统当天。 */
public final class DatePickerPanel extends JPanel {

    private static final Color SELECTED_BG = new Color(52, 120, 246);
    private static final Color TODAY_BORDER = new Color(52, 120, 246);

    private enum View {
        DAY, MONTH, YEAR
    }

    private final JButton titleButton = new JButton();
    private final JButton previousButton = new JButton("‹");
    private final JButton nextButton = new JButton("›");
    private final JLabel selectedLabel = new JLabel("", SwingConstants.CENTER);
    private final JPanel calendarPanel = new JPanel();
    private LocalDate selectedDate = LocalDate.now();
    private YearMonth displayedMonth = YearMonth.from(selectedDate);
    private View view = View.DAY;
    private int yearPageStart;

    public DatePickerPanel() {
        super(new BorderLayout(4, 4));
        setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 1, 0, 0, Color.LIGHT_GRAY),
                BorderFactory.createEmptyBorder(2, 8, 2, 2)));
        setPreferredSize(new Dimension(250, 175));

        JPanel header = new JPanel(new BorderLayout(3, 0));
        titleButton.setFont(titleButton.getFont().deriveFont(Font.BOLD));
        titleButton.setBorderPainted(false);
        titleButton.setContentAreaFilled(false);
        titleButton.setFocusPainted(false);
        header.add(previousButton, BorderLayout.WEST);
        header.add(titleButton, BorderLayout.CENTER);
        header.add(nextButton, BorderLayout.EAST);
        add(header, BorderLayout.NORTH);

        add(calendarPanel, BorderLayout.CENTER);

        JPanel footer = new JPanel(new BorderLayout(4, 0));
        JButton todayButton = new JButton("回到今日");
        JPanel buttonHolder = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        buttonHolder.add(todayButton);
        footer.add(selectedLabel, BorderLayout.CENTER);
        footer.add(buttonHolder, BorderLayout.EAST);
        add(footer, BorderLayout.SOUTH);

        previousButton.addActionListener(e -> movePrevious());
        nextButton.addActionListener(e -> moveNext());
        titleButton.addActionListener(e -> openHigherLevel());
        todayButton.addActionListener(e -> setSelectedDate(LocalDate.now()));
        rebuildCalendar();
    }

    public LocalDate getSelectedDate() {
        return selectedDate;
    }

    public void setSelectedDate(LocalDate date) {
        selectedDate = date;
        displayedMonth = YearMonth.from(date);
        view = View.DAY;
        rebuildCalendar();
    }

    private void rebuildCalendar() {
        calendarPanel.removeAll();
        selectedLabel.setText("已选：" + selectedDate.getYear() + "-"
                + twoDigits(selectedDate.getMonthValue()) + "-" + twoDigits(selectedDate.getDayOfMonth()));

        switch (view) {
            case DAY -> rebuildDayView();
            case MONTH -> rebuildMonthView();
            case YEAR -> rebuildYearView();
        }
        calendarPanel.revalidate();
        calendarPanel.repaint();
    }

    private void rebuildDayView() {
        calendarPanel.setLayout(new GridLayout(7, 7, 2, 2));
        titleButton.setText(displayedMonth.getYear() + "年 " + displayedMonth.getMonthValue() + "月  ▾");
        titleButton.setToolTipText("点击选择月份");
        previousButton.setToolTipText("上个月");
        nextButton.setToolTipText("下个月");

        String[] weekdays = {"一", "二", "三", "四", "五", "六", "日"};
        for (String weekday : weekdays) {
            JLabel label = new JLabel(weekday, SwingConstants.CENTER);
            label.setForeground(Color.GRAY);
            calendarPanel.add(label);
        }

        int leading = displayedMonth.atDay(1).getDayOfWeek().getValue() - 1;
        int length = displayedMonth.lengthOfMonth();
        for (int cell = 0; cell < 42; cell++) {
            int day = cell - leading + 1;
            if (day < 1 || day > length) {
                calendarPanel.add(new JLabel());
                continue;
            }
            LocalDate date = displayedMonth.atDay(day);
            JButton button = calendarButton(Integer.toString(day));
            if (date.equals(selectedDate)) {
                markSelected(button);
            } else if (date.equals(LocalDate.now())) {
                button.setBorder(BorderFactory.createLineBorder(TODAY_BORDER, 2));
                button.setToolTipText("今天");
            }
            button.addActionListener(e -> setSelectedDate(date));
            calendarPanel.add(button);
        }
    }

    private void rebuildMonthView() {
        calendarPanel.setLayout(new GridLayout(3, 4, 5, 5));
        int year = displayedMonth.getYear();
        titleButton.setText(year + "年  ▾");
        titleButton.setToolTipText("点击选择年份");
        previousButton.setToolTipText("上一年");
        nextButton.setToolTipText("下一年");

        for (int month = 1; month <= 12; month++) {
            JButton button = calendarButton(month + "月");
            if (selectedDate.getYear() == year && selectedDate.getMonthValue() == month) {
                markSelected(button);
            } else if (LocalDate.now().getYear() == year && LocalDate.now().getMonthValue() == month) {
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
        previousButton.setToolTipText("前 12 年");
        nextButton.setToolTipText("后 12 年");

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

    private void movePrevious() {
        switch (view) {
            case DAY -> displayedMonth = displayedMonth.minusMonths(1);
            case MONTH -> displayedMonth = displayedMonth.minusYears(1);
            case YEAR -> yearPageStart -= 12;
        }
        rebuildCalendar();
    }

    private void moveNext() {
        switch (view) {
            case DAY -> displayedMonth = displayedMonth.plusMonths(1);
            case MONTH -> displayedMonth = displayedMonth.plusYears(1);
            case YEAR -> yearPageStart += 12;
        }
        rebuildCalendar();
    }

    private static JButton calendarButton(String text) {
        JButton button = new JButton(text);
        button.setMargin(new java.awt.Insets(0, 0, 0, 0));
        button.setFocusPainted(false);
        return button;
    }

    private static void markSelected(JButton button) {
        button.setOpaque(true);
        button.setBackground(SELECTED_BG);
        button.setForeground(Color.WHITE);
    }

    /** 以整十年开头显示 12 年，例如 2020—2031。 */
    private static int decadePageStart(int year) {
        return Math.floorDiv(year, 10) * 10;
    }

    private static String twoDigits(int value) {
        return value < 10 ? "0" + value : Integer.toString(value);
    }
}
