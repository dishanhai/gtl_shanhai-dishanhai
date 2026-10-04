package com.shanhai.item;

import com.gregtechceu.gtceu.api.machine.MachineDefinition;
import com.gregtechceu.gtceu.api.machine.MultiblockMachineDefinition;
import com.shanhai.fluid.ShanhaiFluids;
import com.shanhai.machine.ShanhaiMachines;
import com.shanhai.machine.module.ModuleRegistry;
import com.shanhai.machine.wildcard.ShanhaiWildcardMachines;
import com.shanhai.registry.ShanhaiRegistration;
import com.tterrag.registrate.util.entry.ItemEntry;
import com.tterrag.registrate.util.entry.RegistryEntry;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;

/**
 * 本 mod 自己的创造模式物品栏（{@code shanhai}）—— 18 个物品 + B1a 的 131 个私货物品 + 主机控制器的归属。
 *
 * <h2>🔴 为什么必须有它（不是"好看而已"）</h2>
 * <b>JEI 的物品表是从创造模式物品栏建的。</b>取证（反编译 JEI 15.49.0.188 的
 * {@code mezz.jei.library.plugins.vanilla.ingredients.ItemStackListFactory#create}）：
 * <pre>
 *   for (CreativeModeTab tab : CreativeModeTabs.tabs()) {
 *       if (tab.getType() != CreativeModeTab.Type.CATEGORY) continue;
 *       tab.buildContents(displayParameters);
 *       ... addFromTab(tab.getDisplayItems()) / addFromTab(tab.getSearchTabDisplayItems()) ...
 *   }
 *   if (showHidden) { addItemsFromRegistries(...); }   // ← 只有开了"显示隐藏物品"才会兜底扫注册表
 * </pre>
 * 也就是说：<b>不在任何 CATEGORY 物品栏里的物品，JEI 搜不到</b>
 * （除非用户在 JEI 配置里打开了 showHiddenIngredients，默认关闭）。
 * 本 mod 之前 18 个物品全部没有归属栏 ⇒ 创造栏看不见、JEI 也搜不到。
 *
 * <p>⛔ <b>已作废的旧段落描述（逐字留档，供对照）</b>：
 * <pre>
 *   <h2>🔴 2026-10-03 用户点单：本栏 displayItems 的<b>段落顺序整体重排</b></h2>
 *   新序 = 6 段（段号即用户给的清单序号）：
 *   <ol>
 *     <li><b>段 1</b> 主机 + 27 台原初模块（主机打头，27 台 = {@code ModuleRegistry.SPECS} 顺序连成一块）
 *         + 其余机器/方块物品（排在它们之后，不插在 27 台之间）；</li>
 *     <li><b>段 2</b> 17 个物质模块（新档位 1→17）+ <b>段 2b</b> 与之一一对应的 14 个物质流桶；</li>
 *     <li><b>段 3</b> 世线族（6 个世线本体 + 世线晶核/太初世线之种 + 16 块世线板 + wem 1~4 +
 *         世线残片 7 档 + 寰宇并行超限器）；</li>
 *     <li><b>段 4</b> 粒子（保持原相对顺序）；</li>
 *     <li><b>段 5</b> 材料 / 容器 / 其余物品（含其余 11 个桶）；</li>
 *     <li><b>段 6</b> 调试与彩蛋（置尾）。</li>
 *   </ol>
 *
 * </pre>
 * <p>🔴 <b>为什么"机器排在物品前面"必须落在这一栏、而不是注册顺序里</b>：
 * Forge 物品注册表的数字 id 顺序由<b>入列时间</b>决定，而机器（{@code MultiblockMachineBuilder}）是在
 * GTCEu 的 {@code MachineDefinition RegisterEvent} 里才入列的，<b>晚于</b> mod 构造期的
 * {@code ShanhaiItems.init()} ⇒ 注册顺序里机器<b>必然排在全部物品之后</b>
 * （证据：{@code ShanhaiMachines} 类注释「本方法跑在 GTCEu 的 MachineDefinition RegisterEvent 里，
 * <b>早于</b> Forge 的方块 RegisterEvent」）。而用户看的 JEI 顺序 = <b>本栏</b>的顺序
 * （见上面那段 {@code ItemStackListFactory} 取证）⇒ 需求在本栏兑现。
 * <p>⚠️ 本次改动<b>只挪位置</b>：id、配方、显示名、tooltip 一个都没有改；逐项对账见
 * {@code temp/reorder/verify-order.txt}（改前/改后各 210 条 accept，排序后集合 diff 为空、条数相同）。
 *
 * <h2>写法出处（照抄 GTCEu 自己的 GTCreativeModeTabs，不发明 API）</h2>
 * 反编译 {@code com.gregtechceu.gtceu.common.data.GTCreativeModeTabs:52}：
 * <pre>
 *   GTRegistration.REGISTRATE
 *       .defaultCreativeTab("material_fluid", builder -&gt; builder
 *           .displayItems(new RegistrateDisplayItemsGenerator("material_fluid", GTRegistration.REGISTRATE))
 *           .icon(() -&gt; GTItems.FLUID_CELL.asStack())
 *           .title(GTRegistration.REGISTRATE.addLang("itemGroup", GTCEu.id("material_fluid"), "...")))
 *       .register();
 * </pre>
 * 本类沿用同一入口 {@code GTRegistrate.defaultCreativeTab(String, Consumer&lt;CreativeModeTab.Builder&gt;)}
 * （继承自 {@code AbstractRegistrate}，javap 已确认签名；GTRegistrate 另覆写了
 * {@code protected createCreativeModeTab(P, String, Consumer)}，用来做 tab 的注册表绑定）。
 * <p><b>与 GTCEu 的差别只有一处、且是故意的</b>：GTCEu 用
 * {@code RegistrateDisplayItemsGenerator} 依赖 {@code GTRegistrate.isInCreativeTab()}
 * （内建 {@code TAB_LOOKUP}），本 mod 的物品是自己列的，直接<b>显式列举</b>更确定 ——
 * 不依赖"注册时 currentTab 恰好是这个栏"这条隐式时序。
 *
 * <h2>lang</h2>
 * 标题走 {@code Component.translatable("itemGroup.shanhai.shanhai")}，中文名在
 * {@code assets/shanhai/lang/zh_cn.json}（与 18 个物品名的存放位置一致）。
 */
