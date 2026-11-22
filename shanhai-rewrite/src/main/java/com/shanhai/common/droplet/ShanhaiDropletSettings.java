package com.shanhai.common.droplet;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;

/**
 * 强相互作用水滴的<b>「按拥有者统一」设置</b>＋<b>三条可点击操作提示</b>（规格 1 / 2 / 11）。
 *
 * <h2>一、为什么存在拥有者的 {@code getPersistentData()} 里</h2>
 * 规格 11：模式 1/2/3 与爆炸开关<b>存在拥有者玩家身上</b>，该玩家名下<b>所有</b>水滴<b>实时</b>读同一份设置。
 * {@code Entity#getPersistentData()}（Forge）返回的 {@link CompoundTag} 会随玩家存档一起写盘
 * ⇒ 读档不丢；水滴每 tick 现读一次 ⇒ 改设置立刻对所有在场水滴生效（无需广播、无需重启）。
 *
 * <h2>二、默认值</h2>
 * {@link #DEFAULT_MODE} = 1（攻击除拥有者以外的所有生物）、{@link #DEFAULT_EXPLOSION} = {@code false}（关）。
 * 读的时候<b>不写回</b>：NBT 里没有那个键 ≠ 需要立刻落盘；{@link #mode(Player)} 对越界/缺失值一律回落到默认档
 * （坏存档不会让水滴进入一个不存在的模式）。
 *
 * <h2>三、三条提示</h2>
 * 放置后（以及每点一次切换）由 {@link #sendControlPanel(ServerPlayer)} 发出三条
 * {@code ClickEvent.Action.RUN_COMMAND} 可点击指令：
 * ① 切换攻击模式 ② 切换爆炸地形破坏 ③ 收回水滴。
 * 文案全部走语言键（{@code assets/shanhai/lang/{zh_cn,en_us}.json}），本类不硬编码任何中文。
 */
public final class ShanhaiDropletSettings {

    // ------------------------------------------------------------------ 攻击模式

    /** 模式 1：攻击<b>除拥有者以外的所有生物</b>（含其他玩家）。 */
    public static final int MODE_ALL = 1;

    /** 模式 2：只攻击原版 {@link net.minecraft.world.entity.monster.Enemy}（敌对生物）。 */
    public static final int MODE_ENEMY = 2;

    /** 模式 3：<b>不攻击</b>（只跟随拥有者）。 */
    public static final int MODE_NONE = 3;

    /** 合法区间（含两端）。 */
    public static final int MODE_MIN = MODE_ALL;

    /** 合法区间（含两端）。 */
    public static final int MODE_MAX = MODE_NONE;

    /** 规格 2：默认 1 = 攻击所有生物。 */
    public static final int DEFAULT_MODE = MODE_ALL;

    /** 规格 7：爆炸地形破坏默认<b>关</b>。 */
    public static final boolean DEFAULT_EXPLOSION = false;

    // ------------------------------------------------------------------ 跟随距离 / 最大速度（2026-11-24）

    /**
     * 跟随距离默认值（格）—— <b>2.5</b>。
     *
     * <p>用户原话：「<b>水滴距离玩家5格有点太远了，改成可以让玩家用指令设置的，默认2.5格</b>」。
     * 老的固定值来自"左 3 / 上 4"（3²+4²=25 ⇒ 距眼睛 5 格）；现在<b>方向比例仍是 3:4（左上）</b>，
     * 只按距离缩放：{@code left = 0.6·d}、{@code up = 0.8·d}。
     */
    public static final double DEFAULT_FOLLOW_DISTANCE = 2.5D;

    /** 跟随距离下限（格）。 */
    public static final double FOLLOW_DISTANCE_MIN = 1.0D;

    /** 跟随距离上限（格）。 */
    public static final double FOLLOW_DISTANCE_MAX = 10.0D;

    /** 跟随距离的小数位（用户要求"留 2 位小数"）。 */
    public static final int FOLLOW_DISTANCE_DECIMALS = 2;

    /** 方向比例：左 0.6、上 0.8（0.6²+0.8²=1 ⇒ 偏移向量的长度恰好 = 距离本身）。 */
    public static final double FOLLOW_RATIO_LEFT = 0.6D;

