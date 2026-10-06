package com.shanhai.common.recipe.editor;

import com.lowdragmc.lowdraglib.gui.widget.Widget;
import com.shanhai.ShanhaiMod;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

/**
 * 查询结果屏顶部那一排 <b>机器图标</b>（照 JEI 的「催化剂」那一排画）。
 *
 * <h2>用户原话（逐字，2026-10-05）</h2>
 * <blockquote>「切换配方种类可以直接在上面那一排切换」<br>
 * 「你最好像jei一样把那排机器的图标也画出来，并且鼠标悬停在上面时可以显示配方类型的中文名」</blockquote>
 *
 * <h2>照抄的是 JEI 的哪一块</h2>
 * {@code mezz.jei.gui.recipes.RecipeCatalysts}（JEI 15.49.0.188，MIT；javap -c 实测）：
 * <ul>
 *   <li>一个催化剂 = <b>一个 18×18 的槽位 ＋ 里面的方块图标</b>（本类同款）；
 *   <li>多个催化剂<b>横向并排</b>（JEI 里排不下会折成多列，本工程一排放 4 个、
 *       多的靠左右两颗箭头翻"排"）；
 *   <li>悬停给 tooltip（JEI 给的是那一格里的物品名；本工程按用户要求给
 *       <b>配方类型的中文名</b>＋它有多少条配方）。
 * </ul>
 *
 * <h2>🔴 为什么整排做成【一个】控件而不是 4 个</h2>
 * LDLib 的控件表在 UI 建好之后不能再改（本面板的既有纪律），而"一排有几个"会随查询结果变。
 * 做成一个控件、内部按 x 坐标自己命中，就能在不增删控件的前提下画出任意个数（最多
 * {@link ShanhaiRecipeEditorWorkspace#TABS_PER_PAGE} 个）。
 *
 * <h2>🔴 第 11 刀：名字被裁成一个字（用户报的 ③）—— 先量，再改</h2>
 * <b>量出来的数</b>（都在代码里，可逐条核）：
 * <pre>
 *   一格宽 TAB_W          = 74 px
 *   图标槽                = 18 px（画在 0..18）
 *   名字起点              = x+20（图标 18 ＋ 间距 2）
 *   名字区可用宽          = 74 − 20 − 2 = <b>52 px</b>
 *   老代码的裁法          = clip(label, 5) ⇒ clipUnits(label, 5)，
 *                           单位口径 = 汉字 2 / 省略号 2 ⇒ 预算 5−2 = 3 ⇒ 只放得进 1 个汉字
 *   ⇒ 「粉碎机」（3 个汉字 = 6 个单位）被裁成「碎…」＝ 用户截图里那一排
 * </pre>
 * ⇒ <b>格子宽度不是瓶颈</b>（52 px 放得下 5 个汉字、「粉碎机」只要 27 px），
 * 真凶是那个写死的 {@code 5}。现在整排按像素选一档字号（1.0 / 0.75 / 0.5），
 * 单名放不下才补省略号（至少留 2 个汉字），并把
 * {@code cell_px / name_px / font.width 实测 / estimate / 实际画出来的串} 打一行日志。
 *
 * <h2>⚠️ 如实交代</h2>
 * 图标能不能取到取决于配方类型有没有声明图标（{@link ShanhaiRecipeQuery#iconOf}）——
 * 取不到时画灰框并在 tooltip 里写明"这个类型没有图标"，<b>绝不画一个错的图标</b>。
 * 观感（颜色、间距、0.75 倍字会不会糊）只能在客户端上看，本轮没验 ——
 * 但"名字有几个字"这件事已经变成可读的读数（那一行 {@code tab_name} 日志）。
 */
public final class ShanhaiRecipeTabBarWidget extends Widget {

    private static final String PREFIX = "[SHANHAI-EDIT] editor";

    /**
     * 一格标签页的宽度（图标 18 ＋ 间隔 2 ＋ 中文名与条数）。
     *
     * <p>🔴 取 74 是算过的：4 格 = 4×74 − 2 = <b>294</b> ⇒ 那一排占 4..298，
     * 右边留给"翻一排"那两颗 16px 箭头（302..318 / 320..336）⇒ 加起来正好 336 ≤ 面板宽 340。
     */
    public static final int TAB_W = ShanhaiRecipeEditorSession.TAB_CELL_PX;
    /** 高（＝图标高 + 2）。 */
    public static final int TAB_H = 20;

