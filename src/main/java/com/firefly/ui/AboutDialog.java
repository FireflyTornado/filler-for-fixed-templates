package com.firefly.ui;

import javax.swing.*;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Offline application and third-party license viewer, independent of template state. */
public final class AboutDialog extends JDialog {
    private final JTabbedPane pages = new JTabbedPane();

    public AboutDialog(Window owner) {
        super(owner, "关于模板填充工具", Dialog.ModalityType.MODELESS);
        setDefaultCloseOperation(HIDE_ON_CLOSE);
        JPanel root = new JPanel(new BorderLayout(10, 12));
        root.setBorder(BorderFactory.createEmptyBorder(16, 18, 12, 18));
        JPanel heading = new JPanel(new GridLayout(0, 1, 0, 5));
        JLabel title = new JLabel("模板填充工具");
        title.setFont(title.getFont().deriveFont(Font.BOLD));
        heading.add(title);
        heading.add(new JLabel("文本 / Word 模板填充与 Excel 数据提取"));
        root.add(heading, BorderLayout.NORTH);

        pages.setTabLayoutPolicy(JTabbedPane.SCROLL_TAB_LAYOUT);
        pages.addTab("本项目许可", textPage("/LICENSE", "本项目 MIT 许可证"));
        pages.addTab("第三方依赖", textPage("/THIRD_PARTY_NOTICES.txt", "第三方依赖与分发说明"));
        pages.addTab("许可原文", textPage("/META-INF/THIRD_PARTY_LICENSES.txt", "第三方许可与署名原文"));
        root.add(pages, BorderLayout.CENTER);

        JPanel footer = new JPanel(new BorderLayout(12, 0));
        footer.add(new JLabel("许可材料已内置，可离线查看、选择和复制。"), BorderLayout.CENTER);
        JButton close = new JButton("关闭");
        close.addActionListener(e -> setVisible(false));
        footer.add(close, BorderLayout.EAST);
        root.add(footer, BorderLayout.SOUTH);
        setContentPane(root);
        getRootPane().setDefaultButton(close);
        getRootPane().registerKeyboardAction(e -> setVisible(false),
                KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_IN_FOCUSED_WINDOW);
        setSize(780, 590);
        setMinimumSize(new Dimension(520, 380));
    }

    public void showDialog() {
        if (!isVisible()) setLocationRelativeTo(getOwner());
        setVisible(true);
        toFront();
    }

    private static JComponent textPage(String resource, String description) {
        JTextArea text = new JTextArea(readResource(resource));
        text.setEditable(false);
        text.setLineWrap(true);
        text.setWrapStyleWord(true);
        text.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        text.setCaretPosition(0);
        text.getAccessibleContext().setAccessibleName(description);
        UiFontManager.registerReadingComponent(text, "TextArea.font");
        return new JScrollPane(text);
    }

    private static String readResource(String resource) {
        try (var input = AboutDialog.class.getResourceAsStream(resource)) {
            return input == null ? "许可材料缺失，请重新构建或获取完整发布文件。"
                    : new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) { return "无法读取许可材料：" + e.getMessage(); }
    }
}
