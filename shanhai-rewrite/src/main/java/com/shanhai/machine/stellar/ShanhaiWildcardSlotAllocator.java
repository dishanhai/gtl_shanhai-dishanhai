package com.shanhai.machine.stellar;

import org.jetbrains.annotations.NotNull;

import java.util.Arrays;

/**
 * <b>通配符展开槽号的动态分配器（C3：原版无上限，槽号按"实际展开条数"分配）。</b>
 *
 * <p>纯逻辑、零 Minecraft 依赖 ⇒ 可离线 {@code java} 单跑探针
 * （{@code temp\poolsplit\probe\AllocatorProbe.java}）。
 *
 * <h2>一、为什么要它（改前的错在哪）</h2>
 * 改前是<b>固定上界 K=128</b>：{@code slot = 1130 + mother*128 + branch}，
 * 一块母样板展开 200 条就<b>截断</b>到 128（用户实机报过"60 多种只剩 4 条"的同族问题）。
 * 用户口径是「原版无上限」⇒ <b>槽号必须按实际展开条数分配</b>，不能有一个编译期 K。
 *
 * <h2>二、分配策略（写清楚，报告里也照抄这一段）</h2>
 * <ol>
 *   <li><b>普通槽与母槽不动</b>：{@code 0..1079} 普通样板、{@code 1080..1129} 通配符母槽，
 *       槽号空间的前缀固定（AE/UI/NBT 全都按这个含义写好了）。</li>
 *   <li><b>展开槽从 1130 起，"相对下标" 0,1,2,… 由本类发放</b>
 *       （对外槽号 = {@link ShanhaiWildcardLayout#EXPAND_OFFSET} + 相对下标）。</li>
 *   <li><b>先复用、再抬高水位</b>：回收的槽号进一个 LIFO 空闲栈；下次分配先弹栈，
 *       栈空才把水位（{@code high}）加一。</li>
 *   <li><b>收缩只收尾部</b>：回收后若空闲栈顶正好是 {@code high-1}，就连续弹掉并降水位
 *       （{@code getInternalSlotCount()} 跟着变小）。中间的空洞<b>不整理</b>（不做压缩），
 *       因为"搬家"会让 AE 手里那批槽号整体漂移 —— 记住"稳定优先"。</li>
 * </ol>
 *
 * <h2>三、🔴 AE 样板身份会不会变（必须写清楚的代价）</h2>
 * <ul>
 *   <li><b>同一次重建内</b>：一个 (母槽, 分支) 分配到一个唯一槽号，绝不共享 ⇒
 *       一个池只对应一条配方（用户要的"从结构上不可能串"）。</li>
 *   <li><b>跨重建</b>：如果某块母样板<b>变少</b>了（尾部被回收），它腾出来的槽号会被
 *       <b>后一次</b>重建里的别的母槽复用 ⇒ 同一个样板实例的槽号<b>可能变</b>。
 *       这不会造成错配：每次重建之后机器都会 {@code ICraftingProvider.requestUpdate}（C4），
 *       AE 会把旧清单整个丢掉重读；而槽号只在<b>同一次</b>重建内被解释。</li>
 *   <li><b>只在尾部回收 + 稳定优先</b>：为了把"变号"限制在"真的变少了"的那些尾部槽上，
 *       不搞中间空洞压缩。</li>
 * </ul>
 *
 * <h2>四、复杂度</h2>
 * 分配/回收均摊 O(1)；{@link #motherOf}/{@link #branchOf} 是两条数组读（
 * {@code hasPatternInSlot} 在配方查找热路径上，必须是数组不是 Map）。
 */
public final class ShanhaiWildcardSlotAllocator {

    /**
     * 释放回调（<b>唯一释放点</b> {@link #release(int)} 里逐次触发）。
     *
     * <p>🔴 2026-10-27（C5）：<b>这是"槽号表"与"材质对象表"之间唯一的那条连线。</b>
     * 机器侧把它接成「清空并丢弃该相对下标的 {@code InternalSlot}」——
     * 于是"分配器把槽号还回去了"与"那一格里的料被清掉了"是<b>同一件事</b>，
     * 不可能只做一半（改前只做一半 ⇒ 复用者带着别人的料上来 = 真串料）。
     */
    private java.util.function.IntConsumer onReleased;

