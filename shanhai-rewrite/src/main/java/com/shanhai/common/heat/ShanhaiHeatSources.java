package com.shanhai.common.heat;

import com.gregtechceu.gtceu.common.block.CoilBlock;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraftforge.registries.ForgeRegistries;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.Set;

/**
 * 山海重构 · <b>「额外挂载槽」里那件东西提供什么</b>的**唯一**读表处（MC 侧）。
 *
 * <p>判据核在 {@link ShanhaiHeatGate}（纯 {@code java.*}，可离线 {@code javac} 驱动）；
 * 本类只负责"从一件 {@link ItemStack} 现读出能力"，<b>一个判据都不写</b>。
 *
 * <h2>1. 线圈 / 恒星热力容器（沿用 2026-09-30 恒星热力槽的旧口径，一字未改）</h2>
 * <ul>
 *   <li><b>哪些方块算线圈</b>：判据一律是「它是不是 {@link CoilBlock}」，温度从
 *       {@link CoilBlock#coilType}（公开字段）现读 —— <b>表里一个数字都不抄</b>。
 *       本整合包有三个来源（GTCEu 自带 8 个 + KubeJS 注册 9 个），写死任何一份都会漏。</li>
 *   <li>⚠️ {@code gtceu:superconducting_coil} / {@code gtlcore:advanced_fusion_coil} 一族
 *       <b>不是</b> {@code CoilBlock} ⇒ 本槽不收它们（名字里带 coil 但不提供炉温）。</li>
 *   <li>恒星热力容器只有 gtlcore 那三个，等级按<b>注册 id 字符串</b>比对，
 *       <b>不 import gtlcore 的类</b>（少一个硬编译依赖）。</li>
 * </ul>
 *
 * <h2>2. 维护仓一族（🔴 逐个 id 与"提供什么"都是 A 级实证，原始输出见交付报告 §3）</h2>
 * 证据链：{@code javap -v org.gtlcore.gtlcore.common.data.GTLMachines} 的
 * BootstrapMethods 把每个注册 id 的工厂 lambda 解出来，{@code javap -p -c} 再读 lambda 体 ——
 * 于是拿到「id → 构造的类 + 传入的 {@code ICleanroomProvider}」这张硬表：
 * <pre>
 *   sterile_cleaning_maintenance_hatch                    → GTLCleaningMaintenanceHatchPartMachine + STERILE_DUMMY_CLEANROOM
 *   law_cleaning_maintenance_hatch                        → GTLCleaningMaintenanceHatchPartMachine + LAW_DUMMY_CLEANROOM
 *   cleaning_configuration_maintenance_hatch              → CleaningConfigurationMaintenanceHatchPartMachine + DUMMY_CLEANROOM
 *   sterile_configuration_cleaning_maintenance_hatch      → CleaningConfigurationMaintenanceHatchPartMachine + STERILE_DUMMY_CLEANROOM
 *   law_configuration_cleaning_maintenance_hatch          → CleaningConfigurationMaintenanceHatchPartMachine + LAW_DUMMY_CLEANROOM
 *   gravity_hatch                                          → GravityCleaningConfigurationMaintenancePartMachine（无 cleanroom provider）
 *   gravity_configuration_hatch                            → GravityCleaningConfigurationMaintenancePartMachine（无 cleanroom provider）
 *   cleaning_gravity_configuration_maintenance_hatch       → GravityCleaningConfigurationMaintenancePartMachine + DUMMY_CLEANROOM
 *   sterile_cleaning_gravity_configuration_maintenance_hatch → GravityCleaningConfigurationMaintenancePartMachine + STERILE_DUMMY_CLEANROOM
 *   law_cleaning_gravity_configuration_maintenance_hatch   → GravityCleaningConfigurationMaintenancePartMachine + LAW_DUMMY_CLEANROOM
 * </pre>
 * 而三档的<b>层级</b>来自 {@code ICleaningRoom.<clinit>}（逐字节读过）：
 * {@code LAW_CLEANROOM ⊇ STERILE_CLEANROOM ⊇ CLEANROOM} ⇒ 档位大的天然满足档位小的。
 * <p>⚠️ <b>不带 cleaning 的维护仓（{@code maintenance_hatch} / {@code auto_maintenance_hatch} /
 * {@code configurable_maintenance_hatch} / {@code auto_configuration_maintenance_hatch}）
 * 提供不了任何超净间、也提供不了重力</b> —— 这正是负对照（"放错维护仓必须失败"）用的物项。
 * <p>⚠️ lang 里还存在三个<b>不带 configuration</b> 的重力维护仓名字
 * （{@code cleaning_gravity_maintenance_hatch} 等），但注册表里<b>没有</b>对应的 id
 * ⇒ 本表<b>不收</b>它们（收了就是收一个玩家拿不到的 id）。
 *
 * <h2>3. 维度碎片与"研究放行"物项</h2>
 * 见 {@link ShanhaiHeatGate#dimensionTable()}（对照表在判据核里，本类只做 id 反查）与
 * {@link #CREATIVE_DATA_ACCESS_HATCH_ID}。
 */
