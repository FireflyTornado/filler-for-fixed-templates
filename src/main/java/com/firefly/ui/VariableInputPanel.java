package com.firefly.ui;

import com.firefly.core.ValueNormalizer;
import com.firefly.core.VariableInputState;
import com.firefly.core.VariableType;

import javax.swing.*;
import javax.swing.border.Border;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

/** 统一变量控件、类型转换、行内错误反馈、滚动与焦点定位。 */
public final class VariableInputPanel extends JPanel {
    private final ScrollablePanel rows = new ScrollablePanel(new GridBagLayout());
    private final JScrollPane scroll = new JScrollPane(rows);
    private final JButton locateButton = new JButton("定位错误");
    private final Map<String, Row> rowByName = new LinkedHashMap<>();
    private final ValidationIssueManager issues;
    private Map<String, VariableInputState> states = new LinkedHashMap<>();
    private Runnable changeListener = () -> { };
    private Runnable commitListener = () -> { };
    private Consumer<String> statusListener = text -> { };
    private boolean rebuilding;

    public VariableInputPanel(ValidationIssueManager issues) {
        super(new BorderLayout(4, 4));
        this.issues = issues;
        setBorder(BorderFactory.createTitledBorder("变量填写"));
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        add(scroll);
        JPanel bottom = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 2));
        bottom.add(locateButton);
        add(bottom, BorderLayout.SOUTH);
        locateButton.addActionListener(e -> locateNextIssue());
        locateButton.setMnemonic('E');
        locateButton.getAccessibleContext().setAccessibleName("定位下一个输入错误");
        issues.setChangeListener(this::refreshLocateButton);
        refreshLocateButton();
        setMinimumSize(new Dimension(340, 220));
    }

    public JButton locateButton() { return locateButton; }
    public boolean hasVisibleErrorIndicator(String name) {
        Row row = rowByName.get(name);
        return row != null && row.warning.isVisible();
    }
    public void setChangeListener(Runnable listener) { changeListener = listener == null ? () -> { } : listener; }
    public void setCommitListener(Runnable listener) { commitListener = listener == null ? () -> { } : listener; }
    public void setStatusListener(Consumer<String> listener) { statusListener = listener == null ? text -> { } : listener; }

    public void rebuild(Map<String, VariableInputState> newStates) {
        for (String oldName : rowByName.keySet()) issues.remove(issueId(oldName));
        rebuilding = true;
        try {
            states = newStates;
            rows.removeAll();
            rowByName.clear();
            GridBagConstraints gc = new GridBagConstraints();
            gc.insets = new Insets(4, 3, 4, 3);
            gc.gridy = 0;
            gc.anchor = GridBagConstraints.WEST;
            if (states.isEmpty()) {
                gc.gridx = 0; gc.weightx = 1; gc.fill = GridBagConstraints.HORIZONTAL;
                rows.add(new JLabel("当前模板没有普通变量。"), gc);
            } else {
                addHeader(gc);
                int order = 0;
                for (VariableInputState state : states.values()) addRow(state, gc, order++);
            }
            gc.gridy++; gc.gridx = 0; gc.weighty = 1; gc.fill = GridBagConstraints.VERTICAL;
            rows.add(new JLabel(), gc);
            rows.revalidate(); rows.repaint();
        } finally { rebuilding = false; }
        refreshAllValidation();
    }

    private void addHeader(GridBagConstraints gc) {
        String[] headers = {"变量", "类型", "值", "", ""};
        for (int column = 0; column < headers.length; column++) {
            gc.gridx = column; gc.weightx = column == 2 ? 1 : 0;
            gc.fill = column == 2 ? GridBagConstraints.HORIZONTAL : GridBagConstraints.NONE;
            rows.add(new JLabel(headers[column]), gc);
        }
        gc.gridy++;
    }

    private void addRow(VariableInputState state, GridBagConstraints gc, int order) {
        JLabel name = new JLabel(state.name());
        name.setToolTipText(state.name() + " — " + syntaxTooltip(state));
        JComboBox<VariableType> type = new JComboBox<>(VariableType.values());
        type.setSelectedItem(state.type());
        type.setPrototypeDisplayValue(VariableType.MULTILINE_TEXT);
        type.setEnabled(!state.numericLocked());
        if (state.numericLocked()) type.setToolTipText("参与表达式，类型锁定为数值");
        JTextField field = new JTextField(8);
        JButton expand = new JButton("展开…");
        expand.setMargin(new Insets(2, 6, 2, 6));
        JLabel warning = new JLabel("⚠");
        warning.setVisible(false);
        warning.getAccessibleContext().setAccessibleName("输入错误");
        Row row = new Row(state, type, field, expand, warning,
                field.getBackground(), field.getBorder(), order);
        rowByName.put(state.name(), row);
        name.setLabelFor(field);
        field.getAccessibleContext().setAccessibleName("变量“" + state.name() + "”的值");
        type.getAccessibleContext().setAccessibleName("变量“" + state.name() + "”的类型");
        expand.getAccessibleContext().setAccessibleName("编辑变量“" + state.name() + "”的多行文本");
        configureValueField(row);
        installSessionValueMenu(row, name);

        gc.gridx = 0; gc.weightx = 0; gc.fill = GridBagConstraints.NONE; rows.add(name, gc);
        gc.gridx = 1; rows.add(type, gc);
        gc.gridx = 2; gc.weightx = 1; gc.fill = GridBagConstraints.HORIZONTAL; rows.add(field, gc);
        gc.gridx = 3; gc.weightx = 0; gc.fill = GridBagConstraints.NONE; rows.add(expand, gc);
        gc.gridx = 4; rows.add(warning, gc);
        gc.gridy++;

        type.addActionListener(e -> typeChanged(row));
        field.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e) { valueChanged(row); }
            public void removeUpdate(DocumentEvent e) { valueChanged(row); }
            public void changedUpdate(DocumentEvent e) { valueChanged(row); }
        });
        expand.addActionListener(e -> editMultiline(row));
        field.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 && row.state.type() == VariableType.MULTILINE_TEXT) editMultiline(row);
            }
        });
    }

    private void installSessionValueMenu(Row row, JLabel name) {
        JPopupMenu menu = new JPopupMenu();
        JMenuItem clearOthers = new JMenuItem("清除其他类型内容");
        clearOthers.addActionListener(e -> {
            row.state.clearOtherTypeValues();
            statusListener.accept("已清除变量“" + row.state.name() + "”的其他类型内容。");
        });
        menu.add(clearOthers);
        menu.addPopupMenuListener(new javax.swing.event.PopupMenuListener() {
            public void popupMenuWillBecomeVisible(javax.swing.event.PopupMenuEvent e) {
                clearOthers.setEnabled(row.state.hasOtherTypeValues());
            }
            public void popupMenuWillBecomeInvisible(javax.swing.event.PopupMenuEvent e) { }
            public void popupMenuCanceled(javax.swing.event.PopupMenuEvent e) { }
        });
        name.setComponentPopupMenu(menu);
        row.type.setComponentPopupMenu(menu);
        row.field.setComponentPopupMenu(menu);
        row.expand.setComponentPopupMenu(menu);
    }

    private void configureValueField(Row row) {
        boolean multiline = row.state.type() == VariableType.MULTILINE_TEXT;
        row.field.setEditable(!multiline);
        row.expand.setVisible(multiline);
        setFieldText(row, multiline ? preview(row.state.value()) : row.state.value());
        row.field.setToolTipText(valueTooltip(row.state));
        validateRow(row);
    }

    private void typeChanged(Row row) {
        if (rebuilding) return;
        VariableType oldType = row.state.type();
        VariableType target = (VariableType) row.type.getSelectedItem();
        if (target == null || target == oldType) return;
        String initial = determineTargetDraft(row, oldType, target);
        if (initial == null) {
            setComboType(row, oldType);
            row.type.requestFocusInWindow();
            return;
        }
        row.state.activateType(target, initial);
        configureValueField(row);
        changeListener.run();
        commitListener.run();
        row.type.requestFocusInWindow();
    }

    private String determineTargetDraft(Row row, VariableType oldType, VariableType target) {
        if (row.state.hasDraft(target)) return row.state.draft(target);
        String current = row.state.value();
        if (target == VariableType.MULTILINE_TEXT) return current;
        if (target == VariableType.SHORT_TEXT) {
            if (oldType != VariableType.MULTILINE_TEXT) return current;
            if (effectiveLineCount(current) <= 1) return toSingleLine(current);
            return VariableTypeConversionDialog.toShortText(
                    SwingUtilities.getWindowAncestor(this), row.state.name(), current, toSingleLine(current));
        }
        String trimmed = current.trim();
        if (trimmed.isEmpty() || ValueNormalizer.normalize(trimmed) != null) return trimmed;
        return VariableTypeConversionDialog.toNumber(
                SwingUtilities.getWindowAncestor(this), row.state.name(), current);
    }

    private void valueChanged(Row row) {
        if (rebuilding || row.state.type() == VariableType.MULTILINE_TEXT) return;
        row.state.setValue(row.field.getText());
        validateRow(row);
        changeListener.run();
    }

    private void editMultiline(Row row) {
        String edited = MultilineEditorDialog.edit(SwingUtilities.getWindowAncestor(this),
                row.state.name(), row.state.value());
        if (edited != null && !edited.equals(row.state.value())) {
            row.state.setValue(edited);
            configureValueField(row);
            changeListener.run();
            commitListener.run();
        }
        row.expand.requestFocusInWindow();
    }

    private void validateRow(Row row) {
        boolean invalid = row.state.type() == VariableType.NUMBER
                && (row.state.requiresNumericAttention()
                || ValueNormalizer.normalize(row.state.value()) == null);
        String message = "变量“" + row.state.name() + "”需要填写有效数字。";
        row.field.setBackground(invalid ? errorBackground(row.normalBackground) : row.normalBackground);
        row.field.setBorder(invalid ? BorderFactory.createLineBorder(new Color(190, 55, 55), 2)
                : row.normalBorder);
        row.warning.setVisible(invalid);
        row.warning.setToolTipText(invalid ? message : null);
        row.warning.getAccessibleContext().setAccessibleDescription(invalid ? message : null);
        row.field.getAccessibleContext().setAccessibleDescription(invalid ? message : valueTooltip(row.state));
        if (invalid) {
            row.field.setToolTipText(message);
            issues.put(new ValidationIssue(issueId(row.state.name()), row.state.name(), message,
                    row.field, IssueSeverity.ERROR, 100 + row.order));
        } else {
            row.field.setToolTipText(valueTooltip(row.state));
            issues.remove(issueId(row.state.name()));
        }
    }

    public void refreshAllValidation() { for (Row row : rowByName.values()) validateRow(row); }
    public void scrollToVariable(String name) {
        Row row = rowByName.get(name);
        if (row != null) rows.scrollRectToVisible(row.field.getParent() == rows
                ? row.field.getBounds() : SwingUtilities.convertRectangle(row.field.getParent(), row.field.getBounds(), rows));
    }
    public void focusVariable(String name) { Row row = rowByName.get(name); if (row != null) row.field.requestFocusInWindow(); }

    public void locateNextIssue() {
        ValidationIssue issue = issues.next();
        if (issue == null) return;
        if (issue.variableName() != null) scrollToVariable(issue.variableName());
        JComponent target = issue.targetComponent();
        if (target == null || !target.isDisplayable()) {
            issues.remove(issue.id());
            locateNextIssue();
            return;
        }
        if (target.isDisplayable()) {
            target.requestFocusInWindow();
            Border original = target.getBorder();
            target.setBorder(BorderFactory.createLineBorder(new Color(220, 120, 0), 3));
            Timer timer = new Timer(650, e -> {
                if (target.isDisplayable() && issues.contains(issue.id())) target.setBorder(original);
            });
            timer.setRepeats(false); timer.start();
        }
        int position = issues.currentPosition();
        statusListener.accept("错误 " + position + "/" + issues.count() + "：" + issue.message());
        refreshLocateButton();
    }

    private void refreshLocateButton() {
        int count = issues.count();
        locateButton.setEnabled(count > 0);
        locateButton.setText(count == 0 ? "定位错误" : "定位错误（" + count + "）");
        locateButton.setToolTipText(count == 0 ? "当前没有输入错误" : "按界面顺序定位下一个错误");
    }

    private void setFieldText(Row row, String text) {
        boolean wasRebuilding = rebuilding;
        rebuilding = true;
        try { row.field.setText(text); } finally { rebuilding = wasRebuilding; }
    }
    private void setComboType(Row row, VariableType type) {
        boolean wasRebuilding = rebuilding;
        rebuilding = true;
        try { row.type.setSelectedItem(type); } finally { rebuilding = wasRebuilding; }
    }
    private static String issueId(String name) { return "variable:" + name; }
    private static int effectiveLineCount(String value) {
        int count = 0;
        for (String line : value.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1))
            if (!line.trim().isEmpty()) count++;
        return count;
    }
    public static String toSingleLine(String value) {
        StringBuilder result = new StringBuilder();
        for (String line : value.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1)) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) continue;
            if (result.length() > 0) result.append(' ');
            result.append(trimmed);
        }
        return result.toString();
    }
    private static String preview(String value) {
        String compact = value.replace("\r\n", "↵ ").replace("\r", "↵ ").replace("\n", "↵ ");
        return compact.length() <= 120 ? compact : compact.substring(0, 117) + "…";
    }
    private static String valueTooltip(VariableInputState state) {
        if (state.type() != VariableType.MULTILINE_TEXT) return null;
        int lines = state.value().isEmpty() ? 0 : state.value().split("\\R", -1).length;
        return "共 " + lines + " 行 / " + state.value().length() + " 个字符";
    }
    private static String syntaxTooltip(VariableInputState state) {
        String syntax = state.braceSyntax() && state.legacyMultilineSyntax() ? "{{变量}} 与 [[变量]]"
                : (state.legacyMultilineSyntax() ? "旧格式 [[变量]]" : "推荐格式 {{变量}}");
        return state.numericLocked() ? syntax + "；表达式使用，锁定为数值" : syntax;
    }

    private static Color errorBackground(Color normal) {
        if (normal == null) return new Color(255, 225, 225);
        return new Color((normal.getRed() * 3 + 220) / 4,
                (normal.getGreen() * 3 + 70) / 4,
                (normal.getBlue() * 3 + 70) / 4);
    }

    private record Row(VariableInputState state, JComboBox<VariableType> type, JTextField field,
                       JButton expand, JLabel warning, Color normalBackground,
                       Border normalBorder, int order) { }
}
