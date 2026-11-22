package com.shanhai.client.pilot;

import com.shanhai.common.droplet.ShanhaiDropletPilotCore;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;

/**
 * 驾驶期间的<b>按键闸门</b>（客户端，无 mixin）：只放行 W / S / Shift / E / Esc / 斜杠 / T。
 *
 * <h2>一、做法（规格原文：「每个客户端 tick 把所有不在白名单的 KeyMapping 强制置为 false」）</h2>
 * <pre>
 *   for (每个 KeyMapping km) {
 *       if (白名单) continue;          // 白名单一个字节都不动
 *       km.setDown(false);             // 1.20.1 里 public（javap 已核）
 *       while (km.consumeClick()) { }  // 抽干累计点击：否则下一帧某个 consumeClick() 仍会读到"按过"
 *   }
 * </pre>
 *
 * <h2>二、为什么"每 tick 置 false"就够（不需要 mixin 去拦键盘事件）</h2>
 * 原版 {@code KeyboardHandler} 只在 GLFW 的 <b>PRESS / RELEASE</b> 两个事件上改 {@code isDown}；
 * 按住不放产生的 <b>GLFW_REPEAT 原版根本不处理</b> ⇒ 我们把 {@code isDown} 归零之后，
 * 只要玩家不松手再按一次，它就<b>不会</b>自己变回 true。所以每 tick 归零 = 按住也失效。
 * 反过来说，白名单里的键（W/S/Shift）我们从不碰，手感与原版完全一致。
 *
 * <h2>三、覆盖范围（不需要枚举黑名单）</h2>
 * 判据是"<b>不在白名单就拦</b>"，所以既涵盖规格点名的攻击/使用/丢弃/跳跃/潜行(按住)/热键栏 1–9/切换视角，
 * 也自动涵盖所有别的键（冲刺、左右平移、拾取方块、副手交换、快捷存读、社交界面……）。
 * 同时扫 {@code options.keyMappings} 与 {@code options.keyHotbarSlots} 两个数组：
 * 热键栏那 9 个是否在 {@code keyMappings} 里属于版本实现细节，两个都扫就不会漏。
 *
 * <h2>四、Esc 为什么不用管</h2>
 * 1.20.1 的 Esc <b>不是</b> {@code KeyMapping}（由 {@code KeyboardHandler}/{@code Screen} 直接处理）
 * ⇒ 它既不会被我们拦，也不需要放行，天然可用。
 *
 * <h2>五、鼠标</h2>
 * 只处理按键映射，<b>不碰任何鼠标移动代码</b> ⇒ 视线（准星）完全自由。
 */
public final class ShanhaiDropletPilotKeys {

    private ShanhaiDropletPilotKeys() {}

    /**
     * 把非白名单按键全部归零（每客户端 tick 调一次，只在骑乘我们水滴时调）。
     *
     * @param mc 客户端实例
     * @return 本次被归零的按键个数（0 = 本来就没按下；用于日志/诊断，不参与逻辑）
     */
    public static int enforce(Minecraft mc) {
        if (mc == null || mc.options == null) {
            return 0;
        }
        final Options options = mc.options;
        int suppressed = 0;
        suppressed += enforceArray(options.keyMappings, options);
        suppressed += enforceArray(options.keyHotbarSlots, options);
        // 显式补一遍这几个"最容易漏"的键：热键栏可能不在 keyMappings 里，
        // 而 attack/use/drop/jump/sprint/pick/swap 是"误触代价最高"的那批（丢东西、跳出去、砍自己人）。
        suppressed += enforceArray(watched(mc), options);
        return suppressed;
    }

