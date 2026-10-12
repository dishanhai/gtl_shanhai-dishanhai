package com.shanhai.common.holo;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Locale;
import java.util.UUID;

/**
 * 山海重构 · 全息联机的<b>线格式</b>（DEF / STATE / PUBLISH 三条载荷的编解码）。
 *
 * <h2>1. 🔴 为什么单独一个类、而且<b>只依赖 java.nio</b></h2>
 * 位姿不用同步（vanilla 已经把玩家的位置与朝向同步了，见 {@code ShanhaiHoloPose} 的类注释）。
 * 联机要发的只有"<b>你那边是怎么设置的</b>"。而那几条载荷的<b>字节数本身就是一条验收判据</b>
 * （用户拍板②：全发 ⇒ 原来是 15 项一次 61 字节；2026-10-07 加第 16 项后是 <b>65</b> 字节）。
 * ⇒ 把它做成<b>不碰 Minecraft、不碰 netty 的纯函数</b>，就能在离线套件里
 * <b>真跑一遍编解码并逐位比对</b>（{@link #selfTest()}），而不是"看着代码数字节"。
 *
 * <p>⚠️ 它是 {@code common} 包：<b>不许</b>引用任何 {@code client} 侧的类（专服也要加载它）。
 * "这个数只有一份"这条判据放在客户端侧的自检里比（{@code ShanhaiHoloRemoteView#selfCheck}），
 * 本类只持有一个 {@link #PARAM_COUNT} 常量 + 一条"算出来的字节数 == 常量"的断言。
 *
 * <h2>2. 线格式（三条载荷，定长 + 每条开头一个 16 字节 UUID）</h2>
 * <pre>
 *   DEF     = 16 + 65  = {@value #DEF_BYTES} 字节   ← "你的环绕调参长什么样"，很少变，进服发一次
 *     [0..15]  UUID   —— 【谁的】状态（收方按它建表；服务端广播时用自己那份权威 UUID 覆盖）
 *     [16]     byte   surroundPreset（0=关 1=弱 2=中 3=强）
 *     [17..80] 16 × float（大端）—— 高级调参 16 项，顺序 = 面板上的顺序（索引 0..15）
 *
 *   STATE   = 16 + 9   = {@value #STATE_BYTES} 字节  ← 开关/档位一变就发
 *     [0..15]  UUID
 *     [16]     byte   flags: bit0 = enabled
 *     [17]     byte   layout（0=ring 1=column 2=row）
 *     [18..21] float  distance（0.8 ~ 8.0 格）
 *     [22]     byte   facing（0=FOLLOW 1=SNAP 2=FIXED）
 *     [23]     byte   surroundPreset（冗余一份，用来"拨档立刻生效"）
 *     [24]     byte   flags2: bit0 = panelOpen（🔴 本轮<b>恒为 0</b>，见 §4）
 *
 *   PUBLISH = DEF + STATE = {@value #PUBLISH_BYTES} 字节  ← C2S："我来了 + 我是这么设的"
 * </pre>
 *
 * <h2>3. 两个都要报的数（不许混）</h2>
 * <ul>
 *   <li>{@link #DEF_PAYLOAD_BYTES} = <b>65</b> —— 1 + {@link #PARAM_COUNT}×4（= 1 + 16×4），
 *       <b>只算"设置本身"</b>；</li>
 *   <li>{@link #DEF_BYTES} = <b>81</b> —— 上线一条消息的真实长度（65 + 16 字节 UUID 头）。</li>
 * </ul>
 * UUID 头是必须的：S2C 的收方要按"这是谁的状态"建表；而 {@code ctx.getSender()} 在
 * {@code PLAY_TO_CLIENT} 那条上是 {@code null}（没有"发送者"这个概念）。
 *
 * <h2>4. 🔴 面板不同步（用户拍板 ①）</h2>
 * 远程只画 5 块菜单板：设置面板 / 命令面板<b>不画</b> —— 否则"你在点哪一格"和
 * <b>命令输入框里的字</b>会显示给周围的人（那是观战/偷看，不是"菜单共享"）。
 * ⇒ {@link #FLAG_PANEL_OPEN} 这个位<b>格式上留着、本轮恒为 0</b>；收方也按 0 处理。
 *
 * <h2>5. 大端</h2>
 * netty 的 {@code ByteBuf} 默认大端，{@code FriendlyByteBuf.writeFloat} 也是大端 ⇒
 * 这里显式 {@link ByteOrder#BIG_ENDIAN}，两边同一个字节序。
 */
