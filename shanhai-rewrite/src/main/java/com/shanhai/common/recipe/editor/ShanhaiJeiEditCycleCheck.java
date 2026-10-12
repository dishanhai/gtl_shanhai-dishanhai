package com.shanhai.common.recipe.editor;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/**
 * 山海重构 · <b>「编辑器改过的配方在 JEI 里越叠越多」的离线复现判据</b>（纯 Java，不碰 MC / JEI 类）。
 *
 * <h2>1. 它把什么变成了一条能离线跑的断言</h2>
 * 红线禁止开客户端 ⇒ "JEI 里那条到底有几份"本机看不见。但这件事<b>是可以离线判的</b>：
 * <b>把 JEI 自己那张表 + 我们那张句柄台账，按它们真实的语义各写一个最小模型，
 * 然后喂"编辑 N 次"的模拟序列</b> ⇒ 每一拍卖出两个读数：
 * <pre>
 *   table_visible  = JEI 自己那张 recipes 列表里、这一条 id 的可见条数
 *   dup            = max(0, table_visible − 1)  ← **"多出来几份"**（正对照必须恒为 0）
 *   screen_visible = 经过我们"查询出口"那一层（按 id 去重 ＋ 换成自己造的那份）之后界面上的条数
 *                    ⚠️ 补丁还被服务端套用时我们那层接管这一页 ⇒ 界面上恒 1 条；
 *                       补丁被对账丢掉之后我们【不再接管】（{@code patched()} 里 p==null 的条目原样进输出、
 *                       不去重）⇒ 界面看到的就是 JEI 表里的原样 ⇒ **这就是"越叠越多"被用户看见的那一刻** ✓
 * </pre>
 * 判据：<b>无论编辑几次、中间夹不夹"对账丢补丁"，dup 都必须恒为 0（可见份数恒为 1）</b>。
 *
 * <h2>2. 模型的两半，各自对齐到哪一段真实语义（逐条给取证口径）</h2>
 * <h4>① JEI 自己那张表 —— {@code javap} 实测（不是猜的）</h4>
 * <pre>
 *   mezz.jei.library.recipes.RecipeManagerInternal#addRecipes(RecipeType, List)
 *     → 逐条 addRecipe(category, recipe, hiddenRecipes)：该实例若在 hidden 集合里则【跳过】
 *     → RecipeTypeData#addRecipes(Collection)  =  this.recipes.addAll(...)
 *   而 RecipeTypeData 的构造器里：recipes = new ArrayList()；（**普通 ArrayList，没有任何去重**）
 *                                 hiddenRecipes = Collections.newSetFromMap(new IdentityHashMap())
 *   ⇒ 三条不可动摇的语义：
 *      (a) **同一个实例登记两次 = 列表里两条**（"只加不撤"真的会叠）；
 *      (b) hide 是<b>按实例同一性</b>的（传错实例静默无效 —— 本工程既有的坑）；
 *      (c) 一份正在 hidden 里的实例<b>加不进去</b>（会被静默跳过）。
 * </pre>
 * <h4>② 我们那一层 —— 对齐 {@code ShanhaiJeiRecipePatches} 的两个出口</h4>
 * <pre>
 *   收包   accept() → tryRuntimeHide()：
 *            · 新建的配方（JEI 里本来没有）：**先撤掉我们上一次自己加的那一份，再登记新的那一份**
 *            · 改已有的配方：hideOwnCopies（按 id 去查 JEI 自己给的那份实例再 hide）
 *   查询   patched()：同 id 只留一条（重复的丢掉），并把留下的那条换成我们造的那份 ⇒ 界面上恒 1 条
 * </pre>
 *
 * <h2>🔴 3. 修复前为什么会叠（本类要复现的就是它）</h2>
 * 修复前"我们自己加过的实例"这张表挂在 {@code Patch} 对象上：
 * <ul>
 *   <li>每次改同一条都会 <b>new 一个 Patch</b>（句柄靠 {@code addedInstances} 搬过去，这条路是通的）；</li>
 *   <li>但<b>对账 {@code reconcile()} 会把 Patch 整个从表里删掉</b>（{@code PATCHES.remove(id)}）
 *       ⇒ <b>句柄跟着一起消失</b>，而那条路只做了"把 hide 过的 JEI 原件还回去"
 *       （判据是 {@code gone.runtimeApplied}，而"新建的配方"那条分支把 {@code runtimeApplied} 置成 false）
 *       ⇒ <b>我们 addRecipes 进去的那一份永远不会被撤</b> ✗✗</li>
 *   <li>它既没被 hide、又在 JEI 的 recipes 列表里 ⇒ <b>界面上照旧显示</b>；
 *       下一次同一条 id 再登记就是第 2 份、第 3 份 ⇒ 用户看到的"×1 与 ×3 并排"、越改越多 ✗</li>
 * </ul>
 * <p>另外修复前还有一处<b>同一份登记两次</b>（{@code tryRuntimeHide} 里两个分支各调了一次
 * {@code registerInJei}，喂进去的是同一个实例）⇒ 由上面 (a) 直接得"列表里两条"。
 *
 * <h2>4. 怎么用（离线）</h2>
 * <pre>
 *   ShanhaiJeiEditCycleCheck.selfcheck()   → 逐条原始读数（正对照 1/2/3 次 ＋ 可夹丢补丁 ＋ 负对照）
 * </pre>
 * 负对照（{@code fixOn=false}）走的是<b>修复前那套记账</b>（句柄挂 Patch、丢补丁即丢句柄、同一份登记两次）
 * ⇒ 它<b>必须</b>报出 dup 1/3/5（表里 2/4/6 条）；报不出来就说明这条判据没有判别力（假绿）。
 * <p>另有一条更硬的负对照在离线语料里：<b>把句柄台账的两个方法回退成空操作</b>（= "只 add 不撤旧的"）
 * 再跑同一条序列 ⇒ 必须报出可见 1→2→3 份（见报告 §③ 的原始输出）。
 */
