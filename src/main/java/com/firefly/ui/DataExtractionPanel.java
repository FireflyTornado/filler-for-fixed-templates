package com.firefly.ui;

import com.firefly.application.TemplateSession;
import com.firefly.core.*;
import com.firefly.extraction.*;

import javax.swing.*;
import javax.swing.Timer;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import java.awt.*;
import java.awt.event.*;
import java.nio.file.*;
import java.util.List;
import java.util.*;

/** 表格预览、映射编辑与数据应用；不提供任何文档生成入口。 */
public final class DataExtractionPanel extends JPanel {
    private final TemplateSession session;
    private final TemplateConfigStore configStore;
    private final AppConfig appConfig;
    private final Runnable saveAppConfig, chooseTemplate, prepareApply, goToFill;
    private final FileTaskManager tasks;
    private final MappingEngine engine = new MappingEngine();
    private final JLabel flow = new JLabel("请选择 Excel 文件 → 请选择模板");
    private final JLabel status = new JLabel("打开表格后，选择数据单元格，再绑定模板变量。");
    private final JTextArea selection = new JTextArea("请选择模板变量和数据来源。", 4, 40);
    private final JLabel recordLabel = new JLabel(" ");
    private final JLabel savedLabel = new JLabel(" ");
    private final JComboBox<String> sheets = new JComboBox<>();
    private final JSpinner headerRow = spinner(1), titleColumn = new JSpinner(new SpinnerNumberModel(1, 1, 16_384, 1)), recordRow = spinner(2);
    private final JSpinner recordColumn = new JSpinner(new SpinnerNumberModel(2, 1, 16_384, 1));
    private final JPanel recordSelector = row(new JLabel("选定行列"), recordRow, recordColumn, recordLabel);
    private final JComboBox<MappingProfile.Mode> mode = new JComboBox<>(MappingProfile.Mode.values());
    private final JComboBox<MappingProfile.EmptyPolicy> emptyPolicy = new JComboBox<>(MappingProfile.EmptyPolicy.values());
    private final JLabel target = new JLabel("请在下方表格选择变量");
    private String targetVariable = "";
    private final JTable grid = new JTable() {
        @Override protected void configureEnclosingScrollPane() {
            // 建立窗口时 JTable 默认会用单层表头覆盖复合标题区。
            if (getParent() instanceof JViewport viewport && viewport.getParent() instanceof SpreadsheetPreview) return;
            super.configureEnclosingScrollPane();
        }
    };
    private SpreadsheetPreview gridScroll;
    private JSplitPane extractionSplit;
    private final JTable mappings = new JTable();
    private final JButton apply = new JButton("应用到模板变量");
    private final JButton undo = new JButton("撤销本次填入");
    private final JTextField address = new JTextField(7);
    private final Timer previewTimer, fileTimer, dividerSaveTimer;
    private SpreadsheetData workbook;
    private MappingProfile profile = MappingProfile.EMPTY;
    private String templateName = "";
    private String loadFailure = "";
    private List<MappingEngine.Preview> previews = List.of();
    private final Set<MappingProfile.Binding> confirmedFixed = new HashSet<>();
    private boolean updating, dirty, previewPending, fileChanged;
    private boolean sourcePicked, draftPending;
    private boolean adjustingDivider;
    private long loadSequence, previewSequence;
    private record PreviewResult(List<MappingEngine.Preview> saved, MappingEngine.Preview selected,
                                 boolean draft, String error) { }