public final class ShanhaiHoloRemoteCodec {

    private ShanhaiHoloRemoteCodec() {
    }

    /**
     * 高级调参项数（= {@code ShanhaiHoloSurroundTuning.PARAM_COUNT}，客户端侧有断言守着）。
     *
     * <p>🔴 2026-10-07：<b>15 → 16</b>（生产侧加了第 16 项「覆盖范围」）。
     * 这个数<b>必须</b>跟着走，两个理由：
     * <ol>
     *   <li>{@code ShanhaiHoloRemoteView#selfCheck} 里有活体交叉断言
     *       （{@code RemoteCodec.PARAM_COUNT == SurroundTuning.PARAM_COUNT == Persist.PARAM_KEYS.length}）
     *       —— 不改就是进游戏当场报红；</li>
     *   <li>不改的话 DEF 报文只带 15 项 ⇒ 联机观看者那边第 16 项永远是 0（环带档）。</li>
     * </ol>
     * 连带的数字：{@link #DEF_PAYLOAD_BYTES} 61 → <b>65</b>、{@link #DEF_BYTES} 77 → <b>81</b>、
     * {@link #PUBLISH_BYTES} 102 → <b>106</b>（三个都是算出来的，不是手抄的 ⇒ 只改这一个常量即可）。
     */
    public static final int PARAM_COUNT = 16;

    /** UUID 头长度（字节）。 */
    public static final int UUID_BYTES = 16;

    /** DEF 里"设置本身"那一段的字节数 = 1 + 16×4 = <b>65</b>（原来是 61 = 1 + 15×4）。 */
    public static final int DEF_PAYLOAD_BYTES = 1 + PARAM_COUNT * 4;

    /** STATE 里"设置本身"那一段的字节数 = 9。 */
    public static final int STATE_PAYLOAD_BYTES = 9;

    /** 上线一条 DEF 消息的真实长度：16（UUID）+ 65。 */
    public static final int DEF_BYTES = UUID_BYTES + DEF_PAYLOAD_BYTES;

    /** 上线一条 STATE 消息的真实长度：16 + 9。 */
    public static final int STATE_BYTES = UUID_BYTES + STATE_PAYLOAD_BYTES;

    /** C2S 那条 PUBLISH 的长度：DEF + STATE。 */
    public static final int PUBLISH_BYTES = DEF_BYTES + STATE_BYTES;

    /** S2C 那条 ASK 的长度：只有"请谁报"（16 字节 UUID）。 */
    public static final int ASK_BYTES = UUID_BYTES;

    /** {@link State#enabled} 的位（在 flags 那个字节里）。 */
    public static final int FLAG_ENABLED = 1;

    /** {@link State#panelOpen} 的位（在 flags2 那个字节里）—— 🔴 本轮恒 0，见类注释 §4。 */
    public static final int FLAG_PANEL_OPEN = 1;

    /** 排布序号（与 {@code ShanhaiHoloMenuState.Layout} 的 ordinal 一致：0=ring 1=column 2=row）。 */
    public static final int LAYOUT_RING = 0;
    public static final int LAYOUT_COLUMN = 1;
    public static final int LAYOUT_ROW = 2;

    /** 朝向档序号（与 {@code ShanhaiHoloMenuState.Facing} 的 ordinal 一致：0=FOLLOW 1=SNAP 2=FIXED）。 */
    public static final int FACING_FOLLOW = 0;
    public static final int FACING_SNAP = 1;
    public static final int FACING_FIXED = 2;

