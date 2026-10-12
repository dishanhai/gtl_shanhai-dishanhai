package com.shanhai.client.holo;

import com.shanhai.ShanhaiMod;
import com.shanhai.common.holo.ShanhaiHoloMenuNetwork;
import com.shanhai.common.holo.ShanhaiHoloRemoteCodec;

import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.Locale;

/**
 * 山海重构 · 联机全息的<b>客户端那一半</b>：两个本地开关 + "我的设置变了就报一次"。
 *
 * <h2>1. 两个开关（用户拍板④）</h2>
 * <pre>
 *   showOthersBoards   显示别人的板子   —— 默认【开】
 *   showOthersSurround 显示别人的星空   —— 默认【开】（P4 才用得上；本轮先把它放在这里）
 * </pre>
 * 🔴 <b>本轮只做"运行副本"，不落 toml</b>（用户点单：P5 不做）。
 * 位置留着：{@code ShanhaiHoloPersist} 的键名段 + {@code ShanhaiClientConfig} 的 {@code [holo_menu]} 节
 * ——那两处属于"另一条线"正在改的文件，本轮一个字都不碰。默认值与"能不能切"都已经就位，
 * 接上去是 P5 那一步的事。
 *
 * <h2>2. 什么时候报（用户拍板②：15 项走 DEF，<b>一人一次</b>）</h2>
 * <table border="1">
 *   <tr><th>时机</th><th>怎么判</th><th>日志</th></tr>
 *   <tr><td>进世界 / 换维度 / 重生</td><td>{@code ClientLevel} 引用变了（{@link #watchLevel}）</td>
 *       <td>{@code holo_mp_level_changed} + 一条 publish</td></tr>
 *   <tr><td>设置变了</td><td>每渲染帧比一次 6 个数（{@link #snapshotEquals}）</td>
 *       <td>{@code holo_mp_publish_sent reason=changed}</td></tr>
 *   <tr><td>服务端点名</td><td>{@link ShanhaiHoloMenuNetwork.ClientSink#republish()}（ASK 消息）</td>
 *       <td>{@code holo_mp_republish reason=asked}</td></tr>
 * </table>
 * 🔴 比对挂在<b>渲染帧</b>上、不挂 tick：20 Hz 的 tick 会错过"拨一下马上拨回来"。
 * 一帧里只做 6 次比较 + 15 个 float 的比对（值没变时几乎不要钱）。
 *
 * <h2>3. 换维度 / 断线为什么必须清表</h2>
 * {@code ClientLevel.players()} 只含<b>当前维度</b>的玩家 ⇒ 换维度那一刻旧玩家全部卸载，
 * 表里留着他们就是"幽灵板子"（在旧维度坐标上画 5 块板）。⇒ {@link ShanhaiHoloRemoteState#clearAll}。
 */
@OnlyIn(Dist.CLIENT)
public final class ShanhaiHoloRemoteView {

    /** 日志前缀（与收方那张表同族）。 */
    public static final String PREFIX = "[SHANHAI-HOLO-MP]";

    // ================================================================== 两个开关

    /** 🔴 用户拍板④：默认值 = <b>开</b>（P5 接 toml 时，那一项的默认值必须引用这两个常量）。 */
    public static final boolean DEFAULT_SHOW_OTHERS_BOARDS = true;
    public static final boolean DEFAULT_SHOW_OTHERS_SURROUND = true;

    private static boolean showOthersBoards = DEFAULT_SHOW_OTHERS_BOARDS;
    private static boolean showOthersSurround = DEFAULT_SHOW_OTHERS_SURROUND;

    /** 铺不铺别人的 5 块板（渲染端唯一判据）。 */
    public static boolean showOthersBoards() {
        return showOthersBoards;
    }

    /** 铺不铺别人的星空环绕（P4 才用；本轮先就位，免得 P4 再改一遍口径）。 */
    public static boolean showOthersSurround() {
        return showOthersSurround;
    }

    public static void setShowOthersBoards(boolean v) {
        if (showOthersBoards != v) {
            showOthersBoards = v;
            ShanhaiMod.LOGGER.info("{} holo_mp_switch show_others_boards={}", PREFIX, v);
        }
        // P5：这里加一行 ShanhaiHoloPersist.saveMenu()（键已经在 Persist 的键名段里留好位置）
    }

    public static void setShowOthersSurround(boolean v) {
        if (showOthersSurround != v) {
            showOthersSurround = v;
            ShanhaiMod.LOGGER.info("{} holo_mp_switch show_others_surround={}", PREFIX, v);
        }
    }

    // ================================================================== 收（ClientSink）

