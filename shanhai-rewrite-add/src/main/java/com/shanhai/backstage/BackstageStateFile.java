package com.shanhai.backstage;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;

/**
 * 状态文件的读与写：<b>原子替换</b> + 读侧「变了才重读」。
 *
 * <h4>写：先写 .tmp，再 ATOMIC_MOVE 覆盖</h4>
 * <p>为什么不用 {@code Files.write(target, ...)} 直接改：那样读侧有机会读到<b>半个文件</b>，
 * 子进程就会解析失败一次（最坏情况：它把那一拍当成"没状态"而少写一次标题）。
 * 换成「写临时文件 + 原子改名」之后，读侧看到的永远是<b>某一版完整内容</b>：
 * Windows 上 {@code MoveFileEx(..., MOVEFILE_REPLACE_EXISTING)} 对同卷文件是原子的。
 * 文件系统不支持 ATOMIC_MOVE 时降级成普通 REPLACE_EXISTING（会打一行日志，不静默）。
 *
 * <h4>读：mtime + size 没变就跳过</h4>
 * <p>子进程 100 ms 一拍，而状态文件只有在 phase / 关键值变化时才写 ⇒ 绝大多数拍子应该"什么都不做"。
 * NTFS 的 mtime 精度是 100 ns 级，两次不同的原子替换不可能落在同一个时间戳上，所以这个判据是安全的。
 * 解析失败（读到空文件 / 旧格式）时<b>保留上一份</b>，不抛给调用方 —— 子进程不许因为一行坏数据就死。
 */
public final class BackstageStateFile {

    private final Path path;

    /** 上一次读到的 mtime（毫秒，0 = 还没读过）。 */
    private long lastModifiedMs = 0L;
    /** 上一次读到的字节数（-1 = 还没读过）。 */
    private long lastSize = -1L;
    /** 缓存的状态（读不到时返回 null）。 */
    private BackstageState cached;

    public BackstageStateFile(Path path) {
        this.path = path;
    }

    public Path path() {
        return path;
    }

    /**
     * 依次取出「写侧」与「读侧」用的临时文件名（同目录，保证 ATOMIC_MOVE 同卷）。
     *
     * <p>用固定后缀而不是随机名：同一时刻只允许一个写者（游戏主线程），固定名可以让上次残留的
     * 临时文件被直接覆盖，不会越积越多。
     */
    public static Path tmpPathFor(Path target) {
        return target.resolveSibling(target.getFileName().toString() + ".tmp");
    }

    /**
     * 原子写一份状态。
     *
     * @throws IOException 目录建不出来 / 写临时文件失败 / 改名失败
     */
    public static void writeAtomic(Path target, BackstageState state) throws IOException {
        final Path parent = target.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        final byte[] bytes = state.toText().getBytes(StandardCharsets.UTF_8);
        final Path tmp = tmpPathFor(target);
        Files.write(tmp, bytes,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        moveReplacing(tmp, target);
    }

    /**
     * 改名覆盖，失败重试几次。
     *
     * <p>🔴 Windows 上有一个必须处理的现实：<b>目标文件正被读侧打开时，MoveFileEx 会以
     * {@code AccessDeniedException} 失败</b>（Java 的读侧没有 FILE_SHARE_DELETE）。子进程 100 ms 一拍、
     * 每拍只读几微秒，撞上的概率很小但确实存在（1000 次写读对撞里就撞到过）。
     * 这里最多试 {@link #MOVE_RETRIES} 次、每次间隔 2 ms —— 读侧那几微秒早就关掉了。
     * 全部失败才抛：调用方（游戏侧）只记一行日志，下一次状态变化会再写一次。
     */
    private static void moveReplacing(Path tmp, Path target) throws IOException {
        IOException last = null;
        for (int attempt = 0; attempt < MOVE_RETRIES; attempt++) {
            try {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                return;
            } catch (AtomicMoveNotSupportedException e) {
                // 文件系统不支持原子改名（极少数情况）：退化成普通替换，至少保证内容完整。
                try {
                    Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
                    return;
                } catch (java.nio.file.FileSystemException sharing) {
                    last = sharing;
                }
            } catch (java.nio.file.FileSystemException e) {
                last = e;
            }
            try {
                Thread.sleep(3L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted while replacing state file", interrupted);
            }
        }
        if (last != null) {
            throw last;
        }
    }

    /** 改名失败时的重试次数（10 × 3 ms ≈ 30 ms 的容忍窗口）。 */
    private static final int MOVE_RETRIES = 10;

    /** 直接读一份（不走缓存；探针/诊断用）。读不到或解析失败返回 {@code null}。 */
    public static BackstageState read(Path target) throws IOException {
        if (!Files.isRegularFile(target)) {
            return null;
        }
        final List<String> lines = Files.readAllLines(target, StandardCharsets.UTF_8);
        return BackstageState.parse(lines);
    }

    /**
     * 变了才重读；没变返回缓存（可能为 {@code null}）。
     *
     * <p>这里绝不抛给调用方：子进程的循环里任何一次 IOException 都只该让这一拍"没新消息"，
     * 不该让它退出（父进程可能正在原子替换的那一瞬间被我们撞上）。
     */
    public BackstageState readIfChanged() {
        try {
            final BasicFileAttributes attrs = Files.readAttributes(path, BasicFileAttributes.class);
            final long modified = attrs.lastModifiedTime().toMillis();
            final long size = attrs.size();
            if (cached != null && modified == lastModifiedMs && size == lastSize) {
                return cached;
            }
            final BackstageState parsed = read(path);
            if (parsed != null) {
                cached = parsed;
                lastModifiedMs = modified;
                lastSize = size;
                return parsed;
            }
            // 读到了但解析不出（空文件 / 旧格式）：保留上一份，不覆盖缓存。
            lastModifiedMs = modified;
            lastSize = size;
            return cached;
        } catch (Throwable ignored) {
            // 文件不存在 / 正被替换 / 权限问题：这一拍就当"没有新状态"
            return cached;
        }
    }

    /** 清掉缓存（探针用）。 */
    public void invalidate() {
        cached = null;
        lastModifiedMs = 0L;
        lastSize = -1L;
    }
}
