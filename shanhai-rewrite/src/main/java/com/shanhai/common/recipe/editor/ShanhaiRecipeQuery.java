package com.shanhai.common.recipe.editor;

import com.gregtechceu.gtceu.api.capability.recipe.FluidRecipeCapability;
import com.gregtechceu.gtceu.api.capability.recipe.ItemRecipeCapability;
import com.gregtechceu.gtceu.api.machine.MachineDefinition;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import com.gregtechceu.gtceu.api.recipe.GTRecipeType;
import com.gregtechceu.gtceu.api.recipe.content.Content;
import com.gregtechceu.gtceu.api.registry.GTRegistries;
import com.lowdragmc.lowdraglib.side.fluid.FluidStack;
import com.shanhai.ShanhaiMod;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.material.Fluid;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * <b>配方查询引擎</b>（服务端权威）：三种「按物品查」＋ 一种「按文字查」。
 *
 * <h2>1. 四种查询（用户点单的语义，逐条对应）</h2>
 * <pre>
 *   ① {@link Kind#SOURCE}   获取途径      —— 这个物品【怎么来的】：哪些配方的【输出】里有它
 *   ② {@link Kind#USE}      作为物品的用处 —— 它【被谁当材料吃】：哪些配方的【输入】里有它
 *   ③ {@link Kind#MACHINE}  作为机器的用处 —— 它【当机器用时能跑哪些配方】：
 *                                            在 {@code GTRegistries.MACHINES} 里找出物品是这个方块的那些机器定义，
 *                                            取它们声明的配方类型，列出这些类型下的配方
 *   ④ {@link Kind#TEXT}     文本搜索      —— 同时匹配【配方 id】与【配方种类名/id】（中英文都吃）
 * </pre>
 * 前三条由用户逐字确认过口径（2026-10-05）：
 * 「获取途径／作为物品的用处／作为机器的用处」「它当机器用的用处 = 这个物品当机器用时能跑哪些配方」。
 *
 * <h2>2. 🔴 结果按【配方种类】分组 —— 这就是"上面那一排"</h2>
 * 一个物品往往被十几种机器用到。把结果按种类分组成 {@link Group}，
 * 界面顶部那一排就是这些分组（照 JEI 顶栏那排机器图标的意思）。
 *
 * <h2>3. 🔴 数据来源仍然是 RecipeManager 里的 {@code GTRecipe} —— 不是 JEI</h2>
 * 与 {@link ShanhaiRecipeReverseIndex} 同一条纪律：本包 {@code gtlcore} 把 GT 的输入查询入口
 * mixin 掉了 ⇒ 唯一可用的真值是配方表本身。所以"获取途径"用新加的<b>输出反向索引</b>
 * （{@link ShanhaiRecipeReverseIndex#queryByOutput}），它与一条独立实现的线性扫当场对过账。
 *
 * <h2>4. ⚠️ 如实交代</h2>
 * 本类只算数据、不画界面 ⇒ 它的每一条判据都能在无头专服里跑（见
 * {@code ShanhaiRecipeEditorWorkspaceCheck} 的 {@code WS_Q_*} 那一组读数）。
 * 卡片<b>长什么样</b>验不了（红线禁止开客户端）。
 */
public final class ShanhaiRecipeQuery {

    public static final String PREFIX = "[SHANHAI-EDIT] editor";

    /** 一张卡片上每侧最多画几个图标（多的写成 {@code +N}）。 */
    public static final int MAX_CHIPS = 4;

    /** 一个查询最多返回多少条（防止"输入 a"命中几万条把界面卡死）。 */
    public static final int MAX_RESULTS = 4000;

    /** 一次查询最多分成几组（＝几种配方种类）。 */
    public static final int MAX_GROUPS = 240;

    // ------------------------------------------------------------------ 数据形状

    /** 一次查询的种类。{@code zh} 就是按钮上的中文名。 */
    public enum Kind {
        NONE("未查询"),
        SOURCE("获取途径"),
        USE("作为物品的用处"),
        MACHINE("作为机器的用处"),
        TEXT("文本搜索");

        public final String zh;

        Kind(String zh) {
            this.zh = zh;
        }
    }

    /** 电路号的「没有」值。⚠️ 不能用 0 —— 电路 0（`gtceu:programmed_circuit` 带 `Configuration=0`）是合法编号。 */
    public static final int NO_CIRCUIT = -1;

    /**
     * 卡片上的一个图标格。
     *
     * <p>⚠️ 只带<b>注册表整数 id ＋ 数量</b>，不带 {@code ItemStack} ——
     * 因为数量那条线在上一轮实测会被 {@code FriendlyByteBuf.writeItem} 里的
     * {@code writeByte(getCount())} 截断（≥128 变负数）。这里自己写 VarInt，绕开那个坑。
     *
     * <h2>🔴 2026-10-05 第 11 刀：多带两个「造卡片时被丢掉」的字段</h2>
     * 用户原话（逐字）：「<b>这个编程电路的显示有问题，它没有具体显示几号电路，
     * 而且那个催化剂的标志也没有显示</b>」 —— 根因是上一刀查出来的：
     * 这一格原来只留了「物品 id ＋ 数量」，<b>NBT 在造卡片那一步就被丢了</b>，
     * 于是"第几号电路"与"是不是不消耗的催化剂"这两件事都传不到客户端。
     * 现在两个都在<b>服务端</b>取好、随卡片推下去（见 {@link #writeChips}/{@link #readChips}）。
     *
     * @param circuit  <b>编程电路的编号</b>（0..32）。{@link #NO_CIRCUIT} = 这一格不是编程电路、
     *                 或者那个物品上没有 {@code Configuration} 这个 NBT（⇒ 卡片上<b>不画</b>电路角标）。
     *                 取值口径见 {@link ShanhaiIoTable#circuitOf(ItemStack)}。
     * @param catalyst 这一格是不是<b>不消耗的催化剂</b>（＝ GT 的 {@code chance == 0}，
     *                 判据与依据见 {@link ShanhaiIoTable#isNotConsumable(Content)}）。
     *                 ⚠️ <b>只在输入侧</b>取：输出侧的 {@code chance == 0} 是"0% 产出"，不是"不消耗"。
     */
    public record Chip(boolean item, int id, int count, int circuit, boolean catalyst) {

        /** 三参数的老构造（电路号 = 没有、非催化剂）—— 只为少改调用点，语义与老版本逐字一致。 */
        public Chip(boolean item, int id, int count) {
            this(item, id, count, NO_CIRCUIT, false);
        }
    }

    /** 一张配方卡片（服务端算好、推给客户端画）。 */
    public record Card(ResourceLocation id, String shortId, String typeName,
                       List<Chip> ins, List<Chip> outs, int insMore, int outsMore,
                       int duration, int original, long eut, int tierIndex, int amperage,
                       boolean selected) {}

    /** 一组（＝一种配方种类的）结果。 */
    public record Group(ResourceLocation typeId, String typeName, List<ResourceLocation> recipes) {}

    /**
     * 顶部那一排里的一个"机器图标"标签页。
     *
     * <p>用户原话（逐字）：「你最好像jei一样把那排机器的图标也画出来，并且鼠标悬停在上面时可以显示
     * 配方类型的中文名」⇒ 所以这一个 record 里除了标签文字，还必须带上<b>图标</b>。
     *
     * @param label    悬停时显示的中文名（服务端按语言表解析好推下来）
     * @param hasIcon  有没有拿到机器方块图标
     * @param iconId   {@code BuiltInRegistries.ITEM} 里的整数 id
     * @param count    这一种配方有多少条
     * @param selected 是不是当前选中的那一个
     */
    public record Tab(String label, boolean hasIcon, int iconId, int count, boolean selected) {}

    /** 走这个物品当机器时，图标从哪来（供 {@link #tabOf} 用）。 */
    private static final Map<ResourceLocation, ItemStack> ICON_CACHE = new LinkedHashMap<>();

    /** 一次查询的结果。 */
    public record Result(Kind kind, List<Group> groups, int total, String note) {}

    /**
     * 一个分组 ⇒ 顶部那一排里的一个标签页（含<b>机器方块图标</b>）。
     *
     * <p>图标的三个来源，按"离用户看到的那台机器最近"排：
     * <ol>
     *   <li>{@code GTRecipeType#getIconSupplier()} —— GT 注册机器时塞进去的那个方块；</li>
     *   <li>遍历 {@code GTRegistries.MACHINES}，找第一个声称能做这个类型的机器；</li>
     *   <li>都拿不到 ⇒ {@code hasIcon=false}，界面画一个灰框（<b>绝不画一个错的图标</b>）。</li>
     * </ol>
     */
    public static Tab tabOf(Group g, boolean selected) {
        boolean has = false;
        int iconId = 0;
        final ItemStack icon = iconOf(g.typeId());
        if (icon != null && !icon.isEmpty()) {
            final ResourceLocation iid = BuiltInRegistries.ITEM.getKey(icon.getItem());
            if (iid != null) {
                has = true;
                iconId = BuiltInRegistries.ITEM.getId(icon.getItem());
            }
        }
        return new Tab(g.typeName(), has, iconId, g.recipes().size(), selected);
    }

    /** 这个类型用哪个方块当图标（三次尝试，拿不到返回 null）。 */
    public static ItemStack iconOf(ResourceLocation typeId) {
        if (typeId == null) {
            return null;
        }
        final ItemStack cached = ICON_CACHE.get(typeId);
        if (cached != null) {
            return cached;
        }
        ItemStack found = null;
        try {
            final GTRecipeType t = GTRegistries.RECIPE_TYPES.get(typeId);
            if (t != null) {
                final java.util.function.Supplier<ItemStack> sup = t.getIconSupplier();
                if (sup != null) {
                    final ItemStack s = sup.get();
                    if (s != null && !s.isEmpty()) {
                        found = s;
                    }
                }
            }
        } catch (Throwable ignored) {
            found = null;
        }
        if (found == null) {
            try {
                for (MachineDefinition def : GTRegistries.MACHINES.values()) {
                    if (def == null) {
                        continue;
                    }
                    final GTRecipeType[] types = def.getRecipeTypes();
                    if (types == null) {
                        continue;
                    }
                    boolean hit = false;
                    for (GTRecipeType t : types) {
                        if (t != null && typeId.equals(t.registryName)) {
                            hit = true;
                            break;
                        }
                    }
                    if (!hit) {
                        continue;
                    }
                    final ItemStack s = def.asStack();
                    if (s != null && !s.isEmpty()) {
                        found = s;
                        break;
                    }
                }
            } catch (Throwable ignored) {
                found = null;
            }
        }
        ICON_CACHE.put(typeId, found == null ? ItemStack.EMPTY : found);
        return found;
    }

    // ------------------------------------------------------------------ 标签页两侧同步

    public static void writeTab(FriendlyByteBuf buf, Tab t) {
        buf.writeUtf(t.label() == null ? "" : t.label());
        buf.writeBoolean(t.hasIcon());
        buf.writeVarInt(t.iconId());
        buf.writeVarInt(t.count());
        buf.writeBoolean(t.selected());
    }

    public static Tab readTab(FriendlyByteBuf buf) {
        return new Tab(buf.readUtf(), buf.readBoolean(), buf.readVarInt(), buf.readVarInt(), buf.readBoolean());
    }

    /** 客户端把标签页的图标解回物品栈（注册表里查不到 ⇒ null，界面画灰框）。 */
    public static ItemStack iconStackOf(Tab t) {
        if (t == null || !t.hasIcon()) {
            return null;
        }
        try {
            final Item item = BuiltInRegistries.ITEM.byId(t.iconId());
            if (item == null || item == net.minecraft.world.item.Items.AIR) {
                return null;
            }
            return new ItemStack(item);
        } catch (Throwable e) {
            return null;
        }
    }

    private ShanhaiRecipeQuery() {}

    // ------------------------------------------------------------------ 四种查询

    /** ① 获取途径：哪些配方的【输出】里有这个物品。 */
    public static Result byOutput(MinecraftServer server, Item item) {
        if (server == null || item == null) {
            return empty(Kind.SOURCE, "没有物品");
        }
        final List<GTRecipe> hits = ShanhaiRecipeReverseIndex.queryByOutput(server, item);
        // 🆕 2026-10-05（用户报「通过获取途径等搜索是搜索不到工作台的配方的」）：
        //    非 GT 配方**不在** GT 的反查索引里（那 5 处扫描的第一句就是 `instanceof GTRecipe`），
        //    所以要单独扫一遍非 GT 表，再把两组并进同一个结果。
        final List<ShanhaiVanillaRecipeView> van = vanillaHits(server, item, true);
        return groupMixed(Kind.SOURCE, hits, van,
                "物品 " + idOf(item) + " 出现在 " + (hits.size() + van.size()) + " 条配方的【输出】里"
                        + "（GT " + hits.size() + " 条 · 非 GT " + van.size() + " 条）");
    }

    /** ② 作为物品的用处：哪些配方的【输入】里有这个物品。 */
    public static Result byInput(MinecraftServer server, Item item) {
        if (server == null || item == null) {
            return empty(Kind.USE, "没有物品");
        }
        final List<GTRecipe> hits = ShanhaiRecipeReverseIndex.query(server, item);
        final List<ShanhaiVanillaRecipeView> van = vanillaHits(server, item, false);
        return groupMixed(Kind.USE, hits, van,
                "物品 " + idOf(item) + " 出现在 " + (hits.size() + van.size()) + " 条配方的【输入】里"
                        + "（GT " + hits.size() + " 条 · 非 GT " + van.size() + " 条）");
    }

    /**
     * 🆕 2026-10-06（第 14 刀）<b>流体版的两个查询</b>。
     *
     * <h4>用户原话（逐字）</h4>
     * <blockquote>「还有一个很严重的问题，就是我在第一面中无法拖动流体到查询物品框中，<b>流体也是需要查询的</b>」</blockquote>
     *
     * <h4>口径（与物品侧逐条对齐，不同的地方只有一处）</h4>
     * <ul>
     *   <li>分组、排序、截断三层<b>完全复用</b> {@link #group}（流体没有"非 GT"那一半：原版/工作台那一族
     *       不存在流体输入 ⇒ <b>不会</b>去扫 {@link ShanhaiVanillaRecipeTable}，也不用 {@code groupMixed}）；</li>
     *   <li>键 = {@link Fluid} 本体（不带 NBT/数量/chance；<b>不区分"消耗"与"催化剂"</b>）；
     *       完整语义写在 {@code ShanhaiRecipeReverseIndex.BY_FLUID_IN} 的类文档里；</li>
     *   <li>note 里把"GT N 条 · 非 GT 0 条"这件事<b>写明</b>，免得用户以为漏了工作台。</li>
     * </ul>
     */
    public static Result byOutputFluid(MinecraftServer server, net.minecraft.world.level.material.Fluid fluid) {
        if (server == null || fluid == null) {
            return empty(Kind.SOURCE, "没有流体");
        }
        final List<GTRecipe> hits = ShanhaiRecipeReverseIndex.queryFluidOutput(server, fluid);
        return group(Kind.SOURCE, hits,
                "流体 " + fluidIdOf(fluid) + " 出现在 " + hits.size() + " 条配方的【输出】里"
                        + "（GT " + hits.size() + " 条 · 非 GT 0 条 —— 原版配方没有流体输入）");
    }

    /** 流体版的「作为物品的用处」：哪些配方的【输入】里用到这种流体。 */
    public static Result byInputFluid(MinecraftServer server, net.minecraft.world.level.material.Fluid fluid) {
        if (server == null || fluid == null) {
            return empty(Kind.USE, "没有流体");
        }
        final List<GTRecipe> hits = ShanhaiRecipeReverseIndex.queryFluidInput(server, fluid);
        return group(Kind.USE, hits,
                "流体 " + fluidIdOf(fluid) + " 出现在 " + hits.size() + " 条配方的【输入】里"
                        + "（GT " + hits.size() + " 条 · 非 GT 0 条 —— 原版配方没有流体输入）");
    }

    /** 流体 id（与物品侧 {@link #idOf} 同口径的读数用字符串）。 */
    public static String fluidIdOf(net.minecraft.world.level.material.Fluid fluid) {
        if (fluid == null) {
            return "?";
        }
        final ResourceLocation id = BuiltInRegistries.FLUID.getKey(fluid);
        return id == null ? "?" : id.toString();
    }

    /**
     * 🆕 <b>非 GT 那一边的命中</b>（工作台 / 熔炉 / 切石机 / 锻造台 …）。
     *
     * <p>为什么单独扫：GT 的反查索引 {@link ShanhaiRecipeReverseIndex} 的 5 处扫描循环
     * 第一句都是 {@code if (!(r instanceof GTRecipe gt)) continue;} ⇒ 非 GT 配方一条都进不去。
     * 本方法走的是 {@link ShanhaiVanillaRecipeTable} 那份<b>已经维护好的</b>索引
     * （类型 → 配方 id ＋ id → 活对象），所以是一次哈希查表，不是全表线性扫。
     *
     * @param byOutput true = 看产物；false = 看输入（用 {@link Ingredient#test} 判，
     *                 所以<b>标签入料也能命中</b>）
     */
    private static List<ShanhaiVanillaRecipeView> vanillaHits(MinecraftServer server, Item item,
                                                              boolean byOutput) {
        final List<ShanhaiVanillaRecipeView> out = new ArrayList<>();
        if (server == null || item == null) {
            return out;
        }
        try {
            ShanhaiVanillaRecipeTable.captureIfAbsent(server);
            if (!ShanhaiVanillaRecipeTable.isIndexBuilt()) {
                ShanhaiVanillaRecipeTable.rebuildIndex(server);
            }
            final ItemStack probe = new ItemStack(item);
            for (ResourceLocation t : ShanhaiVanillaRecipeTable.types()) {
                for (ResourceLocation id : ShanhaiVanillaRecipeTable.idsOf(t)) {
                    final net.minecraft.world.item.crafting.Recipe<?> r =
                            ShanhaiVanillaRecipeTable.liveById(id);
                    if (r == null) {
                        continue;
                    }
                    final ShanhaiVanillaRecipeView v = ShanhaiVanillaRecipeView.of(r);
                    if (v == null) {
                        continue;
                    }
                    if (byOutput) {
                        if (!v.result.isEmpty() && v.result.getItem() == item) {
                            out.add(v);
                        }
                        continue;
                    }
                    boolean hit = false;
                    for (net.minecraft.world.item.crafting.Ingredient in : v.inputs) {
                        if (in == null || in.isEmpty()) {
                            continue;
                        }
                        try {
                            if (in.test(probe)) {
                                hit = true;
                                break;
                            }
                        } catch (Throwable ignored) {
                            // 单个 ingredient 判不了不影响别的
                        }
                    }
                    if (hit) {
                        out.add(v);
                    }
                }
            }
        } catch (Throwable t) {
            ShanhaiMod.LOGGER.error("{} query_vanilla_hits_failed item={} err={}",
                    PREFIX, idOf(item), t.toString());
        }
        return out;
    }

    /**
     * ③ 作为机器的用处：这个物品<b>当机器</b>时能跑哪些配方。
     *
     * <p>做法：遍历 {@code GTRegistries.MACHINES}，挑出"它的物品就是这个物品"的机器定义，
     * 取它们声明的 {@link MachineDefinition#getRecipeTypes()}，再把这些类型下的配方全列出来。
     *
     * <p>⚠️ 一个物品可能<b>不是机器</b>（绝大多数物品都不是）⇒ 那时返回零组，
     * 界面显示"这个物品不是机器"的空态，<b>绝不编数据</b>。
     */
    public static Result asMachine(MinecraftServer server, Item item) {
        if (server == null || item == null) {
            return empty(Kind.MACHINE, "没有物品");
        }
        final Set<ResourceLocation> typeIds = new LinkedHashSet<>();
        final List<String> machineNames = new ArrayList<>();
        int defs = 0;
        try {
            for (MachineDefinition def : GTRegistries.MACHINES.values()) {
                if (def == null) {
                    continue;
                }
                try {
                    final ItemStack stack = def.asStack();
                    if (stack == null || stack.isEmpty() || stack.getItem() != item) {
                        continue;
                    }
                } catch (Throwable ignored) {
                    continue;
                }
                defs++;
                try {
                    machineNames.add(ShanhaiRecipeEditorSession.shortId(def.getId()));
                } catch (Throwable ignored) {
                    // 名字取不到不影响功能
                }
                try {
                    final GTRecipeType[] types = def.getRecipeTypes();
                    if (types == null) {
                        continue;
                    }
                    for (GTRecipeType t : types) {
                        if (t != null && t.registryName != null) {
                            typeIds.add(t.registryName);
                        }
                    }
                } catch (Throwable ignored) {
                    // 单个机器读不出类型 ⇒ 跳过（不许因此整条链失败）
                }
            }
        } catch (Throwable t) {
            ShanhaiMod.LOGGER.warn("{} query_machine_scan_failed item={} err={}", PREFIX, idOf(item), t.toString());
        }
        if (typeIds.isEmpty()) {
            return empty(Kind.MACHINE, "这个物品不是 GT 机器（" + idOf(item)
                    + " 没有任何机器定义）⇒ 它没有可以跑的配方");
        }
        final List<GTRecipe> hits = new ArrayList<>();
        for (GTRecipe r : ShanhaiRecipeReverseIndex.all()) {
            final GTRecipeType t = r.getType();
            if (t != null && t.registryName != null && typeIds.contains(t.registryName)) {
                hits.add(r);
            }
        }
        return group(Kind.MACHINE, hits, "机器 " + idOf(item) + "（" + defs + " 个定义"
                + (machineNames.isEmpty() ? "" : "：" + String.join("/", clipList(machineNames)))
                + "）能跑 " + typeIds.size() + " 种配方，共 " + hits.size() + " 条");
    }

    /**
     * ④ 文本搜索：匹配【配方 id】与【配方种类名/id】，中英文都吃。
     *
     * <h4>中文怎么吃</h4>
     * 种类名走 {@link ShanhaiRecipeEditorSession#typeName} —— 它的三级口径是
     * <b>活的语言表 → {@code assets/<modid>/lang/zh_cn.json} → 原始 id</b>
     * ⇒ 用户输入「压模器」也能命中，不是只认英文 id。
     */
    public static Result byText(MinecraftServer server, String query) {
        final String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        if (server == null || q.isEmpty()) {
            return empty(Kind.TEXT, "请输入关键词");
        }
        ShanhaiRecipeReverseIndex.ensure(server);
        final List<GTRecipe> hits = new ArrayList<>();
        final Set<ResourceLocation> matchedTypes = new LinkedHashSet<>();
        // 种类名只算一次（一个类型的名字是确定的，重复算纯浪费）
        final Map<ResourceLocation, String> typeNames = new LinkedHashMap<>();
        final Map<ResourceLocation, String> typeNamesLower = new LinkedHashMap<>();
        // 🆕 第 7 轮：**语言文件里的中文名**单独再算一份。
        //    🔴 为什么要单独一份：单机里 `Component.translatable(...)` 就是客户端语言（中文），
        //    但**无头专服**没有客户端语言 ⇒ 它给出的是英文，中文名就永远命中不了
        //    （冒烟第一版读数：zh_type_name=Macerator —— 那其实是英文）。
        //    用户点名的口径是「中文匹配要能吃语言文件里的中文名，别只匹配英文 id」
        //    ⇒ 这里直接读 `assets/<modid>/lang/zh_cn.json`，与"活的语言表"两条并集匹配。
        final Map<ResourceLocation, String> zhNames = new LinkedHashMap<>();
        int n = ShanhaiRecipeReverseIndex.size();
        for (int i = 0; i < n; i++) {
            final GTRecipe r = ShanhaiRecipeReverseIndex.recipeAt(i);
            if (r == null) {
                continue;
            }
            final ResourceLocation typeId = ShanhaiRecipeReverseIndex.typeIdAt(i);
            String zhLower = null;
            if (typeId != null) {
                String zh = typeNames.get(typeId);
                if (zh == null) {
                    zh = ShanhaiRecipeEditorSession.typeName(typeId.getPath()).text();
                    typeNames.put(typeId, zh);
                    typeNamesLower.put(typeId, zh.toLowerCase(Locale.ROOT));
                }
                zhLower = typeNamesLower.get(typeId);
            }
            final String lowerId = ShanhaiRecipeReverseIndex.lowerIdAt(i);
            boolean hit = lowerId != null && lowerId.contains(q);
            if (!hit && typeId != null) {
                hit = typeId.toString().toLowerCase(Locale.ROOT).contains(q)
                        || (zhLower != null && !zhLower.isEmpty() && zhLower.contains(q));
            }
            if (!hit && typeId != null) {
                // 语言文件里的中文名（单机与专服都能读到的一份）
                String zhFile = zhNames.get(typeId);
                if (zhFile == null) {
                    zhFile = zhCnNameOf(typeId);
                    zhNames.put(typeId, zhFile);
                }
                hit = !zhFile.isEmpty() && zhFile.contains(q);
            }
            if (hit) {
                hits.add(r);
                if (typeId != null) {
                    matchedTypes.add(typeId);
                }
                if (hits.size() >= MAX_RESULTS) {
                    break;
                }
            }
        }
        final String note = "关键词「" + query.trim() + "」命中 " + hits.size() + " 条配方、"
                + matchedTypes.size() + " 种配方种类（匹配口径：配方 id ＋ 种类名（活语言表 ∪ zh_cn 语言文件）＋ 种类 id）"
                + (hits.size() >= MAX_RESULTS ? "（已截断到上限 " + MAX_RESULTS + "）" : "");
        return group(Kind.TEXT, hits, note);
    }

    /**
     * <b>直接读语言文件</b>拿这个配方种类的中文名（小写）。
     *
     * <p>走 {@code ShanhaiLangLookup.zhCn("gtceu." + path)} —— 它读的是各 mod jar 里的
     * {@code assets/<modid>/lang/zh_cn.json}，所以<b>无头专服也能出中文</b>
     * （单机时这条与"活的语言表"内容一致，两条并集匹配不会互相干扰）。
     * 拿不到返回空串（<b>绝不编一个中文名</b>）。
     */
    public static String zhCnNameOf(ResourceLocation typeId) {
        try {
            final String zh = com.shanhai.common.text.ShanhaiLangLookup.zhCn(
                    ShanhaiRecipeEditorSession.TYPE_LANG_PREFIX + typeId.getPath());
            if (zh == null || zh.isEmpty()) {
                return "";
            }
            return com.shanhai.common.text.ShanhaiTextParser.stripStyleCode(zh)
                    .toLowerCase(Locale.ROOT);
        } catch (Throwable t) {
            return "";
        }
    }

    private static List<String> clipList(List<String> in) {
        return in.size() <= 4 ? in : in.subList(0, 4);
    }

    private static String idOf(Item item) {
        final ResourceLocation id = BuiltInRegistries.ITEM.getKey(item);
        return id == null ? "?" : id.toString();
    }

    private static Result empty(Kind kind, String note) {
        return new Result(kind, List.of(), 0, note);
    }

    /**
     * 把一票配方<b>按种类分组</b>（组内按 id 排序，组间按条数从多到少）。
     *
     * <p>这就是界面上"上面那一排"的来源：一排 = 一组 = 一种配方种类。
     */
    public static Result group(Kind kind, List<GTRecipe> hits, String note) {
        final Map<ResourceLocation, List<ResourceLocation>> byType = new LinkedHashMap<>();
        final Map<ResourceLocation, String> names = new LinkedHashMap<>();
        int total = 0;
        for (GTRecipe r : hits) {
            if (r == null || r.id == null) {
                continue;
            }
            final GTRecipeType t = r.getType();
            final ResourceLocation key = t == null || t.registryName == null
                    ? new ResourceLocation("minecraft", "unknown") : t.registryName;
            byType.computeIfAbsent(key, k -> new ArrayList<>()).add(r.id);
            if (!names.containsKey(key)) {
                names.put(key, ShanhaiRecipeEditorSession.typeName(
                        t == null ? null : t).text());
            }
            total++;
        }
        return finish(kind, byType, names, total, note);
    }

    /**
     * 🆕 把 <b>GT 与非 GT 两组命中并成同一个结果</b>（分组口径完全相同）。
     *
     * <p>两边的"类型 id"取法不同，但出来的都是同一个口径的 {@code ResourceLocation}：
     * GT 那边是 {@code r.getType().registryName}（如 {@code gtceu:assembler}），
     * 非 GT 那边是 {@code BuiltInRegistries.RECIPE_TYPE} 的键（如 {@code minecraft:crafting}）。
     * ⇒ 分组、排序、截断三层都能共用 {@link #finish}。
     */
    public static Result groupMixed(Kind kind, List<GTRecipe> gtHits,
                                    List<ShanhaiVanillaRecipeView> vanillaHits, String note) {
        final Map<ResourceLocation, List<ResourceLocation>> byType = new LinkedHashMap<>();
        final Map<ResourceLocation, String> names = new LinkedHashMap<>();
        int total = 0;
        if (gtHits != null) {
            for (GTRecipe r : gtHits) {
                if (r == null || r.id == null) {
                    continue;
                }
                final GTRecipeType t = r.getType();
                final ResourceLocation key = t == null || t.registryName == null
                        ? new ResourceLocation("minecraft", "unknown") : t.registryName;
                byType.computeIfAbsent(key, k -> new ArrayList<>()).add(r.id);
                if (!names.containsKey(key)) {
                    names.put(key, ShanhaiRecipeEditorSession.typeName(t).text());
                }
                total++;
            }
        }
        if (vanillaHits != null) {
            for (ShanhaiVanillaRecipeView v : vanillaHits) {
                if (v == null || v.id == null) {
                    continue;
                }
                final ResourceLocation key = v.typeId == null
                        ? new ResourceLocation("minecraft", "unknown") : v.typeId;
                byType.computeIfAbsent(key, k -> new ArrayList<>()).add(v.id);
                if (!names.containsKey(key)) {
                    // 非 GT 类型的中文名走"那个工作台方块的名字"那一条口径（不是硬编码中文）
                    names.put(key, ShanhaiVanillaRecipeView.typeLabel(key));
                }
                total++;
            }
        }
        return finish(kind, byType, names, total, note);
    }

    /** 分组 → 排序 → 截断（{@link #group} 与 {@link #groupMixed} 共用这一段）。 */
    private static Result finish(Kind kind, Map<ResourceLocation, List<ResourceLocation>> byType,
                                 Map<ResourceLocation, String> names, int total, String note) {
        final List<Group> groups = new ArrayList<>();
        for (Map.Entry<ResourceLocation, List<ResourceLocation>> e : byType.entrySet()) {
            final List<ResourceLocation> list = e.getValue();
            list.sort(Comparator.comparing(ResourceLocation::toString));
            groups.add(new Group(e.getKey(), names.get(e.getKey()), list));
        }
        groups.sort(Comparator.comparingInt((Group g) -> -g.recipes().size())
                .thenComparing(g -> g.typeId().toString()));
        if (groups.size() > MAX_GROUPS) {
            groups.subList(MAX_GROUPS, groups.size()).clear();
        }
        return new Result(kind, List.copyOf(groups), total, note);
    }

    // ------------------------------------------------------------------ 卡片

    /**
     * 🆕 2026-10-05（工作台 / 原版配方）：<b>一条非 GT 配方的卡片</b>。
     *
     * <p>与 {@link #cardOf(GTRecipe, boolean)} 同一个口径（图标顺序 ＝ 物品在前、流体在后），
     * 只是数据来源换成 {@link ShanhaiVanillaRecipeView}：
     * 输入 = 每一格的<b>代表物品</b>（取 {@code Ingredient.getItems()} 的第一个候选），
     * 输出 = 那一个产物，{@code duration} = 烧炼时间（没有时间的类型给 0）。
     *
     * <p>⚠️ 如实交代一处<b>信息损失</b>：带标签的输入（一个 {@code Ingredient} 有多个候选）在卡片上
     * 只画得出<b>第一个</b>代表物品。第三屏那一栏也是同一份代表物品 ——
     * 这一版输入只读，所以不会因为"看起来只有一个"而被误改。
     */
    public static Card cardOfVanilla(ShanhaiVanillaRecipeView v, boolean selected) {
        if (v == null) {
            return null;
        }
        final List<Chip> ins = new ArrayList<>();
        final int[] insMore = new int[1];
        final List<Chip> outs = new ArrayList<>();
        final int[] outsMore = new int[1];
        for (ItemStack st : v.representativeInputs()) {
            if (st == null || st.isEmpty()) {
                continue;
            }
            if (ins.size() >= MAX_CHIPS) {
                insMore[0]++;
                continue;
            }
            ins.add(new Chip(true, BuiltInRegistries.ITEM.getId(st.getItem()), Math.max(0, st.getCount())));
        }
        if (!v.result.isEmpty()) {
            outs.add(new Chip(true, BuiltInRegistries.ITEM.getId(v.result.getItem()),
                    Math.max(0, v.result.getCount())));
        }
        final int cook = v.canEditCooking() ? Math.max(0, v.cookingTime) : 0;
        return new Card(v.id, ShanhaiRecipeEditorSession.shortId(v.id), v.typeLabel(),
                List.copyOf(ins), List.copyOf(outs), insMore[0], outsMore[0],
                cook, cook, 0L, -1, 0, selected);
    }

    /**
     * 一条配方 ⇒ 一张卡片。
     *
     * <p>图标顺序与编辑器里那 24 个格子<b>同一个口径</b>：物品在前、流体在后
     * （见 {@link ShanhaiIoTable#fromRecipe}）。
     */
    public static Card cardOf(GTRecipe r, boolean selected) {
        final List<Chip> ins = new ArrayList<>();
        final int[] insMore = new int[1];
        final List<Chip> outs = new ArrayList<>();
        final int[] outsMore = new int[1];
        collect(r, true, true, ins, insMore);
        collect(r, true, false, outs, outsMore);
        collect(r, false, true, ins, insMore);
        collect(r, false, false, outs, outsMore);

        final long eut = ShanhaiRecipeIoApply.euOf(r);
        final int tier = ShanhaiRecipeEditorWorkspace.tierOf(eut);
        return new Card(
                r.id,
                ShanhaiRecipeEditorSession.shortId(r.id),
                ShanhaiRecipeEditorSession.typeName(r.getType()).text(),
                List.copyOf(ins), List.copyOf(outs), insMore[0], outsMore[0],
                r.duration, ShanhaiRecipeDuration.originalOf(r), eut, tier,
                ShanhaiRecipeEditorWorkspace.amperageOf(eut, tier),
                selected);
    }

    /**
     * 一个侧（输入/输出）上的某一类（物品/流体）的图标。
     *
     * <h2>🔴 第 11 刀：「图标数 ＋ N ＝ 这一侧真实的格数」—— 一次实测把这个式子定死了</h2>
     * 用户点名表扬过这个折叠（原话：「这个超出4个格子直接变成+3+6什么的是个不错的设计，
     * 既不会占用太多空间，也能一眼看出这是什么配方」）⇒ 它的算术<b>不许说谎</b>。
     *
     * <p>老写法是"先判放不放得下、再去取代表物"，于是取不出代表物的那些<b>两处都不算</b>：
     * 本机实测（{@code case=card_plus_n}，2000 条）抓到 5 条这样的配方，例如
     * {@code thetornproductionline:fragment_world_collection/advanced_world_fragment_gtlcore_world_fragments_nether}
     * —— 输入侧真实 3 格，卡片上却是「2 个图标 ＋ +0」：<b>凭空少了一格，用户看不出来</b>。
     *
     * <p>⇒ 现在的口径（也是判据）：{@code out.size() + more[0] == contents.size()}，
     * 即 <b>N = 这一侧真实的格数 − 画出来的图标数</b>。
     * 取不出代表物的那些（标签入料在那个时点一个物品都没匹配上）<b>也是真实的格子</b>
     * （第三屏那一格也在，只是空的）⇒ 算进 N，不许凭空少。
     */
    private static void collect(GTRecipe r, boolean item, boolean inputs,
                                List<Chip> out, int[] more) {
        final List<Content> contents;
        try {
            contents = inputs
                    ? r.getInputContents(item ? ItemRecipeCapability.CAP : FluidRecipeCapability.CAP)
                    : r.getOutputContents(item ? ItemRecipeCapability.CAP : FluidRecipeCapability.CAP);
        } catch (Throwable t) {
            return;
        }
        if (contents == null) {
            return;
        }
        for (Content c : contents) {
            final Chip chip = chipOf(c, item, inputs);
            if (chip == null) {
                continue;   // 画不出图标，但它是【一个真实的格子】⇒ 由下面那一行算进 N
            }
            if (out.size() >= MAX_CHIPS) {
                more[0]++;
                continue;
            }
            out.add(chip);
        }
        // 🔴 N 的下界 = 真实格数 − 已画出的图标数（把"画不出来"的那些也补进 N）
        more[0] = Math.max(more[0], contents.size() - out.size());
    }

    /**
     * 一条 {@link Content} ⇒ 一个 {@link Chip}（画不出来就返回 {@code null}，调用方<b>不</b>记账）。
     *
     * @param inputs 是不是输入侧 —— 只有输入侧的 {@code chance == 0} 才叫"催化剂"（见 {@link Chip#catalyst()}）
     */
    private static Chip chipOf(Content c, boolean item, boolean inputs) {
        if (item) {
            final ItemStack stack = ShanhaiIoTable.representativeItem(c);
            if (stack == null || stack.isEmpty()) {
                return null;
            }
            final ResourceLocation id = BuiltInRegistries.ITEM.getKey(stack.getItem());
            if (id == null) {
                return null;
            }
            // 电路号从【这一格的物品】上取（NBT 就在这里，造卡片这一步就地取走）
            return new Chip(true, BuiltInRegistries.ITEM.getId(stack.getItem()),
                    Math.max(0, stack.getCount()),
                    ShanhaiIoTable.circuitOf(stack),
                    inputs && ShanhaiIoTable.isNotConsumable(c));
        }
        final FluidStack fs = ShanhaiIoTable.representativeFluid(c);
        if (fs == null || fs.isEmpty()) {
            return null;
        }
        final ResourceLocation id = BuiltInRegistries.FLUID.getKey(fs.getFluid());
        if (id == null) {
            return null;
        }
        return new Chip(false, BuiltInRegistries.FLUID.getId(fs.getFluid()),
                (int) Math.min(Integer.MAX_VALUE, Math.max(0L, fs.getAmount())),
                NO_CIRCUIT,
                inputs && ShanhaiIoTable.isNotConsumable(c));
    }

    // ------------------------------------------------------------------ 两侧同步

    /**
     * 一张卡片写出去（解码见 {@link #readCard}）。
     *
     * <p>🔴 数量一律走 {@code writeVarInt}：{@code FriendlyByteBuf.writeItem} 里数量是
     * {@code writeByte}（上一轮实测：99999 会变成 159）⇒ 卡片上"×576 / ×1024"这类读数
     * 不能走那条路。
     */
    public static void writeCard(FriendlyByteBuf buf, Card c) {
        buf.writeUtf(c.id() == null ? "" : c.id().toString());
        buf.writeUtf(c.shortId() == null ? "" : c.shortId());
        buf.writeUtf(c.typeName() == null ? "" : c.typeName());
        writeChips(buf, c.ins());
        buf.writeVarInt(c.insMore());
        writeChips(buf, c.outs());
        buf.writeVarInt(c.outsMore());
        buf.writeVarInt(c.duration());
        buf.writeVarInt(c.original());
        buf.writeVarLong(c.eut());
        buf.writeVarInt(c.tierIndex());
        buf.writeVarInt(c.amperage());
        buf.writeBoolean(c.selected());
    }

    public static Card readCard(FriendlyByteBuf buf) {
        final String id = buf.readUtf();
        final String shortId = buf.readUtf();
        final String typeName = buf.readUtf();
        final List<Chip> ins = readChips(buf);
        final int insMore = buf.readVarInt();
        final List<Chip> outs = readChips(buf);
        final int outsMore = buf.readVarInt();
        final int dur = buf.readVarInt();
        final int orig = buf.readVarInt();
        final long eut = buf.readVarLong();
        final int tier = buf.readVarInt();
        final int amps = buf.readVarInt();
        final boolean sel = buf.readBoolean();
        return new Card(id.isEmpty() ? null : ResourceLocation.tryParse(id), shortId, typeName,
                ins, outs, insMore, outsMore, dur, orig, eut, tier, amps, sel);
    }

    /**
     * 一个图标格写出去（解码见 {@link #readChips}）。
     *
     * <p>🔴 第 11 刀新增两个字段（电路号 / 催化剂）。两端是<b>同一个 jar</b>，不存在旧客户端
     * 读新格式的问题 ⇒ <b>不升任何版本号</b>（{@code schema_version} 是覆盖文件那边的，与网络无关，没动）。
     */
    private static void writeChips(FriendlyByteBuf buf, List<Chip> chips) {
        buf.writeVarInt(chips == null ? 0 : chips.size());
        if (chips == null) {
            return;
        }
        for (Chip c : chips) {
            buf.writeBoolean(c.item());
            buf.writeVarInt(c.id());
            buf.writeVarInt(c.count());
            buf.writeVarInt(c.circuit());
            buf.writeBoolean(c.catalyst());
        }
    }

    private static List<Chip> readChips(FriendlyByteBuf buf) {
        final int n = buf.readVarInt();
        if (n <= 0) {
            return List.of();
        }
        final List<Chip> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            out.add(new Chip(buf.readBoolean(), buf.readVarInt(), buf.readVarInt(),
                    buf.readVarInt(), buf.readBoolean()));
        }
        return out;
    }

    // ------------------------------------------------------------------ 客户端解码

    /**
     * 客户端把 {@link Chip} 变成能画的东西（物品栈 / 流体栈）。
     *
     * <p>注册表里查不到（服务端与客户端版本不一致等）⇒ 返回 null，界面画一个占位方框，
     * <b>绝不画成一个错误的图标</b>。
     */
    public static ItemStack itemOf(Chip c) {
        if (c == null || !c.item()) {
            return null;
        }
        try {
            final Item item = BuiltInRegistries.ITEM.byId(c.id());
            if (item == null || item == net.minecraft.world.item.Items.AIR) {
                return null;
            }
            return new ItemStack(item, Math.max(1, c.count()));
        } catch (Throwable t) {
            return null;
        }
    }

    public static FluidStack fluidOf(Chip c) {
        if (c == null || c.item()) {
            return null;
        }
        try {
            final Fluid f = BuiltInRegistries.FLUID.byId(c.id());
            if (f == null) {
                return null;
            }
            final FluidStack fs = FluidStack.create(f, Math.max(1, c.count()));
            return fs == null || fs.isEmpty() ? null : fs;
        } catch (Throwable t) {
            return null;
        }
    }
}
