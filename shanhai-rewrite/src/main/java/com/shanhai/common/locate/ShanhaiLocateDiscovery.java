package com.shanhai.common.locate;

import appeng.api.networking.IGrid;
import appeng.api.networking.IGridNode;
import appeng.api.networking.IManagedGridNode;
import appeng.api.networking.crafting.ICraftingProvider;

import com.gregtechceu.gtceu.api.machine.MetaMachine;
import com.gregtechceu.gtceu.api.machine.feature.multiblock.IMultiController;
import com.gregtechceu.gtceu.api.machine.feature.multiblock.IMultiPart;
import com.gregtechceu.gtceu.api.machine.multiblock.MultiblockControllerMachine;
import com.gregtechceu.gtceu.integration.ae2.machine.feature.IGridConnectedMachine;

import java.util.ArrayList;
import java.util.List;

/**
 * 山海重构 · 定位的<b>只读发现层</b>：从一台"我们已经认识的"样板 part 出发，
 * 找出<b>不是我们注册的</b>那些样板总成（例如 gtladditions 的 {@code MESuperPatternBufferPartMachine}）。
 *
 * <h2>1. 为什么需要它（现场证据）</h2>
 * 2026-11-01 用户点单：「<b>shift+右键还是没反应</b>」，服务端日志给出的原因是
 * <pre>
 *   latest.log:11351 [SHANHAI-LOCATE] none player=dgyjh02804 item=ae2:i@shanhai:light_voyage
 * </pre>
 * 即"注册表里那几台"都没有这个物品的样板。而用户的样板实际装在哪，同一份日志写得明明白白：
 * <pre>
 *   latest.log:7097 [SHANHAI-RLPROBE] setup_gate machine=useless_mod:uselessdim2@-88,130,83
 *        layout=[AdaptiveNetEnergyHatchPartMachinex2,MESuperPatternBufferPartMachinex1]
 *   latest.log:27651 [SHANHAI-STELLAR] STOCKWHO pos=useless_mod:uselessdim2@-88,131,82 …
 *        providers=[StellarSampleAssemblerPartMachine=3, MESuperPatternBufferPartMachine=0]
 * </pre>
 * ⇒ {@code MESuperPatternBufferPartMachine}（{@code com.gtladd.gtladditions.common.machine.multiblock.part}）
 * 里确实有样板，而它<b>不在</b>{@link ShanhaiLocateRegistry}（那张表只有我们自己的两类 part 在
 * {@code onLoad} 里报名，见 {@code StellarSampleAssemblerPartMachine:4457} /
 * {@code SuperWildcardPatternBufferPartMachine:487}）。
 *
 * <h2>2. 两条只读路径（都只在"搜索那一下"跑，<b>没有 tick、没有扫描世界</b>）</h2>
 * <pre>
 *   ① 同一结构：anchor instanceof IMultiPart
 *        → IMultiPart#getControllers()            （javap 存在的 abstract 方法）
 *        → IMultiController#self() : MultiblockControllerMachine
 *        → MultiblockControllerMachine#getParts()
 *      覆盖"我们的 part 和那台上游样板总成装在同一个多方块里"这种最常见的情形。
 *   ② 同一 AE 网格：anchor instanceof IGridConnectedMachine
 *        → getMainNode() : IManagedGridNode     （appeng.me.helpers.IGridConnectedBlockEntity 的抽象方法）
 *        → getGrid() : IGrid
 *        → getMachineClasses() / getMachineNodes(类) / IGridNode#getOwner()
 *      覆盖"那台上游样板总成装在<b>另一台</b>多方块里、但接在同一个 ME 网络上"的情形
 *      —— 用户那两台正是这样（{@code @-88,130,83} 的结构里<b>没有</b>我们的 part）。
 * </pre>
 *
 * <h2>3. 🔴 为什么必须用 {@code getMachineClasses()} 而不是 {@code getMachines(ICraftingProvider.class)}</h2>
 * 这条写下来是为了防止以后有人"顺手简化"成一句 {@code grid.getMachines(ICraftingProvider.class)} ——
 * 那样写<b>永远返回空集，而且不报错</b>。证据是 AE2 自己的字节码
 * （{@code appliedenergistics2-forge-15.4.10.jar}）：
 * <pre>
 *   javap -p -c appeng.me.Grid#add(GridNode, CompoundTag)   偏移 0–12：
 *       machines.put(node.getOwner().getClass(), node)          ← 键是【具体类】，不是接口
 *   javap -p -c appeng.me.Grid#getMachines(Class)           偏移 0–10：
 *       machines.get(clazz)                                     ← 按这个键查
 * </pre>
 * ⇒ {@code getMachines(ICraftingProvider.class)} 查的是一个<b>不存在的键</b>。
 * 正确写法：{@code getMachineClasses()} 拿到所有具体类 → {@code ICraftingProvider.class.isAssignableFrom(c)}
 * 过滤 → {@code getMachineNodes(c)} 拿节点 → {@code getOwner()}。
 *
 * <h2>4. 只读边界（一个字节都不许写）</h2>
 * 本类只调：{@code getControllers} / {@code getParts} / {@code getMainNode} / {@code getGrid} /
 * {@code getMachineClasses} / {@code getMachineNodes} / {@code getOwner}，
 * 外加 {@link ShanhaiLocateRegistry#isLive} 那道"这个位置现在还是不是那台 BE"的复核（也只读）。
 * <b>不调</b> {@code getAvailablePatterns()}（那是搜索那一步的事）、
 * <b>不调</b> {@code pushPattern}、不碰任何字段、不发 {@code requestUpdate}。
 */