    /**
     * 白名单判定 = Core 的语义白名单 + <b>F5（切视角）</b>。
     *
     * <p>🔴 F5 的放行落在本类（不在 {@link ShanhaiDropletPilotCore#keyAllowed} 里）的原因：
     * 本轮允许改动的文件只有 Entity / Client / Keys / Overlay，Core 不在其中；
     * 而 {@code keyTogglePerspective} 是 {@link Options} 上的真实字段，天然属于客户端这一层。
     * 下一轮若允许动 Core，可以把它并回枚举白名单（语义更整齐，行为不变）。
     */
    public static boolean isWhitelisted(KeyMapping mapping, Options options) {
        if (mapping == null || options == null) {
            return false;
        }
        if (mapping == options.keyTogglePerspective) {
            return true;                       // F5：允许切第一/第三人称
        }
        return ShanhaiDropletPilotCore.keyAllowed(classify(mapping, options));
    }

    /**
     * 白名单自检（实机一行可判）：把"该放行的 7 个 + F5"与"该拦的 5 个"逐条打出来。
     *
     * <p>因为 F5 的放行不在 Core 的纯逻辑白名单里，纯逻辑断言覆盖不到它，所以这里给一条运行期读数：
     * 期望 {@code f5=true … hotbar1_blocked=true}，并且 {@code keys_blocked … still_down=[]}。
     */
    public static String whitelistSelfTest(Options options) {
        if (options == null) {
            return "whitelist_check options=null";
        }
        return "whitelist_check f5=" + isWhitelisted(options.keyTogglePerspective, options)
                + " w=" + isWhitelisted(options.keyUp, options)
                + " s=" + isWhitelisted(options.keyDown, options)
                + " shift=" + isWhitelisted(options.keyShift, options)
                + " e=" + isWhitelisted(options.keyInventory, options)
                + " t=" + isWhitelisted(options.keyChat, options)
                + " slash=" + isWhitelisted(options.keyCommand, options)
                + " attack_blocked=" + !isWhitelisted(options.keyAttack, options)
                + " use_blocked=" + !isWhitelisted(options.keyUse, options)
                + " drop_blocked=" + !isWhitelisted(options.keyDrop, options)
                + " jump_blocked=" + !isWhitelisted(options.keyJump, options)
                + " hotbar1_blocked=" + !isWhitelisted(options.keyHotbarSlots[0], options);
    }

    /** 显式盯死的高危键（值在 {@link #watched(Minecraft)} 里按当前 Options 取，避免静态持有 KeyMapping）。 */
    private static final KeyMapping[] EXTRA_WATCHED = new KeyMapping[7];

    /** 用当前 Options 刷新 {@link #EXTRA_WATCHED}，返回它（spare 数组复用，不每 tick 新建）。 */
    private static KeyMapping[] watched(Minecraft mc) {
        final Options o = mc.options;
        EXTRA_WATCHED[0] = o.keyAttack;
        EXTRA_WATCHED[1] = o.keyUse;
        EXTRA_WATCHED[2] = o.keyDrop;
        EXTRA_WATCHED[3] = o.keyJump;
        EXTRA_WATCHED[4] = o.keySprint;
        EXTRA_WATCHED[5] = o.keyPickItem;
        EXTRA_WATCHED[6] = o.keySwapOffhand;
        return EXTRA_WATCHED;
    }

    private static int enforceArray(KeyMapping[] mappings, Options options) {
        if (mappings == null) {
            return 0;
        }
        int suppressed = 0;
        for (final KeyMapping mapping : mappings) {
            if (mapping == null) {
                continue;
            }
            if (isWhitelisted(mapping, options)) {
                continue;
            }
            if (mapping.isDown()) {
                suppressed++;
            }
            mapping.setDown(false);
            // 抽干累计点击：不清的话，任何一个用 consumeClick() 的逻辑（放置/攻击等）下一帧仍会读到一次
            while (mapping.consumeClick()) {
                // 故意空循环：只为了把计数清零
            }
        }
        return suppressed;
    }

