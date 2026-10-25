package com.shanhai.machine.stellar;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * 星律样板总成的<b>槽号空间"固定前缀"唯一常量处</b>。
 *
 * <h2>🔴 2026-10-27（C3）：这里<b>不再有 K</b>，也没有"总长"常量</h2>
 * 改前是 {@code K = 128} + {@code EXPAND_COUNT = 6400} + {@code SLOT_SPACE_TOTAL = 7530}，
 * 展开槽号用 {@code 1130 + mother*K + branch} 算出来。用户口径是「原版无上限」、
 * 「槽号按实际展开条数动态分配」⇒ 那一整套都删了，改成：
 * <pre>
 *   段 1  普通样板槽   0 .. 1079              （固定，父类容器真身）
 *   段 2  通配符母槽   1080 .. 1129           （固定，50 格）
 *   段 3  展开槽       1130 .. 1130+n-1       （n = 分配器水位 = 实际展开出的条数）
 *   ⇒ 总长 = {@code EXPAND_OFFSET + allocator.slotCount()}，由
 *      {@code StellarSampleAssemblerPartMachine#getInternalSlotCount()} 现算
 * </pre>
 * <b>为什么固定段仍然写死</b>：1080 / 50 这两段是 AE 终端库存、GUI 槽号、NBT 键的<b>同一个下标空间</b>
 * （见类注释 §七的越界论证）—— 它们一动，存档里所有槽号都会失效。
 * <p>动态那一段的分配/回收/收缩策略、以及"AE 样板身份会不会变"，写在
 * {@link ShanhaiWildcardSlotAllocator} 的类注释里（那里是唯一实现）。
 *
 * <h2>掩码编解码（仍在本类）</h2>
 * {@link #toHexMask} / {@link #maskBit} 是「角标画错格」这类静默 bug 的高发点，
 * 所以两边放在一起、由探针实跑正/负对照。
 */
public final class ShanhaiWildcardLayout {

    /** 普通样板槽数（9 × 6 × 20）。 */
    public static final int PATTERN_SLOTS = 1080;

    /** 通配符母槽数（10 × 5 一页）。 */
    public static final int MOTHER_COUNT = 50;

    /** 母槽段起点 = 1080。 */
    public static final int MOTHER_OFFSET = PATTERN_SLOTS;

    /** 展开槽段起点 = 1080 + 50 = 1130。 */
    public static final int EXPAND_OFFSET = MOTHER_OFFSET + MOTHER_COUNT;

    /**
     * 「固定存在的那一段」的右开边界 = 1130（普通 1080 + 母槽 50）。
     *
     * <p>展开段长度是<b>动态</b>的 ⇒ 遍历时前半段用本常量、后半段用
     * {@code getInternalSlotCount() - EXPAND_OFFSET}（候选集见机器侧 {@code shanhaiCandidateSlots()}）。
     */
    public static final int MOTHER_END = EXPAND_OFFSET;

    private ShanhaiWildcardLayout() {}

    /** 槽号是不是普通样板槽。 */
    public static boolean isPatternSlot(int slot) {
        return slot >= 0 && slot < PATTERN_SLOTS;
    }

    /** 槽号是不是通配符母槽（{@code 1080..1129}）。 */
    public static boolean isMotherSlot(int slot) {
        return slot >= MOTHER_OFFSET && slot < EXPAND_OFFSET;
    }

    /** 母槽号（0..49）→ 对外 API 槽号（1080..1129）。 */
    public static int motherApiSlot(int mother) {
        return MOTHER_OFFSET + mother;
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
}