public final class ShanhaiLocateDiscovery {

    /** 来源：同一多方块结构里的仓室。 */
    public static final int SOURCE_MULTIBLOCK = 1;

    /** 来源：同一个 AE 网格上的机器。 */
    public static final int SOURCE_GRID = 2;

    /**
     * 一次搜索最多回收多少台（硬上限，防止巨型 ME 网络把一次请求拖成几十毫秒）。
     * <p>用户规格是「1 秒最多 1 次请求」⇒ 这个上限只影响单次耗时的上界，不影响正确性。
     */
    public static final int MAX_DISCOVERED = 64;

    private ShanhaiLocateDiscovery() {}

    /** 一次发现的结果：<b>哪台 part</b> ＋ <b>从哪条路径摸到的</b>（只用于日志，不参与判定）。 */
    public static final class Found {

        public final MetaMachine part;
        public final int source;

        Found(MetaMachine part, int source) {
            this.part = part;
            this.source = source;
        }
    }

    /**
     * 从一台已知 part 出发，只读地回收周围的样板总成。
     *
     * @param anchor 注册表里那台（我们自己报名过的 part）
     * @return 去重后的候选（可能为空）；任何一步失败只丢那一台，<b>绝不抛异常给调用方</b>
     */
    public static List<Found> around(MetaMachine anchor) {
        final List<Found> out = new ArrayList<>();
        if (anchor == null) {
            return out;
        }
        addAll(out, sameStructure(anchor), SOURCE_MULTIBLOCK);
        addAll(out, sameGrid(anchor), SOURCE_GRID);
        return out;
    }

    /**
     * 这台 part 所在的 AE 网格的<b>身份令牌</b>（只用于"同一张网只走一遍"的去重；{@code null} = 没网）。
     *
     * <p>为什么需要：一次搜索会对<b>每一个</b>注册表条目各调一次 {@link #around}，而同一结构里的
     * 几台 part 都在同一张网上 ⇒ 不做去重就会对同一张网络重复枚举 N 遍
     * （用户点一次 shift+右键就卡一下，正是本项目付过账的那类"看不见的卡顿"）。
     * 用 {@code IGrid} 对象本身当键（identity 语义），不去碰它的任何字段。
     */
    public static Object gridToken(MetaMachine anchor) {
        if (!(anchor instanceof IGridConnectedMachine gridConnected)) {
            return null;
        }
        try {
            final IManagedGridNode main = gridConnected.getMainNode();
            return main == null ? null : main.getGrid();
        } catch (Throwable t) {
            return null;
        }
    }