    private static final ShanhaiHoloMenuNetwork.ClientSink SINK =
            new ShanhaiHoloMenuNetwork.ClientSink() {
                @Override
                public void acceptDef(java.util.UUID who, int surroundPreset, float[] params) {
                    ShanhaiHoloRemoteState.acceptDef(who, surroundPreset, params);
                }

                @Override
                public void acceptState(java.util.UUID who, boolean enabled, int layout, float distance,
                                        int facing, int surroundPreset, boolean panelOpen) {
                    ShanhaiHoloRemoteState.acceptState(who, enabled, layout, distance, facing,
                            surroundPreset, panelOpen);
                }

                @Override
                public void republish() {
                    forcePublish = true;
                }
            };

    /**
     * 客户端开机时挂上（由 {@code ShanhaiHoloMenuClient} 在进世界那一次调，幂等）。
     *
     * <p>🔴 这是 {@code common} 那侧"不 import 客户端类"的代价：反向注册。
     * 同一个方法里顺手把两条纯逻辑自检打出来（机器可判读数）。
     */
    public static void install() {
        ShanhaiHoloMenuNetwork.setClientSink(SINK);
        ShanhaiMod.LOGGER.info("{} {}", PREFIX, selfCheck());
        ShanhaiMod.LOGGER.info("{} {}", PREFIX, ShanhaiHoloRemoteCodec.describe());
    }

    // ================================================================== 发（每渲染帧）

    /** 这一帧的"我长什么样"的快照（值一变就发；比对全部是基本类型）。 */
    private static boolean lastEnabled;
    private static int lastLayout = -1;
    private static float lastDistance = Float.NaN;
    private static int lastFacing = -1;
    private static int lastPreset = -1;
    private static final float[] LAST_PARAMS = new float[ShanhaiHoloRemoteCodec.PARAM_COUNT];
    private static boolean haveSnapshot;
    private static boolean forcePublish = true;

    /** 上一次看到的那个世界（换世界 ⇒ 清表 + 重报）。 */
    private static Object lastLevel;

    private static int publishCount;
    private static int levelChanges;

    /**
     * <b>每渲染帧</b>调一次（{@code ShanhaiHoloMenuClient#onRenderLevelStage} 的第一行）。
     * 关着投影的时候<b>也要</b>调 —— "让别人看见我"与"我自己看不看见"是两件事。
     */
    public static void frameTick(Minecraft minecraft) {
        if (minecraft == null || minecraft.player == null || minecraft.level == null) {
            if (lastLevel != null) {
                lastLevel = null;
                ShanhaiHoloRemoteState.clearAll("left_world");
                haveSnapshot = false;
                forcePublish = true;
            }
            return;
        }
        watchLevel(minecraft);
        if (!ShanhaiHoloMenuNetwork.isReady()) {
            return;
        }
        if (!forcePublish && haveSnapshot && snapshotEquals()) {
            return;                                      // 稳态：6 个数比较，什么都不发
        }
        publish(minecraft.player.getUUID());
    }

    private static void watchLevel(Minecraft minecraft) {
        final Object level = minecraft.level;
        if (level != lastLevel) {
            final boolean first = lastLevel == null;
            lastLevel = level;
            levelChanges++;
            ShanhaiHoloRemoteState.clearAll(first ? "joined_world" : "dimension");
            haveSnapshot = false;
            forcePublish = true;
            ShanhaiMod.LOGGER.info("{} holo_mp_level_changed first={} changes={} tracked={}",
                    PREFIX, first, levelChanges, ShanhaiHoloRemoteState.tracked());
        }
    }

    private static boolean snapshotEquals() {
        if (ShanhaiHoloMenuState.enabled() != lastEnabled
                || ShanhaiHoloMenuState.layout().ordinal() != lastLayout
                || Float.compare(ShanhaiHoloMenuState.distance(), lastDistance) != 0
                || ShanhaiHoloMenuState.facing().ordinal() != lastFacing
                || ShanhaiHoloSurround.preset() != lastPreset) {
            return false;
        }
        for (int i = 0; i < LAST_PARAMS.length; i++) {
            if (Float.compare(ShanhaiHoloSurround.param(i), LAST_PARAMS[i]) != 0) {
                return false;
            }
        }
        return true;
    }

