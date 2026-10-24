package com.shanhai.machine.host;

import com.gregtechceu.gtceu.api.machine.MultiblockMachineDefinition;
import com.gregtechceu.gtceu.api.machine.multiblock.PartAbility;
import com.gregtechceu.gtceu.api.pattern.BlockPattern;
import com.gregtechceu.gtceu.api.pattern.FactoryBlockPattern;
import com.gregtechceu.gtceu.api.pattern.Predicates;
import com.gregtechceu.gtceu.api.pattern.TraceabilityPredicate;
import com.shanhai.common.compat.GtlAddCompat;
import com.shanhai.machine.module.ModuleRegistry;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * 归墟视界枢核的<b>结构图案</b>：把 v5.1 的体素结构（{@code temp/host-shape/gen5.mjs} 生成）
 * 变成 GTCEu 的 {@link BlockPattern}。
 *
 * <h2>资源格式与位置（为什么是 {@code assets/gtladditions/…}）</h2>
 * 结构数据是 {@code .bin}（{@code GTLASB2} 二进制，<b>不是</b> {@code .nbt}），由
 * {@link GtlAddCompat#guixuVisionCorePattern()} 内部走 gtladditions 的
 * {@code StructureResourceLoader#loadFactoryPattern} 读出来。
 * <p>该 loader 的根路径是<b>写死的</b>（反编译实证：{@code ROOT_PATH = "assets/gtladditions/structures"}，
 * 再用 {@code StructureResourceLoader.class.getClassLoader().getResourceAsStream(...)} 取），
 * 它按<b>类路径</b>找资源、不区分是哪个 jar ⇒ 我们把自己的 {@code .bin} 放进
 * <b>本 mod jar 的同名路径</b>即可读到，<b>不需要</b>也不能改 gtladditions 的 jar。
 * 文件名带 {@code guixu_} 前缀，与 gtladditions 自带的 30 多个 {@code .bin} 不重名。
 *
 * <h2>符号表（必须与生成器 {@code gen5.mjs} 的 {@code BIN_SYMBOLS} 逐字对应）</h2>
 * <pre>
 *   A gtceu:iv_machine_casing        B gtceu:titanium_pipe_casing
 *   C gtceu:hv_machine_casing        D gtceu:reinforced_stone
 *   E gtceu:mv_machine_casing        F minecraft:sea_lantern
 *   G minecraft:ochre_froglight      H gtceu:zpm_machine_casing
 *   I minecraft:steel_block          J gtceu:tempered_glass
 *   M 模块槽（空气 ∪ 全部模块控制器方块）   ~ 控制器   空格 = 任意（不参与匹配）
 * </pre>
 * <p>⚠️ 模块占位（黄/橙/绿混凝土）在 {@code .bin} 里已经被当成<b>空格</b>——模块本体是玩家自己摆的
 * 独立多方块，主机图案只能要求"那 24 格是空的或者是模块"，不能把模块的 7×7×13 形状钉进来。
 *
 * <h2>为什么不用 {@code MultiBlockStructure}</h2>
 * 全工程禁用：那是 {@code kotlin.Lazy} 共享可变单例，{@code where()} 原地改符号表，
 * 会污染 gtladditions 自己的 6 台机器（详见 {@link GtlAddCompat} 类注释）。
 */
public final class GuixuVisionCoreStructure {

    private GuixuVisionCoreStructure() {}

    /** 结构资源路径（相对 {@code StructureResourceLoader} 的根，即 {@code assets/gtladditions/structures/}）。 */
    public static final String RESOURCE_PATH = "multiblock/guixu_vision_core.bin";
    /** 结构资源里写死的 id（loader 会校验它，不一致直接抛）。 */
    public static final String RESOURCE_ID = "guixu_vision_core";

    /** 符号 → 方块 id（必须与 {@code gen5.mjs} 的 {@code BIN_SYMBOLS} 一一对应）。 */
    public static final String[][] SYMBOL_BLOCKS = {
            {"A", "gtceu:iv_machine_casing"},
            {"B", "gtceu:titanium_pipe_casing"},
            {"C", "gtceu:hv_machine_casing"},
            {"D", "gtceu:reinforced_stone"},
            {"E", "gtceu:mv_machine_casing"},
            {"F", "minecraft:sea_lantern"},
            {"G", "minecraft:ochre_froglight"},
            {"H", "gtceu:zpm_machine_casing"},
            {"I", "gtceu:steel_block"},
            {"J", "gtceu:tempered_glass"},
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
    /** 控制器符号（恰好 1 个）。 */
    public static final char CONTROLLER = '~';

    /** 外观方块（JEI 预览 / 破坏时用的外壳）：外环桁架带。 */
    public static final String APPEARANCE_BLOCK = "gtceu:iv_machine_casing";

    /**
     * 建图案。**首次结构检查 / JEI 预览时才会被调用**（那时注册表早已冻结，方块句柄取得到）。
     *
     * @throws IllegalStateException 任一方块 id 在注册表里查不到（宁可加载期炸，也不要"图案恒不成立且无日志"）
     */
    public static BlockPattern createPattern(MultiblockMachineDefinition definition) {
        final FactoryBlockPattern pattern = GtlAddCompat.guixuVisionCorePattern();
        for (String[] entry : SYMBOL_BLOCKS) {
            final char symbol = entry[0].charAt(0);
            // 'A' 是唯一的仓室贴附点：本体是 iv 机壳，但允许被能源仓/动力仓替换。
            pattern.where(symbol, symbol == RING_BAND
                    ? ringBandPredicate()
                    : Predicates.blocks(requireBlock(entry[1])));
        }
        pattern.where(MODULE_SLOT, moduleSlotPredicate());
        pattern.where(CONTROLLER, Predicates.controller(Predicates.blocks(definition.getBlock())));
        return pattern.build();
    }

    /**
     * 'A' 的谓词 = {@code gtceu:iv_machine_casing} ∪ <b>能源仓</b> ∪ <b>动力仓</b>。
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
     * <p>⚠️ 本阶段<b>只放这两种</b>电仓：物品/流体仓的贴附点等阶段 3 接了三个自有配方类型之后再加，
     * 免得现在放上去一堆"能装但机器不认"的仓。
     */
    public static TraceabilityPredicate ringBandPredicate() {
        return Predicates.blocks(requireBlock("gtceu:iv_machine_casing"))
                .or(Predicates.abilities(PartAbility.INPUT_ENERGY).setPreviewCount(1))
                .or(Predicates.abilities(PartAbility.OUTPUT_ENERGY).setPreviewCount(1));
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
        final java.util.List<Block> moduleBlocks = ModuleRegistry.allModuleBlocks();
        if (moduleBlocks.isEmpty()) {
            throw new IllegalStateException("[SHANHAI-HOST] 模块方块表为空，拒绝生成 M 谓词："
                    + "那会让装了模块的主机结构恒不成型，且不抛异常、不打日志。"
                    + "请确认 ModuleRegistry.init() 先跑过。");
        }
        TraceabilityPredicate predicate = Predicates.air();
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
