package com.dishanhai.gt_shanhai.common.item.terminal;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
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
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.common.util.Lazy;
import net.minecraftforge.registries.ForgeRegistries;
import org.gtlcore.gtlcore.common.block.BlockMap;

public final class ShanhaiStructurePlanner {

    @FunctionalInterface
    public interface CandidatePriority {
        int priority(ItemStack candidate);
    }

    private record Candidate(ItemStack item, BlockState state) {
        private Candidate {
            item = item == null ? ItemStack.EMPTY : item.copyWithCount(1);
            state = state == null ? Blocks.AIR.defaultBlockState() : state;
        }
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
                        ShanhaiStructurePlan.Kind.CHAMBER_HINT, entry.desired(), entry.desiredState(),
                        entry.current(), entry.currentState(),
                        entry.candidates(), true));
            } else if (entry.chamberCapable()
                    && entry.kind() == ShanhaiStructurePlan.Kind.SATISFIED) {
                normalized.add(new ShanhaiStructurePlan.Entry(entry.pos(),
                        ShanhaiStructurePlan.Kind.CHAMBER_HINT, entry.desired(), entry.desiredState(),
                        entry.current(), entry.currentState(),
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
        List<Candidate> allCandidates = collectCandidates(predicate, candidateCache);
        List<ItemStack> candidateItems = allCandidates.stream()
                .map(Candidate::item)
                .filter(stack -> !stack.isEmpty())
                .toList();
        boolean chamberCapable = allCandidates.stream()
                .map(candidate -> candidate.state().getBlock())
                .anyMatch(ShanhaiChamberClassifier::isChamberBlock);
        List<Candidate> ordinaryCandidates = allCandidates.stream()
                .filter(candidate -> !ShanhaiChamberClassifier.isChamberBlock(candidate.state().getBlock()))
                .filter(candidate -> candidate.state().getBlock() != Blocks.AIR)
                .sorted(Comparator.comparingInt(candidate -> priority.priority(candidate.item())))
                .toList();

        BlockState currentState = level.getBlockState(pos);
        ItemStack current = currentState.isAir()
                ? ItemStack.EMPTY : currentState.getBlock().asItem().getDefaultInstance();
        Block[] replacementFamily = replacementFamily(terminal);
        Block replacementTarget = replacementTarget(terminal, replacementFamily);
        boolean replacementApplies = replacementTarget != null && ordinaryCandidates.stream()
                .map(candidate -> candidate.state().getBlock())
                .anyMatch(block -> contains(replacementFamily, block));

        Candidate desiredCandidate = replacementApplies
                ? new Candidate(replacementTarget.asItem().getDefaultInstance(), replacementTarget.defaultBlockState())
                : ordinaryCandidates.stream().findFirst()
                        .orElse(new Candidate(ItemStack.EMPTY, Blocks.AIR.defaultBlockState()));
        ItemStack desired = desiredCandidate.item();
        BlockState desiredState = desiredCandidate.state();
        Block currentBlock = currentState.getBlock();
        boolean currentAllowed = allCandidates.stream()
                .map(candidate -> candidate.state().getBlock())
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
            kind = ShanhaiUltimateTerminalConfig.isAbsoluteReplaceMode(terminal) && !desired.isEmpty()
                    ? ShanhaiStructurePlan.Kind.FORCE_REPLACE
                    : ShanhaiStructurePlan.Kind.BLOCKED;
        }
        return new ShanhaiStructurePlan.Entry(pos, kind, desired, desiredState,
                current, currentState, candidateItems, chamberCapable);
    }

    private static List<Candidate> collectCandidates(TraceabilityPredicate predicate, CandidateCache candidateCache) {
        List<Candidate> result = new ArrayList<>();
        addCandidates(result, predicate.limited, candidateCache);
        addCandidates(result, predicate.common, candidateCache);
        return new ArrayList<>(result);
    }

    private static void addCandidates(List<Candidate> result, List<SimplePredicate> predicates,
                                      CandidateCache candidateCache) {
        for (SimplePredicate simple : predicates) {
            result.addAll(candidateCache.get(simple));
        }
    }

    private static List<Candidate> readCandidates(SimplePredicate simple) {
        if (simple.candidates == null) return Collections.emptyList();
        BlockInfo[] infos = simple.candidates.get();
        if (infos == null || infos.length == 0) return Collections.emptyList();
        List<Candidate> result = new ArrayList<>(infos.length);
        for (BlockInfo info : infos) {
            BlockState state = info.getBlockState();
            ItemStack stack = itemStackFor(info, state);
            if (stack.isEmpty() && state.getBlock() == Blocks.AIR) continue;
            result.add(new Candidate(stack, state));
        }
        return Collections.unmodifiableList(result);
    }

    private static ItemStack itemStackFor(BlockInfo info, BlockState state) {
        if (state != null && state.getBlock() instanceof LiquidBlock liquidBlock) {
            return liquidBlock.getFluid().getBucket().getDefaultInstance();
        }
        ItemStack stack = info.getItemStackForm();
        return stack == null ? ItemStack.EMPTY : stack.copyWithCount(1);
    }

    private static final class CandidateCache {
        private final Map<SimplePredicate, List<Candidate>> byPredicate = new IdentityHashMap<>();

        private List<Candidate> get(SimplePredicate simple) {
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
