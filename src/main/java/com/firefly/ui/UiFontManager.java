package com.firefly.ui;

import javax.swing.*;
import javax.swing.plaf.FontUIResource;
import javax.swing.text.JTextComponent;
import java.awt.*;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.WeakHashMap;

/** 以未缩放的系统字体为唯一基准，统一应用用户倍率并刷新现有窗口。 */
public final class UiFontManager {
    public static final float READING_SCALE = 1.05f;
    private static final Map<Object, Font> ORIGINAL_FONTS = new LinkedHashMap<>();
    private static final Map<JComponent, String> READING_COMPONENTS = new WeakHashMap<>();
    private static final Map<JTable, Integer> ORIGINAL_TABLE_ROWS = new WeakHashMap<>();
    private static float scale = 1.0f;

    private UiFontManager() { }

    /** 必须在系统 Look and Feel 安装完成后、创建任何窗口之前调用。 */
    public static void initialize() {
        ORIGINAL_FONTS.clear();
        UIDefaults defaults = UIManager.getLookAndFeelDefaults();
        for (Object keyValue : defaults.keySet().toArray()) {
            if (keyValue instanceof String key && key.endsWith(".font")) {
                Object value = defaults.get(keyValue);
                if (value instanceof Font font) ORIGINAL_FONTS.put(keyValue, font);
            }
        }
    }

    public static float scale() { return scale; }

    public static void applyScale(float newScale) {
        if (ORIGINAL_FONTS.isEmpty()) initialize();
        scale = newScale;
        for (Map.Entry<Object, Font> entry : ORIGINAL_FONTS.entrySet()) {
            Font base = entry.getValue();
            UIManager.put(entry.getKey(), new FontUIResource(
                    base.deriveFont(Math.max(1f, base.getSize2D() * scale))));
        }
    }

    public static void registerReadingComponent(JComponent component, String baseFontKey) {
        READING_COMPONENTS.put(component, baseFontKey);
        applyReadingFont(component, baseFontKey);
    }

    public static void refreshOpenWindows() {
        Component focus = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
        Map<JTextComponent, Integer> carets = new WeakHashMap<>();
        Map<JSplitPane, Integer> dividers = new WeakHashMap<>();
        Map<JScrollPane, Point> scrollPositions = new WeakHashMap<>();
        for (Window window : Window.getWindows()) {
            if (!window.isDisplayable()) continue;
            visit(window, component -> {
                if (component instanceof JTextComponent text) carets.put(text, text.getCaretPosition());
                if (component instanceof JSplitPane split) dividers.put(split, split.getDividerLocation());
                if (component instanceof JScrollPane scroll) {
                    scrollPositions.put(scroll, scroll.getViewport().getViewPosition());
                }
            });
            SwingUtilities.updateComponentTreeUI(window);
        }
        for (Map.Entry<JComponent, String> entry : new LinkedHashMap<>(READING_COMPONENTS).entrySet()) {
            if (entry.getKey() != null) applyReadingFont(entry.getKey(), entry.getValue());
        }
        for (Window window : Window.getWindows()) {
            if (!window.isDisplayable()) continue;
            visit(window, component -> {
                if (component instanceof JTable table) updateTableRowHeight(table);
            });
            window.invalidate();
            window.validate();
            window.repaint();
        }
        carets.forEach((text, position) -> text.setCaretPosition(Math.min(position, text.getDocument().getLength())));
        dividers.forEach(JSplitPane::setDividerLocation);
        scrollPositions.forEach((scroll, point) -> scroll.getViewport().setViewPosition(point));
        if (focus != null) focus.requestFocusInWindow();
    }

    public static void updateTableRowHeight(JTable table) {
        int base = ORIGINAL_TABLE_ROWS.computeIfAbsent(table, ignored -> table.getRowHeight());
        int line = table.getFontMetrics(table.getFont()).getHeight();
        table.setRowHeight(Math.max(base, line + 8));
    }

    private static void applyReadingFont(JComponent component, String key) {
        Font base = ORIGINAL_FONTS.get(key);
        if (base == null) base = UIManager.getFont(key);
        if (base != null) component.setFont(base.deriveFont(
                Math.max(1f, base.getSize2D() * scale * READING_SCALE)));
    }

    private static void visit(Component component, java.util.function.Consumer<Component> visitor) {
        visitor.accept(component);
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) visit(child, visitor);
        }
    }
}
