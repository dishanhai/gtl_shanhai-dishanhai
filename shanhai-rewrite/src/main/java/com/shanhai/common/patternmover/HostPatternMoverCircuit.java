package com.shanhai.common.patternmover;

import com.shanhai.machine.wildcard.ShanhaiSlotCircuit;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

/**
 * 「通配符母槽的<b>编程电路</b>跟着样板一起搬」这件事的<b>纯核</b>
 * （不 import 任何 Minecraft / GTCEu / AE2 / LDLib 类 ⇒ 裸 JVM 可跑断言）。
 *
 * <h2>一、为什么需要它（用户原话，第六刀）</h2>
 * 「等一下，通配符样板的编程电路没有复制和黏贴」——
 * 搬运工具把通配符样板<b>搬过去</b>之后，星律母槽上原来那个 1..32 的电路设置丢了。
 * 根因很直白：<b>电路不是样板物品的一部分</b>。它住在机器的
 * {@code StellarSampleAssemblerPartMachine#shanhaiWildcardCircuit}（{@code byte[50]}，下标 = 母槽号），
 * 而我们的条目只拷了 {@code ItemStack.serializeNBT()}。所以：
 * <pre>
 *   读：母槽 i（= 槽号 1080 + i）上的电路 ⇒ 记进条目的 {@code circuit} 键
 *   写：往母槽 j 里写完样板 ⇒ 把 {@code circuit} 写回机器的那个数组
 * </pre>
 *
 * <h2>二、槽号映射（🔴 这是本类唯一"硬口径"，落地前已核三处）</h2>
 * 母槽号 {@code i} ⇔ 合并视图里的槽号 {@code PATTERN_SLOT_COUNT + i} = <b>{@code 1080 + i}</b>，
 * 三处独立代码逐字一致：
 * <pre>
 *   StellarSampleAssemblerPartMachine#WILDCARD_API_SLOT_OFFSET = PATTERN_SLOT_COUNT  （L306）
 *   #shanhaiWildcardMotherOfSlot(slot)   slot - WILDCARD_API_SLOT_OFFSET            （L7567-7568）
 *   #CombinedTerminalPatternInventory#getStackInSlot   slot - PATTERN_SLOT_COUNT     （L4622-4623）
 *   #shanhaiRecalculateWildcardCatalyst  getInternalSlot(WILDCARD_API_SLOT_OFFSET + mother)（L4031）
 * </pre>
 * ⇒ 本类的 {@link #motherSlotOf} 就按「分区总成的通配符区起点 + 下标」算，<b>不写 1080 字面量</b>
 * （偏移由调用方给，见 {@link Source#mothers}）。
 *
 * <h2>🆕 二·补（第七刀）：<b>第二台</b>有电路 API 的总成 = 超级通配符总成，索引域不一样</h2>
 * 用户实测（2026-11-06 之后）：「通配符还是没有电路，这可能是由于通配符的电路选择是我们那里
 * 比较特殊的选法导致的」——<b>他是在超级通配符总成上设的</b>。那台的电路 API 是另一套
 * （{@code int shanhaiSlotCircuit(int)} / {@code boolean shanhaiApplySlotCircuit(int,int)}），
 * 索引是<b>它自己的槽号</b>，<b>不是</b> 1080+i：
 * <pre>
 *   SuperWildcardPatternBufferPartMachine#WILDCARD_SLOT_COUNT = 10                    （L143）
 *   #internalPatternInventory#size()                → WILDCARD_SLOT_COUNT             （L428-430）
 *   #internalPatternInventory#getStackInSlot(i)     → wildcardPatternSlot[i]          （L433-435）
 *   #slotCircuit[i]（@Persisted @DescSynced int[10]）                                  （L190-192）
 *   #shanhaiSlotCircuit(i) / #shanhaiApplySlotCircuit(i, n)（i 域 0..9）                （L902-953）
 * </pre>
 * ⇒ 容器槽号 {@code i} ⇔ 它自己的槽号 {@code i}，<b>起点 = 0</b>：{@link Source#selfIndexed}。
 * ⚠️ 这与星律那台不冲突：{@link #motherSlotOf} 的式子一个字都没改，只是起点不同。
 *
 * <h2>三、🔴「0 = 不设置」与「不动目标」</h2>
 * <ul>
 *   <li>{@code 0}（{@link ShanhaiSlotCircuit#NONE}）表示<b>没设置</b>；
 *       1..32 是合法电路号（值域与机器侧 {@link ShanhaiSlotCircuit} 同一个常量，<b>不另立一套</b>）。
 *       越界值（负数 / ≥33）在本类里一律<b>折成 {@link #NONE}</b> —— 与
 *       {@code shanhaiApplyWildcardCircuit} 的归一化口径逐字一致。</li>
 *   <li><b>源那份是 0 ⇒ 目标<b>不动</b></b>（不去清目标原有的电路）。
 *       理由写在这里，也写在报告里：搬运工具的语义是「把源那边的东西带过去」，
 *       而 0 的含义是<b>「源那边没设置」</b>，<b>不是</b>「目标必须被清空」——
 *       两者在数据上不可区分（{@code byte[]} 里没有"这个母槽从没被碰过"的标记）。
 *       拿"源没设置"去清"目标已设置"是<b>凭空删用户的数据</b>；
 *       而反过来的代价只是"目标原来那个电路还在"，用户能看见、也能一键改。
 *       ⇒ 宁可保留，不去删。</li>
 * </ul>
 *
 * <h2>四、字节账</h2>
 * {@code circuit} 键写在<b>条目</b>里（{@code {pattern, type, circuit?}}），所以它
 * <b>天然计入 1.5 MiB 闸</b>：{@link HostPatternMoverEntry} 的 {@code standaloneBytes}
 * 是用「真的 {@code NbtIo.write} 一遍」量出来的（见 {@link HostPatternMoverBytes#writeSize}），
 * 键写进去之后那个数自然变大。实测每块 <b>＋14 B</b>
 * （{@code 1 类型 + 2 名字长 + 7 "circuit" + 4 int}），自检 C35 逐字节对账
 * （含「1653 块 → 多少块」的实测）。<b>不</b>另建一套字节算法。
 * <p>⚠️ 只有 {@code circuit != 0} 才写那个键（0 不写）⇒
 * ① 老物品/老存档没有这个键，读出来就是 0（<b>向后兼容</b>，一个字节都没多）；
 * ② 普通槽 / 非星律总成读出来的条目<b>不会</b>凭空多 14 B。
 *
 * <h2>五、同 key 合并时电路<b>不</b>走"新的覆盖旧的"（用户 2026-11-06 拍板）</h2>
 * 见 {@link #mergeForDedup}：0 与 5 ⇒ <b>5</b>（不是 0）。理由是一旦按"后读覆盖"，
 * 「先从母槽读到 5、再从别处读到同一配方（那里没设电路）」就会把 5 丢掉 —— 那是真丢数据。
 * 其余字段（{@code pattern} / {@code type}）仍按"新的覆盖旧的"。
 * 两边都是不同非 0 ⇒ 保留后读到的 ＋ 一行 {@code circuit_conflict}（同 key 最多一次）。
 */
