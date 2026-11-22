package com.shanhai.common.droplet;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

/**
 * 驾驶功能的<b>文案统一出口</b>（一处定义、两处使用：命令反馈 + 客户端提示）。
 *
 * <h2>一、为什么用 {@code translatableWithFallback} 而不是纯 {@code translatable}</h2>
 * 本工程既有文案全部走语言键（{@code assets/shanhai/lang/*.json}）。但这一轮有硬约束：
 * 只允许写 {@code common\droplet\**}、{@code client\pilot\**} 与新的网络包类，
 * <b>资源目录不在其中</b> ⇒ 新键加不进语言文件。若直接用 {@code Component.translatable(...)}，
 * 玩家看到的就是裸键名（{@code droplet.shanhai.cmd.autopilot}）。
 *
 * <p>所以这里一律用 1.20.1 自带的<b>带兜底</b>写法：
 * {@code Component.translatableWithFallback(键, 中文兜底, 参数…)} —— 语言文件里<b>有</b>键就用翻译、
 * <b>没有</b>就用中文兜底。等以后允许改资源时，把 {@code PLAN.md} 里那 7 行 JSON 粘进
 * {@code zh_cn.json / en_us.json} 即可无缝切换到正式翻译，<b>本类一行都不用改</b>。
 *
 * <h2>二、键名约定</h2>
 * 沿用既有 {@code droplet.shanhai.*} 前缀（既有 19 个键就在这个命名空间下），
 * 新增的都在 {@code droplet.shanhai.pilot.*} 与 {@code droplet.shanhai.cmd.*} 下。
 */
public final class ShanhaiDropletPilotText {

    // ------------------------------------------------------------------ 语言键

    public static final String KEY_NOT_OWNER = "droplet.shanhai.pilot.not_owner";
    public static final String KEY_ENTER_HINT = "droplet.shanhai.pilot.enter_hint";
    public static final String KEY_CMD_AUTOPILOT = "droplet.shanhai.cmd.autopilot";
    public static final String KEY_CMD_ASSIST = "droplet.shanhai.cmd.assist";
    public static final String KEY_CMD_ASSIST_ANGLE = "droplet.shanhai.cmd.assist_angle";
    public static final String KEY_CMD_STATUS_PILOT = "droplet.shanhai.cmd.status.pilot";
    public static final String KEY_ON = "droplet.shanhai.pilot.on";
    public static final String KEY_OFF = "droplet.shanhai.pilot.off";

    // ------------------------------------------------------------------ 中文兜底

    private static final String FALLBACK_NOT_OWNER = "只有水滴的拥有者才能驾驶";
    private static final String FALLBACK_ENTER_HINT = "右键水滴即可进入 · W 加速 · S 减速 · Shift 离开";
    private static final String FALLBACK_CMD_AUTOPILOT = "自动驾驶：%s";
    private static final String FALLBACK_CMD_ASSIST = "瞄准辅助：%s，判定角度 %s°";
    private static final String FALLBACK_CMD_ASSIST_ANGLE = "瞄准辅助角度：%s°（可用范围 1–45）";
    private static final String FALLBACK_CMD_STATUS_PILOT =
            "自动驾驶：%s · 瞄准辅助：%s(%s°) · 驾驶中：%s · 速度：%s 格/tick · 限流丢弃：%s";
    private static final String FALLBACK_ON = "开";
    private static final String FALLBACK_OFF = "关";

    private ShanhaiDropletPilotText() {}

    // ------------------------------------------------------------------ 文案

    /** 开关 → 可翻译文案（开 / 关）。 */
    public static MutableComponent onOff(boolean value) {
        return Component.translatableWithFallback(
                value ? KEY_ON : KEY_OFF, value ? FALLBACK_ON : FALLBACK_OFF);
    }

    /** 「只有拥有者才能驾驶」——非拥有者右键水滴时的提示。 */
    public static MutableComponent notOwner() {
        return Component.translatableWithFallback(KEY_NOT_OWNER, FALLBACK_NOT_OWNER);
    }

    /** 进入水滴后的一行操作提示（聊天栏，只发一次）。 */
    public static MutableComponent enterHint() {
        return Component.translatableWithFallback(KEY_ENTER_HINT, FALLBACK_ENTER_HINT);
    }

    /** `/shanhai droplet autopilot on|off` 的反馈。 */
    public static MutableComponent autopilot(boolean enabled) {
        return Component.translatableWithFallback(KEY_CMD_AUTOPILOT, FALLBACK_CMD_AUTOPILOT, onOff(enabled));
    }

    /** `/shanhai droplet assist on|off` 的反馈（带当前判定角度）。 */
    public static MutableComponent assist(boolean enabled, double angleDeg) {
        return Component.translatableWithFallback(KEY_CMD_ASSIST, FALLBACK_CMD_ASSIST,
                onOff(enabled), angle(angleDeg));
    }

    /** `/shanhai droplet assist angle <1..45>` 的反馈（夹紧后的实际生效值）。 */
    public static MutableComponent assistAngle(double angleDeg) {
        return Component.translatableWithFallback(KEY_CMD_ASSIST_ANGLE, FALLBACK_CMD_ASSIST_ANGLE, angle(angleDeg));
    }

    /**
     * `/shanhai droplet status` 的<b>驾驶态那一行</b>（追加在既有状态行之后，不改既有键的参数个数）。
     */
    public static MutableComponent statusPilot(boolean autopilot, boolean assist, double assistAngleDeg,
                                               boolean piloted, double speedPerTick, int dropped) {
        return Component.translatableWithFallback(KEY_CMD_STATUS_PILOT, FALLBACK_CMD_STATUS_PILOT,
                onOff(autopilot),
                onOff(assist),
                angle(assistAngleDeg),
                onOff(piloted),
                Component.literal(ShanhaiDropletPilotCore.speedText(speedPerTick)),
                Integer.valueOf(dropped));
    }

    /** 角度文案：一位小数（避免 5 与 5.0° 两种写法混用）。 */
    public static MutableComponent angle(double angleDeg) {
        return Component.literal(String.format(java.util.Locale.ROOT, "%.1f", Double.valueOf(angleDeg)));
    }
}
