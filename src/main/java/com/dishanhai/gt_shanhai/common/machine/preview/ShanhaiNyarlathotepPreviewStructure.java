package com.dishanhai.gt_shanhai.common.machine.preview;

import com.dishanhai.gt_shanhai.GTDishanhaiMod;
import com.dishanhai.gt_shanhai.api.DShanhaiMBParser;
import com.gregtechceu.gtceu.api.machine.MultiblockMachineDefinition;
import com.gregtechceu.gtceu.api.pattern.BlockPattern;
import com.gregtechceu.gtceu.api.pattern.Predicates;
import com.gregtechceu.gtceu.api.pattern.TraceabilityPredicate;
import com.gregtechceu.gtceu.api.pattern.util.RelativeDirection;
import com.lowdragmc.lowdraglib.utils.BlockInfo;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.Set;
import java.util.HashSet;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 山海：GTO 奈亞拉托提普之觸的唯讀預覽映射。
 * MB 每行為一個 Z 切片，逗號分隔由下至上的 Y 列；三軸原序保留。
 * C/S/U/] 使用 GTL 現有方塊替代來源包自造件，詳見 docs/nyarlathoteps-tentacle-preview.md。
 */
public final class ShanhaiNyarlathotepPreviewStructure {
    private static final Set<ResourceLocation> WARNED_MISSING = ConcurrentHashMap.newKeySet();

    private ShanhaiNyarlathotepPreviewStructure() {}

    public static BlockPattern createPattern(MultiblockMachineDefinition definition) {
        return DShanhaiMBParser.parse(new ResourceLocation(GTDishanhaiMod.MOD_ID, "nyarlathoteps_tentacle"),
                symbol -> predicateFor(symbol, definition),
                RelativeDirection.LEFT, RelativeDirection.UP, RelativeDirection.FRONT);
    }

    /** 與結構謂詞共用候選方塊；供客戶端判斷錨點，包含加熱線圈及缺件佔位。 */
    public static Set<Block> previewBlocks(char symbol) {
        if (symbol == 'a' || symbol == ' ') return Set.of();
        var result = new HashSet<Block>();
        for (var predicate : predicateFor(symbol, null).common) {
            if (predicate.candidates == null) continue;
            for (BlockInfo candidate : predicate.candidates.get()) {
                result.add(candidate.getBlockState().getBlock());
            }
        }
        return result;
    }

    private static TraceabilityPredicate predicateFor(char symbol, MultiblockMachineDefinition definition) {
        switch (symbol) {
            case 'A': return lazy("gtlcore:molecular_casing");
            case 'B': return lazy("gtlcore:advanced_fusion_coil");
            case 'C': return lazy("gtceu:laser_safe_engraving_casing");
            case 'D': return lazy("kubejs:uruium_coil_block");
            case 'E': return lazy("gtlcore:hyper_core");
            case 'F': return lazy("gtceu:neutronium_frame");
            case 'G': return lazy("kubejs:spacetime_assembly_line_casing");
            case 'H': return lazy("gtladditions:extreme_density_casing");
            case 'I': return lazy("gtceu:fusion_glass");
            case 'J': return lazy("kubejs:laser_cooling_casing");
            case 'K': return lazy("gtladditions:quantum_glass");
            case 'L': return lazy("gtlcore:naquadah_alloy_casing");
            case 'M': return lazy("gtlcore:sps_casing");
            case 'N': return lazy("kubejs:spacetime_assembly_line_unit");
            case 'O': return lazy("gtlcore:molecular_casing");
            case 'P': return lazy("kubejs:containment_field_generator");
            case 'Q': return Predicates.heatingCoils();
            case 'R': return lazy("gtceu:sterilizing_filter_casing");
            case 'S': return lazy("gtlcore:rhenium_reinforced_energy_glass");
            case 'T': return lazy("kubejs:hollow_casing");
            case 'U': return lazy("gtceu:neutronium_block");
            case 'V': return lazy("gtceu:mithril_frame");
            case 'W': return lazy("gtceu:ruby_block");
            case 'X': return lazy("gtceu:electrolytic_cell");
            case 'Y': return lazy("gtceu:trinium_frame");
            case 'Z': return lazy("gtceu:high_power_casing");
            case '[': return lazy("kubejs:dimensional_bridge_casing");
            case ']': return lazy("gtceu:tungstensteel_pipe_casing");
            case '^': return lazy("kubejs:molecular_coil");
            case '_': return lazy("gtlcore:degenerate_rhenium_constrained_casing");
            case '`': return lazy("gtlcore:dimensionally_transcendent_casing");
            case '~': return lazy("kubejs:restraint_device");
            case 'a': return Predicates.controller(Predicates.blocks(definition.getBlock()));
            default: throw new IllegalArgumentException("Unknown Nyarlathotep symbol: " + symbol);
        }
    }

    private static TraceabilityPredicate lazy(String blockId) {
        ResourceLocation id = new ResourceLocation(blockId);
        return new TraceabilityPredicate(
                state -> state.getBlockState().is(resolve(id)),
                () -> new BlockInfo[]{new BlockInfo(resolve(id).defaultBlockState())});
    }

    /** 不快取註冊前的空結果，保留 KubeJS 晚註冊相容性。缺件以洋紅方塊清楚顯示。 */
    private static Block resolve(ResourceLocation id) {
        if (ForgeRegistries.BLOCKS.containsKey(id)) {
            Block block = ForgeRegistries.BLOCKS.getValue(id);
            if (block != null && block != Blocks.AIR) return block;
        }
        if (WARNED_MISSING.add(id)) {
            GTDishanhaiMod.LOGGER.warn("[山海結構預覽] 缺少方塊 {}，以洋紅混凝土顯示；僅供預覽", id);
        }
        return Blocks.MAGENTA_CONCRETE;
    }

    public static Block appearanceBlock() {
        return resolve(new ResourceLocation("gtlcore", "molecular_casing"));
    }
}