public final class HostPatternMoverCircuit {

    /** 条目（{@code shanhai:mover.patterns[]} 的元素）里记电路号的键。 */
    public static final String K_CIRCUIT = "circuit";

    /**
     * 「没设置」的规范值。
     *
     * <p>🔴 取 {@link ShanhaiSlotCircuit#NONE}（= 0），<b>不在这里另立一个 0</b> ——
     * 那是 {@code static final int} 编译期常量，javac 会内联进来，运行时<b>不会</b>去加载
     * {@code ShanhaiSlotCircuit}（离线断言跑的就是这条路，与 {@code KEY_RECIPE_ID} 同一处置）。
     */
    public static final int NONE = ShanhaiSlotCircuit.NONE;

    /** 合法电路号下界（同上：编译期常量，内联）。 */
    public static final int MIN = ShanhaiSlotCircuit.MIN;

    /** 合法电路号上界（同上）。 */
    public static final int MAX = ShanhaiSlotCircuit.MAX;

    /**
     * 「某格上的电路号」的取法（生产实现 = {@code stellar::shanhaiWildcardCircuit} /
     * {@code superWildcard::shanhaiSlotCircuit}）。
     *
     * <p>它只收<b>电路索引</b>（星律 = 母槽号 {@code 0..49}；超级通配符 = 它自己的槽号 {@code 0..9}），
     * 不收合并视图的槽号 —— 槽号 ⇒ 索引那一步在 {@link #motherSlotOf} 里，
     * 是纯算术、可离线断言。
     */
    public interface MotherLookup {