    private static void publish(java.util.UUID self) {
        final int preset = ShanhaiHoloSurround.preset();
        final float[] params = new float[ShanhaiHoloRemoteCodec.PARAM_COUNT];
        for (int i = 0; i < params.length; i++) {
            params[i] = ShanhaiHoloSurround.param(i);
        }
        // 🔴 面板【不同步】（用户拍板①）：panelOpen 恒 false —— 收方就算收到也只当 0。
        final boolean panelOpen = false;
        ShanhaiHoloMenuNetwork.publishHolo(self,
                ShanhaiHoloMenuState.enabled(),
                ShanhaiHoloMenuState.layout().ordinal(),
                ShanhaiHoloMenuState.distance(),
                ShanhaiHoloMenuState.facing().ordinal(),
                preset, params, panelOpen);

        lastEnabled = ShanhaiHoloMenuState.enabled();
        lastLayout = ShanhaiHoloMenuState.layout().ordinal();
        lastDistance = ShanhaiHoloMenuState.distance();
        lastFacing = ShanhaiHoloMenuState.facing().ordinal();
        lastPreset = preset;
        System.arraycopy(params, 0, LAST_PARAMS, 0, params.length);
        haveSnapshot = true;
        forcePublish = false;
        publishCount++;
        ShanhaiMod.LOGGER.info("{} holo_mp_publish_state enabled={} layout={} distance={} facing={} "
                        + "preset={} params={} count={} extra_param_bytes_per_publish={}",
                PREFIX, lastEnabled, lastLayout, lastDistance, lastFacing, preset, params.length,
                publishCount, ShanhaiHoloRemoteCodec.DEF_PAYLOAD_BYTES);
    }

    // ================================================================== 读数与自检

    /** 一行机器可判读数。 */
    public static String describe() {
        return "show_others_boards=" + showOthersBoards
                + " show_others_surround=" + showOthersSurround
                + " publishes=" + publishCount + " level_changes=" + levelChanges
                + " tracked=" + ShanhaiHoloRemoteState.tracked()
                + " " + ShanhaiHoloRemoteState.describe();
    }

    /**
     * <b>纯逻辑自检</b>（不碰游戏状态）：把"15 项这个数只有一份"这条铁律钉住。
     *
     * <p>判据三条：
     * <ol>
     *   <li>{@link ShanhaiHoloRemoteCodec#PARAM_COUNT}（线格式那一份）<b>必须等于</b>
     *       {@code ShanhaiHoloSurroundTuning.PARAM_COUNT}（生产那一份）；</li>
     *   <li>也必须等于 {@code ShanhaiHoloPersist.PARAM_KEYS.length}（落盘那一份）——
     *       三处一旦漂，"别人看到的星空"就会少一项或者错位；</li>
     *   <li>收方那张表的默认排布/朝向序号必须落在线格式的合法区间里（0..2）。</li>
     * </ol>
     * 失败会打 {@code PASS=false}（闸门与日志都认这一串）。
     */
    public static String selfCheck() {
        int checks = 0;
        int fails = 0;
        final StringBuilder bad = new StringBuilder();

        checks++;
        if (ShanhaiHoloRemoteCodec.PARAM_COUNT != ShanhaiHoloSurroundTuning.PARAM_COUNT) {
            fails++;
            bad.append(" codec_vs_tuning(").append(ShanhaiHoloRemoteCodec.PARAM_COUNT)
                    .append("!=").append(ShanhaiHoloSurroundTuning.PARAM_COUNT).append(')');
        }
        checks++;
        if (ShanhaiHoloRemoteCodec.PARAM_COUNT != ShanhaiHoloPersist.PARAM_KEYS.length) {
            fails++;
            bad.append(" codec_vs_persist(").append(ShanhaiHoloRemoteCodec.PARAM_COUNT)
                    .append("!=").append(ShanhaiHoloPersist.PARAM_KEYS.length).append(')');
        }
        checks++;
        if (ShanhaiHoloRemoteCodec.LAYOUT_RING < 0 || ShanhaiHoloRemoteCodec.LAYOUT_ROW > 2
                || ShanhaiHoloRemoteCodec.FACING_FIXED > 2) {
            fails++;
            bad.append(" enum_range");
        }

        return String.format(Locale.ROOT,
                "holo_mp_selfcheck %d/%d PASS=%s%s（判据：线格式那 %d 项 == 生产那 %d 项 == 落盘那 %d 项；"
                        + "两个开关默认 %s/%s；本轮不落 toml）",
                checks - fails, checks, fails == 0, bad,
                ShanhaiHoloRemoteCodec.PARAM_COUNT,
                ShanhaiHoloSurroundTuning.PARAM_COUNT,
                ShanhaiHoloPersist.PARAM_KEYS.length,
                DEFAULT_SHOW_OTHERS_BOARDS, DEFAULT_SHOW_OTHERS_SURROUND);
    }
}