    /** 🔴 名字区可用像素（74 − 20 − 2 = 52）—— 见 {@link ShanhaiRecipeEditorSession#TAB_NAME_PX}。 */
    private static final int NAME_PX = ShanhaiRecipeEditorSession.TAB_NAME_PX;

    private static final int SLOT_BG = 0xFF373737;
    private static final int FRAME_IDLE = 0xFF8B8B8B;
    private static final int FRAME_SEL = 0xFFE0B000;
    private static final int FRAME_EMPTY = 0xFF5A5A5A;

    private final ShanhaiRecipeEditorWorkspace session;
    /** 鼠标当前压在第几个标签上（-1 = 没有）——由 drawInBackground 写、drawInForeground 读。 */
    private int hoverIndex = -1;

    /**
     * 已经往日志里量过的标签（每一条只量一次，免得每帧刷屏）。
     * ⚠️ 不带缓存地每帧重算字号：一页才 4 个短串，代价可忽略；而缓存会在"翻排之后标签换了"
     * 时留下过大的字号 ⇒ 反而可能溢出。
     */
    private static final java.util.Set<String> MEASURED = new java.util.HashSet<>();

    public ShanhaiRecipeTabBarWidget(ShanhaiRecipeEditorWorkspace session, int x, int y) {
        super(x, y, TAB_W * ShanhaiRecipeEditorWorkspace.TABS_PER_PAGE - 2, TAB_H);
        this.session = session;
    }

    private ShanhaiRecipeQuery.Tab tab(int i) {
        return session == null ? null : session.tabAt(i);
    }

    @Override
    public void drawInBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
        hoverIndex = -1;
        if (session == null || !session.stageIsQuery()) {
            return;
        }
        final Minecraft mc = Minecraft.getInstance();
        final int x0 = getPositionX();
        final int y0 = getPositionY();

        // ── 先量、再画：这一排该用多大的字号（放不下整排就一起缩，缩到底才裁）──
        final java.util.List<String> labels = new java.util.ArrayList<>();
        for (int i = 0; i < ShanhaiRecipeEditorWorkspace.TABS_PER_PAGE; i++) {
            final ShanhaiRecipeQuery.Tab t = tab(i);
            if (t != null) {
                labels.add(t.label());
            }
        }
        final float nameScale = ShanhaiRecipeEditorSession.tabNameScale(labels);