    /** ① 同一多方块结构里的仓室（路径与理由见类注释 §2①）。 */
    public static List<MetaMachine> sameStructure(MetaMachine anchor) {
        final List<MetaMachine> out = new ArrayList<>();
        if (!(anchor instanceof IMultiPart multiPart)) {
            return out;
        }
        List<IMultiController> controllers;
        try {
            controllers = multiPart.getControllers();
        } catch (Throwable t) {
            return out;
        }
        if (controllers == null) {
            return out;
        }
        for (IMultiController controller : controllers) {
            if (controller == null) {
                continue;
            }
            List<IMultiPart> parts;
            try {
                final MultiblockControllerMachine self = controller.self();
                if (self == null) {
                    continue;
                }
                parts = self.getParts();
            } catch (Throwable t) {
                continue;
            }
            if (parts == null) {
                continue;
            }
            for (IMultiPart part : parts) {
                addPart(out, part);
                if (out.size() >= MAX_DISCOVERED) {
                    return out;
                }
            }
        }
        return out;
    }

    /** ② 同一个 AE 网格上的机器（「键是具体类」那条坑见类注释 §3）。 */
    public static List<MetaMachine> sameGrid(MetaMachine anchor) {
        final List<MetaMachine> out = new ArrayList<>();
        if (!(anchor instanceof IGridConnectedMachine gridConnected)) {
            return out;
        }
        final IGrid grid;
        try {
            final IManagedGridNode main = gridConnected.getMainNode();
            grid = main == null ? null : main.getGrid();
        } catch (Throwable t) {
            return out;
        }
        if (grid == null) {
            return out;
        }
        Iterable<Class<?>> classes;
        try {
            classes = grid.getMachineClasses();
        } catch (Throwable t) {
            return out;
        }
        if (classes == null) {
            return out;
        }
        for (Class<?> c : classes) {
            if (c == null || !ICraftingProvider.class.isAssignableFrom(c)) {
                continue;
            }
            Iterable<IGridNode> nodes;
            try {
                nodes = grid.getMachineNodes(c);
            } catch (Throwable t) {
                continue;
            }
            if (nodes == null) {
                continue;
            }
            for (IGridNode node : nodes) {
                if (node == null) {
                    continue;
                }
                final Object owner;
                try {
                    owner = node.getOwner();
                } catch (Throwable t) {
                    continue;
                }
                if (owner instanceof MetaMachine machine) {
                    addPart(out, machine);
                }
                if (out.size() >= MAX_DISCOVERED) {
                    return out;
                }
            }
        }
        return out;
    }

    /**
     * 收一台：必须是 {@code ICraftingProvider}（没有样板的仓室问也白问）、必须还活着、不许重复。
     *
     * <p>{@code ICraftingProvider} 这一条与 {@link ShanhaiLocateCore#kindFor} 的第二道判据是同一件事 ——
     * 这里先筛，是为了<b>不去碰别人的方法</b>（不上网格、不读缓存的仓室连问都不问）。
     */
    private static void addPart(List<MetaMachine> out, IMultiPart part) {
        if (part == null) {
            return;
        }
        try {
            final MetaMachine machine = part.self();
            if (machine == null || !(machine instanceof ICraftingProvider)) {
                return;
            }
            if (!ShanhaiLocateRegistry.isLive(machine)) {
                return;
            }
            if (!containsIdentity(out, machine)) {
                out.add(machine);
            }
        } catch (Throwable ignored) {
            // 单台读不出来 ⇒ 跳过它，不拖垮整次搜索（与 producedKeys 的同一条纪律）。
        }
    }

    private static void addPart(List<MetaMachine> out, MetaMachine machine) {
        if (machine == null) {
            return;
        }
        try {
            if (!(machine instanceof ICraftingProvider)) {
                return;
            }
            if (!ShanhaiLocateRegistry.isLive(machine)) {
                return;
            }
            if (!containsIdentity(out, machine)) {
                out.add(machine);
            }
        } catch (Throwable ignored) {
            // 同上。
        }
    }

    private static boolean containsIdentity(List<MetaMachine> list, MetaMachine machine) {
        for (MetaMachine m : list) {
            if (m == machine) {
                return true;
            }
        }
        return false;
    }

    private static void addAll(List<Found> out, List<MetaMachine> parts, int source) {
        if (parts == null) {
            return;
        }
        for (MetaMachine part : parts) {
            boolean seen = false;
            for (Found f : out) {
                if (f.part == part) {
                    seen = true;
                    break;
                }
            }
            if (!seen) {
                out.add(new Found(part, source));
            }
            if (out.size() >= MAX_DISCOVERED) {
                return;
            }
        }
    }
}
