package com.dishanhai.gt_shanhai.mixin;

import com.dishanhai.gt_shanhai.api.guideme.RecipeCardSlotOverflow;
import com.gregtechceu.gtceu.api.recipe.content.Content;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 修复 gtladditions 配方卡输出物超出网格容量时崩溃。
 *
 * <p>gtladditions 的 {@code GTLytSlotGrid} 网格尺寸按 {@code recipeType.maxOutputs}
 * 计算：{@code width = min(items,3)}、{@code height = ceil(items/3)}，槽数组长度 = width×height。
 * 当某配方实际输出数**超过**其类型声明的 maxOutputs 时（如声明 13 输出、实际 16 输出），
 * 放置超额槽位会命中 {@code slots[15]}（数组长度 15）→ 抛 IndexOutOfBoundsException，
 * 被 RecipeCard 的 catch 变成红字丢给玩家。</p>
 *
 * <p>本 mixin 在 {@code addSlot} 入口做**数量阻断**：坐标超出网格范围时直接跳过该槽位
 * （超额输出物截断不画），既不崩溃也不报错。同时修正 gtladditions 自身
 * {@code y <= height} 的 off-by-one 越界放行。输入/输出网格同受保护。</p>
 */
@OnlyIn(Dist.CLIENT)
@Mixin(targets = "com.gtladd.gtladditions.api.gui.GTLytSlotGrid", remap = false)
public class GTLytSlotGridOverflowMixin {

    @Shadow
    @Final
    private int width;

    @Shadow
    @Final
    private int height;

    @Inject(method = "addSlot", at = @At("HEAD"), cancellable = true, remap = false)
    private void gtShanhai$blockOverflowSlot(int x, int y, Content content, CallbackInfo ci) {
        // 阻断越界槽位：index = y*width+x，只要 x/y 在 [0,width)×[0,height) 内即保证 index < width*height。
        if (x < 0 || x >= width || y < 0 || y >= height) {
            RecipeCardSlotOverflow.increment();
            ci.cancel();
        }
    }
}
