package com.dishanhai.gt_shanhai.common.patternmover;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * 「一块样板占多少字节」的<b>唯一算法处</b>（只用 {@code net.minecraft.nbt}，不需要 ItemStack / 注册表
 * ⇒ 裸 JVM 里能实跑，见 {@code temp/pattern-mover/probe}）。
 *
 * <h2>一、为什么要自己算 NBT 字节，而不直接 {@code NbtIo.write(整份)}</h2>
 * 需求写的是「每次加入前用 {@code NbtIo.write(CompoundTag)} 实算序列化字节数」。这句话有两层：
 * <ol>
 *   <li><b>实算</b>，不是估 ≈ 所以<b>单条目</b>的字节数<b>真的</b>用 {@link #writeSize(CompoundTag)}
 *       走一遍 {@code NbtIo.write}（真序列化，一个字节都不猜）；</li>
 *   <li><b>每次加入前</b> ⇒ 必须在提交之前就知道总量。</li>
 * </ol>
 * 但「每加一条就把整份 1000+ 条重新序列化一遍」是 O(n²) 次真写（1130 条 × 每条几百字节 ≈ 上亿字节
 * 的序列化），玩家右键一下会卡好几百毫秒。所以本类把整份的字节数用<b>NBT 格式的算术</b>拼出来：
 * <pre>
 *   整份 = 固定外壳 + Σ(单条目实算字节 - 3)
 * </pre>
 * 这个式子<b>不是近似</b>：{@code NbtIo.write(CompoundTag)} 的布局是逐字节确定的，下面 §二 是逐项推导，
 * 而 {@code HostPatternMoverSelfCheck} 里拿<b>真的 {@code NbtIo.write} 全量写一遍</b>去对账
 * （0/1/2/5 条 × 有/无 fromHost × 含中文的类型串，全部要求<b>逐字节相等</b>）。
 *
 * <h2>二、整份 {@code shanhai:mover} 标签的字节布局（逐项）</h2>
 * <pre>
 *   根头            1 (TAG_Compound) + 2 (空名字长)                       = 3
 *   "patterns" 键   1 (TAG_List) + 2 (名字长) + 8 ("patterns")             = 11
 *   ListTag 体      1 (元素类型) + 4 (条数)                                = 5
 *   每个元素        单条目独立的 NbtIo.write 结果 - 3（列表元素不写「类型+名字」这两个头）
 *   "fromHost" 键   1 (TAG_String) + 2 (名字长) + 8 ("fromHost")            = 11
 *   字符串值        2 (长度前缀) + writeUTF 的字节数
 *   结尾            1 (TAG_End)                                           = 1
 * </pre>
 * ⇒ 无 fromHost：{@code 3+11+5+1 = 20} ＋ Σ；有 fromHost：{@code 20+11+2+L = 33+L} ＋ Σ。
 * <p>⚠️ {@code bytes} 字段<b>不在这份计数里</b>（否则它就自指了）。它记的是「把 {@code bytes} 摘掉之后
 * 这个标签有多大」，写在 {@code HostPatternMoverNbt} 的注释里，读的时候也一律以实算为准、不信任存档值。
 *
 * <h2>三、为什么 {@code writeUTF} 的长度要自己算</h2>
 * {@code NbtIo} 用 {@code DataOutputStream#writeUTF} 写字符串 ⇒ 那是 <b>modified UTF-8</b>：
 * ASCII 1 字节、BMP 2/3 字节、<b>代理对按两个 char 各 3 字节</b>（不是 4）。
 * 拿 {@code String.getBytes(UTF_8).length} 去算会在 emoji 上少 2 字节
 * ⇒ 见 {@link #nbtStringBytes(String)}（自检里拿真的 {@code writeUTF} 对账，含代理对）。
 */
public final class HostPatternMoverBytes {

    /** {@code NbtIo.write(CompoundTag)} 里「根类型字节 + 空名字长」这层头 = 3 B。 */
    public static final int ROOT_HEADER = 3;

    /** 无 {@code fromHost} 时整份标签的固定开销：{@code 3 + 11 + 5 + 1}。 */
    public static final int FIXED_OVERHEAD = 20;

    /** 有 {@code fromHost} 时，它额外贡献的固定开销：{@code 1 + 2 + 8 ("fromHost") + 2 (长度前缀)}。 */
    public static final int FROM_HOST_OVERHEAD = 13;

    private HostPatternMoverBytes() {}

    /**
     * <b>真的把它写一遍</b>，返回字节数（{@code NbtIo.write} + {@code DataOutputStream}）。
     *
     * <p>这是本类唯一的「实算」入口：单条目字节数、自检里的全量对账，全都走它。
     */
    public static int writeSize(CompoundTag tag) {
        if (tag == null) {
            return 0;
        }
        try {
            final ByteArrayOutputStream bos = new ByteArrayOutputStream(256);
            final DataOutputStream dos = new DataOutputStream(bos);
            NbtIo.write(tag, dos);
            dos.flush();
            return bos.size();
        } catch (IOException e) {
            throw new UncheckedIOException("NbtIo.write 失败（内存标签本该不会抛）", e);
        }
    }

    /** 同上，但要的是字节数组（去重键的散列用）。 */
    public static byte[] writeBytes(CompoundTag tag) {
        try {
            final ByteArrayOutputStream bos = new ByteArrayOutputStream(256);
            final DataOutputStream dos = new DataOutputStream(bos);
            NbtIo.write(tag, dos);
            dos.flush();
            return bos.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException("NbtIo.write 失败（内存标签本该不会抛）", e);
        }
    }

    /**
     * 一条独立 {@code NbtIo.write} 出来的复合标签，作为 <b>ListTag 的一个元素</b>占多少字节。
     *
     * <p>{@code = 独立字节数 - 3}：列表元素不写「类型字节 + 名字长」这两个头（见类注释 §二）。
     */
    public static int listElementBytes(int standaloneBytes) {
        return Math.max(0, standaloneBytes - ROOT_HEADER);
    }

    /**
     * {@code DataOutputStream#writeUTF} 会写出的<b>正文字节数</b>（不含 2 字节长度前缀）。
     *
     * <p>modified UTF-8：{@code 1..0x7F} 1 B；其余 BMP（含 {@code \u0000}）2～3 B；
     * <b>代理对按两个 char 各 3 B</b> = 6 B（与标准 UTF-8 的 4 B 不同，这是本方法存在的理由）。
     */
    public static int nbtStringBytes(String s) {
        if (s == null) {
            return 0;
        }
        int n = 0;
        for (int i = 0; i < s.length(); i++) {
            final char c = s.charAt(i);
            if (c >= 1 && c <= 0x7F) {
                n += 1;
            } else if (c <= 0x7FF) {
                n += 2;
            } else {
                n += 3;
            }
        }
        return n;
    }

    /**
     * 整份 {@code shanhai:mover} 标签（<b>不含</b> {@code bytes} 字段）的字节数。
     *
     * @param sumOfElementBytes Σ{@code (单条目独立字节数 - 3)}，由调用方累加
     * @param fromHost          {@code fromHost} 的值（{@code null}/空 ⇒ 这个键不存在）
     */
    public static int totalBytes(int sumOfElementBytes, String fromHost) {
        final int base = FIXED_OVERHEAD + Math.max(0, sumOfElementBytes);
        if (fromHost == null || fromHost.isEmpty()) {
            return base;
        }
        return base + FROM_HOST_OVERHEAD + nbtStringBytes(fromHost);
    }
}