    /** 母槽数（10×5 = 50，与 {@link ShanhaiWildcardLayout#MOTHER_COUNT} 同源）。 */
    private final int motherCount;

    /** {@code perMother[mother][branch] = 相对下标}（-1 = 未分配）。 */
    private final int[][] perMother;

    /** 每个母槽已分配几个分支（= 当前展开条数）。 */
    private final int[] counts;

    /** 相对下标 → 母槽（-1 = 空闲）。 */
    private int[] owner = new int[64];

    /** 相对下标 → 分支号（-1 = 空闲）。 */
    private int[] branch = new int[64];

    /**
     * 🔴 2026-10-30（C7）：<b>相对下标 → 这条展开的「样板身份键」</b>（{@code null} = 没有身份）。
     *
     * <p>存在的理由只有一个：<b>重建不许把已经推进来的料弄丢</b>。
     * 改前槽号只按 {@code (母槽, 分支号)} 记，而分支号 = 展开列表里的<b>位置</b> ⇒
     * 只要展开顺序抖动 / 条数抖动，同一个槽号就会被解释成另一条配方，或者被尾部裁掉
     * （{@code release} ⇒ 材质清空）。现在槽号跟着<b>身份键</b>走：
     * 同一母槽的同一条展开，跨重建拿到的是<b>同一个</b>相对下标 ⇒ 同一个 {@code InternalSlot}
     * ⇒ 里面的物品/流体原样还在（见 {@link #reconcile(int, String[])}）。
     */
    private String[] relKey = new String[64];

    /** 身份键 → 相对下标（与 {@link #relKey} 互为反查；{@link #release(int)} 里成对清理）。 */
    private final java.util.HashMap<String, Integer> keyToRel = new java.util.HashMap<>();

    /** LIFO 空闲栈（存相对下标）。 */
    private int[] free = new int[64];

    private int freeTop;

    /** 当前分配水位（合法相对下标 = {@code 0..high-1}；会被 {@link #shrinkTail()} 降下来）。 */
    private int high;

    /**
     * <b>单调水位</b>（只增不减）= 曾经发放到过的最大相对下标 + 1。
     *
     * <p>🔴 2026-10-27（C5）红线：{@code getInternalSlotCount()} 读的是<b>它</b>，不是 {@link #high}。
     * 理由：槽号空间<b>在运行中变小</b>会让「同一个槽号」在被复用后指代另一条展开，
     * 而 AE / 界面 / NBT 手里可能还拿着旧的那个号 ⇒ 那是"覆盖已存在的槽"。
     * 现在空间只增不减（仍按实际展开条数增长，不是回归固定 K），
     * 而 {@link #high} 继续负责"先复用、再抬水位"的省下标策略。
     * <p>⚠️ {@code [high, everHigh)} 这一段是<b>空洞</b>（已释放、尚未再分配）：
     * {@link #isRegistered(int)} / {@link #isHandedOut(int)} 都恒 false，
     * 候选集枚举靠 {@code isRegistered} 把它们滤掉（不许把空洞当槽）。
     */
    private int everHigh;

    public ShanhaiWildcardSlotAllocator(int motherCount) {
        if (motherCount <= 0) {
            throw new IllegalArgumentException("motherCount 必须 > 0，实际 " + motherCount);
        }
        this.motherCount = motherCount;
        this.perMother = new int[motherCount][];
        this.counts = new int[motherCount];
        for (int i = 0; i < motherCount; i++) {
            this.perMother[i] = new int[0];
        }
        // 🔴 必须显式填 -1：数组默认值是 0，会让"未分配的下标"被读成"母槽 0"（静默串槽）。
        Arrays.fill(this.owner, -1);
        Arrays.fill(this.branch, -1);
        Arrays.fill(this.free, -1);
    }

    /**
     * 注册释放回调（机器侧接「清空/丢弃该下标的 InternalSlot」）。
     *
     * <p>可以重复调用（后写的覆盖先写的）；传 {@code null} = 摘掉。
     */
    public void setOnReleased(java.util.function.IntConsumer listener) {
        this.onReleased = listener;
    }

    // ---------------------------------------------------------------- 分配 / 回收