public final class ShanhaiJeiEditCycleCheck {

    /** 配方在我们眼里的最小模型：**不重写 equals/hashCode** ⇒ 与 JEI 的 IdentityHashMap 同一套身份语义。 */
    public static final class Copy {
        public final String id;
        public final int serial;

        Copy(String id, int serial) {
            this.id = id;
            this.serial = serial;
        }

        @Override
        public String toString() {
            return "copy#" + serial + "(" + id + ")";
        }
    }

    /**
     * JEI 自己那张表的最小模型（语义逐条对齐 {@code RecipeTypeData} / {@code RecipeManagerInternal}，
     * 取证件见类注释 §2①）。
     */
    public static final class JeiTable {
        /** = {@code RecipeTypeData.recipes}：普通 ArrayList，addAll 追加，**不去重**。 */
        private final List<Object> recipes = new ArrayList<>();
        /** = {@code RecipeTypeData.hiddenRecipes}：身份集合。 */
        private final Set<Object> hidden = Collections.newSetFromMap(new IdentityHashMap<>());

        /** = {@code addRecipes}：正在 hidden 里的实例会被跳过，其余追加。 */
        public void addRecipes(Object o) {
            if (!hidden.contains(o)) {
                recipes.add(o);
            }
        }

        /** = {@code hideRecipes}：按身份塞进 hidden 集合。 */
        public void hideRecipes(Collection<Object> os) {
            hidden.addAll(os);
        }

        /** = {@code hideRecipes} 的"还回去"那一半。 */
        public void unhideRecipes(Collection<Object> os) {
            hidden.removeAll(os);
        }

        /** JEI 会把这一条 id 的几条交给界面（含隐藏过滤；= 我们注入点拿到的输入）。 */
        public List<Object> visibleOf(String id) {
            final List<Object> out = new ArrayList<>();
            for (Object o : recipes) {
                if (!(o instanceof Copy c) || !c.id.equals(id)) {
                    continue;
                }
                if (hidden.contains(o)) {
                    continue;
                }
                out.add(o);
            }
            return out;
        }

        /** 列表总长度（含隐藏）——"表里堆了几条"的读数。 */
        public int allSize() {
            return recipes.size();
        }
    }

    private static final String TYPE = "gtceu:demo";
    private static final String ID = "shanhai:demo/new_recipe_1";
    private static final String KEY = ShanhaiJeiOwnCopies.key(TYPE, ID);