    /** 方向比例：上 0.8（见上一条）。 */
    public static final double FOLLOW_RATIO_UP = 0.8D;

    /** 最大飞行速度默认值（格/秒）—— <b>40</b>（= 老实现 20 格/秒的<b>两倍</b>）。 */
    public static final double DEFAULT_MAX_SPEED_PER_SEC = 40.0D;

    /** 最大飞行速度下限（格/秒）。 */
    public static final double MAX_SPEED_MIN = 10.0D;

    /** 最大飞行速度上限（格/秒）。 */
    public static final double MAX_SPEED_MAX = 200.0D;

    /** 每秒多少 tick（{@code 20}）：格/秒 ÷ 20 = 格/tick。 */
    public static final double TICKS_PER_SECOND = 20.0D;

    // ------------------------------------------------------------------ NBT 键

    /** 拥有者玩家 persistentData 里的攻击模式键。 */
    public static final String TAG_MODE = "shanhai_droplet_mode";

    /** 拥有者玩家 persistentData 里的爆炸开关键。 */
    public static final String TAG_EXPLOSION = "shanhai_droplet_explosion";

    /** 拥有者玩家 persistentData 里的跟随距离键（格）。 */
    public static final String TAG_FOLLOW_DISTANCE = "shanhai_droplet_distance";

    /** 拥有者玩家 persistentData 里的最大速度键（格/秒）。 */
    public static final String TAG_MAX_SPEED = "shanhai_droplet_speed";

    /** 拥有者玩家 persistentData 里的自动驾驶开关键（驾驶功能，默认关）。 */
    public static final String TAG_AUTOPILOT = "shanhai_droplet_autopilot";

    /** 拥有者玩家 persistentData 里的瞄准辅助开关键（驾驶功能，默认开）。 */
    public static final String TAG_ASSIST = "shanhai_droplet_assist";

    /** 拥有者玩家 persistentData 里的瞄准辅助判定角度键（度，默认 5）。 */
    public static final String TAG_ASSIST_ANGLE = "shanhai_droplet_assist_angle";

    // ------------------------------------------------------------------ 命令串（ClickEvent）

    /** 第 1 条：切换攻击模式（本人执行，作用于本人的设置）。 */
    public static final String COMMAND_CYCLE_MODE = "/shanhai droplet mode";

    /** 第 2 条：切换爆炸地形破坏。 */
    public static final String COMMAND_TOGGLE_EXPLOSION = "/shanhai droplet explode";

    /** 第 3 条：收回本人名下所有水滴。 */
    public static final String COMMAND_RECALL = "/shanhai droplet recall";

    /** 第 4 条点击时执行：把自动驾驶切到"开"（点击时按当前值二选一，所以点一下就是切换）。 */
    public static final String COMMAND_AUTOPILOT_ON = "/shanhai droplet autopilot on";

    /** 第 4 条点击时执行：把自动驾驶切到"关"。 */
    public static final String COMMAND_AUTOPILOT_OFF = "/shanhai droplet autopilot off";

    /** 第 5 条点击时执行：把辅助瞄准切到"开"。 */
    public static final String COMMAND_ASSIST_ON = "/shanhai droplet assist on";

    /** 第 5 条点击时执行：把辅助瞄准切到"关"。 */
    public static final String COMMAND_ASSIST_OFF = "/shanhai droplet assist off";

    // ------------------------------------------------------------------ 语言键

    public static final String KEY_LINE_MODE = "droplet.shanhai.line.mode";
    public static final String KEY_LINE_MODE_HOVER = "droplet.shanhai.line.mode.hover";
    public static final String KEY_LINE_EXPLOSION = "droplet.shanhai.line.explosion";
    public static final String KEY_LINE_EXPLOSION_HOVER = "droplet.shanhai.line.explosion.hover";
    public static final String KEY_LINE_RECALL = "droplet.shanhai.line.recall";
    public static final String KEY_LINE_RECALL_HOVER = "droplet.shanhai.line.recall.hover";

    public static final String KEY_MODE_ALL = "droplet.shanhai.mode.all";
    public static final String KEY_MODE_ENEMY = "droplet.shanhai.mode.enemy";
    public static final String KEY_MODE_NONE = "droplet.shanhai.mode.none";