public final class ShanhaiCreativeModeTabs {

    /** 物品栏标题的语言键；条目在 {@code assets/shanhai/lang/zh_cn.json}。 */
    public static final String SHANHAI_TAB_LANG_KEY = "itemGroup.shanhai.shanhai";

    /**
     * 本 mod 唯一的创造模式物品栏。静态初始化即完成 Registrate 入列。
     * <p>⚠️ 本栏的 {@code displayItems} 生成器在<b>构建章节目录时</b>才被调用
     * （客户端打开创造栏 / JEI 启动 / 服务端建 {@code CreativeModeTab} 内容），
     * 所以 lambda 里引用 {@link ShanhaiItems}、{@link ShanhaiMachines} 都是安全的
     * —— 那时两类早已初始化完毕。
     */
    public static final RegistryEntry<CreativeModeTab> SHANHAI = ShanhaiRegistration.REGISTRATE
            .defaultCreativeTab("shanhai", builder -> builder
                    .title(Component.translatable(SHANHAI_TAB_LANG_KEY))
                    // 图标用"原初引擎核心"：它是本 mod 最有辨识度的一个。
                    // 用 Supplier 形式（CreativeModeTab.Builder.icon），取栈时机晚于注册时机。
                    .icon(() -> ShanhaiItems.PRIMORDIAL_ENGINE_CORE.asStack())
                    .displayItems(ShanhaiCreativeModeTabs::fill))
            .register();

    /**
     * 触发本类静态初始化（进而注册物品栏）。必须由
     * {@code com.shanhai.registry.ShanhaiRegistry#init()} 调用，且顺序上先于
     * {@code ShanhaiItems.init()}（虽然本类不依赖那个顺序，但先建栏后放东西更直观）。
     */
    public static void init() {
        // 静态字段初始化即注册；本方法只为「显式触达类」而存在。
    }

