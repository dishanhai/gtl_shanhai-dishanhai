package com.shanhai.add.mixin;

import com.shanhai.add.time.ShanhaiClockGuard;
import com.shanhai.add.time.ShanhaiClockHooks;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 山海重构 · 「冻结期间禁止改世界」的<b>硬网</b>：{@code Level#setBlock} 一律返回 {@code false}。
 *
 * <p>它不是主防线（主防线是 {@code ShanhaiClockGuard} 的事件层，那里能给玩家一句提示），
 * 而是第三道保险：兜住"某个第三方 mod 走了我们没挂事件的路径"。
 *
 * <p>🔴 <b>不用担心理赔世界生成</b>：世界生成走的是 {@code WorldGenRegion}/{@code ProtoChunk}
 * （不继承 {@code Level}），本 mixin 完全碰不到它 ⇒ 暂停期间即使触发新区块生成也不会被拦。
 *
 * <p>目标方法：{@code Level#setBlock(BlockPos, BlockState, int, int)Z}（1.20.1 {@code Level.java:199}）。
 * 3 参重载内部转调它，所以只拦 4 参这一个就够。
 */
@Mixin(Level.class)
public abstract class ShanhaiLevelSetBlockClockMixin {

    @Inject(
            method = "setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)Z",
            at = @At("HEAD"),
            require = 1,
            cancellable = true)
    private void shanhai$denyWorldWrite(BlockPos pos, BlockState state, int flags, int recursionLeft,
                                        CallbackInfoReturnable<Boolean> cir) {
        try {
            // 🔴 2026-10-18：判定下沉到 ShanhaiClockHooks#onLevelSetBlock，它【按侧分流】——
            //    服务端照旧全拦；客户端（ClientLevel）放行，因为那是纯表现写
            //    （GtlAddClientCompat 的藏环/恢复环就是客户端 setBlock）。
            //    旧写法 denyWorldWrite() 只判 SERVER.isPaused() ⇒ 暂停期间客户端的藏环写入被拦掉
            //    ⇒ 环上那一整片实体方块露出来（用户实机缺陷 ⑤）。
            if (ShanhaiClockHooks.onLevelSetBlock((Object) this)) {
                cir.setReturnValue(false);
            }
        } catch (Throwable ignored) {
            // 硬网自身出错 ⇒ 放行（宁可漏拦一次，也不能让正常游戏写不进方块）
        }
    }
}
