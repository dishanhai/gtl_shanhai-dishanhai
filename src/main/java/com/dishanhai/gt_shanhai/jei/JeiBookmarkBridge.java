package com.dishanhai.gt_shanhai.jei;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import net.minecraftforge.fluids.FluidStack;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * JEI 书签桥接（山海署名，客户端）。软依赖 JEI，纯反射实现——刻意不直接 import 任何 JEI 类型，
 * 哪怕 {@code mezz.jei.api.*} 平时可以安全直接引用（见 {@link ShanhaiJEIPlugin}）：这个类的公开方法
 * 会被完全不受 JEI 门控的 {@code MultiPickerScreen} 直接调用，一旦字段/方法签名里出现具体的 JEI 类型，
 * 未装 JEI 时类加载校验就会失败——跟 {@link com.dishanhai.gt_shanhai.client.gui.scaled.PinyinSearchBridge}
 * 的道理一样，同一个软依赖降级模式。{@code net.minecraftforge.*}/vanilla 类型不受此限制（Forge 本来就是
 * 整个模组的硬依赖）。
 *
 * <p>JEI 官方 {@code IBookmarkOverlay} 只暴露"鼠标悬停在哪个书签上"，没有公开的"读取完整书签列表"接口
 * （读过 jei-1.20.1-forge-15.20.0.129 源码确认），只能反射进内部实现：{@code IBookmarkOverlay} 实例的
 * 私有字段 {@code bookmarkList}（{@code BookmarkList} 类型）→ 其私有字段 {@code bookmarksList}
 * （{@code List<IBookmark>}）→ 逐项反射调用 {@code IngredientBookmark#getIngredient()} 拿到
 * {@code ITypedIngredient} → 再反射调用其无参 {@code getIngredient()} 拿到真正的原始摄取物（{@code ItemStack}
 * 走 vanilla 物品栈规范化路径会带回原 NBT，见 {@code TypedItemStack}/{@code NormalizedTypedItemStack} 源码；
 * 流体走 Forge 集成时原始值就是 {@code net.minecraftforge.fluids.FluidStack}）。按 {@code instanceof} 分流成
 * 物品/流体两条结果；配方书签等既不是 ItemStack 也不是 FluidStack，自然被跳过。</p>
 *
 * <p>运行时引用由 {@link ShanhaiJEIPlugin#onRuntimeAvailable} 在 JEI 就绪时以 {@code Object} 形式塞进来
 * （那个类本身已经安全 import 了 JEI 类型，往这边传的时候自动转型抹掉，这边不需要反射构造运行时对象）。</p>
 */
public final class JeiBookmarkBridge {

    private static volatile Object runtimeRef;

    private JeiBookmarkBridge() {}

    /** 由 {@link ShanhaiJEIPlugin#onRuntimeAvailable} 调用，塞入 JEI 运行时引用（{@code IJeiRuntime}，以 Object 接收）。 */
    public static void setRuntime(Object runtime) {
        runtimeRef = runtime;
    }

    /** 是否已就绪（JEI 已装且运行时已可用）。 */
    public static boolean available() {
        return runtimeRef != null;
    }

    /**
     * 读取 JEI 当前书签列表里全部「物品类」条目（保留原 NBT；跳过流体/配方等其他类型书签）。
     * 未装 JEI / 运行时未就绪 / 反射失败时静默返回空列表，不抛异常，调用方按空列表处理即可。
     */
    public static List<ItemStack> getBookmarkedItemStacks() {
        List<ItemStack> result = new ArrayList<>();
        forEachRawIngredient(raw -> {
            if (raw instanceof ItemStack stack && !stack.isEmpty()) result.add(stack);
        });
        return result;
    }

    /**
     * 读取 JEI 当前书签列表里全部「流体类」条目对应的流体类型（去重，只取基础 {@link Fluid}，
     * 不含流体 NBT/数量——跟本选择器「流体」浏览模式的既有语义一致，见 {@code ALL_FLUIDS}）。
     * 未装 JEI / 运行时未就绪 / 反射失败时静默返回空列表。
     */
    public static List<Fluid> getBookmarkedFluids() {
        List<Fluid> result = new ArrayList<>();
        forEachRawIngredient(raw -> {
            if (raw instanceof FluidStack fluidStack) {
                Fluid f = fluidStack.getFluid();
                if (f != null && f != Fluids.EMPTY && !result.contains(f)) result.add(f);
            }
        });
        return result;
    }

    /** 遍历当前书签列表，把每一项能解出的原始摄取物（ItemStack/FluidStack/其他）交给 consumer；解不出的静默跳过。 */
    private static void forEachRawIngredient(Consumer<Object> consumer) {
        Object runtime = runtimeRef;
        if (runtime == null) return;
        try {
            Object bookmarkOverlay = invoke(runtime, "getBookmarkOverlay");
            if (bookmarkOverlay == null) return;
            Object bookmarkList = readField(bookmarkOverlay, "bookmarkList");
            if (bookmarkList == null) return;
            Object rawList = readField(bookmarkList, "bookmarksList");
            if (!(rawList instanceof List<?> list)) return;
            for (Object entry : list) {
                Object raw = extractRawIngredient(entry);
                if (raw != null) consumer.accept(raw);
            }
        } catch (Throwable ignored) {
            // JEI 内部实现变动/反射失败：静默降级为空列表
        }
    }

    /** 单个书签条目（IBookmark）尝试解出真正的原始摄取物；配方书签等没有 getIngredient() 方法，异常直接跳过返回 null。 */
    private static Object extractRawIngredient(Object bookmarkEntry) {
        try {
            Object typedIngredient = invoke(bookmarkEntry, "getIngredient"); // IBookmark → ITypedIngredient
            if (typedIngredient == null) return null;
            return invoke(typedIngredient, "getIngredient"); // ITypedIngredient → 原始 T（ItemStack/FluidStack/...）
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Object invoke(Object target, String methodName) throws Exception {
        Method m = target.getClass().getMethod(methodName);
        return m.invoke(target);
    }

    private static Object readField(Object target, String fieldName) throws Exception {
        Field f = target.getClass().getDeclaredField(fieldName);
        f.setAccessible(true);
        return f.get(target);
    }
}