        /** 第 {@code mother} 格（电路索引）的电路号；没设置 ⇒ {@link #NONE}。 */
        int circuitAt(int mother);
    }

    /**
     * 「把某格的电路写成 {@code circuit}」的写法（生产实现 =
     * {@code stellar::shanhaiApplyWildcardCircuit} / {@code superWildcard::shanhaiApplySlotCircuit}）。
     *
     * <p>🔴 与 {@link MotherLookup} 成对：<b>同一台总成</b>的读和写必须走<b>同一个索引域</b>
     * （星律都是母槽号，超级通配符都是它自己的槽号）。索引由
     * {@link Source#indexAt(int)} 算，<b>这里只负责"把那个 int 交出去"</b>。
     *
     * <p>⚠️ 传进来的 {@code circuit} 恒为 {@code 1..32}：0（= 不设置）在
     * {@link Source#applyAt} 里就被拦掉了，绝不会到这里 —— 因为对两台机器来说
     * {@code 0} 的语义都是"清除"，而"源那边没设置"<b>不等于</b>"要把目标清掉"。
     */
    public interface SlotCircuit {

        /**
         * 把第 {@code index} 格（电路索引）设成 {@code circuit}（恒 1..32）。
         *
         * @return 机器<b>接受</b>了才 {@code true}；机器明确拒绝（超级通配符会返回 false：
         *         值没变 / 该格没有通配符样板 / 客户端侧）⇒ {@code false}。
         *         ⚠️ 星律那台的 API 是 {@code void} ⇒ 适配器按"没抛就是接受"补一个 {@code true}
         *         （它内部 {@code isRemote()} 早退也返回不了信息，这一点两边口径一致地写在报告里）。
         */
        boolean set(int index, int circuit);
    }

    /** 日志里的来源标签：本期没有电路 API 的总成。 */
    public static final String KIND_NONE = "none";

    /** 日志里的来源标签：只按分区信息判（4 参构造的老形态，自检用）。 */
    public static final String KIND_ZONED = "zoned";

    /** 日志里的来源标签：<b>星律样板总成</b>（{@code shanhaiWildcardCircuit}，索引 = 母槽号）。 */
    public static final String KIND_STELLAR = "stellar";

    /**
     * 日志里的来源标签：<b>超级通配符总成</b>
     * （{@code shanhaiSlotCircuit}，索引 = <b>它自己的槽号</b>，与容器槽号 1:1）。
     */
    public static final String KIND_SUPER_WILDCARD = "super";

