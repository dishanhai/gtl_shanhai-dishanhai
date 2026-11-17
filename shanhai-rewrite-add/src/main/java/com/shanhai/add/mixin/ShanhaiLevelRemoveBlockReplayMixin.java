package com.shanhai.add.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 山海重构 · <b>方块"破坏"的独立见证点</b>：{@code Level#removeBlock} HEAD。
 *
 * <h2>1. 为什么破坏要单独挂一处（2026-11 真机实测）</h2>
 * 用户原话：「现在我看到的最大的问题是方块状态回不来，并不是个别，而是所有」。
 * 破坏链是 {@code ServerPlayerGameMode#destroyBlock → Level#removeBlock → Level#setBlock
 * → LevelChunk#setBlockState}；只挂最内层理论上能看见，但那条链是"多个 mod 都可能改写"的：
 * <ul>
 *   <li>{@code Level#setBlock} 在<b>目标格已经是空气</b>时会提前 return
 *       （同态早退），早期实现因此把每一笔"挖掉"都判成写失败；</li>
 *   <li>第三方 mod 可能绕过 {@code setBlock} 直接动 chunk。</li>
 * </ul>
 * ⇒ 在最外层再放一个见证点，代价是一个 ThreadLocal（{@code ShanhaiReplayRecorder#SWALLOW}），
 * 收益是"破坏<b>结构上</b>不可能漏记"。
 *
 * <h2>2. 🔴 混入类体内零 Minecraft 成员引用（除了形参类型）</h2>
 * 本工程的 refmap 只映射 {@code @Inject} 选中的方法名 ⇒ 混入体内直接碰 MC 成员会拿开发名去
 * SRG 命名的目标类上找 ⇒ 找不到 ⇒ 混入失败或崩。所以这里只做"形参转交"，
 * 真正的 {@code getBlockState} 等操作全在 {@code ShanhaiReplayRecorder}（普通类，会被 reobf）。
 *
 * <h2>3. {@code require = 0} 的理由</h2>
 * 这个见证点是"第二保险"：万一上游改了 {@code removeBlock} 的签名，我们宁可少一层见证，
 * 也不要整合包起不来。挂没挂上由 {@code hooks_armed blocks_destroy=hook:Level#removeBlock ok=…}
 * 那一行如实报告（反射数 {@code shanhai$recordRemoveForReplay}）。
 */
@Mixin(Level.class)
public abstract class ShanhaiLevelRemoveBlockReplayMixin {

    @Inject(method = "removeBlock(Lnet/minecraft/core/BlockPos;Z)Z", at = @At("HEAD"), require = 0)
    private void shanhai$recordRemoveForReplay(BlockPos pos, boolean isMoving,
                                               CallbackInfoReturnable<Boolean> cir) {
        try {
            com.shanhai.add.time.replay.ShanhaiReplayRecorder.onRemoveBlock((Object) this, pos);
        } catch (Throwable ignored) {
            // 第二道保险：真正的自吞在记录器内部（它坏了绝不能挡住方块被挖掉）
        }
    }
}
