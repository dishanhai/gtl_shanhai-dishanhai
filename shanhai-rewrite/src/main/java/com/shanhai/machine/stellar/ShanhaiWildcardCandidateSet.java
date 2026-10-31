package com.shanhai.machine.stellar;

/**
 * <b>「需要被遍历的槽号」候选集的唯一构造点</b>（纯逻辑、零 Minecraft 依赖、零注解依赖）。
 *
 * <h2>一、为什么把它从机器类里搬出来</h2>
 * 候选集原来写在 {@code StellarSampleAssemblerPartMachine#shanhaiCandidateSlots()} 里，
 * 而那个类一行都离不开 Minecraft ⇒ 候选集<b>没法离线复跑</b>，
 * 于是「候选集里到底有没有某个槽」这件事只能靠实机日志猜。
 * 搬到这里之后，机器侧与探针跑的是<b>同一段字节码</b>
 * （探针见 {@code temp\candfix\probe\CandSetProbe.java}，{@code javac --release 17} 直接编译本文件）。
 *
 * <h2>二、🔴 判据只有一条：{@link ShanhaiWildcardSlotAllocator#isRegistered(int)}</h2>
 * 这个类<b>不许</b>出现第二条判据。硬规则：
 * <ul>
 *   <li>{@code [0, motherEnd)} —— <b>无条件全收</b>（普通样板 {@code 0..1079} + 通配符母槽 {@code 1080..1129}）。
 *       这一段是「固定前缀」：槽里有没有样板由父类容器 / 母槽容器回答，
 *       <b>与分配器无关</b> ⇒ 绝不能拿分配器的任何方法去过滤它
 *       （历史事故：拿"已分配"去过滤固定段 ⇒ 整段普通样板从候选集消失）。</li>
 *   <li>{@code [motherEnd, motherEnd + slotCount())} —— 逐 {@code rel} 问
 *       {@link ShanhaiWildcardSlotAllocator#isRegistered(int)}，
 *       与 {@code hasPatternInSlot} <b>同一个方法</b>（不许用 {@code isHandedOut} 之类的近义判据：
 *       "发过号"不等于"仍登记着一条展开"，两者一旦分叉就是"候选集说没有、hasPattern 说有"）。</li>
 * </ul>
 *
 * <h2>三、参数化（{@code motherEnd} / {@code expandOffset}）是为了负对照</h2>
 * 生产调用传的是 {@link ShanhaiWildcardLayout#MOTHER_END} 与
 * {@link ShanhaiWildcardLayout#EXPAND_OFFSET}（都是 1130），
 * 探针则把 {@code motherEnd} 传 <b>0</b> 做负对照 —— 那样固定段整段消失，
 * 探针<b>必须报红</b>。常量写死在方法体里就做不出这个对照。
 */
public final class ShanhaiWildcardCandidateSet {

    private ShanhaiWildcardCandidateSet() {}

    /**
     * 构造候选集（升序：先固定段 {@code 0..motherEnd-1}，再按 {@code rel} 升序的展开段）。
     *
     * @param motherEnd    固定前缀的右开边界（生产 = {@link ShanhaiWildcardLayout#MOTHER_END} = 1130）
     * @param expandOffset 展开段起点（生产 = {@link ShanhaiWildcardLayout#EXPAND_OFFSET} = 1130）
     * @param alloc        槽号分配器（{@code null} ⇒ 只有固定段，且不抛 —— 读档早期分配器可能还没建）
     * @return 新的槽号数组（不会返回原数组，调用方可以随意持有）
     */
    public static int[] build(int motherEnd, int expandOffset, ShanhaiWildcardSlotAllocator alloc) {
        if (motherEnd < 0) {
            throw new IllegalArgumentException("motherEnd 必须 >= 0，实际 " + motherEnd);
        }
        if (expandOffset < 0) {
            throw new IllegalArgumentException("expandOffset 必须 >= 0，实际 " + expandOffset);
        }
        final int live = alloc == null ? 0 : alloc.slotCount();
        final int[] out = new int[motherEnd + live];
        int n = 0;
        for (int slot = 0; slot < motherEnd; slot++) {
            out[n++] = slot;
        }
        for (int rel = 0; rel < live; rel++) {
            // 🔴 唯一判据：与 hasPatternInSlot 同一个方法（见类注释 §二）
            if (!alloc.isRegistered(rel)) {
                continue;
            }
            final long slot = (long) expandOffset + (long) rel;
            if (slot > Integer.MAX_VALUE) {
                continue;   // 防御：溢出的槽号绝不进候选集
            }
            out[n++] = (int) slot;
        }
        return n == out.length ? out : java.util.Arrays.copyOf(out, n);
    }

    /** 候选集里有没有 {@code slot}（{@code null} ⇒ false；纯线性扫，调用方都是低频路径）。 */
    public static boolean contains(int[] slots, int slot) {
        if (slots == null) {
            return false;
        }
        for (int candidate : slots) {
            if (candidate == slot) {
                return true;
            }
        }
        return false;
    }
}
