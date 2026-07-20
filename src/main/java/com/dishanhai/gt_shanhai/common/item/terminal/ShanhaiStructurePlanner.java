package com.dishanhai.gt_shanhai.common.item.terminal;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import com.dishanhai.gt_shanhai.common.item.ShanhaiUltimateTerminalConfig;
import com.gregtechceu.gtceu.api.machine.feature.multiblock.IMultiController;
import com.gregtechceu.gtceu.api.pattern.TraceabilityPredicate;
import com.gregtechceu.gtceu.api.pattern.predicates.SimplePredicate;
import com.lowdragmc.lowdraglib.utils.BlockInfo;

import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.common.util.Lazy;
import net.minecraftforge.registries.ForgeRegistries;
import org.gtlcore.gtlcore.common.block.BlockMap;

public final class ShanhaiStructurePlanner {

    @FunctionalInterface
    public interface CandidatePriority {
        int priority(ItemStack candidate);
    }

    private ShanhaiStructurePlanner() {}

    public static ShanhaiStructurePlan scan(IMultiController controller, ItemStack terminal) {
        return scan(controller, terminal, candidate -> Integer.MAX_VALUE);
    }

    public static ShanhaiStructurePlan scan(IMultiController controller, ItemStack terminal,
                                            CandidatePriority priority) {
        Level level = controller.self().getLevel();
        boolean mirrored = ShanhaiUltimateTerminalConfig.isMirrored(terminal);
        boolean noChambers = ShanhaiUltimateTerminalConfig.isNoChamberMode(terminal);
        int repeatCount = ShanhaiUltimateTerminalConfig.getRepeatCount(terminal);
        List<ShanhaiStructurePlan.Entry> entries = new ArrayList<>();
        CandidateCache candidateCache = new CandidateCache();
        for (ShanhaiStructurePatternAdapter.Slot slot
                : ShanhaiStructurePatternAdapter.map(controller, repeatCount, mirrored)) {
            if (slot.pos().equals(controller.self().getPos())) continue;
            entries.add(classify(level, slot.pos(), slot.predicate(), terminal, priority, candidateCache));
        }
        List<ShanhaiStructurePlan.Entry> normalized = new ArrayList<>(entries.size());
        for (ShanhaiStructurePlan.Entry entry : entries) {
            if (!noChambers && entry.chamberCapable()) {
                normalized.add(new ShanhaiStructurePlan.Entry(entry.pos(),
                        ShanhaiStructurePlan.Kind.CHAMBER_HINT, entry.desired(), entry.current(),
                        entry.candidates(), true));
            } else if (entry.chamberCapable()
                    && entry.kind() == ShanhaiStructurePlan.Kind.SATISFIED) {
                normalized.add(new ShanhaiStructurePlan.Entry(entry.pos(),
                        ShanhaiStructurePlan.Kind.CHAMBER_HINT, entry.desired(), entry.current(),
                        entry.candidates(), true));
            } else {
                normalized.add(entry);
            }
        }
        Block controllerBlock = controller.self().getBlockState().getBlock();
        var machineId = ForgeRegistries.BLOCKS.getKey(controllerBlock);
        return new ShanhaiStructurePlan(
                GlobalPos.of(level.dimension(), controller.self().getPos()),
                machineId == null ? "" : machineId.toString(), mirrored, repeatCount, normalized);
    }