    public DataExtractionPanel(TemplateSession session, TemplateConfigStore configStore, AppConfig appConfig,
                               FileTaskManager tasks, Runnable saveAppConfig, Runnable chooseTemplate,
                               Runnable prepareApply, Runnable goToFill) {
        super(new BorderLayout(5, 5));
        this.session = session; this.configStore = configStore; this.appConfig = appConfig; this.tasks = tasks;
        this.saveAppConfig = saveAppConfig; this.chooseTemplate = chooseTemplate; this.prepareApply = prepareApply; this.goToFill = goToFill;
        previewTimer = new Timer(160, event -> rebuildPreview()); previewTimer.setRepeats(false);
        fileTimer = new Timer(2000, event -> checkFileChanged());
        dividerSaveTimer = new Timer(400, event -> saveAppConfig.run()); dividerSaveTimer.setRepeats(false);
        buildUi();
    }
    private static JSpinner spinner(int value) { return new JSpinner(new SpinnerNumberModel(value, 1, 1_048_576, 1)); }
    private static JButton button(String text, Runnable action) { JButton b = new JButton(text); b.addActionListener(e -> action.run()); return b; }
    private static JPanel row(Component... components) {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 3));
        for (Component component : components) panel.add(component); return panel;
    }
    private void buildUi() {
        JPanel top = new JPanel(); top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));
        flow.setFont(flow.getFont().deriveFont(Font.BOLD));
        headerRow.setEditor(new JSpinner.NumberEditor(headerRow, "第 0 行"));
        titleColumn.setEditor(columnEditor(titleColumn));
        for (JSpinner spinner : List.of(headerRow, titleColumn)) spinner.setPreferredSize(new Dimension(100, spinner.getPreferredSize().height));
        recordRow.setEditor(new JSpinner.NumberEditor(recordRow, "第 0 行"));
        recordColumn.setEditor(new JSpinner.NumberEditor(recordColumn, "第 0 列"));
        for (JSpinner spinner : List.of(recordRow, recordColumn)) spinner.setPreferredSize(new Dimension(105, spinner.getPreferredSize().height));
        sheets.setPreferredSize(new Dimension(145, sheets.getPreferredSize().height));
        top.add(row(button("选择 Excel…", this::chooseExcel), button("刷新表格", this::reload),
                new JLabel("→"), button("选择模板…", () -> { if (flushMappings()) chooseTemplate.run(); }),
                new JLabel("地址"), address, button("定位", this::locate)));
        JPanel flowRow = new JPanel(new BorderLayout()); flowRow.setBorder(BorderFactory.createEmptyBorder(3, 6, 3, 6));
        flowRow.add(flow); top.add(flowRow);
        top.add(row(new JLabel("工作表"), sheets, new JLabel("列标题所在行"), headerRow, new JLabel("行标题所在列"), titleColumn));
        headerRow.setToolTipText("例如第 1 行写着姓名、电话、金额，就选择第 1 行。");
        titleColumn.setToolTipText("例如 A 列写着张三、李四、王五，就选择 A 列；可输入字母或列序号。");
        add(top, BorderLayout.NORTH);

        grid.setAutoResizeMode(JTable.AUTO_RESIZE_OFF); grid.setCellSelectionEnabled(true);
        for (JTable table : List.of(grid, mappings)) {
            DefaultTableCellRenderer renderer = new DefaultTableCellRenderer(); renderer.putClientProperty("html.disable", Boolean.TRUE);
            table.setDefaultRenderer(Object.class, renderer);
        }
        grid.setSelectionMode(ListSelectionModel.SINGLE_INTERVAL_SELECTION);
        grid.getSelectionModel().addListSelectionListener(e -> { if (!e.getValueIsAdjusting()) showSelection(); });
        grid.getColumnModel().getSelectionModel().addListSelectionListener(e -> { if (!e.getValueIsAdjusting()) showSelection(); });
        grid.getTableHeader().setReorderingAllowed(false);
        JPanel sourcePanel = new JPanel(new BorderLayout(3, 3));
        gridScroll = new SpreadsheetPreview(grid,
                row -> { if (mode.getSelectedItem() == MappingProfile.Mode.RECORD) recordRow.setValue(row + 1); },
                column -> {
                    if (mode.getSelectedItem() == MappingProfile.Mode.COLUMN_RECORD) recordColumn.setValue(column + 1);
                    else if (grid.getRowCount() > 0) grid.changeSelection(Math.min(value(recordRow), grid.getRowCount() - 1), column, false, false);
                });
        sourcePanel.add(gridScroll);
        selection.setEditable(false); selection.setLineWrap(true); selection.setWrapStyleWord(true);
        sourcePanel.setBorder(BorderFactory.createTitledBorder("表格预览（只读；显示公式已保存的结果）"));
        JPanel mappingPanel = new JPanel(new BorderLayout(3, 3));
        JPanel controls = new JPanel(); controls.setLayout(new BoxLayout(controls, BoxLayout.Y_AXIS));
        controls.add(row(new JLabel("定位方式"), mode, recordSelector, new JLabel("空值"), emptyPolicy));
        mode.setToolTipText("锁定列或行时按标题识别，标题位置调整后仍会尝试重新定位。");
        emptyPolicy.setToolTipText("来源为空：数值可取 0 或保留；文本取 0 会报错，保留原文会显示提示。");
        target.putClientProperty("html.disable", Boolean.TRUE);
        target.setFont(target.getFont().deriveFont(Font.BOLD));
        target.setPreferredSize(new Dimension(250, target.getPreferredSize().height));
        controls.add(row(new JLabel("当前变量"), target, button("添加／更新映射", this::bindSelected),
                button("同名绑定建议…", this::suggestBindings)));
        controls.add(row(button("改为手工填写", this::removeSelected),
                button("定位来源", this::locateSource), button("清理失效映射", this::removeOrphans), button("保存映射", () -> flushMappings()), savedLabel));
        mappingPanel.add(controls, BorderLayout.NORTH);
        mappings.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        mappings.setAutoResizeMode(JTable.AUTO_RESIZE_SUBSEQUENT_COLUMNS);
        mappings.getSelectionModel().addListSelectionListener(e -> {
            int selected = mappings.getSelectedRow();
            if (!updating && !e.getValueIsAdjusting() && selected >= 0 && selected < previews.size()) {
                targetVariable = previews.get(selected).variable();
                loadEditor();
                String error = previews.get(selected).error();
                if (!error.isEmpty()) status.setText(error);
            }
        });
        mappings.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent event) {
                // 同一行已被选中时，再次点击也能放弃草稿并恢复已保存映射。
                int row = mappings.rowAtPoint(event.getPoint());
                if (row >= 0 && row == mappings.getSelectedRow()) loadEditor();
            }
        });
        JScrollPane mappingScroll = new JScrollPane(mappings); mappingScroll.setColumnHeaderView(mappings.getTableHeader()); mappingPanel.add(mappingScroll);
        JScrollPane explanation = new JScrollPane(selection);
        explanation.setBorder(BorderFactory.createTitledBorder("当前映射的取值与替换说明"));
        mappingPanel.add(explanation, BorderLayout.SOUTH);
        mappingPanel.setBorder(BorderFactory.createTitledBorder("映射与变更预览（未绑定的变量保留手工填写）"));
        extractionSplit = new JSplitPane(JSplitPane.VERTICAL_SPLIT, sourcePanel, mappingPanel) {
            @Override public void doLayout() {
                // 预留至少四行变量；屏幕变小时只限制实际位置，不覆盖用户保存的位置。
                int minimumMapping = controls.getPreferredSize().height + explanation.getPreferredSize().height
                        + mappings.getTableHeader().getPreferredSize().height + mappings.getRowHeight() * 4 + 28;
                mappingPanel.setMinimumSize(new Dimension(300, minimumMapping));
                if (getHeight() > 0) {
                    int maximum = Math.max(60, getHeight() - getDividerSize() - minimumMapping - 4);
                    int desired = Math.max(60, Math.min(appConfig.extractionDividerLocation(), maximum));
                    if (getDividerLocation() != desired) {
                        adjustingDivider = true;
                        try { setDividerLocation(desired); } finally { adjustingDivider = false; }
                    }
                }
                super.doLayout();
            }
        };
        extractionSplit.setContinuousLayout(true); extractionSplit.setResizeWeight(0);
        extractionSplit.setDividerLocation(appConfig.extractionDividerLocation());
        extractionSplit.addPropertyChangeListener(JSplitPane.DIVIDER_LOCATION_PROPERTY, event -> {
            if (!adjustingDivider && extractionSplit.getDividerLocation() > 0) {
                appConfig.setExtractionDividerLocation(extractionSplit.getDividerLocation()); dividerSaveTimer.restart();
            }
        });
        sourcePanel.setMinimumSize(new Dimension(300, 60));
        add(extractionSplit);
        apply.addActionListener(e -> applyPreview()); undo.addActionListener(e -> undoImport());
        JPanel bottom = new JPanel(new BorderLayout());
        bottom.add(status, BorderLayout.NORTH);
        bottom.add(row(apply, undo, button("前往模板填充", goToFill)), BorderLayout.SOUTH); add(bottom, BorderLayout.SOUTH);
        apply.setEnabled(false); undo.setEnabled(false);
        sheets.addActionListener(e -> { if (!updating) sheetChanged(); });
        headerRow.addChangeListener(e -> { if (!updating) { refreshGrid(); schedulePreview(); } });
        titleColumn.addChangeListener(e -> { if (!updating) { refreshGrid(); schedulePreview(); } });
        recordRow.addChangeListener(e -> { if (!updating) { showRecord(); schedulePreview(); } });
        recordColumn.addChangeListener(e -> { if (!updating) { showRecord(); schedulePreview(); } });
        mode.addActionListener(e -> { if (!updating) { updateRecordSelector(); schedulePreview(); } });
        emptyPolicy.addActionListener(e -> { if (!updating) schedulePreview(); });
        address.addActionListener(e -> locate());
        mode.setSelectedItem(MappingProfile.Mode.TITLES);
    }

    private void loadEditor() {
        sourcePicked = false;
        updateTargetLabel();
        MappingProfile.Binding binding = profile.get(targetVariable);
        updating = true;
        try {
            if (binding != null) { mode.setSelectedItem(binding.mode()); emptyPolicy.setSelectedItem(binding.emptyPolicy()); }
        } finally { updating = false; }
        updateRecordSelector(); schedulePreview();
    }
    private void updateTargetLabel() {
        var variable = session.variables().get(targetVariable);
        String text = variable == null ? "请在下方表格选择变量" : targetVariable + "（" + variable.type() + "）";
        target.setText(text); target.setToolTipText("当前变量：" + text);
    }
    private static JComponent columnEditor(JSpinner spinner) {
        JSpinner.DefaultEditor editor = new JSpinner.DefaultEditor(spinner);
        editor.getTextField().setEditable(true);
        editor.getTextField().setFormatterFactory(new javax.swing.text.DefaultFormatterFactory(new JFormattedTextField.AbstractFormatter() {
            public Object stringToValue(String text) throws java.text.ParseException {
                String value = text.strip().toUpperCase(Locale.ROOT).replace("列", "").strip();
                try {
                    int column = 0;
                    if (value.matches("[A-Z]{1,3}")) for (char letter : value.toCharArray()) column = column * 26 + letter - 'A' + 1;
                    else column = Integer.parseInt(value);
                    if (column >= 1 && column <= 16_384) return column;
                } catch (NumberFormatException ignored) { }
                throw new java.text.ParseException("请输入 A 至 XFD 的列字母或有效列序号", 0);
            }
            public String valueToString(Object value) { return value instanceof Number number ? columnName(number.intValue() - 1) + " 列" : ""; }
        }));
        editor.getTextField().setValue(spinner.getValue()); return editor;
    }
    private void updateRecordSelector() {
        boolean rows = mode.getSelectedItem() == MappingProfile.Mode.RECORD;
        boolean columns = mode.getSelectedItem() == MappingProfile.Mode.COLUMN_RECORD;
        recordSelector.setVisible(rows || columns); recordRow.setVisible(rows); recordColumn.setVisible(columns);
        showRecord(); recordSelector.getParent().revalidate();
    }

    public void templateChanged() {
        String name = session.templateName();
        if (!name.equals(templateName)) {
            templateName = name;
            profile = name.isBlank() ? MappingProfile.EMPTY : configStore.load(name).dataExtraction();
            dirty = false; confirmedFixed.clear(); savedLabel.setText(profile.bindings().isEmpty() ? "尚无映射" : "已载入此模板的映射");
        }
        if (!session.variables().containsKey(targetVariable)) targetVariable = session.variables().keySet().stream().findFirst().orElse("");
        updateFlow(); loadEditor();
    }
    public void variablesChanged() { updateTargetLabel(); undo.setEnabled(session.canUndoImport()); schedulePreview(); }
    public void disposePanel() {
        previewTimer.stop(); fileTimer.stop();
        if (dividerSaveTimer.isRunning()) { dividerSaveTimer.stop(); saveAppConfig.run(); }
        ++loadSequence; ++previewSequence; tasks.cancelKind("excel-load"); tasks.cancelKind("mapping-preview");
    }
    public MappingProfile profile() { return profile; }
    public List<MappingEngine.Preview> previews() { return previews; }
    public SpreadsheetData workbook() { return workbook; }

    private void chooseExcel() {
        JFileChooser chooser = new JFileChooser(directory(appConfig.lastExcelDirectory()).toFile());
        chooser.setFileFilter(new FileNameExtensionFilter("Excel 工作簿 (*.xlsx)", "xlsx")); chooser.setAcceptAllFileFilterUsed(false);
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) openWorkbook(chooser.getSelectedFile().toPath());
    }
    public void openWorkbook(Path path) {
        long request = ++loadSequence; ++previewSequence; previewTimer.stop(); previewPending = true; apply.setEnabled(false);
        loadFailure = "";
        tasks.cancelKind("excel-load"); tasks.cancelKind("mapping-preview");
        status.setText("正在读取表格…");
        tasks.submit("excel-load", "读取 Excel", FileTaskManager.LockScope.NONE, true,
                progress -> new ExcelReader().read(path, progress::update, progress::checkpoint),
                loaded -> {
                    if (request != loadSequence) return;
                    installWorkbook(loaded);
                    appConfig.setLastExcelDirectory(loaded.path().getParent().toString()); saveAppConfig.run();
                }, error -> { if (request == loadSequence) { loadFailure = "读取失败，保留原表格：" + error.getMessage(); status.setText(loadFailure); status.setToolTipText(loadFailure); schedulePreview(); } },
                () -> { if (request == loadSequence) { loadFailure = "读取已取消，保留原表格。"; status.setText(loadFailure); schedulePreview(); } });
    }
    /** 只安装完整的快照；源文件名不参与映射匹配。 */
    public void installWorkbook(SpreadsheetData loaded) {
        String previousSheet = Objects.toString(sheets.getSelectedItem(), "");
        workbook = loaded; fileChanged = false; loadFailure = ""; sourcePicked = false; status.setToolTipText(null); confirmedFixed.clear();
        updating = true;
        try {
            sheets.removeAllItems(); loaded.sheets().forEach(s -> sheets.addItem(s.name()));
            if (loaded.sheets().stream().anyMatch(s -> s.name().equals(previousSheet))) sheets.setSelectedItem(previousSheet);
            else if (!profile.bindings().isEmpty() && loaded.sheets().stream().anyMatch(s -> s.name().equals(profile.bindings().get(0).sheet()))) sheets.setSelectedItem(profile.bindings().get(0).sheet());
        } finally { updating = false; }
        updateFlow(); sheetChanged(); fileTimer.start();
    }
    private void reload() { if (workbook != null) openWorkbook(workbook.path()); else chooseExcel(); }
    private void updateFlow() {
        flow.setText((workbook == null ? "未选择表格" : workbook.path().getFileName().toString()) + " → " + (templateName.isBlank() ? "未选择模板" : templateName));
        flow.setToolTipText(workbook == null ? null : workbook.path().toString());
    }
    private SpreadsheetData.Sheet sheet() {
        if (workbook == null) return null;
        return workbook.sheets().stream().filter(s -> s.name().equals(sheets.getSelectedItem())).findFirst().orElse(null);
    }
    private void sheetChanged() {
        SpreadsheetData.Sheet sheet = sheet(); if (sheet == null) return;
        sourcePicked = false;
        MappingProfile.Binding remembered = profile.bindings().stream().filter(b -> b.sheet().equals(sheet.name())).findFirst().orElse(null);
        updating = true;
        try {
            if (remembered != null) { headerRow.setValue(remembered.headerRow() + 1); titleColumn.setValue(remembered.titleColumn() + 1); }
            recordRow.setValue(Math.min(Math.max(value(headerRow) + 2, 1), Math.max(sheet.rows(), 1)));
            recordColumn.setValue(Math.min(Math.max(value(titleColumn) + 2, 1), Math.min(Math.max(sheet.columns(), 1), 16_384)));
        } finally { updating = false; }
        refreshGrid(); schedulePreview();
    }
    private static int value(JSpinner spinner) { return ((Number) spinner.getValue()).intValue() - 1; }
    private void refreshGrid() {
        SpreadsheetData.Sheet sheet = sheet();
        int selectedRow = grid.getSelectedRow(), selectedColumn = grid.getSelectedColumn();
        updating = true;
        try { grid.setModel(new AbstractTableModel() {
            public int getRowCount() { return sheet == null ? 0 : sheet.rows(); }
            public int getColumnCount() { return sheet == null ? 0 : sheet.columns(); }
            public String getColumnName(int column) { return columnName(column); }
            public Object getValueAt(int row, int column) {
                SpreadsheetData.Cell cell = sheet.cell(row, column);
                return cell.error().isEmpty() ? cell.display() : "⚠ " + cell.error();
            }
        });
            for (int c = 0; c < grid.getColumnCount(); c++) grid.getColumnModel().getColumn(c).setPreferredWidth(140);
            gridScroll.refresh(sheet, value(headerRow), value(titleColumn));
            UiFontManager.updateTableRowHeight(grid); UiFontManager.updateTableRowHeight(mappings);
            if (selectedRow >= 0 && selectedColumn >= 0 && selectedRow < grid.getRowCount() && selectedColumn < grid.getColumnCount()) grid.changeSelection(selectedRow, selectedColumn, false, false);
        } finally { updating = false; }
        showRecord();
    }
    private void showRecord() {
        recordLabel.setText(mode.getSelectedItem() == MappingProfile.Mode.COLUMN_RECORD ? "（" + columnName(value(recordColumn)) + " 列）" : "");
    }
    private void showSelection() {
        if (updating) return;
        SpreadsheetData.Sheet sheet = sheet(); int row = grid.getSelectedRow(), column = grid.getSelectedColumn();
        if (sheet == null || row < 0 || column < 0) { schedulePreview(); return; }
        sourcePicked = true;
        address.setText(SpreadsheetData.address(row, column)); schedulePreview();
    }
    private static String columnName(int column) { return SpreadsheetData.address(0, column).replace("1", ""); }
    private void locate() {
        try {
            org.apache.poi.ss.util.CellReference reference = new org.apache.poi.ss.util.CellReference(address.getText().strip().toUpperCase(Locale.ROOT));
            selectCell(reference.getRow(), reference.getCol());
        } catch (RuntimeException e) { status.setText("请输入有效地址，例如 D8。"); }
    }
    private void selectCell(int row, int column) {
        if (row < 0 || column < 0 || row >= grid.getRowCount() || column >= grid.getColumnCount()) throw new IllegalArgumentException("地址超出当前工作表范围");
        grid.changeSelection(row, column, false, false); grid.scrollRectToVisible(grid.getCellRect(row, column, true));
    }
    private void bindSelected() {
        if (sheet() == null || targetVariable.isEmpty() || templateName.isBlank()) { status.setText("请先选择表格和模板变量。"); return; }
        try {
            MappingProfile.Binding binding = editorBinding();
            profile = profile.put(binding); confirmedFixed.add(binding);
            sourcePicked = false;
            mappingChanged();
        } catch (IllegalArgumentException e) { status.setText(e.getMessage()); }
    }
    private MappingProfile.Binding editorBinding() {
        String variable = targetVariable;
        if (!session.variables().containsKey(variable)) throw new IllegalArgumentException("请先选择模板变量。");
        MappingProfile.Mode selectedMode = (MappingProfile.Mode) mode.getSelectedItem();
        MappingProfile.EmptyPolicy policy = (MappingProfile.EmptyPolicy) emptyPolicy.getSelectedItem();
        MappingProfile.Binding existing = profile.get(variable);
        if (!sourcePicked && existing != null && existing.mode() == selectedMode) return existing.withEmptyPolicy(policy);
        if (sheet() == null) throw new IllegalArgumentException("请先打开 Excel 文件。");
        int row = grid.getSelectedRow(), column = grid.getSelectedColumn();
        if (!sourcePicked && existing != null) {
            MappingEngine.Match match = previews.stream().filter(p -> p.variable().equals(variable)).map(MappingEngine.Preview::match).filter(Objects::nonNull).findFirst().orElse(null);
            if (match == null || !match.sheet().name().equals(sheet().name())) throw new IllegalArgumentException("请在表格中选择新的数据来源。");
            row = match.row(); column = match.column();
        } else if (grid.getSelectedRowCount() != 1 || grid.getSelectedColumnCount() != 1 || column < 0) {
            throw new IllegalArgumentException("请选择一个数据单元格后绑定。");
        }
        if (selectedMode == MappingProfile.Mode.RECORD) row = value(recordRow);
        if (selectedMode == MappingProfile.Mode.COLUMN_RECORD) column = value(recordColumn);
        return engine.bind(variable, sheet(), selectedMode, value(headerRow), value(titleColumn), row, column, policy);
    }
    private String selectedVariable() {
        return targetVariable;
    }
    private void removeSelected() {
        profile = profile.remove(selectedVariable()); sourcePicked = false;
        updating = true;
        try { grid.clearSelection(); } finally { updating = false; }
        mappingChanged();
    }
    private void removeOrphans() {
        Set<String> active = session.variables().keySet();
        List<String> orphan = profile.bindings().stream().map(MappingProfile.Binding::variable).filter(name -> !active.contains(name)).toList();
        if (orphan.isEmpty()) { status.setText("没有已从模板移除的变量映射。"); return; }
        if (JOptionPane.showConfirmDialog(this, "删除以下已不在模板中的映射？\n" + String.join("、", orphan), "清理失效映射", JOptionPane.OK_CANCEL_OPTION) == JOptionPane.OK_OPTION) {
            for (String name : orphan) profile = profile.remove(name); mappingChanged();
        }
    }
    private void mappingChanged() { dirty = true; flushMappings(); schedulePreview(); }
    public boolean flushMappings() {
        if (!dirty || templateName.isBlank()) return true;
        try { configStore.saveMapping(templateName, profile); dirty = false; savedLabel.setText("映射已保存"); savedLabel.setToolTipText(null); return true; }
        catch (Exception e) { savedLabel.setText("映射未保存"); savedLabel.setToolTipText(e.getMessage()); status.setText("映射保存失败，请重试：" + e.getMessage()); return false; }
    }
    private void suggestBindings() {
        if (sheet() == null || templateName.isBlank()) return;
        boolean byColumn = mode.getSelectedItem() == MappingProfile.Mode.COLUMN_RECORD;
        List<MappingProfile.Binding> candidates = new ArrayList<>();
        for (String variable : session.variables().keySet()) {
            if (profile.get(variable) != null) continue;
            if (byColumn) {
                List<Integer> rows = new ArrayList<>();
                for (int r = 0; r < sheet().rows(); r++) if (SpreadsheetData.normalize(sheet().cell(r, value(titleColumn)).display()).equals(SpreadsheetData.normalize(variable))) rows.add(r);
                if (rows.size() == 1 && value(recordColumn) > value(titleColumn) && value(recordColumn) < sheet().columns()) {
                    candidates.add(engine.bind(variable, sheet(), MappingProfile.Mode.COLUMN_RECORD, value(headerRow), value(titleColumn), rows.get(0), value(recordColumn), (MappingProfile.EmptyPolicy) emptyPolicy.getSelectedItem()));
                }
                continue;
            }
            List<Integer> columns = new ArrayList<>();
            for (int c = 0; c < sheet().columns(); c++) if (SpreadsheetData.normalize(sheet().cell(value(headerRow), c).display()).equals(SpreadsheetData.normalize(variable))) columns.add(c);
            if (columns.size() == 1 && value(recordRow) > value(headerRow) && value(recordRow) < sheet().rows()) {
                candidates.add(engine.bind(variable, sheet(), MappingProfile.Mode.RECORD, value(headerRow), value(titleColumn), value(recordRow), columns.get(0), (MappingProfile.EmptyPolicy) emptyPolicy.getSelectedItem()));
            }
        }
        if (candidates.isEmpty()) { status.setText("没有唯一同名" + (byColumn ? "行" : "列") + "可建议绑定，请检查标题及选定行列。"); return; }
        String text = String.join("\n", candidates.stream().map(b -> (byColumn ? b.rowTitle() : b.columnTitle()) + " → " + b.variable() + "（名称一致）").toList());
        JTextArea details = new JTextArea(text, Math.min(12, candidates.size()), 35); details.setEditable(false);
        if (JOptionPane.showConfirmDialog(this, new JScrollPane(details), "确认选定" + (byColumn ? "列" : "行") + "的同名绑定建议", JOptionPane.OK_CANCEL_OPTION) == JOptionPane.OK_OPTION) {
            for (var binding : candidates) profile = profile.put(binding); sourcePicked = false; mappingChanged();
        }
    }
    private void schedulePreview() {
        ++previewSequence; previewPending = true; apply.setEnabled(false); previewTimer.restart();
        selection.setText("正在检查选定行列及替换内容…当前模板变量尚未因本次选择而修改。");
        undo.setEnabled(session.canUndoImport());
    }
    public void rebuildPreview() {
        previewTimer.stop(); long request = ++previewSequence;
        SpreadsheetData source = workbook; MappingProfile rules = profile; var variables = session.variables();
        String activeSheet = Objects.toString(sheets.getSelectedItem(), ""); int record = value(recordRow), column = value(recordColumn);
        Set<MappingProfile.Binding> fixed = Set.copyOf(confirmedFixed);
        MappingProfile.Binding candidate = null; String candidateError = "";
        try { candidate = editorBinding(); } catch (IllegalArgumentException e) { candidateError = e.getMessage(); }
        final MappingProfile.Binding editor = candidate;
        final String editorError = candidateError;
        final String selectedVariable = targetVariable;
        MappingProfile.Binding savedEditor = rules.get(selectedVariable);
        final boolean pending = editor != null ? !editor.equals(savedEditor) : sourcePicked || (savedEditor != null
                && (savedEditor.mode() != mode.getSelectedItem() || savedEditor.emptyPolicy() != emptyPolicy.getSelectedItem()));
        final boolean confirmedEditor = sourcePicked || (savedEditor != null && fixed.contains(savedEditor));
        tasks.cancelKind("mapping-preview");
        if (source == null) {
            previews = engine.preview(null, rules, variables, activeSheet, record, column, fixed, () -> { });
            previewPending = false; draftPending = false; showPreviews(); selection.setText("请先打开 Excel 文件并选择模板变量。"); return;
        }
        tasks.submit("mapping-preview", "检查映射", FileTaskManager.LockScope.NONE, true,
                progress -> {
                    List<MappingEngine.Preview> saved = engine.preview(source, rules, variables, activeSheet, record, column, fixed, progress::checkpoint);
                    MappingEngine.Preview selected = null;
                    if (editor != null) selected = engine.preview(source, MappingProfile.EMPTY.put(editor),
                            Map.of(editor.variable(), variables.get(editor.variable())), activeSheet, record, column,
                            confirmedEditor ? Set.of(editor) : Set.of(), progress::checkpoint).get(0);
                    return new PreviewResult(saved, selected, pending, editorError);
                },
                result -> {
                    if (request != previewSequence) return;
                    previews = result.saved(); previewPending = false; draftPending = result.draft();
                    showPreviews(); showReplacement(result, selectedVariable);
                },
                error -> { if (request == previewSequence) { previewPending = false; apply.setEnabled(false); status.setText("映射检查失败：" + error.getMessage()); selection.setText("映射检查失败：" + error.getMessage()); } },
                () -> { if (request == previewSequence) { previewPending = true; status.setText("映射检查已取消，请修改选择或刷新表格后重试。"); selection.setText("检查已取消，尚未应用。请修改选择或刷新表格后重试。"); } });
    }
    private void showReplacement(PreviewResult result, String variable) {
        MappingEngine.Preview p = result.selected();
        if (p == null) {
            selection.setText("模板「" + templateName + "」 · 变量「" + variable + "」\n" + result.error() + "\n上方映射表显示已保存映射；尚未应用。"); return;
        }
        String origin = p.match() == null ? "尚未找到有效来源" : "工作表「" + p.match().sheet().name() + "」第 " + (p.match().row() + 1)
                + " 行、" + columnName(p.match().column()) + " 列（" + SpreadsheetData.address(p.match().row(), p.match().column()) + "），内容：" + displayValue(p.display());
        String destination = "模板「" + templateName + "」的变量「" + p.variable() + "」，原值：" + displayValue(p.oldValue());
        String outcome = !p.error().isEmpty() ? "无法填入：" + p.error() : p.apply()
                ? "将填入：" + displayValue(p.value()) + "；" + p.status() : p.status() + "，不替换现有内容。";
        String state = result.draft() ? "这是待保存的映射预览；请先点击“添加／更新映射”，再应用。" : "以上为已保存映射的取值预览；点击“应用到模板变量”才会填入。";
        if (fileChanged) state = "源文件已变化，此预览不可应用，请先刷新表格。";
        selection.setText(origin + "\n" + destination + "\n" + outcome + "\n" + state);
        selection.setCaretPosition(0);
    }
    private static String displayValue(String value) {
        if (value.isEmpty()) return "（空）";
        String text = value.replace("\r", "").replace("\n", " ↵ ");
        return "「" + (text.length() > 180 ? text.substring(0, 180) + "…" : text) + "」";
    }
    private void showPreviews() {
        updating = true;
        try { mappings.setModel(new AbstractTableModel() {
            final String[] names = {"模板变量（点击选择）", "数据来源 →", "单元格显示", "将填入的值", "当前变量值", "状态／问题"};
            public int getRowCount() { return previews.size(); }
            public int getColumnCount() { return names.length; }
            public String getColumnName(int column) { return names[column]; }
            public Object getValueAt(int row, int column) {
                var p = previews.get(row);
                return switch (column) { case 0 -> p.variable(); case 1 -> p.source(); case 2 -> p.display(); case 3 -> p.value(); case 4 -> p.oldValue(); default -> p.error().isEmpty() ? p.status() : p.error(); };
            }
        });
            for (int r = 0; r < previews.size(); r++) if (previews.get(r).variable().equals(targetVariable)) { mappings.setRowSelectionInterval(r, r); break; }
        } finally { updating = false; }
        long errors = previews.stream().filter(p -> !p.error().isEmpty()).count();
        long ready = previews.stream().filter(MappingEngine.Preview::apply).count();
        long manual = previews.stream().filter(p -> profile.get(p.variable()) == null).count();
        long orphan = profile.bindings().stream().filter(b -> !session.variables().containsKey(b.variable())).count();
        status.setText(fileChanged ? "源文件已变化，请刷新表格后重新检查。当前变量未被修改。" : previews.size() + " 个变量：" + ready + " 个可应用，" + manual + " 个手工填写，" + errors + " 个需要处理" + (orphan > 0 ? "；另有 " + orphan + " 条已不在模板中的映射（保留但不应用）" : ""));
        if (!loadFailure.isEmpty() && !fileChanged) status.setText(loadFailure + "；" + status.getText());
        apply.setEnabled(!previewPending && !draftPending && !fileChanged && errors == 0 && ready > 0 && !tasks.hasTask("excel-load"));
        undo.setEnabled(session.canUndoImport());
    }
    public void applyPreview() {
        if (previewPending || workbook == null || tasks.hasTask("excel-load")) return;
        if (draftPending) { status.setText("请先添加／更新当前映射，或重新选择已保存映射后应用。"); return; }
        prepareApply.run();
        if (previewPending) { status.setText("模板内容有变化，正在重新检查映射；完成后请再次应用。"); return; }
        checkFileChanged(); if (fileChanged) return;
        if (previews.stream().anyMatch(p -> !p.error().isEmpty())) { status.setText("请先修复有问题的映射。"); return; }
        Map<String, String> values = new LinkedHashMap<>(), sources = new LinkedHashMap<>();
        for (var p : previews) if (p.apply()) {
            if (!session.variable(p.variable()).value().equals(p.oldValue())) { schedulePreview(); return; }
            values.put(p.variable(), p.value()); sources.put(p.variable(), workbook.path().getFileName() + " / " + p.source());
        }
        try { session.applyImportedValues(values, sources); status.setText("已填入 " + values.size() + " 个变量，可前往“模板填充”调整并生成。"); schedulePreview(); }
        catch (IllegalArgumentException e) { status.setText("未应用：" + e.getMessage()); }
    }
    private void undoImport() {
        Set<String> conflicts = session.undoImport(); schedulePreview();
        if (!conflicts.isEmpty()) JOptionPane.showMessageDialog(this, "已保留后续手工修改的变量：" + String.join("、", conflicts), "部分撤销", JOptionPane.INFORMATION_MESSAGE);
    }
    private void locateSource() {
        int row = mappings.getSelectedRow();
        if (row < 0 || row >= previews.size() || previews.get(row).match() == null) return;
        var match = previews.get(row).match();
        if (!Objects.equals(sheets.getSelectedItem(), match.sheet().name())) sheets.setSelectedItem(match.sheet().name());
        updating = true;
        try { selectCell(match.row(), match.column()); address.setText(SpreadsheetData.address(match.row(), match.column())); }
        finally { updating = false; }
    }
    private void checkFileChanged() {
        if (workbook == null || fileChanged) return;
        try { fileChanged = Files.getLastModifiedTime(workbook.path()).toMillis() != workbook.modified() || Files.size(workbook.path()) != workbook.size(); }
        catch (Exception e) { fileChanged = true; }
        if (fileChanged) { apply.setEnabled(false); status.setText("源文件已更新或不可访问，请刷新表格；现有变量保持原值。"); selection.setText("源文件已更新或不可访问，当前取值预览已失效。请先刷新表格；现有模板变量保持原值。"); }
    }
    private static Path directory(String saved) {
        try { if (saved != null && Files.isDirectory(Path.of(saved))) return Path.of(saved); } catch (RuntimeException ignored) { }
        return Path.of("").toAbsolutePath();
    }
}
