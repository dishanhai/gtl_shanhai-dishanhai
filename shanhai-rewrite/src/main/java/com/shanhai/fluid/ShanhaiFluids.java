package com.shanhai.fluid;

import com.shanhai.ShanhaiMod;
import com.shanhai.registry.ShanhaiRegistration;
import com.tterrag.registrate.util.entry.FluidEntry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.material.Fluid;
import net.minecraftforge.fluids.ForgeFlowingFluid;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * B2 批次的 <b>25 个流体</b> + <b>25 个桶物品</b>，全部注册进 jar（不经 KubeJS）。
 *
 * <h2>🔴 为什么桶必须连带流体一起注册</h2>
 * Forge 里 {@code BucketItem} 的构造器要求一个
 * {@code Supplier<? extends Fluid>}；只注册桶不注册流体 ⇒ 拿到的就是"空桶"
 * （无对应流体、模型取不到贴图）。所以本类以 <b>流体</b> 为第一主体，
 * 桶由 Registrate 的 {@code FluidBuilder} 自动派生，桶 id 恒为
 * {@code shanhai:<流体id>_bucket}（{@code FluidBuilder} 构造器里
 * {@code bucketName = sourceName + "_bucket"}，javap -c 已核实）。
 *
 * <h2>🔴 用了哪条注册链（本轮首次出现流体）</h2>
 * 仍是 <b>全工程唯一的</b>{@link ShanhaiRegistration#REGISTRATE}（{@code GTRegistrate}），
 * 调用它<b>继承自 {@code AbstractRegistrate} 的</b>
 * {@code fluid(String, ResourceLocation, ResourceLocation)} —— javap 已确认
 * {@code GTRegistrate} <b>没有覆写</b>这个方法（它只新增了 static {@code fluid(6 参)}
 * 与实例 {@code createFluid(...)}，前者要 GTCEu 的 {@code Material}，本批不用）。
 * ⇒ <b>没有第二个 GTRegistrate、没有第二套 DeferredRegister</b>，不是新基建。
 *
 * <h2>🔴 链条里【绝不能】再调 defaultBlock() / bucket()（本轮第一次真启动换来的）</h2>
 * {@code FluidBuilder.create(...)} 内部<b>已经</b>串好了
 * {@code .defaultLang().defaultSource().defaultBlock().defaultBucket()}
 * （javap -c 已核实：偏移 19/22/25/28 依次 invokevirtual 这四个）。
 * 再自己调一次 {@code defaultBlock()} 会当场抛
 * {@code IllegalStateException: Cannot set a default block after a custom block has been created}
 * —— 首次冒烟就是这样崩在 mod 构造期的（{@code ShanhaiRegistry.init:116} →
 * {@code ShanhaiFluids.<clinit>} → {@code FluidBuilder.defaultBlock(:399)}）。
 * 所以本类只补一个 {@code .lang(...)}，其余全走 Registrate 的默认。
 * 连带结论：<b>桶句柄拿不到静态 {@code ItemEntry}</b>（桶由 {@code register()} 内部派生），
 * 只能按名字懒解析 —— 见 {@link #bucketStack(String)}。
 *
 * <h2>贴图</h2>
 * 沿用命名口径【甲·保持原贴图名】：文件落在
 * {@code assets/shanhai/textures/block/fluid/<原文件名>.png}（含 {@code .png.mcmeta}），
 * 文件名与源目录逐字节一致。流体名 {@code id} 与贴图名不是一一对应
 * （{@code light} 的贴图叫 {@code light_fluid.png}），所以两者的映射在本类里<b>逐条显式写出</b>。
 *
 * <h2>显示名</h2>
 * 键形 {@code item.shanhai.<桶id>} + {@code fluid_type.shanhai.<流体id>} / {@code fluid.*} /
 * {@code block.*}，值见 {@code assets/shanhai/lang/zh_cn.json} / {@code en_us.json}。
 */
public final class ShanhaiFluids {

    /** 流体贴图目录（{@code assets/shanhai/textures/} 之下）。 */
    private static final String TEX_DIR = "block/fluid/";

    // ==================================================================
    // B2 · 25 个流体 + 25 个桶（2026-09-23 批次）
    // 来源：_evidence/register-inventory/SH清单解析与实施计划.md §2.2 / §5.1
    // 贴图名 ≠ 流体名的 1 条：light → light_fluid.png / light_fluid_flow.png
    // ==================================================================

    /** 流体 `zero_point_energy`（真空零点能）· 贴图 `zero_point_energy.png`（flow `zero_point_energy_flow.png`）· 桶 `shanhai:zero_point_energy_bucket`（真空零点能桶）。 */
    public static final FluidEntry<ForgeFlowingFluid.Flowing> ZERO_POINT_ENERGY =
            fluid("zero_point_energy", "zero_point_energy", "zero_point_energy_flow", "真空零点能");

    /** 流体 `light`（光）· 贴图 `light_fluid.png`（flow `light_fluid_flow.png`）· 桶 `shanhai:light_bucket`（光桶）。 */
    public static final FluidEntry<ForgeFlowingFluid.Flowing> LIGHT =
            fluid("light", "light_fluid", "light_fluid_flow", "光");

    /** 流体 `liquid_ending`（液态终末）· 贴图 `liquid_ending.png`（flow `liquid_ending_flow.png`）· 桶 `shanhai:liquid_ending_bucket`（液态终末桶）。 */
    public static final FluidEntry<ForgeFlowingFluid.Flowing> LIQUID_ENDING =
            fluid("liquid_ending", "liquid_ending", "liquid_ending_flow", "液态终末");

    // ==================================================================
    // 🔴 2026-10-03 用户点单：**14 个物质流的注册顺序改成与「物质模块等级」同序**
    //    （新序见下；id 与中文名一个字节都没动，只改了字段（=注册）顺序）
    //
    //    新序 = entry(入门,Lv1) → foundation(基础,Lv2) → basic(推演,Lv3) → virtual(虚像,Lv4)
    //           → advanced(重组,Lv5) → zero(归零,Lv6) → darkstar(暗星,Lv7) → transition(虚数跃迁,Lv8)
    //           → transmutation(嬗变,Lv9) → ascension(升维,Lv10) → peak(巅峰,Lv11)
    //           → transcend(超限,Lv13) → eternal(永恒,Lv14) → ultimate(创造,Lv15)
    //    ⚠️ Lv12 混沌 / Lv16 现实锚点 / Lv17 创始现实修改**没有对应流体**（保持没有，不编造）。
    //
    //    旧序（作废，逐字留档）：entry, foundation, basic, virtual, **transmutation, darkstar,
    //        advanced, transition, zero, peak, ascension, transcend**, eternal, ultimate
    //      ⇒ 本次移动的只有 5 个：advanced / zero / darkstar / transmutation / ascension。
    //    （2026-10-01 创造栏那边已经把「物质流桶」按模块顺序排过一轮；本轮把**注册顺序**也对齐，
    //      两处同序 ⇒ 以后不再出现"注册序与显示序对不上"。）
    // ==================================================================

    /** 流体 `matter_fluid_entry`（入门物质流）· 贴图 `matter_fluid_entry.png`（🔴 源目录无 flow ⇒ flowingTexture 指回 still（避免缺失贴图））· 桶 `shanhai:matter_fluid_entry_bucket`（入门物质流桶）。 */
    public static final FluidEntry<ForgeFlowingFluid.Flowing> MATTER_FLUID_ENTRY =
            fluid("matter_fluid_entry", "matter_fluid_entry", "matter_fluid_entry", "入门物质流");

    /** 流体 `matter_fluid_foundation`（基础物质流）· 贴图 `matter_fluid_foundation.png`（🔴 源目录无 flow ⇒ flowingTexture 指回 still（避免缺失贴图））· 桶 `shanhai:matter_fluid_foundation_bucket`（基础物质流桶）。 */
    public static final FluidEntry<ForgeFlowingFluid.Flowing> MATTER_FLUID_FOUNDATION =
            fluid("matter_fluid_foundation", "matter_fluid_foundation", "matter_fluid_foundation", "基础物质流");

    /** 流体 `matter_fluid_basic`（推演物质流）· 贴图 `matter_fluid_basic.png`（flow `matter_fluid_basic_flow.png`）· 桶 `shanhai:matter_fluid_basic_bucket`（初级物质流桶）。 */
    public static final FluidEntry<ForgeFlowingFluid.Flowing> MATTER_FLUID_BASIC =
            fluid("matter_fluid_basic", "matter_fluid_basic", "matter_fluid_basic_flow", "推演物质流");

    /** 流体 `matter_fluid_virtual`（虚像物质流）· 贴图 `matter_fluid_virtual.png`（flow `matter_fluid_virtual_flow.png`）· 桶 `shanhai:matter_fluid_virtual_bucket`（虚像物质流桶）。 */
    public static final FluidEntry<ForgeFlowingFluid.Flowing> MATTER_FLUID_VIRTUAL =
            fluid("matter_fluid_virtual", "matter_fluid_virtual", "matter_fluid_virtual_flow", "虚像物质流");

    /** 流体 `matter_fluid_advanced`（重组物质流）· 贴图 `matter_fluid_advanced.png`（flow `matter_fluid_advanced_flow.png`）· 桶 `shanhai:matter_fluid_advanced_bucket`（高级物质流桶）。 */
    public static final FluidEntry<ForgeFlowingFluid.Flowing> MATTER_FLUID_ADVANCED =
            fluid("matter_fluid_advanced", "matter_fluid_advanced", "matter_fluid_advanced_flow", "重组物质流");

    /** 流体 `matter_fluid_zero`（归零物质流）· 贴图 `matter_fluid_zero.png`（🔴 源目录无 flow ⇒ flowingTexture 指回 still（避免缺失贴图））· 桶 `shanhai:matter_fluid_zero_bucket`（归零物质流桶）。 */
    public static final FluidEntry<ForgeFlowingFluid.Flowing> MATTER_FLUID_ZERO =
            fluid("matter_fluid_zero", "matter_fluid_zero", "matter_fluid_zero", "归零物质流");

    /** 流体 `matter_fluid_darkstar`（暗星物质流）· 贴图 `matter_fluid_darkstar.png`（flow `matter_fluid_darkstar_flow.png`）· 桶 `shanhai:matter_fluid_darkstar_bucket`（暗星物质流桶）。 */
    public static final FluidEntry<ForgeFlowingFluid.Flowing> MATTER_FLUID_DARKSTAR =
            fluid("matter_fluid_darkstar", "matter_fluid_darkstar", "matter_fluid_darkstar_flow", "暗星物质流");

    /** 流体 `matter_fluid_transition`（虚数跃迁物质流）· 贴图 `matter_fluid_transition.png`（flow `matter_fluid_transition_flow.png`）· 桶 `shanhai:matter_fluid_transition_bucket`（虚数跃迁物质流桶）。 */
    public static final FluidEntry<ForgeFlowingFluid.Flowing> MATTER_FLUID_TRANSITION =
            fluid("matter_fluid_transition", "matter_fluid_transition", "matter_fluid_transition_flow", "虚数跃迁物质流");

    /** 流体 `matter_fluid_transmutation`（嬗变物质流）· 贴图 `matter_fluid_transmutation.png`（🔴 源目录无 flow ⇒ flowingTexture 指回 still（避免缺失贴图））· 桶 `shanhai:matter_fluid_transmutation_bucket`（嬗变物质流桶）。 */
    public static final FluidEntry<ForgeFlowingFluid.Flowing> MATTER_FLUID_TRANSMUTATION =
            fluid("matter_fluid_transmutation", "matter_fluid_transmutation", "matter_fluid_transmutation", "嬗变物质流");

    /** 流体 `matter_fluid_ascension`（升维物质流）· 贴图 `matter_fluid_ascension.png`（🔴 源目录无 flow ⇒ flowingTexture 指回 still（避免缺失贴图））· 桶 `shanhai:matter_fluid_ascension_bucket`（升维物质流桶）。 */
    public static final FluidEntry<ForgeFlowingFluid.Flowing> MATTER_FLUID_ASCENSION =
            fluid("matter_fluid_ascension", "matter_fluid_ascension", "matter_fluid_ascension", "升维物质流");

    /** 流体 `matter_fluid_peak`（巅峰物质流）· 贴图 `matter_fluid_peak.png`（flow `matter_fluid_peak_flow.png`）· 桶 `shanhai:matter_fluid_peak_bucket`（巅峰物质流桶）。 */
    public static final FluidEntry<ForgeFlowingFluid.Flowing> MATTER_FLUID_PEAK =
            fluid("matter_fluid_peak", "matter_fluid_peak", "matter_fluid_peak_flow", "巅峰物质流");

    /** 流体 `matter_fluid_transcend`（超限物质流）· 贴图 `matter_fluid_transcend.png`（🔴 源目录无 flow ⇒ flowingTexture 指回 still（避免缺失贴图））· 桶 `shanhai:matter_fluid_transcend_bucket`（超限物质流桶）。 */
    public static final FluidEntry<ForgeFlowingFluid.Flowing> MATTER_FLUID_TRANSCEND =
            fluid("matter_fluid_transcend", "matter_fluid_transcend", "matter_fluid_transcend", "超限物质流");

    /** 流体 `matter_fluid_eternal`（永恒物质流）· 贴图 `matter_fluid_eternal.png`（🔴 源目录无 flow ⇒ flowingTexture 指回 still（避免缺失贴图））· 桶 `shanhai:matter_fluid_eternal_bucket`（永恒物质流桶）。 */
    public static final FluidEntry<ForgeFlowingFluid.Flowing> MATTER_FLUID_ETERNAL =
            fluid("matter_fluid_eternal", "matter_fluid_eternal", "matter_fluid_eternal", "永恒物质流");

    /** 流体 `matter_fluid_ultimate`（创造物质流）· 贴图 `matter_fluid_ultimate.png`（flow `matter_fluid_ultimate_flow.png`）· 桶 `shanhai:matter_fluid_ultimate_bucket`（终极物质流桶）。 */
    public static final FluidEntry<ForgeFlowingFluid.Flowing> MATTER_FLUID_ULTIMATE =
            fluid("matter_fluid_ultimate", "matter_fluid_ultimate", "matter_fluid_ultimate_flow", "创造物质流");

    /** 流体 `primal_chaos`（原初混沌）· 贴图 `primal_chaos.png`（flow `primal_chaos_flow.png`）· 桶 `shanhai:primal_chaos_bucket`（原初混沌桶）。 */
    public static final FluidEntry<ForgeFlowingFluid.Flowing> PRIMAL_CHAOS =
            fluid("primal_chaos", "primal_chaos", "primal_chaos_flow", "原初混沌");

    /** 流体 `dimensional_fabric`（维度织构）· 贴图 `dimensional_fabric.png`（flow `dimensional_fabric_flow.png`）· 桶 `shanhai:dimensional_fabric_bucket`（维度织构桶）。 */
    public static final FluidEntry<ForgeFlowingFluid.Flowing> DIMENSIONAL_FABRIC =
            fluid("dimensional_fabric", "dimensional_fabric", "dimensional_fabric_flow", "维度织构");

    /** 流体 `causal_essence`（因果精髓）· 贴图 `causal_essence.png`（flow `causal_essence_flow.png`）· 桶 `shanhai:causal_essence_bucket`（因果精髓桶）。 */
    public static final FluidEntry<ForgeFlowingFluid.Flowing> CAUSAL_ESSENCE =
            fluid("causal_essence", "causal_essence", "causal_essence_flow", "因果精髓");

    /** 流体 `stabilized_eternity`（稳态永恒）· 贴图 `stabilized_eternity.png`（flow `stabilized_eternity_flow.png`）· 桶 `shanhai:stabilized_eternity_bucket`（稳态永恒桶）。 */
    public static final FluidEntry<ForgeFlowingFluid.Flowing> STABILIZED_ETERNITY =
            fluid("stabilized_eternity", "stabilized_eternity", "stabilized_eternity_flow", "稳态永恒");

    /** 流体 `chaos_fluid`（永恒混沌）· 贴图 `chaos_fluid.png`（flow `chaos_fluid_flow.png`）· 桶 `shanhai:chaos_fluid_bucket`（永恒混沌桶）。 */
    public static final FluidEntry<ForgeFlowingFluid.Flowing> CHAOS_FLUID =
            fluid("chaos_fluid", "chaos_fluid", "chaos_fluid_flow", "永恒混沌");

    /** 流体 `wl_catalyst`（世线光刻催化剂）· 贴图 `wl_catalyst.png`（🔴 源目录无 flow ⇒ flowingTexture 指回 still（避免缺失贴图））· 桶 `shanhai:wl_catalyst_bucket`（世线光刻催化剂桶）。 */
    public static final FluidEntry<ForgeFlowingFluid.Flowing> WL_CATALYST =
            fluid("wl_catalyst", "wl_catalyst", "wl_catalyst", "世线光刻催化剂");

    /** 流体 `universal_coolant`（寰宇联合冷却液）· 贴图 `universal_coolant.png`（flow `universal_coolant_flow.png`）· 桶 `shanhai:universal_coolant_bucket`（寰宇联合冷却液桶）。 */
    public static final FluidEntry<ForgeFlowingFluid.Flowing> UNIVERSAL_COOLANT =
            fluid("universal_coolant", "universal_coolant", "universal_coolant_flow", "寰宇联合冷却液");

    /** 流体 `spacetime`（时空流体）· 贴图 `spacetime.png`（flow `spacetime_flow.png`）· 桶 `shanhai:spacetime_bucket`（时空流体桶）。 */
    public static final FluidEntry<ForgeFlowingFluid.Flowing> SPACETIME =
            fluid("spacetime", "spacetime", "spacetime_flow", "时空流体");

    /**
     * 触发本类静态初始化（进而把 25 个流体 + 25 个桶交给 REGISTRATE）。
     * 必须由 {@code com.shanhai.registry.ShanhaiRegistry#init()} 调用，
     * 且与 {@code ShanhaiItems.init()} 同样处在 mod 构造期 ——
     * 流体/物品都是 Forge 原生注册表（{@code ForgeRegistries.FLUIDS/ITEMS}），
     * 由 Registrate 在 {@code RegisterEvent} 里落盘，构造期入列即可，
     * <b>不受</b> GTCEu {@code GTRegistry} 那套冻结窗口的约束（与配方类型/机器不同）。
     */
    public static void init() {
        // 静态字段初始化即注册；本方法只为「显式触达类」而存在，不要在别处再塞逻辑。
    }

    /**
     * 按名字拿桶物品的栈（{@code shanhai:<fluidId>_bucket}）。
     *
     * <p>⚠️ <b>懒解析，只能在注册表填充之后调用</b>（例如创造栏的
     * {@code displayItems} 生成器里）—— 构造期拿不到，所以本类不暴露静态
     * {@code ItemEntry} 字段（原因见类注释「链条里绝不能再调 defaultBlock()/bucket()」）。
     *
     * @return 桶的默认栈；桶不存在时返回 {@link ItemStack#EMPTY}（调用方负责跳过，别放进创造栏）
     */
    public static ItemStack bucketStack(String fluidId) {
        Item item = ForgeRegistries.ITEMS.getValue(new ResourceLocation(ShanhaiMod.MOD_ID, fluidId + "_bucket"));
        return item == null ? ItemStack.EMPTY : item.getDefaultInstance();
    }

    /** 按名字拿流体句柄（同样只在注册表填充之后可用）。 */
    public static Fluid fluid(String fluidId) {
        return ForgeRegistries.FLUIDS.getValue(new ResourceLocation(ShanhaiMod.MOD_ID, fluidId));
    }

    /**
     * 一个流体（+ 它自动派生的流体类型 / 流动方块 / 桶），共用同一个形状。
     *
     * @param id        流体 id（注册为 {@code shanhai:<id>}；桶自动为 {@code shanhai:<id>_bucket}）
     * @param stillBase still 贴图基线名（= 源目录里 {@code <stillBase>.png}）
     * @param flowBase  flow 贴图基线名（源目录没有 flow 时传 {@code stillBase}）
     * @param fluidName 流体显示名（写进 lang，键 {@code fluid_type.shanhai.<id>} 等）
     */
    private static FluidEntry<ForgeFlowingFluid.Flowing> fluid(
            String id, String stillBase, String flowBase, String fluidName) {
        return ShanhaiRegistration.REGISTRATE
                .fluid(id, tex(stillBase), tex(flowBase))
                .lang(fluidName)
                .register();
    }

    /** 流体贴图的 ResourceLocation（成品路径 = {@code assets/shanhai/textures/block/fluid/<base>.png}）。 */
    private static ResourceLocation tex(String base) {
        return new ResourceLocation(ShanhaiMod.MOD_ID, TEX_DIR + base);
    }

    private ShanhaiFluids() {}
}