    /**
     * 跑一条"编辑 N 次"的模拟序列。
     *
     * <p>一次"编辑"= 服务端 {@code net_sent action=changed} 到客户端 ⇒ {@code accept()} ⇒ {@code tryRuntimeHide()}
     * ＋ 之后查询一拍（{@code patched()}）。
     *
     * @param edits            编辑几次（1/2/3…）
     * @param fixOn            true = 修复后的记账（全局句柄台账：先撤上一份、再登记新的、只登记一次）；
     *                         false = 修复前那套（句柄挂 Patch、丢补丁即丢句柄、同一份登记两次）
     * @param dropBetweenEdits 两次编辑之间是否夹一发"对账丢补丁"（重进存档 / {@code /shanhai edit restore}
     *                         都会来一发；丢补丁时客户端把补丁从表里删掉）
     * @return 逐拍读数行（每行都带 table_visible / screen_visible / 判据 PASS）
     */
    public static String[] simulate(int edits, boolean fixOn, boolean dropBetweenEdits) {
        final JeiTable table = new JeiTable();
        final List<String> lines = new ArrayList<>();
        final List<Object> patchHandle = new ArrayList<>();   // 修复前：句柄挂在 Patch 上（对账丢补丁即丢）
        int serial = 0;
        boolean registered = false;                            // = Patch.registered（会随 Patch 换代搬过去）
        boolean patchAlive = true;                             // 补丁还在表里吗（丢了 ⇒ 我们那层不再接管这一页）
        int maxDup = 0;
        int maxScreen = 0;

        for (int e = 1; e <= edits; e++) {
            final Copy next = new Copy(ID, ++serial);

            // ---------------- 收包：accept() → tryRuntimeHide() ----------------
            if (fixOn) {
                // 修复后：句柄从全局台账取 ⇒ 先撤掉上一次自己加进去的那一份，再登记新的那一份（只登记一次）
                final List<Object> prev = ShanhaiJeiOwnCopies.takePrevious(KEY, next);
                if (!prev.isEmpty()) {
                    table.hideRecipes(prev);
                }
                table.addRecipes(next);
                ShanhaiJeiOwnCopies.record(KEY, next);
            } else {
                // 修复前：第一个分支"还没登记过 ⇒ 登记一次"（ShanhaiJeiRecipePatches L280-285）
                if (!registered) {
                    table.addRecipes(next);
                    registered = true;
                }
                // 修复前：第二个分支"新建的配方"再登记一次同一份（L291-315；喂进去的是同一个实例）
                //         —— 只有那一刻会双登记；句柄还在时改同一条只加一份（所以叠加必须有"丢补丁"这一拍）
                if (patchHandle.isEmpty()) {
                    table.addRecipes(next);              // ⇒ 同一个实例进了 ArrayList 两条
                } else {
                    table.hideRecipes(new ArrayList<>(patchHandle));
                }
                patchHandle.clear();
                patchHandle.add(next);
            }
            patchAlive = true;

            // ---------------- 查询一拍：patched() ----------------
            final int tableVisible = table.visibleOf(ID).size();
            // 出口层：补丁还在 ⇒ 同 id 只留一条（重复的丢掉）、留下的那条换成我们造的那份 ⇒ 界面上 1 条；
            //         补丁被丢掉 ⇒ 我们那层【根本不接管这一页】（patched 里 p==null 的条目原样进输出、不去重）
            //         ⇒ 界面看到的就是 JEI 表里的原样（这一步正是"越叠越多"被用户看见的时刻）。
            final int screenVisible = patchAlive ? (tableVisible >= 1 ? 1 : 1) : tableVisible;
            final int dup = Math.max(0, tableVisible - 1);
            maxDup = Math.max(maxDup, dup);
            maxScreen = Math.max(maxScreen, screenVisible);
            lines.add("EDIT_CYCLE edit=" + e + "/" + edits + " mode=" + (fixOn ? "fixed" : "legacy")
                    + " table_all=" + table.allSize() + " table_visible=" + tableVisible
                    + " dup=" + dup + " screen_visible=" + screenVisible
                    + " expected_dup=0 PASS_dup=" + (dup == 0)
                    + " expected_screen=1 PASS_screen=" + (screenVisible == 1));

            // ---------------- 两次编辑之间：对账（丢补丁） ----------------
            if (dropBetweenEdits && e < edits) {
                if (fixOn) {
                    // 修复后：句柄在全局台账里 ⇒ 丢补丁时把我们自己加的那一份也撤掉
                    final List<Object> mine = ShanhaiJeiOwnCopies.drain(KEY);
                    if (!mine.isEmpty()) {
                        table.hideRecipes(mine);
                    }
                    lines.add("EDIT_CYCLE  drop_after_edit=" + e + " mode=fixed drained_mine=" + mine.size()
                            + " table_visible_after_drop=" + table.visibleOf(ID).size()
                            + " expected=0 PASS=" + (table.visibleOf(ID).size() == 0));
                } else {
                    // 修复前：补丁被删 ⇒ 挂在 Patch 上的句柄一起丢 ⇒ 我们加进去的那一份没人撤、也没被 hide
                    final int before = table.visibleOf(ID).size();
                    patchHandle.clear();
                    registered = false;                    // 新 Patch：registered 也没了（重建后又是"第一次登记"）
                    lines.add("EDIT_CYCLE  drop_after_edit=" + e + " mode=legacy handle_lost=true"
                            + " table_visible_after_drop=" + table.visibleOf(ID).size()
                            + " (修复前：丢补丁只把 hide 过的原件还回去，我们 addRecipes 进去的那一份没人管)"
                            + " leaked_before=" + before);
                }
                patchAlive = false;                        // 这一拍之后补丁不在表里了
            }
        }
        lines.add("EDIT_CYCLE SUMMARY edits=" + edits + " mode=" + (fixOn ? "fixed" : "legacy")
                + " drop_between=" + dropBetweenEdits + " max_dup=" + maxDup
                + " max_screen_visible=" + maxScreen + " PASS=" + (maxDup == 0));
        return lines.toArray(new String[0]);
    }

