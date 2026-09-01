package com.firefly.ui;

import com.firefly.core.ValueNormalizer;
import com.firefly.core.VariableInputState;
import com.firefly.core.VariableType;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Window;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

/** 数值、短字符串和多行文本共用的紧凑变量填写面板。 */
public final class VariableInputPanel extends JPanel {
    private static final Color ERROR_BG = new Color(0xff, 0xe3, 0xe3);
    private static final Color NORMAL_BG = Color.WHITE;

    private final ScrollablePanel rows = new ScrollablePanel(new GridBagLayout());
    private final Map<String, Row> rowByName = new LinkedHashMap<>();
    private Map<String, VariableInputState> states = new LinkedHashMap<>();
    private Runnable changeListener = () -> { };
    private boolean rebuilding;

    public VariableInputPanel() {
        super(new java.awt.BorderLayout());
        setBorder(BorderFactory.createTitledBorder(
                "变量填写（推荐 {{变量}}；[[变量]] 仅用于兼容旧模板）"));
        JScrollPane scroll = new JScrollPane(rows);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        add(scroll);
        setMinimumSize(new Dimension(340, 220));
    }

    public void setChangeListener(Runnable listener) {
        changeListener = listener == null ? () -> { } : listener;
    }

    public void rebuild(Map<String, VariableInputState> newStates) {
        rebuilding = true;
        try {
            states = newStates;
            rows.removeAll();
            rowByName.clear();
            GridBagConstraints gc = new GridBagConstraints();
            gc.insets = new Insets(4, 7, 4, 7);
            gc.gridy = 0;
            gc.anchor = GridBagConstraints.WEST;
            if (states.isEmpty()) {
                gc.gridx = 0;
                gc.weightx = 1;
                gc.fill = GridBagConstraints.HORIZONTAL;
                rows.add(new JLabel("模板中没有需要填写的普通变量。"), gc);
            } else {
                addHeader(gc);
                for (VariableInputState state : states.values()) addRow(state, gc);
            }
            gc.gridy++;
            gc.gridx = 0;
            gc.weighty = 1;
            gc.fill = GridBagConstraints.VERTICAL;
            rows.add(new JLabel(), gc);
            rows.revalidate();
            rows.repaint();
        } finally {
            rebuilding = false;
        }
    }

    private void addHeader(GridBagConstraints gc) {
        String[] headers = {"变量名", "类型", "值", ""};
        for (int column = 0; column < headers.length; column++) {
            gc.gridx = column;
            gc.weightx = column == 2 ? 1 : 0;
            gc.fill = column == 2 ? GridBagConstraints.HORIZONTAL : GridBagConstraints.NONE;
            rows.add(new JLabel(headers[column]), gc);
        }
        gc.gridy++;
    }

