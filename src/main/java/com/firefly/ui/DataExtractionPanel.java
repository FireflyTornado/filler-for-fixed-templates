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
    private final JTextArea selection = new JTextArea("尚未选择单元格", 2, 40);
    private final JLabel recordLabel = new JLabel(" ");
    private final JLabel savedLabel = new JLabel(" ");
    private final JComboBox<String> sheets = new JComboBox<>();
    private final JSpinner headerRow = spinner(1), titleColumn = spinner(1), recordRow = spinner(2);
    private final JComboBox<MappingProfile.Mode> mode = new JComboBox<>(MappingProfile.Mode.values());
    private final JComboBox<MappingProfile.EmptyPolicy> emptyPolicy = new JComboBox<>(MappingProfile.EmptyPolicy.values());
    private final JComboBox<String> target = new JComboBox<>();
    private final JTable grid = new JTable();
    private final JTable mappings = new JTable();
    private final JButton apply = new JButton("应用到模板变量");
    private final JButton undo = new JButton("撤销本次填入");
    private final JTextField address = new JTextField(7);
    private final Timer previewTimer, fileTimer;
    private SpreadsheetData workbook;
    private MappingProfile profile = MappingProfile.EMPTY;
    private String templateName = "";
    private String loadFailure = "";
    private List<MappingEngine.Preview> previews = List.of();
    private final Set<MappingProfile.Binding> confirmedFixed = new HashSet<>();
    private boolean updating, dirty, previewPending, fileChanged;
    private long loadSequence, previewSequence;

    public DataExtractionPanel(TemplateSession session, TemplateConfigStore configStore, AppConfig appConfig,
                               FileTaskManager tasks, Runnable saveAppConfig, Runnable chooseTemplate,
                               Runnable prepareApply, Runnable goToFill) {
        super(new BorderLayout(5, 5));
        this.session = session; this.configStore = configStore; this.appConfig = appConfig; this.tasks = tasks;
        this.saveAppConfig = saveAppConfig; this.chooseTemplate = chooseTemplate; this.prepareApply = prepareApply; this.goToFill = goToFill;
        previewTimer = new Timer(160, event -> rebuildPreview()); previewTimer.setRepeats(false);
        fileTimer = new Timer(2000, event -> checkFileChanged());
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
        for (JSpinner spinner : List.of(headerRow, titleColumn, recordRow)) spinner.setPreferredSize(new Dimension(70, spinner.getPreferredSize().height));
        sheets.setPreferredSize(new Dimension(145, sheets.getPreferredSize().height));
        top.add(row(button("选择 Excel…", this::chooseExcel), button("刷新表格", this::reload),
                new JLabel("→"), button("选择模板…", () -> { if (flushMappings()) chooseTemplate.run(); })));
        JPanel flowRow = new JPanel(new BorderLayout()); flowRow.setBorder(BorderFactory.createEmptyBorder(3, 6, 3, 6));
        flowRow.add(flow); top.add(flowRow);
        top.add(row(new JLabel("工作表"), sheets, new JLabel("表头行"), headerRow, new JLabel("行标题列（A=1）"), titleColumn,
                new JLabel("当前记录行"), recordRow));
        top.add(row(new JLabel("地址"), address, button("定位", this::locate), recordLabel));
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
        grid.getTableHeader().addMouseListener(new MouseAdapter() {
            public void mouseClicked(MouseEvent event) {
                int column = grid.columnAtPoint(event.getPoint());
                if (column >= 0 && grid.getRowCount() > 0) grid.changeSelection(Math.min(value(recordRow), grid.getRowCount() - 1), column, false, false);
            }
        });
        JPanel sourcePanel = new JPanel(new BorderLayout(3, 3));
        JScrollPane gridScroll = new JScrollPane(grid); gridScroll.setColumnHeaderView(grid.getTableHeader()); sourcePanel.add(gridScroll);
        selection.setEditable(false); selection.setLineWrap(true); selection.setWrapStyleWord(true);
        sourcePanel.add(new JScrollPane(selection), BorderLayout.SOUTH);
        sourcePanel.setBorder(BorderFactory.createTitledBorder("表格预览（只读；显示公式已保存的结果）"));
        JPanel mappingPanel = new JPanel(new BorderLayout(3, 3));
        JPanel controls = new JPanel(); controls.setLayout(new BoxLayout(controls, BoxLayout.Y_AXIS));
        controls.add(row(new JLabel("定位方式"), mode, new JLabel("空值"), emptyPolicy));
        target.setPreferredSize(new Dimension(150, target.getPreferredSize().height));
        target.setRenderer(new DefaultListCellRenderer() {
            @Override public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean selected, boolean focus) {
                JLabel label = (JLabel) super.getListCellRendererComponent(list, value, index, selected, focus);
                if (value != null && session.variables().containsKey(value.toString())) {
                    var state = session.variable(value.toString());
                    label.setText(value + "（" + state.type() + "）");
                }
                return label;
            }
        });
        controls.add(row(new JLabel("选中数据 → 变量"), target, button("添加／更新映射", this::bindSelected),
                button("同名绑定建议…", this::suggestBindings)));
        controls.add(row(button("改为手工填写", this::removeSelected), button("启用／停用", this::toggleSelected),
                button("定位来源", this::locateSource), button("清理失效映射", this::removeOrphans), button("保存映射", () -> flushMappings()), savedLabel));
        mappingPanel.add(controls, BorderLayout.NORTH);
        mappings.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        mappings.setAutoResizeMode(JTable.AUTO_RESIZE_SUBSEQUENT_COLUMNS);
        mappings.getSelectionModel().addListSelectionListener(e -> {
            int selected = mappings.getSelectedRow();
            if (!e.getValueIsAdjusting() && selected >= 0 && selected < previews.size()) {
                target.setSelectedItem(previews.get(selected).variable());
                MappingProfile.Binding binding = profile.get(previews.get(selected).variable());
                if (binding != null) { mode.setSelectedItem(binding.mode()); emptyPolicy.setSelectedItem(binding.emptyPolicy()); }
                String error = previews.get(selected).error();
                if (!error.isEmpty()) status.setText(error);
            }
        });
        JScrollPane mappingScroll = new JScrollPane(mappings); mappingScroll.setColumnHeaderView(mappings.getTableHeader()); mappingPanel.add(mappingScroll);
        mappingPanel.setBorder(BorderFactory.createTitledBorder("映射与变更预览（未绑定的变量保留手工填写）"));
        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, sourcePanel, mappingPanel);
        split.setResizeWeight(0.48); split.setDividerLocation(240);
        sourcePanel.setMinimumSize(new Dimension(300, 120)); mappingPanel.setMinimumSize(new Dimension(300, 210));
        add(split);
        apply.addActionListener(e -> applyPreview()); undo.addActionListener(e -> undoImport());
        JPanel bottom = new JPanel(new BorderLayout());
        bottom.add(status, BorderLayout.NORTH);
        bottom.add(row(apply, undo, button("前往模板填充", goToFill)), BorderLayout.SOUTH); add(bottom, BorderLayout.SOUTH);
        apply.setEnabled(false); undo.setEnabled(false);
        sheets.addActionListener(e -> { if (!updating) sheetChanged(); });
        headerRow.addChangeListener(e -> { if (!updating) { refreshGrid(); schedulePreview(); } });
        titleColumn.addChangeListener(e -> { if (!updating) { showSelection(); schedulePreview(); } });
        recordRow.addChangeListener(e -> { if (!updating) { showRecord(); schedulePreview(); } });
        address.addActionListener(e -> locate());
        mode.setSelectedItem(MappingProfile.Mode.TITLES);
    }

    public void templateChanged() {
        String name = session.templateName();
        if (!name.equals(templateName)) {
            templateName = name;
            profile = name.isBlank() ? MappingProfile.EMPTY : configStore.load(name).dataExtraction();
            dirty = false; confirmedFixed.clear(); savedLabel.setText(profile.bindings().isEmpty() ? "尚无映射" : "已载入此模板的映射");
        }
        Object previous = target.getSelectedItem(); target.removeAllItems();
        session.variables().keySet().forEach(target::addItem);
        if (previous != null && session.variables().containsKey(previous.toString())) target.setSelectedItem(previous);
        updateFlow(); schedulePreview();
    }
    public void variablesChanged() { undo.setEnabled(session.canUndoImport()); schedulePreview(); }
    public void disposePanel() { previewTimer.stop(); fileTimer.stop(); ++loadSequence; ++previewSequence; tasks.cancelKind("excel-load"); tasks.cancelKind("mapping-preview"); }
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
        workbook = loaded; fileChanged = false; loadFailure = ""; status.setToolTipText(null); confirmedFixed.clear();
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
        MappingProfile.Binding remembered = profile.bindings().stream().filter(b -> b.sheet().equals(sheet.name())).findFirst().orElse(null);
        updating = true;
        try {
            if (remembered != null) { headerRow.setValue(remembered.headerRow() + 1); titleColumn.setValue(remembered.titleColumn() + 1); }
            recordRow.setValue(Math.min(Math.max(value(headerRow) + 2, 1), Math.max(sheet.rows(), 1)));
        } finally { updating = false; }
        refreshGrid(); schedulePreview();
    }
    private static int value(JSpinner spinner) { return ((Number) spinner.getValue()).intValue() - 1; }
    private void refreshGrid() {
        SpreadsheetData.Sheet sheet = sheet();
        grid.setModel(new AbstractTableModel() {
            public int getRowCount() { return sheet == null ? 0 : sheet.rows(); }
            public int getColumnCount() { return sheet == null ? 0 : sheet.columns() + 1; }
            public String getColumnName(int column) {
                if (column == 0) return "行号";
                return SpreadsheetData.address(0, column - 1).replace("1", "") + " · " + sheet.cell(value(headerRow), column - 1).display();
            }
            public Object getValueAt(int row, int column) {
                if (column == 0) return row + 1;
                SpreadsheetData.Cell cell = sheet.cell(row, column - 1);
                return cell.error().isEmpty() ? cell.display() : "⚠ " + cell.error();
            }
        });
        for (int c = 0; c < grid.getColumnCount(); c++) grid.getColumnModel().getColumn(c).setPreferredWidth(c == 0 ? 55 : 130);
        showRecord(); showSelection();
    }
    private void showRecord() {
        SpreadsheetData.Sheet sheet = sheet();
        recordLabel.setText(sheet == null ? "" : "当前记录：" + (value(recordRow) + 1) + " 行 · " + sheet.cell(value(recordRow), value(titleColumn)).display());
    }
    private void showSelection() {
        SpreadsheetData.Sheet sheet = sheet(); int row = grid.getSelectedRow(), column = grid.getSelectedColumn() - 1;
        if (sheet != null && row >= 0 && column == -1 && grid.getSelectedRowCount() == 1) {
            recordRow.setValue(row + 1); selection.setText("已选择当前记录：第 " + (row + 1) + " 行。点击数据单元格可创建映射。"); return;
        }
        if (sheet == null || row < 0 || column < 0) { selection.setText("请选择数据单元格（行号与列标题仅用于定位）。"); return; }
        SpreadsheetData.Cell cell = sheet.cell(row, column);
        String text = SpreadsheetData.address(row, column) + " · 行标题：" + sheet.cell(row, value(titleColumn)).display()
                + " · 列标题：" + sheet.cell(value(headerRow), column).display() + " · 内容：" + cell.display()
                + (cell.formula() ? "（公式已保存结果）" : "") + (cell.error().isEmpty() ? "" : " · " + cell.error());
        selection.setText(text); selection.setToolTipText(text); address.setText(SpreadsheetData.address(row, column));
        if (grid.getSelectedRowCount() > 1 || grid.getSelectedColumnCount() > 1) selection.setText("已选择区域；创建映射前请选择一个单元格，或使用当前记录模式。");
    }
    private void locate() {
        try {
            org.apache.poi.ss.util.CellReference reference = new org.apache.poi.ss.util.CellReference(address.getText().strip().toUpperCase(Locale.ROOT));
            selectCell(reference.getRow(), reference.getCol());
        } catch (RuntimeException e) { status.setText("请输入有效地址，例如 D8。"); }
    }
    private void selectCell(int row, int column) {
        if (row < 0 || column < 0 || row >= grid.getRowCount() || column + 1 >= grid.getColumnCount()) throw new IllegalArgumentException("地址超出当前工作表范围");
        grid.changeSelection(row, column + 1, false, false); grid.scrollRectToVisible(grid.getCellRect(row, column + 1, true));
    }
    private void bindSelected() {
        if (sheet() == null || target.getSelectedItem() == null || templateName.isBlank()) { status.setText("请先选择表格和模板变量。"); return; }
        if (grid.getSelectedRowCount() != 1 || grid.getSelectedColumnCount() != 1) { status.setText("请选择一个数据单元格后绑定。"); return; }
        try {
            MappingProfile.Binding binding = engine.bind(target.getSelectedItem().toString(), sheet(), (MappingProfile.Mode) mode.getSelectedItem(),
                    value(headerRow), value(titleColumn), grid.getSelectedRow(), grid.getSelectedColumn() - 1, (MappingProfile.EmptyPolicy) emptyPolicy.getSelectedItem());
            profile = profile.put(binding); confirmedFixed.add(binding);
            if (binding.mode() == MappingProfile.Mode.RECORD) recordRow.setValue(binding.row() + 1);
            mappingChanged();
        } catch (IllegalArgumentException e) { status.setText(e.getMessage()); }
    }
    private String selectedVariable() {
        int row = mappings.getSelectedRow();
        return row >= 0 && row < previews.size() ? previews.get(row).variable() : Objects.toString(target.getSelectedItem(), "");
    }
    private void removeSelected() { profile = profile.remove(selectedVariable()); mappingChanged(); }
    private void toggleSelected() {
        MappingProfile.Binding b = profile.get(selectedVariable()); if (b != null) { profile = profile.put(b.enabled(!b.enabled())); mappingChanged(); }
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
        List<MappingProfile.Binding> candidates = new ArrayList<>();
        for (String variable : session.variables().keySet()) {
            if (profile.get(variable) != null) continue;
            List<Integer> columns = new ArrayList<>();
            for (int c = 0; c < sheet().columns(); c++) if (SpreadsheetData.normalize(sheet().cell(value(headerRow), c).display()).equals(SpreadsheetData.normalize(variable))) columns.add(c);
            if (columns.size() == 1 && value(recordRow) > value(headerRow) && value(recordRow) < sheet().rows()) {
                candidates.add(engine.bind(variable, sheet(), MappingProfile.Mode.RECORD, value(headerRow), value(titleColumn), value(recordRow), columns.get(0), MappingProfile.EmptyPolicy.ERROR));
            }
        }
        if (candidates.isEmpty()) { status.setText("没有唯一同名列可建议绑定；重复标题需手动选择固定单元格。"); return; }
        String text = String.join("\n", candidates.stream().map(b -> b.columnTitle() + " → " + b.variable() + "（名称一致）").toList());
        JTextArea details = new JTextArea(text, Math.min(12, candidates.size()), 35); details.setEditable(false);
        if (JOptionPane.showConfirmDialog(this, new JScrollPane(details), "确认当前记录的同名绑定建议", JOptionPane.OK_CANCEL_OPTION) == JOptionPane.OK_OPTION) {
            for (var binding : candidates) profile = profile.put(binding); mappingChanged();
        }
    }
    private void schedulePreview() {
        ++previewSequence; previewPending = true; apply.setEnabled(false); previewTimer.restart();
        undo.setEnabled(session.canUndoImport());
    }
    public void rebuildPreview() {
        previewTimer.stop(); long request = ++previewSequence;
        SpreadsheetData source = workbook; MappingProfile rules = profile; var variables = session.variables();
        String activeSheet = Objects.toString(sheets.getSelectedItem(), ""); int record = value(recordRow);
        Set<MappingProfile.Binding> fixed = Set.copyOf(confirmedFixed);
        tasks.cancelKind("mapping-preview");
        if (source == null) {
            previews = engine.preview(null, rules, variables, activeSheet, record, fixed, () -> { });
            previewPending = false; showPreviews(); return;
        }
        tasks.submit("mapping-preview", "检查映射", FileTaskManager.LockScope.NONE, true,
                progress -> engine.preview(source, rules, variables, activeSheet, record, fixed, progress::checkpoint),
                result -> { if (request == previewSequence) { previews = result; previewPending = false; showPreviews(); } },
                error -> { if (request == previewSequence) { previewPending = false; apply.setEnabled(false); status.setText("映射检查失败：" + error.getMessage()); } },
                () -> { if (request == previewSequence) { previewPending = true; status.setText("映射检查已取消，请修改选择或刷新表格后重试。"); } });
    }
    private void showPreviews() {
        mappings.setModel(new AbstractTableModel() {
            final String[] names = {"模板变量", "数据来源 →", "单元格显示", "将填入的值", "当前变量值", "状态／问题"};
            public int getRowCount() { return previews.size(); }
            public int getColumnCount() { return names.length; }
            public String getColumnName(int column) { return names[column]; }
            public Object getValueAt(int row, int column) {
                var p = previews.get(row);
                return switch (column) { case 0 -> p.variable(); case 1 -> p.source(); case 2 -> p.display(); case 3 -> p.value(); case 4 -> p.oldValue(); default -> p.error().isEmpty() ? p.status() : p.error(); };
            }
        });
        long errors = previews.stream().filter(p -> !p.error().isEmpty()).count();
        long ready = previews.stream().filter(MappingEngine.Preview::apply).count();
        long manual = previews.stream().filter(p -> profile.get(p.variable()) == null || !profile.get(p.variable()).enabled()).count();
        long orphan = profile.bindings().stream().filter(b -> !session.variables().containsKey(b.variable())).count();
        status.setText(fileChanged ? "源文件已变化，请刷新表格后重新检查。当前变量未被修改。" : previews.size() + " 个变量：" + ready + " 个可应用，" + manual + " 个手工填写，" + errors + " 个需要处理" + (orphan > 0 ? "；另有 " + orphan + " 条已不在模板中的映射（保留但不应用）" : ""));
        if (!loadFailure.isEmpty() && !fileChanged) status.setText(loadFailure + "；" + status.getText());
        apply.setEnabled(!previewPending && !fileChanged && errors == 0 && ready > 0 && !tasks.hasTask("excel-load"));
        undo.setEnabled(session.canUndoImport());
    }
    public void applyPreview() {
        if (previewPending || workbook == null || tasks.hasTask("excel-load")) return;
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
        var match = previews.get(row).match(); sheets.setSelectedItem(match.sheet().name()); selectCell(match.row(), match.column());
    }
    private void checkFileChanged() {
        if (workbook == null || fileChanged) return;
        try { fileChanged = Files.getLastModifiedTime(workbook.path()).toMillis() != workbook.modified() || Files.size(workbook.path()) != workbook.size(); }
        catch (Exception e) { fileChanged = true; }
        if (fileChanged) { apply.setEnabled(false); status.setText("源文件已更新或不可访问，请刷新表格；现有变量保持原值。"); }
    }
    private static Path directory(String saved) {
        try { if (saved != null && Files.isDirectory(Path.of(saved))) return Path.of(saved); } catch (RuntimeException ignored) { }
        return Path.of("").toAbsolutePath();
    }
}
