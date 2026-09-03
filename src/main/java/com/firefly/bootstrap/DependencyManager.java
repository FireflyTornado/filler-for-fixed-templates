package com.firefly.bootstrap;

import com.firefly.core.JsonData;
import java.io.*;
import java.net.*;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Uses only JDK classes and the application's JSON reader; safe before POI is available. */
public final class DependencyManager {
    private static final long MAX_DOWNLOAD = 128L * 1024 * 1024;
    public record Dependency(String file, URI url, String sha256) {
        public Dependency {
            if (file == null || !file.matches("[A-Za-z0-9_.-]+\\.jar")
                    || sha256 == null || !sha256.matches("[a-f0-9]{64}")
                    || url == null || !"https".equals(url.getScheme())
                    || !"repo.maven.apache.org".equals(url.getHost())
                    || url.getPort() != -1 || url.getUserInfo() != null
                    || url.getQuery() != null || url.getFragment() != null
                    || !url.getPath().startsWith("/maven2/") || !url.equals(url.normalize())) {
                throw new IllegalArgumentException("依赖清单包含无效文件名、来源或校验值");
            }
        }
    }
    public record Progress(String message, int completed, int total, long bytes, long size) { }
    record Download(InputStream stream, long size) implements AutoCloseable {
        @Override public void close() throws IOException { stream.close(); }
    }
    @FunctionalInterface interface Source { Download open(URI uri) throws IOException; }

    private final Path lib;
    private final List<Dependency> dependencies;
    private final Source source;

    public DependencyManager(Path directory, List<Dependency> dependencies) {
        this(directory, dependencies, DependencyManager::openDownload);
    }
    DependencyManager(Path directory, List<Dependency> dependencies, Source source) {
        this.lib = directory.toAbsolutePath().normalize();
        this.dependencies = List.copyOf(dependencies);
        this.source = source;
    }

    /** The running JAR's embedded lock is authoritative; no external lock file is needed. */
    public static List<Dependency> bundledDependencies() throws IOException {
        try (InputStream in = DependencyManager.class.getResourceAsStream("/dependencies.lock.json")) {
            if (in == null) throw new IOException("程序缺少内置依赖清单，请重新构建或获取完整 JAR。");
            return parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    static List<Dependency> parse(String json) throws IOException {
        try {
            if (!(JsonData.parse(json) instanceof List<?> entries) || entries.isEmpty()) {
                throw new IOException("依赖清单为空或格式错误");
            }
            List<Dependency> result = new ArrayList<>();
            Set<String> names = new HashSet<>();
            for (Object entry : entries) {
                if (!(entry instanceof Map<?, ?> map)) throw new IOException("依赖清单格式错误");
                Dependency item = new Dependency((String) map.get("file"), URI.create((String) map.get("url")),
                        (String) map.get("sha256"));
                if (!names.add(item.file().toLowerCase(Locale.ROOT))) throw new IOException("依赖文件名重复");
                result.add(item);
            }
            return List.copyOf(result);
        } catch (IllegalArgumentException | ClassCastException | NullPointerException e) {
            throw new IOException("依赖清单格式错误", e);
        }
    }

    public boolean isComplete(BooleanSupplier cancelled) throws IOException {
        for (Dependency dependency : dependencies) {
            checkpoint(cancelled);
            if (!valid(dependency, cancelled)) return false;
        }
        return true;
    }

    /** No network or filesystem writes when the cache is already complete. */
    public void ensure(Consumer<Progress> progress, BooleanSupplier cancelled) throws IOException {
        if (isComplete(cancelled)) return;
        Files.createDirectories(lib);
        // Keep the lock file in lib: deleting it on release would race another startup.
        try (FileChannel channel = FileChannel.open(lib.resolve(".dependencies.lock"),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
            FileLock lock = null;
            try {
                while (lock == null) {
                    checkpoint(cancelled);
                    try { lock = channel.tryLock(); } catch (OverlappingFileLockException ignored) { }
                    if (lock == null) {
                        progress.accept(new Progress("正在等待另一个程序完成依赖准备…", 0, dependencies.size(), 0, -1));
                        try { Thread.sleep(200); }
                        catch (InterruptedException e) { Thread.currentThread().interrupt(); checkpoint(cancelled); }
                    }
                }
                int completed = 0;
                for (Dependency dependency : dependencies) {
                    checkpoint(cancelled);
                    progress.accept(new Progress("正在检查 " + dependency.file(), completed, dependencies.size(), 0, -1));
                    if (!valid(dependency, cancelled)) download(dependency, completed, progress, cancelled);
                    progress.accept(new Progress("已就绪 " + dependency.file(), ++completed, dependencies.size(), 0, 0));
                }
            } finally { if (lock != null) lock.release(); }
        }
    }

    private boolean valid(Dependency item, BooleanSupplier cancelled) throws IOException {
        Path path = lib.resolve(item.file());
        return Files.isRegularFile(path) && item.sha256().equals(sha256(path, cancelled));
    }

    private void download(Dependency item, int completed, Consumer<Progress> progress,
                          BooleanSupplier cancelled) throws IOException {
        Path temporary = Files.createTempFile(lib, ".dependency-", ".download");
        try {
            progress.accept(new Progress("正在连接下载 " + item.file(), completed, dependencies.size(), 0, -1));
            try (Download download = source.open(item.url()); OutputStream out = Files.newOutputStream(temporary)) {
                if (download.size() > MAX_DOWNLOAD) throw new IOException("依赖文件过大：" + item.file());
                byte[] buffer = new byte[64 * 1024];
                long received = 0;
                int count;
                while (true) {
                    checkpoint(cancelled);
                    count = download.stream().read(buffer);
                    if (count < 0) break;
                    received += count;
                    if (received > MAX_DOWNLOAD) throw new IOException("依赖文件过大：" + item.file());
                    out.write(buffer, 0, count);
                    progress.accept(new Progress("正在下载 " + item.file(), completed, dependencies.size(), received, download.size()));
                }
                if (download.size() >= 0 && received != download.size()) throw new IOException("下载未完成：" + item.file());
            }
            checkpoint(cancelled);
            if (!item.sha256().equals(sha256(temporary, cancelled))) {
                throw new IOException("下载文件校验失败：" + item.file() + "。请重试。");
            }
            checkpoint(cancelled);
            try { Files.move(temporary, lib.resolve(item.file()), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, lib.resolve(item.file()), StandardCopyOption.REPLACE_EXISTING);
            }
        } finally { Files.deleteIfExists(temporary); }
    }

    static String sha256(Path path, BooleanSupplier cancelled) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream in = Files.newInputStream(path)) {
                byte[] buffer = new byte[64 * 1024];
                int count;
                while ((count = in.read(buffer)) != -1) { checkpoint(cancelled); digest.update(buffer, 0, count); }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    private static Download openDownload(URI uri) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) uri.toURL().openConnection();
        connection.setConnectTimeout(10_000);
        connection.setReadTimeout(5_000);
        connection.setInstanceFollowRedirects(false);
        connection.setRequestProperty("User-Agent", "TemplateFiller/1.0");
        try {
            int status = connection.getResponseCode();
            if (status != HttpURLConnection.HTTP_OK) throw new IOException("下载服务返回 HTTP " + status);
            return new Download(new FilterInputStream(connection.getInputStream()) {
                @Override public void close() throws IOException { try { super.close(); } finally { connection.disconnect(); } }
            }, connection.getContentLengthLong());
        } catch (IOException e) { connection.disconnect(); throw e; }
    }

    private static void checkpoint(BooleanSupplier cancelled) {
        if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) throw new CancellationException("已取消启动");
    }
}
