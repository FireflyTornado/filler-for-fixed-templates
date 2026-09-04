package com.firefly.ui;

import com.firefly.extraction.SpreadsheetData;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.function.IntConsumer;

/** 数据区只含 Excel 单元格；行列编号和标题独立显示，并随数据区同步滚动。 */
final class SpreadsheetPreview extends JScrollPane {
    private final JTable grid;
    private final JTable rowTitles = tooltipTable(false);
    private final JTable columnTitles = tooltipTable(true);
    private final JPanel top = new JPanel(new BorderLayout());

    SpreadsheetPreview(JTable grid, IntConsumer selectRow, IntConsumer selectColumn) {
        super(grid);
        this.grid = grid;
        rowTitles.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        rowTitles.setRowSelectionAllowed(false);
        rowTitles.setCellSelectionEnabled(false);
        rowTitles.setTableHeader(null);
        rowTitles.setFocusable(false);
        columnTitles.setAutoCreateColumnsFromModel(false);
        columnTitles.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        columnTitles.setColumnModel(grid.getColumnModel());
        columnTitles.setTableHeader(null);
        columnTitles.setFocusable(false);
        columnTitles.setRowSelectionAllowed(false);
        for (JTable table : new JTable[]{rowTitles, columnTitles}) {
            DefaultTableCellRenderer renderer = new DefaultTableCellRenderer() {
                @Override public Component getTableCellRendererComponent(JTable table, Object value, boolean selected,
                                                                         boolean focus, int row, int column) {
                    Component component = super.getTableCellRendererComponent(table, value, selected, false, row, column);
                    if (!selected) { component.setBackground(UIManager.getColor("TableHeader.background")); component.setForeground(UIManager.getColor("TableHeader.foreground")); }
                    return component;
                }
            };
            renderer.putClientProperty("html.disable", Boolean.TRUE);
            table.setDefaultRenderer(Object.class, renderer);
        }
        top.add(grid.getTableHeader(), BorderLayout.NORTH);
        top.add(columnTitles, BorderLayout.CENTER);
        setColumnHeaderView(top);
        setRowHeaderView(rowTitles);
        JPanel corner = new JPanel(new GridLayout(2, 1));
        corner.add(cornerLine("位置编号", "列号 →"));
        corner.add(cornerLine("行号", "行标题 ↓ / 列标题 →"));
        setCorner(UPPER_LEFT_CORNER, corner);
        rowTitles.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent event) {
                int row = rowTitles.rowAtPoint(event.getPoint()); if (row >= 0) selectRow.accept(row);
            }
        });
        MouseAdapter columns = new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent event) {
                int column = grid.getColumnModel().getColumnIndexAtX(event.getX()); if (column >= 0) selectColumn.accept(column);
            }
        };
        grid.getTableHeader().addMouseListener(columns); columnTitles.addMouseListener(columns);
        grid.addPropertyChangeListener("rowHeight", event -> syncRowHeights());
        grid.addPropertyChangeListener("font", event -> syncRowHeights());
        syncRowHeights();
    }

    private static JPanel cornerLine(String first, String second) {
        JPanel panel = new JPanel(new BorderLayout());
        JLabel left = new JLabel(first, SwingConstants.CENTER); left.setPreferredSize(new Dimension(58, 1));
        JLabel right = new JLabel(second, SwingConstants.CENTER);
        panel.add(left, BorderLayout.WEST); panel.add(right, BorderLayout.CENTER);
        panel.setBorder(UIManager.getBorder("TableHeader.cellBorder"));
        return panel;
    }
    private static JTable tooltipTable(boolean sharedColumns) {
        return new JTable() {
            @Override public boolean isCellSelected(int row, int column) {
                // 标题栏与数据表共享列模型以同步列宽，但标题栏自身不显示选择色。
                return false;
            }
            @Override public void doLayout() {
                // 列宽由数据表唯一管理；标题行不能按整个视口宽度再次拉伸共享列。
                if (!sharedColumns) super.doLayout();
            }
            @Override public String getToolTipText(MouseEvent event) {
                int row = rowAtPoint(event.getPoint()), column = columnAtPoint(event.getPoint());
                return row < 0 || column < 0 ? null : "标题：" + getValueAt(row, column);
            }
        };
    }
    private void syncRowHeights() {
        rowTitles.setRowHeight(grid.getRowHeight()); columnTitles.setRowHeight(grid.getRowHeight());
        columnTitles.setPreferredScrollableViewportSize(new Dimension(0, grid.getRowHeight()));
        top.revalidate();
    }
    void refresh(SpreadsheetData.Sheet sheet, int headerRow, int titleColumn) {
        rowTitles.setModel(new AbstractTableModel() {
            public int getRowCount() { return sheet == null ? 0 : sheet.rows(); }
            public int getColumnCount() { return 2; }
            public Object getValueAt(int row, int column) { return column == 0 ? row + 1 : sheet.cell(row, titleColumn).display(); }
        });
        rowTitles.getColumnModel().getColumn(0).setPreferredWidth(58);
        rowTitles.getColumnModel().getColumn(0).setWidth(58);
        rowTitles.getColumnModel().getColumn(1).setPreferredWidth(165);
        rowTitles.getColumnModel().getColumn(1).setWidth(165);
        rowTitles.setPreferredScrollableViewportSize(new Dimension(223, 0));
        columnTitles.setModel(new AbstractTableModel() {
            public int getRowCount() { return 1; }
            public int getColumnCount() { return sheet == null ? 0 : sheet.columns(); }
            public Object getValueAt(int row, int column) { return sheet.cell(headerRow, column).display(); }
        });
        syncRowHeights();
    }
}
