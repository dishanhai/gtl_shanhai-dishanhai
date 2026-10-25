package com.shanhai.mixin.probe;

import com.shanhai.machine.host.GuixuVisionCoreMachine;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * <b>只读计时探针 · 我们自己的成型回调</b>{@code GuixuVisionCoreMachine#onStructureFormed()V}。
 *
 * <h2>🔴 这是只读探针，验收后可整包删除</h2>
 * 本类只做"进 → 记时刻 / 出 → 算耗时 → ≥20ms 打一行"，<b>没有</b>
 * {@code cir.setReturnValue}、<b>没有</b> {@code cancel()}、<b>不写</b>目标对象的任何字段
 * （唯一的静态写是自己 {@link PatternTimingProbe} 里的嵌套深度计数器）。
 * {@code require = 0} ⇒ 注入失败不崩；这时"没有 onFormed 行"同样只能读成"探针没挂上"。
 *
 * <h2>为什么这一层要单独计时</h2>
 * 这一层把三类完全不同的代价<b>混</b>在一起，不拆开就分不清是谁卡了服务器：
 * <ol>
 *   <li>基类 {@code PrimordialOmegaEngineMachine#onStructureFormed()}：
 *       {@code reconcileModules()} → {@code scanAndConnectModules()}（24 个槽位逐个
 *       {@code MetaMachine.getMachine}）；</li>
 *   <li>本类追加的 {@code refreshMatterLevelOverride()} / {@code refreshModuleMask()} /
 *       {@code logModuleSlots()}（同样只遍历 24 个槽位，O(24)，量级应在毫秒以下）；</li>
 *   <li>🔴 <b>隐藏在里面的大头</b>：这一层里若有任何方块写入，会经
 *       {@code LevelChunk#setBlockState} → {@code ChunkMixin#gtceu$onChunkChanged} →
 *       {@code MinecraftServer.execute} → {@code MultiblockState#onBlockStateChanged} →
 *       {@code checkPatternWithLock()} ⇒ <b>一次完整的 261.7 万格图案检查</b>
 *       （每一次这种写入都算一次，且会在本方法的耗时里以 {@code cause=onFormed} 的
 *       {@code check} 行成对出现）。</li>
 * </ol>
 * ⇒ 判据：如果 {@code onFormed ms} 很大，而同一窗口里 {@code check cause=onFormed}
 * 出现很多行，那就<b>不是</b>我们的 Java 循环慢，而是"成型回调里在写方块"。
 */
@Mixin(value = GuixuVisionCoreMachine.class, remap = false)
public class ShanhaiHostFormedProbeMixin {

    /** HEAD：记时刻 + 压深度（深度让 check 探针能打出 {@code cause=onFormed}）。 */
    @Inject(method = "onStructureFormed()V", at = @At("HEAD"), require = 0, remap = false)
    private void shanhai$probeFormedStart(CallbackInfo ci) {
        try {
            SHANHAI$START.set(Long.valueOf(System.nanoTime()));
            PatternTimingProbe.enterFormed();
        } catch (Throwable ignored) {
            // 探针绝不许影响成型回调
        }
    }

    /** RETURN：算耗时，≥20ms 才打一行。 */
    @Inject(method = "onStructureFormed()V", at = @At("RETURN"), require = 0, remap = false)
    private void shanhai$probeFormedEnd(CallbackInfo ci) {
        try {
            final Long startObj = SHANHAI$START.get();
            if (startObj != null) {
                final long nanos = System.nanoTime() - startObj.longValue();
                final double ms = nanos / 1_000_000.0;
                PatternTimingProbe.recordFormed(nanos);
                if (ms >= 20.0) {
                    PatternTimingProbe.LOGGER.info(
                            "[SHANHAI-PATPROBE] onFormed ms={} pos={} formed={} cause={} thread={}",
                            PatternTimingProbe.round3(ms), posString((GuixuVisionCoreMachine) (Object) this),
                            formedFlag((GuixuVisionCoreMachine) (Object) this), causeLabel(),
                            PatternTimingProbe.threadTag());
                }
            }
        } catch (Throwable ignored) {
            // 同上
        } finally {
            try {
                PatternTimingProbe.exitFormed();
            } catch (Throwable ignored) {
                // ignore
            }
        }
    }

    private static final ThreadLocal<Long> SHANHAI$START = new ThreadLocal<>();

    private static String posString(GuixuVisionCoreMachine machine) {
        try {
            final var p = machine.getPos();
            return "(" + p.getX() + "," + p.getY() + "," + p.getZ() + ")";
        } catch (Throwable t) {
            return "?";
        }
    }

    private static boolean formedFlag(GuixuVisionCoreMachine machine) {
        try {
            return machine.isFormed();
        } catch (Throwable t) {
            return false;
        }
    }

    private static String causeLabel() {
        return PatternTimingProbe.inFormedCallback() ? "nested" : "top";
    }
}
