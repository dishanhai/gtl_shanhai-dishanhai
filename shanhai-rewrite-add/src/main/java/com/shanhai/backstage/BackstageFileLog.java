package com.shanhai.backstage;

import java.io.Closeable;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 子进程自己的日志文件：{@code logs/shanhai-backstage.log}（**追加**写，每次一行，立即 flush）。
 *
 * <p>为什么要每个进程各写一份而不是把 stdout 接回父进程：父进程（游戏）在加载存档时本来就卡着，
 * 子进程的日志不该依赖它去读管道 —— <b>管道没人读就会把子进程卡死</b>（本工程血账）。
 * 所以子进程只写自己的文件，与父进程的 stdout 完全解耦。
 *
 * <p>格式：{@code HH:mm:ss.SSS [SHANHAI-BACKSTAGE] 正文}。时间戳用本地时间（与人看日志的习惯一致），
 * 需要与游戏日志对齐时按时间排序即可。
 */
public final class BackstageFileLog implements BackstageLog, Closeable {

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    private final Writer writer;
    private final Object lock = new Object();

    private BackstageFileLog(Writer writer) {
        this.writer = writer;
    }

    /**
     * 打开（或新建）日志文件。失败<b>不抛</b>：退化成 {@link BackstageLog#NOOP} 由调用方决定。
     *
     * @return 打开成功 = 文件日志；失败 = {@link BackstageLog#NOOP}
     */
    public static BackstageLog open(Path file) {
        try {
            final Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            final Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND);
            return new BackstageFileLog(w);
        } catch (Throwable ignored) {
            return BackstageLog.NOOP;
        }
    }

    @Override
    public void line(String message) {
        final String text = LocalDateTime.now().format(STAMP) + " [SHANHAI-BACKSTAGE] " + message;
        synchronized (lock) {
            try {
                writer.write(text);
                writer.write('\n');
                writer.flush();
            } catch (Throwable ignored) {
                // 日志写不出来不许影响主流程（子进程的职责是计时与写标题，不是记日志）
            }
        }
    }

    @Override
    public void close() {
        synchronized (lock) {
            try {
                writer.close();
            } catch (IOException ignored) {
                // 关不上也无所谓：进程要退出了
            }
        }
    }
}
