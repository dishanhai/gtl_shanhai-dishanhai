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

    /** LIFO 空闲栈（存相对下标）。 */
    private int[] free = new int[64];

    private int freeTop;

    /** 水位 = 已被发放过的相对下标个数（合法相对下标 = {@code 0..high-1}）。 */
    private int high;

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
     */
    public void setCount(int mother, int count) {
        requireMother(mother);
        if (count < 0) {
            throw new IllegalArgumentException("展开条数必须 >= 0，实际 " + count);
        }
        final int current = this.counts[mother];
        if (count < current) {
            final int[] row = this.perMother[mother];
            for (int b = count; b < current; b++) {
                final int rel = b < row.length ? row[b] : -1;
                if (rel >= 0) {
                    row[b] = -1;
                    this.release(rel);
                }
            }
            this.counts[mother] = count;
            this.shrinkTail();
            return;
        }
        if (count > current) {
            for (int b = current; b < count; b++) {
                this.slotFor(mother, b);
            }
        }
    }

    /** 全部回收（重建开始时<b>不</b>用 —— 重建是逐母槽 setCount，跨母槽复用才省下标）。 */
    public void clear() {
        for (int m = 0; m < this.motherCount; m++) {
            this.counts[m] = 0;
            this.perMother[m] = new int[0];
        }
        this.freeTop = 0;
        this.high = 0;
        Arrays.fill(this.owner, -1);
        Arrays.fill(this.branch, -1);
    }

    private int allocate() {
        if (this.freeTop > 0) {
            final int rel = this.free[--this.freeTop];
            this.owner[rel] = -2; // 已发放但还没写归属，由调用方立刻填
            return rel;
        }
        final int rel = this.high++;
        ensureCapacity(rel);
        return rel;
    }

    private void release(int rel) {
        this.owner[rel] = -1;
        this.branch[rel] = -1;
        if (this.freeTop >= this.free.length) {
            this.free = Arrays.copyOf(this.free, this.free.length * 2);
        }
        this.free[this.freeTop++] = rel;
    }

    /** 收尾部：空闲栈顶正好是水位末位 ⇒ 一直弹到不能再降。 */
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
    }

    // ---------------------------------------------------------------- 查询

    /** 已发放的最大相对下标 + 1（= {@code getInternalSlotCount()} 的展开段长度）。 */
    public int slotCount() {
        return this.high;
    }

    /** 母槽当前展开条数。 */
    public int count(int mother) {
        requireMother(mother);
        return this.counts[mother];
    }

    /** 相对下标 → 母槽号；未分配 / 越界 ⇒ -1。 */
    public int motherOf(int rel) {
        if (rel < 0 || rel >= this.high) {
            return -1;
        }
        return this.owner[rel];
    }

    /** 相对下标 → 分支号；未分配 / 越界 ⇒ -1。 */
    public int branchOf(int rel) {
        if (rel < 0 || rel >= this.high) {
            return -1;
        }
        return this.branch[rel];
    }

    /** 相对下标是否已分配给某条展开。 */
    public boolean isAllocated(int rel) {
        return rel >= 0 && rel < this.high && this.owner[rel] >= 0;
    }

    /** 一行式描述（日志/探针用）。 */
    public @NotNull String describe() {
        return "expandAlloc{high=" + this.high + " free=" + this.freeTop + " mothers=" + this.motherCount + "}";
    }

    private void requireMother(int mother) {
        if (mother < 0 || mother >= this.motherCount) {
            throw new IllegalArgumentException("母槽号越界：" + mother + "（合法 0.." + (this.motherCount - 1) + "）");
        }
    }
}