        for (int i = 0; i < ShanhaiRecipeEditorWorkspace.TABS_PER_PAGE; i++) {
            final ShanhaiRecipeQuery.Tab t = tab(i);
            if (t == null) {
                continue;
            }
            final int x = x0 + i * TAB_W;
            final boolean hover = mouseX >= x && mouseX < x + TAB_W
                    && mouseY >= y0 && mouseY < y0 + TAB_H;
            if (hover) {
                hoverIndex = i;
            }
            // 槽位（照 JEI 的 catalyst slot：一个 18×18 的框 ＋ 里面的方块图标）
            graphics.fill(x, y0, x + 18, y0 + 18, t.selected() ? FRAME_SEL : FRAME_IDLE);
            graphics.fill(x + 1, y0 + 1, x + 17, y0 + 17, SLOT_BG);
            final ItemStack icon = ShanhaiRecipeQuery.iconStackOf(t);
            if (icon != null) {
                graphics.renderItem(icon, x + 1, y0 + 1);
            } else {
                graphics.fill(x + 3, y0 + 3, x + 15, y0 + 15, FRAME_EMPTY);
            }
            // ── 名字（中文名，服务端按语言表解析好推下来的）──
            // 🔴 用户报的 ③：「机器名字只显示了前面一个字」。老代码是 clip(label, 5)
            //    ⇒ 单位口径（汉字 2、省略号 2）下只放得进 1 个汉字 ⇒ 「粉碎机」变「碎…」。
            //    量出来的事实：名字区 52 px，5 个汉字只要 45 px ⇒ 根本没到"放不下"。
            //    现在改成【按像素算】：整排一个字号（1.0 / 0.75 / 0.5），
            //    单个名字放不下才补省略号（且至少留 2 个汉字）。
            final String shown = ShanhaiRecipeEditorSession.fitTabLabel(t.label(), nameScale);
            drawScaled(graphics, mc, (t.selected() ? "§e" : "§7") + shown,
                    x + ShanhaiRecipeEditorSession.TAB_NAME_X, y0 + 2, nameScale);
            graphics.drawString(mc.font, "§8" + t.count(), x + 20, y0 + 11, 0xFFFFFF, false);
            measureOnce(mc, t.label(), shown, nameScale);
        }
    }

    /** 把「控件宽 / 可用宽 / 文字真实宽（font.width）」量一次并写进日志（每条标签只写一次）。 */
    private static void measureOnce(Minecraft mc, String label, String shown, float scale) {
        if (label == null || !MEASURED.add(label)) {
            return;
        }
        final String plain = ShanhaiRecipeEditorSession.stripColor(label);
        int real = -1;
        try {
            real = mc.font.width(plain);
        } catch (Throwable ignored) {
            real = -1;
        }
        ShanhaiMod.LOGGER.info("{} tab_name cell_px={} name_px={} label='{}' font_width={} "
                        + "scale={} estimate_px={} shown='{}'",
                PREFIX, TAB_W, NAME_PX, plain, real, scale,
                ShanhaiRecipeEditorSession.textPx(plain), shown);
    }

    /**
     * 按 {@code scale} 画一行字（{@code scale == 1} 时走原路，保证"没缩"的那一档像素级不变）。
     *
     * <p>⚠️ 缩放后的文字宽度＝原宽 × scale，所以"放不下"的判据用的是
     * {@link ShanhaiRecipeEditorSession#textPx} × scale（同一把尺子）。
     */
    private static void drawScaled(GuiGraphics graphics, Minecraft mc, String text,
                                   int x, int y, float scale) {
        if (scale == 1.0f) {
            graphics.drawString(mc.font, text, x, y, 0xFFFFFF, false);
            return;
        }
        graphics.pose().pushPose();
        graphics.pose().translate(x, y, 0f);
        graphics.pose().scale(scale, scale, 1f);
        graphics.drawString(mc.font, text, 0, 0, 0xFFFFFF, false);
        graphics.pose().popPose();
    }

    @Override
    public void drawInForeground(GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
        if (hoverIndex >= 0) {
            final ShanhaiRecipeQuery.Tab t = tab(hoverIndex);
            if (t != null) {
                tooltipTexts.clear();
                tooltipTexts.add(Component.literal("§f" + t.label() + " §7（配方类型）"));
                tooltipTexts.add(Component.literal("§7这一种共有 §f" + t.count() + " §7条配方"));
                if (!t.hasIcon()) {
                    tooltipTexts.add(Component.literal("§8（这个配方类型没有声明机器图标）"));
                }
                tooltipTexts.add(Component.literal("§a点它 = 切换到这一种"));
            }
        }
        super.drawInForeground(graphics, mouseX, mouseY, partialTicks);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (session == null || !isMouseOverElement(mouseX, mouseY)) {
            return false;
        }
        final int x0 = getPositionX();
        final int i = (int) ((mouseX - x0) / TAB_W);
        if (i < 0 || i >= ShanhaiRecipeEditorWorkspace.TABS_PER_PAGE || tab(i) == null) {
            return false;
        }
        if (isRemote()) {
            writeClientAction(1, buf -> buf.writeVarInt(i));
        } else {
            session.tabSelect(i);
        }
        ShanhaiMod.LOGGER.info("{} tab_click index={} label={}", PREFIX, i,
                tab(i) == null ? "(none)" : tab(i).label());
        return true;
    }

    @Override
    public void handleClientAction(int id, FriendlyByteBuf buffer) {
        if (session == null) {
            return;
        }
        try {
            final int i = buffer.readVarInt();
            final boolean ok = session.tabSelect(i);
            ShanhaiMod.LOGGER.info("{} tab_action_applied index={} ok={}", PREFIX, i, ok);
        } catch (Throwable t) {
            ShanhaiMod.LOGGER.error("{} tab_action_failed id={} err={}", PREFIX, id, t.toString(), t);
        }
    }
}