    /**
     * 取（必要时分配）母槽 {@code mother} 的第 {@code branch} 条展开的槽号（相对下标）。
     *
     * <p>同一个 (mother, branch) 反复调用返回<b>同一个</b>下标，直到 {@link #setCount} 把它回收。
     */
    public int slotFor(int mother, int branch) {
        requireMother(mother);
        if (branch < 0) {
            throw new IllegalArgumentException("分支号必须 >= 0，实际 " + branch);
        }
        final int[] row = this.perMother[mother];
        if (branch < row.length && row[branch] >= 0) {
            return row[branch];
        }
        final int rel = this.allocate();
        if (branch >= row.length) {
            final int[] grown = Arrays.copyOf(row, Math.max(branch + 1, row.length == 0 ? 4 : row.length * 2));
            Arrays.fill(grown, row.length, grown.length, -1);
            this.perMother[mother] = grown;
        }
        this.perMother[mother][branch] = rel;
        this.owner[rel] = mother;
        this.branch[rel] = branch;
        if (branch + 1 > this.counts[mother]) {
            this.counts[mother] = branch + 1;
        }
        return rel;
    }

    /**
     * 把母槽 {@code mother} 的展开条数设成 {@code count}：
     * <b>多退少补</b>（少 = 分配新的，多 = 回收尾部的）。
     *
     * <p>重建循环里每个母槽都调一次 —— 于是"展开条数减少 ⇒ 槽号回收 ⇒ 水位可能下降"。
     *
     * <p>🔴 C5：<b>这是"改分配表"的唯一入口</b>，并把「被释放的那些相对下标」交回给调用方。
     * 返回之后那些下标的材质对象<b>已经</b>被 {@link #onReleased} 回调清空/丢弃
     * （回调在 {@link #release(int)} 里触发，是唯一释放点）—— 调用方不需要、也不许再自己清一遍。
     *
     * @return 本次被释放的相对下标（升序、长度 = 实际释放数；空数组 = 没释放）
     */
    public int[] align(int mother, int count) {
        requireMother(mother);
        if (count < 0) {
            throw new IllegalArgumentException("展开条数必须 >= 0，实际 " + count);
        }
        final int current = this.counts[mother];
        if (count < current) {
            final int[] row = this.perMother[mother];
            final int[] out = new int[current - count];
            int n = 0;
            for (int b = count; b < current; b++) {
                final int rel = b < row.length ? row[b] : -1;
                if (rel >= 0) {
                    row[b] = -1;
                    out[n++] = rel;
                    this.release(rel);
                }
            }
            this.counts[mother] = count;
            this.shrinkTail();
            return n == out.length ? out : Arrays.copyOf(out, n);
        }
        if (count > current) {
            for (int b = current; b < count; b++) {
                this.slotFor(mother, b);
            }
        }
        return EMPTY;
    }

    /**
     * {@link #align(int, int)} 的 void 形态（语义一字不差，只是把"释放了哪些下标"丢掉）。
     *
     * <p>⚠️ 机器侧<b>不许</b>再用它去收缩：那会把「材质对象要跟着清」这条也丢掉
     * （历史上就是这么丢的 —— 见 C5 报告"改前：setCount 收缩只还槽号、不清料"）。
     */
    public void setCount(int mother, int count) {
        this.align(mother, count);
    }

    /** 空数组常量（免每次分配）。 */
    private static final int[] EMPTY = new int[0];