    /**
     * 本栏的 displayItems —— <b>序 = 前 4 组（🔴 冻结）+ 后半段 7 个桶（⑤~⑪）</b>；
     * 逐桶成员与顺序见类注释的「🔴 2026-10-03 用户点单：后半段分 7 个桶」一节。
     *
     * <p>⛔ <b>旧 javadoc（已作废，逐字留档）</b>：
     * <pre>
     *   17 个物质模块（等级 1..17，顺序 = ShanhaiItems 的字段声明顺序）
     *   + 原初引擎核心 + 主机控制器。
     * </pre>
     * —— 这段描述的是<b>重排之前</b>的段落结构（那时模块在整栏中段、引擎核心紧跟在它们后面、
     * 主机在最后）。现在：主机 + 27 台模块在<b>最前</b>，17 个物质模块在<b>第二段</b>，
     * {@code primordial_engine_core} 落在<b>第五段</b>（材料/容器，它是普通物品、不是机器定义）。
     *
     * <p><b>为什么主机与机器也要放进来</b>：主机物品 {@code shanhai:primordial_omega_engine} 与
     * 26 台模块的物品都由 {@code MultiblockMachineBuilder} 注册，它们同样没有别的归属栏
     * ⇒ 不放进来，JEI 里就<b>搜不到</b>（同一条 JEI 机制）。
     * 用 {@code != null} 兜底：注册失败时宁可少一格，也绝不让生成器抛异常
     * —— JEI 对抛异常的栏是<b>整栏丢弃</b>（{@code ItemStackListFactory} 里的
     * {@code catch (RuntimeException e) { continue; }}），那会把整栏物品一起变成搜不到。
     */
    private static void fill(CreativeModeTab.ItemDisplayParameters parameters, CreativeModeTab.Output output) {
        MultiblockMachineDefinition engine = ShanhaiMachines.primordialOmegaEngine();
        MachineDefinition superWildcardBuffer = ShanhaiWildcardMachines.superWildcardPatternBuffer();

        // ==================================================================
        // 段 1 · 主机 + 26 台原初模块 + 机器/方块物品 + 通配符总成（🔴 冻结，逐位未变）
        // 主机打头；26 台 = ModuleRegistry 的注册顺序连成一块，中间不许被别的物品插进来。
        // 🔴 机器必须在这一栏里才会出现在 JEI（不在 CATEGORY 物品栏里的物品 JEI 搜不到）。
        // ==================================================================

        if (engine != null) {
            output.accept(engine.asStack());
        }
        acceptIfPresent(output, ModuleRegistry.PRIMORDIAL_VOID_INDUCTION_ARMATURE);
        acceptIfPresent(output, ModuleRegistry.PRIMORDIAL_BIOLOGICAL_CORE);
        acceptIfPresent(output, ModuleRegistry.PRIMORDIAL_CHAOTIC_EPHEMERAL_DECONSTRUCTION_CRYSTALLIZATION_FURNACE);
        acceptIfPresent(output, ModuleRegistry.PRIMORDIAL_MATTER_RECOMBINATOR_CORE);
        acceptIfPresent(output, ModuleRegistry.PRIMORDIAL_CAUSAL_WEAVING_MATRIX);
        acceptIfPresent(output, ModuleRegistry.PRIMORDIAL_SINGULARITY_INVERSION_CORE);
        acceptIfPresent(output, ModuleRegistry.PRIMORDIAL_WORLD_FRAGMENTS_COLLECTOR);
        acceptIfPresent(output, ModuleRegistry.PRIMORDIAL_ANTI_ENTROPY_CONDENSATION_CORE);
        acceptIfPresent(output, ModuleRegistry.PRIMORDIAL_DIVERGENCE_GENERATOR);
        acceptIfPresent(output, ModuleRegistry.PRIMORDIAL_MATTER_CASTER);
        acceptIfPresent(output, ModuleRegistry.PRIMORDIAL_ASSEMBLY_LINE_MODULE);
        acceptIfPresent(output, ModuleRegistry.PRIMORDIAL_CRITICAL_PROCESSING_MODULE);
        acceptIfPresent(output, ModuleRegistry.PRIMORDIAL_MULTIDIMENSIONAL_IMPLOSION_CORE);
        acceptIfPresent(output, ModuleRegistry.PRIMORDIAL_SUPERCRITICAL_MATTER_GENERATION_CORE);
        acceptIfPresent(output, ModuleRegistry.PRIMORDIAL_COSMIC_REACTOR);
        acceptIfPresent(output, ModuleRegistry.PRIMORDIAL_MOLECULAR_RIFT_CORE);
        acceptIfPresent(output, ModuleRegistry.PRIMORDIAL_TIANQIONG_ASSEMBLY_CORE);
        acceptIfPresent(output, ModuleRegistry.PRIMORDIAL_ETERNAL_SMELTING_FURNACE);
        acceptIfPresent(output, ModuleRegistry.PRIMORDIAL_WORLDLINE_TRAVERSAL_MATRIX);
        acceptIfPresent(output, ModuleRegistry.PRIMORDIAL_QUANTUM_DISTORTION_MATRIX);
        acceptIfPresent(output, ModuleRegistry.PRIMORDIAL_SHAOGUANG_AGGREGATION_CORE);
        acceptIfPresent(output, ModuleRegistry.PRIMORDIAL_WEIYANG_RECONSTRUCTION_MODULE);
        acceptIfPresent(output, ModuleRegistry.PRIMORDIAL_ENGRAVING_MODULE);
        acceptIfPresent(output, ModuleRegistry.TAIXU_SMELTING_FURNACE);
        acceptIfPresent(output, ModuleRegistry.PRIMORDIAL_DEBUG_MODULE);
        acceptIfPresent(output, ModuleRegistry.PRIMORDIAL_CARBON_DECONSTRUCTION_CORE);
        // 🟢 2026-10-03 新增（用户点单）：原初引力干涉阵列（第 28 台模块）。
        //    放在本段末尾（与第 27 台相邻）——本段是"主机 + 全部原初模块"冻结段，
        //    位置口径与创建栏既有的"新模块追加在末尾"一致（不是 SPECS 顺序，是本段历史顺序）。
        acceptIfPresent(output, ModuleRegistry.PRIMORDIAL_GRAVITATIONAL_INTERFERENCE_ARRAY);
        // 🟢 2026-10-03 新增（用户点单）：原初深空汲取核心（第 29 台模块）。
        //    同样放在本段末尾（紧跟第 28 台「原初引力干涉阵列」之后）——本段是
        //    "主机 + 全部原初模块"冻结段，位置口径与创建栏既有的"新模块追加在末尾"一致
        //    （不是 SPECS 顺序，是本段历史顺序）。
        acceptIfPresent(output, ModuleRegistry.PRIMORDIAL_DEEP_SPACE_EXTRACTION_CORE);
        output.accept(ShanhaiItems.WORLDLINE_CRACKING_HUB.asStack());
        output.accept(ShanhaiItems.SPACETIME_WAVE_MATRIX.asStack());
        output.accept(ShanhaiItems.WORLD_LINE_STRIPPING_OSCILLATION_GENERATOR.asStack());
        output.accept(ShanhaiItems.BIG_TAG_FILTER_STOCK_BUS.asStack());
        output.accept(ShanhaiItems.MAINTENANCE_HATCH.asStack());
        output.accept(ShanhaiItems.NEBULA_SIPHON.asStack());
        output.accept(ShanhaiItems.ZERO_PHOTON_CONDENSER.asStack());
        output.accept(ShanhaiItems.BLACK_HOLE_CONTAINMENT.asStack());
        output.accept(ShanhaiItems.TIANJIE_NAVIGATION_TOWER.asStack());
        output.accept(ShanhaiItems.ULV_ZERO_POINT_CONVERSION.asStack());
        output.accept(ShanhaiItems.LV_ZERO_POINT_CONVERSION.asStack());
        output.accept(ShanhaiItems.MV_ZERO_POINT_CONVERSION.asStack());
        output.accept(ShanhaiItems.ULV_PHOTON_SIPHON.asStack());
        output.accept(ShanhaiItems.LV_PHOTON_SIPHON.asStack());
        output.accept(ShanhaiItems.MV_PHOTON_SIPHON.asStack());
        if (superWildcardBuffer != null) {
            output.accept(superWildcardBuffer.asStack());
        }

        // ==================================================================
        // 段 2 · 17 个物质模块（等级 1..17）
        // 顺序 = matterRows() 的行序 = PrimordialModuleMachine.MODULE_LEVELS 的等级序。
        // ==================================================================

        for (MatterRow row : matterRows()) {
            output.accept(row.module().asStack());
        }

        // ==================================================================
        // 段 3 · 世线族（🔴 冻结，逐位未变）
        // 6 个世线本体 + 世线晶核/太初世线之种 + 16 块世线板 + wem 1~4 + 世线残片 7 档 + 寰宇并行超限器。
        // ==================================================================

        output.accept(ShanhaiItems.DIMENSIONAL_WORLDLINE_FRAGMENT.asStack());
        output.accept(ShanhaiItems.WORLDLINE_RESIDUAL_FRAGMENT.asStack());
        output.accept(ShanhaiItems.WORLDLINE_DIVERGENT_CORE.asStack());
        output.accept(ShanhaiItems.WORLDLINE_BOUNDLESS_SINGULARITY.asStack());
        output.accept(ShanhaiItems.WORLDLINE_IMAGINARY_STRING.asStack());
        output.accept(ShanhaiItems.WORLDLINE_GENESIS_EMBRYO.asStack());
        output.accept(ShanhaiItems.WORLDLINE_CRYSTAL_CORE.asStack());
        output.accept(ShanhaiItems.PRIMORDIAL_WORLDLINE_SEED.asStack());
        output.accept(ShanhaiItems.WL_BOARD_ULV.asStack());
        output.accept(ShanhaiItems.WL_BOARD_LV.asStack());
        output.accept(ShanhaiItems.WL_BOARD_MV.asStack());
        output.accept(ShanhaiItems.WL_BOARD_HV.asStack());
        output.accept(ShanhaiItems.WL_BOARD_EV.asStack());
        output.accept(ShanhaiItems.WL_BOARD_IV.asStack());
        output.accept(ShanhaiItems.WL_BOARD_LUV.asStack());
        output.accept(ShanhaiItems.WL_BOARD_ZPM.asStack());
        output.accept(ShanhaiItems.WL_BOARD_UV.asStack());
        output.accept(ShanhaiItems.WL_BOARD_UHV.asStack());
        output.accept(ShanhaiItems.WL_BOARD_UEV.asStack());
        output.accept(ShanhaiItems.WL_BOARD_UIV.asStack());
        output.accept(ShanhaiItems.WL_BOARD_UXV.asStack());
        output.accept(ShanhaiItems.WL_BOARD_OPV.asStack());
        output.accept(ShanhaiItems.WL_BOARD_MAX.asStack());
        output.accept(ShanhaiItems.WL_BOARD_ETERNAL.asStack());
        output.accept(ShanhaiItems.WEM_1.asStack());
        output.accept(ShanhaiItems.WEM_2.asStack());
        output.accept(ShanhaiItems.WEM_3.asStack());
        output.accept(ShanhaiItems.WEM_4.asStack());
        output.accept(ShanhaiItems.THREAD_SHARD_1.asStack());
        output.accept(ShanhaiItems.THREAD_SHARD_2.asStack());
        output.accept(ShanhaiItems.THREAD_SHARD_3.asStack());
        output.accept(ShanhaiItems.THREAD_SHARD_4.asStack());
        output.accept(ShanhaiItems.THREAD_SHARD_5.asStack());
        output.accept(ShanhaiItems.THREAD_SHARD_6.asStack());
        output.accept(ShanhaiItems.THREAD_SHARD_7.asStack());
        output.accept(ShanhaiItems.UNIVERSAL_PARALLEL_OVERDRIVER.asStack());

        // ==================================================================
        // 段 4 · 粒子（🔴 冻结；FIRST_LIGHT 已按 ⑥ 逐光 5 件移出）
        // 保持原相对顺序（离子 → 夸克 → 轻子 → 玻色子 → 反物质 → 介子 → 重子 → 夸克态 → 假想粒子）。
        // ==================================================================

        output.accept(ShanhaiItems.PHOTON.asStack());
        output.accept(ShanhaiItems.HYDROGEN_ION.asStack());
        output.accept(ShanhaiItems.HELIUM_ION.asStack());
        output.accept(ShanhaiItems.GRAVITON.asStack());
        output.accept(ShanhaiItems.UP_QUARK.asStack());
        output.accept(ShanhaiItems.DOWN_QUARK.asStack());
        output.accept(ShanhaiItems.CHARM_QUARK.asStack());
        output.accept(ShanhaiItems.STRANGE_QUARK.asStack());
        output.accept(ShanhaiItems.BOTTOM_QUARK.asStack());
        output.accept(ShanhaiItems.TOP_QUARK.asStack());
        output.accept(ShanhaiItems.ELECTRON.asStack());
        output.accept(ShanhaiItems.ELECTRON_NEUTRINO.asStack());
        output.accept(ShanhaiItems.MUON.asStack());
        output.accept(ShanhaiItems.MUON_NEUTRINO.asStack());
        output.accept(ShanhaiItems.TAU.asStack());
        output.accept(ShanhaiItems.TAU_NEUTRINO.asStack());
        output.accept(ShanhaiItems.GLUON.asStack());
        output.accept(ShanhaiItems.PHOTON_RAINBOW.asStack());
        output.accept(ShanhaiItems.Z_BOSON.asStack());
        output.accept(ShanhaiItems.W_BOSON.asStack());
        output.accept(ShanhaiItems.HIGGS_BOSON.asStack());
        output.accept(ShanhaiItems.PROTON.asStack());
        output.accept(ShanhaiItems.NEUTRON.asStack());
        output.accept(ShanhaiItems.LAMBDA_PARTICLE.asStack());
        output.accept(ShanhaiItems.OMEGA_PARTICLE.asStack());
        output.accept(ShanhaiItems.PION.asStack());
        output.accept(ShanhaiItems.ETA_MESON.asStack());
        output.accept(ShanhaiItems.UNKNOWN_PARTICLE.asStack());
        output.accept(ShanhaiItems.POSITRON.asStack());
        output.accept(ShanhaiItems.ANTIPROTON.asStack());
        output.accept(ShanhaiItems.ANTIHYDROGEN.asStack());
        output.accept(ShanhaiItems.POSITRONIUM.asStack());
        output.accept(ShanhaiItems.MUONIUM.asStack());
        output.accept(ShanhaiItems.KAON.asStack());
        output.accept(ShanhaiItems.J_PSI_MESON.asStack());
        output.accept(ShanhaiItems.UPSILON_MESON.asStack());
        output.accept(ShanhaiItems.SIGMA_BARYON.asStack());
        output.accept(ShanhaiItems.XI_BARYON.asStack());
        output.accept(ShanhaiItems.LAMBDA_C_BARYON.asStack());
        output.accept(ShanhaiItems.LAMBDA_B_BARYON.asStack());
        output.accept(ShanhaiItems.TETRAQUARK.asStack());
        output.accept(ShanhaiItems.PENTAQUARK.asStack());
        output.accept(ShanhaiItems.STRANGELET.asStack());
        output.accept(ShanhaiItems.QUARK_GLUON_PLASMA.asStack());
        output.accept(ShanhaiItems.MAGNETIC_MONOPOLE.asStack());
        output.accept(ShanhaiItems.AXION.asStack());

        // ==================================================================
        // 桶 ⑤ · 夸克催化剂（2026-10-03 用户点单）
        // 内部按夸克种类排（上/下/奇/粲/顶/底），两个特例（空壳 / 非对齐）收尾。
        // ==================================================================

        output.accept(ShanhaiItems.UP_QUARK_EMISSION_CATALYST.asStack());
        output.accept(ShanhaiItems.DOWN_QUARK_EMISSION_CATALYST.asStack());
        output.accept(ShanhaiItems.STRANGE_QUARK_EMISSION_CATALYST.asStack());
        output.accept(ShanhaiItems.CHARM_QUARK_EMISSION_CATALYST.asStack());
        output.accept(ShanhaiItems.TOP_QUARK_EMISSION_CATALYST.asStack());
        output.accept(ShanhaiItems.BOTTOM_QUARK_EMISSION_CATALYST.asStack());
        output.accept(ShanhaiItems.CASING_EMPTY_QUARK_EMISSION_CATALYST.asStack());
        output.accept(ShanhaiItems.MISALIGNED_QUARK_EMISSION_CATALYST.asStack());

        // ==================================================================
        // 桶 ⑥ · 逐光 5 件（顺序铁律）（2026-10-03 用户点单）
        // 🔴 出场顺序铁律：初光 → 导航棱镜 → 逐光启航 → 星火意志 → 蓝星之子（不许调换）。
        // ==================================================================

        output.accept(ShanhaiItems.FIRST_LIGHT.asStack());
        output.accept(ShanhaiItems.NAVIGATE_PRISM.asStack());
        output.accept(ShanhaiItems.LIGHT_VOYAGE.asStack());
        output.accept(ShanhaiItems.STAR_SPARK.asStack());
        output.accept(ShanhaiItems.BLUE_SON.asStack());

        // ==================================================================
        // 桶 ⑦ · 5 个结局（2026-10-03 用户点单）
        // 万物崩灭·大撕裂 / 逆向坍缩·大反冲 / 万态平衡·大冻结·创世纪 / 桥与门 / 门与桥。
        // ==================================================================

        output.accept(ShanhaiItems.COLLAPSE_TEAR.asStack());
        output.accept(ShanhaiItems.BIG_TEAR.asStack());
        output.accept(ShanhaiItems.CSJ.asStack());
        output.accept(ShanhaiItems.BRIDGE_AND_GATE.asStack());
        output.accept(ShanhaiItems.GATE_AND_BRIDG.asStack());

        // ==================================================================
        // 桶 ⑧ · 太虚与终末线（2026-10-03 用户点单）
        // 太虚 4 件（晶核 → 液滴 → 尘埃 → 之上的丝线）在前，终末 6 件在后。
        // ==================================================================

        output.accept(ShanhaiItems.TAIXU_CRYSTAL_CORE.asStack());
        output.accept(ShanhaiItems.TAIXU_LIQUID_DROPLET.asStack());
        output.accept(ShanhaiItems.TAIXU_DUST.asStack());
        output.accept(ShanhaiItems.BEYOND_TAIXU_THREAD.asStack());
        output.accept(ShanhaiItems.PROLOGUE_OF_THE_END.asStack());
        output.accept(ShanhaiItems.HALO_END.asStack());
        output.accept(ShanhaiItems.FINALITY_CERTIFICATE.asStack());
        output.accept(ShanhaiItems.IDEAL_ASHES.asStack());
        output.accept(ShanhaiItems.GENESIS_SHARD.asStack());
        output.accept(ShanhaiItems.CENTRAL_FINITE_CURVE.asStack());

        // ==================================================================
        // 桶 ⑨ · 流体（一个连续的桶）（2026-10-03 用户点单）
        // 🔴 25 个桶连成一块：14 个物质流桶（顺序 = matterRows()）+ 原来那 11 个（各自内部顺序一个位置都没换）。
        // ==================================================================

        for (MatterRow row : matterRows()) {
            if (row.fluidId() != null) {
                acceptBucket(output, row.fluidId());
            }
        }
        acceptBucket(output, "zero_point_energy");
        acceptBucket(output, "light");
        acceptBucket(output, "liquid_ending");
        acceptBucket(output, "primal_chaos");
        acceptBucket(output, "dimensional_fabric");
        acceptBucket(output, "causal_essence");
        acceptBucket(output, "stabilized_eternity");
        acceptBucket(output, "chaos_fluid");
        acceptBucket(output, "wl_catalyst");
        acceptBucket(output, "universal_coolant");
        acceptBucket(output, "spacetime");

        // ==================================================================
        // 桶 ⑩ · 彩蛋物品（2026-10-03 用户点单）
        // 以设计页《02-配方设计.html》「只列名 · 彩蛋与测试（15 个）」那一节为准。
        // 该节里的「原初山海调试模块」是机器，留在冻结的段 1；「创始SOC晶圆」按用户第二轮裁定归 ⑪ ⇒ 本桶 13 件 item。
        // ==================================================================

        output.accept(ShanhaiItems.BLUE_ALIEN.asStack());
        output.accept(ShanhaiItems.KU_MING_YUAN_YANG.asStack());
        output.accept(ShanhaiItems.LONG_ZUI.asStack());
        output.accept(ShanhaiItems.PIGGY.asStack());
        output.accept(ShanhaiItems.FOOD.asStack());
        output.accept(ShanhaiItems.DISHANHAI.asStack());
        output.accept(ShanhaiItems.PLATINUM_GOD_PROOF.asStack());
        output.accept(ShanhaiItems.WANXIANG_CORE.asStack());
        output.accept(ShanhaiItems.GUIDE_BOOK.asStack());
        output.accept(ShanhaiItems.FISHBIG_SHARDS.asStack());
        output.accept(ShanhaiItems.ZWF.asStack());
        output.accept(ShanhaiItems.TEST_ITEM.asStack());
        output.accept(ShanhaiItems.TEST_DYNAMIC_TEXT.asStack());

        // ==================================================================
        // 桶 ⑪ · 其余物品（放最后）
        // 顺序 = 改前的相对顺序（不额外排序）。
        // ==================================================================

        output.accept(ShanhaiItems.PRIMORDIAL_ENGINE_CORE.asStack());
        output.accept(ShanhaiItems.ANNIHILATION_CORE.asStack());
        output.accept(ShanhaiItems.BHD_COLLAPSER.asStack());
        output.accept(ShanhaiItems.BHD_HYPER_SEED.asStack());
        output.accept(ShanhaiItems.COSMIC_DUST.asStack());
        output.accept(ShanhaiItems.CSHX.asStack());
        output.accept(ShanhaiItems.DARK_ENERGY_MULTIPLIER.asStack());
        output.accept(ShanhaiItems.DIMENSIONAL_FRAME.asStack());
        output.accept(ShanhaiItems.DIMENSIONAL_MATRIX.asStack());
        output.accept(ShanhaiItems.GRAVITATIONAL_LENS.asStack());
        output.accept(ShanhaiItems.HXSP.asStack());
        output.accept(ShanhaiItems.HYPERDIMENSIONAL_CALIBRATION_MATRIX.asStack());
        output.accept(ShanhaiItems.NOVA_CATALYST.asStack());
        output.accept(ShanhaiItems.PRIMORDIAL_PARALLEL_PARTICLE.asStack());
        output.accept(ShanhaiItems.REALITY_CORE.asStack());
        output.accept(ShanhaiItems.SINGULARITY_RING.asStack());
        output.accept(ShanhaiItems.SOC.asStack());
        output.accept(ShanhaiItems.ARTIFICIAL_NEUTRON_STAR.asStack());
        output.accept(ShanhaiItems.COSMIC_PROBE_MK.asStack());
        output.accept(ShanhaiItems.GOD_FORGE_MOD.asStack());
        output.accept(ShanhaiItems.GRAVITATIONAL_ANTENNA.asStack());
        output.accept(ShanhaiItems.GRAVITATIONAL_MEDIUM.asStack());
        output.accept(ShanhaiItems.GRAVITATIONAL_VIBRATION_STRING.asStack());
        output.accept(ShanhaiItems.JUDGMENT_LIMITER.asStack());
        output.accept(ShanhaiItems.MATTER_SINGULARITY.asStack());
        output.accept(ShanhaiItems.PRIMORDIAL_DIVERGENCE_HEART.asStack());
        output.accept(ShanhaiItems.STRONG_INTERACTION_DROPLET.asStack());
        output.accept(ShanhaiItems.TIME_REVERSAL_PROTOCOL.asStack());
        output.accept(ShanhaiItems.UNIVERSAL_PARALLEL_CORE.asStack());
        output.accept(ShanhaiItems.GRAVITON_SHARD.asStack());
        output.accept(ShanhaiItems.WORLD_FRAGMENTS_CREATION.asStack());
    }

