package com.firefly.bootstrap;

import javax.swing.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;

/** JAR entry point; no application or third-party class is loaded before verification. */
public final class Bootstrap {
    private static URLClassLoader applicationLoader;
    private Bootstrap() { }

    public static void main(String[] args) {
        try { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()); }
        catch (Exception ignored) { }
        new Thread(() -> {
            try {
                Path source = Path.of(Bootstrap.class.getProtectionDomain().getCodeSource().getLocation().toURI());
                Path directory = Files.isDirectory(source) ? source : source.getParent();
                List<DependencyManager.Dependency> dependencies = DependencyManager.bundledDependencies();
                DependencyManager manager = new DependencyManager(directory.resolve("lib"), dependencies);
                Runnable ready = () -> launch(source, directory.resolve("lib"), dependencies, args);
                boolean complete = manager.isComplete(() -> false);
                SwingUtilities.invokeLater(() -> {
                    if (complete) ready.run();
                    else {
                        DependencyDialog dialog = new DependencyDialog(manager, ready);
                        dialog.setVisible(true); dialog.start();
                    }
                });
            } catch (Exception e) { SwingUtilities.invokeLater(() -> showFailure(e)); }
        }, "dependency-check").start();
    }

    static URLClassLoader applicationLoader(Path source, Path lib,
                                            List<DependencyManager.Dependency> dependencies) throws Exception {
        List<URL> urls = new ArrayList<>();
        urls.add(source.toUri().toURL());
        for (var item : dependencies) urls.add(lib.resolve(item.file()).toUri().toURL());
        // A fresh loader sees newly downloaded JARs, avoiding cached missing-class lookups.
        return new URLClassLoader(urls.toArray(URL[]::new), ClassLoader.getPlatformClassLoader());
    }

    private static void launch(Path source, Path lib, List<DependencyManager.Dependency> dependencies, String[] args) {
        try {
            applicationLoader = applicationLoader(source, lib, dependencies);
            Thread.currentThread().setContextClassLoader(applicationLoader);
            Class<?> main = Class.forName("com.firefly.Main", true, applicationLoader);
            main.getMethod("main", String[].class).invoke(null, (Object) args);
        } catch (Exception | LinkageError e) { showFailure(e); }
    }

    private static void showFailure(Throwable failure) {
        JOptionPane.showMessageDialog(null, "程序启动失败：" + failure.getMessage()
                + "\n请确认程序文件完整、目录可读写，并使用 Java 17 或更高版本。", "无法启动", JOptionPane.ERROR_MESSAGE);
    }
}
