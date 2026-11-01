package com.shanhai.common.recipe.editor;

import java.util.ArrayList;
import java.util.List;

/**
 * 山海重构 · <b>「改一条配方」在 JEI 运行时里的**顺序**（纯 Java，不碰任何 MC / JEI 类）</b>。
 *
 * <h2>1. 这个类修的是哪一条现场症状</h2>
 * 用户：<b>「JEI 里能看见改过的那条（分类页），但用产物去点（点物品查"用途/配方"）就是点不动」</b>。
 *
 * <p>取证（{@code temp\zhuguang-bug\jei与候选.md} §0 / §A-2～A-4，日志原文逐字）：
 * <pre>
 *   jei_runtime_hidden uid=gtceu:primordial_causal_weaving id=…/new_recipe_4 hidden=1 total=2   ← 原稿被藏了
 *   jei_sync APPENDED matched=0 hidden=0 added=1 … channel=category                              ← 替换份只走"分类页"出口
 *   全日志 0 条 jei_runtime_registered ⇒ 替换份【从未】进过 JEI 自己的表
 * </pre>
 *
 * <h2>2. 为什么"只 hide、不 add"必然导致点不动（字节码，不是推测）</h2>
 * <pre>
 *   点物品（有焦点）：
 *     RecipeLookup.get (includeHidden=false)
 *       → RecipeManagerInternal.getRecipesStream(type, focus, false)
 *       → PluginManager.getRecipes(RecipeTypeData, IFocusGroup, boolean)
 *           偏移 6–34  plugins.flatMap(getPluginRecipeStream(…)).distinct()
 *                      └ 焦点非空 ⇒ InternalRecipeManagerPlugin.getRecipes(cat, focus)
 *                          ⇒ RecipeMap.getRecipes(type, ingredient)   ← **物品索引**
 *           偏移 36–74 if (!includeHidden) stream = stream.filter(hidden::contains).negate()
 *       ⇒ 索引里查到的是**被我们藏掉的原稿那一份实例** ⇒ 被上面那行 filter 掉 ⇒ 0 条
 *       ⇒ 我们的出口（PluginManager.getRecipes 的 RETURN）拿到空列表
 *       ⇒ ShanhaiJeiRecipePatches.patched() 第 410 行 `recipes.isEmpty() ⇒ return recipes`
 *       ⇒ 恒 0 条 ⇒ 点击没有任何反应 ✓（与用户描述逐条对上）
 * </pre>
 *
 * <h2>3. 🔴 关键事实：JEI 15.49.0.188 <b>有</b>一条会更新物品索引的公开运行时 add</h2>
 * <pre>
 *   mezz.jei.api.recipe.IRecipeManager#addRecipes(RecipeType&lt;T&gt;, List&lt;T&gt;)        ← 公开 API
 *     → mezz.jei.library.recipes.RecipeManagerInternal#addRecipes                    （偏移 12–122）
 *         逐条 addRecipe(category, recipe, hiddenRecipes)，只把**成功的那几条**收集起来
 *         → RecipeTypeData#addRecipes(Collection) = recipes.addAll(...)（普通 ArrayList，不去重）
 *     → private boolean addRecipe(IRecipeCategory, T, Set&lt;T&gt;)                        （偏移 0–170）
 *         偏移 8–15   hiddenRecipes.contains(recipe) ⇒ 打 debug "Recipe not added because it is hidden"
 *                                                  ⇒ iconst_0 ireturn（**静默拒收**）
 *         偏移 55–62  !category.isHandled(recipe)    ⇒ 打 debug "…cannot handle it" ⇒ 拒收
 *         偏移 102–111 IngredientSupplierHelper.getIngredientSupplier(...) 为 null ⇒ 拒收
 *         偏移 120–166 for (RecipeMap m : recipeMaps.values()) m.addRecipe(type, recipe, supplier)
 *                      ↑↑ **这就是物品索引（每一个 role 各一张）**：输入键、输出键都会更新
 *     ⇒ 所以「addRecipes 会不会更新物品索引」的答案是 **会**；
 *        「能不能在运行时把一条配方加进 JEI 自己的表」的答案也是 **能**。
 * </pre>
 * 但 {@code addRecipes} 的返回类型是 {@code void} ⇒ <b>它拒收时不会告诉你</b>
 * ⇒ 调用方**必须自己复核**"{@code 这一份到底进没进}"（{@link Ops#add} 的契约就写死这一条）。
 *
 * <h2>4. 因此顺序必须是这三步（幂等）</h2>
 * <pre>
 *   改一条配方（ACTION_CHANGED）：
 *     ① 若 JEI 里已有我们之前加的那一版 ⇒ 先把它 hide 掉（**不许叠加成两份**）
 *     ② 把【重建份】addRecipes 进 JEI（走上面那条会更新物品索引的路），并复核真的进去了
 *     ③ **只有 ② 真的成功了**，才去 hide 原稿（否则藏了原稿又没加进去 ⇒ 又变回"点不动"）
 *   删一条配方（ACTION_REMOVED）：
 *     ① 把重建份移除（撤销我们自己登记的那几份）
 *     ② hide 原稿 —— 语义选择见 {@link #applyRemoved} 的注释（选"藏"而不是"放着不动"）
 * </pre>
 *
 * <h2>5. 判据（离线可跑，见 {@link ShanhaiJeiIndexCheck}）</h2>
 * 本类只做顺序与记账，给结论的是 {@link ShanhaiJeiIndexCheck}：
 * 「改一条 ⇒ hide 原稿 1 次 / 进索引 1 次 / 第二次保存不叠加 / 点物品能查到那一条」，
 * 外加一条负对照（把 ② 改回"只 hide 不 add"⇒ 断言必须红）。
 */