    /**
     * 🔴 <b>按「样板身份键」把母槽 {@code mother} 的展开表对齐到 {@code keys}（C7：重建不丢料）。</b>
     *
     * <h2>为什么必须有它（改前的机制性问题）</h2>
     * {@link #align} / {@link #slotFor} 只认<b>分支号</b>，而分支号是<span>展开列表里的位置</span>。
     * 于是任何一次"内容没变的重建"都只能靠"顺序与条数完全一致"侥幸保住料；
     * 一旦顺序抖动 / 中间某条被剔掉，尾部 {@code align} 就会 {@code release} ⇒ 材质被清 ⇒
     * 实机表现为「AE 已经把料推进来了，下一拍机器说材料输入不足」。
     *
     * <h2>语义（三条，缺一不可）</h2>
     * <ol>
     *   <li><b>身份键还在 ⇒ 槽号与材质都不动</b>（哪怕它换了分支号：只改分配表，不 release）；</li>
     *   <li><b>身份键真的消失了（或被别的样板取代）⇒ 才 release</b>（于是才清材质，
     *       与 {@link #setOnReleased} 的唯一释放点那条链一致）；</li>
     *   <li><b>键必须唯一</b>：同一母槽里重复的键按<b>确定性后缀</b>去重
     *       （{@code key + "\u0001dup" + 分支号}）—— 同样的输入数组必然得到同样的键数组，
     *       所以"没变化的两拍"不会来回改名。</li>
     * </ol>
     *
     * <h2>空键</h2>
     * {@code null} = 这条展开没有可比的身份 ⇒ 退化成<b>位置键</b>（{@code "\u0000pos:" + 分支号}），
     * 即"回到改前的行为"，绝不因为拿不到身份就乱搬槽。
     *
     * @param mother 母槽号
     * @param keys   按<b>新分支顺序</b>给出的身份键（长度 = 本次展开条数；可为 {@code null} 元素）
     * @return {@code rels[branch]} = 该分支的相对下标（长度 = {@code keys.length}）
     */
    public int[] reconcile(int mother, String[] keys) {
        requireMother(mother);
        if (keys == null) {
            keys = EMPTY_KEYS;
        }
        // 0) 确定性去重（同键第 2 次以后带位置后缀）
        final String[] eff = new String[keys.length];
        final java.util.HashSet<String> seen = new java.util.HashSet<>();
        for (int b = 0; b < keys.length; b++) {
            // 🔴 wildfix-8（Bug A）：身份键**必须按母槽隔离** —— 改前这里是裸键，
            //    而 lookupByKey 查的是**全局** keyToRel ⇒ 两条母样板若展开出同名键（同材料同输出），
            //    后 reconcile 的那条会把前一条的 rel **抢走**（下面一句 owner[rel] = mother 直接改写归属），
            //    于是那一格挂在前一个母槽的位置上、却带着后一个母槽的声明类型 = 实机那个
            //    「1132 声明 assembler、其实是导线那条」的形态。
            final String scoped = mother + "\u0002" + (keys[b] == null ? ("\u0000pos:" + b) : keys[b]);
            String k = scoped;
            if (!seen.add(k)) {
                k = k + "\u0001dup" + b;
                seen.add(k);
            }
            eff[b] = k;
        }
        // 1) 先把旧分配表的 (分支 → 下标) 全摘下来（**只动分配表，不动材质**）
        final int current = this.counts[mother];
        final int[] row = this.perMother[mother];
        final int[] old = new int[current];
        for (int b = 0; b < current; b++) {
            old[b] = b < row.length ? row[b] : -1;
        }
        if (row.length > 0) {
            Arrays.fill(row, -1);
        }
        this.counts[mother] = 0;
        // 2) 目标集合里没有的键 ⇒ 这才是"真的消失/被取代"，只有这里允许 release（=清材质）
        for (int i = 0; i < old.length; i++) {
            final int rel = old[i];
            if (rel < 0) {
                continue;
            }
            final String k = this.relKey[rel];
            if (k == null || !seen.contains(k)) {
                this.release(rel);
            }
        }
        // 3) 按新分支顺序重新登记：有键 ⇒ 复用原下标；新键 ⇒ 分配
        final int[] rels = new int[eff.length];
        for (int b = 0; b < eff.length; b++) {
            final String k = eff[b];
            int rel = lookupByKey(k);
            // 🔴 wildfix-8：再加一道保险 —— 就算键表里查到了，只要那一格**已经属于别的母槽**，
            //    也不许复用（宁可多占一格，也绝不让两条母样板共用同一个槽号 = 串类型/串料）。
            if (rel >= 0 && this.owner[rel] != mother) {
                rel = -1;
            }
            if (rel < 0) {
                rel = this.allocate();
                this.bindKey(rel, k);
            }
            if (b >= this.perMother[mother].length) {
                final int[] grown = Arrays.copyOf(this.perMother[mother], Math.max(b + 1, this.perMother[mother].length == 0 ? 4 : this.perMother[mother].length * 2));
                Arrays.fill(grown, this.perMother[mother].length, grown.length, -1);
                this.perMother[mother] = grown;
            }
            this.perMother[mother][b] = rel;
            this.owner[rel] = mother;
            this.branch[rel] = b;
            if (b + 1 > this.counts[mother]) {
                this.counts[mother] = b + 1;
            }
            rels[b] = rel;
        }
        this.shrinkTail();
        return rels;
    }

