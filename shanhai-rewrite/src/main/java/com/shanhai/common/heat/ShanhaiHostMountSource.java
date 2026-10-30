package com.shanhai.common.heat;

import com.shanhai.common.heat.ShanhaiHeatGate.SlotContent;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * <b>「我能给挂在我身上的模块提供额外挂载槽」</b> —— 2026-10-25 用户点单的「复写」口子。
 *
 * <h2>用户原话（逐字）</h2>
 * <blockquote>「<b>开</b>，而且我说的是<b>复写</b>，归墟视界枢核的配方虽然没有，
 * <b>但是他模块的配方需要啊</b>」</blockquote>
 * ⇒ 要的不是"归墟自己能不能跑熔炼配方"，而是：<b>归墟身上那 3 个额外挂载槽，要能给【装在他身上的模块】
 * 提供额外条件</b>（cleanroom / gravity / dimension / research <b>＋ 热力 ebf_temp/SCTier</b>）。
 *
 * <h2>为什么做成接口（而不是在模块侧 {@code instanceof GuixuVisionCoreMachine}）</h2>
 * <ul>
 *   <li><b>依赖方向</b>：模块包不该认识主机包的具体类。接口放在 {@code common/heat} ⇒
 *       模块侧只依赖"能力"，主机侧（归墟）自己声明实现它。</li>
 *   <li><b>「只有归墟做这件事」由结构保证</b>：老主机 {@code PrimordialOmegaEngineMachine}
 *       <b>不实现</b>这个接口 ⇒ 模块拿到的是 {@code null} ⇒ 一个数都不变（不需要任何开关）。</li>
 *   <li>将来若某台新主机也想供源，实现这个接口即可，<b>模块侧零改动</b>。</li>
 * </ul>
 *
 * <h2>🔴 语义（模块侧怎么用它）</h2>
 * 模块的可用源 = <b>自己的 3 格 ∪ 主机的 3 格</b>，判定仍是 {@link ShanhaiHeatGate#evaluate}
 * 那<b>唯一一份</b>判据（把两个来源拼成一个槽列表传进去 ⇒ 一条需求只要"任一个来源的某一格"满足就算满足；
 * 模块自己就能满足时<b>不会</b>被主机的低级物项压低 —— 见 {@link ShanhaiHeatGate#slotSatisfies} 的逐格判据）。
 * 本接口<b>只提供数据</b>，不参与任何判断。
 */
public interface ShanhaiHostMountSource {

    /**
     * 主机那 3 格现读内容（顺序 = 槽序号，长度 = {@link ShanhaiHeatGate#SLOT_COUNT}）。
     *
     * <p>实现方必须<b>不抛异常</b>（这是每次找配方都会走的路）：拿不到就返回空列表。
     */
    @NotNull
    List<SlotContent> hostExtraMountContents();

    /** 供源主机的显示名（只给日志用，例：{@code 归墟视界枢核}）。 */
    @NotNull
    String hostExtraMountSourceName();

    /**
     * 这台主机此刻是否愿意/能够当热力源（{@code ebf_temp} / {@code SCTier} 那两条门槛）。
     *
     * <p>为什么单独一条：那两条门槛原本还受"这台机器在不在热力白名单"限制
     * （{@link ShanhaiHeatGate#hasHeatSlot}）；主机既然真能放线圈，就该把这条一起放开。
     */
    default boolean hostProvidesHeatSource() {
        return true;
    }

    /** `null` 安全的工具：把它当成"多出来的那几个槽"，拿不到就当没有（返回 {@code null}）。 */
    @Nullable
    static List<SlotContent> contentsOf(@Nullable Object maybeSource) {
        if (!(maybeSource instanceof ShanhaiHostMountSource source)) {
            return null;
        }
        try {
            final List<SlotContent> slots = source.hostExtraMountContents();
            return slots == null || slots.isEmpty() ? null : slots;
        } catch (Throwable ignored) {
            return null;                        // 供源坏了 ⇒ 退回"只有模块自己的 3 格"（原行为）
        }
    }
}
