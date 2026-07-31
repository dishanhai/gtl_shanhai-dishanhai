package com.dishanhai.gt_shanhai.common.machine.output;

import com.dishanhai.gt_shanhai.api.machine.output.IOutputMultiplierSource;
import com.dishanhai.gt_shanhai.common.machine.wave.GravitationalWaveBroadcastManager;
import com.dishanhai.gt_shanhai.config.DShanhaiConfig;
import com.gregtechceu.gtceu.api.machine.feature.multiblock.IMultiController;
import com.gregtechceu.gtceu.api.machine.feature.multiblock.IMultiPart;
import com.gtladd.gtladditions.api.machine.feature.IThreadModifierPart;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import org.gtlcore.gtlcore.utils.NumberUtils;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;

/**
 * 星律/AE 下单用的宿主产出倍率聚合器。
 * <p>
 * 确定性倍率来源按 source key 去重后叠乘；线程通道沿用现有机器逻辑，
 * 在 hubOutputMultiplier 开启时把同一宿主内的线程数先相加，再作为一个倍率通道参与叠乘。
 */
public final class OutputMultiplierResolver {

    private OutputMultiplierResolver() {}

    public static long resolveHostOutputMultiplier(Iterable<?> controllers,
            @Nullable Level level, @Nullable BlockPos pos) {
        Accumulator accumulator = new Accumulator();
        if (controllers != null) {
            for (Object controller : controllers) {
                collectController(controller, accumulator);
            }
        }
        accumulator.multiply(resolveFixedBroadcastMultiplier(level, pos));
        return accumulator.result();
    }

    private static void collectController(Object controller, Accumulator accumulator) {
        collectOutputSource(controller, accumulator);
        if (!(controller instanceof IMultiController multiController)) return;

        Iterable<? extends IMultiPart> parts = multiController.getParts();
        if (parts == null) return;
        for (IMultiPart part : parts) {
            collectOutputSource(part, accumulator);
            if (DShanhaiConfig.COMMON.hubOutputMultiplier.get()
                    && part instanceof IThreadModifierPart threadPart) {
                accumulator.addThread(threadPart.getThreadCount());
            }
        }
    }

    private static void collectOutputSource(Object candidate, Accumulator accumulator) {
        if (!(candidate instanceof IOutputMultiplierSource source)) return;
        accumulator.addSource(source.getOutputMultiplierSourceKey(),
                source.getOutputMultiplierContribution());
    }

    private static long resolveFixedBroadcastMultiplier(@Nullable Level level, @Nullable BlockPos pos) {
        if (!(level instanceof ServerLevel serverLevel) || pos == null) return 1L;
        int fixed = GravitationalWaveBroadcastManager.INSTANCE.getFixedOutputMultiplier(serverLevel, pos);
        return fixed > 1 ? fixed : 1L;
    }

    static long sanitizeMultiplier(long value) {
        return value <= 1L ? 1L : value;
    }

    private static final class Accumulator {

        private final Map<Object, Long> sourceMultipliers = new HashMap<>();
        private long threadMultiplier;
        private long fixedMultiplier = 1L;

        void addSource(Object sourceKey, long multiplier) {
            long sanitized = sanitizeMultiplier(multiplier);
            Object key = sourceKey == null ? this : sourceKey;
            sourceMultipliers.merge(key, sanitized, Math::max);
        }

        void addThread(int threads) {
            if (threads <= 0) return;
            long next = threadMultiplier + (long) threads;
            threadMultiplier = next < threadMultiplier ? Long.MAX_VALUE : next;
        }

        void multiply(long multiplier) {
            fixedMultiplier = NumberUtils.saturatedMultiply(fixedMultiplier, sanitizeMultiplier(multiplier));
        }

        long result() {
            long result = fixedMultiplier;
            if (threadMultiplier > 0L) {
                result = NumberUtils.saturatedMultiply(result, threadMultiplier);
            }
            for (long sourceMultiplier : sourceMultipliers.values()) {
                result = NumberUtils.saturatedMultiply(result, sourceMultiplier);
            }
            return sanitizeMultiplier(result);
        }
    }
}
