package com.dishanhai.gt_shanhai.common.time.replay;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

/**
 * 山海重构 · <b>第 4 层：存档级 SavedData 名单审计</b>（只读；不写任何存档文件）。
 *
 * <h2>1. 用户口径</h2>
 * <blockquote>「第 4 层（存档级 SavedData 名单）：列出 level.dat 与 data/ 下的 SavedData 全名，
 * <b>只覆盖我们能安全写回的</b>（scoreboard/teams 之类），其余在矩阵里标"未覆盖·原因"」</blockquote>
 *
 * <h2>2. 名单从哪来</h2>
 * 不在代码里硬编码一份"可能存在的文件名"，而是<b>现场扫</b>
 * {@code <world>/data/*.dat} 与 {@code <world>/level.dat}（{@code MinecraftServer#getWorldPath}）。
 * 这样整合包自己加的 SavedData 也会出现在名单里（它们默认标"未覆盖"，原因写在行尾）。
 *
 * <h2>3. 只读边界</h2>
 * 本类<b>只做 {@code DirectoryStream} 列目录</b>：不打开、不解析、不写入任何 {@code .dat}。
 * 计分板/游戏规则的写回走 {@link ShanhaiReplayScalarRecorder} 的公开 API（不碰文件）。
 */
public final class ShanhaiReplaySavedDataAudit {

    /** 我们能安全写回的存档级内容（按文件名/来源归类）。 */
    private static final String[] SAFE = {"scoreboard.dat", "level.dat#gamerules"};

    /** 一行审计结果（{@code id=... covered=0/1 reason=...}）。 */
    public record Entry(String name, boolean covered, String reason) {

        public String toLine() {
            return "saveddata name=" + this.name + " covered=" + (this.covered ? 1 : 0)
                    + " reason=" + this.reason;
        }
    }

    /** 扫一遍世界目录，按名字排序返回。 */
    public static List<Entry> scan(MinecraftServer server) {
        final List<Entry> out = new ArrayList<>();
        if (server == null) {
            return out;
        }
        try {
            final Path root = server.getWorldPath(LevelResource.ROOT);
            if (root == null) {
                return out;
            }
            final Path levelDat = root.resolve("level.dat");
            if (Files.isRegularFile(levelDat)) {
                out.add(new Entry("level.dat", false,
                        "存档元信息（种子/难度/允许指令）；改写风险高，只读不写"));
            }
            final Path data = root.resolve("data");
            if (Files.isDirectory(data)) {
                final List<String> names = new ArrayList<>();
                try (DirectoryStream<Path> stream = Files.newDirectoryStream(data, "*.dat")) {
                    for (Path p : stream) {
                        names.add(p.getFileName().toString());
                    }
                }
                names.sort(String::compareTo);
                for (String name : names) {
                    out.add(new Entry("data/" + name, isSafe(name), reasonFor(name)));
                }
            }
        } catch (IOException | RuntimeException ignored) {
            // 列目录失败不影响倒流本身：名单里少几行，比崩掉好
        }
        return out;
    }

    private static boolean isSafe(String fileName) {
        for (String safe : SAFE) {
            if (safe.equals(fileName)) {
                return true;
            }
        }
        return false;
    }

    private static String reasonFor(String fileName) {
        if ("scoreboard.dat".equals(fileName)) {
            return "已覆盖：整份快照 + 清空后 load（ShanhaiReplayScalarRecorder）";
        }
        return "未覆盖：格式由该 SavedData 自己定义，写回风险高（只列名单）";
    }

    /** 逐行文本（命令 / 日志）。 */
    public static List<String> lines(MinecraftServer server) {
        final List<Entry> entries = scan(server);
        final List<String> out = new ArrayList<>(entries.size() + 1);
        for (Entry e : entries) {
            out.add(e.toLine());
        }
        out.add("saveddata_total=" + entries.size());
        return out;
    }

    private ShanhaiReplaySavedDataAudit() {
    }
}
