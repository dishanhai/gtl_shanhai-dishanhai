package com.dishanhai.gt_shanhai.common.machine.primordial;

import com.gregtechceu.gtceu.api.block.ActiveBlock;
import com.gregtechceu.gtceu.api.machine.IMachineBlockEntity;
import com.gregtechceu.gtceu.api.machine.feature.multiblock.IMultiPart;
import com.gregtechceu.gtceu.api.pattern.BlockPattern;
import com.gregtechceu.gtceu.api.pattern.MultiblockState;
import com.gregtechceu.gtceu.api.pattern.TraceabilityPredicate;
import com.gregtechceu.gtceu.api.pattern.error.PatternError;
import com.gregtechceu.gtceu.api.pattern.error.PatternStringError;
import com.gregtechceu.gtceu.api.pattern.error.SinglePredicateError;
import com.gregtechceu.gtceu.api.pattern.predicates.SimplePredicate;
import com.gregtechceu.gtceu.api.pattern.util.PatternMatchContext;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import org.gtlcore.gtlcore.api.pattern.AdvancedBlockPattern;
import org.gtlcore.gtlcore.api.pattern.FixedPatternLayout;
import org.gtlcore.gtlcore.api.pattern.WorldPatternTiming;
import org.gtlcore.gtlcore.mixin.gtm.api.machine.IMultiblockStateInvoker;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 山海原初固定結構的稀疏匹配器。
 * 只省略 GTLCore 原本就不判定的 isAny 格；空氣、倉室、數量與共享規則照常執行。
 * 原始陣列仍交給父類供預覽與自動建造使用，不修改共用結構定義。
 */
final class PrimordialStructurePattern extends BlockPattern {
    private static final Map<BlockPattern, BlockPattern> PATTERNS = new ConcurrentHashMap<>();

    private final int[][] cells;
    private final int[] repetitions;
    private final Method relativeOffset;

    private PrimordialStructurePattern(BlockPattern original, TraceabilityPredicate[][][] matches,
                                       int[] center, int[] repetitions, Method relativeOffset) {
        super(matches, original.structureDir, original.aisleRepetitions, center);
        this.cells = PrimordialPatternIndex.compile(matches, TraceabilityPredicate::isAny);
        this.repetitions = repetitions;
        this.relativeOffset = relativeOffset;
    }

    static BlockPattern optimize(BlockPattern original) {
        if (original == null) return null;
        return PATTERNS.computeIfAbsent(original, PrimordialStructurePattern::create);
    }

    private static BlockPattern create(BlockPattern original) {
        // 自訂 BlockPattern 子類可能改寫判定；不能繞過它的契約。
        if (original.getClass() != BlockPattern.class) return original;
        try {
            // 舊 GTLCore 不具備這組固定匹配／計時契約時，仍保留原生流程。
            Class.forName("org.gtlcore.gtlcore.api.pattern.WorldPatternTiming", false,
                    PrimordialStructurePattern.class.getClassLoader());
            int[] repetitions = FixedPatternLayout.repetitions(original.aisleRepetitions);
            if (repetitions == null || Boolean.getBoolean("gtlcore.world.disableFixedMatcher")) return original;
            Field matchesField = BlockPattern.class.getDeclaredField("blockMatches");
            Field centerField = BlockPattern.class.getDeclaredField("centerOffset");
            Method relativeOffset = BlockPattern.class.getDeclaredMethod("setActualRelativeOffset",
                    int.class, int.class, int.class, Direction.class, Direction.class, boolean.class);
            matchesField.setAccessible(true);
            centerField.setAccessible(true);
            relativeOffset.setAccessible(true);
            TraceabilityPredicate[][][] matches = (TraceabilityPredicate[][][]) matchesField.get(original);
            if (matches.length == 0 || matches[0].length == 0 || matches[0][0].length == 0) return original;
            return new PrimordialStructurePattern(original, matches, (int[]) centerField.get(original),
                    repetitions, relativeOffset);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError unsupported) {
            // 依賴契約不符時快取原物件，後續仍走原生判定，不在每次檢測重試反射。
            return original;
        }
    }

    @Override
    public boolean checkPatternAt(MultiblockState state, BlockPos center, Direction front,
                                  Direction upwards, boolean flipped, boolean savePredicate) {
        if (!(state.world instanceof ServerLevel) || !(state instanceof IMultiblockStateInvoker invoker)) {
            return super.checkPatternAt(state, center, front, upwards, flipped, savePredicate);
        }
        try (WorldPatternTiming.Matcher timing = WorldPatternTiming.beginMatcher(state, front, flipped)) {
            boolean matched = matchFixed(state, invoker, center, front, upwards, flipped, savePredicate);
            if (timing != null) timing.result(matched);
            return matched;
        }
    }