public final class ShanhaiJeiReplaceOrder {

    /**
     * 与 JEI 的唯一接口（客户端用 {@code ShanhaiJeiRuntime} 实现；离线用一张假表实现）。
     *
     * <p>只有三个方法，每个都正对着一条 JEI 字节码语义（见类注释 §3）：
     * <ul>
     *   <li>{@link #visible} = {@code createRecipeLookup(type).get()}（<b>不带</b> includeHidden ⇒ 只给可见的）；</li>
     *   <li>{@link #hide} = {@code IRecipeManager#hideRecipes}（<b>按实例同一性</b>，传错实例静默无效）；</li>
     *   <li>{@link #add} = {@code IRecipeManager#addRecipes} <b>＋ 复核</b>（它返回 void，会静默拒收）。</li>
     * </ul>
     */
    public interface Ops {

        /** 这个分类里、这个完整 recipe id、**当前可见**（未被 hide）的所有实例。 */
        List<Object> visible(String typeId, String recipeId);

        /** 把这几份 hide 掉（按实例同一性）。返回 true = 调用成功（不是"确实藏了 N 份"）。 */
        boolean hide(String typeId, List<Object> instances);

        /**
         * 把这一份加进 JEI 自己的表（<b>会更新物品索引</b>），并<b>复核</b>它真的在里面。
         *
         * @return true = 复核通过（它现在确实在 JEI 的表里）；false = 被拒（hidden / isHandled / 造不出
         *         ingredient supplier）或 JEI 还没就绪
         */
        boolean add(String typeId, String recipeId, Object rebuilt);
    }