    /** 环绕档序号（0=关 1=弱 2=中 3=强）。 */
    public static final int PRESET_COUNT = 4;

    // ================================================================== 载荷

    /** DEF 的解码结果（不可变；{@code params} 是拷贝）。 */
    public record Def(UUID id, int surroundPreset, float[] params) {
    }

    /** STATE 的解码结果（不可变）。 */
    public record State(UUID id, boolean enabled, int layout, float distance, int facing,
                        int surroundPreset, boolean panelOpen) {
    }

    // ================================================================== DEF

    /** 编码 DEF（{@code params} 少于 16 项时缺的补 0；多了截断 —— 两侧都不许抛异常）。 */
    public static byte[] encodeDef(UUID id, int surroundPreset, float[] params) {
        final ByteBuffer bb = ByteBuffer.allocate(DEF_BYTES).order(ByteOrder.BIG_ENDIAN);
        putUuid(bb, id);
        bb.put((byte) surroundPreset);
        for (int i = 0; i < PARAM_COUNT; i++) {
            bb.putFloat(params != null && i < params.length ? params[i] : 0.0f);
        }
        return bb.array();
    }

    /** 解码 DEF；长度不对 ⇒ {@code null}（调用方记一行日志并丢掉，绝不用半个包去画）。 */
    public static Def decodeDef(byte[] raw) {
        if (raw == null || raw.length != DEF_BYTES) {
            return null;
        }
        final ByteBuffer bb = ByteBuffer.wrap(raw).order(ByteOrder.BIG_ENDIAN);
        final UUID id = getUuid(bb);
        final int preset = bb.get() & 0xFF;
        final float[] p = new float[PARAM_COUNT];
        for (int i = 0; i < PARAM_COUNT; i++) {
            p[i] = bb.getFloat();
        }
        return new Def(id, preset, p);
    }

    // ================================================================== STATE

    /** 编码 STATE。 */
    public static byte[] encodeState(UUID id, boolean enabled, int layout, float distance, int facing,
                                     int surroundPreset, boolean panelOpen) {
        final ByteBuffer bb = ByteBuffer.allocate(STATE_BYTES).order(ByteOrder.BIG_ENDIAN);
        putUuid(bb, id);
        bb.put((byte) ((enabled ? FLAG_ENABLED : 0) & 0xFF));
        bb.put((byte) layout);
        bb.putFloat(distance);
        bb.put((byte) facing);
        bb.put((byte) surroundPreset);
        bb.put((byte) ((panelOpen ? FLAG_PANEL_OPEN : 0) & 0xFF));
        return bb.array();
    }

    /** 解码 STATE；长度不对 ⇒ {@code null}。 */
    public static State decodeState(byte[] raw) {
        if (raw == null || raw.length != STATE_BYTES) {
            return null;
        }
        final ByteBuffer bb = ByteBuffer.wrap(raw).order(ByteOrder.BIG_ENDIAN);
        final UUID id = getUuid(bb);
        final boolean enabled = (bb.get() & FLAG_ENABLED) != 0;
        final int layout = bb.get() & 0xFF;
        final float distance = bb.getFloat();
        final int facing = bb.get() & 0xFF;
        final int preset = bb.get() & 0xFF;
        final boolean panelOpen = (bb.get() & FLAG_PANEL_OPEN) != 0;
        return new State(id, enabled, layout, distance, facing, preset, panelOpen);
    }

    // ================================================================== ASK

    /** 编码 ASK（16 字节 UUID）。 */
    public static byte[] encodeAsk(UUID who) {
        final ByteBuffer bb = ByteBuffer.allocate(ASK_BYTES).order(ByteOrder.BIG_ENDIAN);
        putUuid(bb, who);
        return bb.array();
    }

    /** 解码 ASK；长度不对 ⇒ {@code null}。 */
    public static UUID decodeAsk(byte[] raw) {
        if (raw == null || raw.length != ASK_BYTES) {
            return null;
        }
        return getUuid(ByteBuffer.wrap(raw).order(ByteOrder.BIG_ENDIAN));
    }