    /**
     * 一台总成的「电路来源」：<b>槽号 ⇒ 电路索引</b>的分区信息 ＋ 一读一写两个适配器。
     *
     * <h2>🔴 两种来源只是"索引域"不同，判定只有一套</h2>
     * <pre>
     *   星律样板总成   normalSlots=1080, wildcardSlots=50   ⇒ 槽 1080+i ⇔ 母槽 i
     *   超级通配符总成 normalSlots=0,    wildcardSlots=10   ⇒ 槽 i      ⇔ 它自己的槽 i
     * </pre>
     * 两者都用 {@link #motherSlotOf} 那<b>同一个式子</b>算索引（起点 + 差），
     * 所以"母槽区间"这件事<b>没有第二份实现</b>；{@link #selfIndexed} 只是把起点填 0。
     *
     * <p>🔴 {@link #circuitAt(int)} / {@link #indexAt(int)} / {@link #applyAt(int, int)} 都是<b>纯的</b>
     * （只用字段算术，不碰机器）⇒ 自检可以拿假 lookup / 假 writer 直接断言
     * 「母槽 5 设 5 ⇒ 1085 读到 5 / 3 号普通槽读到 0」与「超级通配符第 3 格设 7 ⇒ 写回的索引是 3」。
     *
     * @param kind          日志标签（{@link #KIND_STELLAR} / {@link #KIND_SUPER_WILDCARD} / {@link #KIND_NONE}）
     * @param zoned         这台总成有没有「普通槽 / 通配符区」的<b>显式分区</b>（{@code false} ⇒ 恒 {@link #NONE}）
     * @param normalSlots   通配符区在<b>容器视图</b>里的起点（{@code -1} = 不知道；超级通配符 = 0）
     * @param wildcardSlots 该区格数（{@code <= 0} = 不知道）
     * @param atMother      电路索引 ⇒ 电路号（{@code null} ⇒ 恒 {@link #NONE}）
     * @param atSlot        电路索引 ⇒ 写机器（{@code null} ⇒ 没有写 API，放置时记 {@code circuitSkipped}）
     */
    public record Source(String kind, boolean zoned, int normalSlots, int wildcardSlots,
                         MotherLookup atMother, SlotCircuit atSlot) {

        public Source {
            if (kind == null || kind.isBlank()) {
                kind = zoned ? KIND_ZONED : KIND_NONE;
            }
            if (atMother == null) {
                // ⚠️ 这里必须写全限定：本 record 自己有一个静态字段 NONE（见下），
                //    简单名 NONE 在内层作用域里会被它【遮蔽】成 Source（踩过一次：javac 报"Source 无法转换为 int"）
                atMother = mother -> HostPatternMoverCircuit.NONE;
            }
        }

        /** 老形态（4 参）：只有读、没有写；{@code kind} 按 {@code zoned} 猜。 */
        public Source(boolean zoned, int normalSlots, int wildcardSlots, MotherLookup atMother) {
            this(zoned ? KIND_ZONED : KIND_NONE, zoned, normalSlots, wildcardSlots, atMother, null);
        }

        /** 老形态（5 参）：读写都给，但 {@code kind} 按 {@code zoned} 猜。 */
        public Source(boolean zoned, int normalSlots, int wildcardSlots, MotherLookup atMother,
                      SlotCircuit atSlot) {
            this(zoned ? KIND_ZONED : KIND_NONE, zoned, normalSlots, wildcardSlots, atMother, atSlot);
        }

        /** 「这台总成没有电路可言」（普通 gtlcore 总成 / 认不出的总成）。 */
        public static final Source NONE = new Source(KIND_NONE, false, -1, -1,
                mother -> HostPatternMoverCircuit.NONE, null);

        /**
         * <b>按母槽号</b>读写的那一种（星律样板总成）。
         *
         * @param normalSlots   普通槽数（= 通配符区在合并视图里的起点，{@code PATTERN_SLOT_COUNT}）
         * @param wildcardSlots 母槽数（{@code WILDCARD_SLOT_COUNT}）
         */
        public static Source mothers(String kind, int normalSlots, int wildcardSlots,
                                     MotherLookup read, SlotCircuit write) {
            return new Source(kind == null ? KIND_STELLAR : kind, true, normalSlots, wildcardSlots,
                    read, write);
        }

        /**
         * <b>按总成自己的槽号</b>读写的那一种（超级通配符总成）。
         *
         * <p>🔴 为什么起点是 0：它 {@code getTerminalPatternInventory()} 返回的
         * {@code internalPatternInventory} 就是 10 格本身（{@code size()=WILDCARD_SLOT_COUNT}、
         * {@code getStackInSlot(i) → wildcardPatternSlot.getStackInSlot(i)}）⇒
         * <b>容器槽号 i 与它的槽号 i 是同一个</b>，既没有 1080 那个偏移、也没有分页。
         * 判据在 {@code SuperWildcardPatternBufferPartMachine.java:426-448 / 1684}。
         */
        public static Source selfIndexed(String kind, int slots, MotherLookup read, SlotCircuit write) {
            return new Source(kind == null ? KIND_SUPER_WILDCARD : kind, true, 0, slots, read, write);
        }

        /** 槽号 ⇒ 该格的<b>电路索引</b>（星律 = 母槽号；超级通配符 = 它自己的槽号）；不在区内 ⇒ {@code -1}。 */
        public int indexAt(int slot) {
            return motherSlotOf(this.zoned, this.normalSlots, this.wildcardSlots, slot);
        }

        /** 这一格现在能不能写电路（在区内<b>且</b>这台总成给了写适配器）。 */
        public boolean writableAt(int slot) {
            return this.atSlot != null && indexAt(slot) >= 0;
        }

        /** 槽号 ⇒ 该格的电路号；不在区内 / 不分区 ⇒ {@link #NONE}。 */
        public int circuitAt(int slot) {
            return pick(this.zoned, this.normalSlots, this.wildcardSlots, slot, this.atMother);
        }

        /**
         * 把 {@code circuit} 写回这一格（<b>唯一</b>的写入口）。
         *
         * <p>三道门，顺序固定：① 不在区内 ⇒ {@code false}（调用方记 {@code circuitSkipped}）；
         * ② 没有写适配器 ⇒ {@code false}（同上）；③ 归一化后是 {@link #NONE}（源那边没设置）
         * ⇒ {@code false} 且<b>一次都不调机器</b>；④ 调 {@code atSlot.set(索引, 值)}。
         *
         * @return 机器<b>接受</b>了这个值才 {@code true}（机器拒绝 ⇒ {@code false}，样板不回滚）
         */
        public boolean applyAt(int slot, int circuit) {
            final int index = indexAt(slot);
            if (index < 0 || this.atSlot == null) {
                return false;
            }
            final int normalized = normalize(circuit);
            if (normalized == HostPatternMoverCircuit.NONE) {
                return false; // 🔴 0 绝不往下写（对两台机器都是"清除"的意思）
            }
            return this.atSlot.set(index, normalized);
        }
    }

