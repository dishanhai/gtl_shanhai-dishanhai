package com.dishanhai.gt_shanhai.client.gui.shop;

import net.minecraft.world.item.ItemStack;

import java.util.List;

/**
 * JEI 物品排序结果的纯 Java 中转站（山海署名，客户端）。
 *
 * <p>本类不出现任何 JEI 类型引用——{@link com.dishanhai.gt_shanhai.jei.ShanhaiJEIPlugin}
 * （只有装了 JEI，才会被 JEI 自己的插件扫描器加载、调用 onRuntimeAvailable）把 JEI 最终成品
 * 摄取管理器（已排序、已过滤隐藏黑名单、含本模组通过 JEI 额外注册的摄取物如 SDA 包）转换成
 * 纯 {@link ItemStack} 列表后存进这里。{@link MultiPickerScreen} 只读这个类，不直接引用任何
 * JEI API 类型，未装 JEI / JEI 尚未就绪时 {@link #get()} 恒返回 {@code null}，调用方按原有
 * 创造栏顺序兜底，不受影响。</p>
 */
public final class JeiItemOrderHolder {

    private static volatile List<ItemStack> ordered;

    private JeiItemOrderHolder() {}

    public static void set(List<ItemStack> items) {
        ordered = items;
    }

    /** JEI 排好序的全物品列表；未装 JEI / 尚未就绪返回 null。 */
    public static List<ItemStack> get() {
        return ordered;
    }
}
