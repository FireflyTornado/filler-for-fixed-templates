package com.firefly;

import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import com.firefly.ui.UiFontManager;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.CodeSource;
import java.security.ProtectionDomain;

/**
 * 依赖校验完成后的应用入口：由 bootstrap.Bootstrap 加载，再启动主窗口。
 */
public final class Main {

    private Main() {
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            } catch (Exception ignored) {
                // 保持默认外观
            }
            UiFontManager.initialize();
            TemplateToolApp app = new TemplateToolApp(appDir());
            app.setVisible(true);
            app.initializeAfterShowing();
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
