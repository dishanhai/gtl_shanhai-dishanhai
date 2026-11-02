package com.dishanhai.gt_shanhai.client.loading;

import com.dishanhai.gt_shanhai.config.ShanhaiConfigPaths;
import com.dishanhai.gt_shanhai.mixin.ShanhaiLoadingListenerAccessor;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.PreparableReloadListener;
import net.minecraftforge.fml.loading.progress.StartupNotificationManager;
import org.apache.logging.log4j.LogManager;

import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.function.BiFunction;

/** 山海：reload 工作者直接計時、Forge 階段觀測、配方慢項及本機報告。 */
public final class ShanhaiLoadingProfiler {
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().create();
    private static final java.lang.management.ThreadMXBean THREADS = ManagementFactory.getThreadMXBean();
    private static final Executor WRITER = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "shanhai-loading-report"); thread.setDaemon(true); return thread;
    });
    private static volatile Session startup, world;
    private static volatile ProfileReport startupReport, worldReport;
    private static volatile boolean startupEnded;

    private ShanhaiLoadingProfiler() {}

    public static Session session() {
        if (!ShanhaiLoadingPreferences.profiling()) return null;
        Session current = world;
        if (current != null && !current.closed) return current;
        if (startupEnded) return null;
        return startupSession();
    }

    private static synchronized Session startupSession() {
        if (startupEnded) return null;
        if (startup == null) startup = new Session("startup");
        return startup;
    }

    public static synchronized void beginWorld() {
        if (!ShanhaiLoadingPreferences.profiling()) return;
        finishWorld("replaced");
        world = new Session("world");
    }

    public static synchronized void finishWorld(String outcome) {
        if (world != null && !world.closed) publish(world.report(outcome), world.sampler);
    }

    public static synchronized void finishStartup() {
        if (startupEnded) return;
        startupEnded = true;
        if (startup != null) publish(startup.report("ready"), startup.sampler);
    }

    public static void observeStartup() {
        Session current = session();
        if (current == null || !current.phase.equals("startup")) return;
        var meters = StartupNotificationManager.getCurrentProgress();
        Map<String, String> labels = new HashMap<>();
        for (var meter : meters) {
            if (meter.name().equals("Minecraft Progress")) continue;
            labels.put(meter.name(), meter.label() == null ? "" : meter.label().getText());
        }
        current.observe(labels);
    }

    public static <S> CompletableFuture<S> listener(PreparableReloadListener listener, Executor prepare, Executor apply,
            BiFunction<Executor, Executor, CompletableFuture<S>> original) {
        Session owner = session();
        if (owner == null) return original.apply(prepare, apply);
        PreparableReloadListener unwrapped = listener;
        for (int i = 0; i < 8 && unwrapped instanceof ShanhaiLoadingListenerAccessor wrapper; i++) {
            PreparableReloadListener next = wrapper.shanhai$wrapped();
            if (next == null || next == unwrapped) break;
            unwrapped = next;
        }
        String name = unwrapped.getClass().getName();
        long started = System.nanoTime();
        try {
            var future = original.apply(executor(owner, name, "prepare", prepare), executor(owner, name, "apply", apply));
            future.whenComplete((result, error) -> owner.metrics.record(name, "listener elapsed (includes waits)",
                    System.nanoTime() - started, -1, -1, heap(), error != null));
            return future;
        } catch (RuntimeException | Error error) {
            owner.metrics.record(name, "listener elapsed (includes waits)", System.nanoTime() - started, -1, -1, heap(), true);
            throw error;
        }
    }

    private static Executor executor(Session owner, String name, String kind, Executor delegate) {
        return task -> delegate.execute(() -> {
            if (owner.closed) { task.run(); return; }
            long started = System.nanoTime(), cpu = cpu(), allocated = allocated();
            boolean failed = true;
            try { task.run(); failed = false; }
            finally {
                long endCpu = cpu(), endAllocated = allocated();
                owner.metrics.record(name, kind, System.nanoTime() - started, delta(cpu, endCpu),
                        delta(allocated, endAllocated), heap(), failed);
            }
        });
    }

    /** 逐筆配方只讀 nanoTime；CPU／配置量由外層 executor 量測，避免每筆 JMX 查詢。 */
    public static void recipe(Session owner, ResourceLocation id, long started, boolean failed) {
        if (owner == null || owner.closed) return;
        long elapsed = System.nanoTime() - started;
        owner.metrics.recipe(id.getNamespace(), id, elapsed, failed);
    }

    public static ProfileReport report(boolean startupPhase) {
        ProfileReport saved = startupPhase ? startupReport : worldReport;
        if (saved != null) return saved;
        try {
            Path file = directory().resolve(startupPhase ? "latest-startup.json" : "latest-world.json");
            if (Files.exists(file)) {
                try (var reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                    saved = JSON.fromJson(reader, ProfileReport.class);
                    if (startupPhase) startupReport = saved; else worldReport = saved;
                }
            }
        } catch (Exception error) { LogManager.getLogger("gt_shanhai:loading").warn("山海載入效能報告讀取失敗", error); }
        return saved;
    }

    public static Path directory() { return ShanhaiConfigPaths.clientDir().resolve("loading_reports"); }

    private static void publish(ProfileReport report, ShanhaiLoadingJfr sampler) {
        if (report.metrics().phase().equals("startup")) startupReport = report; else worldReport = report;
        WRITER.execute(() -> {
            try {
                Path dir = directory(); Files.createDirectories(dir);
                String name = "latest-" + report.metrics().phase();
                Files.deleteIfExists(dir.resolve(name + ".jfr"));
                var complete = report.withSampling(sampler.finish(dir.resolve(name + ".jfr"), java.time.Instant.parse(report.capturedAt())));
                if (startupReport == report) startupReport = complete;
                if (worldReport == report) worldReport = complete;
                Files.writeString(dir.resolve(name + ".json"), JSON.toJson(complete), StandardCharsets.UTF_8);
                Files.writeString(dir.resolve(name + ".txt"), text(complete), StandardCharsets.UTF_8);
                LogManager.getLogger("gt_shanhai:loading").info("山海載入分析 {}：{} 秒，報告 {}", report.metrics().phase(),
                        seconds(report.metrics().elapsedNanos()), dir.resolve(name + ".txt"));
            } catch (Exception error) { LogManager.getLogger("gt_shanhai:loading").warn("山海載入效能報告儲存失敗", error); }
            finally { sampler.close(); }
        });
    }

    public static String text(ProfileReport report) {
        var metrics = report.metrics();
        StringBuilder text = new StringBuilder("山海載入效能分析 v2\n階段：").append(metrics.phase())
                .append("；狀態：").append(metrics.outcome()).append("\n耗時：").append(seconds(metrics.elapsedNanos())).append(" s")
                .append("；程序 CPU：").append(report.processCpuNanos() < 0 ? "N/A" : seconds(report.processCpuNanos()) + " s")
                .append("；平均使用核心數：").append(report.processCpuNanos() < 0 || metrics.elapsedNanos() == 0 ? "N/A"
                        : String.format(Locale.ROOT, "%.2f / %d", (double) report.processCpuNanos() / metrics.elapsedNanos(), report.processors()))
                .append("\n堆用量淨變化：").append(metrics.heapDeltaBytes()).append(" bytes；已觀察堆峰值：").append(metrics.peakObservedHeapBytes())
                .append(" bytes\nGC：").append(report.gcCollections() < 0 ? "N/A" : report.gcCollections() + " 次 / " + report.gcMillis() + " ms").append("\n\n")
                .append("範圍：模組可執行後的載入工作，不含 Forge 獨立早期視窗。\n")
                .append("prepare/apply 為直接執行耗時；listener elapsed 含等待；Forge observed 為進度切換觀測。\n")
                .append("並行／巢狀工作不可相加當作總耗時；CPU／配置量僅在 JVM 支援時提供。\n")
                .append("堆變化包含其他執行緒與 GC，峰值僅取樣，不是單一模組的精確記憶體歸因。\n")
                .append("配方逐筆只量耗時，CPU／配置量歸外層工作；已包含分析器自身少量開銷。\n\n耗時排序\n");
        text.append("直接執行工作優先；含等待與觀測項列於後方，不表示可節省的時間。\n");
        for (var entry : ShanhaiLoadingMetrics.executionFirst(metrics.entries(),
                java.util.Comparator.comparingLong(ShanhaiLoadingMetrics.Entry::elapsedNanos))) text.append(row(entry)).append('\n');
        text.append("\n最慢配方（最多 32 筆）\n");
        for (var entry : metrics.slowRecipes()) text.append(row(entry)).append('\n');
        if (report.sampling() != null) {
            text.append("\nJFR 抽樣：").append(report.sampling().status()).append("\nCPU 熱點（樣本次數，不是精確 CPU 耗時）\n");
            for (var row : report.sampling().cpuHotspots()) text.append(row.method()).append(" / ").append(row.cpuSamples()).append(" samples\n");
            text.append("\n配置熱點（估計 bytes，不是精確配置總量）\n");
            for (var row : report.sampling().allocationHotspots()) text.append(row.method()).append(" / ").append(row.sampledAllocationBytes()).append(" estimated bytes / ").append(row.allocationSamples()).append(" samples\n");
        }
        return text.toString();
    }

    public static String row(ShanhaiLoadingMetrics.Entry entry) {
        return entry.name() + " [" + entry.kind() + "] " + seconds(entry.elapsedNanos()) + " s / " + entry.calls()
                + " calls; CPU " + (entry.cpuSamples() == 0 ? "N/A" : seconds(entry.cpuNanos()) + " s (" + entry.cpuSamples() + " samples)")
                + "; allocated " + (entry.allocationSamples() == 0 ? "N/A" : entry.allocatedBytes() + " bytes (" + entry.allocationSamples() + " samples)")
                + "; failures " + entry.failures();
    }

    private static String seconds(long nanos) { return String.format(Locale.ROOT, "%.3f", nanos / 1_000_000_000d); }
    private static long heap() { Runtime runtime = Runtime.getRuntime(); return runtime.totalMemory() - runtime.freeMemory(); }
    private static long delta(long before, long after) { return before < 0 || after < 0 ? -1 : Math.max(0, after - before); }
    private static long cpu() {
        return THREADS.isCurrentThreadCpuTimeSupported() && THREADS.isThreadCpuTimeEnabled() ? THREADS.getCurrentThreadCpuTime() : -1;
    }
    private static long allocated() {
        return THREADS instanceof com.sun.management.ThreadMXBean bean && bean.isThreadAllocatedMemorySupported()
                && bean.isThreadAllocatedMemoryEnabled() ? bean.getThreadAllocatedBytes(Thread.currentThread().threadId()) : -1;
    }

    public record ProfileReport(int schemaVersion, String capturedAt, ShanhaiLoadingMetrics.Report metrics,
                                long processCpuNanos, int processors, long gcCollections, long gcMillis, ShanhaiLoadingJfr.Summary sampling) {
        private ProfileReport withSampling(ShanhaiLoadingJfr.Summary value) {
            return new ProfileReport(schemaVersion, capturedAt, metrics, processCpuNanos, processors, gcCollections, gcMillis, value);
        }
    }

    public static final class Session {
        private final String phase;
        private final ShanhaiLoadingMetrics metrics;
        private final ShanhaiLoadingJfr sampler;
        private final Map<String, Observation> observations = new HashMap<>();
        private Environment checkpoint;
        private long processCpu, collections, gcMillis;
        private boolean processCpuSupported = true;
        private boolean gcSupported = true;
        private volatile boolean closed;
        private Session(String phase) {
            this.phase = phase; checkpoint = Environment.read(); metrics = new ShanhaiLoadingMetrics(phase, System::nanoTime, heap());
            sampler = ShanhaiLoadingJfr.start(ShanhaiLoadingPreferences.sampling());
        }
        private synchronized void observe(Map<String, String> current) {
            if (closed) return;
            long now = System.nanoTime();
            observations.entrySet().removeIf(entry -> {
                String label = current.get(entry.getKey());
                if (label != null && label.equals(entry.getValue().label())) return false;
                recordObservation(entry.getKey(), entry.getValue(), now); return true;
            });
            current.forEach((name, label) -> observations.putIfAbsent(name, new Observation(label, now)));
        }
        private void recordObservation(String name, Observation observed, long now) {
            metrics.record(name + (observed.label().isBlank() ? "" : " / " + observed.label()), "Forge observed",
                    now - observed.started(), -1, -1, 0, false);
        }
        private void consumeEnvironment() {
            Environment end = Environment.read();
            long cpu = delta(checkpoint.processCpu(), end.processCpu());
            if (cpu < 0) processCpuSupported = false; else processCpu += cpu;
            if (checkpoint.collections() < 0 || end.collections() < 0 || checkpoint.gcMillis() < 0 || end.gcMillis() < 0) gcSupported = false;
            else {
                collections += Math.max(0, end.collections() - checkpoint.collections());
                gcMillis += Math.max(0, end.gcMillis() - checkpoint.gcMillis());
            }
            checkpoint = end;
        }
        private synchronized ProfileReport report(String outcome) {
            consumeEnvironment();
            long now = System.nanoTime();
            observations.forEach((name, observed) -> recordObservation(name, observed, now)); observations.clear();
            var report = metrics.finish(outcome, heap());
            closed = true;
            return new ProfileReport(2, java.time.Instant.now().toString(), report,
                    processCpuSupported ? processCpu : -1, Runtime.getRuntime().availableProcessors(), gcSupported ? collections : -1, gcSupported ? gcMillis : -1,
                    new ShanhaiLoadingJfr.Summary("processing", java.util.List.of(), java.util.List.of()));
        }
    }
    private record Observation(String label, long started) {}
    private record Environment(long processCpu, long collections, long gcMillis) {
        private static Environment read() {
            long cpu = ManagementFactory.getOperatingSystemMXBean() instanceof com.sun.management.OperatingSystemMXBean bean ? bean.getProcessCpuTime() : -1;
            long count = 0, millis = 0;
            boolean supported = true;
            for (var bean : ManagementFactory.getGarbageCollectorMXBeans()) {
                if (bean.getCollectionCount() < 0 || bean.getCollectionTime() < 0) supported = false;
                count += Math.max(0, bean.getCollectionCount()); millis += Math.max(0, bean.getCollectionTime());
            }
            return new Environment(cpu, supported ? count : -1, supported ? millis : -1);
        }
    }
}