    /**
     * 🔴 「物质模块 → 它对应的物质流」的<b>唯一真源</b>（2026-10-01 新增，用户点单）。
     *
     * <h2>1. 这张表的顺序 = 【物质模块在 JEI 里的显示顺序】= 等级 1..17</h2>
     * 「物质模块的顺序」的权威来源是<b>本表</b>。
     *
     * <p>⛔ <b>2026-10-03 订正（本段旧理由已作废，逐字留档）</b>：
     * <pre>
     *   旧原文：「而本表的顺序就是 fill() 里那 17 行的原顺序（老脚本 L970–L1135 的注册序，未改一个位置）。
     *           <b>两条独立旁证</b>证明它就是等级序：① 并行上限值按本表顺序<b>严格单调递增</b>
     *           （128 → 256 → … → Long.MAX_VALUE）⇒「等级」与「本表顺序」同序；② 用户原话…」
     * </pre>
     * <b>作废原因（两条都作废）</b>：
     * <ol>
     *   <li>「老脚本注册序未改一个位置」<b>不再成立</b> —— 2026-10-03 用户点单「物质模块按新档位重排」，
     *       本表已按<b>新等级 1..17</b> 重写（见下方 {@code // Lv.N} 标注）；</li>
     *   <li>旁证①「并行上限严格单调递增」<b>本来就不成立</b>：旧表第 3→4 档（强化档 2048→1024）
     *       是<b>回落</b>的（上游原值），靠它推「与等级同序」是错的。现在等级序有<b>唯一真源</b>
     *       {@code PrimordialModuleMachine.MODULE_LEVELS}，本表的顺序与它逐行对齐即可，不需要旁证。</li>
     * </ol>
     * <p>⚠️ 现行不变式：<b>本表的行序 = {@code PrimordialModuleMachine.MODULE_LEVELS} 的等级 1..17 序</b>，
     * 且由 {@code PrimordialModuleMachine#assertLevelCurve()} 在<b>注册期</b>用等级表反查并行值逐档断言
     * （抄错一档 ⇒ 加载期抛异常，不会静默）。
     *
     * <h2>2. 🔴 为什么模块与流体必须共用这一张表</h2>
     * 用户要的是「物质流按物质模块的顺序排」。若模块那份顺序与流体那份顺序各写一遍，
     * 将来加一台模块就<b>必然</b>出现两份顺序分叉（本工程血账：「同一个数被两处写一份，必然漂移」）
     * ⇒ 本方法返回的<b>同一张表</b>同时喂给 ①模块那一段 ②物质流桶那一段，顺序由构造保证。
     * <p>🔴 2026-10-03 起，{@code ShanhaiFluids} 里那 14 个物质流的<b>注册顺序</b>也按同一序排好了
     * （见该文件 2026-10-03 段注释）⇒ 注册序、创造栏序、模块等级序三者同序。
     *
     * <h2>3. 配对判据</h2>
     * 中文名字面呼应（入门↔入门、基础↔基础、推演↔推演、虚像↔虚像、重组↔重组、归零↔归零、
     * 暗星↔暗星、虚数跃迁↔虚数物质跃迁重塑、嬗变↔嬗变、升维↔升维、巅峰↔巅峰、
     * 超限↔超限、永恒↔永恒、创造↔物质创造）：<b>14 对 14，无歧义、无一对多</b>。
     * 17 台模块里另有 3 台<b>没有对应流体</b>（混沌 / 现实锚点 / 创始现实修改）⇒ {@code fluidId = null}，
     * <b>不编造</b>一条不存在的流体。
     *
     * <h2>4. 为什么是「方法」而不是「静态字段」</h2>
     * 静态字段会在 {@code ShanhaiCreativeModeTabs} 的类初始化期就去触碰 {@code ShanhaiItems} 的静态字段，
     * 从而把物品注册的时机提前到「创造栏注册之前」—— <b>那是一次没人要求过的初始化顺序变更</b>。
     * 写成方法 ⇒ 只在 {@code fill()} 被调用时（那时物品早已注册完）取表，<b>零时序影响</b>。
     */
    private static MatterRow[] matterRows() {
        return new MatterRow[] {
                new MatterRow(ShanhaiItems.INTRODUCTORY_MATERIAL_MODULE,                   "matter_fluid_entry"), // Lv.1
                new MatterRow(ShanhaiItems.BASIC_MATERIAL_MODULE,                          "matter_fluid_foundation"), // Lv.2
                new MatterRow(ShanhaiItems.MATERIAL_DEDUCTION_MODULE,                      "matter_fluid_basic"), // Lv.3
                new MatterRow(ShanhaiItems.VIRTUAL_IMAGE_MATERIAL_MODULE,                  "matter_fluid_virtual"), // Lv.4
                new MatterRow(ShanhaiItems.MATERIAL_RECOMBINATION_MODULE,                  "matter_fluid_advanced"), // Lv.5
                new MatterRow(ShanhaiItems.ZEROING_MATERIAL_MODULE,                        "matter_fluid_zero"), // Lv.6
                new MatterRow(ShanhaiItems.DARK_STAR_MATERIAL_MODULE,                      "matter_fluid_darkstar"), // Lv.7
                new MatterRow(ShanhaiItems.IMAGINARY_MATERIAL_TRANSITION_REMOLDING_MODULE, "matter_fluid_transition"), // Lv.8
                new MatterRow(ShanhaiItems.TRANSFORMATION_MATERIAL_MODULE,                 "matter_fluid_transmutation"), // Lv.9
                new MatterRow(ShanhaiItems.DIMENSIONAL_ASCENSION_MATERIAL_MODULE,          "matter_fluid_ascension"), // Lv.10
                new MatterRow(ShanhaiItems.APEX_MATERIAL_MODULE,                           "matter_fluid_peak"), // Lv.11
                new MatterRow(ShanhaiItems.CHAOS_MATERIAL_MODULE,                          null), // Lv.12
                new MatterRow(ShanhaiItems.TRANSFINITE_MATERIAL_MODULE,                    "matter_fluid_transcend"), // Lv.13
                new MatterRow(ShanhaiItems.ETERNAL_MATERIAL_MODULE,                        "matter_fluid_eternal"), // Lv.14
                new MatterRow(ShanhaiItems.MATERIAL_CREATION_MODULE,                       "matter_fluid_ultimate"), // Lv.15
                new MatterRow(ShanhaiItems.REALITY_ANCHOR_MODULE,                          null), // Lv.16
                new MatterRow(ShanhaiItems.GENESIS_REALITY_MODIFICATION_MODULE,            null), // Lv.17
        };
    }

    /** 一行：一台物质模块 ＋ 它对应的物质流 id（{@code null} = 该模块没有对应流体）。 */
    private record MatterRow(ItemEntry<Item> module, String fluidId) {}

    /**
     * 把一个桶放进创造栏。桶句柄是懒解析的（{@link ShanhaiFluids#bucketStack(String)}），
     * 解析不到就<b>保持少一格</b>，而不是把 {@code ItemStack.EMPTY} 塞进去
     * —— 与 {@link #acceptIfPresent} 同一条纪律（宁可少一格，不让生成器抛异常）。
     */
    private static void acceptBucket(CreativeModeTab.Output output, String fluidId) {
        net.minecraft.world.item.ItemStack stack = ShanhaiFluids.bucketStack(fluidId);
        if (!stack.isEmpty()) {
            output.accept(stack);
        }
    }

    /** 机器句柄非 null 才入列（注册失败时保持"少一格"，而不是让整栏崩掉）。 */
    private static void acceptIfPresent(CreativeModeTab.Output output, MultiblockMachineDefinition definition) {
        if (definition != null) {
            output.accept(definition.asStack());
        }
    }

    private ShanhaiCreativeModeTabs() {}
}
