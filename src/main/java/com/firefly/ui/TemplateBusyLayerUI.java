package com.firefly.ui;

import javax.swing.*;
import javax.swing.plaf.LayerUI;
import java.awt.*;
import java.awt.event.InputEvent;

/** 在模板独占文件任务期间遮罩并阻止主工作区输入，状态栏仍保持可操作。 */
public final class TemplateBusyLayerUI extends LayerUI<JComponent> {
    private boolean busy;
    private String message = "正在处理模板，请稍候…";

    public void setBusy(JLayer<? extends JComponent> layer, boolean busy, String message) {
        this.busy = busy;
        if (message != null && !message.isBlank()) this.message = message;
        layer.setLayerEventMask(busy
                ? AWTEvent.MOUSE_EVENT_MASK | AWTEvent.MOUSE_MOTION_EVENT_MASK
                    | AWTEvent.MOUSE_WHEEL_EVENT_MASK | AWTEvent.KEY_EVENT_MASK
                : 0);
        layer.setCursor(busy ? Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR) : Cursor.getDefaultCursor());
        layer.repaint();
    }

    @Override public void eventDispatched(AWTEvent event, JLayer<? extends JComponent> layer) {
        if (busy && event instanceof InputEvent input) input.consume();
    }

    @Override public void paint(Graphics graphics, JComponent component) {
        super.paint(graphics, component);
        if (!busy) return;
        Graphics2D g = (Graphics2D) graphics.create();
        try {
            g.setColor(new Color(245, 245, 245, 155));
            g.fillRect(0, 0, component.getWidth(), component.getHeight());
            Font font = UIManager.getFont("Label.font");
            if (font != null) g.setFont(font.deriveFont(Font.BOLD));
            FontMetrics metrics = g.getFontMetrics();
            int paddingX = 18, paddingY = 10;
            int width = metrics.stringWidth(message) + paddingX * 2;
            int height = metrics.getHeight() + paddingY * 2;
            int x = Math.max(8, (component.getWidth() - width) / 2);
            int y = Math.max(8, (component.getHeight() - height) / 2);
            g.setColor(new Color(255, 255, 255, 235));
            g.fillRoundRect(x, y, width, height, 12, 12);
            g.setColor(new Color(110, 110, 110));
            g.drawRoundRect(x, y, width, height, 12, 12);
            g.setColor(UIManager.getColor("Label.foreground"));
            g.drawString(message, x + paddingX, y + paddingY + metrics.getAscent());
        } finally {
            g.dispose();
        }
    }
}
