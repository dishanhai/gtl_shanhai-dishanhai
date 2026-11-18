package com.dishanhai.gt_shanhai.mixin.replay;

import net.minecraft.client.renderer.LevelRenderer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * 山海重构 · 客户端：{@code LevelRenderer#ticks} 的访问器（只给"倒放雨"用）。
 *
 * <p>1.20.1 的雨相位是 {@code (float)this.ticks + partialTick}
 * （{@code LevelRenderer.java:291}，另有 {@code :334} 的哈希与 {@code :357} 的雪滚动），
 * 而 {@code ticks}（{@code :190}）是<b>整数</b>、每 tick 才 +1。
 *
 * <p>⇒ 冻结期间我们把它写成<b>由回放游标驱动的递减值</b>：几何/密度/渐隐全部保持原版，
 * 雨照常可见，只是相位倒着走（往上）。
 */
@Mixin(LevelRenderer.class)
public interface ShanhaiLevelRendererTicksAccessor {

    @Accessor("ticks")
    void shanhai$setTicks(int ticks);

    @Accessor("ticks")
    int shanhai$getTicks();
}
