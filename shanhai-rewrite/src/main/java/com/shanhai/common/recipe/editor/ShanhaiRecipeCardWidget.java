package com.shanhai.common.recipe.editor;

import com.lowdragmc.lowdraglib.gui.widget.Widget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.shanhai.ShanhaiMod;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.ItemStack;

/**
 * 一张 <b>配方卡片</b>（第二屏与查询结果屏共用）。
 *
 * <h2>0. 🔴 来源声明（用户点名要的：「画成配方卡片你甚至可以照抄jei代码」）</h2>
 * 本类的画法照抄 GTCEu 与 JEI 的实现，<b>出处逐条列在这里</b>（两侧都是许可兼容的：
 * GTCEu 是 LGPL-3.0，与本工程同许可；JEI 15.49.0.188 是 MIT，允许并入 LGPL-3.0 项目，
 * 条件是保留来源标注 —— 这一节就是那个标注）：
 * <table border="1">
 *   <tr><th>画的是哪一块</th><th>照抄自</th><th>抄到的具体口径</th></tr>
 *   <tr><td>三行数值（耗时 / 总计 EU / 耗能功率）的<b>文案与算法</b></td>
 *       <td>{@code com.gregtechceu.gtceu.integration.GTRecipeWidget#getRecipeParaText}
 *           （gtceu-1.20.1-1.4.4.jar，javap -c 实测）</td>
 *       <td>耗时 = {@code duration / 20.0f} 经 {@code FormattingUtil.formatNumbers}；
 *           总计 = {@code eut × duration}；耗能功率 = {@code eut}；
 *           三条分别用 GT 自己的语言键 {@code gtceu.recipe.duration / total / eu}</td></tr>
 *   <tr><td>三行数值的<b>行距</b></td>
 *       <td>同上，{@code GTRecipeWidget.LINE_HEIGHT} 与 {@code initializeRecipeTextWidget}
 *           的 {@code iinc yOffset, 10}</td>
 *       <td>每行 <b>10 px</b></td></tr>
 *   <tr><td>右上角那个<b>带颜色的电压词</b></td>
 *       <td>同上，{@code initializeRecipeTextWidget} 里的 {@code GTValues.VNF[tier]}</td>
 *       <td>电压名直接取 {@code GTValues.VNF}（它自带 § 颜色码，MV 就是青色 §b）</td></tr>
 *   <tr><td>顶部那一排<b>机器图标</b>的槽位外观</td>
 *       <td>{@code mezz.jei.gui.recipes.RecipeCatalysts}（JEI 15.49.0.188，javap -c 实测）</td>
 *       <td>每个催化剂 = 一个 18×18 的槽位 ＋ 里面的方块图标；悬停给 tooltip</td></tr>
 *   <tr><td>右侧那两个小按钮的<b>位置</b></td>
 *       <td>{@code mezz.jei.gui.recipes.RecipeLayoutWithButtons}（bookmark ＋ transfer 两个按钮）</td>
 *       <td>贴在卡片右侧、纵向排列</td></tr>
 * </table>
 *
 * <h2>1. 🔴 与 JEI 的两处<b>故意不同</b>（用户点名改的）</h2>
 * <ol>
 *   <li><b>右边那两个小按钮换成一个「选中」</b>。用户原话（逐字）：
 *       「指的就是JEI 里是<b>书签和加号</b>，你把他俩<b>删了</b>，改成一个<b>选中按钮</b>，
 *        点了<b>进去就是第三屏编辑</b>」 ⇒ 那两个按钮在本类里<b>不存在</b>，
 *       只有一个 {@code 选中}，点下去 = 选中这条配方并直接进第三屏。</li>
 *   <li><b>多一行"原始耗时"</b>。用户原话：「第2屏与第3屏都显示两条时间」——
 *       第 2 屏两条都<b>只读</b>（原始＋实际），第 3 屏才允许改原始值。
 *       所以本卡片的第 1 行同时写「原始耗时」与「实际耗时」。</li>
 * </ol>
 *
 * <h2>2. 🔴 性能：这一屏只建【本页】那 4 张</h2>
 * 用户报过第二屏「化学浸洗机共 1378 条」⇒ 一次全建 1378 张卡会把这一帧拖死。
 * 卡片数据由服务端按页算好推下来（见 {@link ShanhaiRecipeEditorWorkspace#buildCards}），
 * 控件数量固定（{@link ShanhaiRecipeEditorWorkspace#CARDS_PER_PAGE} 个）。
 *
 * <h2>3. ⚠️ 如实交代</h2>
 * 红线禁止启动客户端 ⇒ <b>这一屏的观感（字有没有重叠、图标有没有画歪）没有被真机验过</b>。
 * 能机器验的是"每张卡上有什么数据"（服务端那份），见 {@code WS_Q_*} 那一组读数。
 */
