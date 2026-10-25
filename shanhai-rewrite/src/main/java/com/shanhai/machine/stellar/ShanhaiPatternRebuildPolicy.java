package com.shanhai.machine.stellar;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * <b>「展开表什么时候重建」的唯一判定点（C4：回到原版时机）。</b>
 *
 * <p>纯逻辑、零 Minecraft 依赖 ⇒ 可离线 {@code java} 单跑探针
 * （{@code temp\poolsplit\probe\RebuildPolicyProbe.java}：正向 5 读 + 2 变 ⇒ 重建 2 次；
 * 负对照"每次读都重建" ⇒ 重建 5 次）。
 *
 * <h2>一、改前错在哪</h2>
 * 改前 {@code getAvailablePatterns()}（AE 会频繁问的那一跳）里带
 * {@code if (expandCacheDirty) rebuild} ⇒ <b>读路径重建</b>。那等于：
 * AE 问一次就重建一次，而重建要遍历 GT 材料表、还会换掉整批 {@code IPatternDetails} 实例
 * ⇒ 「AE 手里是旧实例 / 表是新的」这类错配（{@code patternNotOurs}）就是这么来的。
 *
 * <h2>二、改后的时机（用户口径：原版也是"变化时建一次"）</h2>
 * <pre>
 *   建一次的三个触发点：
 *     ① 样板格内容变化   → onPatternChange(slot) / 通配符母槽内容变
 *     ② 读档             → onLoad()
 *     ③ 母槽类型变化     → 通配符母槽的配方类型键被写
 *   另外：置脏之后由 update() 每 tick 消费一次（脏才建，不脏一个字节都不动）
 *   建完 ⇒ 主动 requestAePatternSync（让 AE 重读整份清单）
 *   读路径（getAvailablePatterns / getSlotIndexForPattern / 候选槽枚举）⇒ 【永不重建】
 * </pre>
 * <b>判据</b>：连续两次 {@code getAvailablePatterns()} 之间不重建（重建时只打一行
 * {@code PATREBUILD no=N ...}，所以"行数 = 重建次数"）。
 *
 * <h2>三、负对照开关</h2>
 * {@link #EAGER_REBUILD_EVERY_READ} 改 {@code true} ⇒ {@link #shouldBuildOnRead()} 恒 true
 * （= 退回"每次调用都重建"）。只用于证明探针/判据有效，<b>生产必须是 false</b>。
 */
public final class ShanhaiPatternRebuildPolicy {

    /**
     * <b>负对照开关</b>：{@code true} = 读路径每次调用都重建（改前的错法）。
     *
     * <p>⛔ 生产必须是 {@code false}。改成 {@code true} 只用于证明判据能翻红。
     */
    public static final boolean EAGER_REBUILD_EVERY_READ = false;

    private boolean dirty = true;

    private int rebuilds;

    private int aeSyncs;

    @Nullable
    private String lastReason;

    /** 置脏（样板变 / 读档 / 类型变 / 闸变 / 神锻变 ⇒ 全是事件）。 */
    public void markDirty() {
        this.dirty = true;
    }

    public boolean isDirty() {
        return this.dirty;
    }

    /**
     * 【读路径】要不要重建 —— <b>生产恒 false</b>（负对照时为 true）。
     *
     * <p>这是本类存在的意义：把"读不许建"写成<b>一个可断言的方法</b>，而不是靠注释约束。
     */
    public boolean shouldBuildOnRead() {
        return EAGER_REBUILD_EVERY_READ;
    }

    /** 【事件/tick 路径】脏了就建一次。 */
    public boolean shouldBuildNow() {
        return this.dirty;
    }

    /**
     * 真的建了一次：清脏 + 计数 + 记原因。
     *
     * @return 这是第几次重建（从 1 开始，直接进 {@code PATREBUILD no=} 那一行）
     */
    public int onBuilt(@NotNull String reason) {
        this.dirty = false;
        this.lastReason = reason;
        return ++this.rebuilds;
    }

    /** 建完叫醒 AE 的那一次记账。 */
    public void onAeSyncRequested() {
        this.aeSyncs++;
    }

    public int rebuilds() {
        return this.rebuilds;
    }

    public int aeSyncs() {
        return this.aeSyncs;
    }

    public @Nullable String lastReason() {
        return this.lastReason;
    }

    public @NotNull String describe() {
        return "rebuilds=" + this.rebuilds + " aeSyncs=" + this.aeSyncs
                + " dirty=" + this.dirty + " lastReason=" + this.lastReason;
    }
}
