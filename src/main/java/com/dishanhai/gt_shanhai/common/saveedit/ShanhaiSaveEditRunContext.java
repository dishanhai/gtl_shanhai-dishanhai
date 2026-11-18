package com.dishanhai.gt_shanhai.common.saveedit;

import java.nio.file.Path;
import java.util.List;

/**
 * 一次"存档直改"要用的路径快照（纯 JDK：{@code java.nio.file.Path}）。
 *
 * <h2>为什么必须在停服<b>之前</b>算好</h2>
 * {@code MinecraftServer#stopServer} 的最后一步是 {@code storageSource.close()}（释放
 * {@code session.lock}）。停服之后再去问服务器"存档在哪"已经不安全了（这也是本类存在的唯一理由）：
 * 三个路径全部在面板打开/点确认的那一刻抓下来，之后只读这个对象。
 *
 * <h2>三个路径从哪来</h2>
 * <pre>
 *   worldDir        MinecraftServer#getWorldPath(LevelResource.ROOT)          → {@code <saves>/<levelId>}
 *   levelId         {@link ShanhaiSaveEditReopenGate#resolve(Path)}
 *                     （🔴 不再用 {@code worldDir.getFileName()}：1.20.1 的 ROOT 尾段就是 "."，
 *                       见 2026-11-14 的现场；现在必须过"归一化 + level.dat 存在"两道闸）
 *   playerDataFile  MinecraftServer#getWorldPath(LevelResource.PLAYER_DATA_DIR)
 *                     + "/<uuid>.dat"
 *   backupRoot      MinecraftServer#getServerDirectory() + "temp/saveedit-backup"
 * </pre>
 * 用户口径的备份目录就是 {@code temp\saveedit-backup\<时间戳>\}；游戏里这一层相对的是
 * <b>游戏根目录</b>（{@code MinecraftServer#getServerDirectory()}），离线判据里相对的是仓库根。
 */
public final class ShanhaiSaveEditRunContext {

    /** 存档目录名（= {@code Minecraft.getInstance().getLevelSource()} 里那个 id）。空串 = 没算出来。 */
    public final String levelId;
    /** 算 {@link #levelId} 时试过的候选（顺序 = 尝试顺序；只进日志，见 {@code level_id_unresolved}）。 */
    public final List<String> levelIdCandidates;
    /** 存档根目录（绝对路径，已归一化）。 */
    public final Path worldDir;
    /** 这个玩家的 {@code playerdata/<uuid>.dat}（绝对路径）。 */
    public final Path playerDataFile;
    /** 备份根目录（{@code temp/saveedit-backup}）。 */
    public final Path backupRoot;
    /** 玩家名（只用于日志与回显）。 */
    public final String playerName;
    /** 玩家 uuid（字符串形态，只用于日志与回显）。 */
    public final String playerUuid;
    /** 这个存档是否带 {@code server-resources}（带了就不能走本期这条重载路线，见 Bridge）。 */
    public final boolean hasServerResources;

    public ShanhaiSaveEditRunContext(String levelId, List<String> levelIdCandidates, Path worldDir,
                                     Path playerDataFile, Path backupRoot,
                                     String playerName, String playerUuid, boolean hasServerResources) {
        this.levelId = levelId == null ? "" : levelId;
        this.levelIdCandidates = levelIdCandidates == null ? List.of() : List.copyOf(levelIdCandidates);
        this.worldDir = worldDir;
        this.playerDataFile = playerDataFile;
        this.backupRoot = backupRoot;
        this.playerName = playerName == null ? "?" : playerName;
        this.playerUuid = playerUuid == null ? "" : playerUuid;
        this.hasServerResources = hasServerResources;
    }

    /**
     * {@code levelId} 算出来了吗？
     *
     * <p>🔴 {@code false} ⇒ <b>不许重开</b>（{@code validateAndCreateAccess("")} 会指到不存在的目录，
     * 现场那次就是这样掉回原版「数据包有错误」屏的）。
     */
    public boolean hasLevelId() {
        return !this.levelId.isEmpty();
    }

    /** {@code candidates=[…]}（与 {@link ShanhaiSaveEditReopenGate.Resolution#candidatesLine()} 同一形状）。 */
    public String levelIdCandidatesLine() {
        return "[" + String.join(", ", this.levelIdCandidates) + "]";
    }

    /** 一行读数（进日志；也是"出问题时能一眼看出路径对不对"的唯一凭据）。 */
    public String line() {
        return "level=" + (hasLevelId() ? this.levelId
                        : "<unresolved:candidates=" + levelIdCandidatesLine() + ">")
                + " world_dir=" + (this.worldDir == null ? "-" : this.worldDir)
                + " playerdata=" + (this.playerDataFile == null ? "-" : this.playerDataFile)
                + " backup_root=" + (this.backupRoot == null ? "-" : this.backupRoot)
                + " player=" + this.playerName
                + " server_resources=" + this.hasServerResources;
    }

    @Override
    public String toString() {
        return "ShanhaiSaveEditRunContext[" + line() + "]";
    }
}