public final class ShanhaiHeatSources {

    private ShanhaiHeatSources() {}

    // ═══════════════ 恒星热力容器（三个 id） ═══════════════

    /** 基础恒星热力容器（{@code GTLBlocks.STELLAR_CONTAINMENT_CASING}）。 */
    public static final String SC_BASIC_ID = "gtlcore:stellar_containment_casing";
    /** 高级恒星热力容器（{@code GTLBlocks.ADVANCED_STELLAR_CONTAINMENT_CASING}）。 */
    public static final String SC_ADVANCED_ID = "gtlcore:advanced_stellar_containment_casing";
    /** 终极恒星热力容器（{@code GTLBlocks.ULTIMATE_STELLAR_CONTAINMENT_CASING}）。 */
    public static final String SC_ULTIMATE_ID = "gtlcore:ultimate_stellar_containment_casing";

    /** 容器等级上限（= 上面三个）。给 tooltip 与自证用，避免两处各写一个 3。 */
    public static final int MAX_CONTAINMENT_TIER = 3;

    // ═══════════════ 超净间 / 重力：维护仓一族 ═══════════════

    /** 维护仓 id → 它提供的超净间档位（0 = 不提供）。**逐个都经 GTMachines 字节码实证。** */
    public static final Map<String, Integer> HATCH_CLEANROOM_TIER = Map.ofEntries(
            // —— 不提供超净间 ——
            Map.entry("gtceu:maintenance_hatch", ShanhaiHeatGate.CLEANROOM_NONE),
            Map.entry("gtceu:auto_maintenance_hatch", ShanhaiHeatGate.CLEANROOM_NONE),
            Map.entry("gtceu:configurable_maintenance_hatch", ShanhaiHeatGate.CLEANROOM_NONE),
            Map.entry("gtceu:auto_configuration_maintenance_hatch", ShanhaiHeatGate.CLEANROOM_NONE),
            Map.entry("shanhai:maintenance_hatch", ShanhaiHeatGate.CLEANROOM_NONE),
            // —— 超净间（1 档）——
            Map.entry("gtceu:cleaning_maintenance_hatch", ShanhaiHeatGate.CLEANROOM_PLAIN),
            Map.entry("gtceu:cleaning_configuration_maintenance_hatch", ShanhaiHeatGate.CLEANROOM_PLAIN),
            Map.entry("gtceu:cleaning_gravity_configuration_maintenance_hatch", ShanhaiHeatGate.CLEANROOM_PLAIN),
            // —— 无菌超净间（2 档）——
            Map.entry("gtceu:sterile_cleaning_maintenance_hatch", ShanhaiHeatGate.CLEANROOM_STERILE),
            Map.entry("gtceu:sterile_configuration_cleaning_maintenance_hatch", ShanhaiHeatGate.CLEANROOM_STERILE),
            Map.entry("gtceu:sterile_cleaning_gravity_configuration_maintenance_hatch", ShanhaiHeatGate.CLEANROOM_STERILE),
            // —— 绝对超净间（3 档，gtlcore 加的）——
            Map.entry("gtceu:law_cleaning_maintenance_hatch", ShanhaiHeatGate.CLEANROOM_LAW),
            Map.entry("gtceu:law_configuration_cleaning_maintenance_hatch", ShanhaiHeatGate.CLEANROOM_LAW),
            Map.entry("gtceu:law_cleaning_gravity_configuration_maintenance_hatch", ShanhaiHeatGate.CLEANROOM_LAW),
            // —— 只提供重力、不提供超净间（工厂没传 ICleanroomProvider）——
            Map.entry("gtceu:gravity_hatch", ShanhaiHeatGate.CLEANROOM_NONE),
            Map.entry("gtceu:gravity_configuration_hatch", ShanhaiHeatGate.CLEANROOM_NONE));

