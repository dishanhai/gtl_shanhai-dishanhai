package com.shanhai.add.hotfix;

import java.nio.charset.StandardCharsets;

/**
 * <b>常量替换</b>式的"改方法体"：把 {@link HotfixProbeTarget#stamp()} 返回的那个字符串常量
 * 在 class 文件里原地换掉（长度必须不变）。
 *
 * <h2>为什么不是 ASM</h2>
 * 这是<b>自检</b>用的最小手段，不是将来"热改敌方代码"的成品工具。自检要证明的是
 * "retransform 这条管道能把新方法体送进 JVM 并真的生效"，而不是"我们的字节码编辑器很好用"。
 * 用 ASM 会有两个额外代价：① add 工程的编译期 classpath 里<b>没有</b> {@code org.objectweb.asm}
 * （它只在游戏的运行期库里有，{@code compileOnly} 拿不到 ⇒ 要动 build.gradle）；
 * ② 引入一个第三方依赖会让"自检失败"这件事多一种可能原因（ASM 版本/类加载器）。
 * 而常量替换只用 {@code java.nio} + 我们自己 30 行常量池解析，出错面极小。
 *
 * <h2>为什么"同长度"是硬条件</h2>
 * class 文件的常量池里，{@code CONSTANT_Utf8} 的长度是 {@code u2} 且<b>后面紧跟下一个常量</b>
 * ⇒ 一旦长度变了，所有后续常量的偏移全部平移，方法体里的索引、属性长度、代码偏移……都得重算。
 * 只换<b>同样长度</b>的字节，文件结构与所有下标都不变，改动点只有那 4 个字节，
 * "改错了"也只会得到"字符串不对"而不是"class 文件坏了"。
 *
 * <h2>🔴 为什么要改【所有】命中的常量，而不是只改一个</h2>
 * 这是<b>实测</b>出来的（{@code temp/hotfix/报告.md} §"retransform 的底座会合并常量池"）：
 * 对一个类做过一次"带着 transformer 的 retransform"之后，{@code retransformClasses} 交下来的
 * {@code classfileBuffer} <b>不再是磁盘上那份 class</b> —— 它会变成"原来那份 + 新增常量"的合并版。
 * 同一个类上量到的读数：第一次 {@code len=338 hits=1}（只有 {@code #8=…0000}），
 * 还原之后第二次 {@code len=369 hits=2}（{@code #8=…0000} 与 {@code #18=…A9K7} 同时存在）。
 * ⇒ 如果"命中多份就拒绝"，自检在<b>同一个进程里只能成功跑一次</b>（第二次就会 {@code stamp_appears_2_times}）。
 * ⇒ 所以这里把<b>所有</b>命中都改成同一个新值：无论方法体引用的是哪一个条目，读到都是新值；
 * 而"还原"（不带 transformer 的 retransform）会回到那个合并版底座，方法体重新指向 {@code …0000}，
 * 行为仍然是"原始返回值"。多余的常量条目只是<b>没人引用的死条目</b>，不影响任何行为。
 *
 * <h2>失败即拒绝（返回 {@code null}）</h2>
 * 靶子类里找不到、或 token 不合法、或改动越过 token 区、或"一个字节都没改" —— 一律不动手，
 * 返回一条 {@code reason=...}。这一点很重要：transformer 返回一个"我们没把握的字节数组"
 * 会让 {@code retransformClasses} 抛 {@code UnsupportedOperationException}，
 * 而在活游戏里没人想看到那种异常。
 */
public final class HotfixBytePatch {

    /** 靶子字符串的前缀（与 {@link HotfixProbeTarget} 里那个字面量必须逐字一致）。 */
    public static final String STAMP_PREFIX = "SHANHAI-HOTFIX-STAMP-";

    /** 后缀 token 的固定长度。 */
    public static final int TOKEN_LEN = 4;

    /**
     * 一次最多接受多少个命中条目。
     *
     * <p>16 远大于实测值（重试若干轮后也只是 2~3 个）；设上限是为了"如果一个类里莫名其妙有几百个
     * 同前缀常量，说明我们认错东西了" —— 那种时候应该拒绝，而不是一口气全改。
     */
    public static final int MAX_STAMP_ENTRIES = 16;

    /** 编译进靶子类的原始 token。 */
    public static final String ORIGINAL_TOKEN = "0000";

