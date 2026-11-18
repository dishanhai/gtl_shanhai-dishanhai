package com.dishanhai.gt_shanhai.common.saveedit;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code level.dat} 的读 / 改 / 写（<b>离线可跑</b>：只依赖 {@code net.minecraft.nbt}）。
 *
 * <h2>一、为什么改 level.dat 必须走"世界数据模型"而不是裸 NBT</h2>
 * {@code doWorldLoad:2013} 会 {@code saveDataTag(...)}：把内存里的 {@code PrimaryLevelData}
 * 重新 {@code createTag()} 出来覆盖 level.dat。也就是说 —— <b>重开时 level.dat 不是"被保留"，
 * 而是"被重造"</b>。所以：
 * <ul>
 *   <li>写进去的键只有在 {@code setTagData} 的写入清单里，才会在重造后仍然是我们的值；</li>
 *   <li>不在清单里的键，重开一次就消失（甚至可能被写进别的默认值）。</li>
 * </ul>
 * 白名单见 {@link ShanhaiLevelDataSpec}（21 项，逐项附"读它的方法 / 写回它的方法"）。
 *
 * <h2>二、写盘顺序（与一期同一条纪律，🔴 只在停服窗口内被调用）</h2>
 * <pre>
 *   读 before → copy 成 after → 改 after（只动白名单路径）
 *     → diff(before, after) 必须非空（否则报 no_effective_change，一个字节都不写）
 *     → 编码成 gzip NBT 字节
 *     → {@link ShanhaiSaveEditStorage#writeAtomic}（备份 + tmp + ATOMIC_MOVE + 回读哈希）
 *     → 从盘上再读一次 reread
 *     → diff(before, reread) 必须**逐条等于** diff(before, after)
 *         相等 ⇒ ok；不等 ⇒ 用备份把原文件盖回去，报 reread_mismatch
 * </pre>
 * 🔴 本方法<b>不</b>判窗口：窗口判据在 {@link ShanhaiSaveEditFlow#tryApply(String)}。
 *
 * <h2>三、我们从不"删键"</h2>
 * {@code mutate} 只对白名单路径做 put；根上的 {@code fml}（Forge 额外数据）与
 * {@code Data} 里其余所有键都原样留在同一个 {@link CompoundTag} 里一起被重新编码
 * ⇒ 判据"其余 NBT 键逐条等价"天然成立，并且由 {@link ShanhaiPlayerDataEditor#diffTags} 逐条验证。
 */
public final class ShanhaiLevelDataEditor {

    /** 日志前缀（与一期同一个串）。 */
    public static final String PREFIX = ShanhaiSaveEditFlow.PREFIX;

    /** 一次改盘的结果。 */
    public record Result(boolean ok, String error, List<String> changed, List<String> notes,
                         ShanhaiSaveEditStorage.WriteResult write) {
        public String line() {
            return "ok=" + this.ok
                    + (this.error == null || this.error.isEmpty() ? "" : " error=" + this.error)
                    + " changed=" + this.changed
                    + (this.notes == null || this.notes.isEmpty() ? "" : " notes=" + this.notes)
                    + (this.write == null ? "" : " | " + this.write.line());
        }
    }

    private ShanhaiLevelDataEditor() {}

    // ================================================================== 读 / 编码

    /** 读整份 level.dat（不存在 ⇒ {@code null}）。 */
    public static CompoundTag read(Path file) throws IOException {
        if (file == null || !Files.isRegularFile(file)) {
            return null;
        }
        return NbtIo.readCompressed(file.toFile());
    }

    /** 编码成 gzip 后的 NBT 字节（与原版 {@code NbtIo.writeCompressed} 同一格式）。 */
    public static byte[] encode(CompoundTag tag) throws IOException {
        final ByteArrayOutputStream out = new ByteArrayOutputStream(64 * 1024);
        NbtIo.writeCompressed(tag, out);
        return out.toByteArray();
    }

    /** 从字节解出来（回读校验用；不落盘也能验）。 */
    public static CompoundTag decode(byte[] gzip) throws IOException {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(gzip))) {
            return NbtIo.readCompressed(in);
        }
    }

    /** {@code <存档根>/level.dat}。 */
    public static Path levelDatOf(Path worldDir) {
        return worldDir == null ? null : worldDir.resolve(ShanhaiLevelDataSpec.LEVEL_DAT);
    }

    /** {@code playerdata/<uuid>.dat} 的父目录的父目录 = 存档根（面板/桥都用这条推导）。 */
    public static Path worldDirOfPlayerData(Path playerDataFile) {
        if (playerDataFile == null || playerDataFile.getParent() == null) {
            return null;
        }
        return playerDataFile.getParent().getParent();
    }

    // ================================================================== 当前值

    /** 读一份摘要（面板第二页的"当前值"）。任何异常都折成 {@code present=false}，不抛。 */
    public static ShanhaiLevelDataSnapshot summarize(Path levelDat) {
        final String name = levelDat == null ? "-" : levelDat.getFileName().toString();
        if (levelDat == null || !Files.isRegularFile(levelDat)) {
            return ShanhaiLevelDataSnapshot.missing(name, ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.3703e47860f64a12"));
        }
        final long bytes;
        final CompoundTag root;
        try {
            bytes = Files.size(levelDat);
            root = read(levelDat);
        } catch (Throwable t) {
            return ShanhaiLevelDataSnapshot.missing(name, ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.6687f349a978a8bc") + t);
        }
        if (root == null) {
            return ShanhaiLevelDataSnapshot.missing(name, ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.d350d798f368f180"));
        }
        final CompoundTag data = dataOf(root);
        if (data == null) {
            return ShanhaiLevelDataSnapshot.missing(name, ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.edfeeded4bd87f07"));
        }
        final Map<String, String> current = new LinkedHashMap<>();
        for (ShanhaiLevelDataSpec.Field f : ShanhaiLevelDataSpec.FIELDS) {
            current.put(f.id(), currentValue(root, f));
        }
        // 面板/日志还要几个"只读不写"的元信息
        current.put("Data.version", String.valueOf(data.getInt("version")));
        current.put("Data.DataVersion", String.valueOf(data.getInt("DataVersion")));
        return ShanhaiLevelDataSnapshot.of(name, bytes, current);
    }

    /** 某个字段的当前值（规范写法；取不到返回空串）。 */
    public static String currentValue(CompoundTag root, ShanhaiLevelDataSpec.Field f) {
        if (root == null || f == null) {
            return "";
        }
        final CompoundTag data = dataOf(root);
        if (data == null) {
            return "";
        }
        try {
            switch (f.kind()) {
                case STRING:
                    return data.contains(keyOf(f), 8) ? data.getString(keyOf(f)) : "";
                case LONG:
                    return data.contains(keyOf(f), 99) ? String.valueOf(data.getLong(keyOf(f))) : "";
                case INT:
                    return data.contains(keyOf(f), 99) ? String.valueOf(data.getInt(keyOf(f))) : "";
                case BYTE:
                    return data.contains(keyOf(f), 99) ? String.valueOf(data.getByte(keyOf(f))) : "";
                case FLOAT:
                    return data.contains(keyOf(f), 99)
                            ? ShanhaiLevelDataEdit.trimFloat(data.getFloat(keyOf(f))) : "";
                case BOOL:
                    return data.contains(keyOf(f), 99) ? String.valueOf(data.getBoolean(keyOf(f))) : "";
                case RULE_BOOL:
                case RULE_INT: {
                    final Tag rules = data.get("GameRules");
                    if (!(rules instanceof CompoundTag c)) {
                        return "";
                    }
                    return c.contains(keyOf(f), 8) ? c.getString(keyOf(f)) : "";
                }
                default:
                    return "";
            }
        } catch (Throwable t) {
            return "";
        }
    }

    /** {@code Data.GameRules.keepInventory} → {@code keepInventory}；{@code Data.SpawnX} → {@code SpawnX}。 */
    public static String keyOf(ShanhaiLevelDataSpec.Field f) {
        final String id = f.id();
        final int dot = id.lastIndexOf('.');
        return dot < 0 ? id : id.substring(dot + 1);
    }

    /** 根下的 {@code Data} 复合标签（不是复合标签 ⇒ {@code null}）。 */
    public static CompoundTag dataOf(CompoundTag root) {
        final Tag t = root == null ? null : root.get("Data");
        return t instanceof CompoundTag c ? c : null;
    }

    // ================================================================== Data.Player（单人存档里的第二份玩家数据）

    /**
     * {@code Data} 下那个"单人存档玩家数据"的键名 = {@code "Player"}。
     *
     * <p>出处：{@code javap -p net.minecraft.world.level.storage.PrimaryLevelData} 的
     * {@code protected static final java.lang.String PLAYER;}，以及 {@code setTagData} 里
     * {@code ldc #43 // String Player}。
     */
    public static final String DATA_PLAYER = "Player";

    /** 根下的 {@code Data.Player}（不存在 / 不是复合标签 ⇒ {@code null}）。 */
    public static CompoundTag playerTagOf(CompoundTag root) {
        final CompoundTag data = dataOf(root);
        final Tag t = data == null ? null : data.get(DATA_PLAYER);
        return t instanceof CompoundTag c ? c : null;
    }

    /**
     * 🔴 <b>把 {@code level.dat} 里的 {@code Data.Player} 整棵删掉</b>（只删这一个键，其它键一个字节都不碰）。
     *
     * <h2>一、为什么必须删（javap 事实，不是推测）</h2>
     * {@code PlayerList#load(ServerPlayer)} 的头 36 字节：
     * <pre>
     *   0-12:  tag = server.getWorldData().getLoadedPlayerTag()          ← 就是 level.dat 的 Data.Player
     *   13-24: if (!server.isSingleplayerOwner(player.getGameProfile())) goto 67
     *   27-28: if (tag == null) goto 67
     *   31-35: player.load(tag)                                          ← 🔴 用 level.dat 里那份
     *   67-75: tag = playerIo.load(player)                               ← 只有走不到上面才读 playerdata/&lt;uuid&gt;.dat
     * </pre>
     * ⇒ 本机是<b>单机 owner</b>、tag 又非空 ⇒ 我们改的 {@code playerdata/<uuid>.dat} 每次都被无视
     * （现场：删掉的物品重开后又回来了，正是这条路把旧值盖回去）。
     *
     * <h2>二、删掉之后会发生什么</h2>
     * <ul>
     *   <li>{@code getLoadedPlayerTag()} ⇒ {@code null} ⇒ {@code PlayerList#load} 走 playerdata 分支 ✔；</li>
     *   <li>下一次保存 {@code IntegratedPlayerList#save} 把<b>内存里的玩家</b>缓存成 {@code playerData}，
     *       再经 {@code MinecraftServer.saveLevel} → {@code PrimaryLevelData.createTag/setTagData}
     *       写回 {@code Data.Player}（那里是 {@code ifnull 517} 守卫，null 就<b>不写</b>这个键、
     *       不是 NPE）⇒ <b>会自愈</b>，所以<b>每次 apply 都要再删一次</b>，不是一次性动作；</li>
     *   <li>自愈写回去的是"从 playerdata 读进来的那个玩家"，也就是改过的版本 ✔。</li>
     * </ul>
     *
     * <h2>三、写法与既有纪律一致</h2>
     * 读 before → copy 成 after → {@code afterData.remove("Player")} → diff 必须<b>恰好</b>是
     * {@code Data.Player (-removed)} 一条 → 编码 → {@link ShanhaiSaveEditStorage#writeAtomic}
     * （备份 + tmp + ATOMIC_MOVE + sha 回读）→ 从盘上再读一次：差异必须逐条等于 intended
     * <b>并且</b>该键必须真的不在了。任何一条不满足 ⇒ 用备份盖回去并报错。
     *
     * @return {@code ok=true} 且 {@code changed} 为空 = 本来就没有 {@code Data.Player}（什么都没写）
     */
    public static Result dropPlayerTag(Path levelDat, Path backupRoot, String stamp) {
        final List<String> notes = new ArrayList<>();
        if (levelDat == null) {
            return logDrop(false, false, false, new Result(false, "null_file", List.of(), notes, null));
        }
        if (!Files.isRegularFile(levelDat)) {
            notes.add(ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.33ec49d419a7cfbf") + levelDat + ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.76855c1cac492ffb"));
            return logDrop(false, true, false, new Result(true, "", List.of(), notes, null));
        }
        final CompoundTag before;
        try {
            before = read(levelDat);
        } catch (Throwable t) {
            return logDrop(true, false, false, new Result(false, "read_before_failed:" + t,
                    List.of(), notes, null));
        }
        if (before == null) {
            return logDrop(true, false, false, new Result(false, "level_dat_unreadable:" + levelDat,
                    List.of(), notes, null));
        }
        final CompoundTag data = dataOf(before);
        if (data == null) {
            return logDrop(false, false, false, new Result(false, ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.b2cd88eda002625e"),
                    List.of(), notes, null));
        }
        final boolean had = data.get(DATA_PLAYER) instanceof CompoundTag;
        if (!had) {
            notes.add(ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.c82943c6ab333bdc"));
            return logDrop(false, true, false, new Result(true, "", List.of(), notes, null));
        }
        final CompoundTag after = before.copy();
        final CompoundTag afterData = dataOf(after);
        if (afterData == null) {
            return logDrop(true, false, false, new Result(false, "bad_level_dat_copy",
                    List.of(), notes, null));
        }
        afterData.remove(DATA_PLAYER);
        final List<String> intended;
        try {
            intended = ShanhaiPlayerDataEditor.diffTags(before, after);
        } catch (Throwable t) {
            return logDrop(true, false, false, new Result(false, "diff_failed:" + t, List.of(), notes, null));
        }
        if (intended.isEmpty()) {
            return logDrop(true, false, false, new Result(false, "no_effective_change", List.of(), notes, null));
        }
        final byte[] content;
        try {
            content = encode(after);
        } catch (Throwable t) {
            return logDrop(true, false, false, new Result(false, "encode_failed:" + t, List.of(), notes, null));
        }
        // 🔴 备份目录单独一层（{@code <stamp>/leveldat-player/level.dat}）：
        //    {@link ShanhaiSaveEditStorage#backup} 是"同 stamp 同名就覆盖"，而世界页（第 ② 页）
        //    在同一次 apply 里也会写 level.dat —— 共用目录会把"删之前那份原始 level.dat"覆盖掉。
        final Path dropBackupRoot = backupRoot == null ? null : backupRoot.resolve("leveldat-player");
        final ShanhaiSaveEditStorage.WriteResult write =
                ShanhaiSaveEditStorage.writeAtomic(levelDat, content, dropBackupRoot, stamp);
        if (!write.ok()) {
            final boolean dirty = write.targetSha256After() != null
                    && !write.targetSha256After().equals(write.originalSha256());
            final boolean restored = dirty && ShanhaiSaveEditStorage.restore(write.backupPath(), levelDat);
            // 写失败：不猜，读一次盘上现在还有没有这个键
            return logDrop(true, false, reReadPresent(levelDat, true),
                    new Result(false, write.error() + (dirty ? (restored ? "+restored" : "+restore_failed") : ""),
                            List.of(), notes, write));
        }
        final CompoundTag reread;
        try {
            reread = read(levelDat);
        } catch (Throwable t) {
            final boolean restored = ShanhaiSaveEditStorage.restore(write.backupPath(), levelDat);
            return logDrop(true, false, reReadPresent(levelDat, true),
                    new Result(false, "reread_failed:" + t + (restored ? "+restored" : "+restore_failed backup=" + write.backupPath()), List.of(), notes, write));
        }
        final boolean stillPresent = playerTagOf(reread) != null;
        final List<String> observed = reread == null
                ? List.of("<unreadable>") : ShanhaiPlayerDataEditor.diffTags(before, reread);
        if (stillPresent || !observed.equals(intended)) {
            final boolean restored = ShanhaiSaveEditStorage.restore(write.backupPath(), levelDat);
            return logDrop(true, false, stillPresent, new Result(false,
                    "reread_mismatch still_present=" + stillPresent + " intended=" + intended
                            + " observed=" + observed + (restored ? "+restored" : "+restore_failed"),
                    observed, notes, write));
        }
        notes.add(ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.86bf7c48f4618135"));
        return logDrop(true, true, false, new Result(true, "", observed, notes, write));
    }

    /**
     * 打那一行诊断（<b>每次调用恰好一行</b>，成功/跳过/失败都有）：
     * {@code panel_leveldat_player_dropped had=<bool> ok=<bool> readback_present=<bool>}。
     *
     * <p>判据：{@code readback_present=false} 才算删干净；{@code had=false} 说明这个存档本来就没有第二份。
     */
    private static Result logDrop(boolean had, boolean ok, boolean readbackPresent, Result result) {
        org.apache.logging.log4j.LogManager.getLogger("gt_shanhai").info(
                "{} panel_leveldat_player_dropped had={} ok={} readback_present={} {}",
                PREFIX, had, ok, readbackPresent,
                result.error() == null || result.error().isEmpty() ? "" : "error=" + result.error());
        return result;
    }

    /** 盘上现在还有没有 {@code Data.Player}（读不出来 ⇒ {@code fallback}，不猜）。 */
    private static boolean reReadPresent(Path levelDat, boolean fallback) {
        try {
            final CompoundTag reread = read(levelDat);
            return reread == null ? fallback : playerTagOf(reread) != null;
        } catch (Throwable t) {
            return fallback;
        }
    }

    // ================================================================== 改

    /**
     * 把 {@code edit} 应用到 {@code root} 上（<b>纯内存</b>）。
     *
     * <p>拆分出来的原因与一期相同：离线判据要先拿到"预期结果"再和盘上的回读比对，
     * 生产路径两者用同一段代码 —— 不许有第二份实现。
     */
    public static void mutate(CompoundTag root, ShanhaiLevelDataEdit edit, List<String> notes) {
        if (root == null || edit == null || edit.values.isEmpty()) {
            return;
        }
        final CompoundTag data = dataOf(root);
        if (data == null) {
            throw new IllegalStateException(ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.db8cb7586b5d0cd7"));
        }
        final List<String> out = notes == null ? new ArrayList<>() : notes;
        for (Map.Entry<String, String> e : edit.values.entrySet()) {
            final ShanhaiLevelDataSpec.Field f = ShanhaiLevelDataSpec.field(e.getKey());
            if (f == null) {
                continue;               // parse 已经拒过的，这里也不许"顺手写"
            }
            final String value = e.getValue();
            final String before = currentValue(root, f);
            if (before.equals(value)) {
                out.add(f.label() + ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.67cbe0f48f6155ca") + value + ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.c46301b66d49fa91"));
                continue;
            }
            switch (f.kind()) {
                case STRING:
                    data.putString(keyOf(f), value);
                    break;
                case LONG:
                    data.putLong(keyOf(f), Long.parseLong(value));
                    break;
                case INT:
                    data.putInt(keyOf(f), Integer.parseInt(value));
                    break;
                case BYTE:
                    // 🔴 原版 Difficulty 是 ByteTag（setTagData 用 putByte）；写 IntTag 会被读成别的类型
                    data.putByte(keyOf(f), Byte.parseByte(value));
                    break;
                case FLOAT:
                    data.putFloat(keyOf(f), Float.parseFloat(value));
                    break;
                case BOOL:
                    data.putBoolean(keyOf(f), Boolean.parseBoolean(value));
                    break;
                case RULE_BOOL:
                case RULE_INT: {
                    CompoundTag rules = data.contains("GameRules", 10) ? data.getCompound("GameRules") : null;
                    if (rules == null) {
                        rules = new CompoundTag();
                        data.put("GameRules", rules);
                        out.add(ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.99143fe853559ac3"));
                    }
                    // 游戏规则在 NBT 里是 StringTag："true"/"false"/"3"
                    rules.putString(keyOf(f), value);
                    break;
                }
                default:
                    break;
            }
        }
    }

    // ================================================================== 改 + 写

    /**
     * 把 {@code edit} 应用到 {@code levelDat} 上（<b>会写盘</b>）。
     *
     * <p>🔴 调用方必须先通过 {@link ShanhaiSaveEditFlow#tryApply(String)}。
     *
     * @param backupRoot 备份根目录（{@code temp\saveedit-backup}）
     * @param stamp      备份子目录名（时间戳）
     */
    public static Result apply(Path levelDat, ShanhaiLevelDataEdit edit, Path backupRoot, String stamp) {
        final List<String> notes = new ArrayList<>();
        if (levelDat == null) {
            return new Result(false, "null_file", List.of(), notes, null);
        }
        if (edit == null || edit.isEmpty()) {
            return new Result(false, edit != null && edit.errors != null && !edit.errors.isEmpty()
                    ? "invalid_edit:" + edit.errors : "empty_edit", List.of(), notes, null);
        }
        if (edit.errors != null && !edit.errors.isEmpty()) {
            return new Result(false, "invalid_edit:" + edit.errors, List.of(), notes, null);
        }
        final CompoundTag before;
        try {
            before = read(levelDat);
        } catch (Throwable t) {
            return new Result(false, "read_before_failed:" + t, List.of(), notes, null);
        }
        if (before == null) {
            return new Result(false, "level_dat_missing:" + levelDat, List.of(), notes, null);
        }
        if (dataOf(before) == null) {
            return new Result(false, ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.b2cd88eda002625e"), List.of(), notes, null);
        }
        final CompoundTag after = before.copy();
        final List<String> intended;
        try {
            mutate(after, edit, notes);
            intended = ShanhaiPlayerDataEditor.diffTags(before, after);
        } catch (Throwable t) {
            return new Result(false, "diff_failed:" + t, List.of(), notes, null);
        }
        if (intended.isEmpty()) {
            return new Result(false, "no_effective_change", List.of(), notes, null);
        }
        final byte[] content;
        try {
            content = encode(after);
        } catch (Throwable t) {
            return new Result(false, "encode_failed:" + t, List.of(), notes, null);
        }
        final ShanhaiSaveEditStorage.WriteResult write =
                ShanhaiSaveEditStorage.writeAtomic(levelDat, content, backupRoot, stamp);
        if (!write.ok()) {
            final boolean dirty = write.targetSha256After() != null
                    && !write.targetSha256After().equals(write.originalSha256());
            final boolean restored = dirty && ShanhaiSaveEditStorage.restore(write.backupPath(), levelDat);
            return new Result(false, write.error() + (dirty ? (restored ? "+restored" : "+restore_failed") : ""),
                    List.of(), notes, write);
        }
        // 回读校验：盘上的结果必须和我想写的一模一样
        final List<String> observed;
        try {
            final CompoundTag reread = read(levelDat);
            observed = reread == null ? List.of("<unreadable>")
                    : ShanhaiPlayerDataEditor.diffTags(before, reread);
        } catch (Throwable t) {
            final boolean restored = ShanhaiSaveEditStorage.restore(write.backupPath(), levelDat);
            return new Result(false, "reread_failed:" + t + (restored ? "+restored" : "+restore_failed backup=" + write.backupPath()), List.of(), notes, write);
        }
        if (!observed.equals(intended)) {
            final boolean restored = ShanhaiSaveEditStorage.restore(write.backupPath(), levelDat);
            return new Result(false, "reread_mismatch intended=" + intended + " observed=" + observed
                    + (restored ? "+restored" : "+restore_failed"), observed, notes, write);
        }
        return new Result(true, "", observed, notes, write);
    }

    // ================================================================== 只读小工具

    /** 某个字段"为什么能活过重开"（面板提示/日志都用它）。 */
    public static String why(String id) {
        final ShanhaiLevelDataSpec.Field f = ShanhaiLevelDataSpec.field(id);
        return f == null ? "" : f.why();
    }

    /** 这份 level.dat 里 {@code fml} 那棵子树（判据"Forge 额外数据没被动过"用）。 */
    public static CompoundTag fmlOf(CompoundTag root) {
        final Tag t = root == null ? null : root.get("fml");
        return t instanceof CompoundTag c ? c : null;
    }
}
