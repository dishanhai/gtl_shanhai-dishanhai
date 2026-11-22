package com.shanhai.backstage;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Supplier;

/**
 * <b>标题带画线程</b>：独立线程、100 ms 一拍，直接往目标窗口的标题带画。
 *
 * <h4>三档（{@code config/shanhai/backstage_caption.txt} 一行 {@code caption_paint=…}）</h4>
 * <ul>
 *   <li>{@code inflight}（默认，文件读不到也按它）：仅当写线程有一次 {@code SetWindowTextW}
 *       已经飞了 &gt; 300 ms 没返回时才画（{@link Win32TitleSink#inflightMs()}）；</li>
 *   <li>{@code always}：每拍都画；</li>
 *   <li>{@code off}：不画。</li>
 * </ul>
 *
 * <h4>恢复</h4>
 * <p>写调用返回（{@code inflightMs() == 0}）⇒ 停手（{@code caption_paint stop reason=resume}），
 * 让原生重绘覆盖我们画的像素。
 *
 * <h4>不影响写线程与计时</h4>
 * <p>本线程只调 GDI（不 SendMessage、不碰写队列）；任何 Win32 返 0 / 抛异常 ⇒ 停画 +
 * {@code caption_paint=unavailable reason=…}，数字照走。
 */
final class CaptionPaintThread extends Thread {

    enum Mode {
        ALWAYS,
        INFLIGHT,
        OFF;

        static Mode parse(String raw) {
            if (raw == null) {
                return INFLIGHT;
            }
            final String v = raw.trim().toLowerCase(java.util.Locale.ROOT);
            if (v.equals("always")) {
                return ALWAYS;
            }
            if (v.equals("off")) {
                return OFF;
            }
            return INFLIGHT;
        }
    }

    private static final long INFLIGHT_THRESHOLD_MS = 300L;
    private static final long SLOW_MS = 200L;
    private static final long NORMAL_INTERVAL_MS = 100L;
    private static final long SLOW_INTERVAL_MS = 500L;
    private static final long STATS_INTERVAL_MS = 10_000L;
    private static final String CONFIG_NAME = "backstage_caption.txt";

    private final CaptionPainter painter;
    private final Win32TitleSink writer;
    private final BackstageLog log;
    private final Supplier<String> title;
    private final Path configPath;
    private final long processStartMs;

    private volatile boolean running = true;
    private volatile long firstPaintMs = -1L;
    private volatile boolean unavailable;

    CaptionPaintThread(CaptionPainter painter, Win32TitleSink writer, BackstageLog log,
                       Supplier<String> title, Path statePath) {
        super("shanhai-caption-paint");
        setDaemon(true);
        this.painter = painter;
        this.writer = writer;
        this.log = log;
        this.title = title;
        this.configPath = statePath == null ? null : statePath.resolveSibling(CONFIG_NAME);
        this.processStartMs = startEpochMs();
    }

    long firstPaintMs() {
        return firstPaintMs;
    }

    void shutdown() {
        running = false;
        interrupt();
    }

    @Override
    public void run() {
        long next = System.currentTimeMillis();
        long ok = 0L;
        long fail = 0L;
        long maxMs = 0L;
        long statsAt = System.currentTimeMillis();
        long interval = NORMAL_INTERVAL_MS;
        boolean painting = false;
        String reason = "";
        while (running) {
            try {
                final Mode mode = readMode();
                final boolean want = mode == Mode.ALWAYS
                        || (mode == Mode.INFLIGHT && writer != null
                        && writer.inflightMs() > INFLIGHT_THRESHOLD_MS);
                if (want && !painting) {
                    painting = true;
                    reason = mode == Mode.ALWAYS ? "always" : "inflight";
                    log.line("caption_paint begin reason=" + reason);
                }
                if (!want && painting) {
                    painting = false;
                    log.line("caption_paint stop reason=" + (mode == Mode.OFF ? "off" : "resume"));
                }
                if (want && !unavailable) {
                    final long p0 = System.nanoTime();
                    final boolean drawn = painter != null && painter.paintCaption(writer.hwnd(), title.get());
                    final long ms = (System.nanoTime() - p0) / 1_000_000L;
                    if (drawn) {
                        ok++;
                        if (firstPaintMs < 0L) {
                            firstPaintMs = System.currentTimeMillis() - processStartMs;
                            log.line("first_paint ms=" + firstPaintMs);
                        }
                        maxMs = Math.max(maxMs, ms);
                        if (ms > SLOW_MS) {
                            log.line("caption_paint slow ms=" + ms);
                            interval = SLOW_INTERVAL_MS;
                        } else {
                            interval = NORMAL_INTERVAL_MS;
                        }
                    } else {
                        fail++;
                        unavailable = true;
                        painting = false;
                        log.line("caption_paint=unavailable reason="
                                + (painter == null ? "painter_null" : "paint_failed"));
                    }
                }
                if (System.currentTimeMillis() - statsAt >= STATS_INTERVAL_MS) {
                    statsAt = System.currentTimeMillis();
                    log.line("caption_paint stats ok=" + ok + " fail=" + fail + " ms_max=" + maxMs);
                }
                next += interval;
                final long sleep = next - System.currentTimeMillis();
                if (sleep > 0L) {
                    Thread.sleep(sleep);
                } else {
                    next = System.currentTimeMillis();
                }
            } catch (InterruptedException e) {
                return;
            } catch (Throwable t) {
                if (!unavailable) {
                    unavailable = true;
                    log.line("caption_paint=unavailable reason=" + t.getClass().getSimpleName());
                }
                return;
            }
        }
    }

    private Mode readMode() {
        try {
            if (configPath != null && Files.isRegularFile(configPath)) {
                for (String line : Files.readAllLines(configPath, StandardCharsets.UTF_8)) {
                    final String s = line.trim();
                    if (s.startsWith("caption_paint=")) {
                        return Mode.parse(s.substring("caption_paint=".length()));
                    }
                }
            }
        } catch (Throwable ignored) {
            // 读失败按默认
        }
        return Mode.INFLIGHT;
    }

    private static long startEpochMs() {
        try {
            return java.lang.management.ManagementFactory.getRuntimeMXBean().getStartTime();
        } catch (Throwable t) {
            return System.currentTimeMillis();
        }
    }
}
