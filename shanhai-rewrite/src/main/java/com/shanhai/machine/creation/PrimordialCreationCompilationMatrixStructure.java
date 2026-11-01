package com.shanhai.machine.creation;

import com.gregtechceu.gtceu.api.machine.MultiblockMachineDefinition;
import com.gregtechceu.gtceu.api.machine.multiblock.PartAbility;
import com.gregtechceu.gtceu.api.pattern.BlockPattern;
import com.gregtechceu.gtceu.api.pattern.Predicates;
import com.gregtechceu.gtceu.api.pattern.TraceabilityPredicate;
import com.gregtechceu.gtceu.common.data.GCyMBlocks;
import com.gregtechceu.gtceu.common.data.GTBlocks;
import com.shanhai.common.compat.GtlAddCompat;

import net.minecraft.world.level.block.Block;

/**
 * 「原初造物编译矩阵」的<b>本体结构</b>。
 *
 * <h2>几何：与现有 29 台模块<b>逐字节同源</b></h2>
 * 用 {@link GtlAddCompat#modulePattern()}（= 上游那份 {@code forge_of_the_antichrist_module.bin}，
 * 13 aisles × 7 rows × 7 wide），<b>不重写几何</b>。理由：
 * <ul>
 *   <li>模块位与主机槽位的对位关系是由这份 {@code .bin} 的形状决定的 —— 换形状就要重新验证"装得上"；</li>
 *   <li>本工程已有 29 台模块共用它，验证成本为 0。</li>
 * </ul>
 *
 * <h2>.bin 的真实内容（本轮<b>逐字节解出来</b>，不是转述 P1）</h2>
 * <pre>
 * 格式：magic "GTLASB2" + 版本(1) + id(len+bytes) + 4 个 unsigned short(13, 7, 7, 9)
 *       + 9 条"行模板"(每条 7 字符) + 13×7 个 big-endian short（= 行模板下标）
 * 解出来的 13 个 aisle（row0..row6）：
 *   aisle[0]      只有 row3 = "   G   "        ← 🔴 P3 起：这一格改成 Predicates.any()
 *   aisle[1..5]   只有 row3 = "   E   "        （5 层）
 *   aisle[6..10]  E 十字 + 中心 F（row2/3/4 竖 + row3 横，5 格）  （5 层）
 *   aisle[11]     7×7 满板（外圈 B，内部 D）
 *   aisle[12]     5×5 环 + 中心 " BB~BB " 的 row3 col3 = "~"（= 控制器）
 * 轴映射（{@code javap -c BlockPattern#setActualRelativeOffset} + {@code BlockPattern$1} 的
 *   $SwitchMap 实证；模块用默认 structureDir = (LEFT, UP, FRONT)）：
 *     row 轴 = UP ⇒ row 下标 <b>+Y</b>（竖直）；aisle 轴 = FRONT ⇒ 沿机器朝向；col 轴 = UP×朝向。
 *   ⇒ 机器 = 7 宽 × 7 高 × 13 深；<b>控制器在"深度"方向的最外层</b>（aisle 12），
 *     而且它的 4 个侧邻 + 上方 + 后方（aisle 11 那块满板）在图案里全是外壳
 *     ⇒ <b>6 个面里只有"正面"（朝机器朝向那边）是图案外的空气</b>。
 *     这正是 P3「AE 线缆直接贴控制器」能成立的前提，也是控制器把 6 面都算可接（{@code setExposedOnSides}
 *     传 {@code EnumSet.allOf}）的原因 —— 线缆唯一能贴的就是那一面。
 * </pre>
 *
 * <h2>🔴 P3 的改动：{@code G} 这一格从「外壳 ∪ 端口方块」改成 <b>什么都行</b></h2>
 * <ul>
 *   <li>端口方块（{@code shanhai:creation_pattern_port}）<b>已经删除</b>（用户拍板「改成控制器自己挂 AE」），
 *       所以这一格不再有"要接纳的专属方块"。</li>
 *   <li>用 {@link Predicates#any()} 而不是 {@code air().or(shell)}：用户口径是
 *       <b>「摆了也行、空着也行、放什么都不管」</b>，而且老存档里可能<b>还摆着那块已经删掉的端口方块</b>
 *       —— 用 {@code air ∪ shell} 会让那种存档当场<b>不成型</b>（正好违反"老机器不许因这次改动而失效"）。
 *       {@code any()} ⊆ 空气 ∪ 外壳 ∪ 端口方块 ∪ 任何东西，是这条口径的最宽解。</li>
 *   <li>是<b>放宽</b>不是收紧：原来允许的（青铜外壳）现在照样允许 ⇒ <b>已经摆好的机器必然仍然成型</b>。</li>
 * </ul>
 *
 * <h2>为什么把字母映射成 A–S 的超集（而不是只映射 .bin 里出现的那几个）</h2>
 * 与 {@code PrimordialMatterRecombinatorCoreStructure} 同一条纪律：图案里出现却没 {@code where} 的符号会让
 * {@code build()} 抛异常；未出现的符号映射起来零成本。gs 上游换 {@code .bin} 时不会当场炸。
 */
public final class PrimordialCreationCompilationMatrixStructure {

    private PrimordialCreationCompilationMatrixStructure() {}

    public static BlockPattern createPattern(MultiblockMachineDefinition definition) {
        final Block bronzeCasing = GTBlocks.BRONZE_HULL.get();                 // gtceu:bronze_machine_casing
        final Block industrialSteam = GCyMBlocks.CASING_INDUSTRIAL_STEAM.get(); // gtceu:industrial_steam_casing

        TraceabilityPredicate shell = Predicates.blocks(bronzeCasing);
        TraceabilityPredicate shellWithHatches = shell
                .or(Predicates.abilities(PartAbility.IMPORT_ITEMS).setPreviewCount(1))
                .or(Predicates.abilities(PartAbility.EXPORT_ITEMS).setPreviewCount(1))
                .or(Predicates.abilities(PartAbility.IMPORT_FLUIDS).setPreviewCount(1))
                .or(Predicates.abilities(PartAbility.EXPORT_FLUIDS).setPreviewCount(1))
                .or(Predicates.abilities(PartAbility.INPUT_ENERGY).setPreviewCount(1))
                .or(Predicates.abilities(PartAbility.OUTPUT_ENERGY).setPreviewCount(1));

        return GtlAddCompat.modulePattern()
                .where('A', shell)
                .where('B', shellWithHatches)
                .where('C', shell)
                .where('D', Predicates.blocks(industrialSteam))
                .where('E', shell)
                .where('F', shell)
                // 🔴 P3：原来是「外壳 ∪ 造物样板端口」，端口方块删除后改成"放什么都不管"
                //    （用户口径：摆了也行、空着也行；老存档里那块已删掉的端口方块也照样接纳）。
                .where('G', Predicates.any())
                .where('H', shell)
                .where('I', shell)
                .where('J', shell)
                .where('K', shell)
                .where('L', shell)
                .where('M', shell)
                .where('N', shell)
                .where('O', shell)
                .where('P', shell)
                .where('Q', shell)
                .where('S', shell)
                .where('~', Predicates.controller(Predicates.blocks(definition.getBlock())))
                .build();
    }
}
