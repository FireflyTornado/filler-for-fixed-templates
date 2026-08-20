package com.firefly;

import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.CodeSource;
import java.security.ProtectionDomain;

/**
 * 程序入口：设置高 DPI / 系统外观，然后启动主窗口。
 */
public final class Main {

    private Main() {
    }

    public static void main(String[] args) {
        // 高 DPI 下让界面更清晰（JDK9+ 默认已适配，这里兼容旧 JDK）
        System.setProperty("sun.java2d.dpiaware", "true");

        SwingUtilities.invokeLater(() -> {
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            } catch (Exception ignored) {
                // 保持默认外观
            }
            TemplateToolApp app = new TemplateToolApp(appDir());
            app.setVisible(true);
        });
    }

    /**
     * 程序所在目录（兼容 jar 与 class 目录两种启动方式），
     */
    static Path appDir() {
        try {
            ProtectionDomain pd = Main.class.getProtectionDomain();
            CodeSource cs = pd.getCodeSource();
            if (cs != null) {
                Path location = Paths.get(cs.getLocation().toURI());
                if (!Files.isDirectory(location)) {
                    return location.getParent(); // 从 jar 启动：取 jar 所在目录
                }
                return location;                 // 从 class 目录启动
            }
        } catch (Exception ignored) {
            // 回退到工作目录
        }
        return Paths.get(System.getProperty("user.dir"));
    }
}
