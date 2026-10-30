package com.shanhai.machine.stellar;

import it.unimi.dsi.fastutil.ints.Int2ReferenceMap;
import it.unimi.dsi.fastutil.ints.Int2ReferenceOpenHashMap;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.function.Function;
import java.util.function.IntPredicate;

/**
 * <b>【按槽号记账的配方缓存】—— 与父类 {@code protected final boolean[] cacheRecipe}（定长 1080）等价语义，
 * 但<b>不按下标</b>。</b>
 *
 * <h2>一、为什么必须有这一层（而不是直接复用父类那个数组）</h2>
 * 父类 {@code MEPatternBufferPartMachine} 的 {@code cacheRecipe} 在<b>构造器里</b>按
 * {@code newarray (maxPatternCount = 1080)} 定长（{@code javap -c} 偏移 52–63），而本机槽号空间是
 * <b>动态</b>的（{@code 1130 + 分配器水位}，用户口径「原版无上限」）⇒ 拿 ≥1080 的槽号去下标<b>必然 AIOOBE</b>
 * （现场实证：{@code Index 1080 out of bounds for length 1080}，见
 * {@code StellarSampleAssemblerPartMachine#ShanhaiMEPatternTrait#hasCacheInSlot} 的类注释）。
 *
 * <p>所以这里用 <b>{@code Int2ReferenceMap} 按槽号存</b>：
 * <b>结构上没有"下标"这个量</b> ⇒ {@code ArrayIndexOutOfBoundsException} 在构造上不可能发生
 * （不是"加了个边界判断挡住的"，而是"根本没有能越界的那条指令"）。"容量"由调用方给的
 * {@link IntPredicate 槽号空间判据}决定（生产 = {@code [1080, getInternalSlotCount())} 现算）。
 *
 * <h2>二、语义与父类逐条同构（这是"普通槽与展开槽不再分叉"的前提）</h2>
 * <ul>
 *   <li>{@link #has(int)} ⇔ 父类 {@code cacheRecipe[slot]}（"这一格的配方缓存<b>已就绪</b>"）；</li>
 *   <li>{@link #register(int, Object)} ⇔ 父类 {@code setSlotCacheRecipe} 里那两句
 *       {@code recipeMultipleCacheMap.computeIfAbsent(index).add(recipe)} +
 *       {@code cacheRecipe[index] = set.size() >= cacheRecipeCount[index]}；</li>
 *   <li><b>阈值 = 1</b>（父类 {@code cacheRecipeCount} 构造器的默认值 {@code Arrays.fill(.., (byte) 1)}）：
 *       登记第一条即"就绪"，此后 {@link #register} 不再覆盖（上游正因 {@code has=true} 而不再调它）。</li>
 * </ul>
 *
 * <h2>三、可离线验证</h2> 本类<b>零 Minecraft / 零 GT 依赖</b>（{@code T} 是任意类型），
 * 所以「登记过 = 就绪，没登记 = 不就绪，忘掉 = 回到不就绪」这条语义可以由
 * {@code temp\wildfix\probe} 的纯 java 探针实跑（生产侧传的就是 {@code GTRecipe}）。
 */
public final class ShanhaiWildcardRecipeCache<T> {

    private final Int2ReferenceMap<T> table = new Int2ReferenceOpenHashMap<>();

    /** 槽号在不在<b>当前</b>槽号空间里（生产侧 = {@code [PATTERN_SLOTS, getInternalSlotCount())}，现算）。 */
    private final IntPredicate inSpace;

    public ShanhaiWildcardRecipeCache(@NotNull IntPredicate inSpace) {
        this.inSpace = inSpace;
    }

    /** 这一格的配方缓存是否已就绪（= 登记过）。<b>不在槽号空间里的一律 false</b>（水位收缩后的陈旧槽号）。 */
    public boolean has(int slot) {
        return this.inSpace.test(slot) && this.table.containsKey(slot);
    }

    /**
     * 登记一条。
     *
     * @return {@code true} = 这次真的写进去了（新登记）；{@code false} = 被忽略
     *         （{@code null} / 不在槽号空间 / 这一格已登记过 = 阈值 1）
     */
    public boolean register(int slot, @Nullable T value) {
        if (value == null || !this.inSpace.test(slot) || this.table.containsKey(slot)) {
            return false;
        }
        this.table.put(slot, value);
        return true;
    }

    /** 忘掉一格（样板变 / 展开槽被回收）。 */
    public boolean forget(int slot) {
        return this.table.remove(slot) != null;
    }

    /** 整表清空，返回清掉了几格。 */
    public int clear() {
        final int n = this.table.size();
        if (n > 0) {
            this.table.clear();
        }
        return n;
    }

    /** 直接取（不在空间里 / 没登记 ⇒ {@code null}）。 */
    public @Nullable T get(int slot) {
        return this.inSpace.test(slot) ? this.table.get(slot) : null;
    }

    public int size() {
        return this.table.size();
    }

    public boolean isEmpty() {
        return this.table.isEmpty();
    }

    /** 遍历（{@link java.util.function.BiConsumer} 语义：slot, value）。 */
    public void forEach(@NotNull java.util.function.ObjIntConsumer<T> consumer) {
        for (Int2ReferenceMap.Entry<T> e : this.table.int2ReferenceEntrySet()) {
            consumer.accept(e.getValue(), e.getIntKey());
        }
    }

    /** 一行读数（日志/自证用）。 */
    public @NotNull String describe(@NotNull Function<T, String> idOf) {
        if (this.table.isEmpty()) {
            return "{}";
        }
        final StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Int2ReferenceMap.Entry<T> e : this.table.int2ReferenceEntrySet()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append(e.getIntKey()).append('=').append(idOf.apply(e.getValue()));
        }
        return sb.append('}').toString();
    }
}
