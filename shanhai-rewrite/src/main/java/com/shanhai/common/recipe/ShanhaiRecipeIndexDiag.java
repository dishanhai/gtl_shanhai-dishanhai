package com.shanhai.common.recipe;

import com.google.common.collect.HashBasedTable;
import com.google.common.collect.Table;
import com.gregtechceu.gtceu.api.capability.recipe.IO;
import com.gregtechceu.gtceu.api.capability.recipe.IRecipeCapabilityHolder;
import com.gregtechceu.gtceu.api.capability.recipe.IRecipeHandler;
import com.gregtechceu.gtceu.api.capability.recipe.RecipeCapability;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import com.gregtechceu.gtceu.api.recipe.GTRecipeType;
import com.gregtechceu.gtceu.api.recipe.content.Content;
import com.gregtechceu.gtceu.api.recipe.lookup.AbstractMapIngredient;
import com.gregtechceu.gtceu.api.recipe.lookup.Branch;
import com.mojang.datafixers.util.Either;
import com.shanhai.ShanhaiMod;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * <b>GTCEu 输入索引（{@code GTRecipeLookup} 那棵 {@code Branch} 树）的只读解剖器</b> ——
 * 2026-10-04 本轮为「按输入查返回 null」定案而写。
 *
 * <h2>为什么要单独一个类</h2>
 * 上一轮的探针把"按输入查不到"定位到
 * <blockquote>
 *   「我算出来的输入键确实在树里、而且挂的是叶子，但查询仍返回 null」
 * </blockquote>
 * 这一句话里藏着 <b>三个互相独立的可能</b>（键不等 ／ 键等但取不到 ／ 取到了但谓词/分支不对），
 * 光靠"再打一个数"分不开。本类的作用是<b>把 GT 自己那段递归原样跑一遍并逐步打读数</b>，
 * 让三者当场可判：
 * <pre>
 *   p4 key …        ← 每个键：在不在树上、挂的是叶子还是子树、哈希撞了几次、叶子是谁
 *   p4 gt6 …        ← 用【从配方现推的键】调 GT 的公开 6 参版：返回谁
 *   p4 pathquery …  ← 用【从树里收割的真实键路径】调同一个方法：返回谁   ← 这就是本轮修法
 *   p4 holder …     ← 用【机器那条路的合成 holder】调 find()：返回谁
 * </pre>
 *
 * <h2>🔴 三个必须写进代码注释的 GTCEu 事实（都是从字节码/反编译核过的，不是猜的）</h2>
 * <ol>
 *   <li><b>{@code AbstractMapIngredient.equals} 是【非对称】的。</b>
 *       反编译 {@code MapFluidTagIngredient}：{@code equals} 用的是 {@code this.tag == other.tag}
 *       <b>引用相等</b>；{@code MapItemStackIngredient} 在 {@code this.ingredient == null} 而
 *       {@code other.ingredient != null} 时走 {@code other.ingredient.test(this.stack)}，
 *       反过来（both null 或 this 有 ingredient 而 other 没有）<b>直接 return false</b>。
 *       ⇒ "谁能查到谁"<b>取决于 map 用哪个方向比</b>。</li>
 *   <li><b>fastutil 的 {@code Object2ObjectOpenHashMap} 比的是 {@code 查询键.equals(存储键)}。</b>
 *       取证（{@code javap -p -c it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap}，
 *       fastutil 8.5.9 = 冒烟装置 classpath 上那一份）：
 *       <pre>
 *   private int find(K);        // K 是 get/containsKey 传进来的参数
 *     …
 *     59: aload_1                 // ← 参数（查询键）
 *     60: aload_2                 // ← 槽位里的键（存储键）
 *     61: invokevirtual Object.equals(Object)Z
 *       </pre>
 *       ⇒ 方向 = <b>查询键.equals(存储键)</b>，正好是 {@code fromHolder} 那条路需要的那一侧
 *       （holder 键的 {@code ingredient == null}，树上的键有 ingredient ⇒ 走 {@code test}）。</li>
 *   <li><b>{@code GTRecipeLookup.fromRecipe} 会对键做 interning。</b>
 *       反编译：{@code retrieveCachedIngredient(list, convertToMapIngredient(x), ingredientRoot)}
 *       —— 键先在一个 <b>static WeakHashMap</b> 里"认亲"，认到就<b>换成先前那个实例</b>再插树。
 *       ⇒ 树上的键实例是<b>被 intern 过的规范实例</b>；用现推的<b>新实例</b>去 {@code get} 时，
 *       只要该类 {@code equals} 里有 {@code ==}（tag 类就是这样），<b>实例不同就查不到</b>。</li>
 * </ol>
 *
 * <h2>本类的"修法"是什么</h2>
 * 不再从配方现推键，而是<b>先走一遍树，把这个配方所在的那条键路径（键实例本身）收割下来</b>，
 * 再用这条路径去查。见 {@link #pathOf(Branch, GTRecipe)} ／ {@link #queryByPath}。
 * 这样得到的是"树自己认的那把钥匙"，与 {@code equals} 的实现细节无关。
 *
 * <p><b>本类全部方法只读</b>（唯一的写副作用是 {@code Branch.getNodes()/getSpecialNodes()}
 * 的惰性建表 —— GT 自己的查询路径也会触发它，语义等价）。任何写索引的动作都在
 * {@link ShanhaiRecipeEditProbe} 那一侧、并且带 {@code restored=} 核验。
 */
public final class ShanhaiRecipeIndexDiag {

    /** 与探针同一个前缀 —— grep 一个词就能把所有读数捞出来。 */
    public static final String PREFIX = ShanhaiRecipeEditProbe.PREFIX;

    /** {@link #collectLeaves} 的默认节点预算（防大类型炸内存/时间）。 */
    public static final int DEFAULT_NODE_CAP = 400_000;

    /** 键路径的最大层数（防病态自引用；正常 ≤ inputs.size()+tickInputs.size()）。 */
    private static final int MAX_DEPTH = 12;

    private ShanhaiRecipeIndexDiag() {}

    // =============================================================== 键推导（GT fromRecipe 的复刻）

    /**
     * <b>{@code GTRecipeLookup.fromRecipe} 的忠实复刻</b>（逐行对齐，包括"不筛 null content"）。
     *
     * <p>反编译原文：
     * <pre>
     *   r.inputs.forEach((cap, contents) -&gt; {
     *      if (cap.isRecipeSearchFilter() &amp;&amp; !contents.isEmpty()) {
     *         List&lt;Object&gt; ingredients = new ArrayList&lt;&gt;();
     *         for (Content content : contents) ingredients.add(content.getContent());
     *         for (Object ingredient : cap.compressIngredients(ingredients))
     *            retrieveCachedIngredient(list, cap.convertToMapIngredient(ingredient), ingredientRoot);
     *      }
     *   });
     *   r.tickInputs.forEach(……同上……);
     * </pre>
     * ⚠️ <b>与 interning 的差别如实标出</b>：GT 会把键换成 {@code ingredientRoot} 里先前那个实例；
     * 本方法<b>不做 interning</b>（那个 map 是 private static，取不到）。
     * 这个差别<b>正是</b>本轮要量的东西，所以不能偷偷"补上" —— 见 {@link #queryByPath} 的对照。
     */
    public static List<List<AbstractMapIngredient>> keysFromRecipe(GTRecipe r) {
        final List<List<AbstractMapIngredient>> out = new ArrayList<>();
        appendKeys(out, r == null ? null : r.inputs);
        appendKeys(out, r == null ? null : r.tickInputs);
        return out;
    }

    /** 一个 capability 分组 → 若干"键组"（每个键组是 {@code convertToMapIngredient} 一次的结果）。 */
    private static void appendKeys(List<List<AbstractMapIngredient>> out,
                                   Map<RecipeCapability<?>, List<Content>> src) {
        if (src == null) {
            return;
        }
        for (Map.Entry<RecipeCapability<?>, List<Content>> e : src.entrySet()) {
            final RecipeCapability<?> cap = e.getKey();
            final List<Content> contents = e.getValue();
            if (cap == null || contents == null || contents.isEmpty() || !cap.isRecipeSearchFilter()) {
                continue;
            }
            final List<Object> raw = new ArrayList<>(contents.size());
            for (Content c : contents) {
                if (c != null) {
                    raw.add(c.getContent());                        // GT 不筛 null，这里也不筛
                }
            }
            if (raw.isEmpty()) {
                continue;
            }
            for (Object compressed : cap.compressIngredients(raw)) {
                final List<AbstractMapIngredient> one = cap.convertToMapIngredient(compressed);
                if (one != null && !one.isEmpty()) {
                    out.add(new ArrayList<>(one));
                }
            }
        }
    }

    // =============================================================== 从树里收割真实键路径

    /** 一条"叶子 + 通向它的键路径"。{@code keys} 的第 i 个元素 = 第 i 层的那把键（单元素列表）。 */
    public record LeafPath(GTRecipe recipe, List<List<AbstractMapIngredient>> keys) {}

    /** DFS 栈帧。 */
    private record Frame(Branch branch, List<List<AbstractMapIngredient>> path) {}

    /**
     * <b>把这个类型索引树里所有叶子连同"通向它的键路径"一次性收下来</b>（只读）。
     *
     * <p>为什么要连路径一起收：树的第 i 层住着哪把键，只有树自己知道
     * （键实例被 GT intern 过、tag 类的 {@code equals} 又是 {@code ==}）
     * ⇒ <b>"照着树给的钥匙去开门"是唯一与 equals 实现无关的查法。</b>
     *
     * @param nodeCap 节点预算；超了就停并置 {@code truncated[0] = true}
     */
    public static List<LeafPath> collectLeaves(Branch root, int nodeCap, boolean[] truncated) {
        final List<LeafPath> out = new ArrayList<>();
        if (root == null) {
            return out;
        }
        final ArrayDeque<Frame> stack = new ArrayDeque<>();
        stack.push(new Frame(root, List.of()));
        int visited = 0;
        while (!stack.isEmpty()) {
            final Frame f = stack.pop();
            if (++visited > nodeCap) {
                if (truncated != null && truncated.length > 0) {
                    truncated[0] = true;
                }
                break;
            }
            if (f.path().size() > MAX_DEPTH) {
                continue;
            }
            for (int pass = 0; pass < 2; pass++) {
                final Map<AbstractMapIngredient, Either<GTRecipe, Branch>> map =
                        pass == 0 ? f.branch().getNodes() : f.branch().getSpecialNodes();
                for (Map.Entry<AbstractMapIngredient, Either<GTRecipe, Branch>> en : map.entrySet()) {
                    final List<List<AbstractMapIngredient>> np = new ArrayList<>(f.path().size() + 1);
                    np.addAll(f.path());
                    np.add(List.of(en.getKey()));
                    final Either<GTRecipe, Branch> v = en.getValue();
                    if (v.left().isPresent()) {
                        out.add(new LeafPath(v.left().get(), np));
                    } else if (v.right().isPresent()) {
                        stack.push(new Frame(v.right().get(), np));
                    }
                }
            }
        }
        return out;
    }

    /**
     * <b>收割指定配方在树里的那条键路径</b>（按身份找，不看 equals）。
     *
     * @return {@code null} = 这棵树里<b>根本没有</b>这个配方对象（例如它只是原版表里有、
     *         GT 索引里没有；或者它是被冲突挤掉的那一条）
     */
    public static List<List<AbstractMapIngredient>> pathOf(Branch root, GTRecipe target) {
        if (root == null || target == null) {
            return null;
        }
        final ArrayDeque<Frame> stack = new ArrayDeque<>();
        stack.push(new Frame(root, List.of()));
        int visited = 0;
        while (!stack.isEmpty()) {
            final Frame f = stack.pop();
            if (++visited > DEFAULT_NODE_CAP || f.path().size() > MAX_DEPTH) {
                return null;
            }
            for (int pass = 0; pass < 2; pass++) {
                final Map<AbstractMapIngredient, Either<GTRecipe, Branch>> map =
                        pass == 0 ? f.branch().getNodes() : f.branch().getSpecialNodes();
                for (Map.Entry<AbstractMapIngredient, Either<GTRecipe, Branch>> en : map.entrySet()) {
                    final List<List<AbstractMapIngredient>> np = new ArrayList<>(f.path().size() + 1);
                    np.addAll(f.path());
                    np.add(List.of(en.getKey()));
                    final Either<GTRecipe, Branch> v = en.getValue();
                    if (v.left().isPresent()) {
                        if (v.left().get() == target) {
                            return np;
                        }
                    } else if (v.right().isPresent()) {
                        stack.push(new Frame(v.right().get(), np));
                    }
                }
            }
        }
        return null;
    }

    /**
     * <b>用树自己给的键路径去查</b> —— 本轮修法。调用方式与 GT 私有的
     * {@code recurseIngredientTreeFindRecipe(ingredients, root, canHandle)} 完全一致
     * （逐 i 起步、{@code skip = 1L << i}），只是键来自树而不是现推。
     *
     * @param keys 非 null 时直接用；为空 ⇒ 返回 {@code null}（调用方自行回落）
     */
    public static GTRecipe queryByPath(GTRecipeType type, List<List<AbstractMapIngredient>> keys,
                                       Predicate<GTRecipe> canHandle) {
        if (type == null || keys == null || keys.isEmpty()) {
            return null;
        }
        try {
            for (int i = 0; i < keys.size(); i++) {
                final GTRecipe r = type.getLookup().recurseIngredientTreeFindRecipe(
                        keys, type.getLookup().getLookup(), canHandle, i, 0, 1L << i);
                if (r != null) {
                    return r;
                }
            }
        } catch (Throwable t) {
            ShanhaiMod.LOGGER.error("{} p4 pathquery_failed type={} reason={}", PREFIX,
                    type.registryName, t);
        }
        return null;
    }

    /** {@link #queryByPath} 的身份版：问"树能不能把这个【物体本身】按输入交回来"。 */
    public static boolean reachableByIdentity(GTRecipeType type, List<List<AbstractMapIngredient>> keys,
                                             GTRecipe target) {
        return queryByPath(type, keys, r -> r == target) != null;
    }

    // ============================================ ① 原版算法的忠实复刻（本整合包【必需】的替代入口）

    /**
     * <b>把 GTCEu 原版的 {@code recurseIngredientTreeFindRecipe} 逐行抄一遍 —— 这是本轮真正的修法。</b>
     *
     * <h4>🔴 为什么非抄不可（2026-10-04 本轮定案的根因，有反编译证据）</h4>
     * 这个整合包里，{@code GTRecipeLookup.recurseIngredientTreeFindRecipe(List,Branch,Predicate,int,int,long)}
     * <b>已经被 gtlcore 的 mixin {@code @Overwrite} 掉了</b>：
     * <pre>
     *   类：{@code org.gtlcore.gtlcore.mixin.gtm.api.recipe.GTRecipeLookupMixin}
     *   来源：{@code mods\gtlcore-1.2.3.2.jar}
     *   启用：{@code gtlcore.mixin.json} 的 mixins 列表里确实登记了 {@code gtm.api.recipe.GTRecipeLookupMixin}
     *   反编译正文（节选）：
     *     @Overwrite(remap = false)
     *     public GTRecipe recurseIngredientTreeFindRecipe(ingredients, branchMap, canHandle, index, count, skip) {
     *        if (count == ingredients.size()) return null;
     *        if (!(this.gtlCore$machine instanceof PrimitiveWorkableMachine)
     *            &amp;&amp; !(this.gtlCore$machine instanceof SteamWorkableMachine)
     *            &amp;&amp; !(this.gtlCore$machine instanceof WorkableTieredMachine)
     *            &amp;&amp; !(this.gtlCore$machine instanceof ResearchStationMachine)) {
     *           if (this.gtlCore$machine instanceof IRecipeCapabilityMachine) {
     *              return IRecipeIterator.diveIngredientTreeFindRecipe(new ObjectArrayList(ingredients.get(index)), branchMap, canHandle);
     *           }
     *           // ← 两条都不成立 ⇒ 直接掉到下面的 return null（不抛异常！）
     *        } else { ……原版那段循环…… }
     *        return null;
     *     }
     * </pre>
     * 那个 {@code gtlCore$machine} 是一个 {@code @Unique} 字段，<b>只有同被 @Overwrite 的
     * {@code prepareRecipeFind(holder)} 才会给它赋值</b>（{@code find()} / {@code getRecipeIterator()}
     * 内部会先调它）。我们从外面<b>直接调 6 参版</b>时它是 {@code null}
     * ⇒ 四个 {@code instanceof} 全假、{@code null instanceof IRecipeCapabilityMachine} 也假
     * ⇒ <b>无条件 {@code return null}</b>。
     * <br>同理，<b>合成 holder 那条路也走不通</b>：{@code prepareRecipeFind} 对"不是那 4 个机器类、
     * 也不是 {@code IRecipeCapabilityMachine}"的 holder 会 {@code return List.of();}（空键表）
     * ⇒ {@code find()} 里 {@code count == size} 当场早退 ⇒ 还是 null。
     * <br>⇒ <b>上一轮"按输入查返回 null"根本不是 GTCEu 索引的问题，是本包里这个查询入口
     * 只有在"机器自己发起"时才可用。</b>
     *
     * <p>本方法就是原版那段循环的逐行等价实现（只在公开 API 上操作：
     * {@code Branch.getNodes()/getSpecialNodes()}、{@code AbstractMapIngredient.isSpecialIngredient()}、
     * {@code Either.map}），<b>不受任何 mixin 影响</b>。它读的是同一棵树、同一把键，语义与 GTCEu 原版一致。
     */
    public static GTRecipe vanillaFind(GTRecipeType type, List<List<AbstractMapIngredient>> ingredients,
                                       Predicate<GTRecipe> canHandle) {
        if (type == null || ingredients == null || ingredients.isEmpty()) {
            return null;
        }
        final Branch root = type.getLookup().getLookup();
        for (int i = 0; i < ingredients.size(); i++) {
            final GTRecipe r = vanillaFind(ingredients, root, canHandle, i, 0, 1L << i);
            if (r != null) {
                return r;
            }
        }
        return null;
    }

    /** 原版 6 参版（逐行等价；{@code branchMap.getNodes()} 的惰性建表副作用与 GT 一致）。 */
    private static GTRecipe vanillaFind(List<List<AbstractMapIngredient>> ingredients, Branch branchMap,
                                        Predicate<GTRecipe> canHandle, int index, int count, long skip) {
        if (count == ingredients.size()) {
            return null;
        }
        for (AbstractMapIngredient obj : ingredients.get(index)) {
            final Map<AbstractMapIngredient, Either<GTRecipe, Branch>> targetMap =
                    obj.isSpecialIngredient() ? branchMap.getSpecialNodes() : branchMap.getNodes();
            final Either<GTRecipe, Branch> result = targetMap.get(obj);
            if (result != null) {
                final GTRecipe r = result.map(
                        recipe -> canHandle.test(recipe) ? recipe : null,
                        branch -> vanillaDive(ingredients, branch, canHandle, index, count, skip));
                if (r != null) {
                    return r;
                }
            }
        }
        return null;
    }

    /** 原版 {@code diveIngredientTreeFindRecipe}（private，同款等价实现）。 */
    private static GTRecipe vanillaDive(List<List<AbstractMapIngredient>> ingredients, Branch map,
                                        Predicate<GTRecipe> canHandle, int currentIndex, int count, long skip) {
        for (int i = (currentIndex + 1) % ingredients.size(); i != currentIndex; i = (i + 1) % ingredients.size()) {
            if ((skip & 1L << i) == 0L) {
                final GTRecipe found = vanillaFind(ingredients, map, canHandle, i, count + 1, skip | 1L << i);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    /** {@link #vanillaFind} 的身份版。 */
    public static boolean vanillaReachableByIdentity(GTRecipeType type, List<List<AbstractMapIngredient>> keys,
                                                    GTRecipe target) {
        return vanillaFind(type, keys, r -> r == target) != null;
    }

    // ============================================ ② 运行期取证：谁改了那个方法、那个字段现在是什么

    /** 目标方法在运行期的签名（用来 {@code getMethod}）。 */
    private static final Class<?>[] FIND_SIG =
            {List.class, Branch.class, Predicate.class, int.class, int.class, long.class};

    /**
     * <b>运行期直接读证据</b>：打出三件事，让"根因是 gtlcore 的 @Overwrite"这条结论
     * <b>不靠推理、靠读数</b>：
     * <ol>
     *   <li>{@code GTRecipeLookup.class.getDeclaredField("gtlCore$machine")} 的<b>当前值</b>
     *       —— 若它是 {@code null}，@Overwrite 版就是"必然 return null"那个分支；</li>
     *   <li>{@code Class.forName("…gtlcore.mixin.gtm.api.recipe.GTRecipeLookupMixin")} 能不能加载
     *       —— mixin 类在不在这个整合包里；</li>
     *   <li>先用合成 holder 调一次 {@code find()}（这会走 gtlcore 的 {@code prepareRecipeFind}，
     *       把字段赋值成"我们的 holder"），<b>再读字段</b>并报 {@code find()} 的返回值
     *       —— 这直接证明"字段被设成了我们的 holder、但查询仍然返回 null"，即
     *       "这个入口只认机器"。</li>
     * </ol>
     * 全程只读（{@code find()} 不改索引内容）。
     */
    public static void reflectProbe(GTRecipeType type, GTRecipe sample) {
        String fieldBefore = "(unreadable)";
        String fieldAfter = "(unreadable)";
        String holderClass = "-";
        String findRet = "-";
        boolean fieldDeclared = false;
        String fieldType = "-";
        try {
            Class.forName("org.gtlcore.gtlcore.mixin.gtm.api.recipe.GTRecipeLookupMixin");
            // 这条 forName 在 Mixin 环境里【读不到不代表 mixin 没生效】（mixin 类被改名/隔离），
            // ⇒ 它不作为证据，只留作"能不能加载"这一个事实。真正的证据是下面那个字段。
            fieldDeclared = true;
        } catch (Throwable ignored) {
            fieldDeclared = false;
        }
        boolean machineField = false;
        try {
            final java.lang.reflect.Field f = com.gregtechceu.gtceu.api.recipe.lookup.GTRecipeLookup.class
                    .getDeclaredField("gtlCore$machine");
            machineField = true;
            fieldType = f.getType().getName();
        } catch (Throwable ignored) {
            machineField = false;
        }
        try {
            final Object lookup = type.getLookup();
            fieldBefore = readGtlcoreMachine(lookup);
            final IRecipeCapabilityHolder h = holderFor(sample);
            holderClass = h == null ? "(holder null)" : h.getClass().getName();
            GTRecipe got = null;
            if (h != null) {
                got = type.getLookup().find(h, r -> r != null);
            }
            findRet = got == null ? "null" : idOf(got);
            fieldAfter = readGtlcoreMachine(lookup);
        } catch (Throwable t) {
            findRet = "threw:" + t.getClass().getSimpleName();
        }
        // 🔴 判"gtlcore 的 @Overwrite 有没有生效"的【机器判据】是这一条：
        //    原版 GTCEu 的 GTRecipeLookup 里【没有】gtlCore$machine 这个字段
        //    （javap -p gtceu 的 GTRecipeLookup 只有 recipeType / lookup / ingredientRoot 三样）。
        //    能从运行期的类上读到它 ⇒ 那个加字段的 mixin 一定已经并进来了。
        ShanhaiMod.LOGGER.info("{} p4 reflect type={} gtlCore$machine_field_declared={} field_type={} "
                        + "gtlCore$machine_before={} synthetic_holder_class={} find_with_synthetic_holder={} "
                        + "gtlCore$machine_after={} mixin_class_loadable={}（此值不作证据：mixin 类在本环境常不可 forName）",
                PREFIX, type == null ? null : type.registryName, machineField, fieldType,
                fieldBefore, holderClass, findRet, fieldAfter, fieldDeclared);
    }

    /** 读 {@code GTRecipeLookup} 里那个 gtlcore 注入的 {@code @Unique} 字段（读不到就如实报）。 */
    private static String readGtlcoreMachine(Object lookup) {
        try {
            final java.lang.reflect.Field f =
                    com.gregtechceu.gtceu.api.recipe.lookup.GTRecipeLookup.class.getDeclaredField("gtlCore$machine");
            f.setAccessible(true);
            final Object v = f.get(lookup);
            return v == null ? "null" : v.getClass().getName();
        } catch (Throwable t) {
            return "(no_field:" + t.getClass().getSimpleName() + ")";
        }
    }

    /**
     * 用<b>反射</b>调 gtlcore 自己的替代实现
     * {@code org.gtlcore.gtlcore.api.recipe.IRecipeIterator#diveIngredientTreeFindRecipe(List, Branch, Predicate)}
     * —— 那是"这个包里 {@code IRecipeCapabilityMachine} 型机器实际走的"那条查询。
     *
     * <p>用反射而不是直接引用：本工程编译期不依赖 gtlcore（少一个硬依赖），
     * 且这条读数是"诊断"性质，拿不到就报 {@code null}，不能影响判据。
     */
    public static GTRecipe gtlcoreDive(Branch root, List<AbstractMapIngredient> flat,
                                       Predicate<GTRecipe> canHandle) {
        try {
            final Class<?> c = Class.forName("org.gtlcore.gtlcore.api.recipe.IRecipeIterator");
            final java.lang.reflect.Method m = c.getMethod("diveIngredientTreeFindRecipe",
                    List.class, Branch.class, Predicate.class);
            return (GTRecipe) m.invoke(null, flat, root, canHandle);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 把一条键路径"拍平"成一个键表（gtlcore 那条 {@code dive} 收的是拍平的键表）。 */
    public static List<AbstractMapIngredient> flatten(List<List<AbstractMapIngredient>> keys) {
        final List<AbstractMapIngredient> out = new ArrayList<>();
        if (keys != null) {
            for (List<AbstractMapIngredient> g : keys) {
                out.addAll(g);
            }
        }
        return out;
    }

    // =============================================================== 合成 holder（机器那条路）

    /**
     * 用一条配方自己的输入造一个合成 {@link IRecipeCapabilityHolder}（= 机器那条路的钥匙）。
     *
     * <p>与探针里那份同源，放在这里是为了让"诊断"这一段自洽可单跑。
     * 🔴 必须覆写 {@code getSize()}：{@code IRecipeHandler} 的默认值是 {@code -1}，
     * 而 {@code prepareRecipeFind} 会因为 {@code totalSize == 0} 直接返回 null ⇒ {@code find} 恒 null。
     */
    public static IRecipeCapabilityHolder holderFor(GTRecipe r) {
        try {
            final Table<IO, RecipeCapability<?>, List<IRecipeHandler<?>>> table = HashBasedTable.create();
            int keys = fill(table, r.inputs);
            keys += fill(table, r.tickInputs);
            if (keys == 0) {
                return null;
            }
            return new IRecipeCapabilityHolder() {
                @Override
                public Table<IO, RecipeCapability<?>, List<IRecipeHandler<?>>> getCapabilitiesProxy() {
                    return table;
                }
            };
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static int fill(Table<IO, RecipeCapability<?>, List<IRecipeHandler<?>>> table,
                            Map<RecipeCapability<?>, List<Content>> src) {
        if (src == null) {
            return 0;
        }
        int keys = 0;
        for (Map.Entry<RecipeCapability<?>, List<Content>> e : src.entrySet()) {
            final RecipeCapability<?> cap = e.getKey();
            final List<Content> contents = e.getValue();
            if (cap == null || contents == null || contents.isEmpty() || !cap.isRecipeSearchFilter()) {
                continue;
            }
            final List<Object> raw = new ArrayList<>(contents.size());
            for (Content c : contents) {
                if (c != null && c.getContent() != null) {
                    raw.add(c.getContent());
                }
            }
            if (raw.isEmpty()) {
                continue;
            }
            final List<IRecipeHandler<?>> handlers = new ArrayList<>(1);
            handlers.add(new ConstHandler(cap, raw));
            table.put(IO.IN, cap, handlers);
            keys += raw.size();
        }
        return keys;
    }

    /** 内容恒定的处理器（只为了让 {@code fromHolder} 能算出键；不参与任何真实匹配）。 */
    private static final class ConstHandler implements IRecipeHandler<Object> {

        private final RecipeCapability<?> capability;
        private final List<Object> contents;

        ConstHandler(RecipeCapability<?> capability, List<Object> contents) {
            this.capability = capability;
            this.contents = contents;
        }

        @Override
        public List<Object> handleRecipeInner(IO io, GTRecipe recipe, List<Object> left,
                                              String slotName, boolean simulate) {
            return left;
        }

        @Override
        public List<Object> getContents() {
            return contents;
        }

        @Override
        public double getTotalContentAmount() {
            return 0.0D;
        }

        @Override
        public int getSize() {
            return contents.size();
        }

        @Override
        public boolean isProxy() {
            return false;
        }

        @Override
        @SuppressWarnings("unchecked")
        public RecipeCapability<Object> getCapability() {
            return (RecipeCapability<Object>) capability;
        }
    }

    // =============================================================== P4-A：逐条解剖

    private static String hex(Object o) {
        return o == null ? "null" : Integer.toHexString(System.identityHashCode(o));
    }

    private static String idOf(GTRecipe r) {
        if (r == null) {
            return "(null_recipe)";
        }
        return r.id == null ? "(null_id)" : r.id.toString();
    }

    /** 原版配方表（{@code RecipeManager.getRecipes()} 拉平）里所有带 id 的 GTRecipe。 */
    public static Map<String, GTRecipe> tableOf(MinecraftServer server) {
        final Map<String, GTRecipe> table = new HashMap<>();
        try {
            for (Recipe<?> r : server.getRecipeManager().getRecipes()) {
                if (r instanceof GTRecipe gr && gr.recipeType != null && gr.id != null) {
                    table.put(gr.id.toString(), gr);
                }
            }
        } catch (Throwable t) {
            ShanhaiMod.LOGGER.error("{} p4 table_scan_failed reason={}", PREFIX, t);
        }
        return table;
    }

    /**
     * <b>P4-A：挑几个小类型，把每条配方"按输入查"的每一跳都打出来。</b>
     *
     * <p>一个配方打 4 行，逐层回答：
     * <pre>
     *   p4 type …            这个类型的树规模（根表两张各几个键、叶子上有多少条）
     *   p4 key …             每个【现推导出的键】：哈希、在不在树上、挂的是叶子还是子树、
     *                        哈希撞了几次但 equals 为假（= interning/引用相等这条病）、叶子是谁
     *   p4 query …           同一把键、四条查询路径各交回谁：gt6 / pathquery / holder_any / holder_self
     * </pre>
     * 只读：不重建索引、不换对象。
     */
    public static void diagnose(MinecraftServer server) {
        if (server == null) {
            return;
        }
        try {
            final Map<String, GTRecipe> table = tableOf(server);
            final Map<GTRecipeType, List<GTRecipe>> byType = new IdentityHashMap<>();
            for (GTRecipe gr : table.values()) {
                byType.computeIfAbsent(gr.recipeType, k -> new ArrayList<>()).add(gr);
            }
            // 选类型：优先上一轮踩过的那一个（口径可比），否则挑"字典序最小的中小类型"
            GTRecipeType chosen = null;
            for (GTRecipeType t : byType.keySet()) {
                if ("gtceu:advanced_hyper_reactor".equals(String.valueOf(t.registryName))) {
                    chosen = t;
                    break;
                }
            }
            if (chosen == null) {
                final List<GTRecipeType> sorted = new ArrayList<>(byType.keySet());
                sorted.sort(Comparator.comparing(t -> String.valueOf(t.registryName)));
                for (GTRecipeType t : sorted) {
                    final int n = byType.get(t).size();
                    if (n >= 1 && n <= 6) {
                        chosen = t;
                        break;
                    }
                }
            }
            if (chosen == null) {
                ShanhaiMod.LOGGER.warn("{} p4 skipped reason=no_small_type table={}", PREFIX, table.size());
                return;
            }

            final Branch root = chosen.getLookup().getLookup();
            final List<GTRecipe> cands = new ArrayList<>(byType.get(chosen));
            cands.sort(Comparator.comparing(x -> x.id.toString()));
            final boolean[] trunc = {false};
            final List<LeafPath> leaves = collectLeaves(root, 20_000, trunc);

            ShanhaiMod.LOGGER.info("{} p4 type={} table_recipes={} root_nodes={} special_nodes={} "
                            + "tree_leaves={} truncated={}",
                    PREFIX, chosen.registryName, cands.size(), root.getNodes().size(),
                    root.getSpecialNodes().size(), leaves.size(), trunc[0]);

            int shown = 0;
            for (GTRecipe c : cands) {
                if (shown >= 4) {
                    break;
                }
                shown++;
                diagnoseOne(chosen, root, c);
            }
            // 最后做运行期取证（它会调一次 find()，把 gtlcore 那个字段赋值；放在读完之后，免得影响上面的读数口径）
            reflectProbe(chosen, cands.isEmpty() ? null : cands.get(0));
        } catch (Throwable t) {
            ShanhaiMod.LOGGER.error("{} p4 FAIL reason={}", PREFIX, t);
        }
    }

    private static void diagnoseOne(GTRecipeType type, Branch root, GTRecipe c) {
        final List<List<AbstractMapIngredient>> keys = keysFromRecipe(c);
        ShanhaiMod.LOGGER.info("{} p4 recipe id={} duration={} obj={} key_sets={} key_total={}",
                PREFIX, idOf(c), c.duration, hex(c), keys.size(),
                keys.stream().mapToInt(List::size).sum());

        for (int gi = 0; gi < keys.size(); gi++) {
            final List<AbstractMapIngredient> group = keys.get(gi);
            for (int ki = 0; ki < group.size(); ki++) {
                final AbstractMapIngredient k = group.get(ki);
                final boolean special = k.isSpecialIngredient();
                final Map<AbstractMapIngredient, Either<GTRecipe, Branch>> map =
                        special ? root.getSpecialNodes() : root.getNodes();
                final Either<GTRecipe, Branch> hit = map.get(k);
                // 哈希相同但 equals 为假的存储键有几个 —— interning／引用相等那条病的直接读数
                int sameHashDifferent = 0;
                boolean sameHashIsSameInstance = false;
                String sampleOther = "-";
                final int h = k.hashCode();
                for (Map.Entry<AbstractMapIngredient, Either<GTRecipe, Branch>> en : map.entrySet()) {
                    if (en.getKey().hashCode() != h) {
                        continue;
                    }
                    if (en.getKey().equals(k)) {
                        sameHashIsSameInstance = en.getKey() == k;
                    } else {
                        sameHashDifferent++;
                        if ("-".equals(sampleOther)) {
                            sampleOther = en.getKey().getClass().getSimpleName() + ":" + en.getKey();
                        }
                    }
                }
                final String entryKind = hit == null ? "none" : (hit.left().isPresent() ? "leaf" : "branch");
                final String leafId = hit != null && hit.left().isPresent() ? idOf(hit.left().get()) : "-";
                final boolean leafIsSelf = hit != null && hit.left().isPresent() && hit.left().get() == c;
                ShanhaiMod.LOGGER.info("{} p4 key recipe={} group={} idx={} cls={} special={} hash={} "
                                + "entry={} leaf_id={} leaf_is_self={} same_hash_same_instance={} "
                                + "same_hash_different={} sample_other=[{}]",
                        PREFIX, idOf(c), gi, ki, k.getClass().getSimpleName(), special, h,
                        entryKind, leafId, leafIsSelf, sameHashIsSameInstance, sameHashDifferent,
                        sampleOther);
            }
        }

        // 四条查询路径，一把键、同一棵树
        final GTRecipe viaGt6 = queryByPath(type, keys, r -> r != null);          // GT 6 参版 + 现推键
        final List<List<AbstractMapIngredient>> path = pathOf(root, c);
        final GTRecipe viaPathAny = queryByPath(type, path, r -> r != null);
        final boolean viaPathSelf = path != null && reachableByIdentity(type, path, c);
        // 🔴 本轮修法：原版算法的本地复刻（不受 gtlcore @Overwrite 影响）
        final GTRecipe viaVanilla = vanillaFind(type, keys, r -> r != null);
        final boolean viaVanillaSelf = vanillaFind(type, keys, r -> r == c) != null;
        final GTRecipe viaGtlcoreDive = path == null ? null
                : gtlcoreDive(root, flatten(path), r -> r != null);

        final IRecipeCapabilityHolder holder = holderFor(c);
        final GTRecipe viaHolderAny = holder == null ? null
                : type.getLookup().find(holder, r -> true);
        final GTRecipe viaHolderSelf = holder == null ? null
                : type.getLookup().find(holder, r -> r == c);
        final int holderKeys = holderKeys(holder);

        ShanhaiMod.LOGGER.info("{} p4 query recipe={} derived_gt6={} derived_gt6_id={} "
                        + "path_len={} path_query_any={} path_query_self={} "
                        + "vanilla_any={} vanilla_self={} gtlcore_dive_any={} "
                        + "holder_keys={} holder_any={} holder_any_id={} holder_self={}",
                PREFIX, idOf(c), hex(viaGt6), idOf(viaGt6),
                path == null ? -1 : path.size(), hex(viaPathAny), viaPathSelf,
                hex(viaVanilla), viaVanillaSelf, hex(viaGtlcoreDive),
                holderKeys, hex(viaHolderAny), idOf(viaHolderAny), hex(viaHolderSelf));
    }

    /** 合成 holder 里"可作索引键"的条数（0 或 -1 ⇒ 那条路必然查不到）。 */
    public static int holderKeys(IRecipeCapabilityHolder holder) {
        if (holder == null) {
            return -1;
        }
        try {
            int n = 0;
            for (List<IRecipeHandler<?>> hs : holder.getCapabilitiesProxy().row(IO.IN).values()) {
                for (IRecipeHandler<?> h : hs) {
                    n += Math.max(0, h.getSize());
                }
            }
            return n;
        } catch (Throwable ignored) {
            return -1;
        }
    }

    // =============================================================== P4-B：全量普查

    /**
     * <b>P4-B：对全包每一个类型做一次"自键可达性"普查</b>（只读，不动索引）。
     *
     * <p>判据（对树里每一条叶子）：把<b>通向它自己的那条键路径</b>交给 GT 自己的
     * {@code recurseIngredientTreeFindRecipe}，只问"能不能把<b>这个物体本身</b>交回来"：
     * <pre>
     *   self_reach = 能 ⇒ 这条配方按输入查得到
     *   self_miss  = 不能 ⇒ 按输入查不到它（"有损"的机器证据）
     *   null_id    = 叶子上的配方对象 id 为 null（老山海那种"不设 id 直接塞索引"的痕迹）
     * </pre>
     * 这三个数一起看：{@code null_id_leaves > 0} 说明树里有"没有身份的配方"，
     * 这正是"按输入查得回来、但按 id 反查不到"（或反之）的根源。
     */
    public static void survey(MinecraftServer server) {
        if (server == null) {
            return;
        }
        try {
            final Map<String, GTRecipe> table = tableOf(server);
            final Map<GTRecipeType, List<GTRecipe>> byType = new IdentityHashMap<>();
            for (GTRecipe gr : table.values()) {
                byType.computeIfAbsent(gr.recipeType, k -> new ArrayList<>()).add(gr);
            }
            final Set<GTRecipeType> types = new IdentityHashMap<>(byType).keySet();
            long leaves = 0;
            long nullId = 0;
            long selfReach = 0;
            long selfMiss = 0;
            long notInTable = 0;
            long tableRecipes = 0;
            long tableMissingFromTree = 0;
            long tableMissingWithTick = 0;
            long tableWithTickSearchable = 0;
            int truncatedTypes = 0;
            final List<String> worst = new ArrayList<>();
            for (GTRecipeType t : types) {
                final boolean[] trunc = {false};
                final List<LeafPath> ls = collectLeaves(t.getLookup().getLookup(), 60_000, trunc);
                if (trunc[0]) {
                    truncatedTypes++;
                }
                final Set<GTRecipe> inTree = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
                long miss = 0;
                for (LeafPath lp : ls) {
                    leaves++;
                    if (lp.recipe().id == null) {
                        nullId++;
                    }
                    if (!table.containsKey(String.valueOf(lp.recipe().id))) {
                        notInTable++;
                    }
                    inTree.add(lp.recipe());
                    // 🔴 判据走【原版算法的本地复刻】：被 gtlcore @Overwrite 的那个入口在这个包里恒 null，
                    //    用它当判据只会把"入口坏了"误报成"索引有损"。
                    if (vanillaReachableByIdentity(t, lp.keys(), lp.recipe())) {
                        selfReach++;
                    } else {
                        selfMiss++;
                        miss++;
                    }
                }
                // 🔴 反向：配方表里有、但树里根本没有的（= "不在索引里"那一类损失）
                for (GTRecipe gr : byType.get(t)) {
                    tableRecipes++;
                    final boolean hasTick = hasSearchableTickInput(gr);
                    if (hasTick) {
                        tableWithTickSearchable++;
                    }
                    if (!inTree.contains(gr)) {
                        tableMissingFromTree++;
                        if (hasTick) {
                            tableMissingWithTick++;
                        }
                    }
                }
                worst.add(t.registryName + "=" + ls.size() + "条/miss=" + miss);
            }
            worst.sort((a, b) -> Long.compare(missOf(b), missOf(a)));
            ShanhaiMod.LOGGER.info("{} p4_survey types={} leaves={} null_id_leaves={} "
                            + "self_reach={} self_miss={} tree_not_in_table={} truncated_types={} "
                            + "口径=树里每条叶子各查一次(原版算法复刻/身份判据)",
                    PREFIX, types.size(), leaves, nullId, selfReach, selfMiss, notInTable, truncatedTypes);
            // 🔴 第二条：**索引对配方表的覆盖率**（"有损"的另一种形态：不在树里）
            ShanhaiMod.LOGGER.info("{} p4_coverage table_recipes={} table_missing_from_tree={} "
                            + "table_missing_with_searchable_tickInputs={} table_with_searchable_tickInputs={} "
                            + "口径=按类型分组的配方表 ∩ 该类型索引树的叶子(身份判据)",
                    PREFIX, tableRecipes, tableMissingFromTree, tableMissingWithTick, tableWithTickSearchable);
            final int show = Math.min(6, worst.size());
            for (int i = 0; i < show; i++) {
                ShanhaiMod.LOGGER.info("{} p4_survey_top rank={} {}", PREFIX, i + 1, worst.get(i));
            }
        } catch (Throwable t) {
            ShanhaiMod.LOGGER.error("{} p4_survey FAIL reason={}", PREFIX, t);
        }
    }

    /**
     * 这条配方的 <b>{@code tickInputs}</b> 里有没有"能进索引的输入"
     * （{@code isRecipeSearchFilter()} 为真且非空）。
     *
     * <p>为什么要单列它：gtlcore 那个 {@code GTRecipeLookupMixin} 把 {@code fromRecipe}
     * 也 {@code @Overwrite} 了，而它那段<b>只遍历 {@code r.inputs}，没有 {@code tickInputs} 那一段</b>
     * （原版有）。⇒ <b>只靠 tickInputs 才可搜到的配方，在这个包里压根不会被插进索引树。</b>
     * 本方法就是去量"有多少条配方属于这种情况"。
     */
    public static boolean hasSearchableTickInput(GTRecipe r) {
        if (r == null || r.tickInputs == null) {
            return false;
        }
        for (Map.Entry<RecipeCapability<?>, List<Content>> e : r.tickInputs.entrySet()) {
            final RecipeCapability<?> cap = e.getKey();
            final List<Content> contents = e.getValue();
            if (cap == null || contents == null || contents.isEmpty() || !cap.isRecipeSearchFilter()) {
                continue;
            }
            for (Content c : contents) {
                if (c != null && c.getContent() != null) {
                    return true;
                }
            }
        }
        return false;
    }

    private static long missOf(String s) {        final int i = s.lastIndexOf("miss=");
        if (i < 0) {
            return -1L;
        }
        try {
            return Long.parseLong(s.substring(i + 5));
        } catch (NumberFormatException e) {
            return -1L;
        }
    }

    // =============================================================== P4-C：反查（按物品找配方）的替代路径

    /**
     * <b>P4-C：量一下"按物品反查配方"在【配方表】上直接扫的代价与命中数</b>。
     *
     * <p>为什么必须量这个：GT 的输入索引是<b>给机器匹配用的</b> —— 它按输入键分叉，
     * {@code find()} 交回的是<b>第一条命中</b>，本来就不提供"列出所有用到 X 的配方"这个能力。
     * 真要做反查，数据源只能是<b>配方表本身</b>（{@code RecipeManager} 那 5 万多条）。
     * 这一段把它变成数字：取一条已知配方里的一个物品，全表扫一遍，报命中数与时延。
     */
    public static void reverseLookupProbe(MinecraftServer server) {
        if (server == null) {
            return;
        }
        try {
            final List<Recipe<?>> all = new ArrayList<>(server.getRecipeManager().getRecipes());
            ItemStack probe = null;
            for (Recipe<?> r : all) {
                if (!(r instanceof GTRecipe gr) || gr.inputs == null) {
                    continue;
                }
                for (Map.Entry<RecipeCapability<?>, List<Content>> e : gr.inputs.entrySet()) {
                    for (Content content : e.getValue()) {
                        if (content != null && content.getContent() instanceof Ingredient ing) {
                            final ItemStack[] stacks = ing.getItems();
                            if (stacks.length > 0 && !stacks[0].isEmpty()) {
                                probe = stacks[0].copy();
                                break;
                            }
                        }
                    }
                    if (probe != null) {
                        break;
                    }
                }
                if (probe != null) {
                    break;
                }
            }
            if (probe == null) {
                ShanhaiMod.LOGGER.warn("{} p4_reverse skipped reason=no_item_ingredient_found", PREFIX);
                return;
            }
            final ItemStack wanted = probe;
            final long t0 = System.nanoTime();
            int hits = 0;
            int scanned = 0;
            for (Recipe<?> r : all) {
                if (!(r instanceof GTRecipe gr) || gr.inputs == null) {
                    continue;
                }
                scanned++;
                boolean hit = false;
                for (List<Content> contents : gr.inputs.values()) {
                    for (Content content : contents) {
                        if (content != null && content.getContent() instanceof Ingredient ing
                                && ing.test(wanted)) {
                            hit = true;
                            break;
                        }
                    }
                    if (hit) {
                        break;
                    }
                }
                if (hit) {
                    hits++;
                }
            }
            final long us = (System.nanoTime() - t0) / 1_000L;
            ShanhaiMod.LOGGER.info("{} p4_reverse item={} recipes_scanned={} hits={} total_us={} "
                            + "口径=全表线性扫(空载单次,未预热) 数据源=RecipeManager.getRecipes()",
                    PREFIX, BuiltInRegistries.ITEM.getKey(wanted.getItem()), scanned, hits, us);
        } catch (Throwable t) {
            ShanhaiMod.LOGGER.error("{} p4_reverse FAIL reason={}", PREFIX, t);
        }
    }
}