    /**
     * 一次处置的结果（全部是"这一次调用真的做了什么"的读数，不是累计）。
     *
     * @param ok            true = 这一次的处置目的达成了（改 ⇒ 新那份已在 JEI 里；删 ⇒ 那一份已不可见）
     * @param added         true = 我们真的把重建份加进 JEI 自己的表了（**这一格 false 时绝不许 hide 原稿**）
     * @param prevHidden    先撤掉了我们上一次加进去的几份（0 = 之前没有，或撤不掉）
     * @param prevHideFailed 撤不掉的那几份（句柄已放回台账，下一次还能撤）
     * @param foreignHidden 藏掉了几份"不是我们的"（= JEI 自己那份原稿；0 = 本来就没有/已经藏过）
     * @param prevHash      被撤掉的那一份的<b>实例同一性哈希</b>（0 = 没有；日志里 {@code old=own_copy#…}）
     * @param newHash       这一次登记进去的那一份的<b>实例同一性哈希</b>（0 = 没加；日志里 {@code new=…}）
     * @param ignored       为什么没做成（{@code ok=false} 时才有意义）
     */
    public record Outcome(boolean ok, boolean added, int prevHidden, int prevHideFailed,
                          int foreignHidden, int prevHash, int newHash, String ignored) {

        /** 一行可 grep 的读数（调用方拼进日志）。 */
        public String describe() {
            return "ok=" + ok + " added=" + added + " prev_hidden=" + prevHidden
                    + " prev_hide_failed=" + prevHideFailed + " hidden_original=" + foreignHidden
                    + " reason=" + ignored;
        }
    }

    private static final Outcome NOTHING =
            new Outcome(false, false, 0, 0, 0, 0, 0, "no_input");

    /**
     * <b>改/新建一条配方</b>：① 撤掉我们上一版 → ② 把重建份加进 JEI（会更新物品索引）→ ③ 再 hide 原稿。
     *
     * <p>⚠️ 幂等性靠的正是这个顺序：
     * 第二次保存时 ① 会把第一版撤掉（句柄在 {@link ShanhaiJeiOwnCopies} 里，不随补丁换代丢），
     * ② 再登记新版，③ 发现原稿已经藏过（{@link #visible} 里没有它了）⇒ 什么都不做
     * ⇒ <b>界面上永远只有 1 份</b>（既有"越叠越多"那条 bug 不会因为这次改动复活）。
     *
     * <p>⚠️ <b>②失败 ⇒ ③不做</b>。这一条是硬的：原稿被藏掉、我们那份又没进索引
     * ⇒ 用户点产物时 JEI 拿到空列表 ⇒ 又回到本轮要修的那个"点不动"。
     *
     * @param ops       JEI 侧实现（客户端 = {@code ShanhaiJeiRuntime}；离线 = 假表）
     * @param typeId    分类 uid（GT = 配方类型 id）
     * @param recipeId  完整 recipe id（去重口径，同 {@code jei_sync}）
     * @param rebuilt   我们重建出来的那一份（{@code GTRecipeWrapper} / 原版 {@code Recipe}）
     */
    public static Outcome applyChanged(Ops ops, String typeId, String recipeId, Object rebuilt) {
        if (ops == null || typeId == null || recipeId == null || rebuilt == null) {
            return NOTHING;
        }
        final String ownKey = ShanhaiJeiOwnCopies.key(typeId, recipeId);

        // ① 先撤掉"我们上一次加进去的那一份"（next == rebuilt 除外）—— 这一步就是"不许叠加"。
        int prevHidden = 0;
        int prevFailed = 0;
        final List<Object> prev = ShanhaiJeiOwnCopies.takePrevious(ownKey, rebuilt);
        if (!prev.isEmpty()) {
            if (ops.hide(typeId, prev)) {
                prevHidden = prev.size();
            } else {
                // 撤不掉（JEI 还没就绪 / 传错实例静默无效）⇒ 句柄放回台账，下次还能撤；
                // **绝不静默丢句柄** —— 句柄一丢那份就永远撤不掉 ⇒ 越叠越多（2026-10-11 那条血账）。
                ShanhaiJeiOwnCopies.recordAll(ownKey, prev);
                prevFailed = prev.size();
            }
        }

        // ② 把重建份加进 JEI 自己的表（这条 API 会更新物品索引 recipeMaps）＋ 复核真的进去了。
        final int newHash = System.identityHashCode(rebuilt);
        final boolean added = ops.add(typeId, recipeId, rebuilt);
        if (!added) {
            return new Outcome(false, false, prevHidden, prevFailed, 0,
                    prev.isEmpty() ? 0 : System.identityHashCode(prev.get(0)), newHash, "add_rejected");
        }
        ShanhaiJeiOwnCopies.record(ownKey, rebuilt);

        // ③ 再 hide 原稿 —— 只 hide【不是我们的】那些（按实例同一性排除我们自己的句柄）。
        final List<Object> foreign = foreignVisible(ops, typeId, recipeId, rebuilt);
        int foreignHidden = 0;
        if (!foreign.isEmpty() && ops.hide(typeId, foreign)) {
            foreignHidden = foreign.size();
        }
        return new Outcome(true, true, prevHidden, prevFailed, foreignHidden,
                prev.isEmpty() ? 0 : System.identityHashCode(prev.get(0)), newHash,
                foreign.isEmpty() ? "no_original_visible" : "replaced");
    }

