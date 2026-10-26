package com.dishanhai.gt_shanhai.common.machine.primordial.guixu;

import com.gregtechceu.gtceu.api.machine.MultiblockMachineDefinition;
import com.gregtechceu.gtceu.api.machine.multiblock.PartAbility;
import com.gregtechceu.gtceu.api.pattern.BlockPattern;
import com.gregtechceu.gtceu.api.pattern.FactoryBlockPattern;
import com.gregtechceu.gtceu.api.pattern.Predicates;
import com.gregtechceu.gtceu.api.pattern.TraceabilityPredicate;
import com.gtladd.gtladditions.common.machine.multiblock.structure.StructureResourceLoader;
import com.gregtechceu.gtceu.api.pattern.util.RelativeDirection;
import com.dishanhai.gt_shanhai.common.machine.primordial.PrimordialOmegaEngineStructure;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * 归墟视界枢核的<b>结构图案</b>：把 v5.1 的体素结构（{@code temp/host-shape/gen5.mjs} 生成）
 * 变成 GTCEu 的 {@link BlockPattern}。
 *
 * <h2>资源格式与位置（为什么是 {@code assets/gtladditions/…}）</h2>
 * 结构数据是 {@code .bin}（{@code GTLASB2} 二进制，<b>不是</b> {@code .nbt}），由
 * 本類直接走 gtladditions 的
 * {@code StructureResourceLoader#loadFactoryPattern} 读出来。
 * <p>该 loader 的根路径是<b>写死的</b>（反编译实证：{@code ROOT_PATH = "assets/gtladditions/structures"}，
 * 再用 {@code StructureResourceLoader.class.getClassLoader().getResourceAsStream(...)} 取），
 * 它按<b>类路径</b>找资源、不区分是哪个 jar ⇒ 我们把自己的 {@code .bin} 放进
 * <b>本 mod jar 的同名路径</b>即可读到，<b>不需要</b>也不能改 gtladditions 的 jar。
 * 文件名带 {@code guixu_} 前缀，与 gtladditions 自带的 30 多个 {@code .bin} 不重名。
 *
 * <h2>符号表（必须与生成器 {@code gen5.mjs} 的 {@code BIN_SYMBOLS} 逐字对应）</h2>
 * <pre>
 *   A gtlcore:molecular_casing       B gtlcore:space_elevator_mechanical_casing
 *   C kubejs:hollow_casing           D kubejs:high_strength_concrete
 *   E gtceu:high_power_casing        F kubejs:containment_field_generator
 *   G gtlcore:power_module_5         H gtlcore:hyper_mechanical_casing
 *   I kubejs:restraint_device        J kubejs:molecular_coil
 *   M 模块槽（空气 ∪ 全部模块控制器方块）   ~ 控制器   空格 = 任意（不参与匹配）
 * </pre>
 * <p>⚠️ 模块占位（黄/橙/绿混凝土）在 {@code .bin} 里已经被当成<b>空格</b>——模块本体是玩家自己摆的
 * 独立多方块，主机图案只能要求"那 24 格是空的或者是模块"，不能把模块的 7×7×13 形状钉进来。
 *
 * <h2>为什么不用 {@code MultiBlockStructure}</h2>
 * 全工程禁用：那是 {@code kotlin.Lazy} 共享可变单例，{@code where()} 原地改符号表，
 * 不能把本機符號表寫入上游共享的可變單例。
 */
public final class GuixuVisionCoreStructure {

    private GuixuVisionCoreStructure() {}

    /** 结构资源路径（相对 {@code StructureResourceLoader} 的根，即 {@code assets/gtladditions/structures/}）。 */
    public static final String RESOURCE_PATH = "multiblock/guixu_vision_core.bin";
    /** 结构资源里写死的 id（loader 会校验它，不一致直接抛）。 */
    public static final String RESOURCE_ID = "guixu_vision_core";

    /** 山海：結構符號保持原位，材料採用深藍機殼、黑色骨架及能量節點配色。 */
    public static final String[][] SYMBOL_BLOCKS = {
            {"A", "gtlcore:molecular_casing"},
            {"B", "gtlcore:space_elevator_mechanical_casing"},
            {"C", "kubejs:hollow_casing"},
            {"D", "kubejs:high_strength_concrete"},
            {"E", "gtceu:high_power_casing"},
            {"F", "kubejs:containment_field_generator"},
            {"G", "gtlcore:power_module_4"},
            {"H", "gtlcore:hyper_mechanical_casing"},
            {"I", "kubejs:restraint_device"},
            {"J", "gtceu:fusion_glass"},
            {"N", "gtlcore:molecular_casing"},
            {"P", "gtlcore:molecular_casing"},
            {"Q", "gtlcore:molecular_casing"},
            {"T", "gtceu:bronze_machine_casing"},
            {"S", "gtceu:industrial_steam_casing"},
    };

    /**
     * 外环桁架带 / 站体机壳的符号 —— <b>本机唯一的"仓室贴附点"</b>。
     *
     * <p>第二轮的<b>能量双仓</b>就挂在这个符号上（见 {@link #ringBandPredicate()}）：
     * 结构同时接受<b>能源仓</b>（{@code PartAbility.INPUT_ENERGY}）与
     * <b>动力仓</b>（{@code PartAbility.OUTPUT_ENERGY}）。
     */
    public static final char RING_BAND = 'A';

    /** 模块槽符号（24 个）。 */
    public static final char MODULE_SLOT = 'M';
    public static final char MODULE_SLOT_FILLED = 'N';
    public static final char MODULE_CAVITY_FILLED = 'P';
    public static final char CAVITY_MOUTH_FILLED = 'Q';
    public static final char MODULE_SHELL_BRONZE = 'T';
    public static final char MODULE_SHELL_STEAM = 'S';
    /** 控制器符号（恰好 1 个）。 */
    public static final char CONTROLLER = '~';

    /** 外观方块（JEI 预览 / 破坏时用的外壳）：外环桁架带。 */
    public static final String APPEARANCE_BLOCK = "gtlcore:molecular_casing";

    /**
     * 建图案。**首次结构检查 / JEI 预览时才会被调用**（那时注册表早已冻结，方块句柄取得到）。
     *
     * @throws IllegalStateException 任一方块 id 在注册表里查不到（宁可加载期炸，也不要"图案恒不成立且无日志"）
     */
    public static BlockPattern createPattern(MultiblockMachineDefinition definition) {
        final FactoryBlockPattern pattern = StructureResourceLoader.INSTANCE.loadFactoryPattern(RESOURCE_PATH, RESOURCE_ID,
                RelativeDirection.LEFT, RelativeDirection.DOWN, RelativeDirection.BACK);
        for (String[] entry : SYMBOL_BLOCKS) {
            final char symbol = entry[0].charAt(0);
            if (symbol == MODULE_SLOT_FILLED || symbol == MODULE_CAVITY_FILLED
                    || symbol == CAVITY_MOUTH_FILLED || symbol == MODULE_SHELL_BRONZE
                    || symbol == MODULE_SHELL_STEAM) continue;
            // 'A' 是唯一的倉室貼附點：分子機械方塊允許被能源倉／動力倉替換。
            pattern.where(symbol, symbol == RING_BAND
                    ? ringBandPredicate()
                    : Predicates.blocks(requireBlock(entry[1])));
        }
        pattern.where(MODULE_SLOT, moduleSlotPredicate());
        pattern.where(MODULE_SLOT_FILLED, filledModuleSlotPredicate());
        // 來源新增的模組腔體與外殼只提供預覽；實際模組獨立檢查自己的結構。
        pattern.where(MODULE_CAVITY_FILLED, Predicates.air().or(Predicates.any()));
        pattern.where(CAVITY_MOUTH_FILLED, Predicates.blocks(appearanceBlock()).or(Predicates.any()));
        pattern.where(MODULE_SHELL_BRONZE,
                Predicates.blocks(requireBlock("gtceu:bronze_machine_casing")).or(Predicates.any()));
        pattern.where(MODULE_SHELL_STEAM,
                Predicates.blocks(requireBlock("gtceu:industrial_steam_casing")).or(Predicates.any()));
        pattern.where(CONTROLLER, Predicates.controller(Predicates.blocks(definition.getBlock())));
        return pattern.build();
    }

    /**
     * 'A' 的謂詞 = {@code gtlcore:molecular_casing} ∪ <b>能源倉</b> ∪ <b>動力倉</b>。
     *
     * <p>为什么要两种电仓（用户交办原话「结构同时收能源仓与动力仓」）：
     * <ul>
     *   <li><b>能源仓</b>（{@code INPUT_ENERGY}）—— 机器吃电的那一侧（本机做配方用）；</li>
     *   <li><b>动力仓</b>（{@code OUTPUT_ENERGY}）—— 机器往外送电的那一侧
     *       （阶段 3 的「视界能流抽取」要用；本阶段先让结构收得下、装得上）。</li>
     * </ul>
     * 两侧的存量在抬头里<b>分两行</b>画（{@link GuixuVisionCoreMachine#addDisplayText}），
     * 机器等级不卡（沿用 {@code PrimordialOmegaEngineMachine#getMaxVoltage()} = {@code Long.MAX_VALUE}）。
     *
     * <p>山海：專屬模式另接受物品／流體進出料倉，沿用原生 capability 聚合及 ME 倉能力。
     */
    public static TraceabilityPredicate ringBandPredicate() {
        return Predicates.blocks(appearanceBlock())
                .or(Predicates.abilities(PartAbility.INPUT_ENERGY).setPreviewCount(1))
                .or(Predicates.abilities(PartAbility.OUTPUT_ENERGY).setPreviewCount(1))
                .or(Predicates.abilities(PartAbility.IMPORT_ITEMS).setPreviewCount(1))
                .or(Predicates.abilities(PartAbility.EXPORT_ITEMS).setPreviewCount(1))
                .or(Predicates.abilities(PartAbility.IMPORT_FLUIDS).setPreviewCount(1))
                .or(Predicates.abilities(PartAbility.EXPORT_FLUIDS).setPreviewCount(1));
    }

    /** 外观方块句柄（注册期惰性取）。 */
    public static Block appearanceBlock() {
        return requireBlock(APPEARANCE_BLOCK);
    }

    /**
     * 模块槽谓词 = <b>空气 ∪ 全部模块控制器方块</b>。
     *
     * <p>为什么含空气：本阶段要让"空槽的主机"也能成型（进游戏的 30 秒判据就是"放下去能不能成型"）。
     * 插上模块之后那一格变成模块控制器方块 ⇒ 同样合法；模块本体（7×7×13）在图案里是空格（任意），
     * 不参与匹配。
     *
     * <p>模块方块表为空 ⇒ 直接抛（与 {@code ShanhaiMachines#moduleSlotPredicate()} 同一条纪律：
     * 绝不放行成"谓词只认空气"那种静默失败）。
     */
    public static TraceabilityPredicate moduleSlotPredicate() {
        return moduleSlotPredicate(false);
    }

    public static TraceabilityPredicate filledModuleSlotPredicate() {
        return moduleSlotPredicate(true);
    }

    private static TraceabilityPredicate moduleSlotPredicate(boolean filled) {
        final java.util.List<Block> moduleBlocks = PrimordialOmegaEngineStructure.allModuleBlocks();
        if (moduleBlocks.isEmpty()) {
            throw new IllegalStateException("[SHANHAI-HOST] 模块方块表为空，拒绝生成 M 谓词："
                    + "那会让装了模块的主机结构恒不成型，且不抛异常、不打日志。"
                    + "請確認 DShanhaiMachines 的模組註冊已完成。");
        }
        TraceabilityPredicate casing = Predicates.blocks(appearanceBlock());
        TraceabilityPredicate predicate = filled ? casing.or(Predicates.air()) : Predicates.air().or(casing);
        for (Block block : moduleBlocks) {
            predicate = predicate.or(Predicates.blocks(block));
        }
        return predicate;
    }

    /** 按 id 取方块；取不到就抛（带 id），绝不返回 null。 */
    private static Block requireBlock(String id) {
        final ResourceLocation rl = ResourceLocation.tryParse(id);
        if (rl == null) {
            throw new IllegalStateException("[SHANHAI-HOST] 方块 id 不合法：" + id);
        }
        final Block block = ForgeRegistries.BLOCKS.getValue(rl);
        if (block == null) {
            throw new IllegalStateException("[SHANHAI-HOST] 注册表里没有方块 " + id
                    + " —— 结构图案的符号表与造型调色板不一致，或该 mod 缺失。");
        }
        return block;
    }
}