    /** 原始返回值（{@code stamp()} 应当返回它）。 */
    public static final String ORIGINAL_VALUE = STAMP_PREFIX + ORIGINAL_TOKEN;

    /** 自检时换上的 token（4 个字符，故意不像原始值，避免"没改也算改"）。 */
    public static final String SELFTEST_TOKEN = "A9K7";

    /**
     * class 文件魔数 {@code 0xCAFEBABE}。
     *
     * <p>⚠️ 类型必须是 {@code long}：{@code 0xCAFEBABE} 作为 {@code int} 是<b>负数</b>
     * （-889275714），而 {@link #u4(byte[], int)} 返回的是按无符号算出来的 {@code 3405691582}。
     * 两者直接比较会永远不相等 ⇒ 每一个合法 class 都被判成 {@code not_a_class_file}。
     * 这个坑是离线自检抓出来的（{@code t3.patch_ok} 首轮 pass=false），别改回去。
     */
    private static final long MAGIC = 0xCAFEBABEL;

    private HotfixBytePatch() {
    }

    /** 替换结果（不可变）。 */
    public static final class Result {

        /** 改好的字节；{@code null} = 被拒绝（原因见 {@link #reason}）。 */
        public final byte[] bytes;

        /** {@code ok} 或一条机器可判的拒绝原因。 */
        public final String reason;

        /** 命中并改写的常量池条目数（成功时恒为 1）。 */
        public final int entries;

        Result(byte[] bytes, String reason, int entries) {
            this.bytes = bytes;
            this.reason = reason;
            this.entries = entries;
        }

        public boolean ok() {
            return bytes != null;
        }

        @Override
        public String toString() {
            return "ok=" + ok() + " reason=" + reason + " entries=" + entries;
        }
    }

    /** token 合法性：恰好 {@link #TOKEN_LEN} 个可打印 ASCII（不能有空格，否则日志字段会断）。 */
    public static boolean isValidToken(String token) {
        if (token == null || token.length() != TOKEN_LEN) {
            return false;
        }
        for (int i = 0; i < token.length(); i++) {
            final char c = token.charAt(i);
            if (c <= 0x20 || c > 0x7E) {
                return false;
            }
        }
        return true;
    }

    /** 期望的返回值（{@code token} 合法时）。 */
    public static String expectedValue(String token) {
        return STAMP_PREFIX + token;
    }