    private static final String[] EMPTY_KEYS = new String[0];

    /** 键 → 已发放的相对下标（并做一次反向自证：{@code relKey[rel]} 必须还是这个键）。 */
    private int lookupByKey(String key) {
        if (key == null) {
            return -1;
        }
        final Integer rel = this.keyToRel.get(key);
        if (rel == null) {
            return -1;
        }
        final int r = rel.intValue();
        return key.equals(this.relKey[r]) ? r : -1;
    }

    /** 给一个相对下标绑上身份键（成对写两张表）。 */
    private void bindKey(int rel, String key) {
        this.relKey[rel] = key;
        if (key != null) {
            this.keyToRel.put(key, Integer.valueOf(rel));
        }
    }

    /** 相对下标的身份键（诊断/探针用；{@code null} = 没有身份）。 */
    public String keyOfRel(int rel) {
        if (rel < 0 || rel >= this.everHigh) {
            return null;
        }
        return this.relKey[rel];
    }

    /** 全部回收（重建开始时<b>不</b>用 —— 重建是逐母槽 align，跨母槽复用才省下标）。 */
    public void clear() {
        // 逐个 release ⇒ 材质对象也跟着清（不许绕开唯一释放点）
        for (int m = 0; m < this.motherCount; m++) {
            final int[] row = this.perMother[m];
            for (int b = 0; b < this.counts[m] && b < row.length; b++) {
                if (row[b] >= 0) {
                    this.release(row[b]);
                }
            }
        }
        for (int m = 0; m < this.motherCount; m++) {
            this.counts[m] = 0;
            this.perMother[m] = new int[0];
        }
        this.freeTop = 0;
        this.high = 0;
        // ⚠️ everHigh 不归零（单调红线：槽号空间不在运行中变小）
        Arrays.fill(this.owner, -1);
        Arrays.fill(this.branch, -1);
        // 🔴 C7：身份键表与分配表同生共死
        Arrays.fill(this.relKey, null);
        this.keyToRel.clear();
    }

    private int allocate() {
        if (this.freeTop > 0) {
            final int rel = this.free[--this.freeTop];
            this.owner[rel] = -2; // 已发放但还没写归属，由调用方立刻填
            this.branch[rel] = -1;
            return rel;
        }
        final int rel = this.high++;
        if (rel + 1 > this.everHigh) {
            this.everHigh = rel + 1;
        }
        ensureCapacity(rel);
        // 刚越过水位：这两个数组可能还留着上一次（已释放但没被 free 栈接走）的旧值
        this.owner[rel] = -2;
        this.branch[rel] = -1;
        this.relKey[rel] = null;   // C7：新分配的格子绝不能带着上一条展开的身份键
        return rel;
    }

    /**
     * <b>释放一个相对下标的唯一落点。</b>
     *
     * <p>顺序很重要：先把分配表置空，再通知 {@link #onReleased} ——
     * 于是回调里如果再问 {@link #isRegistered(int)} / {@link #motherOf(int)}，读到的<b>已经是"没有"</b>
     * （不会出现"回调把新主人的料清了"这种反向事故）。
     */
    private void release(int rel) {
        this.owner[rel] = -1;
        this.branch[rel] = -1;
        // 🔴 C7：身份键跟着一起清（否则 keyToRel 里会留着一条指向已释放下标的旧记录，
        //    下次 reconcile 会"复用"到一个材质已被清空、甚至已被别的母槽占用的下标）。
        final String oldKey = this.relKey[rel];
        this.relKey[rel] = null;
        if (oldKey != null && this.keyToRel.get(oldKey) != null
                && this.keyToRel.get(oldKey).intValue() == rel) {
            this.keyToRel.remove(oldKey);
        }
        if (this.freeTop >= this.free.length) {
            this.free = Arrays.copyOf(this.free, this.free.length * 2);
        }
        this.free[this.freeTop++] = rel;
        final java.util.function.IntConsumer listener = this.onReleased;
        if (listener != null) {
            listener.accept(rel);
        }
    }