    public static final String KEY_EXPLOSION_ON = "droplet.shanhai.explosion.on";
    public static final String KEY_EXPLOSION_OFF = "droplet.shanhai.explosion.off";

    // 第 4/5 条（驾驶，2026-11-25）：语言键 + 中文兜底。
    // 🔴 为什么用 translatableWithFallback：本轮仍不许改 resources/assets/shanhai/lang/*.json，
    //    直接用 translatable(...) 会显示裸键名。下一轮把那几行 JSON 粘进语言文件即可无缝切换。
    public static final String KEY_LINE_AUTOPILOT = "droplet.shanhai.line.autopilot";
    public static final String KEY_LINE_AUTOPILOT_HOVER = "droplet.shanhai.line.autopilot.hover";
    public static final String KEY_LINE_ASSIST = "droplet.shanhai.line.assist";
    public static final String KEY_LINE_ASSIST_HOVER = "droplet.shanhai.line.assist.hover";

    /** 第 4 条正文兜底（与 1/2 条同款排版：§b编号：§f标题§7（当前：§e值§7））。 */
    private static final String FALLBACK_LINE_AUTOPILOT = "§b4：§f自动驾驶§7（当前：§e%s§7）";

    private static final String FALLBACK_LINE_AUTOPILOT_HOVER =
            "§7点击切换：§f开 §7/ §f关\n"
            + "§8开 = 你不用按键，水滴自己追当前目标并攻击；没有目标就原地静止（Shift 仍可下车）\n"
            + "§8关 = W 加速 / S 减速，行驶方向跟准星";

    /** 第 5 条正文兜底；两个 %s 依次是"开/关"与"判定角度"。 */
    private static final String FALLBACK_LINE_ASSIST = "§b5：§f辅助瞄准§7（当前：§e%s§7，§e%s§7 度）";

    private static final String FALLBACK_LINE_ASSIST_HOVER =
            "§7点击切换：§f开 §7/ §f关\n"
            + "§8开 = 准星与目标夹角小于这个度数时，行驶方向自动修正为目标方向（不改你的视角）\n"
            + "§8关 = 行驶方向永远等于视角朝向\n"
            + "§8改角度：§f/shanhai droplet assist angle <1~45>";

    // ------------------------------------------------------------------ 读写设置

    /**
     * 读攻击模式。缺失 / 越界 ⇒ {@link #DEFAULT_MODE}（<b>不</b>顺手写回 NBT）。
     *
     * @param player 拥有者玩家
     */
    public static int mode(Player player) {
        final CompoundTag data = player.getPersistentData();
        final int raw = data.getInt(TAG_MODE);
        return raw >= MODE_MIN && raw <= MODE_MAX ? raw : DEFAULT_MODE;
    }

    /** 写攻击模式（越界一律夹进 1..3；写进拥有者的 persistentData，随存档走）。 */
    public static void setMode(Player player, int mode) {
        player.getPersistentData().putInt(TAG_MODE, clampMode(mode));
    }

    /** 夹到 1..3。 */
    public static int clampMode(int mode) {
        return Math.max(MODE_MIN, Math.min(MODE_MAX, mode));
    }

    /** 循环切换：1 → 2 → 3 → 1（规格 1 的「切换」语义）。 */
    public static int nextMode(int mode) {
        final int current = clampMode(mode);
        return current >= MODE_MAX ? MODE_MIN : current + 1;
    }

    /**
     * 读爆炸地形破坏开关。缺失 ⇒ {@link #DEFAULT_EXPLOSION}。
     *
     * @param player 拥有者玩家
     */
    public static boolean explosion(Player player) {
        final CompoundTag data = player.getPersistentData();
        return data.contains(TAG_EXPLOSION) ? data.getBoolean(TAG_EXPLOSION) : DEFAULT_EXPLOSION;
    }

    /** 写爆炸开关。 */
    public static void setExplosion(Player player, boolean enabled) {
        player.getPersistentData().putBoolean(TAG_EXPLOSION, enabled);
    }

    /**
     * 切换爆炸开关。
     *
     * @return 切换<b>之后</b>的值
     */
    public static boolean toggleExplosion(Player player) {
        final boolean next = !explosion(player);
        setExplosion(player, next);
        return next;
    }

    // ------------------------------------------------------------------ 跟随距离（格）

