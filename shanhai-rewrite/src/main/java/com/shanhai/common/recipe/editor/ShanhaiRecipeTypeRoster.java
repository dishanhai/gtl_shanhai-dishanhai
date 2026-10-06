package com.shanhai.common.recipe.editor;

import com.shanhai.ShanhaiMod;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * <b>配方类型花名册（开机快照）</b> —— 队列 #4 的根治件。
 *
 * <h2>1. 用户报的毛病（逐字）</h2>
 * <blockquote>「如果我把一个配方种类下面的配方<b>都删完了</b>，就算我输入 {@code /shanhai edit restore}，
 * 然后在面板里点<b>重读配方表</b>，那个配方种类<b>也不会显示了</b>，必须要<b>退存档重进</b>才能再看见」</blockquote>
 *
 * <h2>2. 根因（改之前逐行核过）</h2>
 * 第一屏那个类型列表原先<b>只</b>从「当前配方表里还有配方的类型」派生
 * （{@link ShanhaiRecipeEditorWorkspace#reloadTypes()} 里那张 {@code byType} 计数表）。
 * ⇒ 一个类型被删空之后，它在当前表里是 <b>0 条</b> ⇒ 那张计数表里根本没有这个键 ⇒ 列表里自然没有它。
 * 「重读配方表」读的还是同一张已经空了的表 ⇒ 当然不管用；而「退存档重进」= 配方表从头重建
 * （覆盖层重放），类型又有了 ⇒ 所以只有那一条路能看见它。
 *
 * <h2>3. 修法（用户点单的口径）</h2>
 * <pre>
 *   类型列表 = 【开机快照里的类型】 ∪ 【当前表里的类型】   ← 并集
 * </pre>
 * 本类就是那个「开机快照」：<b>第一次</b>被问到「此刻表里有哪些类型」时把它记下来
 * （装机读数见日志 {@code type_roster_installed}），<b>之后只增不减</b>。
 * 于是：
 * <ul>
 *   <li>一个类型被删空 ⇒ 它<b>还在</b>列表里，条数那一栏如实显示 <b>0 条</b>（不是假装还有）；</li>
 *   <li>点进去列 0 条、不崩，还能在它下面「新建配方」（队列 #3 那条路）；</li>
 *   <li>新建一条之后，当前表里又有它了 ⇒ 并集自然还是它（反面对照）。</li>
 * </ul>
 *
 * <h2>4. 🔴 为什么<b>不</b>写成「列出所有已知配方类型」</h2>
 * 本包注册了 200+ 种配方类型（{@code GTRegistries.RECIPE_TYPES}），其中绝大多数在本整合包里
 * <b>一条配方都没有</b>。全列出来会长得没法看，而且点进去永远是空的 —— 那不是用户要的东西。
 * 快照里的每一个类型都<b>确实在本局开机时有过配方</b>，所以它天然是有意义的那一小撮。
 *
 * <h2>5. ⚠️ 快照的边界（如实写清，不当成"已覆盖"）</h2>
 * 「开机」在本实现里 = <b>本进程里第一次读配方表那一刻</b>（面板开机就会读，见
 * {@link ShanhaiRecipeEditorWorkspace} 的构造函数）。所以：
 * <ul>
 *   <li>与用户实测一致的情形（这一局删空 ⇒ 重读 ⇒ 还看得见）<b>在这一条上是对的</b>；</li>
 *   <li>若某个类型在<b>上一局</b>就被删空、覆盖层已经把那几条的删除落了盘，那么本局开机时它
 *       在表里本来就是 0 条 ⇒ 快照里也没有它 ⇒ 界面上看不见它。这一条<b>没有</b>被本轮改掉，
 *       详见交付报告里的「待你确认」。</li>
 * </ul>
 */
public final class ShanhaiRecipeTypeRoster {

    public static final String PREFIX = "[SHANHAI-EDIT] editor";

    /**
     * 开机快照：类型 id → {@code {中文名, 名字来源}}。
     *
     * <p>只装一次、之后只增不减（装机那一拍之后新出现的类型也记进来 —— 它们同样是"这一局真的存在过"
     * 的类型，记下来不会让列表变长，但能保证"新建出来的类型"不会再被自己挤掉）。
     */
    private static final Map<ResourceLocation, String[]> SNAPSHOT = new LinkedHashMap<>();

    private static boolean installed;
    private static int installedCount = -1;
    private static int addedAfterInstall;
    private static String installedAt = "(never)";

    private ShanhaiRecipeTypeRoster() {}

    /**
     * 把「此刻表里真有配方的那些类型」记进快照。<b>第一次调用 = 装机</b>。
     *
     * @param current 当前表里真有配方的类型（条数 &gt; 0 的那些）
     * @return 这一次新记进来的条数（装机那一次返回快照总数）
     */
    public static synchronized int observe(List<ShanhaiRecipeEditorWorkspace.TypeRow> current) {
        int fresh = 0;
        if (current != null) {
            for (ShanhaiRecipeEditorWorkspace.TypeRow row : current) {
                if (row == null || row.id() == null) {
                    continue;
                }
                if (SNAPSHOT.put(row.id(), new String[]{row.name(), row.nameSource()}) == null) {
                    fresh++;
                }
            }
        }
        if (!installed) {
            installed = true;
            installedCount = SNAPSHOT.size();
            addedAfterInstall = 0;
            installedAt = java.time.LocalTime.now().withNano(0).toString();
            ShanhaiMod.LOGGER.info("{} type_roster_installed snapshot_types={} at={} "
                            + "(开机快照 = 本进程第一次读配方表时【真的有配方】的那些类型；之后只增不减)",
                    PREFIX, installedCount, installedAt);
            return installedCount;
        }
        if (fresh > 0) {
            addedAfterInstall += fresh;
            ShanhaiMod.LOGGER.info("{} type_roster_added n={} total_snapshot={} added_after_install={}",
                    PREFIX, fresh, SNAPSHOT.size(), addedAfterInstall);
        }
        return fresh;
    }

    /**
     * <b>并集</b>：当前表里的类型 ∪ 开机快照里的类型。
     *
     * <p>当前表里有 ⇒ 用当前表那一行（条数是<b>当前真值</b>，不许拿快照里那个旧条数冒充）；
     * 当前表里没有、快照里有 ⇒ 补一行 <b>0 条</b>（用户在界面上看到的就是「(0 条)」）。
     *
     * <p>排序口径与 {@code reloadTypes()} 原来那条完全一致（条数从多到少，再按 id 字典序）
     * —— 所以「本来就有配方的类型」在原列表里的相对次序<b>不受影响</b>，
     * 只有被删空的那几个沉到末尾（它们条数就是 0）。
     */
    public static synchronized List<ShanhaiRecipeEditorWorkspace.TypeRow> union(
            List<ShanhaiRecipeEditorWorkspace.TypeRow> current) {
        final Map<ResourceLocation, ShanhaiRecipeEditorWorkspace.TypeRow> byId = new LinkedHashMap<>();
        if (current != null) {
            for (ShanhaiRecipeEditorWorkspace.TypeRow row : current) {
                if (row != null && row.id() != null) {
                    byId.put(row.id(), row);
                }
            }
        }
        int bootOnly = 0;
        for (Map.Entry<ResourceLocation, String[]> e : SNAPSHOT.entrySet()) {
            final ResourceLocation id = e.getKey();
            if (byId.containsKey(id)) {
                continue;
            }
            bootOnly++;
            byId.put(id, new ShanhaiRecipeEditorWorkspace.TypeRow(id, nameOf(id, e.getValue()), sourceOf(id, e.getValue()), 0));
        }
        final List<ShanhaiRecipeEditorWorkspace.TypeRow> out = new ArrayList<>(byId.values());
        // ⚠️ 排序键一律走 record 的访问器（`id()`/`count()`）：TypeRow 的**字段**是 private，
        //    只有它自己的嵌套类（Workspace）才读得到；本类是另一个顶层类，读字段会编译不过。
        out.sort(Comparator.comparingInt((ShanhaiRecipeEditorWorkspace.TypeRow t) -> -t.count())
                .thenComparing(t -> t.id().toString()));
        if (bootOnly > 0) {
            ShanhaiMod.LOGGER.info("{} type_roster_union current={} boot_only={} total={} "
                            + "(boot_only = 现在 0 条、但开机时有过配方的类型 ⇒ 仍然列出来)",
                    PREFIX, current == null ? 0 : current.size(), bootOnly, out.size());
        }
        return out;
    }

    /**
     * 只补进快照的那一类，名字<b>现查一次</b>（语言可能变），查不到就回落快照里存的那一份。
     *
     * <p>为什么不在建行时直接用快照里那个名字：快照是开机那一刻查的，而"活的翻译"取决于
     * 当前语言（专服=英文、单机=中文）。现查能拿到更贴当前会话的名字；
     * 现查<b>失败</b>（回落成原始 id）时才用快照那一份 —— 两份都是真值，不编。
     */
    private static String nameOf(ResourceLocation id, String[] snap) {
        try {
            final ShanhaiRecipeEditorSession.TypeName tn =
                    ShanhaiRecipeEditorSession.typeName(id.getPath());
            if (!"fallback-id".equals(tn.source()) && tn.text() != null && !tn.text().isEmpty()) {
                return tn.text();
            }
        } catch (Throwable ignored) {
            // 查不动就用快照那份
        }
        return snap == null || snap.length < 1 ? id.getPath() : snap[0];
    }

    private static String sourceOf(ResourceLocation id, String[] snap) {
        try {
            final ShanhaiRecipeEditorSession.TypeName tn =
                    ShanhaiRecipeEditorSession.typeName(id.getPath());
            if (!"fallback-id".equals(tn.source())) {
                return tn.source() + "+boot";
            }
        } catch (Throwable ignored) {
            // 同上
        }
        return "boot-snapshot";
    }

    /** 快照里几条（"并集会不会把列表撑大"的机器判据）。 */
    public static synchronized int snapshotSize() {
        return SNAPSHOT.size();
    }

    public static synchronized boolean isInstalled() {
        return installed;
    }

    public static synchronized String statsLine() {
        return "snapshot_types=" + SNAPSHOT.size() + " installed=" + installed
                + " at=" + installedAt + " installed_count=" + installedCount
                + " added_after_install=" + addedAfterInstall;
    }

    /**
     * 自检专用：把快照清空并退回"没装机"。
     *
     * <p>只给 {@link ShanhaiRecipeEditorSelfcheck} 的**负对照**用（要证明"没有快照时那个类型
     * 确实会掉出列表"）。它<b>不在任何生产路径上</b>被调用 —— 装机之后立刻调一次
     * {@link #observe} 就把真实快照恢复回去了（自检必须自己收拾干净）。
     */
    static synchronized void resetForSelfcheck() {
        SNAPSHOT.clear();
        installed = false;
        installedCount = -1;
        addedAfterInstall = 0;
        installedAt = "(reset-by-selfcheck)";
    }
}
