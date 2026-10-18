package com.shanhai.common.itemedit;

import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * 山海重构 · <b>多行提示的解析器（唯一一份实现）</b>。
 *
 * <h2>1. 谁在用它</h2>
 * <ul>
 *   <li>{@code com.shanhai.item.RealityShellItem#appendHoverText} —— 128 个「现实物品」壳（原有能力）；</li>
 *   <li>{@link ShanhaiItemOverrideHooks} —— 任意已存在物品的提示覆盖（本轮新增）。</li>
 * </ul>
 * 本类是把 {@code RealityShellItem} 里<b>已经在跑的那段代码原样搬出来</b>（行首标记的解析、按键门槛、
 * 灰色前缀、提示行），而不是重写一份 —— 两份实现漂移过一次就够受的。
 *
 * <h2>2. 行首标记（用户拍板的语义）</h2>
 * <pre>
 *   普通行        永远显示
 *   [s] 开头的行  只有按住 Shift 才显示
 *   [c] 开头的行  只有按住 Ctrl  才显示
 * </pre>
 * 若这一条提示里<b>存在</b>被门控的行、而当前一个键都没按 ⇒ 追加一行灰色提示
 * 「按住 Shift / Ctrl 查看完整提示」（{@link #HINT}），否则用户根本不知道还有内容。
 *
 * <h2>3. 🔴 为什么按键状态要经 {@code DistExecutor} 取</h2>
 * {@code Screen} 是<b>纯客户端</b>类，专用服务端上没有它。公共侧的代码直接引用
 * {@code ClientKeyState} 会让专服在类加载期炸 ⇒ 一律经
 * {@code DistExecutor.unsafeRunForDist(() -> ClientKeyState::shift, () -> () -> false)} 间接调用，
 * 服务端那一侧<b>根本不会加载</b>那个类（这也是 {@code ClientKeyState} 类注释里写死的口径）。
 */
public final class ShanhaiItemLore {

    /** 行首标记：按住 Shift 才显示。 */
    public static final String TAG_SHIFT = "[s]";
    /** 行首标记：按住 Ctrl 才显示。 */
    public static final String TAG_CTRL = "[c]";
    /** 有被门控的行、但一个键都没按时追加的那一行。 */
    public static final String HINT = "§8按住 Shift / Ctrl 查看完整提示";
    /** 普通提示行的默认颜色前缀（跟现实物品壳同一口径）。 */
    public static final String LINE_PREFIX = "§7";

    private ShanhaiItemLore() {}

    /** 是否按住 Shift（服务端恒 false）。 */
    public static boolean shiftHeld() {
        final boolean v = net.minecraftforge.fml.DistExecutor.unsafeRunForDist(
                () -> com.shanhai.client.reality.ClientKeyState::shift, () -> () -> false);
        return v;
    }

    /** 是否按住 Ctrl（服务端恒 false）。 */
    public static boolean ctrlHeld() {
        final boolean v = net.minecraftforge.fml.DistExecutor.unsafeRunForDist(
                () -> com.shanhai.client.reality.ClientKeyState::ctrl, () -> () -> false);
        return v;
    }

    /**
     * 把一串多行提示按「按键门槛」筛进 {@code tooltip}。
     *
     * @param lore    多行文本（真实换行；也认字面量 {@code \n}，见 {@link ShanhaiItemOverrideTable}）
     * @param shift   当前是否按住 Shift
     * @param ctrl    当前是否按住 Ctrl
     * @param tooltip 目标列表（会被就地追加）
     * @return {@code true} = 存在被门控的行（调用方据此决定要不要加 {@link #HINT}）
     */
    public static boolean addLoreLines(String lore, boolean shift, boolean ctrl, List<Component> tooltip) {
        if (lore == null || lore.isBlank() || tooltip == null) {
            return false;
        }
        boolean hasGated = false;
        for (String raw : lore.split("\\R")) {
            if (raw.isBlank()) {
                continue;
            }
            String line = raw;
            boolean needShift = false;
            boolean needCtrl = false;
            if (line.startsWith(TAG_SHIFT)) {
                needShift = true;
                line = line.substring(TAG_SHIFT.length());
            } else if (line.startsWith(TAG_CTRL)) {
                needCtrl = true;
                line = line.substring(TAG_CTRL.length());
            }
            if (line.isBlank()) {
                continue;
            }
            if (needShift || needCtrl) {
                hasGated = true;
                final boolean held = needShift ? shift : ctrl;
                if (!held) {
                    continue;
                }
            }
            tooltip.add(Component.literal(LINE_PREFIX + line));
        }
        return hasGated;
    }

    /** 便捷版：自己取一次按键状态（两条入口最常用）。 */
    public static boolean addLoreLines(String lore, List<Component> tooltip) {
        return addLoreLines(lore, shiftHeld(), ctrlHeld(), tooltip);
    }
}
