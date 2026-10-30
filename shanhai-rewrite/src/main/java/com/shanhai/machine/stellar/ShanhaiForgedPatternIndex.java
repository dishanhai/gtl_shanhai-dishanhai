package com.shanhai.machine.stellar;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.IdentityHashMap;

/**
 * <b>【神锻改写实例 → 槽号】的身份反查表 ＋ 「什么时候才改写」的唯一判定核
 * —— 零 Minecraft / 零 GT 依赖 ⇒ 可离线 java 单跑探针。</b>
 *
 * <h2>一、为什么必须有这张表</h2>
 * 上游 {@code MEPatternBufferPartMachine#patternSlotMap} 记的是<b>原样板实例</b> →
 * 槽号（{@code super.getSlotIndexForPattern(pattern)}）。神锻改写会<b>造出一个新实例</b>
 * （{@code ShanhaiForgePatternMode.rewrite} → 上游 {@code rewriteForgeOfTheAntichristPattern}），
 * AE 之后拿着的就是这个新实例 ⇒ 上游那张表<b>查不到</b> ⇒ {@code pushPattern} 找不到槽
 * ⇒ 料推不进来（这正是"改写后必须自建反查表"的原因，与通配符展开段
 * {@code expandedToSlot} 是同一套做法，只是键的来源不同）。
 *
 * <h2>二、为什么用 {@code IdentityHashMap}</h2>
 * 按<b>引用</b>比，不按 {@code equals}/{@code hashCode}：
 * AE 的样板实例没有稳定的值语义，按值比会把"内容相同但不同槽"的两条样板混成一条。
 * 这与 {@code expandedToSlot}（{@code IdentityHashMap}）口径一致。
 *
 * <h2>三、失效（谁都不许留旧表）</h2>
 * 表里存的是"上一轮改写出来的实例"。样板内容一变、倍率一变、神锻开关一变、拆除/读档，
 * 这些实例就<b>不再属于任何槽</b> ⇒ 调用方必须 {@link #clear()}：
 * 生产侧在 {@code shanhaiGatedPatterns()}（每轮重建）与 {@code shanhaiInvalidateGateCache()}
 * （任何置脏）两处都清 —— 宁可能多清一次，也不许让 AE 的旧实例解析回一个槽号。
 */
public final class ShanhaiForgedPatternIndex<T> {

    /** 重写实例 → 原槽号（<b>只认引用</b>）。 */
    private final IdentityHashMap<T, Integer> slotOfForged = new IdentityHashMap<>();

    /** 原槽号 → 重写实例（诊断/自证用；同一个槽只保留最近一次改写的那个）。 */
    private final java.util.HashMap<Integer, T> forgedOfSlot = new java.util.HashMap<>();

    /** 忘掉全部（样板变 / 倍率变 / 开关变 / 拆除 / 读档）。 */
    public void clear() {
        if (!this.slotOfForged.isEmpty()) {
            this.slotOfForged.clear();
        }
        if (!this.forgedOfSlot.isEmpty()) {
            this.forgedOfSlot.clear();
        }
    }

    /** 登记一条：{@code forged} 这个实例属于 {@code slot}。{@code forged == null} ⇒ 忽略。 */
    public void put(@Nullable T forged, int slot) {
        if (forged == null) {
            return;
        }
        this.slotOfForged.put(forged, Integer.valueOf(slot));
        this.forgedOfSlot.put(Integer.valueOf(slot), forged);
    }

    /** 这个实例是不是本轮改写出来的？是 ⇒ 它的原槽号。 */
    public @Nullable Integer slotOf(@Nullable T instance) {
        return instance == null ? null : this.slotOfForged.get(instance);
    }

    /** 某个槽这一轮改写出来的实例（没有 ⇒ {@code null}）。 */
    public @Nullable T forgedAt(int slot) {
        return this.forgedOfSlot.get(Integer.valueOf(slot));
    }

    public int size() {
        return this.slotOfForged.size();
    }

    public boolean isEmpty() {
        return this.slotOfForged.isEmpty();
    }

    // ---------------------------------------------------------------- 判定核

    /**
     * <b>这一轮到底改不改写</b>（纯逻辑；四条与门的顺序就是生产语义）。
     *
     * @param flagOn     面板上那个「神锻样板模式」开关（{@code forgePatternMode}）
     * @param available  上游实现是否解析成功（{@code ShanhaiForgePatternMode.isAvailable()}）
     * @param multiplier 生效倍率（{@code getEffectiveForgePatternMultiplier()}；1 = 不放大）
     * @param hasLevel   当前有没有 level（{@code rewrite} 内部要求非 null，否则原样返回）
     */
    public static boolean shouldRewrite(boolean flagOn, boolean available, int multiplier, boolean hasLevel) {
        return flagOn && available && hasLevel && multiplier > 1;
    }

    /**
     * <b>真正的改写跳</b>（纯逻辑外壳 ＋ 调用方给的改写函数）。
     *
     * <p>关掉/不可用/没有 level/倍率 ≤ 1 ⇒ <b>原样返回同一个引用</b>（逐位不变，这是硬约束）；
     * 否则调 {@code rewriter}，它返回 {@code null} 时同样退回原引用（绝不因为改写失败吞掉样板）。
     *
     * @param rewriter {@code (原样板, 倍率) -> 改写后的样板}
     */
    public static <T> @Nullable T apply(@Nullable T raw, boolean flagOn, boolean available, int multiplier,
                                        boolean hasLevel, @NotNull java.util.function.BiFunction<T, Integer, T> rewriter) {
        if (raw == null) {
            return null;
        }
        if (!shouldRewrite(flagOn, available, multiplier, hasLevel)) {
            return raw;
        }
        final T forged = rewriter.apply(raw, Integer.valueOf(multiplier));
        return forged == null ? raw : forged;
    }

    /** 一行读数（日志用）。 */
    public @NotNull String describe() {
        return "forgedIndex={size=" + size() + "}";
    }
}