    /**
     * 读跟随距离。缺失 / 越界 ⇒ {@link #DEFAULT_FOLLOW_DISTANCE}（<b>不</b>顺手写回 NBT）。
     *
     * @param player 拥有者玩家
     */
    public static double followDistance(Player player) {
        final CompoundTag data = player.getPersistentData();
        if (!data.contains(TAG_FOLLOW_DISTANCE)) {
            return DEFAULT_FOLLOW_DISTANCE;
        }
        return clampFollowDistance(data.getDouble(TAG_FOLLOW_DISTANCE));
    }

    /** 写跟随距离（夹进 1.0~10.0 并四舍五入到 2 位小数；随玩家存档走）。 */
    public static void setFollowDistance(Player player, double distance) {
        player.getPersistentData().putDouble(TAG_FOLLOW_DISTANCE, clampFollowDistance(distance));
    }

    /** 夹到 [{@link #FOLLOW_DISTANCE_MIN}, {@link #FOLLOW_DISTANCE_MAX}] 并保留 2 位小数。 */
    public static double clampFollowDistance(double distance) {
        if (Double.isNaN(distance)) {
            return DEFAULT_FOLLOW_DISTANCE;
        }
        final double clamped = Math.max(FOLLOW_DISTANCE_MIN, Math.min(FOLLOW_DISTANCE_MAX, distance));
        final double scale = 100.0D;      // 10^FOLLOW_DISTANCE_DECIMALS
        return Math.round(clamped * scale) / scale;
    }

    // ------------------------------------------------------------------ 最大速度（格/秒）

    /**
     * 读最大飞行速度（格/秒）。缺失 / 越界 ⇒ {@link #DEFAULT_MAX_SPEED_PER_SEC}。
     *
     * @param player 拥有者玩家
     */
    public static double maxSpeedPerSec(Player player) {
        final CompoundTag data = player.getPersistentData();
        if (!data.contains(TAG_MAX_SPEED)) {
            return DEFAULT_MAX_SPEED_PER_SEC;
        }
        return clampMaxSpeedPerSec(data.getDouble(TAG_MAX_SPEED));
    }

    /** 写最大飞行速度（夹进 10~200；随玩家存档走）。 */
    public static void setMaxSpeedPerSec(Player player, double speedPerSec) {
        player.getPersistentData().putDouble(TAG_MAX_SPEED, clampMaxSpeedPerSec(speedPerSec));
    }

    /** 夹到 [{@link #MAX_SPEED_MIN}, {@link #MAX_SPEED_MAX}]（整数格/秒）。 */
    public static double clampMaxSpeedPerSec(double speedPerSec) {
        if (Double.isNaN(speedPerSec)) {
            return DEFAULT_MAX_SPEED_PER_SEC;
        }
        return Math.max(MAX_SPEED_MIN, Math.min(MAX_SPEED_MAX, Math.rint(speedPerSec)));
    }

    /**
     * 最大飞行速度换算成<b>格/tick</b>（÷20）。
     *
     * <p>整条运动逻辑只用这个数：位移长度 = 它、朝向 = 它、命中/撞地形扫掠 = 这一 tick 的线段
     * ⇒ 换速度不会换掉"不瞬移 + 不穿透"这两条（见 {@code ShanhaiDropletEntity#serverTick}）。
     */
    public static double maxSpeedPerTick(Player player) {
        return speedPerTickFor(maxSpeedPerSec(player));
    }

    /**
     * 纯逻辑版：{@code 格/秒 ÷ 20 = 格/tick}（先夹到 10~200，再除）。
     *
     * <p>单独抽出来是为了让离线探针能<b>直接跑真正在用的那条公式</b>
     * （探针没有 {@code Player}，但不能复制一份公式 —— 复制出来的自检永远自洽）。
     */
    public static double speedPerTickFor(double speedPerSec) {
        return clampMaxSpeedPerSec(speedPerSec) / TICKS_PER_SECOND;
    }

    /** 纯逻辑版：跟随点的"左"偏移（格）= 0.6 × 距离。 */
    public static double followLeft(double distance) {
        return FOLLOW_RATIO_LEFT * clampFollowDistance(distance);
    }

    /** 纯逻辑版：跟随点的"上"偏移（格）= 0.8 × 距离。 */
    public static double followUp(double distance) {
        return FOLLOW_RATIO_UP * clampFollowDistance(distance);
    }