    private HostPatternMoverCircuit() {}

    // ================================================================ 槽号 ⇄ 母槽

    /**
     * 槽号 ⇒ <b>母槽号</b>；不是通配符母槽 ⇒ {@code -1}。
     *
     * <p>母槽区间 = {@code [normalSlots, normalSlots + wildcardSlots)}
     * ⇒ 与 {@code HostPatternMoverWildcard#typeToStamp} 的母槽判据<b>同一个式子</b>
     * （不另写一份区间，免得两处漂移）。{@code zoned=false} 的总成没有母槽。
     */
    public static int motherSlotOf(boolean zoned, int normalSlots, int wildcardSlots, int slot) {
        if (!zoned || normalSlots < 0 || wildcardSlots <= 0) {
            return -1;
        }
        if (slot < normalSlots || slot >= normalSlots + wildcardSlots) {
            return -1;
        }
        return slot - normalSlots;
    }

    /**
     * 槽号 ⇒ 该格所属母槽的电路号（<b>唯一判定点</b>；自检 C31 打的就是它）。
     *
     * <p>三条早退：① 不分区 ⇒ {@link #NONE}（普通总成没有母槽）；
     * ② 槽号不在母槽区间 ⇒ {@link #NONE}（普通槽<b>不</b>写 {@code circuit} 键）；
     * ③ lookup 抛异常 ⇒ {@link #NONE}（读电路失败绝不许影响读样板）。
     */
    public static int pick(boolean zoned, int normalSlots, int wildcardSlots, int slot,
                           MotherLookup atMother) {
        final int mother = motherSlotOf(zoned, normalSlots, wildcardSlots, slot);
        if (mother < 0 || atMother == null) {
            return NONE;
        }
        try {
            return normalize(atMother.circuitAt(mother));
        } catch (Throwable t) {
            return NONE;
        }
    }

    /** 1..32 原样返回；其它（含 0 / 负数 / ≥33）一律 {@link #NONE}。 */
    public static int normalize(int circuit) {
        return circuit >= MIN && circuit <= MAX ? circuit : NONE;
    }

    // ================================================================ 同 key 合并（去重时怎么定电路）

    /**
     * ★<b>同一条记录被合并两次时，电路取哪个</b>（用户 2026-11-06 拍板：「取非 0 的那一个」）。
     *
     * <h2>为什么不能沿用「新的覆盖旧的」</h2>
     * 那个口径对 type 是对的（后读到的类型更新），对电路<b>会真的丢数据</b>：
     * <pre>
     *   第 1 次：从星律母槽读到同一配方，circuit = 5
     *   第 2 次：又从普通槽 / 另一台机器读到同一配方，circuit = 0（那里没设电路）
     *   「新的覆盖旧的」⇒ 合并后 circuit = 0 ⇒ 读的时候明明读到了 5，放的时候一个都不写回
     * </pre>
     * ⇒ 规则（三条，覆盖全部组合）：
     * <pre>
     *   后读到的是非 0              ⇒ 用后读到的（0 与 5 ⇒ 5；5 与 7 ⇒ 7）
     *   后读到的是 0、先前是非 0    ⇒ 【保留先前的】（5 与 0 ⇒ 5）← 这条是本修正的全部意义
     *   两边都是 0                  ⇒ 0（不设置）
     * </pre>
     * 「两边都是不同非 0」时保留后读到的（与"新的覆盖旧的"同向），调用方负责打一行
     * {@code circuit_conflict}（见 {@link HostPatternMoverCodec#merge}）。
     *
     * @param existing 表里已有的那条的电路
     * @param incoming 这次读到的电路
     * @return 合并后该记的电路（永远是 0 或 1..32）
     */
    public static int mergeForDedup(int existing, int incoming) {
        final int a = normalize(existing);
        final int b = normalize(incoming);
        if (b != NONE) {
            return b; // 后读到的非 0：用它（含"两边同值"与"两边不同非 0"两种情况）
        }
        return a; // 后读到的是 0：保留先前的非 0（这就是"取非 0 的那一个"）
    }