    private boolean matchFixed(MultiblockState state, IMultiblockStateInvoker invoker, BlockPos center,
                               Direction front, Direction upwards, boolean flipped, boolean savePredicate) {
        BlockPos[] axes;
        try {
            // 原生座標轉換是線性旋轉／鏡像；每次只求三條基底，免去逐格陣列與反射分配。
            axes = new BlockPos[]{offset(1, 0, 0, front, upwards, flipped),
                    offset(0, 1, 0, front, upwards, flipped), offset(0, 0, 1, front, upwards, flipped)};
        } catch (ReflectiveOperationException unsupported) {
            return super.checkPatternAt(state, center, front, upwards, flipped, savePredicate);
        }

        for (int startZ = -centerOffset[4]; startZ <= -centerOffset[3]; startZ++) {
            WorldPatternTiming.attempt(state, "shanhai_fixed_sparse");
            invoker.cleanState();
            int z = startZ;
            boolean matched = true;
            for (int aisle = 0; aisle < fingerLength && matched; aisle++) {
                for (int repeat = 0; repeat < repetitions[aisle]; repeat++, z++) {
                    if (!matchSlice(state, invoker, center, axes, aisle, z, savePredicate)) {
                        matched = false;
                        break;
                    }
                }
            }
            if (matched && hasRequiredCounts(state, state.getGlobalCount(), false)) {
                state.setError(null);
                state.setNeededFlip(flipped);
                state.getMatchContext().set(AdvancedBlockPattern.MATCHED_REPETITIONS_CONTEXT, repetitions.clone());
                state.getMatchContext().set(AdvancedBlockPattern.MATCHED_MIN_Z_CONTEXT, startZ);
                return true;
            }
            if (state.error == MultiblockState.UNLOAD_ERROR) return false;
        }
        if (!state.hasError()) state.setError(new PatternError());
        return false;
    }

    private BlockPos offset(int x, int y, int z, Direction front, Direction upwards, boolean flipped)
            throws ReflectiveOperationException {
        return (BlockPos) relativeOffset.invoke(this, x, y, z, front, upwards, flipped);
    }

    private boolean matchSlice(MultiblockState state, IMultiblockStateInvoker invoker, BlockPos center,
                               BlockPos[] axes, int aisle, int z, boolean savePredicate) {
        WorldPatternTiming.slice(state);
        state.getLayerCount().clear();
        PatternMatchContext context = state.getMatchContext();
        int[] slice = cells[aisle];
        for (int cell : slice) {
            state.setError(null);
            int row = cell / palmLength;
            int column = cell % palmLength;
            int x = column - centerOffset[0];
            int y = row - centerOffset[1];
            TraceabilityPredicate predicate = blockMatches[aisle][row][column];
            BlockPos pos = center.offset(
                    x * axes[0].getX() + y * axes[1].getX() + z * axes[2].getX(),
                    x * axes[0].getY() + y * axes[1].getY() + z * axes[2].getY(),
                    x * axes[0].getZ() + y * axes[1].getZ() + z * axes[2].getZ());
            if (!invoker.updateState(pos, predicate)) return false;
            if (predicate.addCache()) {
                state.addPosCache(pos);
                if (savePredicate) {
                    context.getOrCreate("predicates", Object2ObjectOpenHashMap::new).put(pos, predicate);
                }
            }

            boolean canShare = true;
            if (state.getTileEntity() instanceof IMachineBlockEntity machineBE
                    && machineBE.getMetaMachine() instanceof IMultiPart part) {
                if (part.isFormed() && !part.canShared() && !part.hasController(state.controllerPos)) {
                    canShare = false;
                    state.setError(new PatternStringError("multiblocked.pattern.error.share"));
                } else {
                    context.getOrCreate("parts", ObjectOpenHashSet::new).add(part);
                }
            }
            if (state.getBlockState().getBlock() instanceof ActiveBlock) {
                context.getOrCreate("vaBlocks", LongOpenHashSet::new).add(pos.asLong());
            }
            if (!predicate.test(state) || !canShare) return false;
            context.getOrCreate("ioMap", Long2ObjectOpenHashMap::new).put(pos.asLong(), state.io);
        }
        // 原生遍歷在尾端 any 格會清除錯誤；全空層也必須維持同樣行為。
        if (slice.length == 0 || slice[slice.length - 1] < thumbLength * palmLength - 1) {
            state.setError(null);
        }
        return hasRequiredCounts(state, state.getLayerCount(), true);
    }

    private static boolean hasRequiredCounts(MultiblockState state, Map<SimplePredicate, Integer> counts,
                                             boolean layer) {
        for (Map.Entry<SimplePredicate, Integer> entry : counts.entrySet()) {
            SimplePredicate predicate = entry.getKey();
            if (entry.getValue() < (layer ? predicate.minLayerCount : predicate.minCount)) {
                state.setError(new SinglePredicateError(predicate, layer ? 3 : 1));
                return false;
            }
        }
        return true;
    }
}