    private static ShanhaiStructurePlan.Entry classify(Level level, BlockPos pos,
                                                         TraceabilityPredicate predicate,
                                                         ItemStack terminal,
                                                         CandidatePriority priority,
                                                         CandidateCache candidateCache) {
        List<ItemStack> allCandidates = collectCandidates(predicate, candidateCache);
        boolean chamberCapable = allCandidates.stream()
                .map(ItemStack::getItem)
                .map(Block::byItem)
                .anyMatch(ShanhaiChamberClassifier::isChamberBlock);
        List<ItemStack> ordinaryCandidates = allCandidates.stream()
                .filter(stack -> !ShanhaiChamberClassifier.isChamberBlock(Block.byItem(stack.getItem())))
                .filter(stack -> Block.byItem(stack.getItem()) != Blocks.AIR)
                .sorted(Comparator.comparingInt(priority::priority))
                .toList();

        BlockState currentState = level.getBlockState(pos);
        ItemStack current = currentState.isAir()
                ? ItemStack.EMPTY : currentState.getBlock().asItem().getDefaultInstance();
        Block[] replacementFamily = replacementFamily(terminal);
        Block replacementTarget = replacementTarget(terminal, replacementFamily);
        boolean replacementApplies = replacementTarget != null && ordinaryCandidates.stream()
                .map(ItemStack::getItem).map(Block::byItem)
                .anyMatch(block -> contains(replacementFamily, block));

        ItemStack desired = replacementApplies
                ? replacementTarget.asItem().getDefaultInstance()
                : ordinaryCandidates.stream().findFirst().orElse(ItemStack.EMPTY);
        Block currentBlock = currentState.getBlock();
        boolean currentAllowed = allCandidates.stream()
                .map(ItemStack::getItem).map(Block::byItem)
                .anyMatch(block -> block == currentBlock);

        ShanhaiStructurePlan.Kind kind;
        if (replacementApplies && contains(replacementFamily, currentBlock)
                && currentBlock != replacementTarget) {
            kind = ShanhaiStructurePlan.Kind.REPLACE;
        } else if (currentAllowed) {
            kind = ShanhaiStructurePlan.Kind.SATISFIED;
        } else if (currentState.isAir()) {
            kind = desired.isEmpty() ? ShanhaiStructurePlan.Kind.MANUAL : ShanhaiStructurePlan.Kind.PLACE;
        } else {
            kind = ShanhaiStructurePlan.Kind.BLOCKED;
        }
        return new ShanhaiStructurePlan.Entry(pos, kind, desired, current, allCandidates, chamberCapable);
    }

    private static List<ItemStack> collectCandidates(TraceabilityPredicate predicate, CandidateCache candidateCache) {
        LinkedHashSet<ItemStack> result = new LinkedHashSet<>();
        addCandidates(result, predicate.limited, candidateCache);
        addCandidates(result, predicate.common, candidateCache);
        return new ArrayList<>(result);
    }

    private static void addCandidates(LinkedHashSet<ItemStack> result, List<SimplePredicate> predicates,
                                      CandidateCache candidateCache) {
        for (SimplePredicate simple : predicates) {
            result.addAll(candidateCache.get(simple));
        }
    }

    private static List<ItemStack> readCandidates(SimplePredicate simple) {
        if (simple.candidates == null) return Collections.emptyList();
        BlockInfo[] infos = simple.candidates.get();
        if (infos == null || infos.length == 0) return Collections.emptyList();
        List<ItemStack> result = new ArrayList<>(infos.length);
        for (BlockInfo info : infos) {
            ItemStack stack = info.getItemStackForm();
            if (!stack.isEmpty()) result.add(stack.copyWithCount(1));
        }
        return Collections.unmodifiableList(result);
    }

    private static final class CandidateCache {
        private final Map<SimplePredicate, List<ItemStack>> byPredicate = new IdentityHashMap<>();

        private List<ItemStack> get(SimplePredicate simple) {
            return byPredicate.computeIfAbsent(simple, ShanhaiStructurePlanner::readCandidates);
        }
    }

    private static Block[] replacementFamily(ItemStack terminal) {
        if (!ShanhaiUltimateTerminalConfig.isReplaceMode(terminal)) return new Block[0];
        String family = ShanhaiUltimateTerminalConfig.getReplacementFamily(terminal);
        Lazy<Block[]> lazy = BlockMap.tierBlockMap.get(family);
        return lazy == null ? new Block[0] : lazy.get();
    }

    private static Block replacementTarget(ItemStack terminal, Block[] family) {
        if (family.length == 0) return null;
        int tier = Math.min(ShanhaiUltimateTerminalConfig.getReplacementTier(terminal), family.length - 1);
        return family[tier];
    }

    private static boolean contains(Block[] blocks, Block block) {
        return Arrays.asList(blocks).contains(block);
    }

}
