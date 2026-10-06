package com.shanhai.common.recipe.editor;

import com.gregtechceu.gtceu.api.capability.recipe.FluidRecipeCapability;
import com.gregtechceu.gtceu.api.capability.recipe.ItemRecipeCapability;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import com.gregtechceu.gtceu.api.recipe.content.Content;
import com.gregtechceu.gtceu.api.recipe.ingredient.FluidIngredient;
import com.shanhai.ShanhaiMod;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.level.material.Fluid;

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
     * 🆕 2026-10-06（第 14 刀）流体侧的输入/输出反查索引。
     *
     * <h4>用户原话（逐字）</h4>
     * <blockquote>「还有一个很严重的问题，就是我在第一面中无法拖动流体到查询物品框中，<b>流体也是需要查询的</b>」</blockquote>
     *
     * <h4>🔴 匹配语义（必须先写清楚，判据才不是"只比 id"）</h4>
     * 与物品侧<b>逐字同构</b>（见 {@link #query} 与那三条 {@code reverse_index_semantics_note}）：
     * <ul>
     *   <li><b>键 = {@link Fluid} 本体</b>（注册表对象），<b>不带</b> NBT、<b>不带</b>数量 ——
     *       GT 的流体输入是 {@code FluidIngredient}（可能带 tag / 多候选），
     *       本索引收的是它 {@code getStacks()} 里出现过的<b>每一种流体</b>；
     *       ⇒ 索引路问的是「这条配方会不会用到<b>这种流体的某个变体</b>」，
     *       而 {@code FluidIngredient.test(某个具体 FluidStack)} 问的是「这一桶能不能直接喂进去」。
     *       两者对不上是<b>正常</b>的（物品侧同款现象：17864 vs 13），自检会把两个数都打出来；</li>
     *   <li><b>chance / tierChanceBoost / 催化剂（chance==0）一律不参与键</b>：
     *       本查询回答的是"哪些配方用到它"，不是"用多少 / 多大概率"。
     *       ⇒ 一个<b>不消耗</b>的流体催化剂（{@code chance==0}）照旧会出现在"作为物品的用处"里 ✓
     *       （这正是用户要的：他要问的是"哪里用得上它"）；</li>
     *   <li>输入侧与输出侧<b>分开两张表</b>（{@code SOURCE=获取途径} 读输出、{@code USE=用处} 读输入），
     *       与物品侧同一口径；</li>
     *   <li>读不出来的 content <b>跳过</b>（与物品侧同一套容错），不因为一条怪配方把整张表搞崩。</li>
     * </ul>
     *
     * <h4>⚠️ 不许把物品那条路弄慢</h4>
     * 两张表在<b>同一趟扫描</b>里建（{@link #build} 那个循环内），<b>不新增任何一次全表遍历</b>；
     * 大多数配方的流体 content 数为 0（{@code getInputContents(FluidRecipeCapability.CAP)} 直接返回空）
     * ⇒ 增量成本 ≈ 几次空调用/条（物品侧一次 54031 条的全表扫是 264~369 ms，这里是它的零头）。
     */
    private static final Map<Fluid, int[]> BY_FLUID_IN = new HashMap<>();
    private static final Map<Fluid, int[]> BY_FLUID_OUT = new HashMap<>();

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
    /** 🆕 第 14 刀：流体两张表各自的自证结论（与物品侧分开，互不代偿）。 */
    private static volatile boolean verifiedFluidIn = false;
    private static volatile boolean verifiedFluidOut = false;

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

    // ---- 🆕 第 14 刀：流体侧读数（全部独立于物品侧，不覆盖上面任何一个数）----
    private static int fluidInEntries = -1;
    private static int fluidOutEntries = -1;
    private static int probeFluidInIndexHits = -1;
    private static int probeFluidInLinearHits = -1;
    private static int probeFluidOutIndexHits = -1;
    private static int probeFluidOutLinearHits = -1;
    private static String probeFluidInId = "(not run)";
    private static String probeFluidOutId = "(not run)";
    private static String fluidInDiagnosis = "(never built)";
    private static String fluidOutDiagnosis = "(never built)";

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
        BY_FLUID_IN.clear();          // 🆕 第 14 刀
        BY_FLUID_OUT.clear();         // 🆕 第 14 刀
        LOWER_IDS.clear();
        TYPE_IDS.clear();
        verified = false;
        verifiedOutput = false;
        verifiedFluidIn = false;      // 🆕 第 14 刀
        verifiedFluidOut = false;     // 🆕 第 14 刀
        probeIndexHits = -1;
        probeLinearHits = -1;
        probeOutIndexHits = -1;
        probeOutLinearHits = -1;
        probeFluidInIndexHits = -1;
        probeFluidInLinearHits = -1;
        probeFluidOutIndexHits = -1;
        probeFluidOutLinearHits = -1;
        fluidInEntries = 0;
        fluidOutEntries = 0;

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
                // 🆕 第 14 刀：同一趟扫描里建流体两张表（口径见 BY_FLUID_IN 的类文档）。
                //    ⚠️ 只在这里多花几次"取流体 content"的调用；物品那三行一个字没动。
                for (Fluid f : fluidsOf(gt, true)) {
                    fluidOutEntries++;
                    addIndex(BY_FLUID_OUT, f, idx);
                }
                for (Fluid f : fluidsOf(gt, false)) {
                    fluidInEntries++;
                    addIndex(BY_FLUID_IN, f, idx);
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

        // ══════════════════════════════════════════════════════════════════════════════════
        // 🆕 2026-10-06 第 14 刀：流体两张表的【当场自证】（与物品侧逐条同款纪律）
        //
        //   · 索引路 vs 一条【独立实现】的线性扫，同一个探针、同一次运行；
        //   · 探针【不写死 id】（写死会在别的整合包里变成"探针不存在"）：取索引里条目最多的
        //     那种流体 ⇒ 一定真实存在、且是最有代表性的那一种；
        //   · 不相等 ⇒ 标记 unverified，查询退回线性扫（正确性优先，速度其次）；
        //   · 与物品侧【完全分开】：物品那两行日志与那两个 verified 一个字节都没动。
        // ══════════════════════════════════════════════════════════════════════════════════
        final Fluid probeFluidIn = argmaxFluid(BY_FLUID_IN);
        if (probeFluidIn == null) {
            probeFluidInId = "(none: 这张表里一条流体输入都没有)";
            probeFluidInIndexHits = 0;
            probeFluidInLinearHits = 0;
            verifiedFluidIn = true;                 // 空表：索引给 0，线性扫也给 0 ⇒ 一致
            fluidInDiagnosis = "empty (no fluid input in this table)";
        } else {
            probeFluidInId = String.valueOf(
                    net.minecraft.core.registries.BuiltInRegistries.FLUID.getKey(probeFluidIn));
            probeFluidInIndexHits = queryFluidViaIndex(BY_FLUID_IN, probeFluidIn).size();
            probeFluidInLinearHits = linearQueryByFluids(server, probeFluidIn, false).size();
            verifiedFluidIn = probeFluidInIndexHits == probeFluidInLinearHits && probeFluidInIndexHits > 0;
            fluidInDiagnosis = verifiedFluidIn ? "ok"
                    : "index/linear disagree -> fluid-input query falls back to the linear scan";
        }
        final Fluid probeFluidOut = argmaxFluid(BY_FLUID_OUT);
        if (probeFluidOut == null) {
            probeFluidOutId = "(none: 这张表里一条流体输出都没有)";
            probeFluidOutIndexHits = 0;
            probeFluidOutLinearHits = 0;
            verifiedFluidOut = true;
            fluidOutDiagnosis = "empty (no fluid output in this table)";
        } else {
            probeFluidOutId = String.valueOf(
                    net.minecraft.core.registries.BuiltInRegistries.FLUID.getKey(probeFluidOut));
            probeFluidOutIndexHits = queryFluidViaIndex(BY_FLUID_OUT, probeFluidOut).size();
            probeFluidOutLinearHits = linearQueryByFluids(server, probeFluidOut, true).size();
            verifiedFluidOut = probeFluidOutIndexHits == probeFluidOutLinearHits && probeFluidOutIndexHits > 0;
            fluidOutDiagnosis = verifiedFluidOut ? "ok"
                    : "index/linear disagree -> fluid-output query falls back to the linear scan";
        }
        ShanhaiMod.LOGGER.info("{} reverse_index_fluid_built fluid_in_entries={} fluid_out_entries={} "
                        + "probe_in={} in_via_index={} in_via_linear={} in_agree={} "
                        + "probe_out={} out_via_index={} out_via_linear={} out_agree={} "
                        + "（流体侧与物品侧【分开自证】；探针=索引里条目最多的那种流体，不写死 id）",
                PREFIX, fluidInEntries, fluidOutEntries,
                probeFluidInId, probeFluidInIndexHits, probeFluidInLinearHits, verifiedFluidIn,
                probeFluidOutId, probeFluidOutIndexHits, probeFluidOutLinearHits, verifiedFluidOut);
        if (!verifiedFluidIn) {
            ShanhaiMod.LOGGER.warn("{} reverse_index_fluid_in_unverified -> 流体输入查询退回线性扫（正确但慢）: {}",
                    PREFIX, fluidInDiagnosis);
        }
        if (!verifiedFluidOut) {
            ShanhaiMod.LOGGER.warn("{} reverse_index_fluid_out_unverified -> 流体输出查询退回线性扫（正确但慢）: {}",
                    PREFIX, fluidOutDiagnosis);
        }
    }

    /** 🆕 第 14 刀：索引里条目最多的那种流体（探针；表为空返回 null）。 */
    private static Fluid argmaxFluid(Map<Fluid, int[]> table) {
        Fluid best = null;
        int bestN = -1;
        for (Map.Entry<Fluid, int[]> e : table.entrySet()) {
            final int n = e.getValue() == null ? 0 : e.getValue().length;
            if (n > bestN) {
                bestN = n;
                best = e.getKey();
            }
        }
        return best;
    }

    /**
     * 把"某个物品 → 用到它的配方下标表"里追加一条。
     *
     * <p>🔴 第 7 轮改成收一个 {@code Map} 参数：输入侧与输出侧共用同一段代码
     * （原来只有输入侧，直接写死 {@link #BY_ITEM}）。
     */
    private static <K> void addIndex(Map<K, int[]> table, K key, int idx) {
        final int[] old = table.get(key);
        if (old == null) {
            table.put(key, new int[]{idx});
            return;
        }
        final int[] next = new int[old.length + 1];
        System.arraycopy(old, 0, next, 0, old.length);
        next[old.length] = idx;
        table.put(key, next);
    }

    /**
     * 🆕 第 14 刀：一条配方某一侧的<b>流体集合</b>（建流体索引用）。
     *
     * <p>与 {@link #itemsOf} 逐字同构，只是把 capability 从物品换成流体：
     * <pre>
     *   raw = c.content
     *   raw 本身就是 FluidStack  ⇒ 直接取（GT 允许 content 放裸 FluidStack）
     *   否则 FluidRecipeCapability.CAP.of(raw) ⇒ FluidIngredient ⇒ getStacks() ⇒ 每种流体的 Fluid
     * </pre>
     * ⚠️ {@code getStacks()} 对 tag 型流体原料走的是"把 tag 解析成具体流体"这条路
     * （与物品侧 {@code Ingredient.getItems()} 解析 tag 同一口径）；解析不出来就给空数组，
     * 那种情况下这条 content 对索引不可见 —— 与物品侧的行为一致，<b>不另外编数据</b>。
     */
    private static Set<Fluid> fluidsOf(GTRecipe gt, boolean output) {
        final Set<Fluid> fluids = new LinkedHashSet<>();
        final List<Content> contents;
        try {
            contents = output
                    ? gt.getOutputContents(FluidRecipeCapability.CAP)
                    : gt.getInputContents(FluidRecipeCapability.CAP);
        } catch (Throwable t) {
            return fluids;
        }
        if (contents == null) {
            return fluids;
        }
        for (Content c : contents) {
            if (c == null || c.content == null) {
                continue;
            }
            try {
                if (c.content instanceof com.lowdragmc.lowdraglib.side.fluid.FluidStack fs) {
                    if (!fs.isEmpty() && fs.getFluid() != null) {
                        fluids.add(fs.getFluid());
                    }
                    continue;
                }
                final FluidIngredient ing = FluidRecipeCapability.CAP.of(c.content);
                if (ing == null) {
                    continue;
                }
                final com.lowdragmc.lowdraglib.side.fluid.FluidStack[] stacks = ing.getStacks();
                if (stacks == null) {
                    continue;
                }
                for (com.lowdragmc.lowdraglib.side.fluid.FluidStack st : stacks) {
                    if (st != null && !st.isEmpty() && st.getFluid() != null) {
                        fluids.add(st.getFluid());
                    }
                }
            } catch (Throwable ignored) {
                // 单条 content 读不出来就跳过（与物品侧同款容错）
            }
        }
        return fluids;
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

    // ------------------------------------------------------------- 🆕 第 14 刀：流体侧

    /**
     * 「哪些配方的【输入】里用到这种流体」（＝第一面那个框放流体时的「作为物品的用处」）。
     *
     * <p>语义见 {@link #BY_FLUID_IN} 的类文档：键是 {@link Fluid} 本体，
     * <b>不带</b> NBT/数量/chance，也不区分"消耗"与"催化剂"。
     */
    public static List<GTRecipe> queryFluidInput(MinecraftServer server, Fluid fluid) {
        ensure(server);
        if (verifiedFluidIn) {
            return queryFluidViaIndex(BY_FLUID_IN, fluid);
        }
        ShanhaiMod.LOGGER.warn("{} reverse_query_fluid_in_fallback_to_linear fluid={} reason={}",
                PREFIX, fluidIdOf(fluid), fluidInDiagnosis);
        return linearQueryByFluids(server, fluid, false);
    }

    /** 「哪些配方的【输出】里有这种流体」（＝「获取途径」）。 */
    public static List<GTRecipe> queryFluidOutput(MinecraftServer server, Fluid fluid) {
        ensure(server);
        if (verifiedFluidOut) {
            return queryFluidViaIndex(BY_FLUID_OUT, fluid);
        }
        ShanhaiMod.LOGGER.warn("{} reverse_query_fluid_out_fallback_to_linear fluid={} reason={}",
                PREFIX, fluidIdOf(fluid), fluidOutDiagnosis);
        return linearQueryByFluids(server, fluid, true);
    }

    /** 🔴 只走索引那条路（自检的对照拍用它；与 {@link #queryIndexOnly} 同一理由）。 */
    public static List<GTRecipe> queryFluidInputIndexOnly(Fluid fluid) {
        return queryFluidViaIndex(BY_FLUID_IN, fluid);
    }

    /** 🔴 只走索引那条路（输出侧）。 */
    public static List<GTRecipe> queryFluidOutputIndexOnly(Fluid fluid) {
        return queryFluidViaIndex(BY_FLUID_OUT, fluid);
    }

    private static List<GTRecipe> queryFluidViaIndex(Map<Fluid, int[]> table, Fluid fluid) {
        final int[] idx = fluid == null ? null : table.get(fluid);
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

    /**
     * <b>与流体索引同语义</b>的线性对照（自证用）。
     *
     * <p>与建表那段是<b>两份独立实现</b>：这里直接读 {@code GTRecipe.inputs/outputs} 那张 public 表
     * （不走 {@code getInputContents}），否则两边都由同一段代码算出来 ⇒ 永远自洽、永远查不出问题。
     * 判据与物品侧同款：{@code FluidIngredient.test(该流体的一桶)} 太严（它还要看 tag/NBT），
     * 所以这里用的是"这个 ingredient 的候选里有没有这种流体"这一条同语义判据。
     */
    public static List<GTRecipe> linearQueryByFluids(MinecraftServer server, Fluid fluid, boolean output) {
        final List<GTRecipe> out = new ArrayList<>();
        if (server == null || fluid == null) {
            return out;
        }
        for (var r : server.getRecipeManager().getRecipes()) {
            if (!(r instanceof GTRecipe gt)) {
                continue;
            }
            boolean hit = false;
            final List<Content> contents = output
                    ? gt.outputs.get(FluidRecipeCapability.CAP)
                    : gt.inputs.get(FluidRecipeCapability.CAP);
            if (contents != null) {
                for (Content c : contents) {
                    try {
                        if (c == null || c.content == null) {
                            continue;
                        }
                        if (c.content instanceof com.lowdragmc.lowdraglib.side.fluid.FluidStack fs) {
                            if (!fs.isEmpty() && fs.getFluid() == fluid) {
                                hit = true;
                                break;
                            }
                            continue;
                        }
                        final FluidIngredient ing = FluidRecipeCapability.CAP.of(c.content);
                        if (ing == null) {
                            continue;
                        }
                        final com.lowdragmc.lowdraglib.side.fluid.FluidStack[] stacks = ing.getStacks();
                        if (stacks == null) {
                            continue;
                        }
                        for (com.lowdragmc.lowdraglib.side.fluid.FluidStack st : stacks) {
                            if (st != null && !st.isEmpty() && st.getFluid() == fluid) {
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

    /** 流体输入索引里的条目数（读数用）。 */
    public static int fluidInputEntryCount() {
        return fluidInEntries;
    }

    /** 流体输出索引里的条目数（读数用）。 */
    public static int fluidOutputEntryCount() {
        return fluidOutEntries;
    }

    public static boolean isFluidInputVerified() {
        return verifiedFluidIn;
    }

    public static boolean isFluidOutputVerified() {
        return verifiedFluidOut;
    }

    /** 流体自证的诊断行（读数用）。 */
    public static String fluidDiagnosisLine() {
        return "in=" + fluidInDiagnosis + " out=" + fluidOutDiagnosis
                + " probe_in=" + probeFluidInId + "(" + probeFluidInIndexHits + "/" + probeFluidInLinearHits + ")"
                + " probe_out=" + probeFluidOutId + "(" + probeFluidOutIndexHits + "/" + probeFluidOutLinearHits + ")";
    }

    /** 流体 id（读数的统一口径）。 */
    public static String fluidIdOf(Fluid fluid) {
        if (fluid == null) {
            return "?";
        }
        final ResourceLocation id = net.minecraft.core.registries.BuiltInRegistries.FLUID.getKey(fluid);
        return id == null ? "?" : id.toString();
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
