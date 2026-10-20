package com.dishanhai.gt_shanhai.mixin;

import com.gregtechceu.gtceu.api.pattern.MultiblockState;
import com.gregtechceu.gtceu.api.pattern.predicates.SimplePredicate;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.HashMap;
import java.util.Map;

/**
 * Keeps the match buffers of {@link MultiblockState} non-null for states that were never matched.
 *
 * <p>GTCEu 只在 {@code clean()} 裡建立 {@code cache}、{@code globalCount}、{@code layerCount}，
 * 而 {@code clean()} 是結構匹配的第一步。只要一次匹配都沒跑起來（例如預覽世界的控制器
 * 沒能成型），這三個欄位就一直為 null。
 *
 * <p>GTLCore 的 JEI 預覽在翻頁/關閉時會走
 * {@code PatternPreviewWidget.clearSharedPreviewState()} →
 * {@code MBPattern.enhanced$releasePreviewMemory()}，裡面連續對這三者做
 * {@code cache.clear()/trim()}、{@code getGlobalCount().clear()}、{@code getLayerCount().clear()}。
 * 任一個為 null 就拋 NPE，把 JEI 的配方布局更新整條打斷（{@code RecipesGui.updateLayout}），
 * 表現就是「JEI 結構預覽打不開」。本模組的 .mb 大型結構（天界領航塔 247 層、
 * 黑洞收容 186 層）正是最容易踩到的對象。
 *
 * <p>這裡在構造器尾端補上與 {@code clean()} 之後等價的空容器。對已經跑過匹配的狀態
 * 沒有任何行為差異（{@code clean()} 會重新賦值）。
 */
@Mixin(value = MultiblockState.class, remap = false)
public abstract class MultiblockStateCacheInitMixin {

    @Shadow(remap = false)
    public LongOpenHashSet cache;

    @Shadow(remap = false)
    private Map<SimplePredicate, Integer> globalCount;

    @Shadow(remap = false)
    private Map<SimplePredicate, Integer> layerCount;

    @Inject(method = "<init>", at = @At("TAIL"), remap = false)
    private void gtShanhai$initMatchBuffers(Level world, BlockPos controllerPos, CallbackInfo ci) {
        if (this.cache == null) {
            this.cache = new LongOpenHashSet();
        }
        if (this.globalCount == null) {
            this.globalCount = new HashMap<>();
        }
        if (this.layerCount == null) {
            this.layerCount = new HashMap<>();
        }
    }
}
