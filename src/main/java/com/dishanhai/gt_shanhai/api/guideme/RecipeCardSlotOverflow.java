package com.dishanhai.gt_shanhai.api.guideme;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/**
 * 配方卡槽位截断计数器。
 *
 * <p>{@link com.dishanhai.gt_shanhai.mixin.GTLytSlotGridOverflowMixin} 每阻断一个越界槽位
 * 就 {@link #increment()} 一次；{@link RecipeCardBlockFactory} 在构建输入/输出网格前
 * {@link #reset()}、构建后 {@link #get()}，把被截断的格数写进配方卡文本。</p>
 *
 * <p>用 ThreadLocal 隔离编译线程，避免其他 GTLytSlotGrid 使用者（JEI 等）的计数串扰。
 * GuideME 页面编译时同步调用 getRecipeInput/Output，reset→build→get 三步紧邻，无并发问题。</p>
 */
@OnlyIn(Dist.CLIENT)
public final class RecipeCardSlotOverflow {

    private static final ThreadLocal<int[]> COUNT = ThreadLocal.withInitial(() -> new int[1]);

    private RecipeCardSlotOverflow() {}

    public static void reset() {
        COUNT.get()[0] = 0;
    }

    public static void increment() {
        COUNT.get()[0]++;
    }

    public static int get() {
        return COUNT.get()[0];
    }
}