    // ------------------------------------------------------------------ 文案

    /** 模式 → 语言键。 */
    public static String modeKey(int mode) {
        switch (clampMode(mode)) {
            case MODE_ENEMY:
                return KEY_MODE_ENEMY;
            case MODE_NONE:
                return KEY_MODE_NONE;
            case MODE_ALL:
            default:
                return KEY_MODE_ALL;
        }
    }

    /** 模式 → 可翻译文案（中文：攻击所有生物 / 仅敌对 / 不攻击）。 */
    public static Component modeName(int mode) {
        return Component.translatable(modeKey(mode));
    }

    /** 爆炸开关 → 可翻译文案（中文：开 / 关）。 */
    public static Component explosionName(boolean enabled) {
        return Component.translatable(enabled ? KEY_EXPLOSION_ON : KEY_EXPLOSION_OFF);
    }

    /** 第 1 条提示（可点击：切换攻击模式）。 */
    public static MutableComponent lineMode(Player player) {
        return Component.translatable(KEY_LINE_MODE, modeName(mode(player)))
                .withStyle(style -> style
                        .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, COMMAND_CYCLE_MODE))
                        .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                                Component.translatable(KEY_LINE_MODE_HOVER))));
    }

    /** 第 2 条提示（可点击：切换爆炸地形破坏）。 */
    public static MutableComponent lineExplosion(Player player) {
        return Component.translatable(KEY_LINE_EXPLOSION, explosionName(explosion(player)))
                .withStyle(style -> style
                        .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, COMMAND_TOGGLE_EXPLOSION))
                        .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                                Component.translatable(KEY_LINE_EXPLOSION_HOVER))));
    }

    /** 第 3 条提示（可点击：收回水滴）。 */
    public static MutableComponent lineRecall() {
        return Component.translatable(KEY_LINE_RECALL)
                .withStyle(style -> style
                        .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, COMMAND_RECALL))
                        .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                                Component.translatable(KEY_LINE_RECALL_HOVER))));
    }

    /**
     * 第 4 条提示（可点击：切换自动驾驶）。
     *
     * <p>与第 1/2 条同款：{@code ClickEvent.Action.RUN_COMMAND}。
     * 点击时把<b>目标状态</b>写进命令（{@code autopilot on|off}）而不是"循环/无参"，
     * 这样点一下就是"切到另一边"，不需要再造一个新的 toggle 命令语义。
     */
    public static MutableComponent lineAutopilot(Player player) {
        final boolean enabled = autopilot(player);
        final String command = enabled ? COMMAND_AUTOPILOT_OFF : COMMAND_AUTOPILOT_ON;
        return Component.translatableWithFallback(KEY_LINE_AUTOPILOT, FALLBACK_LINE_AUTOPILOT,
                        ShanhaiDropletPilotText.onOff(enabled))
                .withStyle(style -> style
                        .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, command))
                        .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                                Component.translatableWithFallback(KEY_LINE_AUTOPILOT_HOVER,
                                        FALLBACK_LINE_AUTOPILOT_HOVER))));
    }

    /**
     * 第 5 条提示（可点击：切换辅助瞄准，并把<b>当前判定角度</b>一并显示）。
     *
     * <p>角度显示在这个"当前：…"里，是因为用户要"一眼知道现在是多少度"；
     * 改角度仍用 {@code /shanhai droplet assist angle <1~45>}（这一条只做开关）。
     */
    public static MutableComponent lineAssist(Player player) {
        final boolean enabled = assist(player);
        final String command = enabled ? COMMAND_ASSIST_OFF : COMMAND_ASSIST_ON;
        return Component.translatableWithFallback(KEY_LINE_ASSIST, FALLBACK_LINE_ASSIST,
                        ShanhaiDropletPilotText.onOff(enabled),
                        ShanhaiDropletPilotText.angle(assistAngle(player)))
                .withStyle(style -> style
                        .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, command))
                        .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                                Component.translatableWithFallback(KEY_LINE_ASSIST_HOVER,
                                        FALLBACK_LINE_ASSIST_HOVER))));
    }

    /**
     * 五条提示（顺序固定：① 模式 ② 爆炸 ③ 收回 ④ 自动驾驶 ⑤ 辅助瞄准）。
     *
     * <p>单独抽出来是为了让命令在切换后能<b>原样再推一遍</b>（"当前：…"必须跟着变），
     * 而不用把五行文案抄第二份。④⑤ 是 2026-11-25 追加，<b>序号接在 3 后面</b>，1/2/3 保持不变。
     */
    public static List<Component> controlPanel(Player player) {
        final List<Component> lines = new ArrayList<>(5);
        lines.add(lineMode(player));
        lines.add(lineExplosion(player));
        lines.add(lineRecall());
        lines.add(lineAutopilot(player));
        lines.add(lineAssist(player));
        return lines;
    }

    /** 把五条提示发到玩家聊天框（规格 1：放置后自动发；每次切换后重发一遍）。 */
    public static void sendControlPanel(ServerPlayer player) {
        for (final Component line : controlPanel(player)) {
            player.sendSystemMessage(line);
        }
    }

    // ------------------------------------------------------------------ 两条新指令的反馈文案

    /**
     * 跟随距离 / 最大速度两条指令的反馈文案。
     *
     * <p>🔴 <b>为什么这里是字面量而不是语言键</b>：本轮允许写入的范围只有
     * {@code common/droplet/**}、{@code client/render/droplet/**}、{@code client/hud/**}
     * ——{@code resources/assets/shanhai/lang/*.json} 不在其中（那个文件被多条线同时改，容易撞）。
     * 所以这两条文案先集中在本类（<b>唯一的硬编码点</b>），键名与格式已经定型，
     * 下一轮把它挪进语言文件时只需要改这 4 个方法体。
     */
    public static Component distanceFeedback(double distance, boolean changed) {
        final String text = changed
                ? String.format(java.util.Locale.ROOT, "§b跟随距离已设为：§e%.2f§b 格（§7范围 1.00~10.00§b）", distance)
                : String.format(java.util.Locale.ROOT, "§b当前跟随距离：§e%.2f§b 格（§7用法：/shanhai droplet distance <1~10>§b）", distance);
        return Component.literal(text);
    }

    public static Component speedFeedback(double speedPerSec, boolean changed) {
        final String text = changed
                ? String.format(java.util.Locale.ROOT,
                        "§b最大飞行速度已设为：§e%.0f§b 格/秒（§7= %.1f 格/tick，范围 10~200§b）",
                        speedPerSec, speedPerSec / TICKS_PER_SECOND)
                : String.format(java.util.Locale.ROOT,
                        "§b当前最大飞行速度：§e%.0f§b 格/秒（§7= %.1f 格/tick，用法：/shanhai droplet speed <10~200>§b）",
                        speedPerSec, speedPerSec / TICKS_PER_SECOND);
        return Component.literal(text);
    }

    // ------------------------------------------------------------------ 驾驶三设置（2026-11-24）

    /** 自动驾驶默认<b>关</b>（规格：用指令开关；默认不接管玩家的操控）。 */
    public static final boolean DEFAULT_AUTOPILOT = false;

    /** 瞄准辅助默认<b>开</b>（规格：默认开启，玩家可以关）。 */
    public static final boolean DEFAULT_ASSIST = true;

    /** 瞄准辅助判定角度默认 <b>5</b> 度（规格原话：「准星距离目标角度小于 5 度时」）。 */
    public static final double DEFAULT_ASSIST_ANGLE = 5.0D;

    /** 瞄准辅助角度下限（度）。 */
    public static final double ASSIST_ANGLE_MIN = 1.0D;

    /** 瞄准辅助角度上限（度）。 */
    public static final double ASSIST_ANGLE_MAX = 45.0D;

    /**
     * 读自动驾驶开关。缺失 ⇒ {@link #DEFAULT_AUTOPILOT}（<b>不</b>顺手写回 NBT）。
     *
     * @param player 拥有者玩家
     */
    public static boolean autopilot(Player player) {
        final CompoundTag data = player.getPersistentData();
        return data.contains(TAG_AUTOPILOT) ? data.getBoolean(TAG_AUTOPILOT) : DEFAULT_AUTOPILOT;
    }

    /** 写自动驾驶开关（随玩家存档走）。 */
    public static void setAutopilot(Player player, boolean enabled) {
        player.getPersistentData().putBoolean(TAG_AUTOPILOT, enabled);
    }

    /** 切自动驾驶开关；返回<b>切换之后</b>的值。 */
    public static boolean toggleAutopilot(Player player) {
        final boolean next = !autopilot(player);
        setAutopilot(player, next);
        return next;
    }

    /**
     * 读瞄准辅助开关。缺失 ⇒ {@link #DEFAULT_ASSIST}。
     *
     * @param player 拥有者玩家
     */
    public static boolean assist(Player player) {
        final CompoundTag data = player.getPersistentData();
        return data.contains(TAG_ASSIST) ? data.getBoolean(TAG_ASSIST) : DEFAULT_ASSIST;
    }

    /** 写瞄准辅助开关（随玩家存档走）。 */
    public static void setAssist(Player player, boolean enabled) {
        player.getPersistentData().putBoolean(TAG_ASSIST, enabled);
    }

    /** 切瞄准辅助开关；返回<b>切换之后</b>的值。 */
    public static boolean toggleAssist(Player player) {
        final boolean next = !assist(player);
        setAssist(player, next);
        return next;
    }

    /**
     * 读瞄准辅助角度（度）。缺失 / 越界 ⇒ {@link #DEFAULT_ASSIST_ANGLE}。
     *
     * @param player 拥有者玩家
     */
    public static double assistAngle(Player player) {
        final CompoundTag data = player.getPersistentData();
        if (!data.contains(TAG_ASSIST_ANGLE)) {
            return DEFAULT_ASSIST_ANGLE;
        }
        return clampAssistAngle(data.getDouble(TAG_ASSIST_ANGLE));
    }

    /** 写瞄准辅助角度（夹进 1~45，保留 1 位小数；随玩家存档走）。 */
    public static void setAssistAngle(Player player, double angleDeg) {
        player.getPersistentData().putDouble(TAG_ASSIST_ANGLE, clampAssistAngle(angleDeg));
    }

    /** 夹到 [{@link #ASSIST_ANGLE_MIN}, {@link #ASSIST_ANGLE_MAX}] 并保留 1 位小数（NaN ⇒ 默认值）。 */
    public static double clampAssistAngle(double angleDeg) {
        if (Double.isNaN(angleDeg)) {
            return DEFAULT_ASSIST_ANGLE;
        }
        final double clamped = Math.max(ASSIST_ANGLE_MIN, Math.min(ASSIST_ANGLE_MAX, angleDeg));
        return Math.round(clamped * 10.0D) / 10.0D;
    }

    /**
     * 驾驶三条指令的反馈文案。
     *
     * <p>🔴 与 {@code distanceFeedback/speedFeedback} 同一处境：本轮不许改
     * {@code resources/assets/shanhai/lang/*.json}，所以这里是<b>唯一的硬编码点</b>，
     * 键名/格式已定型，下一轮挪进语言文件时只改这几个方法体。
     */
    public static Component autopilotFeedback(boolean enabled, boolean changed) {
        final String text = changed
                ? "§b自动驾驶已" + (enabled ? "§a开启§b" : "§c关闭§b") + "（§7Shift 仍然可以离开水滴§b）"
                : "§b当前自动驾驶：" + (enabled ? "§a开" : "§c关") + "§b（§7用法：/shanhai droplet autopilot on|off§b）";
        return Component.literal(text);
    }

    /** 瞄准辅助开关的反馈。 */
    public static Component assistFeedback(boolean enabled, double angleDeg, boolean changed) {
        final String head = changed ? "§b瞄准辅助已" + (enabled ? "§a开启" : "§c关闭") : "§b当前瞄准辅助："
                + (enabled ? "§a开" : "§c关");
        return Component.literal(head + "§b，判定角度 §e"
                + String.format(java.util.Locale.ROOT, "%.1f", Double.valueOf(angleDeg)) + "§b 度（§7范围 1~45§b）");
    }

    /** 瞄准辅助角度的反馈。 */
    public static Component assistAngleFeedback(double angleDeg) {
        return Component.literal("§b瞄准辅助角度已设为：§e"
                + String.format(java.util.Locale.ROOT, "%.1f", Double.valueOf(angleDeg))
                + "§b 度（§7范围 1~45，默认 5§b）");
    }

    private ShanhaiDropletSettings() {}
}