    // ================================================================== UUID 头

    private static void putUuid(ByteBuffer bb, UUID id) {
        final UUID u = id == null ? new UUID(0L, 0L) : id;
        bb.putLong(u.getMostSignificantBits());
        bb.putLong(u.getLeastSignificantBits());
    }

    private static UUID getUuid(ByteBuffer bb) {
        return new UUID(bb.getLong(), bb.getLong());
    }

    // ================================================================== 机器可判读数

    /** 一行读数（开机日志用）：两组字节数都报出来（见类注释 §3）。 */
    public static String describe() {
        return "def_payload_bytes=" + DEF_PAYLOAD_BYTES
                + " state_payload_bytes=" + STATE_PAYLOAD_BYTES
                + " def_wire_bytes=" + DEF_BYTES
                + " state_wire_bytes=" + STATE_BYTES
                + " publish_wire_bytes=" + PUBLISH_BYTES
                + " ask_wire_bytes=" + ASK_BYTES
                + " params=" + PARAM_COUNT + " preset_count=" + PRESET_COUNT;
    }

    /**
     * <b>离线自检</b>：真跑一遍编解码并<b>逐位</b>比对（{@code Float.floatToRawIntBits}）。
     *
     * <p>含三条<b>负对照</b>（否则"全绿"没有信息量）：
     * <ol>
     *   <li>把编码里某一个参数的字节改掉一位 ⇒ 解码结果<b>必须</b>变得不相等；</li>
     *   <li>把长度截短一个字节 ⇒ {@code decode*} <b>必须</b>返回 {@code null}（不是"读半个包"）；</li>
     *   <li>把 UUID 头改掉一个字节 ⇒ 解出来的 UUID <b>必须</b>跟着变（证明"谁的"这一栏真的在线上）。</li>
     * </ol>
     */
    public static String selfTest() {
        int checks = 0;
        int fails = 0;
        final StringBuilder bad = new StringBuilder();

        checks++;
        // 🔴 2026-10-07：15 项 → 16 项 ⇒ 61→65 / 77→81 / 102→106（STATE 与 ASK 不受影响）。
        //    这一串是【故意写死的】：它就是"线格式的字节数不许悄悄变"的那道闸门 ——
        //    加参数时它当场报红，逼人回来核对（本轮就是这么发现的）。
        if (DEF_PAYLOAD_BYTES != 65 || STATE_PAYLOAD_BYTES != 9
                || DEF_BYTES != 81 || STATE_BYTES != 25 || PUBLISH_BYTES != 106 || ASK_BYTES != 16) {
            fails++;
            bad.append(" bytes(").append(DEF_PAYLOAD_BYTES).append('/').append(STATE_PAYLOAD_BYTES)
                    .append('/').append(DEF_BYTES).append('/').append(STATE_BYTES).append(')');
        }

        final UUID id = UUID.fromString("0f9c1d2e-3a4b-5c6d-7e8f-9012345678ab");

        // 位模式向量：故意塞满"二进制不好看"的 float（0.1f / 负数 / 次正规 / 大值）
        final float[] params = new float[PARAM_COUNT];
        for (int i = 0; i < PARAM_COUNT; i++) {
            params[i] = switch (i % 5) {
                case 0 -> 0.1f * (i + 1);
                case 1 -> -1234.5678f + i;
                case 2 -> Float.intBitsToFloat(0x3F800000 + i * 7919);
                case 3 -> i % 2 == 0 ? Float.MIN_NORMAL : -Float.MIN_NORMAL;
                default -> 1.0f / (i + 3.0f);
            };
        }
        final byte[] def = encodeDef(id, 3, params);
        final Def defBack = decodeDef(def);
        checks++;
        if (defBack == null || !id.equals(defBack.id()) || defBack.surroundPreset() != 3
                || defBack.params().length != PARAM_COUNT) {
            fails++;
            bad.append(" def_null");
        } else {
            for (int i = 0; i < PARAM_COUNT; i++) {
                checks++;
                if (Float.floatToRawIntBits(defBack.params()[i]) != Float.floatToRawIntBits(params[i])) {
                    fails++;
                    bad.append(" def_bit").append(i);
                }
            }
        }

        if (def.length == DEF_BYTES) {
            // ---- 负对照 1：改一位必须被发现 ----
            //   ⚠️ 偏移量【算出来】，不许写死：加上 16 字节 UUID 头之后，
            //      "参数 #1 的第一个字节"是 16 + 1（preset）+ 4×1 = 21。
            //      （第一版这里写死了 20 ⇒ 改到的是参数 #0，负对照去比参数 #1 ⇒ 假红。
            //        这正是"检查器自己的假设错了"那一类，写在这里免得再犯。）
            final int tamperAt = UUID_BYTES + 1 + 4;
            checks++;
            if (tamperAt >= DEF_BYTES) {
                fails++;
                bad.append(" tamper_offset_out_of_range");
            }
            final byte[] tampered = def.clone();
            tampered[tamperAt] ^= 0x01;
            final Def t = decodeDef(tampered);
            checks++;
            if (t == null || Float.floatToRawIntBits(t.params()[1]) == Float.floatToRawIntBits(params[1])) {
                fails++;
                bad.append(" neg_tamper_missed");
            }
            // ---- 负对照 2：截短一个字节必须被拒 ----
            final byte[] shortBuf = new byte[DEF_BYTES - 1];
            System.arraycopy(def, 0, shortBuf, 0, shortBuf.length);
            checks++;
            if (decodeDef(shortBuf) != null) {
                fails++;
                bad.append(" neg_trunc_accepted");
            }
            // ---- 负对照 3：UUID 头改一位，解出来的"谁的"必须跟着变 ----
            final byte[] other = def.clone();
            other[0] ^= 0x7F;
            final Def o = decodeDef(other);
            checks++;
            if (o == null || id.equals(o.id())) {
                fails++;
                bad.append(" neg_uuid_header_ignored");
            }
        }

        final byte[] st = encodeState(id, true, LAYOUT_COLUMN, 3.25f, FACING_FIXED, 2, false);
        final State stBack = decodeState(st);
        checks++;
        if (stBack == null
                || !id.equals(stBack.id())
                || !stBack.enabled()
                || stBack.layout() != LAYOUT_COLUMN
                || Float.floatToRawIntBits(stBack.distance()) != Float.floatToRawIntBits(3.25f)
                || stBack.facing() != FACING_FIXED
                || stBack.surroundPreset() != 2
                || stBack.panelOpen()) {
            fails++;
            bad.append(" state_rt");
        }
        final State off = decodeState(encodeState(id, false, LAYOUT_RING, 0.8f, FACING_FOLLOW, 0, false));
        checks++;
        if (off == null || off.enabled() || off.layout() != LAYOUT_RING) {
            fails++;
            bad.append(" state_off");
        }
        checks++;
        if (decodeState(new byte[STATE_BYTES - 1]) != null) {
            fails++;
            bad.append(" neg_state_trunc");
        }
        // ASK：往返 + 截短必须被拒
        checks++;
        if (!id.equals(decodeAsk(encodeAsk(id)))) {
            fails++;
            bad.append(" ask_rt");
        }
        checks++;
        if (decodeAsk(new byte[ASK_BYTES - 1]) != null) {
            fails++;
            bad.append(" neg_ask_trunc");
        }
        // 🔴 面板那一位：本轮"发得出去但恒为 0"，收方也按 0 处理 —— 这条把口径写死
        checks++;
        if (decodeState(encodeState(id, true, LAYOUT_RING, 3.0f, FACING_FIXED, 2, false)).panelOpen()) {
            fails++;
            bad.append(" panel_flag_should_be_false");
        }

        return String.format(Locale.ROOT,
                "holo_codec_selfcheck ok=%s checks=%d fails=%d%s %s",
                fails == 0, checks, fails, bad, describe());
    }
}