public final class ShanhaiRecipeCardWidget extends Widget {

    private static final String PREFIX = "[SHANHAI-EDIT] editor";

    /** 卡片尺寸（面板宽 340 − 左右各 4）。 */
    public static final int CW = ShanhaiRecipeEditorPanel.W - 8;
    public static final int CH = 50;

    /** 图标格（原版槽位 18）。 */
    private static final int ICON = 18;
    private static final int IN_X = 2;
    private static final int OUT_X = 94;
    private static final int ARROW_X = 78;
    private static final int ARROW_W = 12;
    /** 图标行所在的高度。 */
    private static final int ICON_Y = 2;

    /** 文字区（id 行 ＋ 两行数值），左起 2，右到 292 —— 全宽，这样长 id 与长数值都放得下。 */
    private static final int TEXT_X = 2;
    private static final int TEXT_RIGHT = 292;
    private static final int ID_Y = 22;
    private static final int LINE1_Y = 32;
    private static final int LINE2_Y = 41;

    /**
     * 🆕 第 11 刀：第 2 行（总计 EU 那一行）的<b>真实</b>右边界。
     *
     * <p>为什么不是 {@link #TEXT_RIGHT}（292）：那个 292 是为了避让右侧「选中」按钮
     * （x=298..332、<b>y=16..34</b>）而定的一行通宽。而第 2 行在 <b>y=41</b>，已经在按钮下面了
     * ⇒ 它可以从卡片左边一直写到卡片右边缘（卡片宽 {@link #CW}=332，留 2 px ⇒ 330）。
     * 用户报的「这句说明被截断」＝文案本身太长（那句完整说明 ≈ 400+ px），
     * 光靠加宽还是放不下 ⇒ <b>句子也缩短</b>（见 {@link #noteText(Minecraft, String)}）。
     */
    private static final int LINE2_RIGHT = CW - 2;

    /** 右上角电压词：右对齐到这个 x。 */
    private static final int TIER_RIGHT = 292;

    /** 「选中」按钮（贴右侧、纵向居中 —— 位置照 JEI 的 RecipeLayoutWithButtons）。 */
    private static final int BTN_X = 298;
    private static final int BTN_W = 34;
    private static final int BTN_H = 18;
    private static final int BTN_Y = 16;

    // ── 🆕 第 11 刀：图标格上的两个角标（用户点名的 ① 电路号 / ② 催化剂）──
    /** 角标字号（0.75 倍：比数量那个 0.5 倍的字大一档，能读出两位数）。 */
    private static final float BADGE_SCALE = 0.75f;
    /** 角标底色（半透明黑，压在物品图标上也能读）。 */
    private static final int BADGE_BG = 0xC0000000;
    /** 电路号角标的字色（金）。 */
    private static final int BADGE_CIRCUIT_FG = 0xFFFFD040;
    /** 催化剂角标的字色与描边色（青）。 */
    private static final int BADGE_CATALYST_FG = 0xFF3AD8E0;
    /** 催化剂那一格的描边色（把"不消耗"这件事在整格上标出来 —— 用户说的"一种描边"）。 */
    private static final int SLOT_CATALYST = 0xFF3AD8E0;

    /** 已经往日志里量过的说明行（只在第一次画卡时量一次）。 */
    private static final java.util.Set<String> NOTE_MEASURED = new java.util.HashSet<>();