    /**
     * 收尾部：空闲栈顶正好是水位末位 ⇒ 一直弹到不能再降。
     *
     * <p>⚠️ 这里<b>不</b>再调回调：那些下标刚在 {@link #release(int)} 里清过，
     * 弹栈只是"把空洞从栈里去掉"，不是第二次释放。
     */
    private void shrinkTail() {
        while (this.freeTop > 0) {
            final int top = this.free[this.freeTop - 1];
            if (top != this.high - 1) {
                return;
            }
            this.freeTop--;
            this.high--;
        }
    }

    private void ensureCapacity(int rel) {
        if (rel < this.owner.length) {
            return;
        }
        final int want = Math.max(rel + 1, this.owner.length * 2);
        this.owner = Arrays.copyOf(this.owner, want);
        this.branch = Arrays.copyOf(this.branch, want);
        // 🔴 C7：身份键数组必须跟着长（否则 reconcile 里按 rel 写键会越界）
        this.relKey = Arrays.copyOf(this.relKey, want);
    }

    // ---------------------------------------------------------------- 查询

    /**
     * <b>槽号空间的展开段长度</b>（{@code getInternalSlotCount()} 减 {@code EXPAND_OFFSET} 就是它）。
     *
     * <p>🔴 C5：读的是<b>单调水位</b> {@link #everHigh} —— 运行中<b>只增不减</b>。
     * 于是"同一个槽号在被复用后指代另一条展开"这件事只可能发生在
     * {@code [0, allocHigh())} 这段<b>真的被复用</b>的下标上，而 {@code [allocHigh(), slotCount())}
     * 只是一段永远不会被分配的尾巴 —— <b>已发放过的槽号不会因为空间收缩而消失</b>。
     */
    public int slotCount() {
        return this.everHigh;
    }

    /** 当前<b>分配</b>水位（可被尾部收缩降下来；{@code ≤ slotCount()}）。诊断/探针用。 */
    public int allocHigh() {
        return this.high;
    }

    /** 母槽当前展开条数。 */
    public int count(int mother) {
        requireMother(mother);
        return this.counts[mother];
    }

    /**
     * <b>纯读</b>：母槽 {@code mother} 的第 {@code branch} 条展开分到哪个相对下标（未分配 ⇒ -1）。
     *
     * <p>🔴 与 {@link #slotFor(int, int)} 的区别就在这：<b>本方法绝不分配</b>。
     * 所有"查"的路径（反查、诊断、槽号→母槽、材质取用）都必须走它 ——
     * 读一次就顺手把槽号改掉，是"推料按 A 表、判活跃按 B 表"这类事故最隐蔽的来源。
     */
    public int slotOf(int mother, int branch) {
        requireMother(mother);
        if (branch < 0) {
            return -1;
        }
        final int[] row = this.perMother[mother];
        return branch < row.length ? row[branch] : -1;
    }

    /** 相对下标 → 母槽号；未分配 / 越界 ⇒ -1。 */
    public int motherOf(int rel) {
        if (rel < 0 || rel >= this.everHigh) {
            return -1;
        }
        return this.owner[rel];
    }

    /** 相对下标 → 分支号；未分配 / 越界 ⇒ -1。 */
    public int branchOf(int rel) {
        if (rel < 0 || rel >= this.everHigh) {
            return -1;
        }
        return this.branch[rel];
    }

    /**
     * ⚠️ <b>这一格被本分配器发过号、且还没还回来</b>（"发过号"≠"仍登记着一条展开"）。
     *
     * <p>🔴 2026-10-30（C6）改名理由：它原来叫 {@code isAllocated}，与
     * {@link #isRegistered(int)} 只差一层 —— 于是两处各用一个，就出现了
     * "候选集按 {@code isAllocated} 过滤、{@code hasPatternInSlot} 按 {@code isRegistered} 判"
     * 的分裂（两套近义判据 = 两套真相）。现在名字不再近义、职责写死：
     * <ul>
     *   <li><b>任何判定</b>（这一格算不算有样板 / 进不进候选集 / 给不给它造材质对象）⇒
     *       <b>只许用 {@link #isRegistered(int)}</b>；</li>
     *   <li><b>本方法只给诊断用</b>：{@code SLOTMISMATCH} 里区分"纯空洞（从没发过号 / 已还回）"
     *       与"发过号但登记对不上"这两种 {@code isRegistered=false}；
     *       以及 {@link #handedOutCount()} 那个规模读数。</li>
     * </ul>
     * <p>两者关系恒为：{@code isRegistered(rel)} ⇒ {@code isHandedOut(rel)}（反之不成立）。
     */
    public boolean isHandedOut(int rel) {
        return rel >= 0 && rel < this.everHigh && this.owner[rel] >= 0;
    }

