package com.shanhai.api.machine;

import com.gregtechceu.gtceu.api.machine.trait.IRecipeHandlerTrait;

import java.util.List;

/**
 * 「<b>万能仓室</b>」标记接口 —— 让一台 <b>ME 仓室</b>在不丢掉 ME 身份的前提下，
 * 额外把它<b>自己声明的少数 handler</b> 注册进 gtlcore 的「普通供料名单」。
 *
 * <h2>一、为什么需要这个标记（问题原文）</h2>
 * gtlcore 的 {@code WorkableMultiblockMachineMixin#rebuildRecipeHandleParts} 里有一处分流：
 * <pre>
 *   javap -p -c 偏移 45：instanceof org/gtlcore/gtlcore/api/machine/trait/MEPart/IMEFilterIOPartMachine
 *                 偏移 48：ifeq 69        ← 是 ME 仓室就跳过普通分支
 *                 偏移 63：invokevirtual handleMETraitPart:(Ljava/util/List;LRecipeLogic;LIMEFilterIOPartMachine;)V
 *                 偏移 92：invokevirtual handleNormalPart:(LIMultiPart;ZLjava/util/List;)V   ← 只有非 ME 仓室才走这里
 * </pre>
 * ⇒ <b>凡是 {@code IMEFilterIOPartMachine} 的仓室都被整体分给 ME 分支</b>，
 * 而 {@code handleNormalPart}（那里才 {@code part.getRecipeHandlers()}）<b>根本不会为它执行</b>
 * ⇒ ME 仓室身上那些「本来就是普通 {@code IRecipeHandlerTrait}」的 handler（例如库存输入用的
 * {@code ExportOnlyAEStockingItemList} / {@code ExportOnlyAEStockingFluidList}）
 * <b>永远进不了 {@code normalCapabilities}</b>，于是只能走「样板认领」那条路
 * （三闸 {@code hasPatternInSlot → isItemActive → hasConfig()}），没有样板的配方用不到它们。
 *
 * <h2>二、本接口做了什么</h2>
 * 只做一件事：<b>声明</b>。「我这台仓室愿意额外交出这几份 handler，让它们按<b>普通仓室</b>的规则
 * 参与普通配方匹配。」
 * 真正把它接进 gtlcore 的是 {@code com.shanhai.mixin.ShanhaiUniversalSupplyMixin}：
 * 它在 {@code upDate()} 取 {@code getParts()} 的那一处把「带本标记的 ME 仓室」额外插入一份
 * <b>只含 {@link #shanhaiNormalSupplyHandlers()} 的视图</b>，
 * 于是 gtlcore <b>自己那份</b> {@code handleNormalPart} 会照常处理这个视图
 * （不另写一套分流 / 不复制 gtlcore 的 {@code IO} 归类逻辑）。
 *
 * <h2>三、🔴 安全边界（实现方必须遵守，三条都是硬约束）</h2>
 * <ol>
 *   <li><b>只放行带本标记的仓室</b>：未标记的 ME 仓室<b>一个字节都不变</b>
 *       （mixin 里没有标记时直接返回<b>同一个 List 实例</b>，见
 *        {@link ShanhaiNormalSupplyBridge#expandForNormalSupply}）。</li>
 *   <li><b>只放行它明确声明的那几份 handler</b>：mixin 造出来的视图，
 *       {@code getRecipeHandlers()} <b>只返回</b>本方法的返回值，
 *       <b>不会</b>去读 {@code part.getRecipeHandlers()}。
 *       ⇒ 样板槽 / 样板库存 / 催化剂 / 电路配置<b>永远进不了普通供料名单</b>
 *       （它们本来就不在返回值里）。</li>
 *   <li><b>不改变 ME 身份</b>：真实的那台 part 仍按原路进 {@code handleMETraitPart}
 *       （mixin 只是<b>追加</b>一个视图，不替换原元素）⇒
 *       {@code mePatternRecipeHandleParts} / 缓冲监听 / 样板认领全部照旧。</li>
 * </ol>
 *
 * <h2>四、实现方注意</h2>
 * <ul>
 *   <li>返回值里的 handler 必须<b>本来就是</b> {@code IRecipeHandlerTrait}、
 *       且 {@code getHandlerIO() == IO.IN}（gtlcore 的 {@code handleNormalPart} 只收
 *       {@code IN} 与 {@code OUT}；{@code BOTH}/{@code NONE} 会被它直接丢掉）。</li>
 *   <li>{@code isProxy() == true} 的 handler 会被 gtlcore 丢弃（它自己的规则，不是我们的）。</li>
 *   <li>返回 {@code null} 或空列表 ⇒ 等同没标记（mixin 会跳过，不插入视图）。</li>
 * </ul>
 */
public interface IShanhaiNormalSupplyPart {

    /**
     * 本仓室愿意额外交给「普通供料名单」的 handler（<b>只交这些，多一份都不交</b>）。
     *
     * <p>约定：返回的每一份 handler 都会以「普通仓室」的身份被 gtlcore 收进
     * {@code normalCapabilities}（或整机共享输入池 {@code sharedInputRecipeHandlePart}），
     * 与那台仓室原来的 ME 身份<b>并存</b>。
     *
     * @return 只含「应该参与普通配方匹配」的 handler；不要放样板库存、催化剂、电路配置
     */
    List<IRecipeHandlerTrait> shanhaiNormalSupplyHandlers();
}