    private static final int CARD_BG = 0xFF202020;
    private static final int CARD_EDGE = 0xFF4A4A4A;
    private static final int CARD_EDGE_SEL = 0xFFE0B000;
    private static final int SLOT_ITEM = 0xFF8B8B8B;
    private static final int SLOT_FLUID = 0xFF3A7BD5;
    private static final int SLOT_BG = 0xFF373737;
    private static final int BTN_FACE = 0xFF5A5A5A;
    private static final int BTN_FACE_HOVER = 0xFF8A8A8A;
    private static final int ARROW_GRAY = 0xFF9A9A9A;

    private static final int ACT_SELECT = 1;

    private final ShanhaiRecipeEditorWorkspace session;
    /** 本控件是这一页的第几张卡（0..CARDS_PER_PAGE-1）。 */
    private final int slot;

    public ShanhaiRecipeCardWidget(ShanhaiRecipeEditorWorkspace session, int slot, int x, int y) {
        super(x, y, CW, CH);
        this.session = session;
        this.slot = slot;
    }

    private ShanhaiRecipeQuery.Card card() {
        return session == null ? null : session.cardAt(slot);
    }

    // ------------------------------------------------------------------ 渲染

    @Override
    public void drawInBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
        final int x = getPositionX();
        final int y = getPositionY();
        final ShanhaiRecipeQuery.Card c = card();
        final boolean sel = c != null && c.selected();
        graphics.fill(x, y, x + CW, y + CH, sel ? CARD_EDGE_SEL : CARD_EDGE);
        graphics.fill(x + 1, y + 1, x + CW - 1, y + CH - 1, CARD_BG);
        if (c == null) {
            return;
        }
        final Minecraft mc = Minecraft.getInstance();

        // ── 图标行：输入 → 输出（照 JEI 配方页的排布）──
        drawChips(graphics, mc, c.ins(), x + IN_X, y + ICON_Y);
        int used = Math.min(c.ins().size(), ShanhaiRecipeQuery.MAX_CHIPS);
        if (c.insMore() > 0) {
            graphics.drawString(mc.font, "§7+" + c.insMore(),
                    x + IN_X + used * ICON, y + ICON_Y + 5, 0xFFFFFF, false);
        }
        drawArrow(graphics, x + ARROW_X, y + ICON_Y, ICON);
        drawChips(graphics, mc, c.outs(), x + OUT_X, y + ICON_Y);
        used = Math.min(c.outs().size(), ShanhaiRecipeQuery.MAX_CHIPS);
        if (c.outsMore() > 0) {
            graphics.drawString(mc.font, "§7+" + c.outsMore(),
                    x + OUT_X + used * ICON, y + ICON_Y + 5, 0xFFFFFF, false);
        }

        // ── 右上角：带颜色的电压词（GTValues.VNF，MV 就是青色的那个）──
        final int tier = displayTier(c.tierIndex(), c.eut());
        final String tierWord = tierName(tier);
        final int tw = mc.font.width(tierWord);
        graphics.drawString(mc.font, tierWord, x + TIER_RIGHT - tw, y + 2, 0xFFFFFF, false);

        // ── 配方 id（用户点名「同时显示配方id在那上面」）──
        graphics.drawString(mc.font, "§8" + c.typeName() + " §7· §f"
                + clip(c.id() == null ? "?" : c.id().toString(), TEXT_RIGHT - TEXT_X),
                x + TEXT_X, y + ID_Y, 0xFFFFFF, false);

        // ── 第 1 行：两条时间（原始 ＋ 实际）。用户 2026-10-05 的口径：
        //      「第2屏与第3屏都显示两条时间」，第 2 屏两条都只读。──
        graphics.drawString(mc.font, "§7原始耗时 §f" + c.original() + " §7t"
                        + " §8· §7实际耗时 §f" + c.duration() + " §7t"
                        + (c.original() == c.duration() ? " §8(这个类型没被换算)"
                                : " §8(= 原始 × " + ratioText(c.duration(), c.original()) + ")"),
                x + TEXT_X, y + LINE1_Y, 0xFFFFFF, false);

