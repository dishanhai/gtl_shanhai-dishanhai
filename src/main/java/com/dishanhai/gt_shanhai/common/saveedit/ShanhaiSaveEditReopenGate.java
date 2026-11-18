package com.dishanhai.gt_shanhai.common.saveedit;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * 「重开世界之前必须过的闸」——🔴 <b>纯 JDK</b>（只引用 {@code java.nio.file}/{@code java.util}），
 * 所以它可以在<b>不开游戏</b>的情况下被逐条断言（判据装置：{@code temp\saveedit-probe\run-levelid.ps1}）。
 *
 * <h2>一、为什么需要这个类（2026-11-14 09:57:32 的真机现场）</h2>
 * {@code levelId} 被算成了 <b>{@code "."}</b>：
 * <pre>
 *   [SAVEEDIT] confirm_started level=. world_dir=…\saves\新的世界\.
 *   [SAVEEDIT] reopen_probe levelId=. root=…\saves\.\.  dataCfg=null
 *   [SAVEEDIT] reopen_route=vanilla_loadLevel levelId=. reason=data_cfg_missing
 *   [WorldOpenFlows] Failed to load level data or datapacks, can't proceed with server load
 * </pre>
 * 于是 {@code validateAndCreateAccess(".")} / {@code loadLevel(".")} 指向 {@code saves\.}
 * 这个<b>不存在的存档目录</b> ⇒ {@code getDataConfiguration()} 必然读不到 {@code level.dat}
 * ⇒ 掉回原版「数据包有错误」屏。🔴 这不是"数据问题"，是<b>路径问题</b>。
 *
 * <h2>二、根因（字节码事实，不是推测）</h2>
 * 1.20.1 的 {@code LevelResource.ROOT#getId()} <b>就是 {@code "."}</b>：
 * <pre>
 *   javap -p -c net.minecraft.world.level.storage.LevelResource   （mapped forge 1.20.1-47.4.16）
 *     static {}:
 *       132: new  #2   // class LevelResource
 *       135: dup
 *       136: ldc  #98  // String .
 *       138: invokespecial  // LevelResource.&lt;init&gt;(String)
 *       141: putstatic #100  // Field ROOT:LLevelResource;
 * </pre>
 * 所以 {@code MinecraftServer#getWorldPath(LevelResource.ROOT)} 的形态是
 * {@code …\saves\新的世界\.} —— <b>尾段是 {@code "."}，不是目录名</b>；再 {@code resolve} 一次
 * 就变成现场那行 {@code …\saves\.\.}。旧实现 {@code root.getFileName().toString()} 恰好取到那个
 * {@code "."}，而 {@code Path#getFileName()} <b>不会</b>替你去掉 {@code "."}/{@code ".."}。
 *
 * <h2>三、四道闸（全是纯函数，都可离线断言）</h2>
 * <ol>
 *   <li>{@link #nameOf(Path)}：取"真正的最后一段名字"。尾段是 {@code .}/{@code ..}/空 ⇒
 *       <b>沿父目录往上找</b>（这一步就是本 bug 的根治点：删掉它 ⇒ 真值表立刻红）；</li>
 *   <li>{@link #resolve(Path, Predicate)}：名字 + <b>{@code level.dat} 存在性</b>双重校验；
 *       最多试两个候选（① 归一化后的输入本身、② 把名字换成 ROOT 的父目录名），
 *       都不成立就返回"没算出来"——<b>候选一起带上</b>，供 {@code level_id_unresolved candidates=[…]}；</li>
 *   <li>{@link #skipReopen(boolean, String, boolean, Predicate)}：这次改盘<b>什么都没改成</b>
 *       （{@code no_effective_change}）⇒ <b>不许重开</b>（重开只会白停一次世界、再把玩家丢到原版错误屏）；</li>
 *   <li>{@link #isNoEffectiveChange(String)}：失败原因的字面判据（三页各自的 {@code Result.error()}
 *       会带前缀，如 {@code region_page_failed:no_effective_change（这个位置本来就是 air）}）。</li>
 * </ol>
 *
 * <h2>四、🔴 这里<b>不</b>做的事</h2>
 * 不改重开路线本身（{@code PACK_RELOAD} / {@code VANILLA_LOAD_LEVEL} 两路都保留，
 * 判断仍在 {@link com.dishanhai.gt_shanhai.client.saveedit.ShanhaiSaveEditReopenRoute#of(Object)}）；
 * 本类只回答两件事："这次的重开目标到底是哪个世界"、"这次到底该不该重开"。
 */
public final class ShanhaiSaveEditReopenGate {

    /** 存档数据文件名（{@code LevelResource.LEVEL_DATA_FILE#getId()}）。 */
    public static final String LEVEL_DAT = "level.dat";

    /** 失败原因里"没有实际变化"的字面串（{@code ShanhaiRegionEdit} / {@code ShanhaiSaveEditWindow} 都写它）。 */
    public static final String NO_EFFECTIVE_CHANGE = "no_effective_change";

    /** 名字最多沿父目录往上找几级（正常第一级就命中；这只是防病态输入死循环）。 */
    private static final int MAX_UP = 8;

    private ShanhaiSaveEditReopenGate() {}

    // ================================================================== ① 归一化：取名字

    /**
     * 取 {@code path} 的<b>最后一段名字</b>。
     *
     * <p>🔴 尾段是 {@code "."} / {@code ".."} / 空 ⇒ 换父目录再取（不是返回 {@code "."}！）。
     * 这是本 bug 的根治点：{@code …\新的世界\.} 必须得到 {@code 新的世界}。
     *
     * @return 名字；空 / 根 / 全是 {@code .}、{@code ..} ⇒ {@code null}（= 算不出来）
     */
    public static String nameOf(Path path) {
        if (path == null) {
            return null;
        }
        Path p = path;
        for (int i = 0; i < MAX_UP && p != null; i++) {
            final Path tail = p.getFileName();
            if (tail != null) {
                final String s = tail.toString();
                if (!s.isEmpty() && !s.equals(".") && !s.equals("..")) {
                    return s;
                }
            }
            p = p.getParent();
        }
        return null;
    }

    // ================================================================== ② levelId 解析 + level.dat 闸

    /** {@link #resolve(Path, Predicate)} 的读数（不可变；每一格都能进日志）。 */
    public static final class Resolution {

        /** 算出来的 levelId。<b>空串 = 没算出来</b>（🔴 调用方必须就此停手，不许拿去重开）。 */
        public final String levelId;
        /** 试过的候选（顺序 = 尝试顺序）。 */
        public final List<String> candidates;
        /** 命中的存档目录（{@code <saves>/<levelId>}）；没命中 = {@code null}。 */
        public final Path worldDir;
        /** 命中的 {@code level.dat}；没命中 = {@code null}。 */
        public final Path levelDat;
        /** 一句话结论：{@code level_dat_present} / {@code level_dat_absent} / {@code no_name} / …。 */
        public final String why;

        public Resolution(String levelId, List<String> candidates, Path worldDir, Path levelDat, String why) {
            this.levelId = levelId == null ? "" : levelId;
            this.candidates = candidates == null ? List.of() : List.copyOf(candidates);
            this.worldDir = worldDir;
            this.levelDat = levelDat;
            this.why = why == null ? "" : why;
        }

        public boolean resolved() {
            return !this.levelId.isEmpty();
        }

        /** {@code candidates=[新的世界, …]}（正好是 {@code level_id_unresolved} 那行要的形状）。 */
        public String candidatesLine() {
            return "[" + String.join(", ", this.candidates) + "]";
        }

        /** 一行读数（日志用；不碰任何 Minecraft 类型）。 */
        public String line() {
            return "level_id=" + (resolved() ? this.levelId : "<unresolved>")
                    + " candidates=" + candidatesLine()
                    + " world_dir=" + (this.worldDir == null ? "-" : this.worldDir)
                    + " level_dat=" + (this.levelDat == null ? "-" : this.levelDat)
                    + " why=" + this.why;
        }

        @Override
        public String toString() {
            return "Resolution[" + line() + "]";
        }
    }

    /** 生产用：真文件系统（{@code Files.isRegularFile}）。 */
    public static Resolution resolve(Path worldRoot) {
        return resolve(worldRoot, Files::isRegularFile);
    }

    /**
     * 把 {@code MinecraftServer#getWorldPath(LevelResource.ROOT)} 解析成<b>真实存在</b>的 {@code levelId}。
     *
     * <p>校验只有一条：{@code <saves>/<levelId>/level.dat} 必须是<b>普通文件</b>
     * （目录拼错时 {@code level.dat} 一定不在，所以这一条同时能抓"目录算错"）。
     *
     * @param worldRoot      {@code getWorldPath(ROOT)}（尾段带 {@code "."} 也可以）
     * @param levelDatPresent 判"这个 level.dat 在不在"；生产传 {@code Files::isRegularFile}，
     *                        离线判据传假文件系统（这样真值表不碰磁盘）
     */
    public static Resolution resolve(Path worldRoot, Predicate<Path> levelDatPresent) {
        final List<String> candidates = new ArrayList<>();
        if (worldRoot == null) {
            return new Resolution("", candidates, null, null, "world_root_null");
        }
        final Path dir;
        try {
            dir = worldRoot.toAbsolutePath().normalize();
        } catch (Throwable t) {
            return new Resolution("", candidates, null, null,
                    "normalize_failed:" + t.getClass().getSimpleName());
        }
        // 名字：① 给进来的那条路径自己（尾段的 "." 在这一步被跨过去）② ROOT 的父目录名（1b 的那次重试）
        final String own = nameOf(worldRoot);
        if (own != null) {
            candidates.add(own);
        }
        final String up = nameOf(worldRoot.getParent());
        if (up != null && !candidates.contains(up)) {
            candidates.add(up);
        }
        if (candidates.isEmpty()) {
            return new Resolution("", candidates, dir, null, "no_name");
        }
        // 候选 ①：存档目录就是"归一化后的输入自己"（1.20.1 的常态：<saves>/<levelId>/[.]）
        if (own != null && present(levelDatPresent, dir.resolve(LEVEL_DAT))) {
            return new Resolution(own, candidates, dir, dir.resolve(LEVEL_DAT), "level_dat_present");
        }
        // 候选 ②：把 name 换成 ROOT 的父目录名，再从 <saves> 拼回来
        //    ⚠️ 在 1.20.1 的真实形态下它与候选 ① 重合（都被去重掉了）—— 留着是为了别的路径形状，
        //       也为了"① 明明算对却读不到 level.dat 时再试一次"这条纪律。
        final Path savesDir = dir.getParent();
        if (up != null && savesDir != null) {
            final Path alt = savesDir.resolve(up);
            if (!alt.equals(dir) && present(levelDatPresent, alt.resolve(LEVEL_DAT))) {
                return new Resolution(up, candidates, alt, alt.resolve(LEVEL_DAT), "level_dat_present_alt");
            }
        }
        return new Resolution("", candidates, dir, null, "level_dat_absent");
    }

    /** 判 {@code level.dat} 在不在；诊断谓词自己抛了也只当"不在"（绝不让闸门的判断炸掉主流程）。 */
    private static boolean present(Predicate<Path> levelDatPresent, Path levelDat) {
        if (levelDatPresent == null) {
            return false;
        }
        try {
            return levelDatPresent.test(levelDat);
        } catch (Throwable t) {
            return false;
        }
    }

    // ================================================================== ③ 没改成 ⇒ 不重开

    /** 失败原因里是否含"没有实际变化"（{@code no_effective_change}；三页各自可能带前缀）。 */
    public static boolean isNoEffectiveChange(String reason) {
        return reason != null && reason.contains(NO_EFFECTIVE_CHANGE);
    }

    /**
     * ④ <b>可注入判断</b>：这次 apply 的结果该不该"停在没改的返回屏、不进 reopen"。
     *
     * <p>真机现场（2026-11-14）有一行
     * {@code region_page_failed:no_effective_change（这个位置本来就是 air）} ——
     * 明明一个字节都没改成，却照样停了世界、照样重开，最后掉到原版错误屏。
     *
     * <p>三条同时成立才停手：
     * <ol>
     *   <li>{@code ok == false}；</li>
     *   <li><b>一个字节都没写进去</b>（{@code anythingWritten == false}）——
     *       已经写进去过就必须重开，否则改了的页看不见；</li>
     *   <li>失败原因被注入的判据认成"没有实际变化"。</li>
     * </ol>
     *
     * @param anythingWritten {@code applyResult.write() != null}（Storage 只有在真写过之后才给 WriteResult）
     * @param noEffectiveChange 注入点：生产传 {@link #isNoEffectiveChange(String)}
     *                          （离线判据用 {@code r -> false} 就能证明这个注入点真的是活的）
     */
    public static boolean skipReopen(boolean ok, String error, boolean anythingWritten,
                                     Predicate<String> noEffectiveChange) {
        if (ok) {
            return false;
        }
        if (anythingWritten) {
            return false;
        }
        if (noEffectiveChange == null) {
            return false;
        }
        try {
            return noEffectiveChange.test(error);
        } catch (Throwable t) {
            return false;
        }
    }
}