    private void addRow(VariableInputState state, GridBagConstraints gc) {
        JLabel name = new JLabel(state.name());
        name.setPreferredSize(new Dimension(105, 25));
        name.setToolTipText(syntaxTooltip(state));
        JComboBox<VariableType> type = new JComboBox<>(VariableType.values());
        type.setSelectedItem(state.type());
        type.setPreferredSize(new Dimension(112, 26));
        type.setEnabled(!state.numericLocked());
        if (state.numericLocked()) {
            type.setToolTipText("该变量参与表达式，只能使用数值类型");
        }
        JTextField value = new JTextField();
        value.setPreferredSize(new Dimension(100, 26));
        JButton expand = new JButton("展开…");
        expand.setMargin(new Insets(2, 7, 2, 7));
        Row row = new Row(state, type, value, expand);
        rowByName.put(state.name(), row);
        configureValueField(row);

        gc.gridx = 0; gc.weightx = 0; gc.fill = GridBagConstraints.NONE;
        rows.add(name, gc);
        gc.gridx = 1; rows.add(type, gc);
        if (state.numericLocked()) {
            type.setToolTipText("数值（表达式使用）：类型已锁定");
        }
        gc.gridx = 2; gc.weightx = 1; gc.fill = GridBagConstraints.HORIZONTAL;
        rows.add(value, gc);
        gc.gridx = 3; gc.weightx = 0; gc.fill = GridBagConstraints.NONE;
        rows.add(expand, gc);
        gc.gridy++;

        type.addActionListener(e -> typeChanged(row));
        value.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { valueChanged(row); }
            @Override public void removeUpdate(DocumentEvent e) { valueChanged(row); }
            @Override public void changedUpdate(DocumentEvent e) { valueChanged(row); }
        });
        expand.addActionListener(e -> editMultiline(row));
        value.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 && row.state.type() == VariableType.MULTILINE_TEXT) {
                    editMultiline(row);
                }
            }
        });
    }

    private void configureValueField(Row row) {
        boolean multiline = row.state.type() == VariableType.MULTILINE_TEXT;
        row.field.setEditable(!multiline);
        row.expand.setVisible(multiline);
        setFieldText(row, multiline ? preview(row.state.value()) : row.state.value());
        updateTooltip(row);
        validateRow(row);
    }

    private void typeChanged(Row row) {
        if (rebuilding) return;
        VariableType selected = (VariableType) row.type.getSelectedItem();
        if (selected == null || selected == row.state.type()) return;
        row.state.setType(selected);
        configureValueField(row);
        changeListener.run();
    }

    private void valueChanged(Row row) {
        if (rebuilding || row.state.type() == VariableType.MULTILINE_TEXT) return;
        row.state.setValue(row.field.getText());
        validateRow(row);
        updateTooltip(row);
        changeListener.run();
    }

    private void editMultiline(Row row) {
        Window owner = SwingUtilities.getWindowAncestor(this);
        String edited = MultilineEditorDialog.edit(owner, row.state.name(), row.state.value());
        if (edited == null || edited.equals(row.state.value())) return;
        row.state.setValue(edited);
        configureValueField(row);
        changeListener.run();
    }

    private void setFieldText(Row row, String text) {
        rebuilding = true;
        try { row.field.setText(text); }
        finally { rebuilding = false; }
    }

    private void validateRow(Row row) {
        boolean invalid = row.state.type() == VariableType.NUMBER
                && ValueNormalizer.normalize(row.state.value()) == null;
        row.field.setBackground(invalid ? ERROR_BG : NORMAL_BG);
        row.field.setToolTipText(invalid ? "请输入有效数字；当前内容会保留" : valueTooltip(row.state));
    }

    private void updateTooltip(Row row) {
        if (row.state.type() != VariableType.NUMBER
                || ValueNormalizer.normalize(row.state.value()) != null) {
            row.field.setToolTipText(valueTooltip(row.state));
        }
    }

    public void markInvalid(Collection<String> names) {
        Row first = null;
        for (Map.Entry<String, Row> entry : rowByName.entrySet()) {
            boolean invalid = names.contains(entry.getKey());
            entry.getValue().field.setBackground(invalid ? ERROR_BG : NORMAL_BG);
            if (invalid && first == null) first = entry.getValue();
        }
        if (first != null) {
            first.field.scrollRectToVisible(new java.awt.Rectangle(0, 0,
                    first.field.getWidth(), first.field.getHeight()));
            first.field.requestFocusInWindow();
        }
    }

    public void markAllValid() {
        for (Row row : rowByName.values()) validateRow(row);
    }

    private static String syntaxTooltip(VariableInputState state) {
        String syntax = state.braceSyntax() && state.legacyMultilineSyntax()
                ? "同时使用 {{变量}} 与 [[变量]]"
                : (state.legacyMultilineSyntax() ? "旧格式 [[变量]]" : "推荐格式 {{变量}}");
        return state.numericLocked() ? syntax + "；参与表达式，类型锁定为数值" : syntax;
    }

    private static String preview(String value) {
        String compact = value.replace("\r\n", "↵ ").replace("\r", "↵ ").replace("\n", "↵ ");
        return compact.length() <= 120 ? compact : compact.substring(0, 117) + "…";
    }

    private static String valueTooltip(VariableInputState state) {
        if (state.type() != VariableType.MULTILINE_TEXT) return null;
        int lines = state.value().isEmpty() ? 0 : state.value().split("\\R", -1).length;
        return "共 " + lines + " 行 / " + state.value().length() + " 个字符；双击或点“展开…”编辑";
    }

    private record Row(VariableInputState state, JComboBox<VariableType> type,
                       JTextField field, JButton expand) { }
}