        // ── 第 2 行：总计 EU ＋ 耗能功率（EU/t 与电流）。
        //     文案走 GT 自己的语言键（getRecipeParaText 用的就是这三个），这样中文与 JEI 里逐字一致。
        //     ⚠️ 电流是【算出来的】：eut ÷ 该档电压，两位小数 —— 与用户截图里的 0.94A 同口径。
        //     🔴 第 11 刀：原来这句后面硬接一整句「这个数是从【活的配方】上读的（＝实际生效值）」，
        //        它自己就 ≈ 210 px，加上前面的数值 ≈ 400+ px ⇒ 挤出卡片右边被截断（用户报的 ④）。
        //        现在按像素挑"放得下的那一档最短说法"，完整句子放进 tooltip 里（见 drawInForeground）。──
        final long total = c.duration() * c.eut();
        final String euMain = "§7" + tr("gtceu.recipe.total", fmt(total))
                + " §8· §7" + tr("gtceu.recipe.eu", fmt(c.eut()))
                + " §8(" + ampsText(c.eut(), displayTier(c.tierIndex(), c.eut())) + "A)";
        final String euNote = noteText(mc, euMain);
        graphics.drawString(mc.font, euMain + euNote, x + TEXT_X, y + LINE2_Y, 0xFFFFFF, false);

