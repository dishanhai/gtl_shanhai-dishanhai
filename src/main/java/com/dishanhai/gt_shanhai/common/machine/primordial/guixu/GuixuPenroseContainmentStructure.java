package com.dishanhai.gt_shanhai.common.machine.primordial.guixu;

// Author: dishanhai
import com.dishanhai.gt_shanhai.common.block.DShanhaiBlocks;
import com.dishanhai.gt_shanhai.common.machine.starlifter.StarLifterGeometry.Surface;
import com.gregtechceu.gtceu.api.machine.MultiblockMachineDefinition;
import com.gregtechceu.gtceu.api.machine.multiblock.PartAbility;
import com.gregtechceu.gtceu.api.pattern.BlockPattern;
import com.gregtechceu.gtceu.api.pattern.FactoryBlockPattern;
import com.gregtechceu.gtceu.api.pattern.Predicates;
import com.gregtechceu.gtceu.api.pattern.util.RelativeDirection;
import com.gtladd.gtladditions.common.machine.multiblock.structure.StructureResourceLoader;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * 山海：歸墟彭羅斯黑洞遏制場的結構，沿用原初模組的 7×7×13 版型。
 *
 * <p>每次都經 {@link StructureResourceLoader#loadFactoryPattern} 取得新的 pattern；
 * <b>禁止</b>改用 gtladditions 的共享結構單例（它是共享可變物件，{@code where()} 會改寫上游符號表）。
 * 接線測試會檢查本檔不出現那個類名，註解裡也不要寫。
 *
 * <pre>
 *   B 外圈殼（36）   中空機械方塊，可換倉室
 *   D 內環（21）     遏制場發生器
 *   E 束流軸臂（25） 粒子束導向管道機械方塊
 *   F 核心列（5）    遏制場發生器
 *   G 軸端（1）      粒子束導向管道機械方塊
 * </pre>
 */
public final class GuixuPenroseContainmentStructure {

    public static final String RESOURCE_PATH = "multiblock/forge_of_the_antichrist_module.bin";
    public static final String RESOURCE_ID = "forge_of_the_antichrist_module";
    public static final String HOLLOW_CASING = "kubejs:hollow_casing";
    public static final String FIELD_GENERATOR = "kubejs:containment_field_generator";
    public static final String BEAM_PIPE = "dishanhai:particle_beam_guidance_pipe_casing";

    private GuixuPenroseContainmentStructure() {}

    public static BlockPattern createPattern(MultiblockMachineDefinition definition) {
        return createPattern(definition, false);
    }

    public static BlockPattern createKerrPattern(MultiblockMachineDefinition definition) {
        return createPattern(definition, true);
    }

    public static BlockPattern createStarLiftingPattern(MultiblockMachineDefinition definition) {
        var stellarParts = DShanhaiBlocks.STAR_LIFTER_SHELL;
        var bootstrapCasing = appearanceBlock();
        return loadModulePattern()
                .where('B', Predicates.blocks(bootstrapCasing)
                        .or(Predicates.abilities(PartAbility.IMPORT_ITEMS).setPreviewCount(1))
                        .or(Predicates.abilities(PartAbility.EXPORT_ITEMS).setPreviewCount(1))
                        .or(Predicates.abilities(PartAbility.IMPORT_FLUIDS).setPreviewCount(1))
                        .or(Predicates.abilities(PartAbility.EXPORT_FLUIDS).setPreviewCount(1)))
                // The module produces these parts, so its first build can use the base casing.
                .where('D', Predicates.blocks(stellarParts.get(Surface.EXTRACTOR).get(), bootstrapCasing))
                .where('E', Predicates.blocks(stellarParts.get(Surface.CONDUIT).get(), bootstrapCasing))
                .where('F', Predicates.blocks(stellarParts.get(Surface.STELLAR).get(), bootstrapCasing))
                .where('G', Predicates.blocks(stellarParts.get(Surface.MAGNET).get(), bootstrapCasing))
                .where('~', Predicates.controller(Predicates.blocks(definition.getBlock())))
                .build();
    }

    private static FactoryBlockPattern loadModulePattern() {
        return StructureResourceLoader.INSTANCE.loadFactoryPattern(
                RESOURCE_PATH, RESOURCE_ID, new RelativeDirection[0]);
    }

    private static BlockPattern createPattern(MultiblockMachineDefinition definition, boolean kerr) {
        FactoryBlockPattern pattern = loadModulePattern();
        var generator = Predicates.blocks(requireBlock(FIELD_GENERATOR));
        var beamPipe = Predicates.blocks(requireBlock(BEAM_PIPE));
        var shell = Predicates.blocks(appearanceBlock())
                .or(Predicates.abilities(PartAbility.IMPORT_ITEMS).setPreviewCount(1))
                .or(Predicates.abilities(PartAbility.EXPORT_ITEMS).setPreviewCount(1));
        if (!kerr) {
            shell = shell.or(Predicates.abilities(PartAbility.IMPORT_FLUIDS).setPreviewCount(1))
                    .or(Predicates.abilities(PartAbility.EXPORT_FLUIDS).setPreviewCount(1));
        }
        return pattern
                .where('B', shell)
                .where('D', generator)
                .where('E', beamPipe)
                .where('F', generator)
                .where('G', beamPipe)
                .where('~', Predicates.controller(Predicates.blocks(definition.getBlock())))
                .build();
    }

    public static Block appearanceBlock() {
        return requireBlock(HOLLOW_CASING);
    }

    /** 按 id 取方塊；取不到就拋（帶 id），絕不返回 null。 */
    private static Block requireBlock(String id) {
        ResourceLocation rl = ResourceLocation.tryParse(id);
        Block block = rl == null ? null : ForgeRegistries.BLOCKS.getValue(rl);
        if (block == null || block == net.minecraft.world.level.block.Blocks.AIR) {
            throw new IllegalStateException("[SHANHAI-CONTAINMENT] 注册表里没有方块 " + id);
        }
        return block;
    }
}