    /**
     * <b>删一条配方</b>：① 撤掉我们登记的那几份 → ② hide 原稿。
     *
     * <h4>语义选择（写清楚，别让后来人猜）</h4>
     * ② 选的是 <b>hide 原稿</b>，不是"unhide 之后放着不动"：
     * 删掉的配方本来就不该在 JEI 里显示；一旦"放着不动"，用户看到的会是**旧内容**，
     * 那是把"删了没反应"这个老 bug 换了个形态再犯一次。
     * <p>⚠️ 所以"删除之后点产物查不到任何东西"是<b>预期行为</b>（那条配方真的没了），
     * 与"改过的配方点不动"是两件事 —— 后者才是本轮要修的缺陷。
     */
    public static Outcome applyRemoved(Ops ops, String typeId, String recipeId) {
        if (ops == null || typeId == null || recipeId == null) {
            return NOTHING;
        }
        final String ownKey = ShanhaiJeiOwnCopies.key(typeId, recipeId);

        // ① 把我们自己登记进去的那几份撤掉（含"改过之后又删掉"这种组合）。
        int ownHidden = 0;
        int ownFailed = 0;
        final List<Object> mine = ShanhaiJeiOwnCopies.drain(ownKey);
        if (!mine.isEmpty()) {
            if (ops.hide(typeId, mine)) {
                ownHidden = mine.size();
            } else {
                ShanhaiJeiOwnCopies.recordAll(ownKey, mine);
                ownFailed = mine.size();
            }
        }

        // ② hide 原稿（我们的那几份已经 hidden 了 ⇒ 从"可见"里查到的就只剩 JEI 自己那份）。
        final List<Object> foreign = new ArrayList<>();
        for (Object v : safeVisible(ops, typeId, recipeId)) {
            if (!ShanhaiJeiOwnCopies.isOurs(v)) {
                foreign.add(v);
            }
        }
        int foreignHidden = 0;
        if (!foreign.isEmpty() && ops.hide(typeId, foreign)) {
            foreignHidden = foreign.size();
        }
        final boolean ok = foreignHidden > 0 || ownHidden > 0;
        return new Outcome(ok, false, ownHidden, ownFailed, foreignHidden,
                mine.isEmpty() ? 0 : System.identityHashCode(mine.get(0)), 0,
                foreignHidden > 0 ? "deleted" : (ownHidden > 0 ? "own_copy_removed" : "not_present"));
    }

    /** {@link #applyChanged} 第 ③ 步要藏的那些：可见、但不是我们加进去的。 */
    private static List<Object> foreignVisible(Ops ops, String typeId, String recipeId, Object rebuilt) {
        final List<Object> out = new ArrayList<>();
        for (Object v : safeVisible(ops, typeId, recipeId)) {
            if (v == rebuilt || ShanhaiJeiOwnCopies.isOurs(v)) {
                continue;                     // 我们自己的（这一版或上一版）一律不藏
            }
            out.add(v);
        }
        return out;
    }

    /** {@code ops.visible} 的兜底（实现抛异常/返回 null 时按"没有"处理 —— 补丁层绝不许把 JEI 弄崩）。 */
    private static List<Object> safeVisible(Ops ops, String typeId, String recipeId) {
        try {
            final List<Object> v = ops.visible(typeId, recipeId);
            return v == null ? List.of() : v;
        } catch (Throwable t) {
            return List.of();
        }
    }

    private ShanhaiJeiReplaceOrder() {}
}