        // ── 右侧那一个「选中」按钮（JEI 那两个小按钮在本工程里被它替代）──
        final boolean over = inBox(mouseX, mouseY, x + BTN_X, y + BTN_Y, BTN_W, BTN_H);
        button(graphics, mc, x + BTN_X, y + BTN_Y, BTN_W, BTN_H, "§a选中", over);
    }

    @Override
    public void drawInForeground(GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
        final ShanhaiRecipeQuery.Card c = card();
        if (c != null && isMouseOverElement(mouseX, mouseY)) {
            tooltipTexts.clear();
            tooltipTexts.add(Component.literal("§f" + c.typeName() + " §8· §7" + c.id()));
            tooltipTexts.add(Component.literal("§7原始耗时 §f" + c.original() + " t"
                    + " §8· §7实际耗时 §f" + c.duration() + " t"));
            tooltipTexts.add(Component.literal("§7总计 §f" + (c.duration() * c.eut()) + " EU"
                    + " §8· §7耗能功率 §f" + c.eut() + " EU/t §8("
                    + ampsText(c.eut(), displayTier(c.tierIndex(), c.eut())) + "A)"));
            // 🔴 第 11 刀：卡片上那句说明被截断（用户报的 ④）⇒ 完整那句话放在这里，一个字不少。
            tooltipTexts.add(Component.literal("§8这个数是从【活的配方】上读的（＝实际生效值），不是源声明值"));
            tooltipTexts.add(Component.literal("§7电压档位 §f"
                    + tierName(displayTier(c.tierIndex(), c.eut()))));
            // 🔴 第 11 刀：两个角标的图例（只在真的有这类格子时才出现 —— 不画一个用不上的说明）
            final int circuits = countCircuits(c);
            if (circuits > 0) {
                tooltipTexts.add(Component.literal("§e左上角金色数字§7 = 编程电路号（这张卡上有 §f"
                        + circuits + "§7 格）"));
            }
            final int cats = countCatalysts(c);
            if (cats > 0) {
                tooltipTexts.add(Component.literal("§b青色描边 ＋ 左下角「催」§7 = 不消耗的催化剂（这张卡上有 §f"
                        + cats + "§7 格）"));
            }
            tooltipTexts.add(Component.literal("§a点「选中」或整张卡片 = 进第三屏编辑这条配方"));
        }
        super.drawInForeground(graphics, mouseX, mouseY, partialTicks);
    }

    /** 这张卡上有几格带电路号。 */
    private static int countCircuits(ShanhaiRecipeQuery.Card c) {
        int n = 0;
        for (ShanhaiRecipeQuery.Chip ch : c.ins()) {
            if (ch.circuit() >= 0) {
                n++;
            }
        }
        return n;
    }

    /** 这张卡上有几格是催化剂。 */
    private static int countCatalysts(ShanhaiRecipeQuery.Card c) {
        int n = 0;
        for (ShanhaiRecipeQuery.Chip ch : c.ins()) {
            if (ch.catalyst()) {
                n++;
            }
        }
        return n;
    }

    /**
     * 🆕 第 11 刀（用户报的 ④）：<b>挑一句放得下的说明</b>。
     *
     * <p>候选从"最长最完整"到"最短"排；逐个量「主体 ＋ 候选」的像素宽，
     * 取第一个 ≤ {@link #LINE2_RIGHT} 的。都放不下就返回空串（<b>宁可不写，也不写一半</b>
     * —— 半句话读起来是错的）。完整那句话在 tooltip 里，一个字都不少。
     *
     * <p>量宽用的是 {@link ShanhaiRecipeEditorSession#textPx}（半角 6 / 全角 9 的同一把尺子）；
     * 第一次画卡时另外打一行 {@code font.width} 的<b>真实</b>读数进日志，供对账。
     */
    private static String noteText(Minecraft mc, String main) {
        final int budget = LINE2_RIGHT - TEXT_X;
        final String[] candidates = {
                " §8· §7这个数是从【活的配方】上读的（＝实际生效值）",
                " §8· §7活配方实测值",
                " §8· §7实测值",
                " §8· §7实测",
                "",
        };
        String picked = "";
        for (String cand : candidates) {
            if (ShanhaiRecipeEditorSession.textPx(main + cand) <= budget) {
                picked = cand;
                break;
            }
        }
        measureNoteOnce(mc, main, picked, budget);
        return picked;
    }

    /** 把「可用宽 / 估算宽 / font.width 真实宽」量一次并写进日志（只写一次，免得每帧刷屏）。 */
    private static void measureNoteOnce(Minecraft mc, String main, String picked, int budget) {
        final String key = picked;
        if (!NOTE_MEASURED.add(key)) {
            return;
        }
        int real = -1;
        try {
            real = mc.font.width(ShanhaiRecipeEditorSession.stripColor(main + picked));
        } catch (Throwable ignored) {
            real = -1;
        }
        ShanhaiMod.LOGGER.info("{} card_note budget_px={} estimate_px={} font_width={} note_px={} note='{}'",
                PREFIX, budget, ShanhaiRecipeEditorSession.textPx(main + picked), real,
                ShanhaiRecipeEditorSession.textPx(picked),
                ShanhaiRecipeEditorSession.stripColor(picked));
    }

    /**
     * 一排图标（超过 {@link ShanhaiRecipeQuery#MAX_CHIPS} 个的部分由 {@code +N} 表示）。
     *
     * <h2>🔴 第 11 刀：两个角标（用户点名的 ① 电路号 / ② 催化剂）</h2>
     * <pre>
     *   ① 电路号：{@code chip.circuit() >= 0} ⇒ 在这一格【左上角】画一个深底金字角标
     *      （例：{@code 3}）。⚠️ 角标位置三处都空着：左上＝电路、左下＝催化剂、右下＝数量，
     *      三个互不重叠（18 px 的格子里：角标高 8 px，字宽 0.75 倍）。
     *   ② 催化剂：整个格子换成【青色描边】＋【左下角一个青色的「催」】
     *      —— 描边让"哪一格"一眼可见，那个字让"是什么意思"不用查图例。
     * </pre>
     * ⚠️ 上一刀的教训：这两件事的原始数据（NBT 里的电路号、{@code chance==0}）
     * <b>在造卡片那一步就被丢掉了</b> ⇒ 传不到这里。现在它们在
     * {@link ShanhaiRecipeQuery#chipOf} 里就地取好（服务端），随 {@code Chip} 推下来。
     */
    private void drawChips(GuiGraphics g, Minecraft mc, java.util.List<ShanhaiRecipeQuery.Chip> chips,
                           int x, int y) {
        if (chips == null) {
            return;
        }
        for (int i = 0; i < chips.size() && i < ShanhaiRecipeQuery.MAX_CHIPS; i++) {
            final ShanhaiRecipeQuery.Chip chip = chips.get(i);
            final int cx = x + i * ICON;
            // 催化剂 ⇒ 描边换青色（"消耗的"与"催化剂"在这一眼上就不一样了）
            g.fill(cx, y, cx + ICON, y + ICON,
                    chip.catalyst() ? SLOT_CATALYST : (chip.item() ? SLOT_ITEM : SLOT_FLUID));
            g.fill(cx + 1, y + 1, cx + ICON - 1, y + ICON - 1, SLOT_BG);
            if (chip.item()) {
                final ItemStack stack = ShanhaiRecipeQuery.itemOf(chip);
                if (stack != null) {
                    g.renderItem(stack, cx + 1, y + 1);
                    smallText(g, mc, Integer.toString(chip.count()), cx + ICON - 2, y + ICON - 1, 0xFFFFFFFF);
                } else {
                    // 注册表里查不到 ⇒ 画一个红底方块占位，绝不画成一个错的图标
                    g.fill(cx + 2, y + 2, cx + ICON - 2, y + ICON - 2, 0xFF7A3030);
                }
            } else {
                final com.lowdragmc.lowdraglib.side.fluid.FluidStack fs = ShanhaiRecipeQuery.fluidOf(chip);
                if (fs != null) {
                    drawFluidSprite(g, fs, cx + 1, y + 1);
                    smallText(g, mc, shortMb(chip.count()), cx + ICON - 2, y + ICON - 1, 0xFFFFFFFF);
                } else {
                    g.fill(cx + 2, y + 2, cx + ICON - 2, y + ICON - 2, 0xFF30507A);
                }
            }
            // ① 电路号（左上角）
            if (chip.circuit() >= 0) {
                badge(g, mc, Integer.toString(chip.circuit()), cx + 1, y, BADGE_CIRCUIT_FG);
            }
            // ② 催化剂（左下角）
            if (chip.catalyst()) {
                badge(g, mc, "催", cx + 1, y + ICON - 9, BADGE_CATALYST_FG);
            }
        }
    }

    /**
     * 一个小角标：深底 ＋ {@link #BADGE_SCALE} 倍的字。
     *
     * @param bx 角标左上角（相对卡片的本地坐标）
     * @param by 角标左上角
     */
    private static void badge(GuiGraphics g, Minecraft mc, String text, int bx, int by, int color) {
        if (text == null || text.isEmpty()) {
            return;
        }
        final int w = mc.font.width(text);
        final int bw = Math.max(5, (int) (w * BADGE_SCALE) + 2);
        g.fill(bx, by, bx + bw, by + 8, BADGE_BG);
        g.pose().pushPose();
        g.pose().translate(bx + 1, by + 1, 300f);
        g.pose().scale(BADGE_SCALE, BADGE_SCALE, 1f);
        g.drawString(mc.font, text, 0, 0, color, true);
        g.pose().popPose();
    }

    /** 一个灰色箭头（输入 → 输出），与 JEI 配方页中间那个箭头同一个位置。 */
    private static void drawArrow(GuiGraphics g, int x, int y, int h) {
        final int my = y + h / 2;
        g.fill(x, my - 1, x + ARROW_W - 5, my + 1, ARROW_GRAY);
        for (int r = 0; r <= 4; r++) {
            g.fill(x + ARROW_W - 5 + r, my - 4 + r, x + ARROW_W - 4 + r, my + 5 - r, ARROW_GRAY);
        }
    }

    /** 「按钮铺满 ＋ 文字居中」的小按钮（LDLib 的 ButtonWidget 没有 setText，所以手画）。 */
    private static void button(GuiGraphics g, Minecraft mc, int x, int y, int w, int h,
                               String text, boolean hover) {
        g.fill(x, y, x + w, y + h, hover ? BTN_FACE_HOVER : BTN_FACE);
        g.fill(x, y, x + w, y + 1, 0xFF2A2A2A);
        g.fill(x, y + h - 1, x + w, y + h, 0xFF2A2A2A);
        g.fill(x, y, x + 1, y + h, 0xFF2A2A2A);
        g.fill(x + w - 1, y, x + w, y + h, 0xFF2A2A2A);
        final String plain = text.replaceAll("§.", "");
        final int tw = mc.font.width(plain);
        g.drawString(mc.font, text, x + Math.max(1, (w - tw) / 2), y + (h - 8) / 2, 0xFFFFFF, false);
    }

    private static boolean inBox(int mx, int my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    /** 0.5 倍小字，右下角对齐。 */
    private static void smallText(GuiGraphics g, Minecraft mc, String text, int ax, int ay, int color) {
        if (text == null || text.isEmpty()) {
            return;
        }
        final float scale = 0.5f;
        final int w = mc.font.width(text);
        g.pose().pushPose();
        g.pose().translate(ax - (int) (w * scale) - 1, ay - 7, 300f);
        g.pose().scale(scale, scale, 1f);
        g.drawString(mc.font, text, 0, 0, color, true);
        g.pose().popPose();
    }

    /**
     * 画流体：<b>still 贴图 ＋ tint</b>（与 {@link ShanhaiIOWidget} 同一个来源与同一条回退：
     * 拿不到贴图就画纯色块，<b>绝不静默画成空白</b>）。
     */
    private static void drawFluidSprite(GuiGraphics g, com.lowdragmc.lowdraglib.side.fluid.FluidStack stack,
                                        int x, int y) {
        net.minecraft.resources.ResourceLocation still = null;
        int tint = 0xFFFFFFFF;
        try {
            final net.minecraftforge.client.extensions.common.IClientFluidTypeExtensions ext =
                    net.minecraftforge.client.extensions.common.IClientFluidTypeExtensions.of(stack.getFluid());
            still = ext.getStillTexture();
            tint = ext.getTintColor();
        } catch (Throwable ignored) {
            still = null;
        }
        boolean drawn = false;
        if (still != null) {
            try {
                final TextureAtlasSprite sprite = Minecraft.getInstance()
                        .getTextureAtlas(InventoryMenu.BLOCK_ATLAS).apply(still);
                if (sprite != null) {
                    RenderSystem.enableBlend();
                    RenderSystem.defaultBlendFunc();
                    RenderSystem.setShaderColor(((tint >> 16) & 0xFF) / 255f,
                            ((tint >> 8) & 0xFF) / 255f, (tint & 0xFF) / 255f, 1f);
                    g.blit(x, y, 0, ICON - 2, ICON - 2, sprite);
                    RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
                    RenderSystem.disableBlend();
                    drawn = true;
                }
            } catch (Throwable ignored) {
                drawn = false;
            }
        }
        if (!drawn) {
            g.fill(x, y, x + ICON - 2, y + ICON - 2, 0xC0000000 | (tint & 0x00FFFFFF));
        }
    }

    /** 一个 lang 键的译文（取不到就把键原样返回 —— 不编一个中文名）。 */
    private static String tr(String key, String arg) {
        try {
            final String s = Component.translatable(key, arg).getString();
            return s == null || s.isEmpty() ? key : com.shanhai.common.text.ShanhaiTextParser.stripStyleCode(s);
        } catch (Throwable t) {
            return key + " " + arg;
        }
    }

    /** {@code 1200 → 1.2k}（与 GT 的 {@code FormattingUtil.formatNumbers} 同一口径的近似实现）。 */
    private static String fmt(long v) {
        final long a = Math.abs(v);
        if (a < 1000L) {
            return Long.toString(v);
        }
        final String[] unit = {"", "k", "M", "G", "T", "P", "E"};
        double d = v;
        int i = 0;
        while (Math.abs(d) >= 1000.0 && i < unit.length - 1) {
            d /= 1000.0;
            i++;
        }
        return String.format(java.util.Locale.ROOT, "%.2f%s", d, unit[i]);
    }

    /** 「实际 ÷ 原始」的比值（两位小数），只用于那句说明。 */
    private static String ratioText(int actual, int original) {
        if (original == 0) {
            return "?";
        }
        return String.format(java.util.Locale.ROOT, "%.3f", (double) actual / (double) original);
    }

    /** 电流 = 耗能 ÷ 该档电压，两位小数（与用户截图里的 {@code 0.94A} 同口径）。 */
    private static String ampsText(long eut, int tier) {
        if (tier < 0) {
            return "?";
        }
        final long v;
        try {
            v = com.gregtechceu.gtceu.api.GTValues.V[tier];
        } catch (Throwable t) {
            return "?";
        }
        if (v <= 0L) {
            return "?";
        }
        return String.format(java.util.Locale.ROOT, "%.2f", (double) eut / (double) v);
    }

    /**
     * 用来显示/算电流的档位。
     *
     * <p>🔴 冒烟第一版读数：{@code gtceu:packer/hay_block} 的 {@code EU/t = 2}，
     * 而 {@link ShanhaiRecipeEditorWorkspace#tierOf} 反查出 {@code tier = -1}
     * （因为 GT 最低一档是 {@code ULV = 8V}）⇒ 卡片上电压词与电流都变成 "?"。
     * 那种配方在真机上是<b>跑在 ULV 机器上</b>的，所以显示时把它归到 ULV 那一档；
     * 电流就按 8V 算（{@code 2 / 8 = 0.25A}）。这是<b>显示口径</b>，
     * 不改任何数据（{@code Card.tierIndex()} 本身仍是 -1）。
     */
    private static int displayTier(int tier, long eut) {
        return tier < 0 && eut > 0 ? 0 : tier;
    }

    /** 电压词（照 GT 的 {@code GTValues.VNF[tier]} —— 它自带颜色码，MV 就是青色）。 */
    private static String tierName(int tier) {
        if (tier < 0) {
            return "?";
        }
        try {
            final String[] vnf = com.gregtechceu.gtceu.api.GTValues.VNF;
            if (tier < vnf.length && vnf[tier] != null) {
                return vnf[tier];
            }
        } catch (Throwable ignored) {
            // 退回纯名字
        }
        try {
            final String[] vn = com.gregtechceu.gtceu.api.GTValues.VN;
            if (tier < vn.length && vn[tier] != null) {
                return vn[tier];
            }
        } catch (Throwable ignored) {
            // 再退回编辑器那张表
        }
        try {
            final String full = ShanhaiRecipeEditorWorkspace.TIER_NAMES[tier];
            final int sp = full.indexOf(' ');
            return sp > 0 ? full.substring(0, sp) : full;
        } catch (Throwable ignored) {
            return "?";
        }
    }

    /** {@code 1000 → 1B}（与 {@link ShanhaiIOWidget} 同一条口径）。 */
    private static String shortMb(long mb) {
        if (mb >= 1000L) {
            final long b = mb / 1000L;
            return b >= 1000L ? (b / 1000L) + "kB" : b + "B";
        }
        return Long.toString(mb);
    }

    /** 按显示宽度裁剪（中文按 2 个单位算），保证这一行不会压到右边那个「选中」按钮。 */
    private static String clip(String s, int maxPx) {
        final int budgetUnits = Math.max(4, maxPx / 6);
        return ShanhaiRecipeEditorSession.clipUnits(s, budgetUnits);
    }

    // ------------------------------------------------------------------ 点击

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (session == null || !isMouseOverElement(mouseX, mouseY)) {
            return false;
        }
        // 🔴 用户口径：「改成一个选中按钮，点了进去就是第三屏编辑」——
        //    所以【整张卡片】与那个按钮是同一个动作，不存在第二个按钮。
        if (isRemote()) {
            writeClientAction(ACT_SELECT, buf -> buf.writeVarInt(slot));
        } else {
            session.cardAction(slot);
        }
        ShanhaiMod.LOGGER.info("{} card_click slot={} card={}", PREFIX, slot,
                card() == null ? "(none)" : card().id());
        return true;
    }

    @Override
    public void handleClientAction(int id, FriendlyByteBuf buffer) {
        if (session == null) {
            return;
        }
        try {
            final int s = buffer.readVarInt();
            final boolean ok = session.cardAction(s);
            ShanhaiMod.LOGGER.info("{} card_action_applied slot={} ok={}", PREFIX, s, ok);
        } catch (Throwable t) {
            ShanhaiMod.LOGGER.error("{} card_action_failed id={} err={}", PREFIX, id, t.toString(), t);
        }
    }
}
