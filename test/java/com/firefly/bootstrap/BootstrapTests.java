package com.firefly.bootstrap;

import javax.swing.*;
import java.io.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;
import java.util.jar.JarFile;

public final class BootstrapTests {
    private static int checks;
    private static final byte[] A = "dependency A".getBytes(StandardCharsets.UTF_8);
    private static final byte[] B = "dependency B".getBytes(StandardCharsets.UTF_8);
    private static final DependencyManager.Dependency FIRST = dependency("first.jar", A);
    private static final DependencyManager.Dependency SECOND = dependency("second.jar", B);

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("bootstrap-tests-");
        cacheAndDownloads(root);
        failuresAndCancellation(root);
        concurrentStarts(root);
        manifestAndLoader();
        dialogGate(root);
        System.out.println("All " + checks + " bootstrap checks passed.");
    }

    private static void cacheAndDownloads(Path root) throws Exception {
        Path lib = root.resolve("cache/lib");
        AtomicInteger requests = new AtomicInteger();
        DependencyManager manager = new DependencyManager(lib, List.of(FIRST, SECOND), uri -> {
            requests.incrementAndGet();
            byte[] bytes = uri.getPath().endsWith("first.jar") ? A : B;
            return response(bytes, bytes.length);
        });
        check(!manager.isComplete(() -> false), "missing lib detected without creating it");
        check(!Files.exists(lib), "inspection is read-only");
        List<DependencyManager.Progress> progress = new ArrayList<>();
        manager.ensure(progress::add, () -> false);
        check(requests.get() == 2 && manager.isComplete(() -> false), "missing dependencies downloaded and verified");
        check(progress.stream().anyMatch(p -> p.bytes() > 0 && p.size() > 0), "byte progress available");
        check(progress.get(progress.size() - 1).completed() == 2, "overall progress completes");
        check(noPartial(lib), "successful download leaves no partial files");
        DependencyManager offline = new DependencyManager(lib, List.of(FIRST, SECOND), uri -> { throw new IOException("Offline"); });
        offline.ensure(p -> { throw new AssertionError("No download progress expected offline"); }, () -> false);
        check(offline.isComplete(() -> false), "complete cache works offline");
        Files.write(lib.resolve(FIRST.file()), B);
        manager.ensure(p -> { }, () -> false);
        check(requests.get() == 3 && manager.isComplete(() -> false), "only corrupt dependency repaired");
        Files.delete(lib.resolve(SECOND.file()));
        manager.ensure(p -> { }, () -> false);
        check(requests.get() == 4 && manager.isComplete(() -> false), "only missing dependency downloaded");
    }

    private static void failuresAndCancellation(Path root) throws Exception {
        Path lib = Files.createDirectories(root.resolve("failure/lib"));
        Files.write(lib.resolve(FIRST.file()), B);
        DependencyManager bad = new DependencyManager(lib, List.of(FIRST), uri -> response(B, B.length));
        expect(IOException.class, () -> bad.ensure(p -> { }, () -> false), "hash mismatch blocks completion");
        check(Arrays.equals(Files.readAllBytes(lib.resolve(FIRST.file())), B), "failed replacement preserves old file");
        check(noPartial(lib), "hash mismatch removes partial file");
        DependencyManager broken = new DependencyManager(lib, List.of(FIRST), uri -> response(A, A.length + 1));
        expect(IOException.class, () -> broken.ensure(p -> { }, () -> false), "truncated transfer rejected");
        check(noPartial(lib), "truncated download cleaned");
        DependencyManager offline = new DependencyManager(lib, List.of(FIRST), uri -> { throw new IOException("Offline"); });
        expect(IOException.class, () -> offline.ensure(p -> { }, () -> false), "offline missing dependency blocks launch");
        check(noPartial(lib), "connection error cleaned");
        AtomicBoolean cancelled = new AtomicBoolean();
        DependencyManager cancel = new DependencyManager(lib, List.of(FIRST), uri -> response(A, -1));
        expect(CancellationException.class, () -> cancel.ensure(p -> { if (p.bytes() > 0) cancelled.set(true); }, cancelled::get), "cancellation blocks publication");
        check(noPartial(lib) && Arrays.equals(Files.readAllBytes(lib.resolve(FIRST.file())), B), "cancel preserves existing file and cleans partial");
        Path partialLib = root.resolve("retry/lib");
        AtomicBoolean failSecond = new AtomicBoolean(true);
        AtomicInteger firstDownloads = new AtomicInteger();
        DependencyManager retry = new DependencyManager(partialLib, List.of(FIRST, SECOND), uri -> {
            if (uri.getPath().endsWith("first.jar")) { firstDownloads.incrementAndGet(); return response(A, A.length); }
            if (failSecond.get()) throw new IOException("Disconnected");
            return response(B, -1);
        });
        expect(IOException.class, () -> retry.ensure(p -> { }, () -> false), "mid-batch failure propagated");
        check(Files.exists(partialLib.resolve(FIRST.file())), "completed dependencies retained after failure");
        failSecond.set(false); retry.ensure(p -> { }, () -> false);
        check(firstDownloads.get() == 1 && retry.isComplete(() -> false), "retry reuses completed download and handles unknown size");
    }

    private static void concurrentStarts(Path root) throws Exception {
        Path lib = root.resolve("concurrent/lib");
        CountDownLatch downloading = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicInteger requests = new AtomicInteger();
        DependencyManager.Source source = uri -> {
            requests.incrementAndGet(); downloading.countDown();
            try { if (!release.await(5, TimeUnit.SECONDS)) throw new IOException("Test timed out"); }
            catch (InterruptedException e) { throw new IOException(e); }
            return response(A, A.length);
        };
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            Future<?> one = workers.submit(() -> { new DependencyManager(lib, List.of(FIRST), source).ensure(p -> { }, () -> false); return null; });
            check(downloading.await(5, TimeUnit.SECONDS), "first startup holds download lock");
            CountDownLatch waiting = new CountDownLatch(1);
            Future<?> two = workers.submit(() -> { new DependencyManager(lib, List.of(FIRST), source).ensure(p -> waiting.countDown(), () -> false); return null; });
            check(waiting.await(5, TimeUnit.SECONDS), "second startup waits with progress");
            release.countDown(); one.get(5, TimeUnit.SECONDS); two.get(5, TimeUnit.SECONDS);
            check(requests.get() == 1, "concurrent startups share one verified download");
        } finally { release.countDown(); workers.shutdownNow(); }
    }

    private static void manifestAndLoader() throws Exception {
        var dependencies = DependencyManager.bundledDependencies();
        check(dependencies.size() == 13, "dependency lock bundled in JAR");
        expect(IOException.class, () -> DependencyManager.parse("[]"), "empty lock rejected");
        expect(IllegalArgumentException.class, () -> new DependencyManager.Dependency("../bad.jar", FIRST.url(), FIRST.sha256()), "path traversal rejected");
        expect(IllegalArgumentException.class, () -> new DependencyManager.Dependency("bad.jar", URI.create("https://repo.maven.apache.org.attacker.example/maven2/bad.jar"), FIRST.sha256()), "unexpected host rejected");
        Path jar = Path.of(DependencyManager.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        try (JarFile contents = new JarFile(jar.toFile())) {
            check("com.firefly.bootstrap.Bootstrap".equals(contents.getManifest().getMainAttributes().getValue("Main-Class")), "all java -jar launches use bootstrap");
            check(contents.getManifest().getMainAttributes().getValue("Class-Path") == null, "unverified dependencies absent from bootstrap classpath");
            check(contents.getEntry("LICENSE") != null && contents.getEntry("THIRD_PARTY_NOTICES.txt") != null, "license and notices bundled");
            check(contents.getEntry("THIRD_PARTY_NOTICES.md") == null, "Markdown legal overview is not shipped");
            try (var input = contents.getInputStream(contents.getEntry("THIRD_PARTY_NOTICES.txt"))) {
                String overview = new String(input.readAllBytes(), StandardCharsets.UTF_8);
                check(!java.util.regex.Pattern.compile("(?m)^#{1,6} |^\\||\\*\\*|\\[[^\\]]+\\]\\(").matcher(overview).find(), "legal overview uses plain text");
            }
            var legalEntry = contents.getEntry("META-INF/THIRD_PARTY_LICENSES.txt");
            check(legalEntry != null, "full third-party legal text bundled");
            String legal;
            try (var input = contents.getInputStream(legalEntry)) { legal = new String(input.readAllBytes(), StandardCharsets.UTF_8); }
            for (var dependency : dependencies) {
                check(legal.contains("Component: " + dependency.file() + "\nSHA-256: " + dependency.sha256()), "license inventory matches " + dependency.file());
                try (JarFile upstream = new JarFile(jar.getParent().resolve("lib").resolve(dependency.file()).toFile())) {
                    for (var entry : Collections.list(upstream.entries())) {
                        if (!entry.isDirectory() && entry.getName().matches("(?i).*/(?:LICENSE|LICENCE|NOTICE)(?:\\..*)?")) {
                            try (var input = upstream.getInputStream(entry)) {
                                String original = new String(input.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n");
                                check(legal.contains(original), "original legal text retained: " + dependency.file() + "/" + entry.getName());
                            }
                        }
                    }
                }
            }
            check(legal.contains("Copyright (c) 2005, Graph Builder") && legal.contains("Neither the name of Graph Builder"), "curvesapi BSD copyright and conditions supplied");
            check(legal.contains("SparseBitSet/SparseBitSet-1.3/LICENSE") && legal.contains("Paladin Software International"), "SparseBitSet license and attribution supplied");
            check(legal.contains("Apache Harmony") && legal.contains("Denis M. Kishenko"), "curvesapi embedded Harmony attribution supplied");
            check(legal.contains("University of Chicago, as Operator of Argonne National"), "Commons Math acknowledgment retained");
        }
        try (var loader = Bootstrap.applicationLoader(jar, jar.getParent().resolve("lib"), dependencies)) {
            check(loader.loadClass("com.firefly.Main").getClassLoader() == loader, "application uses isolated loader");
            check(loader.loadClass("org.apache.poi.xssf.usermodel.XSSFWorkbook").getClassLoader() == loader, "verified runtime dependencies load successfully");
            check(loader.getURLs().length == dependencies.size() + 1, "only pinned libraries enter application loader");
        }
    }

    private static void dialogGate(Path root) throws Exception {
        AtomicBoolean fail = new AtomicBoolean(true);
        AtomicInteger launched = new AtomicInteger();
        DependencyManager manager = new DependencyManager(root.resolve("ui/lib"), List.of(FIRST), uri -> {
            if (fail.get()) throw new IOException("Simulated network failure");
            return response(A, A.length);
        });
        AtomicReference<DependencyDialog> ref = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> { DependencyDialog dialog = new DependencyDialog(manager, launched::incrementAndGet); ref.set(dialog); dialog.start(); });
        try {
            waitFor(() -> button(ref.get(), "retry").isEnabled());
            check(launched.get() == 0, "failed download cannot launch application");
            fail.set(false);
            SwingUtilities.invokeAndWait(() -> button(ref.get(), "retry").doClick());
            waitFor(() -> launched.get() == 1);
            check(manager.isComplete(() -> false), "retry opens application only after verified completion");
        } finally { SwingUtilities.invokeAndWait(() -> ref.get().dispose()); }

        CountDownLatch connected = new CountDownLatch(1), finishConnection = new CountDownLatch(1);
        AtomicInteger cancelledLaunches = new AtomicInteger();
        DependencyManager cancellable = new DependencyManager(root.resolve("ui-cancel/lib"), List.of(FIRST), uri -> {
            connected.countDown();
            try { if (!finishConnection.await(5, TimeUnit.SECONDS)) throw new IOException("Test timeout"); }
            catch (InterruptedException e) { throw new IOException(e); }
            return response(A, A.length);
        });
        SwingUtilities.invokeAndWait(() -> { DependencyDialog dialog = new DependencyDialog(cancellable, cancelledLaunches::incrementAndGet); ref.set(dialog); dialog.start(); });
        try {
            check(connected.await(5, TimeUnit.SECONDS), "cancellation test reaches active download");
            SwingUtilities.invokeAndWait(() -> button(ref.get(), "cancel").doClick());
            finishConnection.countDown();
            waitFor(() -> !ref.get().isDisplayable());
            check(cancelledLaunches.get() == 0 && !Files.exists(root.resolve("ui-cancel/lib/first.jar"))
                    && noPartial(root.resolve("ui-cancel/lib")), "cancel button waits for cleanup and never opens application");
        } finally { finishConnection.countDown(); SwingUtilities.invokeAndWait(() -> ref.get().dispose()); }
    }

    private static JButton button(DependencyDialog dialog, String name) {
        try { var field = DependencyDialog.class.getDeclaredField(name); field.setAccessible(true); return (JButton) field.get(dialog); }
        catch (ReflectiveOperationException e) { throw new AssertionError(e); }
    }
    private static void waitFor(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        while (System.nanoTime() < deadline) {
            AtomicBoolean done = new AtomicBoolean(); SwingUtilities.invokeAndWait(() -> done.set(condition.getAsBoolean()));
            if (done.get()) return;
            Thread.sleep(25);
        }
        throw new AssertionError("UI operation timed out");
    }
    private static DependencyManager.Dependency dependency(String file, byte[] content) {
        try { return new DependencyManager.Dependency(file, URI.create("https://repo.maven.apache.org/maven2/test/" + file),
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content))); }
        catch (Exception e) { throw new AssertionError(e); }
    }
    private static DependencyManager.Download response(byte[] bytes, long length) { return new DependencyManager.Download(new ByteArrayInputStream(bytes), length); }
    private static boolean noPartial(Path lib) throws IOException {
        try (var files = Files.list(lib)) { return files.noneMatch(p -> p.toString().endsWith(".download")); }
    }
    @FunctionalInterface private interface Action { void run() throws Exception; }
    private static void expect(Class<? extends Throwable> type, Action action, String label) throws Exception {
        try { action.run(); } catch (Exception e) { if (type.isInstance(e)) { check(true, label); return; } throw e; }
        throw new AssertionError(label);
    }
    private static void check(boolean result, String label) { checks++; if (!result) throw new AssertionError(label); }
}