    /**
     * 清完一遍之后<b>仍然按着</b>的那些非白名单键的名字（诊断用：实机一测就知道漏了哪个键）。
     *
     * @return 空数组 = 全清干净；否则是"还按着"的键名（{@code KeyMapping#getName()}）
     */
    public static String[] stillDown(Minecraft mc) {
        if (mc == null || mc.options == null) {
            return new String[0];
        }
        final java.util.List<String> names = new java.util.ArrayList<>();
        collectStillDown(mc.options.keyMappings, mc.options, names);
        collectStillDown(mc.options.keyHotbarSlots, mc.options, names);
        collectStillDown(watched(mc), mc.options, names);
        return names.toArray(new String[0]);
    }

    private static void collectStillDown(KeyMapping[] mappings, Options options, java.util.List<String> out) {
        if (mappings == null) {
            return;
        }
        for (final KeyMapping mapping : mappings) {
            if (mapping == null || !mapping.isDown()) {
                continue;
            }
            if (isWhitelisted(mapping, options)) {
                continue;
            }
            final String name = mapping.getName();
            if (!out.contains(name)) {
                out.add(name);
            }
        }
    }

    /**
     * 按键 → 类别（只对白名单里的 6 个做同一性比对，其余一律 {@link ShanhaiDropletPilotCore.PilotKey#OTHER}）。
     *
     * <p>为什么只认这 6 个：白名单是"允许清单"，未知键默认落到 OTHER ⇒ 默认被拦（宁可多拦）。
     * 这个函数<b>不</b>试图认识"攻击/丢弃"等具体键 —— 那属于枚举黑名单，会漏；而"不在清单就拦"不会漏。
     */
    public static ShanhaiDropletPilotCore.PilotKey classify(KeyMapping mapping, Options options) {
        if (mapping == null || options == null) {
            return ShanhaiDropletPilotCore.PilotKey.OTHER;
        }
        if (mapping == options.keyUp) {
            return ShanhaiDropletPilotCore.PilotKey.FORWARD;
        }
        if (mapping == options.keyDown) {
            return ShanhaiDropletPilotCore.PilotKey.BACK;
        }
        if (mapping == options.keyShift) {
            return ShanhaiDropletPilotCore.PilotKey.SNEAK;
        }
        if (mapping == options.keyInventory) {
            return ShanhaiDropletPilotCore.PilotKey.INVENTORY;
        }
        if (mapping == options.keyChat) {
            return ShanhaiDropletPilotCore.PilotKey.CHAT;
        }
        if (mapping == options.keyCommand) {
            return ShanhaiDropletPilotCore.PilotKey.COMMAND;
        }
        if (mapping == options.keyTogglePerspective) {
            // F5：类别如实标注（放不放行由 isWhitelisted 决定，不看这个类别）
            return ShanhaiDropletPilotCore.PilotKey.PERSPECTIVE;
        }
        return ShanhaiDropletPilotCore.PilotKey.OTHER;
    }

    /** 当前是否按着 W（只读，不合成任何输入）。 */
    public static boolean forward(Minecraft mc) {
        return mc != null && mc.options != null && mc.options.keyUp != null && mc.options.keyUp.isDown();
    }

    /** 当前是否按着 S（只读）。 */
    public static boolean backward(Minecraft mc) {
        return mc != null && mc.options != null && mc.options.keyDown != null && mc.options.keyDown.isDown();
    }

    /**
     * 当前是否按着 Shift（只读）。
     *
     * <p>Shift 属于白名单（"离开水滴"），所以它<b>不会</b>被 {@link #enforce} 归零；
     * 客户端把它的状态随意图包一起上报，服务端据此下车 —— 这样"按 Shift 下车"不依赖
     * "服务端能及时看到 sneak 状态"这条外部假设（实体侧另外还认 {@code isShiftKeyDown()}）。
     */
    public static boolean sneak(Minecraft mc) {
        return mc != null && mc.options != null && mc.options.keyShift != null && mc.options.keyShift.isDown();
    }
}