    /**
     * 提供<b>重力控制</b>的维护仓 id。
     *
     * <p>实证：这些机器构造的是 {@code GravityCleaningConfigurationMaintenancePartMachine}，
     * 它 {@code implements IAutoConfiguratioGravityPart}，而该接口
     * {@code extends IAutoConfigurationMaintenanceHatch, IGravityPartMachine}
     * ⇒ {@code GravityCondition.test} 遍历 parts 时一定能找到 {@code IGravityPartMachine}。
     *
     * <p>🔴 <b>一处必须点名的简化</b>：gtlcore 的 {@code GravityCondition} 里 {@code zero} 是
     * <b>private 且没有 getter</b>（{@code javap -p} 原文），所以本表<b>不区分</b>「无重力(0)」与
     * 「强重力(100)」—— 带重力的维护仓一律两种都算满足。依据是用户规格原话
     * 「<b>超重/无重力</b> + 超净间 ⇒ 都由维护仓提供」，且这些仓是可配置的（{@code isConfig} 字段）。
     */
    public static final Set<String> HATCH_GRAVITY_IDS = Set.of(
            "gtceu:gravity_hatch",
            "gtceu:gravity_configuration_hatch",
            "gtceu:cleaning_gravity_configuration_maintenance_hatch",
            "gtceu:sterile_cleaning_gravity_configuration_maintenance_hatch",
            "gtceu:law_cleaning_gravity_configuration_maintenance_hatch");

    // ═══════════════ 研究放行物项 ═══════════════

    /** 创造模式数据访问仓（gtceu 本体注册；{@code registries/item.json} 与 lang 双重验过）。 */
    public static final String CREATIVE_DATA_ACCESS_HATCH_ID = "gtceu:creative_data_access_hatch";

    // ═══════════════ 读一件物品 ═══════════════

    /**
     * 读一件物品提供什么。<b>纯读，不改 stack，不碰世界</b>。
     *
     * <p>客户端也会调（GUI tooltip 里的活值）⇒ 只做注册表查询与字段读取，不做任何服务端动作。
     */
    @NotNull
    public static ShanhaiHeatGate.SlotContent slotContentOf(@Nullable ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return ShanhaiHeatGate.SlotContent.EMPTY;
        }
        final int count = stack.getCount();
        // ① 线圈（CoilBlock，温度现读）
        final Block block = blockOf(stack);
        if (block instanceof CoilBlock coilBlock && coilBlock.coilType != null) {
            return new ShanhaiHeatGate.SlotContent(count, coilBlock.coilType.getCoilTemperature(),
                    0, ShanhaiHeatGate.CLEANROOM_NONE, false, false, Set.of());
        }
        // ② 恒星热力容器（按注册 id）
        final int tier = containmentTierOf(block);
        if (tier > 0) {
            return new ShanhaiHeatGate.SlotContent(count, 0, tier,
                    ShanhaiHeatGate.CLEANROOM_NONE, false, false, Set.of());
        }
        // ③ 物品 id 一族：维护仓 / 碎片 / 创造模式数据访问仓
        final String id = itemIdOf(stack);
        if (id == null) {
            return new ShanhaiHeatGate.SlotContent(count, 0, 0,
                    ShanhaiHeatGate.CLEANROOM_NONE, false, false, Set.of());
        }
        final Integer hatchTier = HATCH_CLEANROOM_TIER.get(id);
        final boolean gravity = HATCH_GRAVITY_IDS.contains(id);
        final boolean research = CREATIVE_DATA_ACCESS_HATCH_ID.equals(id);
        final String fragmentDim = ShanhaiHeatGate.dimensionOfFragment(id);
        if (hatchTier == null && !gravity && !research && fragmentDim == null) {
            return new ShanhaiHeatGate.SlotContent(count, 0, 0,
                    ShanhaiHeatGate.CLEANROOM_NONE, false, false, Set.of());
        }
        return new ShanhaiHeatGate.SlotContent(count, 0, 0,
                hatchTier == null ? ShanhaiHeatGate.CLEANROOM_NONE : hatchTier,
                gravity, research,
                fragmentDim == null ? Set.of() : Set.of(fragmentDim));
    }

    /** 物品对应的方块；不是方块物品返回 {@code null}。 */
    @Nullable
    private static Block blockOf(@NotNull ItemStack stack) {
        if (stack.getItem() instanceof BlockItem blockItem) {
            return blockItem.getBlock();
        }
        return null;
    }

    /** 物品的注册 id（形如 {@code gtceu:law_cleaning_gravity_configuration_maintenance_hatch}）。 */
    @Nullable
    public static String itemIdOf(@NotNull ItemStack stack) {
        final ResourceLocation key = ForgeRegistries.ITEMS.getKey(stack.getItem());
        return key == null ? null : key.toString();
    }

    /** 按注册 id 判恒星热力容器等级；不是容器返回 0。 */
    public static int containmentTierOf(@Nullable Block block) {
        if (block == null) {
            return 0;
        }
        final ResourceLocation key = ForgeRegistries.BLOCKS.getKey(block);
        if (key == null) {
            return 0;
        }
        final String id = key.toString();
        if (SC_BASIC_ID.equals(id)) {
            return 1;
        }
        if (SC_ADVANCED_ID.equals(id)) {
            return 2;
        }
        if (SC_ULTIMATE_ID.equals(id)) {
            return 3;
        }
        return 0;
    }
}
