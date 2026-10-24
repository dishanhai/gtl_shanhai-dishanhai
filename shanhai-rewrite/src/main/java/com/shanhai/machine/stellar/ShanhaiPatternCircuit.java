package com.shanhai.machine.stellar;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.GenericStack;
import com.gregtechceu.gtceu.common.item.IntCircuitBehaviour;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * <b>样板内虚拟电路</b>的判定与取值（阶段九）。
 *
 * <h2>一、语义（用户口径，逐条对应实现）</h2>
 * <ol>
 *   <li>样板里填了电路 ⇒ <b>用它</b>（覆盖共享电路槽里的那个）；</li>
 *   <li><b>不消耗</b>任何电路物品 —— 本类只「读一个数」，不碰任何库存/网络（无 extract / consume 调用）；</li>
 *   <li>样板没填 ⇒ <b>回落共享电路</b>（交给父类那套缓存/共享逻辑，见 {@link #resolve(int, int)}）。</li>
 * </ol>
 * <b>不是</b>把电路从配方需求里摘掉：需求还在，只是由样板来满足。
 *
 * <h2>二、取证结论（两个 API 的出处，阶段九实读）</h2>
 * <pre>
 *   appeng.api.crafting.IPatternDetails$IInput.getPossibleInputs() : GenericStack[]     // javap -p
 *   com.gregtechceu.gtceu.common.item.IntCircuitBehaviour                              // javap -p
 *       static boolean isIntegratedCircuit(ItemStack)
 *       static int     getCircuitConfiguration(ItemStack)
 *       static ItemStack stack(int)
 * </pre>
 * 上游那半段（「样板有就优先、否则回落共享」）本来就在
 * {@code MEBufferPatternHelper.getCircuitForRecipe(ItemStack)} 里；缺口是「没人把样板里的电路号
 * 填进 {@code SlotCacheManager.circuitStack}」。本轮的补法：<b>不动那个私有缓存</b>，而是在
 * {@code getCircuitForRecipe(int)} 里先问本类（从样板输入读），读不到才 {@code super}。
 *
 * <h2>三、为什么把「取值」和「决策」拆成两个方法</h2>
 * {@link #circuitInPattern(IPatternDetails)} 要 AE/GT 对象（只能在机器里跑）；
 * {@link #resolve(int, int)} 纯整数 ⇒ 可以被无头探针实跑真值表（含负对照）。
 */
public final class ShanhaiPatternCircuit {

    /** 「没读到电路」的哨兵值（GT 的电路号合法值从 0 开始，所以必须用负数当"没有"）。 */
    public static final int NONE = -1;

    private ShanhaiPatternCircuit() {}

    /**
     * 从一块样板的输入里读<b>虚拟电路号</b>。
     *
     * <p>扫描顺序 = AE 给的输入顺序；<b>只认</b> {@link IntCircuitBehaviour#isIntegratedCircuit(ItemStack)}
     * 为真的那些（⇒ 普通物品/流体输入一律忽略，不会把别的输入误当电路）。
     *
     * @return 电路号（≥0）；没填则 {@link #NONE}
     */
    public static int circuitInPattern(@Nullable IPatternDetails pattern) {
        if (pattern == null) {
            return NONE;
        }
        final IPatternDetails.IInput[] inputs = pattern.getInputs();
        if (inputs == null) {
            return NONE;
        }
        for (IPatternDetails.IInput input : inputs) {
            if (input == null) {
                continue;
            }
            final GenericStack[] possible = input.getPossibleInputs();
            if (possible == null) {
                continue;
            }
            for (GenericStack candidate : possible) {
                if (candidate == null || !(candidate.what() instanceof AEItemKey itemKey)) {
                    continue;
                }
                final ItemStack stack = itemKey.getReadOnlyStack();
                if (IntCircuitBehaviour.isIntegratedCircuit(stack)) {
                    return IntCircuitBehaviour.getCircuitConfiguration(stack);
                }
            }
        }
        return NONE;
    }

    /** 电路号 → 电路物品栈（给 {@code getCircuitForRecipe} 用）。 */
    public static @NotNull ItemStack stackOf(int circuit) {
        return IntCircuitBehaviour.stack(circuit);
    }

    /** 一个已存在物品栈的电路号；不是集成电路 ⇒ {@link #NONE}。 */
    public static int circuitOfStack(@Nullable ItemStack stack) {
        if (stack == null || stack.isEmpty() || !IntCircuitBehaviour.isIntegratedCircuit(stack)) {
            return NONE;
        }
        return IntCircuitBehaviour.getCircuitConfiguration(stack);
    }

    /**
     * <b>决策（纯函数）</b>：这一格最终该用哪个电路号。
     *
     * <ul>
     *   <li>样板自带（{@code fromPattern >= 0}）⇒ <b>用它</b>（覆盖共享电路）；</li>
     *   <li>样板没填 ⇒ 用父类的结果（{@code fromSuper}，可能是共享电路，也可能没有 = {@link #NONE}）。</li>
     * </ul>
     * ⚠️ 注意 {@code 0} 是<b>合法电路号</b>（GT 的电路从 0 开始），所以判据必须是 {@code >= 0}
     * 而不是 {@code > 0} —— 写错就会把「样板里那块 0 号电路」当成"没填"。
     */
    public static int resolve(int fromPattern, int fromSuper) {
        return fromPattern >= 0 ? fromPattern : fromSuper;
    }
}
