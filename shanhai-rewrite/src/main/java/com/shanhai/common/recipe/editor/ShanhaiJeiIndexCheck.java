package com.shanhai.common.recipe.editor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 山海重构 · <b>「改过的那条配方能不能被【点物品】查到」的离线复现判据</b>（纯 Java，不碰 MC / JEI 类）。
 *
 * <h2>1. 它把什么变成了一条能离线跑的断言</h2>
 * 红线禁止开客户端 ⇒ "点物品到底有没有反应"本机看不见。但这件事<b>可以离线判</b>：
 * 把 <b>JEI 自己那张表 ＋ 物品索引 ＋ 我们那条顺序</b> 按真实语义各写一个最小模型，
 * 然后喂"改一条 / 删一条 / 不动它"的序列，每拍卖出两个读数：
 * <pre>
 *   page_visible     = 分类页（**无焦点**）上这一条 id 的可见条数
 *   focused_visible  = 点物品（**有焦点**）时这一条 id 的可见条数   ← ★ 本轮要的就是它 ≥1
 * </pre>
 * 判据：<b>改过之后 focused_visible 必须恒为 1</b>；未改动的配方 hide/add 调用必须恒为 0。
 *
 * <h2>2. 模型的三半，各自对齐到哪一段真实语义（逐条给取证口径，全部 {@code javap} 实测）</h2>
 * <h4>① JEI 那张表（{@code RecipeTypeData}）</h4>
 * <pre>
 *   构造器：recipes = new ArrayList()；                                  ← 普通 ArrayList，不去重
 *           hiddenRecipes = Collections.newSetFromMap(new IdentityHashMap())  ← 身份集合
 *   addRecipes(Collection) = recipes.addAll(...)
 * </pre>
 * <h4>② 物品索引（{@code RecipeMap} / {@code recipeMaps}）—— 本轮的关键</h4>
 * <pre>
 *   RecipeManagerInternal#addRecipe 偏移 120–166：
 *     for (RecipeMap m : recipeMaps.values()) m.addRecipe(type, recipe, supplier)
 *   RecipeMap#addRecipe 偏移 0–138：按 role 取这个配方在该 role 下的每个 ingredient，
 *     把 recipe(完整 id) 挂到 recipeTable[ingredientUid] 上  ⇒ **输入键、输出键都会更新**
 *   ⇒ 所以"点物品"（有焦点）走 InternalRecipeManagerPlugin#getRecipes(cat, focus) → RecipeMap#getRecipes
 *     时，**能从索引里查到我们新加的那一份**（前提：它没被 hide）。
 * </pre>
 * <h4>③ 我们的两个出口（{@code ShanhaiJeiRecipePatches}）</h4>
 * <pre>
 *   收包   accept() → tryRuntimeHide()：改/新建那条走 {@link ShanhaiJeiReplaceOrder#applyChanged}（①②③）
 *   查询   patched()：按 id 去重 / 换成我们那份 / **空列表直接原样返回**（第 410 行，本轮"点不动"的最后一环）
 * </pre>
 *
 * <h2>3. 修复前的形态（本类要复现的负对照）</h2>
 * {@code tryRuntimeHide} 的 {@code hadOriginal == true} 那条分支<b>只 hide、不 add</b>（第 324 行）：
 * 原稿进 hidden ⇒ 物品索引里查到的仍是那份被藏的原稿 ⇒ 被 {@code PluginManager} 的
 * {@code !hiddenRecipes.contains} 滤掉 ⇒ 空列表 ⇒ {@code patched()} 第 410 行提前 return
 * ⇒ <b>点物品恒 0 条</b>。
 */
public final class ShanhaiJeiIndexCheck {

    /** 配方在我们眼里的最小模型：**不重写 equals/hashCode** ⇒ 与 JEI 的 IdentityHashMap 同一套身份语义。 */
    public static final class Copy {
        public final String id;
        /** 产物（= 物品索引里的键；"点物品"点的就是它）。 */
        public final String product;
        /** 第几代（一眼看出"这是第几次保存造出来的那一份"）。 */
        public final int serial;

        Copy(String id, String product, int serial) {
            this.id = id;
            this.product = product;
            this.serial = serial;
        }

        @Override
        public String toString() {
            return "copy#" + serial + "(" + id + ")";
        }
    }

    /**
     * JEI 自己那张表 ＋ 物品索引的最小模型（语义逐条对齐 {@code RecipeTypeData} / {@code RecipeManagerInternal}
     * / {@code RecipeMap}，取证件见类注释 §2）。
     */
    public static final class JeiTable {
        /** = {@code RecipeTypeData.recipes}：普通 ArrayList，addAll 追加，**不去重**。 */
        private final List<Object> recipes = new ArrayList<>();
        /** = {@code RecipeTypeData.hiddenRecipes}：身份集合。 */
        private final Set<Object> hidden = Collections.newSetFromMap(new IdentityHashMap<>());
        /** = {@code RecipeMap.recipeTable}（按产物取配方）——**物品索引**。 */
        private final Map<String, List<Object>> outputIndex = new HashMap<>();

        int addCalls;
        int addAccepted;
        int hideCalls;
        int hideInstances;

        /**
         * = {@code RecipeManagerInternal#addRecipes} 里对每一条做的 {@code addRecipe}：
         * hidden 里的<b>拒收</b>；进来的<b>同时</b>进 recipes 列表与物品索引。
         */
        boolean addRecipe(Object r) {
            addCalls++;
            if (!(r instanceof Copy c)) {
                return false;
            }
            if (hidden.contains(r)) {
                return false;                       // 偏移 8–15：hiddenRecipes.contains ⇒ 静默拒收
            }
            recipes.add(r);                          // RecipeTypeData.addRecipes = recipes.addAll
            outputIndex.computeIfAbsent(c.product, k -> new ArrayList<>()).add(r);   // 偏移 120–166
            addAccepted++;
            return true;
        }

        /** = {@code IRecipeManager#hideRecipes}：按身份塞进 hidden 集合（不动索引）。 */
        void hide(List<Object> os) {
            hideCalls++;
            for (Object o : os) {
                if (hidden.add(o)) {
                    hideInstances++;
                }
            }
        }

        /** = {@code createRecipeLookup(type).get()}（**不带** includeHidden）⇒ 只给可见的。 */
        List<Object> visibleMatching(String recipeId) {
            final List<Object> out = new ArrayList<>();
            for (Object o : recipes) {
                if (o instanceof Copy c && c.id.equals(recipeId) && !hidden.contains(o)) {
                    out.add(o);
                }
            }
            return out;
        }

        /**
         * = <b>有焦点那条路</b>：{@code InternalRecipeManagerPlugin.getRecipes(category, focus)}
         * → {@code RecipeMap.getRecipes(type, ingredient)}（<b>物品索引</b>）
         * → 回到 {@code PluginManager} 再被 {@code !hidden} 滤一遍。
         */
        List<Object> focused(String product, String recipeId) {
            final List<Object> out = new ArrayList<>();
            final List<Object> hit = outputIndex.get(product);
            if (hit == null) {
                return out;
            }
            for (Object o : hit) {
                if (o instanceof Copy c && c.id.equals(recipeId) && !hidden.contains(o)) {
                    out.add(o);
                }
            }
            return out;
        }

        int allSize() {
            return recipes.size();
        }

        int hiddenSize() {
            return hidden.size();
        }

        /**
         * = <b>分类页那一拍"整页"交给界面的东西</b>（= {@code PluginManager.getRecipes} 无焦点那条路的输出：
         * 这个类型下**所有**可见配方，不只是这一条 id）。
         *
         * <p>⚠️ 必须用"整页"而不是"这一条 id 的几条"来建模：{@code patched()} 第 410 行的
         * <b>「空列表直接原样返回」</b>只有在<b>整页为空</b>时才会发生；一页里还有别的配方时
         * 它会正常走进追加分支（现场 `jei_sync APPENDED matched=0 … added=1` 就是这一支）。
         * 用"这一条 id 的列表"建模会把这两种情况混成一种，判据就假了。
         */
        List<Object> pageAll() {
            final List<Object> out = new ArrayList<>();
            for (Object o : recipes) {
                if (!hidden.contains(o)) {
                    out.add(o);
                }
            }
            return out;
        }
    }

    /** 客户端 {@code ShanhaiJeiRuntime} 的三个原语在这张假表上的实现（语义一致，只少掉 JEI 的线程/重入闸）。 */
    static final class ModelOps implements ShanhaiJeiReplaceOrder.Ops {
        final JeiTable t;

        ModelOps(JeiTable t) {
            this.t = t;
        }

        @Override
        public List<Object> visible(String typeId, String recipeId) {
            return t.visibleMatching(recipeId);
        }

        @Override
        public boolean hide(String typeId, List<Object> instances) {
            if (instances == null || instances.isEmpty()) {
                return false;
            }
            t.hide(instances);
            return true;
        }

        @Override
        public boolean add(String typeId, String recipeId, Object rebuilt) {
            return t.addRecipe(rebuilt);          // 会更新物品索引；拒收时返回 false（客户端那侧是"复核没进去"）
        }
    }

    // ------------------------------------------------------------------ 出口层（复刻 patched()）

    /**
     * 复刻 {@code ShanhaiJeiRecipePatches#patched} 里与本判据相关的那几支：
     * <ol>
     *   <li>{@code recipes.isEmpty() ⇒ return recipes}（源码第 410 行 —— "点不动"的最后环）；</li>
     *   <li>同 id 只留一条；</li>
     *   <li>命中的那条：**我们自己的句柄原样放行**（它就是改过的那一版），否则换成我们造的 {@code rebuilt}；</li>
     *   <li>分类页（无焦点）且一条都没命中 ⇒ 追加 {@code rebuilt}（{@code jei_sync APPENDED} 那一支）。</li>
     * </ol>
     */
    static List<Object> outlet(List<Object> jeiList, String recipeId, Object rebuilt, boolean categoryChannel) {
        if (jeiList == null || jeiList.isEmpty()) {
            return jeiList;                       // ← patched():410 「空列表直接原样返回」
        }
        final List<Object> out = new ArrayList<>();
        final Map<String, Boolean> seen = new LinkedHashMap<>();
        int matched = 0;
        for (Object r : jeiList) {
            final String id = idOf(r);
            if (!recipeId.equals(id)) {
                out.add(r);
                continue;
            }
            matched++;
            if (seen.put(id, Boolean.TRUE) != null) {
                continue;                         // 同 id 的第二条丢掉
            }
            if (ShanhaiJeiOwnCopies.isOurs(r)) {
                out.add(r);                       // 我们加进去的那一版 ⇒ 原样放行（不重复 synth）
            } else if (rebuilt != null) {
                out.add(rebuilt);                 // 原件 ⇒ 换成我们造的那一份
            }
        }
        if (categoryChannel && matched == 0 && rebuilt != null && !seen.containsKey(recipeId)) {
            out.add(rebuilt);                     // APPENDED 那一支（只对分类页做）
        }
        return out;
    }

    static String idOf(Object o) {
        return o instanceof Copy c ? c.id : null;
    }

    /** 这一份列表里 {@code id} 出现了几次（界面上"看得见几条"的唯一口径）。 */
    static int countId(List<Object> list, String id) {
        int n = 0;
        for (Object o : list) {
            if (id.equals(idOf(o))) {
                n++;
            }
        }
        return n;
    }

    /**
     * <b>负对照用的"修复前的写法"</b>：{@code tryRuntimeHide} 里 {@code hadOriginal == true} 那条分支
     * —— <b>只 hide、不 add</b>（源码第 324 行）。
     *
     * @return 藏掉了几份
     */
    static int legacyApplyChanged(JeiTable t, String recipeId) {
        final List<Object> old = t.visibleMatching(recipeId);
        if (old.isEmpty()) {
            return 0;
        }
        t.hide(old);
        return old.size();
    }

    // ------------------------------------------------------------------ 场景

    private static final String TYPE = "gtceu:primordial_causal_weaving";
    private static final String PRODUCT = "item:shanhai:zhuguang";
    private static final String ID = "shanhai:primordial_causal_weaving/new_recipe_4";

    /** 第 1 组：改一条（保存两次）—— 要 hide 原稿 1 次、进索引 = 保存次数、可见恒 1。 */
    public static String[] simulateChangedTwice() {
        final List<String> lines = new ArrayList<>();
        final JeiTable t = new JeiTable();
        final ModelOps ops = new ModelOps(t);
        // 分类页上还有别的配方（必须的：patched() 第 410 行的"空列表提前返回"只在整页为空时才发生）
        t.addRecipe(new Copy("shanhai:demo/side_1", "item:shanhai:side_1", 0));
        t.addRecipe(new Copy("shanhai:demo/side_2", "item:shanhai:side_2", 0));
        final Object original = new Copy(ID, PRODUCT, 0);
        t.addRecipe(original);                        // JEI 自己加载时登记的原稿（也在索引里）
        final int addBase = t.addCalls;               // ← 基线：setup 那几次登记不算"我们加进去的"

        // —— 第一次保存 ——
        final Object v1 = new Copy(ID, PRODUCT, 1);
        final ShanhaiJeiReplaceOrder.Outcome o1 = ShanhaiJeiReplaceOrder.applyChanged(ops, TYPE, ID, v1);
        final int page1 = countId(outlet(t.pageAll(), ID, v1, true), ID);
        final int focused1 = countId(outlet(t.focused(PRODUCT, ID), ID, v1, false), ID);
        lines.add("JEI_INDEX 改一条 save=1 " + o1.describe()
                + " | 进索引次数=" + (t.addCalls - addBase)
                + " table_visible=" + t.visibleMatching(ID).size()
                + " page_visible=" + page1 + " focused_visible=" + focused1
                + " PASS=" + (o1.foreignHidden() == 1 && t.addCalls - addBase == 1
                        && t.visibleMatching(ID).size() == 1 && page1 == 1 && focused1 == 1));

        // —— 第二次保存（同一条再改一次）——
        final Object v2 = new Copy(ID, PRODUCT, 2);
        final ShanhaiJeiReplaceOrder.Outcome o2 = ShanhaiJeiReplaceOrder.applyChanged(ops, TYPE, ID, v2);
        final int page2 = countId(outlet(t.pageAll(), ID, v2, true), ID);
        final int focused2 = countId(outlet(t.focused(PRODUCT, ID), ID, v2, false), ID);
        final boolean newest = onlyOne(t.focused(PRODUCT, ID)) && t.focused(PRODUCT, ID).get(0) == v2;
        // (a) hide 原稿：两次保存里**真正把原稿从可见变成藏的**只有第一次那 1 次
        final int hideOriginal = o1.foreignHidden() + o2.foreignHidden();
        // (b) 进索引：每次保存 1 次
        final int addCalls = t.addCalls - addBase;
        // (c) 不叠加：JEI 那张表里这一条 id 的可见份数
        final int visible = t.visibleMatching(ID).size();
        lines.add("JEI_INDEX 改一条 save=2 " + o2.describe()
                + " | hide 原稿累计=" + hideOriginal + " 进索引次数=" + addCalls
                + " own_copies=" + ShanhaiJeiOwnCopies.ownCount(ShanhaiJeiOwnCopies.key(TYPE, ID))
                + " table_visible=" + visible + " table_all=" + t.allSize()
                + " hidden_total=" + t.hiddenSize()
                + " page_visible=" + page2 + " focused_visible=" + focused2
                + " focused_is_newest=" + newest
                + " PASS=" + (hideOriginal == 1 && addCalls == 2 && visible == 1
                        && page2 == 1 && focused2 == 1 && newest));
        lines.add("JEI_INDEX 改一条 断言 (a)hide 原稿=" + hideOriginal + " 次(期望1) (b)进索引=" + addCalls
                + " 次(期望=保存次数2) (c)第二次保存后可见=" + visible + " 份(期望1)"
                + " ⇒ PASS=" + (hideOriginal == 1 && addCalls == 2 && visible == 1));
        ShanhaiJeiOwnCopies.drain(ShanhaiJeiOwnCopies.key(TYPE, ID));
        return lines.toArray(new String[0]);
    }

    /** 第 2 组：删一条 —— 我们那几份被移除、原稿被 hide ⇒ 两个通道都 0 条（预期行为）。 */
    public static String[] simulateRemoved() {
        final List<String> lines = new ArrayList<>();
        final JeiTable t = new JeiTable();
        final ModelOps ops = new ModelOps(t);
        t.addRecipe(new Copy("shanhai:demo/side_1", "item:shanhai:side_1", 0));
        final Object original = new Copy(ID, PRODUCT, 0);
        t.addRecipe(original);
        final Object v1 = new Copy(ID, PRODUCT, 1);
        ShanhaiJeiReplaceOrder.applyChanged(ops, TYPE, ID, v1);
        final int ownBefore = ShanhaiJeiOwnCopies.ownCount(ShanhaiJeiOwnCopies.key(TYPE, ID));

        final ShanhaiJeiReplaceOrder.Outcome o = ShanhaiJeiReplaceOrder.applyRemoved(ops, TYPE, ID);
        final int ownAfter = ShanhaiJeiOwnCopies.ownCount(ShanhaiJeiOwnCopies.key(TYPE, ID));
        final int page = countId(outlet(t.pageAll(), ID, null, true), ID);
        final int focused = countId(outlet(t.focused(PRODUCT, ID), ID, null, false), ID);
        final boolean oursHidden = !t.visibleMatching(ID).contains(v1);
        lines.add("JEI_INDEX 删一条 " + o.describe()
                + " | own_before=" + ownBefore + " own_after=" + ownAfter
                + " own_copy_hidden=" + oursHidden + " table_visible=" + t.visibleMatching(ID).size()
                + " page_visible=" + page + " focused_visible=" + focused
                + " PASS=" + (ownBefore == 1 && ownAfter == 0 && oursHidden
                        && t.visibleMatching(ID).isEmpty() && page == 0 && focused == 0));
        ShanhaiJeiOwnCopies.drain(ShanhaiJeiOwnCopies.key(TYPE, ID));
        return lines.toArray(new String[0]);
    }

    /** 第 3 组（回归底线）：<b>没被改动过的配方</b> ⇒ hide / add 调用必须 <b>0</b> 次。 */
    public static String[] simulateUntouched() {
        final List<String> lines = new ArrayList<>();
        final JeiTable t = new JeiTable();
        for (int i = 1; i <= 4; i++) {
            t.addRecipe(new Copy("shanhai:demo/other_" + i, "item:shanhai:other_" + i, 0));
        }
        final int addBase = t.addCalls;
        final int hideBase = t.hideCalls;
        // 一拍查询：这条 id 不在补丁表里 ⇒ patched() 里 p==null ⇒ 原样进输出，一个补丁分支都不进。
        // ⚠️ 「补丁表里没有」在模型里的表达 = 传一个**表里不存在的 patch id**（出口只对 p!=null 的 id 动手）；
        //    传 null 会走"删除"那一支，那就不是"未改动"这个场景了。
        final String untouched = "shanhai:demo/other_2";
        final List<Object> list = t.pageAll();
        final List<Object> screened = outlet(list, "shanhai:demo/no_such_patch", null, true);
        lines.add("JEI_INDEX 未改动 table_all=" + t.allSize() + " page_in=" + list.size()
                + " out=" + screened.size() + " 这一条可见=" + countId(screened, untouched)
                + " add_calls_delta=" + (t.addCalls - addBase) + " hide_calls_delta=" + (t.hideCalls - hideBase)
                + " PASS=" + (t.addCalls - addBase == 0 && t.hideCalls - hideBase == 0
                        && countId(screened, untouched) == 1)
                + " (没补丁 ⇒ accept/tryRuntimeHide 根本不会被调用；查询那层 p==null ⇒ 原样放行)");
        return lines.toArray(new String[0]);
    }

    /**
     * 第 4/5 组：<b>负对照</b> —— 把 ② 改回旧的"只 hide 不 add"（源码第 324 行那一条）。
     *
     * <p>它<b>必须</b>报出 {@code focused_visible=0}（= 点不动被复现），
     * 而分类页仍然是 1（= 用户看到的现象："能看见，但点不动"）。
     */
    public static String[] negativeControlOnlyHide() {
        final List<String> lines = new ArrayList<>();
        final JeiTable t = new JeiTable();
        t.addRecipe(new Copy("shanhai:demo/side_1", "item:shanhai:side_1", 0));
        final Object original = new Copy(ID, PRODUCT, 0);
        t.addRecipe(original);
        final Object v1 = new Copy(ID, PRODUCT, 1);
        final int hidden = legacyApplyChanged(t, ID);            // ← 旧写法：只 hide、不 add
        final int page = countId(outlet(t.pageAll(), ID, v1, true), ID);
        final int focused = countId(outlet(t.focused(PRODUCT, ID), ID, v1, false), ID);
        lines.add("JEI_INDEX 负对照 只hide不add hidden_original=" + hidden
                + " 进索引次数=" + (t.addCalls - 2)
                + " page_visible=" + page + " focused_visible=" + focused
                + " ⇒ 断言_点不动被复现 PASS=" + (focused == 0 && page == 1)
                + " (这一行绿 = 本判据有判别力：修复前就是「分类页看得见、点物品 0 条」)");
        ShanhaiJeiOwnCopies.drain(ShanhaiJeiOwnCopies.key(TYPE, ID));
        return lines.toArray(new String[0]);
    }

    /**
     * 第 5 组（回归底线）：<b>分类页条数不变</b> —— 一条被改的配方在分类页上<b>原地替换</b>，不增不减。
     */
    public static String[] simulatePageCountUnchanged() {
        final List<String> lines = new ArrayList<>();
        final JeiTable t = new JeiTable();
        final ModelOps ops = new ModelOps(t);
        for (int i = 1; i <= 5; i++) {
            t.addRecipe(new Copy("shanhai:demo/side_" + i, "item:shanhai:side_" + i, 0));
        }
        final Object original = new Copy(ID, PRODUCT, 0);
        t.addRecipe(original);
        final int before = t.pageAll().size();
        final Object v1 = new Copy(ID, PRODUCT, 1);
        ShanhaiJeiReplaceOrder.applyChanged(ops, TYPE, ID, v1);
        final List<Object> screened = outlet(t.pageAll(), ID, v1, true);
        lines.add("JEI_INDEX 分类页条数 before=" + before + " after=" + screened.size()
                + " expected=" + before + " PASS=" + (screened.size() == before)
                + " (改一条只做原地替换 ⇒ 分类页数量不变；这一条是回归底线，改了它等于改了用户已经验过的行为)");
        ShanhaiJeiOwnCopies.drain(ShanhaiJeiOwnCopies.key(TYPE, ID));
        return lines.toArray(new String[0]);
    }

    private static boolean onlyOne(List<Object> l) {
        return l.size() == 1;
    }

    /**
     * 自检（离线可跑）：5 组读数 ＋ 1 组负对照，返回逐条原始读数。
     *
     * <p>读法：<b>每行的 {@code PASS=true} 才是绿</b>；负对照那一行的 {@code PASS} 含义不同
     * （它绿 = "点不动被复现" = 本判据有判别力），行尾已写清楚。
     */
    public static String[] selfcheck() {
        final List<String> out = new ArrayList<>();
        Collections.addAll(out, simulateChangedTwice());
        Collections.addAll(out, simulateRemoved());
        Collections.addAll(out, simulateUntouched());
        Collections.addAll(out, negativeControlOnlyHide());
        Collections.addAll(out, simulatePageCountUnchanged());
        out.add("JEI_INDEX 断言 改过之后『点物品』必须恒 1 条（修复前恒 0 条）；未改动配方 hide/add 调用恒 0；"
                + "分类页条数不变 —— 正对照全绿 + 负对照复现 ⇒ 这条判据有判别力");
        return out.toArray(new String[0]);
    }

    private ShanhaiJeiIndexCheck() {}
}
