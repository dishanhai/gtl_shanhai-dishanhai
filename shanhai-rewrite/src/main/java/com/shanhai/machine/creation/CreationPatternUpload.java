package com.shanhai.machine.creation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * P7「写样板 → 自动上传」两条链的<b>接管核心</b>：把一批样板<b>整批</b>写进某台机器的样板库存
 * （原初造物编译矩阵的 1000 格），并且<b>要么全写进去、要么一个字节都不写</b>。
 *
 * <h2>1. 这个类为什么是<b>零 Minecraft / 零 AE2 / 零 gtlcore 依赖</b>的</h2>
 * 因为它要能被<b>离线自证</b>（{@code temp\molmanip\p7selftest}：不启动 Forge、不加载任何模组 jar，
 * 纯 {@code javac + java}）。机器侧只提供一个「收据」（{@link Sink}，10 个动作）把
 * {@code ItemStackTransfer} 的 1000 格翻译成这台机器能听懂的话；<b>算法全部在这里</b>，
 * 所以自证跑的是<b>生产代码本身</b>，不是另写一份副本。
 *
 * <h2>2. 🔴 三条硬约束（本类存在的全部理由）</h2>
 * <ol>
 *   <li><b>全或无</b>（用户 2026 派单原话：「容量不足（1000 格塞不下这批）⇒ 整批不收，返回不 cancel
 *       （不许收一半，否则玩家丢样板）」）⇒ {@link #decide} 是<b>先规划后提交</b>：
 *       {@link Decision#plan()} 拿到的是「每个栈落到第几号槽」的<b>只读计划</b>，
 *       规划期一次真写都不做；只有 {@link Plan#commit()} 才落盘，且落盘前<b>再全量试写一遍</b>。</li>
 *   <li><b>顺序稳定</b>（同一批 N 张样板 ⇒ 进第 k、k+1、… 号空槽，与样板在批里的先后一致）：
 *       槽位从 <b>0 号向 999 号升序</b>扫，批内顺序即列表顺序 ⇒ 同一输入永远得到同一组槽号。</li>
 *   <li><b>失败必须有结果码</b>（不许静默）⇒ {@link Reject} 是封闭枚举，调用方据此决定
 *       「不 cancel 交给上游」/「回 DUPLICATE」/「回 FAILED」。</li>
 * </ol>
 *
 * <h2>3. 「能收下这批」的完整判据（顺序即优先级，先判先返回）</h2>
 * <pre>
 *   ① 批次非空（空栈/空批 ⇒ INVALID_STACK）
 *   ② 机器已成型（NOT_FORMED）        —— 没成型 ⇒ AE 也看不见它的样板
 *   ③ AE 节点已激活（NODE_INACTIVE）  —— 没频道/没连网 ⇒ 收下也用不了
 *   ④ 样板类型是这台机器真能做的（NOT_SUPPORTED）
 *      机器侧口径 = {@code IPatternDetails instanceof IMolecularAssemblerSupportedPattern}
 *      = 合成 / 锻造台 / 切石 三类（AE2 里就这三个实现类），
 *      与上游「分子操纵者」收的是<b>同一批</b>样板 ⇒ 这才配得上「我们 &gt; 分子操纵者」这条优先级。
 *   ⑤ 这 1000 格里<b>没有</b>同一块样板（DUPLICATE）—— 有就整批拒，绝不写第二份
 *   ⑥ 剩余空格 &ge; 批大小，且每张样板都能通过槽过滤器（NO_CAPACITY / FILTER_REJECTED）
 *   ⇒ 六道全过才 ACCEPTED。
 * </pre>
 *
 * <h2>4. 两个调用点（两条链各自怎么用）</h2>
 * <table border="1">
 *   <caption>典型用法</caption>
 *   <tr><th>链</th><th>用哪个方法</th><th>期望拿到的结论</th></tr>
 *   <tr><td>链 A（{@code extendedae_plus} 的「编码 → 自动上传」）</td>
 *       <td>{@link #decide} &rarr; {@link Decision#plan()} &rarr; {@link Plan#commit()}</td>
 *       <td>{@code plan != null} 才 {@code ci.cancel()}；{@code null} 就<b>原样放行</b>给
 *           分子操纵者 / AE 装配矩阵</td></tr>
 *   <tr><td>链 B（gtlcore 的一键上传）</td>
 *       <td>搜索期只看 {@link Decision#accepted()}（<b>不提交</b>），真正写的时候才 commit</td>
 *       <td>能收 ⇒ 候选只留我们（不弹菜单）；收不了 ⇒ 候选列表原样不动</td></tr>
 * </table>
 *
 * <p>⚠️ 诚实边界：本类<b>不</b>负责「网格上哪几台机器算候选」「哪台排前面」「怎么把样板从终端里扣掉」
 * —— 那些分别在 {@code PrimordialCreationCompilationMatrix#creationChoiceFor} 与两个 mixin 里，
 * 因为它们必须 import AE2 / gtlcore 的类型。
 */
public final class CreationPatternUpload {

    private CreationPatternUpload() {
    }

    /**
     * 收不下的<b>结果码</b>（封闭枚举：调用方 switch 它，而不是去解析日志文本）。
     *
     * <p>{@link #ACCEPTED} 只出现在 {@link Decision#accepted()} 为真的那支；
     * 其余每一个都对应一条明确的「为什么没收」，链 B 会把它们翻译成
     * gtlcore 的 {@code UploadStatus}（见 {@code ShanhaiCreationUploadChainBMixin}）。
     */
    public enum Reject {
        /** 收下了（此时 {@link Decision#plan()} 非 null）。 */
        ACCEPTED,
        /** 批次为空 / 里面全是空栈。 */
        INVALID_STACK,
        /** 没有我们的机器（调用方压根不该走到这里）。 */
        NO_MACHINE,
        /** 机器在，但结构没成型。 */
        NOT_FORMED,
        /** 机器成型了，但 AE 网格节点没激活（没频道 / 网络没连上）。 */
        NODE_INACTIVE,
        /** 样板类型不是这台机器能做的（不是分子装配能做的那三类）。 */
        NOT_SUPPORTED,
        /** 这 1000 格里已经有同一块样板了。 */
        DUPLICATE,
        /** 剩余空格不够这批（🔥 整批不收）。 */
        NO_CAPACITY,
        /** 有空格，但槽过滤器把这块样板拒了（槽过滤器 = {@code shanhai$isPatternStackAcceptable}）。 */
        FILTER_REJECTED;

        /** 日志/报告里用的中文说明（不含任何逻辑，改文案不影响行为）。 */
        public String why() {
            switch (this) {
                case ACCEPTED: return "整批收下";
                case INVALID_STACK: return "批次为空";
                case NO_MACHINE: return "没有我们的机器";
                case NOT_FORMED: return "结构没成型";
                case NODE_INACTIVE: return "AE 节点没激活";
                case NOT_SUPPORTED: return "样板类型不归这台机器做";
                case DUPLICATE: return "这 1000 格里已有同一块样板";
                case NO_CAPACITY: return "剩余空格不够这批（整批不收）";
                case FILTER_REJECTED: return "槽过滤器拒收这块样板";
                default: return name();
            }
        }
    }

    /**
     * 「一台机器的样板库存」收据 —— 本类只认这 10 个动作，<b>不认</b> {@code ItemStackTransfer}、
     * 不认 {@code InternalInventory}、不认 {@code ItemStack}。
     *
     * <p>机器侧的实现见 {@code PrimordialCreationCompilationMatrix#creationUploadSink()}；
     * 自证里的假实现见 {@code P7UploadTakeoverTest.FakeMachine}（用 {@code String} 当"样板"）。
     *
     * @param <T> 栈类型（生产 = {@code net.minecraft.world.item.ItemStack}）
     */
    public interface Sink<T> {

        /** 日志标识（坐标 + 机器名）。只用于人读的日志，不参与任何判断。 */
        String describe();

        /**
         * 反查宿主对象（链 B 要把「选中的那台」翻译成 gtlcore {@code Target} 的坐标）。
         *
         * <p>⚠️ 只许 {@code instanceof} 用：本类零 MC 依赖，不能在这里转成具体机器类型。
         */
        Object owner();

        /** 结构成型了吗（{@code IMultiController#isFormed()}）。 */
        boolean isFormed();

        /** AE 网格节点此刻激活吗（{@code IManagedGridNode#isActive()}：有频道、连着网络）。 */
        boolean isNodeActive();

        /** 这块样板<b>该不该归这台机器做</b>（机器侧口径 = 分子装配能做的三类）。 */
        boolean accepts(T stack);

        /** 库存总格数（本机 = 1000）。 */
        int slotCount();

        /** 该槽是空的吗。 */
        boolean isEmptySlot(int slot);

        /** 该槽里是不是<b>同一块</b>样板（查重用，与 {@link #isItemValid} 无关）。 */
        boolean isSameStack(int slot, T stack);

        /** 槽过滤器：这块样板允许放进该槽吗（本机 = {@code shanhai$isPatternStackAcceptable}）。 */
        boolean isItemValid(int slot, T stack);

        /** <b>只读</b>试写：这块样板能完整放进该槽吗（模拟，绝不改库存）。 */
        boolean canInsertInto(int slot, T stack);

        /** 真写（不可逆）：返回是否<b>完整</b>写进去了。 */
        boolean insertInto(int slot, T stack);
    }

    /** 一个「第几号槽放哪个栈」的落位。 */
    public record Placement<T>(int slot, T stack) {
    }

    /**
     * 一批样板的落位计划：<b>只读</b>，{@link #commit()} 之前一个字节都没写进库存。
     *
     * <p>为什么要有这一层（而不是 {@code insertAll} 直接写）：
     * <ul>
     *   <li>链 B 的「搜索」与「写入」是<b>两次独立回调</b>（先 {@code findTargets}，玩家点上传后
     *       才 {@code insertIntoTargetSlotResult}）⇒ 搜索期只能<b>规划</b>，不能写；</li>
     *   <li>全或无需要一个「先问完所有槽、再统一落盘」的窗口。</li>
     * </ul>
     */
    public static final class Plan<T> {

        private final Sink<T> sink;
        private final List<Placement<T>> placements;
        private final int firstSlot;

        private Plan(Sink<T> sink, List<Placement<T>> placements) {
            this.sink = sink;
            this.placements = Collections.unmodifiableList(new ArrayList<>(placements));
            this.firstSlot = placements.isEmpty() ? -1 : placements.get(0).slot();
        }

        public Sink<T> sink() {
            return sink;
        }

        /** 落位表（槽号升序、与批内顺序一一对应；只读）。 */
        public List<Placement<T>> placements() {
            return placements;
        }

        /** 这批样板的第一块落在几号槽（{@code -1} = 空计划）。链 B 拿它当返回的 {@code slot}。 */
        public int firstSlot() {
            return firstSlot;
        }

        public int size() {
            return placements.size();
        }

        /**
         * <b>真正落盘</b>：先把整批<b>再试写一遍</b>（同一个 tick、同一条服务端线程 ⇒ 这段时间里没人能改库存），
         * 全部通过才逐个真写。
         *
         * @return {@code true} = 整批都写进去了；{@code false} = <b>一个都没写</b>（试写阶段就否掉）
         *         或「写了一半才发现失败」（后者只可能在上游回调本身有副作用时发生，
         *         那时只能把已经写进去的如实报出来 —— 调用方必须打 ERROR 日志，不许静默）
         */
        public boolean commit() {
            if (placements.isEmpty()) {
                return true;
            }
            // ① 再全量复核一遍（槽过滤器 + 试写）：任何一块不通过 ⇒ 整批放弃（一个字节都还没写）
            //    为什么连 isItemValid 也要复核：规划与提交之间隔着一次服务端回调（链 B 就是两次独立回调），
            //    这中间机器的状态可能变（散架 / 过滤器对应的解码环境变了）⇒ 提交前的判据必须与规划时**同一套**。
            for (Placement<T> p : placements) {
                if (!sink.isItemValid(p.slot(), p.stack()) || !sink.canInsertInto(p.slot(), p.stack())) {
                    return false;
                }
            }
            // ② 逐块真写（顺序 = 批内顺序）
            int written = 0;
            for (Placement<T> p : placements) {
                if (!sink.insertInto(p.slot(), p.stack())) {
                    if (written > 0) {
                        // 半个批次：本类无法回滚（库存接口没有原子提交），交给调用方打 ERROR
                        throw new PartialCommitException(written, placements.size(), p.slot());
                    }
                    return false;
                }
                written++;
            }
            return true;
        }
    }

    /** 真写到一半失败（理论上不该发生：① 已经全量试写过）。抛出而不是静默吞掉。 */
    public static final class PartialCommitException extends RuntimeException {

        private final int written;
        private final int total;
        private final int failedSlot;

        PartialCommitException(int written, int total, int failedSlot) {
            super("整批写入只成功 " + written + " / " + total + " 块，第 " + failedSlot + " 号槽失败");
            this.written = written;
            this.total = total;
            this.failedSlot = failedSlot;
        }

        public int written() {
            return written;
        }

        public int total() {
            return total;
        }

        public int failedSlot() {
            return failedSlot;
        }
    }

    /** 一次「收不收」的判定结果 = 结果码 + （收下时的）落位计划。 */
    public static final class Decision<T> {

        private final Reject reject;
        private final Plan<T> plan;

        private Decision(Reject reject, Plan<T> plan) {
            this.reject = reject;
            this.plan = plan;
        }

        public static <T> Decision<T> rejected(Reject reject) {
            return new Decision<>(reject, null);
        }

        public static <T> Decision<T> accepted(Plan<T> plan) {
            return new Decision<>(Reject.ACCEPTED, plan);
        }

        /** 收下了吗（= 有落位计划）。<b>链 A 的 {@code ci.cancel()} 只看这一个值。</b> */
        public boolean accepted() {
            return plan != null;
        }

        /** 结果码（收下时 = {@link Reject#ACCEPTED}）。 */
        public Reject reject() {
            return reject;
        }

        /** 落位计划（只在 {@link #accepted()} 为真时非 null）。 */
        public Plan<T> plan() {
            return plan;
        }

        /** 一行日志用：{@code ACCEPTED(3 块 → 槽 17..19)} / {@code NO_CAPACITY(剩余空格 2 < 批 3)}。 */
        public String describe() {
            if (plan == null) {
                return reject.name() + "(" + reject.why() + ")";
            }
            return "ACCEPTED(" + plan.size() + " 块 → 槽 " + plan.firstSlot()
                    + ".." + (plan.firstSlot() + plan.size() - 1) + ")";
        }
    }

    // ═════════════════════════════ 判定 + 落位规划 ═════════════════════════════

    /**
     * 单台机器：这批样板<b>能不能整批收下</b>（判据见类注释 §3，六道闸）。
     *
     * <p>本方法<b>只读</b>：不写库存、不改任何状态、不打日志。
     */
    public static <T> Decision<T> decide(Sink<T> sink, List<T> stacks) {
        if (sink == null) {
            return Decision.rejected(Reject.NO_MACHINE);
        }
        if (!sink.isFormed()) {
            return Decision.rejected(Reject.NOT_FORMED);
        }
        if (!sink.isNodeActive()) {
            return Decision.rejected(Reject.NODE_INACTIVE);
        }
        if (stacks == null || stacks.isEmpty()) {
            return Decision.rejected(Reject.INVALID_STACK);
        }
        // ① 批次里每个栈都必须是"这台机器该做的样板"
        for (T stack : stacks) {
            if (stack == null) {
                return Decision.rejected(Reject.INVALID_STACK);
            }
            if (!sink.accepts(stack)) {
                return Decision.rejected(Reject.NOT_SUPPORTED);
            }
        }
        // ② 查重：整批里任何一块已经在库存里 ⇒ 整批拒（绝不写第二份）
        final int slots = sink.slotCount();
        for (T stack : stacks) {
            for (int slot = 0; slot < slots; slot++) {
                if (sink.isSameStack(slot, stack)) {
                    return Decision.rejected(Reject.DUPLICATE);
                }
            }
        }
        // ③ 容量闸：剩余空格 < 批大小 ⇒ 直接整批拒（用户口径：不许收一半）
        int freeSlots = 0;
        final boolean[] occupied = new boolean[slots];
        for (int slot = 0; slot < slots; slot++) {
            if (sink.isEmptySlot(slot)) {
                freeSlots++;
            } else {
                occupied[slot] = true;   // 已经放着东西的槽：不许再排进来
            }
        }
        if (freeSlots < stacks.size()) {
            return Decision.rejected(Reject.NO_CAPACITY);
        }
        // ④ 落位：空槽从 0 号向 999 号升序找；★ 本批里已经排掉的槽也打上 occupied，
        //    否则第 2 张样板会又挑到第 1 张刚排的那个槽（batch &gt; 1 时必踩，P7 自证 [C2] 盯着这一条）
        final List<Placement<T>> placements = new ArrayList<>(stacks.size());
        for (T stack : stacks) {
            int chosen = -1;
            boolean sawEmptyButRejected = false;
            for (int slot = 0; slot < slots; slot++) {
                if (occupied[slot]) {
                    continue;
                }
                if (!sink.isItemValid(slot, stack) || !sink.canInsertInto(slot, stack)) {
                    sawEmptyButRejected = true;   // 空格在，但这块样板放不进去（过滤器/槽上限）
                    continue;
                }
                chosen = slot;
                break;
            }
            if (chosen < 0) {
                // 有空格却放不下 ⇒ 过滤器拒收；压根没有空格 ⇒ 容量不足。两种都整批不写。
                return Decision.rejected(sawEmptyButRejected ? Reject.FILTER_REJECTED : Reject.NO_CAPACITY);
            }
            occupied[chosen] = true;
            placements.add(new Placement<>(chosen, stack));
        }
        return Decision.accepted(new Plan<>(sink, placements));
    }

    /**
     * 多台机器：按<b>列表顺序</b>取第一台能整批收下的（这就是"优先级链"在代码里的落法 ——
     * 顺序由调用方给，本方法只认顺序，不认识任何上游类型）。
     *
     * @return 收下的那台机器的计划；{@code null} = <b>谁都不收</b> ⇒ 调用方<b>不接管</b>，
     *         原样交给上游（分子操纵者 / AE 装配矩阵）
     */
    public static <T> Plan<T> planAny(List<? extends Sink<T>> sinks, List<T> stacks) {
        if (sinks == null) {
            return null;
        }
        for (Sink<T> sink : sinks) {
            final Decision<T> decision = decide(sink, stacks);
            if (decision.accepted()) {
                return decision.plan();
            }
        }
        return null;
    }

    /**
     * 多台机器：与 {@link #planAny} 同一套顺序，但把<b>每一台的理由</b>也带出来
     * （给"为什么这次没接管"的日志用；自证里用它断言负对照）。
     */
    public static <T> List<Decision<T>> decideEach(List<? extends Sink<T>> sinks, List<T> stacks) {
        final List<Decision<T>> out = new ArrayList<>();
        if (sinks == null) {
            return out;
        }
        for (Sink<T> sink : sinks) {
            out.add(decide(sink, stacks));
        }
        return out;
    }

    /** 这批样板里有没有哪一块已经被这台机器收着了（链 A 用它把"重复"整批让给上游）。 */
    public static <T> boolean containsAny(Sink<T> sink, List<T> stacks) {
        if (sink == null || stacks == null) {
            return false;
        }
        final int slots = sink.slotCount();
        for (T stack : stacks) {
            if (stack == null) {
                continue;
            }
            for (int slot = 0; slot < slots; slot++) {
                if (sink.isSameStack(slot, stack)) {
                    return true;
                }
            }
        }
        return false;
    }

    // ═════════════════════════════ 内部：不属于任何机器的杂项 ═════════════════════════════
}
