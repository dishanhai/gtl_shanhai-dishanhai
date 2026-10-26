package com.shanhai.mixin.probe;

import com.gregtechceu.gtceu.api.machine.feature.multiblock.IMultiController;
import com.gregtechceu.gtceu.api.pattern.BlockPattern;
import com.gregtechceu.gtceu.api.pattern.MultiblockState;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayDeque;

/**
 * <b>只读计时探针 · 图案检查入口</b>{@code BlockPattern#checkPatternAt(MultiblockState, boolean)}。
 *
 * <h2>🔴 这是只读探针，验收后可整包删除</h2>
 * 本类<b>只读字段、只计时、只打日志</b>；没有任何一处 {@code cir.setReturnValue}、
 * 没有 {@code cir.cancel()}、不写目标对象的任何字段。所有入口都是 {@code require = 0}
 * ⇒ 注入失败<b>不崩</b>，最坏情况是这类日志一行都没有（此时"没有日志"必须读成
 * "探针没挂上"，<b>不能</b>读成"没有慢检查"）。
 *
 * <h2>为什么盯这个方法</h2>
 * 上游三条路最后都汇到这里（{@code javap -p -c} 实证，见 {@code temp\hang\报告.md} §1.2）：
 * <pre>
 *   ① MultiblockState#onBlockStateChanged 偏移 148: checkPatternWithLock()
 *        ← ChunkMixin#gtceu$onChunkChanged（每次方块改变）→ MinecraftServer.execute → 本方法
 *   ② MultiblockControllerMachine#asyncCheckPattern 偏移 38: checkPatternWithTryLock()
 *        ← GTCEu 异步线程每 250ms 扫一遍（未成型机器每 4 个 period 才真正查）
 *   ③ MultiblockControllerMachine#lambda$asyncCheckPattern$2 偏移 10: checkPatternWithLock()
 *        ← 成型那一瞬间，<b>在服务器线程上再全量查一遍</b>
 * </pre>
 * 本方法体内是 {@code fingerLength × thumbLength × palmLength} 三重循环
 * （归墟视界枢核 = 148×135×131 ≈ 261.7 万格，<b>空格子也照样走一遍</b>），
 * 所以"一次检查多贵"就等于"这一个数字"。
 *
 * <h2>为什么 {@code formed} 不调 {@code MultiblockState#getController()}</h2>
 * {@code getController()} 有<b>副作用</b>：区块没加载时它会把 {@code error} 写成
 * {@code UNLOAD_ERROR}，成功时又会写 {@code lastController}（{@code javap -c MultiblockState}
 * 偏移 57-63 / 67-71）。探针不许改状态 ⇒ 只读那个<b>公开字段</b>
 * {@code lastController}（{@code public IMultiController lastController}），
 * 再调只读的 {@code isFormed()}。
 *
 * <h2>字段口径</h2>
 * <pre>
 *   [SHANHAI-PATPROBE] check ms=… pattern=WxHxD formed=… pos=… cause=… cache=… thread=… depth=…
 *   pattern = palmLength x thumbLength x fingerLength（列 x 行 x 深度，与 .bin 的 131x135x148 对齐）
 *   formed  = 该控制器当前 isFormed()（读 lastController，不改状态）
 *   pos     = MultiblockState.controllerPos（公开 final 字段）
 *   cause   = onFormed / server / gtceu-async / <线程名>（见 PatternTimingProbe#threadTag）
 *   cache   = MultiblockState.cache.size()（只有非 any() 格子进 cache，见 §1.2）
 *   depth   = 同一线程上嵌套调用的层数（递归/重入时会 >1）
 * </pre>
 */
@Mixin(value = BlockPattern.class, remap = false)
public class ShanhaiPatternTimingProbeMixin {

    @Shadow
    @Final
    protected int fingerLength;

    @Shadow
    @Final
    protected int thumbLength;

    @Shadow
    @Final
    protected int palmLength;

    /** 每线程一个计时栈（检查会递归：成型回调里改方块 ⇒ 又一次检查）。 */
    private static final ThreadLocal<ArrayDeque<Long>> SHANHAI$STACK =
            ThreadLocal.withInitial(ArrayDeque::new);

    /** HEAD：压入起始时刻。只读、无返回值改动。 */
    @Inject(
            method = "checkPatternAt(Lcom/gregtechceu/gtceu/api/pattern/MultiblockState;Z)Z",
            at = @At("HEAD"),
            require = 0,
            remap = false
    )
    private void shanhai$probeCheckStart(MultiblockState state, boolean simulate,
                                        CallbackInfoReturnable<Boolean> cir) {
        try {
            SHANHAI$STACK.get().push(Long.valueOf(System.nanoTime()));
        } catch (Throwable ignored) {
            // 探针异常绝不许冒进 gtceu 的检查路径
        }
    }

    /** RETURN：取耗时，≥50ms 才打一行。 */
    @Inject(
            method = "checkPatternAt(Lcom/gregtechceu/gtceu/api/pattern/MultiblockState;Z)Z",
            at = @At("RETURN"),
            require = 0,
            remap = false
    )
    private void shanhai$probeCheckEnd(MultiblockState state, boolean simulate,
                                      CallbackInfoReturnable<Boolean> cir) {
        try {
            final ArrayDeque<Long> stack = SHANHAI$STACK.get();
            final Long startObj = stack.isEmpty() ? null : stack.pop();
            if (startObj == null) {
                return;
            }
            final long nanos = System.nanoTime() - startObj.longValue();
            final double ms = nanos / 1_000_000.0;
            PatternTimingProbe.recordCheck(nanos);
            if (ms < 50.0) {
                return;
            }
            final int depth = stack.size() + 1;
            String dims = "?x?x?";
            try {
                dims = palmLength + "x" + thumbLength + "x" + fingerLength;
            } catch (Throwable ignored) {
                // 影子字段取不到就退回 ?x?x?
            }
            PatternTimingProbe.LOGGER.info(
                    "[SHANHAI-PATPROBE] check ms={} pattern={} formed={} pos={} cause={} cache={} thread={} depth={}",
                    PatternTimingProbe.round3(ms), dims, formedFlag(state), posString(state),
                    causeLabel(), cacheSize(state), PatternTimingProbe.threadTag(), depth);
        } catch (Throwable ignored) {
            // 同上：探针绝不许影响 checkPatternAt 的返回值
        }
    }

    /** 读 {@code lastController}（公开字段）+ 只读 {@code isFormed()}；绝不调 {@code getController()}。 */
    private static boolean formedFlag(MultiblockState state) {
        try {
            if (state == null) {
                return false;
            }
            final IMultiController controller = state.lastController;
            return controller != null && controller.isFormed();
        } catch (Throwable t) {
            return false;
        }
    }

    private static String posString(MultiblockState state) {
        try {
            if (state == null || state.controllerPos == null) {
                return "?";
            }
            final var p = state.controllerPos;
            return "(" + p.getX() + "," + p.getY() + "," + p.getZ() + ")";
        } catch (Throwable t) {
            return "?";
        }
    }

    private static int cacheSize(MultiblockState state) {
        try {
            if (state == null || state.cache == null) {
                return -1;
            }
            return state.cache.size();
        } catch (Throwable t) {
            return -1;
        }
    }

    private static String causeLabel() {
        if (PatternTimingProbe.inFormedCallback()) {
            return "onFormed";
        }
        return PatternTimingProbe.threadTag();
    }
}
