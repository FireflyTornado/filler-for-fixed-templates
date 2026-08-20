package com.firefly.ui;

import javax.swing.JPanel;
import java.awt.Dimension;
import java.awt.LayoutManager;
import java.awt.Rectangle;

/**
 * 可放入 JScrollPane 的纵向表单面板：
 *   * 宽度始终跟随可视区（表单里的输入框可以拉伸填满整行）
 *   * 高度随内容自适应，内容超出可视区时出现滚动条
 */
public final class ScrollablePanel extends JPanel implements javax.swing.Scrollable {

    public ScrollablePanel(LayoutManager layout) {
        super(layout);
    }

    @Override
    public Dimension getPreferredScrollableViewportSize() {
        return getPreferredSize();
    }

    @Override
    public int getScrollableUnitIncrement(Rectangle visibleRect, int orientation, int direction) {
        return 24;
    }

    @Override
    public int getScrollableBlockIncrement(Rectangle visibleRect, int orientation, int direction) {
        return 120;
    }

    @Override
    public boolean getScrollableTracksViewportWidth() {
        return true; // 宽度跟随可视区，避免横向滚动
    }

    @Override
    public boolean getScrollableTracksViewportHeight() {
        return false; // 高度随内容变化，内容多时出现竖向滚动条
    }
}
