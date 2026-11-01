package com.shanhai.common.locate;

import com.gregtechceu.gtceu.api.machine.MetaMachine;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * 山海重构 · <b>「我们自己的样板 part 都在哪」的弱注册表</b>（服务端侧）。
 *
 * <h2>1. 为什么必须有一张表，而不是每次去扫世界</h2>
 * 用户点单的硬约束：「服务端只扫<b>已加载的方块实体</b>里我们自己的样板 part，
 * <b>不扫整个维度、不做方块遍历</b>」。而"已加载"这件事<b>只有世界自己知道</b>
 * —— 于是正确做法是：<b>机器加载的那一刻自己报名，卸载那一刻自己注销</b>。
 * 表里的一律当过客，命中判定再当场复核（见 {@link #isLive(MetaMachine)}）。
 *
 * <h2>2. 挂在哪两个时机（不是新时机，是 GT 早就接好的那两个）</h2>
 * GTCEu 的 {@code MetaMachineBlockEntity} 字节码把两个生命周期<b>直连</b>到 {@code MetaMachine} 上
 * （{@code javap -p -c com.gregtechceu.gtceu.api.blockentity.MetaMachineBlockEntity}）：
 * <pre>
 *   public void m_6339_()  ( = BlockEntity#clearRemoved )  偏移 8: invokevirtual MetaMachine.onLoad:()V
 *   public void m_7651_()  ( = BlockEntity#setRemoved    )  偏移 8: invokevirtual MetaMachine.onUnload:()V
 * </pre>
 * 而区块卸载走的正是 {@code LevelChunk#clearAllBlockEntities()}：
 * <pre>
 *   this.blockEntities.values().forEach(BlockEntity::onChunkUnloaded);
 *   this.blockEntities.values().forEach(BlockEntity::setRemoved);     ← 所以区块卸载 = onUnload
 * </pre>
 * ⇒ 本表只在这两处维护：{@code onLoad()} 里 {@link #register}、{@code onUnload()} 里 {@link #unregister}。
 * <b>没有任何轮询、没有 tick、没有次数上限</b>。
 *
 * <h2>3. 为什么是 WeakHashMap，以及为什么还不够</h2>
 * 键是 {@code MetaMachine}。用弱键 ⇒ 就算某条路径漏了注销，机器被 GC 掉时条目也自己消失
 * （不会把整张表变成内存泄漏）。但"弱"只保证不泄漏，<b>不保证条目一定还活着</b>：
 * 区块卸载后 {@code setRemoved()} 让 BE 进入 remove 状态，若模型换了一台机器、或注销漏了，
 * 表里就会留一条"僵尸"。⇒ 读的时候一律过 {@link #isLive(MetaMachine)}：
 * <pre>
 *   ① 有 level 且是服务端（专服 + 客户端共用一个类，客户端那条必须剔掉）
 *   ② 持有者（IMachineBlockEntity）与它包着的 BlockEntity 都非 null、且 {@code isRemoved()==false}
 *   ③ 区块确实是加载的：{@code level.isLoaded(pos)}
 *   ④ 🔴 最硬的一条：{@code level.getBlockEntity(pos) == 那一台 BE}
 *      —— 位置上的机器换了，这条必假（比任何"注销记得调"都可靠）
 * </pre>
 *
 * <h2>4. 线程约束（照做）</h2>
 * 写（{@link #register}/{@link #unregister}）只发生在 {@code onLoad}/{@code onUnload} 里 = <b>服务端主线程</b>；
 * 读（{@link #snapshot}）只发生在 S2C/C2S 包处理器的 {@code ctx.enqueueWork(...)} 里 = <b>同样主线程</b>。
 * 两条都在主线程 ⇒ 逻辑上不需要锁；但为了"万一以后有人在别的线程调"不至于把 HashMap 搞坏，
 * 这里仍然用 {@code synchronized (PARTS)} 包住（一次请求一次，开销可忽略）。
 */
public final class ShanhaiLocateRegistry {

    /** 一台还活着的候选。 */
    public static final class Entry {

        private final MetaMachine machine;
        private final int kind;

        Entry(MetaMachine machine, int kind) {
            this.machine = machine;
            this.kind = kind;
        }

        public MetaMachine machine() {
            return this.machine;
        }

        /** {@link ShanhaiLocateCore#KIND_STELLAR} / {@link ShanhaiLocateCore#KIND_SUPER_WILDCARD}。 */
        public int kind() {
            return this.kind;
        }
    }

    /** 键弱引用（见类注释 §3）。值只是 int ⇒ 不会反过来强引用键。 */
    private static final Map<MetaMachine, Integer> PARTS = new WeakHashMap<>();

    /** 进程内注册过的次数 / 注销过的次数（只用于开机日志与排错）。 */
    private static int registeredTotal;
    private static int unregisteredTotal;

    private ShanhaiLocateRegistry() {}

    /**
     * 报名（只在 {@code onLoad()} 里调，见类注释 §2）。
     *
     * <p>客户端那份<b>不报名</b>：{@code onLoad} 两侧都会跑，而"样板在哪"是服务端才有的知识。
     */
    public static void register(MetaMachine part, int kind) {
        if (part == null || part.isRemote()) {
            return;
        }
        synchronized (PARTS) {
            // 🆕 2026-11-01：只在"真的新进表"时计数 —— 搜索时会把被动发现的 part 每轮登记一次，
            // 若无条件 ++，开机那行读数（registered_total）会变成"请求次数"而不是"登记过的机器数"。
            if (PARTS.put(part, Integer.valueOf(kind)) == null) {
                registeredTotal++;
            }
        }
    }

    /** 注销（只在 {@code onUnload()} 里调）。重复注销是无操作。 */
    public static void unregister(MetaMachine part) {
        if (part == null) {
            return;
        }
        synchronized (PARTS) {
            if (PARTS.remove(part) != null) {
                unregisteredTotal++;
            }
        }
    }

    /**
     * 取一份<b>已复核过的</b>活体快照（每台都过了 {@link #isLive(MetaMachine)}）。
     *
     * <p>顺手把复核不过的条目从表里剔掉（惰性清理：注册/注销之外的第三条清理路径，
     * 代价只落在"确实已经死了"的那些条目上）。
     */
    public static List<Entry> snapshot() {
        final List<Entry> out = new ArrayList<>();
        final List<MetaMachine> dead = new ArrayList<>();
        synchronized (PARTS) {
            if (PARTS.isEmpty()) {
                return out;
            }
            for (Map.Entry<MetaMachine, Integer> e : PARTS.entrySet()) {
                final MetaMachine machine = e.getKey();
                if (!isLive(machine)) {
                    dead.add(machine);
                    continue;
                }
                final int kind = e.getValue() == null ? ShanhaiLocateCore.KIND_STELLAR : e.getValue().intValue();
                out.add(new Entry(machine, kind));
            }
            for (MetaMachine machine : dead) {
                if (PARTS.remove(machine) != null) {
                    unregisteredTotal++;
                }
            }
        }
        return out;
    }

    /**
     * 这台机器现在还算不算"世界里那台"（四条判据见类注释 §3）。
     *
     * <p>🔴 <b>只读</b>：只问世界"这个位置有没有这台 BE"，不碰机器的任何状态
     * （尤其<b>不</b>调 {@code getAvailablePatterns()} —— 那是搜索那一步的事）。
     */
    public static boolean isLive(MetaMachine machine) {
        if (machine == null) {
            return false;
        }
        final net.minecraft.world.level.Level level = machine.getLevel();
        if (level == null || level.isClientSide) {
            return false;
        }
        final com.gregtechceu.gtceu.api.machine.IMachineBlockEntity holder = machine.getHolder();
        if (holder == null) {
            return false;
        }
        final net.minecraft.world.level.block.entity.BlockEntity be = holder.self();
        if (be == null || be.isRemoved()) {
            return false;
        }
        final net.minecraft.core.BlockPos pos = machine.getPos();
        if (pos == null || !level.isLoaded(pos)) {
            return false;
        }
        return level.getBlockEntity(pos) == be;
    }

    /** 表里现存的条目数（<b>未</b>复核，含僵尸；开机日志用）。 */
    public static int rawSize() {
        synchronized (PARTS) {
            return PARTS.size();
        }
    }

    /** 开机一行读数（机器可判）。 */
    public static String statsLine() {
        synchronized (PARTS) {
            return "locate_registry size=" + PARTS.size()
                    + " registered_total=" + registeredTotal
                    + " unregistered_total=" + unregisteredTotal
                    + "（键=弱引用；只在 MetaMachine#onLoad/onUnload 维护；读时按 isLive 复核）";
        }
    }

    /** 只读视图（排错用；**不**做复核）。 */
    public static List<MetaMachine> rawMachines() {
        synchronized (PARTS) {
            return Collections.unmodifiableList(new ArrayList<>(PARTS.keySet()));
        }
    }
}
