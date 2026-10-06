package com.shanhai.common.recipe.editor;

import com.gregtechceu.gtceu.api.capability.recipe.ItemRecipeCapability;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import com.gregtechceu.gtceu.api.recipe.content.Content;
import com.shanhai.ShanhaiMod;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * 「物品 → 用它的配方」反查表：<b>从配方表自建</b>，不走 GT 的输入索引。
 *
 * <h2>1. 🔴 为什么不能用 GT 的输入索引（这是本刀的头号坑之一）</h2>
 * 本整合包的 {@code gtlcore} 用 mixin {@code @Overwrite} 掉了 GT 的输入查询入口，改写版依赖一个
 * <b>只有"真机器发起查询"时才被赋值的内部字段</b> ⇒ 外部调用<b>一律返回 null 且不抛异常</b>，
 * 且它语义上只交回<b>第一条命中</b>。前一轮探针已实测到这一条：
 * {@code temp/recipe-edit-probe/p6-raw-log-lines.txt} 的
 * {@code read case=baseline by=input(gt6_tree) found=false} / {@code by=input(holder) found=false}，
 * 而同一拍的 {@code by=input(vanilla_tree)} 是 {@code MATCH}。
 * ⇒ 我们能用的只有<b>配方表本身</b>。
 *
 * <h2>2. 数据源 = {@code RecipeManager.getRecipes()} 的 GTRecipe 部分</h2>
 * 同一份日志给出的读数：{@code p4_reverse item=gtceu:programmed_circuit
 * recipes_scanned=52140 hits=4110 total_us=70343}（口径：全表线性扫，空载单次未预热）。
 * ⇒ 一次全表线性扫 ≈ 70 ms，做一次 UI 查询够用；但每次开面板都扫一遍是不必要的，
 * 所以本类把它<b>物化</b>成 {@code item → 下标} 的表。
 *
 * <h2>3. 🔴 建完表要【当场自证】，否则退回线性扫</h2>
 * 物化索引与"逐条问"可能不等价（例如 GT 的电路类输入是
 * {@code IntCircuitIngredient}，{@code getItems()} 可能给空数组而 {@code test()} 通过）。
 * ⇒ {@link #build} 结束时对一条固定的探针物品同时跑<b>索引路</b>与<b>线性控制路</b>，
 * 两个数字相等才把 {@code verified} 置真；不等则打 ERROR 并让
 * {@link #query} 退回线性扫（正确性优先，速度其次）。
 * 这条对照的实验设计同"负对照"：同一个输入、同一张表、同一次运行，唯一的差别是"走不走索引"。
 */
public final class ShanhaiRecipeReverseIndex {

    public static final String PREFIX = "[SHANHAI-EDIT] editor";

    /** 自证用的探针物品（前一轮实测命中 4110 条，是有真值来源的数）。 */
    public static final ResourceLocation PROBE_ITEM = new ResourceLocation("gtceu", "programmed_circuit");

    /** 前一轮实测的期望命中数（只作参考口径打印，不作硬判据 —— 配方会随包更新而变）。 */
    public static final int PROBE_EXPECTED_HITS = 4110;

    private static final List<GTRecipe> RECIPES = new ArrayList<>();
    private static final Map<ResourceLocation, Integer> BY_ID = new HashMap<>();
    private static final Map<Item, int[]> BY_ITEM = new HashMap<>();

    /**
     * 🆕 2026-10-05 第 7 轮：<b>输出反向索引</b>（物品 → 哪些配方的输出里有它）。
     *
     * <p>用途 = 用户点单的「<b>获取途径</b>」那一问（"这个东西怎么来的"）。
     * 与 {@link #BY_ITEM}（输入侧）同一套建法与同一套自证纪律：索引路必须与一条
     * <b>独立实现的线性扫</b>给出同一个数，否则退回线性扫。
     */
    private static final Map<Item, int[]> BY_OUTPUT = new HashMap<>();

    /**
     * 🆕 2026-10-05 第 7 轮：文本搜索用的两条平行表（下标与 {@link #RECIPES} 一一对应）。
     *
     * <p>为什么预存而不是每次现算：全表 5.2 万条，每条都 {@code id.toString().toLowerCase()}
     * 会在每次敲键时分配 5 万个字符串。预存一次是常数开销。
     */
    private static final List<String> LOWER_IDS = new ArrayList<>();
    private static final List<ResourceLocation> TYPE_IDS = new ArrayList<>();

    private static volatile boolean built = false;
    private static volatile boolean verified = false;
    private static volatile boolean verifiedOutput = false;

    private static long buildMs = -1;
    private static int scannedCount = -1;
    private static int entryCount = -1;
    private static int outputEntryCount = -1;
    private static int probeIndexHits = -1;
    private static int probeLinearHits = -1;
    private static int probeTestHits = -1;
    private static int probeOutIndexHits = -1;
    private static int probeOutLinearHits = -1;
    private static String probeItemId = "(not run)";
    private static String buildDiagnosis = "(never built)";
    /** 🆕 第 7 轮：输出索引的自证结论（与输入侧分开，两者互不代偿）。 */
    private static String outputDiagnosis = "(never built)";

    private ShanhaiRecipeReverseIndex() {}

    /** 记录一条配方的可展示摘要（面板行用它，不把整个 GTRecipe 传到客户端）。 */
    public record Row(ResourceLocation id, String typeId, int duration) {}

    // ------------------------------------------------------------- 建表

    /** 立刻重建（幂等）。 */
    public static synchronized void build(MinecraftServer server) {
        final long t0 = System.nanoTime();
        RECIPES.clear();
        BY_ID.clear();
        BY_ITEM.clear();
        BY_OUTPUT.clear();
        LOWER_IDS.clear();
        TYPE_IDS.clear();
        verified = false;
        verifiedOutput = false;
        probeIndexHits = -1;
        probeLinearHits = -1;
        probeOutIndexHits = -1;
        probeOutLinearHits = -1;

        int scanned = 0;
        int entries = 0;
        int outEntries = 0;
        int noIngredient = 0;
        int zeroItemIngredients = 0;
        try {
            for (var r : server.getRecipeManager().getRecipes()) {
                if (!(r instanceof GTRecipe gt)) {
                    continue;
                }
                scanned++;
                final int idx = RECIPES.size();
                RECIPES.add(gt);
                if (gt.id != null) {
                    BY_ID.put(gt.id, idx);
                }
                LOWER_IDS.add(gt.id == null ? "" : gt.id.toString().toLowerCase(java.util.Locale.ROOT));
                TYPE_IDS.add(gt.getType() == null ? null : gt.getType().registryName);
                // 🆕 第 7 轮：输出侧记账（「获取途径」用它）
                //    🔴 必须是 true（＝读 outputs）—— 冒烟第一版这里写成 false，
                //       结果 BY_OUTPUT 与 BY_ITEM 变成了同一份数据（读数：两条链的条目数
                //       一模一样 151453 / 151453），自证当场判 agree=false 并退回线性扫。
                for (Item it : itemsOf(gt, true)) {
                    outEntries++;
                    addIndex(BY_OUTPUT, it, idx);
                }
                final Set<Item> items = new LinkedHashSet<>();
                final List<Content> contents = gt.getInputContents(ItemRecipeCapability.CAP);
                if (contents == null || contents.isEmpty()) {
                    noIngredient++;
                } else {
                    for (Content c : contents) {
                        final Ingredient ing;
                        try {
                            ing = ItemRecipeCapability.CAP.of(c.content);
                        } catch (Throwable ignored) {
                            continue;
                        }
                        if (ing == null) {
                            continue;
                        }
                        final ItemStack[] stacks;
                        try {
                            stacks = ing.getItems();
                        } catch (Throwable ignored) {
                            continue;
                        }
                        if (stacks.length == 0) {
                            zeroItemIngredients++;
                        }
                        for (ItemStack st : stacks) {
                            if (st != null && !st.isEmpty()) {
                                items.add(st.getItem());
                            }
                        }
                    }
                }
                for (Item it : items) {
                    entries++;
                    addIndex(BY_ITEM, it, idx);
                }
            }
            scannedCount = scanned;
            entryCount = entries;
            outputEntryCount = outEntries;
            built = true;
        } catch (Throwable t) {
            built = false;
            buildDiagnosis = "build threw " + t.getClass().getSimpleName() + ": " + t.getMessage();
            ShanhaiMod.LOGGER.error("{} reverse_index_build_failed err={}", PREFIX, t.toString());
            buildMs = (System.nanoTime() - t0) / 1_000_000L;
            return;
        }
        buildMs = (System.nanoTime() - t0) / 1_000_000L;

        // ---- 当场自证：索引路 vs 线性控制路，同一个输入、同一次运行 ----
        //
        // 🔴 2026-10-05 冒烟第一版在这里判错了，记下来免得下次再判错：
        //    第一版的控制路用的是 {@code Ingredient.test(new ItemStack(item))}，于是
        //    {@code gtceu:programmed_circuit} 得到 index=17438 / linear=1 而判"不一致"。
        //    真相不是索引错，而是【两条路的语义不同】：
        //      · 索引路问的是"这条配方会不会用到这个物品的【某个变体】"（电路 0..32 都算）；
        //      · test 路问的是"这个【裸物品】能不能直接喂进去"（裸 Programmed Circuit 没有电路号，
        //        只有 1 条配方吃它）。
        //    「我手上这东西能做什么」要的是前者 ⇒ 正确的对照必须是【同语义、另一份实现】，
        //    即 {@link #linearQueryByItems}（直接读 {@code recipe.inputs} 表，不复用建表那段代码）。
        //    而 test 语义那条读数仍然打出来（改名 probe_via_test），因为它本身是有价值的信息。
        final Item probe = net.minecraft.core.registries.BuiltInRegistries.ITEM.get(PROBE_ITEM);
        if (probe != null) {
            probeItemId = PROBE_ITEM.toString();
            probeIndexHits = queryViaIndex(probe).size();
            probeLinearHits = linearQueryByItems(server, probe).size();
            probeTestHits = linearQuery(server, probe).size();
            verified = probeIndexHits == probeLinearHits;
            // 🆕 第 7 轮：输出侧用【同一个探针物品】做同一套对照（索引路 vs 独立线性扫）
            probeOutIndexHits = queryViaIndex(BY_OUTPUT, probe).size();
            probeOutLinearHits = linearQueryByOutputItems(server, probe).size();
            verifiedOutput = probeOutIndexHits == probeOutLinearHits;
        } else {
            probeItemId = PROBE_ITEM + " (not registered)";
            verified = false;
            verifiedOutput = false;
        }
        buildDiagnosis = verified ? "ok" : "index/linear disagree -> query() falls back to linear scan";
        outputDiagnosis = verifiedOutput ? "ok"
                : "output index/linear disagree -> queryByOutput() falls back to linear scan";

        ShanhaiMod.LOGGER.info("{} reverse_index_built scanned={} indexed_recipes={} index_entries={} "
                        + "output_index_entries={} empty_input_recipes={} zero_item_ingredients={} ms={} no_ingredient_recipes={}",
                PREFIX, scannedCount, RECIPES.size(), entryCount, outputEntryCount, noIngredient,
                zeroItemIngredients, buildMs, noIngredient);
        ShanhaiMod.LOGGER.info("{} reverse_index_probe item={} via_index={} via_linear_same_semantics={} "
                        + "via_test_semantics={} expected_previous_round_info_only={} agree={}",
                PREFIX, probeItemId, probeIndexHits, probeLinearHits, probeTestHits, PROBE_EXPECTED_HITS, verified);
        ShanhaiMod.LOGGER.info("{} reverse_index_output_probe item={} via_index={} via_linear_same_semantics={} agree={} "
                        + "（「获取途径」走的那条路；与输入侧同一套自证纪律）",
                PREFIX, probeItemId, probeOutIndexHits, probeOutLinearHits, verifiedOutput);
        if (probeTestHits >= 0 && probeTestHits != probeIndexHits) {
            ShanhaiMod.LOGGER.info("{} reverse_index_semantics_note 索引路={} ≠ test 路={} —— "
                            + "这不是不一致：索引路算的是「用到这个物品的某个变体」，test 路算的是「裸物品能直接喂进去」",
                    PREFIX, probeIndexHits, probeTestHits);
        }
        if (!verified) {
            // 🔴 2026-10-05 降 ERROR → WARN（用户口径：「凡不是'真的坏了'一律降到 WARN」）。
            //    这不是坏：函数自己的回退是【正确但慢】的那条线性扫（同一句里就写着 correct but ~70ms），
            //    而且同一个查询还会再打一条 reverse_query_fallback_to_linear（本来就是 WARN）。
            //    ⇒ 原来那两条一个 ERROR 一个 WARN，说的是同一件事，级别却不同。
            ShanhaiMod.LOGGER.warn("{} reverse_index_unverified -> query() will use the linear scan (correct but ~70ms): {}",
                    PREFIX, buildDiagnosis);
        }
    }

    /**
     * 把"某个物品 → 用到它的配方下标表"里追加一条。
     *
     * <p>🔴 第 7 轮改成收一个 {@code Map} 参数：输入侧与输出侧共用同一段代码
     * （原来只有输入侧，直接写死 {@link #BY_ITEM}）。
     */
    private static void addIndex(Map<Item, int[]> table, Item item, int idx) {
        final int[] old = table.get(item);
        if (old == null) {
            table.put(item, new int[]{idx});
            return;
        }
        final int[] next = new int[old.length + 1];
        System.arraycopy(old, 0, next, 0, old.length);
        next[old.length] = idx;
        table.put(item, next);
    }

    /**
     * 一条配方的【输出】里用到的物品集合（建输出索引、以及线性对照共用的一种口径）。
     *
     * <p>⚠️ 与建输入索引那段<b>分开写</b>：输入那段有 {@code noIngredient} /
     * {@code zeroItemIngredients} 两条上一轮就在用的读数，一个字节都不该动（红线）。
     */
    private static Set<Item> itemsOf(GTRecipe gt, boolean output) {
        final Set<Item> items = new LinkedHashSet<>();
        final List<Content> contents;
        try {
            contents = output
                    ? gt.getOutputContents(ItemRecipeCapability.CAP)
                    : gt.getInputContents(ItemRecipeCapability.CAP);
        } catch (Throwable t) {
            return items;
        }
        if (contents == null) {
            return items;
        }
        for (Content c : contents) {
            final Ingredient ing;
            try {
                ing = ItemRecipeCapability.CAP.of(c.content);
            } catch (Throwable ignored) {
                continue;
            }
            if (ing == null) {
                continue;
            }
            final ItemStack[] stacks;
            try {
                stacks = ing.getItems();
            } catch (Throwable ignored) {
                continue;
            }
            for (ItemStack st : stacks) {
                if (st != null && !st.isEmpty()) {
                    items.add(st.getItem());
                }
            }
        }
        return items;
    }

    /** 配方重载 / 编辑之后让缓存失效（下次查询或下次开面板时重建）。 */
    public static synchronized void invalidate() {
        built = false;
        verified = false;
        verifiedOutput = false;
        buildDiagnosis = "invalidated";
        outputDiagnosis = "invalidated";
    }

    /** 需要时重建（懒）。 */
    public static void ensure(MinecraftServer server) {
        if (!built) {
            build(server);
        }
    }

    public static boolean isBuilt() {
        return built;
    }

    public static boolean isVerified() {
        return verified;
    }

    // ------------------------------------------------------------- 查询

    /**
     * 「哪些配方用到了这个物品」。<b>索引与线性扫都给出同一个结果集</b>（自证通过时走索引）。
     */
    public static List<GTRecipe> query(MinecraftServer server, Item item) {
        ensure(server);
        if (verified) {
            return queryViaIndex(item);
        }
        ShanhaiMod.LOGGER.warn("{} reverse_query_fallback_to_linear item={} reason={}",
                PREFIX, item, buildDiagnosis);
        return linearQuery(server, item);
    }

    /** 🔴<b>只走索引</b>那条路（不做 verified 判断、不掉回线性扫）。自检的对照拍必须用它，
     *  否则 {@code query()} 在未自证时会两边都走线性扫，对照就失去判别力。 */
    public static List<GTRecipe> queryIndexOnly(Item item) {
        return queryViaIndex(item);
    }

    private static List<GTRecipe> queryViaIndex(Item item) {
        return queryViaIndex(BY_ITEM, item);
    }

    /** 索引路（输入侧 / 输出侧共用）。 */
    private static List<GTRecipe> queryViaIndex(Map<Item, int[]> table, Item item) {
        final int[] idx = table.get(item);
        if (idx == null) {
            return List.of();
        }
        final List<GTRecipe> out = new ArrayList<>(idx.length);
        for (int i : idx) {
            if (i >= 0 && i < RECIPES.size()) {
                out.add(RECIPES.get(i));
            }
        }
        return out;
    }

    // ------------------------------------------------------------- 🆕 第 7 轮：输出侧

    /** 「哪些配方的【输出】里有这个物品」（＝用户点单的「获取途径」）。 */
    public static List<GTRecipe> queryByOutput(MinecraftServer server, Item item) {
        ensure(server);
        if (verifiedOutput) {
            return queryViaIndex(BY_OUTPUT, item);
        }
        ShanhaiMod.LOGGER.warn("{} reverse_query_output_fallback_to_linear item={} reason={}",
                PREFIX, item, outputDiagnosis);
        return linearQueryByOutputItems(server, item);
    }

    /** 🔴<b>只走输出索引</b>那条路（自检的对照拍必须用它，否则未自证时两边都走线性扫）。 */
    public static List<GTRecipe> queryOutputIndexOnly(Item item) {
        return queryViaIndex(BY_OUTPUT, item);
    }

    /**
     * <b>与输出索引同语义</b>的线性对照：逐条问"这条配方的输出里有没有这个物品"。
     *
     * <p>与 {@link #build} 里建输出索引那段是<b>两份独立实现</b>（这里直接读
     * {@code GTRecipe.outputs} 那张 public 表），否则永远自洽、永远查不出问题。
     */
    public static List<GTRecipe> linearQueryByOutputItems(MinecraftServer server, Item item) {
        final List<GTRecipe> out = new ArrayList<>();
        if (server == null) {
            return out;
        }
        for (var r : server.getRecipeManager().getRecipes()) {
            if (!(r instanceof GTRecipe gt)) {
                continue;
            }
            boolean hit = false;
            final List<Content> contents = gt.outputs.get(ItemRecipeCapability.CAP);
            if (contents != null) {
                for (Content c : contents) {
                    try {
                        final Ingredient ing = ItemRecipeCapability.CAP.of(c.content);
                        if (ing == null) {
                            continue;
                        }
                        for (ItemStack st : ing.getItems()) {
                            if (st != null && !st.isEmpty() && st.getItem() == item) {
                                hit = true;
                                break;
                            }
                        }
                    } catch (Throwable ignored) {
                        // 单条 content 读不出来就跳过（与建表那边同款容错）
                    }
                    if (hit) {
                        break;
                    }
                }
            }
            if (hit) {
                out.add(gt);
            }
        }
        return out;
    }

    // ------------------------------------------------------------- 🆕 第 7 轮：文本搜索的平行表

    /** 第 {@code i} 条配方的 id（全小写；文本搜索用）。 */
    public static String lowerIdAt(int i) {
        return i >= 0 && i < LOWER_IDS.size() ? LOWER_IDS.get(i) : null;
    }

    /** 第 {@code i} 条配方的配方种类 id。 */
    public static ResourceLocation typeIdAt(int i) {
        return i >= 0 && i < TYPE_IDS.size() ? TYPE_IDS.get(i) : null;
    }

    /** 第 {@code i} 条配方本身。 */
    public static GTRecipe recipeAt(int i) {
        return i >= 0 && i < RECIPES.size() ? RECIPES.get(i) : null;
    }

    /** 输出索引里的条目数（读数用）。 */
    public static int outputEntryCount() {
        return outputEntryCount;
    }

    public static boolean isOutputVerified() {
        return verifiedOutput;
    }

    /**
     * <b>与索引路同语义</b>的线性对照：逐条问"这条配方会不会用到这个物品的某个变体"。
     *
     * <p>🔴 它与 {@link #build} 里那段建表代码是<b>两份独立实现</b>（这里直接读
     * {@code GTRecipe.inputs} 那张 public 表，不走 {@code getInputContents}），
     * 否则"两边都由同一段代码算出来"⇒ 永远自洽、永远查不出问题。
     */
    public static List<GTRecipe> linearQueryByItems(MinecraftServer server, Item item) {
        final List<GTRecipe> out = new ArrayList<>();
        for (var r : server.getRecipeManager().getRecipes()) {
            if (!(r instanceof GTRecipe gt)) {
                continue;
            }
            boolean hit = false;
            final List<Content> contents = gt.inputs.get(ItemRecipeCapability.CAP);
            if (contents != null) {
                for (Content c : contents) {
                    try {
                        final Ingredient ing = ItemRecipeCapability.CAP.of(c.content);
                        if (ing == null) {
                            continue;
                        }
                        for (ItemStack st : ing.getItems()) {
                            if (st != null && !st.isEmpty() && st.getItem() == item) {
                                hit = true;
                                break;
                            }
                        }
                    } catch (Throwable ignored) {
                        // 单条 content 读不出来就跳过（与建表那边同款容错）
                    }
                    if (hit) {
                        break;
                    }
                }
            }
            if (hit) {
                out.add(gt);
            }
        }
        return out;
    }

    /**
     * <b>另一套语义</b>的线性控制路：{@code Ingredient.test(裸物品)} —— "这个裸物品能不能直接喂进去"。
     *
     * <p>它不是索引路的对照（语义不同），只作<b>信息性</b>读数：两个数字的差额就是
     * "该物品的种种变体"贡献的那部分。见 {@link #build} 里那段说明。
     */
    public static List<GTRecipe> linearQuery(MinecraftServer server, Item item) {
        final ItemStack probe = new ItemStack(item);
        final List<GTRecipe> out = new ArrayList<>();
        for (var r : server.getRecipeManager().getRecipes()) {
            if (r instanceof GTRecipe gt && usesItem(gt, probe)) {
                out.add(gt);
            }
        }
        return out;
    }

    private static boolean usesItem(GTRecipe gt, ItemStack probe) {
        final List<Content> contents = gt.getInputContents(ItemRecipeCapability.CAP);
        if (contents == null) {
            return false;
        }
        for (Content c : contents) {
            try {
                final Ingredient ing = ItemRecipeCapability.CAP.of(c.content);
                if (ing != null && ing.test(probe)) {
                    return true;
                }
            } catch (Throwable ignored) {
                // 单条 content 读不出来 ⇒ 跳过（不改变"这条算不算命中"的判定口径之外的东西）
            }
        }
        return false;
    }

    /**
     * 按 id 直接取那条<b>活</b>配方（编辑路径用）。
     *
     * <h4>🔴 2026-10-05（用户报「新建了 GT 配方，<b>甚至我们的配方编辑器都没有即时刷新</b>」）</h4>
     * 原来的写法是<b>先查快照</b><code>BY_ID</code>：
     * <pre>
     *   final Integer i = BY_ID.get(id);
     *   if (i != null ...) return RECIPES.get(i);     ← 扫描那一刻的那个对象
     * </pre>
     * 而编辑器改一条配方时会把索引里那条**换成一个新对象**（{@code applyEdits} 里就是"重建一条同类新实例"）
     * ⇒ 这个快照里那个旧对象<b>永远不会变</b> ⇒ 面板的第二屏卡片、第三屏的读回、以及"获取途径"那些卡片
     * <b>全都显示旧值</b>，用户看到的就是"编辑器自己都不刷新"。
     * <p>（这是同一族 bug 的第三个：① FastSuite 的配方缓存 ② 我那条整表广播 ③ 这个快照。
     *  共同点 = <b>拿到的是一份"当时那一刻"的拷贝，却当成了实时真值</b>。）
     *
     * <p>修法 = <b>活表优先</b>：
     * ① {@code RecipeManager.byKey(id)}（O(1)；我们每次写完都会把 byName 那张钉一遍）；
     * ② 万一没命中就线性扫一遍活表；
     * ③ <b>活表里没有就返回 {@code null}</b> —— 这条已经被删了，绝不许拿快照把它"复活"到面板上。
     */
    public static GTRecipe byId(MinecraftServer server, ResourceLocation id) {
        if (server == null || id == null) {
            return null;
        }
        // ① 活表 O(1)（byName）
        try {
            final java.util.Optional<? extends net.minecraft.world.item.crafting.Recipe<?>> live =
                    server.getRecipeManager().byKey(id);
            if (live.isPresent()) {
                return live.get() instanceof GTRecipe gt ? gt : null;
            }
        } catch (Throwable t) {
            ShanhaiMod.LOGGER.warn("{} reverse_by_id_live_lookup_failed id={} err={}", PREFIX, id, t.toString());
        }
        // ② 活表线性扫（byName 万一没跟上）
        for (var r : server.getRecipeManager().getRecipes()) {
            if (r instanceof GTRecipe gt && id.equals(gt.id)) {
                return gt;
            }
        }
        // ③ 活表里没有 ⇒ 这条已经不在配方表里了（快照里有也不许返回）
        return null;
    }

    /** 全量（只读快照，供自检）。 */
    public static List<GTRecipe> all() {
        return List.copyOf(RECIPES);
    }

    public static int size() {
        return RECIPES.size();
    }

    // ------------------------------------------------------------- 读数

    public static String statsLine() {
        return "scanned=" + scannedCount + " indexed_recipes=" + RECIPES.size()
                + " index_entries=" + entryCount + " build_ms=" + buildMs
                + " verified=" + verified + " probe_via_index=" + probeIndexHits
                + " probe_via_linear_same_semantics=" + probeLinearHits
                + " probe_via_test_semantics=" + probeTestHits + " probe_item=" + probeItemId
                + " diagnosis=" + buildDiagnosis
                + " output_entries=" + outputEntryCount + " output_verified=" + verifiedOutput
                + " output_probe_via_index=" + probeOutIndexHits
                + " output_probe_via_linear=" + probeOutLinearHits;
    }

    /** 取若干条配方的可展示摘要（面板分页用）。 */
    public static List<Row> toRows(List<GTRecipe> list) {
        final List<Row> out = new ArrayList<>(list.size());
        for (GTRecipe r : list) {
            final var type = r.getType();
            out.add(new Row(r.id == null ? new ResourceLocation("minecraft", "unknown") : r.id,
                    type == null || type.registryName == null ? "?" : type.registryName.toString(),
                    r.duration));
        }
        return out;
    }

    /** 未使用的占位（保留给将来的 tag 反向索引），避免 IDE 报警。 */
    @SuppressWarnings("unused")
    private static final Predicate<GTRecipe> ALWAYS = r -> true;
}