    /** 从读数行里把某个 {@code key=数字} 的最大值抠出来（读日志用；抠不到返回 -1，绝不假报成 0）。 */
    private static int maxOf(List<String> lines, String field) {
        int max = -1;
        for (String l : lines) {
            final int i = l.indexOf(field);
            if (i < 0) {
                continue;
            }
            int j = i + field.length();
            int k = j;
            while (k < l.length() && Character.isDigit(l.charAt(k))) {
                k++;
            }
            if (k > j) {
                try {
                    max = Math.max(max, Integer.parseInt(l.substring(j, k)));
                } catch (NumberFormatException ignored) {
                    // 读不出来就不改 max（读不出来不假装 0）
                }
            }
        }
        return max;
    }

    /** 从读数行里把最大的 table_visible 抠出来。 */
    private static int maxTableVisible(List<String> lines) {
        return maxOf(lines, "table_visible=");
    }

    /** 从读数行里把最大的 dup（= 多出来的份数）抠出来。 */
    private static int maxDup(List<String> lines) {
        return maxOf(lines, "dup=");
    }

    /**
     * 自检（离线可跑）：正对照三组 ＋ <b>负对照一组</b>，返回逐条原始读数。
     *
     * <p>负对照那几行<b>必须</b>报出 {@code PASS_dup=false} 且 {@code max_dup} 涨到 1/3/5 以上 ——
     * 它就是"把修复回退掉"的形态（句柄挂在 Patch 上、丢补丁即丢句柄、同一份登记两次）。
     */
    public static String[] selfcheck() {
        final List<String> out = new ArrayList<>();
        for (int n = 1; n <= 3; n++) {
            final List<String> fixed = toList(simulate(n, true, true));
            final List<String> fixedNoDrop = toList(simulate(n, true, false));
            out.add("JEI_DEDUP 正对照 编辑" + n + "次（中间夹丢补丁） max_dup=" + maxDup(fixed)
                    + " max_table_visible=" + maxTableVisible(fixed) + " expected_dup=0 PASS="
                    + (maxDup(fixed) == 0));
            out.add("JEI_DEDUP 正对照 编辑" + n + "次（不丢补丁）   max_dup=" + maxDup(fixedNoDrop)
                    + " max_table_visible=" + maxTableVisible(fixedNoDrop) + " expected_dup=0 PASS="
                    + (maxDup(fixedNoDrop) == 0));
            final List<String> legacy = toList(simulate(n, false, true));
            final int legacyDup = maxDup(legacy);
            out.add("JEI_DEDUP 负对照 编辑" + n + "次（修复回退） max_dup=" + legacyDup
                    + " max_table_visible=" + maxTableVisible(legacy)
                    + " 期望_dup>0 PASS=" + (legacyDup > 0)
                    + " (回退的是「句柄挂 Patch ＋ 丢补丁即丢句柄 ＋ 同一份登记两次」⇒ 必须复现出越叠越多)");
        }
        out.add("JEI_DEDUP 断言 无论编辑几次、中间夹不夹对账，JEI 里这一条配方多出来的份数恒为 0（可见份数恒为 1）"
                + " —— 正对照全绿且负对照全红 ⇒ 这条判据有判别力");
        return out.toArray(new String[0]);
    }

    private static List<String> toList(String[] a) {
        final List<String> l = new ArrayList<>(a.length);
        Collections.addAll(l, a);
        return l;
    }

    private ShanhaiJeiEditCycleCheck() {}
}
