package com.shanhai.machine.stellar;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * 星律样板总成的<b>槽号空间唯一常量处</b>（阶段五收敛点 —— 父代理要求「K / 展开槽数 / 槽号空间」只在一处）。
 *
 * <h2>一、槽号空间的四段（从 0 开始，连续、不重叠）</h2>
 * <pre>
 *   段 1  普通样板槽     0 .. 1079            = 9 × 6 × 20
 *   段 2  通配符母槽     1080 .. 1129         = 50        （只存"通配符样板物品" + 母槽中间配置，不跑配方）
 *   段 3  通配符展开槽   1130 .. 1329         = 50 × 4 = 200（每条展开一份独立的物料池）
 *        合计 1330
 * </pre>
 *
 * <h2>🔴 二、为什么展开槽从 1130 开始，而不是父代理口径里的 1280</h2>
 * 父代理给的数是「1080 + 200 = 1280」，那是<b>把母槽的 50 个位置让给展开槽</b>的算法。
 * 本实现<b>没有</b>那样做，原因是**同一组数字不能有两种含义**：
 * <ul>
 *   <li>1080..1129 在阶段二/三/四已经被占用为「母槽的对外 API 槽号」——
 *       {@code patternStackOf(1080+i)} / 母槽配置 / 终端合并视图 / 阶段四的歧义角标
 *       <b>全都按这个含义写好了</b>；</li>
 *   <li>如果展开槽也用 1080..1279，那么 {@code getInternalSlot(1080+i)}（配方侧）与
 *       {@code patternStackOf(1080+i)}（界面/配置侧）就指向**两个不同的东西**
 *       —— 这正是阶段三反复强调要避免的「同一个数字两种含义」。</li>
 * </ul>
 * ⇒ 展开槽单独顺延一段（1130..1329），三段互不重叠，代价只是槽号空间从 1280 变成 1330。
 *
 * <h2>🔴 三、改这里会怎样</h2>
 * 改 {@link #BRANCHES}（K）或 {@link #MOTHER_COUNT} ⇒ 槽号空间、InternalSlot 数量、
 * 展开映射、以及**阶段三的证明表与审计断言**（{@code temp\stellar-p1\verify-phase3.ps1}）全部要同步改。
 * 本类里的 {@link #SLOT_SPACE_TOTAL} 就是那个「一处」；机器侧所有槽数都引用它，<b>不许再写字面量</b>。
 *
 * <h2>四、物料怎么分（① 防串配方的核心，纯算术部分）</h2>
 * 母槽 i 的第 j 条展开 ⇒ 槽号 {@code 1130 + i*4 + j}，<b>每个 (i,j) 唯一</b>
 * ⇒ 4 条展开各自有自己的 {@code InternalSlot}（自己的 itemInventory/fluidInventory/催化剂数组），
 * 推料时 {@code InternalSlot.pushPattern(stacks)} 只会写进自己那一格，<b>不可能互相串料</b>。
 * 纯函数 {@link #expandSlot(int, int)} / {@link #motherOf(int)} / {@link #branchOf(int)} 有探针
 * （{@code temp\stellar-p1\probe5\LayoutProbe.java}）验证：单射、不重叠、可逆。
 */
public final class ShanhaiWildcardLayout {

    /** 普通样板槽数（9 × 6 × 20）。 */
    public static final int PATTERN_SLOTS = 1080;

    /** 通配符母槽数（10 × 5 一页）。 */
    public static final int MOTHER_COUNT = 50;

    /** 每个母槽最多展开几条（K）。<b>改它会改槽号空间</b>，见类注释 §三。 */
    public static final int BRANCHES = 4;

    /** 通配符展开槽数 = 50 × K = 200。 */
    public static final int EXPAND_COUNT = MOTHER_COUNT * BRANCHES;

    /** 母槽段起点 = 1080。 */
    public static final int MOTHER_OFFSET = PATTERN_SLOTS;

    /** 展开槽段起点 = 1080 + 50 = 1130。 */
    public static final int EXPAND_OFFSET = MOTHER_OFFSET + MOTHER_COUNT;

    /** 槽号空间总长 = 1130 + 200 = <b>1330</b>。 */
    public static final int SLOT_SPACE_TOTAL = EXPAND_OFFSET + EXPAND_COUNT;

    private ShanhaiWildcardLayout() {}

    /** 槽号是不是普通样板槽。 */
    public static boolean isPatternSlot(int slot) {
        return slot >= 0 && slot < PATTERN_SLOTS;
    }

    /** 槽号是不是通配符母槽。 */
    public static boolean isMotherSlot(int slot) {
        return slot >= MOTHER_OFFSET && slot < EXPAND_OFFSET;
    }

    /** 槽号是不是通配符展开槽。 */
    public static boolean isExpandSlot(int slot) {
        return slot >= EXPAND_OFFSET && slot < SLOT_SPACE_TOTAL;
    }

    /** 母槽号（0..49）→ 对外 API 槽号（1080..1129）。 */
    public static int motherApiSlot(int mother) {
        return MOTHER_OFFSET + mother;
    }

    /**
     * 母槽 i 的第 j 条展开 ⇒ 槽号。
     *
     * @throws IllegalArgumentException i/j 越界（越界是代码 bug，必须响亮失败而不是回落到别的格）
     */
    public static int expandSlot(int mother, int branch) {
        if (mother < 0 || mother >= MOTHER_COUNT) {
            throw new IllegalArgumentException("母槽号越界：" + mother + "（合法 0.." + (MOTHER_COUNT - 1) + "）");
        }
        if (branch < 0 || branch >= BRANCHES) {
            throw new IllegalArgumentException("展开分支越界：" + branch + "（合法 0.." + (BRANCHES - 1) + "）");
        }
        return EXPAND_OFFSET + mother * BRANCHES + branch;
    }

    /** 展开槽号 → 母槽号（0..49）。 */
    public static int motherOf(int expandSlot) {
        if (!isExpandSlot(expandSlot)) {
            throw new IllegalArgumentException("不是展开槽号：" + expandSlot);
        }
        return (expandSlot - EXPAND_OFFSET) / BRANCHES;
    }

    /** 展开槽号 → 分支号（0..K-1）。 */
    public static int branchOf(int expandSlot) {
        if (!isExpandSlot(expandSlot)) {
            throw new IllegalArgumentException("不是展开槽号：" + expandSlot);
        }
        return (expandSlot - EXPAND_OFFSET) % BRANCHES;
    }

    // ---------------------------------------------------------------- 掩码编解码（可被无头探针验证）

    /**
     * {@code BitSet.toLongArray()} → 小写十六进制串（<b>低 nibble 在前</b>：第 n 个 nibble 装 bit[4n..4n+3]，
     * 第 w 个 long 占 16 个 nibble）。末尾连续的 0 去掉（长度不进语义）。
     *
     * <p>⚠️ 这个编码约定必须和 {@link #maskBit(String, int)} 完全对称 —— 它是「角标画错格」这类
     * 静默 bug 的高发点，所以两边都放在本类里、由探针实跑正/负对照。
     */
    public static @NotNull String toHexMask(@NotNull long[] words) {
        if (words.length == 0) {
            return "";
        }
        final char[] digits = "0123456789abcdef".toCharArray();
        final char[] out = new char[words.length * 16];
        int pos = 0;
        for (long word : words) {
            for (int n = 0; n < 16; n++) {
                out[pos++] = digits[(int) ((word >>> (n * 4)) & 0xFL)];
            }
        }
        int end = out.length;
        while (end > 0 && out[end - 1] == '0') {
            end--;
        }
        return new String(out, 0, end);
    }

    /** 从 {@link #toHexMask} 的串里取第 {@code bit} 位（越界/非法字符 ⇒ false）。 */
    public static boolean maskBit(@Nullable String hex, int bit) {
        if (hex == null || bit < 0) {
            return false;
        }
        final int nibble = bit / 4;
        if (nibble >= hex.length()) {
            return false;
        }
        final int value = Character.digit(hex.charAt(nibble), 16);
        return value >= 0 && ((value >> (bit % 4)) & 1) != 0;
    }
    /** 一行式描述（日志/断言用）。 */
    public static @NotNull String describe() {
        return "pattern=0.." + (PATTERN_SLOTS - 1)
                + " mother=" + MOTHER_OFFSET + ".." + (EXPAND_OFFSET - 1)
                + " expand=" + EXPAND_OFFSET + ".." + (SLOT_SPACE_TOTAL - 1)
                + " (K=" + BRANCHES + ", total=" + SLOT_SPACE_TOTAL + ")";
    }
}