    /**
     * 这次合并是不是<b>真冲突</b>（两边都是非 0 且不同 ⇒ 必须打一行 {@code circuit_conflict}）。
     *
     * <p>{@code 5 & 5} 不算冲突（同值合并是常态，打日志就是刷屏）。
     */
    public static boolean isConflict(int existing, int incoming) {
        final int a = normalize(existing);
        final int b = normalize(incoming);
        return a != NONE && b != NONE && a != b;
    }

    // ================================================================ 条目 NBT

    /**
     * 把电路号写进条目的复合标签（{@code circuit != 0} 才写；否则<b>删掉</b>已有的键）。
     *
     * @return 有没有改动这个标签
     */
    public static boolean putToTag(CompoundTag entryTag, int circuit) {
        if (entryTag == null) {
            return false;
        }
        final int c = normalize(circuit);
        if (c == NONE) {
            // 0 = 不设置 ⇒ 不留键（老数据的形状就该是这样，别凭空多 14 B）
            // ⚠️ CompoundTag#remove 在 1.20.1 返回 void（不是 Tag）⇒ 必须先问 contains
            if (entryTag.contains(K_CIRCUIT)) {
                entryTag.remove(K_CIRCUIT);
                return true;
            }
            return false;
        }
        final boolean changed = !entryTag.contains(K_CIRCUIT, Tag.TAG_INT)
                || entryTag.getInt(K_CIRCUIT) != c;
        entryTag.putInt(K_CIRCUIT, c);
        return changed;
    }

    /** 从条目复合标签读电路号；没有这个键 / 坏值 ⇒ {@link #NONE}（<b>向后兼容</b>的落点）。 */
    public static int ofTag(CompoundTag entryTag) {
        if (entryTag == null || !entryTag.contains(K_CIRCUIT, Tag.TAG_INT)) {
            return NONE;
        }
        try {
            return normalize(entryTag.getInt(K_CIRCUIT));
        } catch (Throwable t) {
            return NONE;
        }
    }

    // ================================================================ 计数

    /**
     * 电路这一路的四个计数（读一侧用前两个、写一侧用后两个；合在一个类里是为了日志只用一份读数）。
     *
     * <pre>
     *   captured  读：记下了几个【非 0】电路（读取汇总行 circuits=）
     *   applied   放：成功写回几个【非 0】电路（放置日志 circuits=）
     *   skipped   放：目标那一格没有电路 API / 不在它的电路区（⇒ 忽略；放置日志 circuitSkipped=）
     *   failed    放：调了但抛异常（样板<b>不许</b>回滚，只记一行 WARN）
     * </pre>
     */
    public static final class Tally {

        private int captured;
        private int applied;
        private int skipped;
        private int failed;

        public void addCaptured() {
            this.captured++;
        }

        public void addApplied() {
            this.applied++;
        }

        public void addSkipped() {
            this.skipped++;
        }

        public void addFailed() {
            this.failed++;
        }

        /** 合并另一份（读：每台总成各有一份，扫完并起来）。 */
        public void merge(Tally other) {
            if (other == null) {
                return;
            }
            this.captured += other.captured;
            this.applied += other.applied;
            this.skipped += other.skipped;
            this.failed += other.failed;
        }

        public int captured() {
            return this.captured;
        }

        public int applied() {
            return this.applied;
        }

        public int skipped() {
            return this.skipped;
        }

        public int failed() {
            return this.failed;
        }

        public String describe() {
            return "captured=" + this.captured + " applied=" + this.applied
                    + " skipped=" + this.skipped + " failed=" + this.failed;
        }
    }
}
