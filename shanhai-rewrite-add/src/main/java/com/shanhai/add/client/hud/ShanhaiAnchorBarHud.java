package com.shanhai.add.client.hud;

import java.util.function.Supplier;

import com.mojang.blaze3d.platform.Window;
import com.shanhai.add.ShanhaiAdd;
import com.shanhai.add.client.hud.ShanhaiAnchorBarState.Mode;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.event.RenderGuiOverlayEvent;
import net.minecraftforge.client.gui.overlay.VanillaGuiOverlay;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * 锚点进度条的<b>客户端 HUD</b>：设了锚点之后，在准星下方、经验条上方那条 182×6 的横条。
 *
 * <h2>0. 只画「读者给的东西」——数据走一个可注入的接缝（已接线）</h2>
 * <pre>
 *   public static Supplier&lt;ShanhaiAnchorBarState&gt; SOURCE = () -&gt; ShanhaiAnchorBarCache.stateOf(levelPresent());
 * </pre>
 * 本类<b>只</b>认 {@link ShanhaiAnchorBarState} 这一种出参：谁给的、怎么算的它一概不管。
 * 现在的实现是「网络线程写缓存 → 本类每帧读缓存」，链路与判据见 {@link #SOURCE} 的注释；
 * 缓存里什么都没有 / 不在世界里 / 接缝被换成了 {@code null} ⇒ {@link ShanhaiAnchorBarState#none()}，
 * 也就是<b>什么都不画、也不会崩</b>。
 * <pre>
 *   服务端每帧一条（AnchorBarStateMsg，shanhai:clock id 2） → 缓存 → 这里
 * </pre>
 * 🔴 本类<b>不读</b> {@code ShanhaiWorldClock} / {@code ShanhaiClockGuard} / 倒流引擎的任何静态字段 ——
 * 锚点/倒流那几处状态由 {@code ShanhaiAnchorBarBroadcast} 读好后发到这里，本类不认识它们；这里只认
 * {@link ShanhaiAnchorBarState} 这一个纯数据出参（所以渲染代码可以脱离业务单独验）。
 *
 * <h2>1. 画在哪（像素坐标是照字节码量的，不是估的）</h2>
 * 依据 {@code net.minecraft.client.gui.Gui#renderExperienceBar} 的字节码：
 * <pre>
 *   int y = this.screenHeight - 32 + 3;              // ⇒ 经验条顶边 = H - 29，高 5，宽 182（sipush 182 / iconst_5）
 *   等级数字画在 y - 8                                 // ⇒ 占据 H-37 .. H-29
 * </pre>
 * 所以本条的落点是（{@code W}/{@code H} = 当前 GUI 缩放后的宽高）：
 * <pre>
 *   x = (W - 182) / 2                                  // 与经验条同左边界（经验条 x = W/2 - 91）
 *   y = H - 29(经验条顶) - 8(等级数字) - 2(留缝) - 6(本条高) = H - 45
 *   ⇒ 本条占 H-45 .. H-39；等级数字顶边 H-37 ⇒ 中间留 2px；经验条顶边 H-29 ⇒ 留 10px
 * </pre>
 * 例：1920×1080、GUI scale=2 ⇒ GUI 960×540 ⇒ 本条落在 (389, 495)，占 182×6。
 * 也顺便避开了原版的「选中物品名」（H-59 一带）与热栏（H-22 起）。
 *
 * <h2>2. 三种形态的颜色（都半透明，不挡视线）</h2>
 * <pre>
 *   WINDOW   暖金  底条深灰 + 填充金      填充从左往右「退」（剩余 1 → 0）
 *   REWIND   青    底条深青 + 填充青      填充从右往左「涨」（已回退 0 → 1）⇒ 这就是「反转」
 *   EXPIRED  灰    底条灰、无填充、灰字    条还在，但已经用完了（fraction = 0）
 * </pre>
 * 不引任何贴图/资源：底条两笔 {@code GuiGraphics.fill} 拼出 1px 倒角，图标位就是一个 4×4 实心块。
 *
 * <h2>3. 只在客户端跑</h2>
 * 整类 {@code @OnlyIn(Dist.CLIENT)} ＋ {@code @Mod.EventBusSubscriber(value = Dist.CLIENT, bus = FORGE)}：
 * 专用服务端上 Forge 不会把本类的静态处理器注册进事件总线（{@code value = Dist.CLIENT}），
 * 常量池里那批 {@code net.minecraft.client.*} 引用<b>永远不解析</b> ⇒ 服务端不可能 {@code NoClassDefFoundError}。
 * 同款写法在扩展里已有先例：{@code com.shanhai.add.client.holo.ShanhaiHoloOntopCommand}（第 59-62 行）。
 *
 * <h2>4. 为什么挂 {@code RenderGuiOverlayEvent.Post} 的 {@code experience_bar}，而不是 CustomizeGuiOverlayEvent</h2>
 * 前者在{@code ForgeGui} 把原版那一层真正画完之后派发（{@code Pre} 被取消时不派发 {@code Post}），
 * 是「贴在原版 HUD 后面补一笔」的标准位置；{@code CustomizeGuiOverlayEvent} 在 1.20.1 里是给
 * 聊天/调试这类<b>自定义层</b>用的。取 {@code event.getOverlay().id()} 而不是自己算 y，
 * 是为了让原版哪天挪动经验条时，我们跟着走（本条 y 由 {@link #barY(int)} 从同一条基准算）。
 */
@OnlyIn(Dist.CLIENT)
@Mod.EventBusSubscriber(modid = ShanhaiAdd.MOD_ID, value = Dist.CLIENT,
        bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class ShanhaiAnchorBarHud {

    // ---------------------------------------------------------------- 接缝（本行已接到 ShanhaiAnchorBarCache）

    /**
     * 数据来源：<b>直接读客户端缓存</b>（{@link ShanhaiAnchorBarCache}），不读任何服务端静态字段。
     *
     * <p>✅ 已接线（原先是 {@code () -> ShanhaiAnchorBarState.none()} ⇒ 什么都不画）。取值链：
     * <pre>
     *   服务端 ShanhaiAnchorBarBroadcast（每帧一条 AnchorBarStateMsg，shanhai:clock id 2）
     *     ⇒ 网络线程 ShanhaiAnchorBarCache.apply(frame)
     *     ⇒ 本行 ShanhaiAnchorBarCache.stateOf(mc.level != null)
     *     ⇒ ShanhaiAnchorBarState（visible/mode/fraction/label）
     * </pre>
     * 🔴 {@code mc.level != null} 这一半是<b>必须</b>的：标题界面/加载中/断线时缓存里可能还留着
     * 上一个世界的帧，不判它就会在标题界面挂一条 60 秒的条（判据 ④）。缓存本身的清空在
     * {@link ShanhaiAnchorBarClient#onLoggingOut}。
     *
     * <p>非 final、可空、可随时替换；读取侧 {@link #currentState()} 对 null 与异常都已兜住。
     */
    public static Supplier<ShanhaiAnchorBarState> SOURCE =
            () -> ShanhaiAnchorBarCache.stateOf(levelPresent());

    /** 客户端此刻在不在地图里（{@code mc.level != null}）；{@code Minecraft} 还没构造出来时也当"不在"。 */
    private static boolean levelPresent() {
        final Minecraft mc = Minecraft.getInstance();
        return mc != null && mc.level != null;
    }

    // ---------------------------------------------------------------- 语言键（3 条，见 assets/shanhai_add/lang）

    /** 「锚点 %ss」。 */
    public static final String KEY_WINDOW = "shanhai_add.anchor_bar.window";

    /** 「倒流 %s%%」。 */
    public static final String KEY_REWIND = "shanhai_add.anchor_bar.rewind";

    /** 「锚点已过期」。 */
    public static final String KEY_EXPIRED = "shanhai_add.anchor_bar.expired";

    // ---------------------------------------------------------------- 尺寸与落点

    /** 条宽：与原版经验条同宽（{@code Gui#renderExperienceBar} 里的 {@code sipush 182}）。 */
    public static final int BAR_WIDTH = 182;

    /** 条高：6（比经验条高 1px，好认；又不至于盖住等级数字）。 */
    public static final int BAR_HEIGHT = 6;

    /** 经验条顶边距屏幕底部的距离：{@code screenHeight - 32 + 3}。 */
    public static final int XP_BAR_TOP_FROM_BOTTOM = 29;

    /** 经验等级数字的高度（{@code Font.lineHeight}，画在经验条顶边再往上 8px）。 */
    public static final int XP_LEVEL_TEXT_HEIGHT = 8;

    /** 本条与等级数字之间的留缝。 */
    public static final int GAP_ABOVE_XP = 2;

    // ---------------------------------------------------------------- 颜色（ARGB，全部半透明）

    /** 底条（空槽）颜色，按形态分：深灰 / 深青 / 灰。 */
    private static final int TRACK_WINDOW = 0x8A101014;
    private static final int TRACK_REWIND = 0x8A0B1A1E;
    private static final int TRACK_EXPIRED = 0x8A3A3A3E;

    /** 填充色：WINDOW 暖金、REWIND 青、EXPIRED 不填（fraction = 0）。 */
    private static final int FILL_WINDOW = 0xC8F0B95A;
    private static final int FILL_REWIND = 0xC83CD8E6;

    /** 左侧图标位：4×4 实心块，比填充更亮一点。 */
    private static final int ICON_WINDOW = 0xFFF7D08A;
    private static final int ICON_REWIND = 0xFF9FF0FA;
    private static final int ICON_EXPIRED = 0xFF9A9A9A;

    /** 右侧文本颜色。 */
    private static final int TEXT_WINDOW = 0xFFF6E0B0;
    private static final int TEXT_REWIND = 0xFFE8FDFF;
    private static final int TEXT_EXPIRED = 0xFFB9B9B9;

    private ShanhaiAnchorBarHud() {}

    // ---------------------------------------------------------------- 事件入口

    /**
     * 原版 {@code experience_bar} 那一层画完之后补一笔（位置见类注释 §1）。
     *
     * <p>三条提前返回：不是经验条这一层 / F1 隐藏 HUD / 状态为空或不该可见。
     * 整个方法不抛异常（渲染线程上抛一次 = 客户端渲染崩）：取状态与上屏都包在 try/catch 里。
     */
    @SubscribeEvent
    public static void onRenderGuiOverlayPost(RenderGuiOverlayEvent.Post event) {
        if (event == null) {
            return;
        }
        final VanillaGuiOverlay anchor = VanillaGuiOverlay.EXPERIENCE_BAR;
        if (!anchor.id().equals(event.getOverlay().id())) {
            return;                                     // 只贴在经验条后面，别的层一概不管
        }
        final Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.options == null || mc.options.hideGui) {
            return;                                     // F1 / 还没进世界：随原版 HUD 一起消失
        }
        final ShanhaiAnchorBarState state = currentState();
        if (state == null || !state.visible() || state.mode() == Mode.NONE) {
            return;                                     // 没设锚点（或接缝没接线）⇒ 什么都不画
        }
        final Window window = event.getWindow();
        if (window == null) {
            return;
        }
        try {
            draw(event.getGuiGraphics(), mc.font, state, barX(window.getGuiScaledWidth()),
                    barY(window.getGuiScaledHeight()));
        } catch (Throwable ignored) {
            // 渲染线程上的任何意外都不许外溢：这一帧不画就是了（宁可不画，不许崩客户端）
        }
    }

    /** 取当前状态：null / 异常 / 不是本类能认的东西 ⇒ {@link ShanhaiAnchorBarState#none()}。 */
    public static ShanhaiAnchorBarState currentState() {
        final Supplier<ShanhaiAnchorBarState> source = SOURCE;
        if (source == null) {
            return ShanhaiAnchorBarState.none();
        }
        try {
            final ShanhaiAnchorBarState state = source.get();
            return state == null ? ShanhaiAnchorBarState.none() : state;
        } catch (Throwable ignored) {
            return ShanhaiAnchorBarState.none();
        }
    }

    // ---------------------------------------------------------------- 几何

    /** 条左边界：与经验条同一套居中规则（{@code (W - 182) / 2}）。 */
    public static int barX(int guiScaledWidth) {
        return (guiScaledWidth - BAR_WIDTH) / 2;
    }

    /** 条上边界：经验条顶（H-29）→ 等级数字（占 8px）→ 留 2px → 6px 高。 */
    public static int barY(int guiScaledHeight) {
        return guiScaledHeight - XP_BAR_TOP_FROM_BOTTOM - XP_LEVEL_TEXT_HEIGHT - GAP_ABOVE_XP - BAR_HEIGHT;
    }

    /**
     * 填充那一段的像素区间（左端含、右端不含）——<b>纯整数运算，不含任何绘制</b>，
     * 所以「倒流时方向反过来」这件事可以离线验（{@code temp/anchor-bar-selfcheck/HudGeomCheck.java}）。
     *
     * <pre>
     *   WINDOW  f=1.0 ⇒ [x+1,   x+181)   左端不动，右端随剩余量往回缩（1 → 0）
     *   REWIND  f=0.0 ⇒ 空；f=0.25 ⇒ [x+136, x+181)；f=1.0 ⇒ [x+1, x+181)
     *                   ⇒ 右端不动，左端一路往左推（0 → 1）＝ 同一根条反着涨
     * </pre>
     *
     * @param mode     {@link Mode#REWIND} 时从右往左涨，其它形态一律从左往右
     * @param fraction 0..1（越界与 NaN 都会被夹住）
     * @param x        {@link #barX(int)} 算出来的条左边界
     */
    public static Span fillSpan(Mode mode, float fraction, int x) {
        final int innerWidth = BAR_WIDTH - 2;                   // 两端各留 1px 当描边
        final int width = Math.round(innerWidth * clamp01(fraction));
        if (width <= 0) {
            return new Span(0, 0);
        }
        if (mode == Mode.REWIND) {
            final int right = x + BAR_WIDTH - 1;                // 内缩后的右端（不含）
            return new Span(right - width, right);
        }
        return new Span(x + 1, x + 1 + width);
    }

    /** 一段像素区间：[{@code from}, {@code to})。 */
    public record Span(int from, int to) {

        /** 宽度（空段为 0）。 */
        public int width() {
            return Math.max(0, this.to - this.from);
        }
    }

    // ---------------------------------------------------------------- 绘制

    /**
     * 把一条状态画出来（坐标已由 {@link #barX(int)}/{@link #barY(int)} 算好）。
     *
     * <p>顺序：底条 → 填充 → 图标位 → 文本，四步都是纯 {@code fill}/{@code drawString}，不碰资源。
     */
    public static void draw(GuiGraphics graphics, Font font, ShanhaiAnchorBarState state, int x, int y) {
        final int right = x + BAR_WIDTH;            // 右边界（不含）
        final int bottom = y + BAR_HEIGHT;          // 下边界（不含）

        // ① 底条：两笔拼出 1px 倒角（中间列满高 ＋ 最左/最右列上下各缩 1px）；颜色按形态分灰/青/暖
        barRect(graphics, x, right, y, trackColor(state.mode()));

        // ② 填充：内缩 1px（上下与两端各留 1px 当描边），宽度严格由 fraction 决定；方向见 fillSpan
        final Span span = fillSpan(state.mode(), state.fraction(), x);
        if (span.width() > 0) {
            graphics.fill(span.from(), y + 1, span.to(), bottom - 1, fillColor(state.mode()));
        }

        // ③ 左侧图标位：4×4 实心块（正好铺满内缩后的 4px 高槽，不引贴图）
        graphics.fill(x + 3, y + 1, x + 7, bottom - 1, iconColor(state.mode()));

        // ④ 右侧文本（右对齐贴着条右端）：字高 8、条高 6 ⇒ 上移 1px 让其垂直居中于条
        final Component text = labelOf(state);
        if (text != null) {
            final int textWidth = font.width(text);
            graphics.drawString(font, text, right - 3 - textWidth, y - 1, textColor(state.mode()), true);
        }
    }

    /**
     * 画一条「两端可倒角」的实心横条（倒角就是跳过 4 个角像素，够看出圆角了，且不用贴图）。
     *
     * @param x0 左端（含）
     * @param x1 右端（不含）；{@code x1 <= x0} 时什么都不画
     */
    private static void barRect(GuiGraphics graphics, int x0, int x1, int y, int argb) {
        if (x1 <= x0) {
            return;
        }
        graphics.fill(x0 + 1, y, x1 - 1, y + BAR_HEIGHT, argb);        // 中间列满高
        graphics.fill(x0, y + 1, x1, y + BAR_HEIGHT - 1, argb);        // 两端上下各缩 1px
    }

    /** 语言键 + {@link ShanhaiAnchorBarState#value()} ⇒ 上屏文本；NONE 不画。 */
    private static Component labelOf(ShanhaiAnchorBarState state) {
        return switch (state.mode()) {
            case WINDOW -> Component.translatable(KEY_WINDOW, state.value());
            case REWIND -> Component.translatable(KEY_REWIND, state.value());
            case EXPIRED -> Component.translatable(KEY_EXPIRED);
            case NONE -> null;
        };
    }

    private static int trackColor(Mode mode) {
        return switch (mode) {
            case REWIND -> TRACK_REWIND;
            case EXPIRED -> TRACK_EXPIRED;
            default -> TRACK_WINDOW;
        };
    }

    private static int iconColor(Mode mode) {
        return switch (mode) {
            case REWIND -> ICON_REWIND;
            case EXPIRED -> ICON_EXPIRED;
            default -> ICON_WINDOW;
        };
    }

    /** 填充色：WINDOW 暖金、REWIND 青；EXPIRED / NONE 不填（fraction 本来就是 0）。 */
    private static int fillColor(Mode mode) {
        return switch (mode) {
            case WINDOW -> FILL_WINDOW;
            case REWIND -> FILL_REWIND;
            default -> 0;
        };
    }

    private static int textColor(Mode mode) {
        return switch (mode) {
            case REWIND -> TEXT_REWIND;
            case EXPIRED -> TEXT_EXPIRED;
            default -> TEXT_WINDOW;
        };
    }

    private static float clamp01(float value) {
        if (!(value > 0.0F)) {         // 也顺手挡掉 NaN
            return 0.0F;
        }
        return value > 1.0F ? 1.0F : value;
    }
}
