package com.shanhai.mixin.probe;

import com.gregtechceu.gtceu.api.pattern.MultiblockState;
import com.shanhai.common.diag.ShanhaiPatternCheckCoalescer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

/**
 * <b>成型检查合并器的混入点</b>{@code MultiblockState#onBlockStateChanged(BlockPos, BlockState)V}。
 *
 * <h2>这是唯一一处<b>会改变行为</b>的注入（其余 {@code *ProbeMixin} 都只打日志）</h2>
 * 它把"每次方块变化 ⇒ 立刻一次 261.7 万格全量检查"改成"同一 tick 攒起来、末尾查一次"。
 * 判据与红线全部在 {@link ShanhaiPatternCheckCoalescer} 的类注释里，这里只负责那一行判断。
 * 开关 = {@code config/shanhai-common.toml} 的 {@code [diag] pattern_check_coalesce}（默认 <b>true</b>）。
 *
 * <h2>注入点为什么是 HEAD + cancellable</h2>
 * 上游调用链（{@code javap -p -c} 实证，见 {@code temp\hang\报告.md} §1.1）：
 * <pre>
 *   ChunkMixin#gtceu$onChunkChanged 偏移 83-94：server.execute(() -> state.onBlockStateChanged(pos, newState))
 *   MultiblockControllerMachine#lambda$asyncCheckPattern$2 偏移 68-75：同上（服务器线程，成型那一瞬）
 * </pre>
 * 两个入口都<b>直接</b>调 {@code onBlockStateChanged}，所以只要在它的 HEAD 上把这次调用"存起来 + cancel"，
 * 就等于把检查推到 tick 末尾；方法体<b>一行都不改</b>。
 *
 * <h2>🔴 守卫必须放行（否则永远查不到）</h2>
 * {@link ShanhaiPatternCheckCoalescer#flush()} 在 tick 末尾会<b>再次</b>调这个方法。如果那时不特判，
 * 它会被自己再 defer 一次 ⇒ 永远不检查 ⇒ 结构永远不成型（这是一条会把机器彻底弄坏的路径）。
 * 所以判据的第一句就是 {@code isFlushing()} —— <b>守卫置位时直接 return，让原逻辑跑完</b>。
 *
 * <h2>为什么异常时"不 cancel"</h2>
 * {@code ci.cancel()} 只在 {@link ShanhaiPatternCheckCoalescer#defer} <b>成功返回之后</b>才执行；
 * 整个方法体被 {@code try/catch(Throwable)} 包住 ⇒ 任何一步出问题都<b>不取消</b>，
 * 原版 {@code onBlockStateChanged} 照原样跑完（红线 4：不能因为合并把成型判定搞坏）。
 */
@Mixin(value = MultiblockState.class, remap = false)
public class ShanhaiMultiblockStateCoalescerMixin {

    @Inject(
            method = "onBlockStateChanged(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;)V",
            at = @At("HEAD"),
            cancellable = true,
            require = 0,
            remap = false
    )
    private void shanhai$coalesceOnBlockStateChanged(BlockPos pos, BlockState blockState, CallbackInfo ci) {
        try {
            // ① 守卫优先：flush 自己发起的那次必须放行（判据顺序刻意写成"守卫在前"，比"先算 shouldDefer"更省也更好读）
            if (ShanhaiPatternCheckCoalescer.isFlushing()) {
                return;
            }
            // ② 三条判据全满足才合并（服务器线程 / 我们这台 / 大图案）—— 详见合并器类注释
            final MultiblockState self = (MultiblockState) (Object) this;
            if (!ShanhaiPatternCheckCoalescer.shouldDefer(self)) {
                return;
            }
            // ③ 存起来；只有成功存下才取消本次调用
            ShanhaiPatternCheckCoalescer.defer(self, pos, blockState);
            ci.cancel();
        } catch (Throwable ignored) {
            // 🔴 红线 4：任何异常都让【原逻辑照常执行】——这里绝不 cancel、也绝不向上抛
        }
    }
}
