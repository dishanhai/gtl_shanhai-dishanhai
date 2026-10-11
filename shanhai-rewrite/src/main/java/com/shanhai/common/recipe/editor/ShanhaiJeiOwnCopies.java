package com.shanhai.common.recipe.editor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 山海重构 · <b>「我们自己 addRecipes 进 JEI 的那一份」的句柄台账</b>（纯 Java，不碰任何 MC / JEI 类）。
 *
 * <h2>1. 为什么必须有一张【不跟着补丁一起死】的台账</h2>
 * 用户症状（他原话）：「<b>新建之后再改同一条 ⇒ JEI 里同一条出现两份</b>」（截图为证：×1 与 ×3 并排）
 * —— 也就是"<b>改一次就多一份，越叠越多</b>"。
 *
 * <p>根因不是"我们没写 hide"，而是<b>句柄会丢</b>：修复前那张"我们自己加过的实例"表是挂在
 * {@code Patch} 对象上的（{@code ShanhaiJeiRecipePatches.Patch.addedInstances}），而
 * <b>每次改同一条都会 new 一个 Patch</b>、<b>对账（{@code reconcile}）会整个把 Patch 从表里删掉</b>
 * ⇒ 句柄跟着 Patch 一起消失 ⇒ 之后<b>再也没有人能撤掉我们上一次加进去的那一份</b> ✗
 * ⇒ 它永久留在 JEI 自己的 recipes 列表里，下一次同一条 id 再登记就是第 2 份、第 3 份 ✗✗
 *
 * <p>⚠️ 这条"只加不撤"是真的会叠，不是猜的 —— JEI 那一侧的语义由字节码定死
 * （{@code mezz.jei.library.recipes.RecipeManagerInternal#addRecipes}
 * → {@code RecipeTypeData.addRecipes} = {@code recipes.addAll(...)}，
 * 而 {@code recipes} 是<b>一个普通 {@code ArrayList}、没有任何去重</b>）：
 * <b>同一个实例登记两次 = 列表里两条</b>（取证见报告 §1）。
 *
 * <h2>2. 台账的规矩</h2>
 * <ul>
 *   <li>键 = 分类 uid ＋ 完整 recipe id（与 {@code jei_sync} 的去重口径同一个）；</li>
 *   <li>值 = <b>我们 addRecipes 进去、且此刻还指望能撤掉的那些实例</b>（按实例同一性，不按 equals）；</li>
 *   <li>{@link #takePrevious} / {@link #drain} 都是<b>取走</b>（取走后台账里就没有了）——
 *       调用方取走后<b>必须</b>真的去 hide；撤不掉时要 {@link #recordAll} 放回去（免得句柄静默丢失）；</li>
 *   <li>台账是 {@code static} 的 ⇒ <b>补丁换代、对账丢补丁、重进存档都不会丢</b>（这正是本类存在的理由）。</li>
 * </ul>
 *
 * <h2>3. 判据（可离线跑）</h2>
 * 本类只做记账，不给结论；"编辑 N 次 ⇒ JEI 里可见几份"这条断言在
 * {@link ShanhaiJeiEditCycleCheck} 里（纯模拟，正对照 ＋ 负对照）。
 */
public final class ShanhaiJeiOwnCopies {

    /** 键 ⇒ 我们自己加进去的实例（保留插入顺序，便于读日志）。 */
    private static final Map<String, List<Object>> OWN = new LinkedHashMap<>();

    /** 台账的键（分类 uid ＋ 完整 recipe id）。 */
    public static String key(String typeId, String recipeId) {
        return (typeId == null ? "?" : typeId) + '\u0000' + (recipeId == null ? "?" : recipeId);
    }

    /**
     * <b>取出"上一次我们自己加进去的那几份"</b>（{@code next} 本身除外），并从台账里删掉。
     *
     * <p>调用方拿到之后<b>先 hide 掉它们、再登记新的那一份</b> —— 这就是"改同一条不会变成两份"的那一步。
     *
     * @param key  台账键（{@link #key}）
     * @param next 这一次要登记的那一份（如果它已经在台账里 ⇒ 不算"上一次的"，不返回）
     * @return 需要先撤掉的实例（没有就返回空表）
     */
    public static List<Object> takePrevious(String key, Object next) {
        final List<Object> all = OWN.get(key);
        if (all == null || all.isEmpty()) {
            return List.of();
        }
        final List<Object> out = new ArrayList<>(all.size());
        final List<Object> keep = new ArrayList<>(all.size());
        boolean dropped = false;
        for (Object o : all) {
            if (o == next) {
                keep.add(o);
            } else {
                out.add(o);
                dropped = true;
            }
        }
        if (!dropped) {
            return List.of();
        }
        if (keep.isEmpty()) {
            OWN.remove(key);
        } else {
            OWN.put(key, keep);
        }
        return out;
    }

    /**
     * <b>把"丢补丁时要撤掉的那几份"整批取走</b>（对账丢补丁那条路用）。
     *
     * <p>为什么不能像修复前那样"Patch 一删就当没事了"：那份实例还活在 JEI 的 recipes 列表里、
     * 而且<b>没有被 hide 过</b> ⇒ 界面照旧显示它（用户看到的就是"服务端已经不套用了，JEI 里那条还在"）。
     */
    public static List<Object> drain(String key) {
        final List<Object> all = OWN.remove(key);
        return all == null ? List.of() : new ArrayList<>(all);
    }

    /** 记下"这一份是我们加进去的"（按实例同一性去重 ⇒ 同一份记两次也只算一份）。 */
    public static void record(String key, Object instance) {
        if (key == null || instance == null) {
            return;
        }
        final List<Object> all = OWN.computeIfAbsent(key, k -> new ArrayList<>(2));
        for (Object o : all) {
            if (o == instance) {
                return;
            }
        }
        all.add(instance);
    }

    /** 把一批实例放回台账（撤不掉时用：句柄绝不静默丢失）。 */
    public static void recordAll(String key, List<Object> instances) {
        if (instances == null) {
            return;
        }
        for (Object o : instances) {
            record(key, o);
        }
    }

    /** 这个键下我们还有几份"登记过、还没撤掉"的副本。 */
    public static int ownCount(String key) {
        final List<Object> all = OWN.get(key);
        return all == null ? 0 : all.size();
    }

    /** 台账里一共几个键（读数用）。 */
    public static int keyCount() {
        return OWN.size();
    }

    /**
     * 自检（纯算术，返回逐条原始读数，调用方直接打日志）。
     *
     * <p>判据 = 台账的三条不变量：① 取走之后再取是空的；② 同一实例记两次只算一份；
     * ③ {@code drain} 之后这个键归零。<b>含一条负对照</b>：故意用"不取走、直接再记一份"的旧写法，
     * 它必须报出 2 份（否则说明本自检没有判别力）。
     */
    public static String[] selfcheck() {
        final String k = key("gtceu:demo", "shanhai:demo/new_recipe_1");
        final Object a = new Object();
        final Object b = new Object();
        final List<String> lines = new ArrayList<>();

        record(k, a);
        final List<Object> prev = takePrevious(k, b);
        lines.add("JEI_OWN take_previous prev=" + prev.size() + " mine=" + (prev.size() == 1 && prev.get(0) == a)
                + " own_after=" + ownCount(k) + " PASS=" + (prev.size() == 1 && ownCount(k) == 0));

        record(k, b);
        record(k, b);
        record(k, b);
        lines.add("JEI_OWN same_instance_recorded_3x own=" + ownCount(k) + " expected=1 PASS=" + (ownCount(k) == 1));

        final List<Object> drained = drain(k);
        lines.add("JEI_OWN drain drained=" + drained.size() + " own_after=" + ownCount(k)
                + " PASS=" + (drained.size() == 1 && ownCount(k) == 0));

        // 负对照：把"先取走旧的"这一步省掉（= 修复前的写法）⇒ 同一个键下必须堆到 2 份
        final String k2 = key("gtceu:demo", "shanhai:demo/new_recipe_2");
        final Object c = new Object();
        final Object d = new Object();
        record(k2, c);
        record(k2, d);                       // 旧写法：不撤上一份、直接再记一份
        lines.add("JEI_OWN NEGATIVE_CONTROL no_take_previous own=" + ownCount(k2)
                + " expected=2 PASS=" + (ownCount(k2) == 2) + " (旧写法下句柄与实例 1:1 堆叠 ⇒ 就是"
                + "「改一次多一份」的记账形态；本行绿 = 这条判据有判别力)");
        drain(k2);
        return lines.toArray(new String[0]);
    }

    private ShanhaiJeiOwnCopies() {}
}