    /**
     * 尝试把 class 文件里那个 {@code SHANHAI-HOTFIX-STAMP-xxxx} 常量换成 {@code newToken} 版。
     *
     * @param classBytes 原始 class 字节（transformer 收到的 {@code classfileBuffer}）
     * @param newToken   新后缀（必须 {@link #isValidToken}）
     * @return 成功时 {@code bytes} 已改好、长度与输入<b>完全一致</b>；否则 {@code bytes == null}
     */
    public static Result patchStamp(byte[] classBytes, String newToken) {
        if (classBytes == null || classBytes.length < 16) {
            return new Result(null, "bad_class_bytes", 0);
        }
        if (!isValidToken(newToken)) {
            return new Result(null, "bad_token", 0);
        }
        if (u4(classBytes, 0) != MAGIC) {
            return new Result(null, "not_a_class_file", 0);
        }
        final int constantPoolCount = u2(classBytes, 8);
        if (constantPoolCount < 1) {
            return new Result(null, "bad_constant_pool_count", 0);
        }

        int offset = 10;
        int found = 0;
        final int[] foundAt = new int[MAX_STAMP_ENTRIES];
        // 常量池索引 1..count-1；Long/Double 占两个槽位（i 额外自增一次），这里必须照 JVMS 走
        for (int index = 1; index < constantPoolCount; index++) {
            if (offset + 1 > classBytes.length) {
                return new Result(null, "truncated_constant_pool", 0);
            }
            final int tag = classBytes[offset] & 0xFF;
            switch (tag) {
                case 1: { // CONSTANT_Utf8
                    if (offset + 3 > classBytes.length) {
                        return new Result(null, "truncated_utf8_header", 0);
                    }
                    final int length = u2(classBytes, offset + 1);
                    final int dataAt = offset + 3;
                    if (dataAt + length > classBytes.length) {
                        return new Result(null, "truncated_utf8_data", 0);
                    }
                    if (isStampConstant(classBytes, dataAt, length)) {
                        if (found >= MAX_STAMP_ENTRIES) {
                            return new Result(null, "stamp_entries_over_" + MAX_STAMP_ENTRIES, found);
                        }
                        foundAt[found] = dataAt;
                        found++;
                    }
                    offset = dataAt + length;
                    break;
                }
                case 3: // Integer
                case 4: // Float
                case 9: // Fieldref
                case 10: // Methodref
                case 11: // InterfaceMethodref
                case 12: // NameAndType
                case 17: // Dynamic
                case 18: // InvokeDynamic
                    offset += 5;
                    break;
                case 5: // Long
                case 6: // Double（这两个占两个槽位）
                    offset += 9;
                    index++;
                    break;
                case 7: // Class
                case 8: // String
                case 16: // MethodType
                case 19: // Module
                case 20: // Package
                    offset += 3;
                    break;
                case 15: // MethodHandle
                    offset += 4;
                    break;
                default:
                    return new Result(null, "unknown_cp_tag_" + tag, 0);
            }
            if (offset > classBytes.length) {
                return new Result(null, "constant_pool_overflow", 0);
            }
        }

        if (found == 0) {
            return new Result(null, "stamp_not_found", 0);
        }

        final byte[] patched = classBytes.clone();
        final byte[] token = newToken.getBytes(StandardCharsets.US_ASCII);
        for (int k = 0; k < found; k++) {
            System.arraycopy(token, 0, patched, foundAt[k] + STAMP_PREFIX.length(), TOKEN_LEN);
        }

        // 自证：长度不变 + 【改动的字节全部落在某个 token 那 4 个字节里】。
        // ⚠️ 不能断言"改动数 == found*TOKEN_LEN"：把 A9K7 再改成 Z9Z9 时中间那个 '9' 本来就相同
        // ⇒ 改得少也是对的（首轮离线自检就在这里红过一次）。真正要守的是"别的地方一个字节都没动"。
        if (patched.length != classBytes.length) {
            return new Result(null, "length_changed", found);
        }
        int diff = 0;
        for (int i = 0; i < patched.length; i++) {
            if (patched[i] != classBytes[i]) {
                boolean inRegion = false;
                for (int k = 0; k < found && !inRegion; k++) {
                    final int start = foundAt[k] + STAMP_PREFIX.length();
                    inRegion = i >= start && i < start + TOKEN_LEN;
                }
                if (!inRegion) {
                    return new Result(null, "diff_outside_token_at_" + i, found);
                }
                diff++;
            }
        }
        if (diff == 0) {
            // 目标 token 与【所有】命中的常量都逐字相同 ⇒ 什么都没改。返回"ok + 原字节"会让调用方
            // 误以为改过了，所以这里明说没改（自检永远用不同的 token，不会走到这条）。
            return new Result(null, "token_unchanged", found);
        }
        return new Result(patched, "ok", found);
    }

    /**
     * 这个 {@code CONSTANT_Utf8} 是不是我们的靶子字符串：前缀逐字节相等 + 尾部 {@code TOKEN_LEN}
     * 个字节都是可打印 ASCII（这样"已经被改过一次的字节"也能被识别，便于重复自检）。
     */
    private static boolean isStampConstant(byte[] bytes, int dataAt, int length) {
        final int prefixLen = STAMP_PREFIX.length();
        if (length != prefixLen + TOKEN_LEN) {
            return false;
        }
        for (int i = 0; i < prefixLen; i++) {
            if ((bytes[dataAt + i] & 0xFF) != (STAMP_PREFIX.charAt(i) & 0x7F)) {
                return false;
            }
        }
        for (int i = 0; i < TOKEN_LEN; i++) {
            final int b = bytes[dataAt + prefixLen + i] & 0xFF;
            if (b <= 0x20 || b > 0x7E) {
                return false;
            }
        }
        return true;
    }

    private static int u2(byte[] bytes, int at) {
        return ((bytes[at] & 0xFF) << 8) | (bytes[at + 1] & 0xFF);
    }

    private static long u4(byte[] bytes, int at) {
        return ((long) (bytes[at] & 0xFF) << 24) | ((long) (bytes[at + 1] & 0xFF) << 16)
                | ((long) (bytes[at + 2] & 0xFF) << 8) | (long) (bytes[at + 3] & 0xFF);
    }
}