    /**
     * <b>规模读数①</b>：{@code [0, slotCount())} 里 {@link #isHandedOut(int)} 为真的下标个数。
     *
     * <p>用途见 {@code StellarSampleAssemblerPartMachine#shanhaiCandidateMismatchProbe()}
     * 的 {@code hashAlloc=}（一行里两个规模一起看，能立刻分出"整体空"与"个别分叉"）。
     */
    public int handedOutCount() {
        int n = 0;
        for (int rel = 0; rel < this.everHigh; rel++) {
            if (this.isHandedOut(rel)) {
                n++;
            }
        }
        return n;
    }

    /**
     * <b>规模读数②</b>：{@code [0, slotCount())} 里 {@link #isRegistered(int)} 为真的下标个数。
     *
     * <p>正常机器上恒等于 {@link #handedOutCount()}（{@code slotFor} 一次就把正查/反查/条数三样写齐）。
     * 两者不等 ⇒ 分配表被写过不一致的条目（这正是要报的那件事）。
     */
    public int registeredCount() {
        int n = 0;
        for (int rel = 0; rel < this.everHigh; rel++) {
            if (this.isRegistered(rel)) {
                n++;
            }
        }
        return n;
    }

    /**
     * 🔴 <b>负对照位（生产恒 false）</b>：打开后 {@link #isRegistered(int)} 一律返回 false
     * —— 等于把"样板登记"这一半故意做假（只认父类那 1080 格，展开段一律不算有样板）。
     *
     * <p>唯一用途：让纯逻辑探针
     * （{@code temp\slotreg\probe\SlotRegProbe.java} 的 C 段）证明
     * <b>它确实能翻红</b>。红不了的探针，它报的绿也不算数。
     * <p>⚠️ 生产代码里<b>一次都不许</b>调本 setter（{@code temp\slotreg\verify.ps1} 的 W 组会盯这一行）。
     */
    public void setNegativeControlParentTableOnly(boolean on) {
        this.negativeControlParentTableOnly = on;
    }

    private boolean negativeControlParentTableOnly;

    /**
     * 🔴 <b>「这个展开槽号真的登记着一条展开」的唯一判据</b>（机器侧 {@code hasPatternInSlot} 就用它）。
     *
     * <p>四条一起判，<b>缺一不可</b>（全部读同一张表 ⇒ 不存在第二条真相）：
     * <ol>
     *   <li>下标已发放（{@link #isHandedOut(int)}）；</li>
     *   <li>反查得出母槽与分支（{@link #motherOf(int)} / {@link #branchOf(int)} 都 ≥ 0）；</li>
     *   <li><b>正查与反查互相对得上</b>：{@code slotOf(mother, branch) == rel}；</li>
     *   <li>该母槽当前的展开条数 &gt; 分支号（这条展开还在表里）。</li>
     * </ol>
     * 第 3 条是这次新增的<b>自证</b>：如果哪天有人往分配表里写了不一致的条目，
     * 这里会当场判 false 而不是"某个消费者说是、另一个说不是"。
     */
    public boolean isRegistered(int rel) {
        if (this.negativeControlParentTableOnly) {
            return false;   // 负对照位（见 setNegativeControlParentTableOnly）
        }
        if (!this.isHandedOut(rel)) {
            return false;
        }
        final int mother = this.owner[rel];
        final int branch = this.branch[rel];
        if (mother < 0 || branch < 0) {
            return false;
        }
        if (this.slotOf(mother, branch) != rel) {
            return false;
        }
        return this.counts[mother] > branch;
    }

    /** 一行式描述（日志/探针用）。 */
    public @NotNull String describe() {
        return "expandAlloc{high=" + this.high + " everHigh=" + this.everHigh
                + " free=" + this.freeTop + " mothers=" + this.motherCount + "}";
    }

    private void requireMother(int mother) {
        if (mother < 0 || mother >= this.motherCount) {
            throw new IllegalArgumentException("母槽号越界：" + mother + "（合法 0.." + (this.motherCount - 1) + "）");
        }
    }
}
