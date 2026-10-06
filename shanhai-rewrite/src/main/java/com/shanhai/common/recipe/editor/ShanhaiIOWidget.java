package com.shanhai.common.recipe.editor;

import com.lowdragmc.lowdraglib.gui.ingredient.IGhostIngredientTarget;
import com.lowdragmc.lowdraglib.gui.ingredient.Target;
import com.lowdragmc.lowdraglib.gui.widget.Widget;
import com.lowdragmc.lowdraglib.side.fluid.FluidStack;
import com.mojang.blaze3d.systems.RenderSystem;
import com.shanhai.ShanhaiMod;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.function.Consumer;

/**
 * 一个 <b>IO 格子</b>（输入物品 ＋ 输入流体 ＋ 输出物品 ＋ 输出流体，格数随配方类型变）。
 *
 * <h2>1. 手势（用户点单的，逐条对应）</h2>
 * <pre>
 *   左键长按从 JEI 拖进来  ⇒ JEI 的 ghost 拖动会找 IGhostIngredientTarget（本类实现它）
 *   右键                  ⇒ 删掉这一格
 *   中键                  ⇒ 打开"这一格"的小窗（数量 / 概率 / 随电压递增 / 是否催化剂）
 * </pre>
 *
 * <h2>2. 🔴 边框四种颜色各是什么（用户 2026-10-05 问过：「这个黄的是没解锁的槽对吧」）</h2>
 * 实测代码（本文件 {@link #drawInBackground}）画的是<b>两种含义叠在一起</b>：
 * <pre>
 *   外框 = 这一格的【种类】  ← 一直在，与"有没有内容"无关
 *        灰 0xFF8B8B8B = 物品格
 *        蓝 0xFF3A7BD5 = 流体格（🆕 2026-10-05 用户点单：「我希望你可以把流体槽的边缘变成蓝的」）
 *   内框 = 这一格此刻的【状态】  ← 只有悬停/选中时才画，画在外框里侧，所以不会把种类色盖掉
 *        金 0xFFFFD400 = 中键点到的那一格（正在改数量的那一格；session.countCell() == 本格下标）
 *        白 0xFFFFFFFF = 鼠标悬停的那一格
 * </pre>
 * ⇒ <b>金黄【不是】"没解锁的槽"</b>：本面板没有"解锁"这个概念（它直接改已经存在的配方）。
 * 这四种颜色的含义同时写在面板底部那行图例上（{@code ShanhaiRecipeEditorPanel#buildEditStage} 的 legend 行），
 * 不必靠猜。
 *
 * <h2>3. 🔴 流体格子画得像 JEI（用户 2026-10-05 点单）</h2>
 * 原话：「你的流体渲染有些问题啊，为什么不能显示的和 jei 的流体一样」「而不是在流体那个物品上面
 * 直接显示名称都把图标覆盖住了」。
 * ⇒ 现在：<b>画流体自己的 still 贴图 ＋ tint 颜色</b>（{@code IClientFluidTypeExtensions#getStillTexture/getTintColor}），
 * 数量画在右下角；<b>名字一个像素都不画在格子上</b>，改成"指到哪一格就在格子下面那一行显示"（见 §4）。
 *
 * <h2>4. 🔴 名字与角标的位置纪律</h2>
 * <pre>
 *   格子内：只放"图标 ＋ 数量(右下) ＋ 概率%(左下) ＋ 不消耗角标(左上)"
 *   格子外：面板下面那一行显示"中文名 (id) ×数量"（鼠标指到哪一格就显示哪一格）
 * </pre>
 * 依据 = 用户原话「中间也需要显示物品的名称（id+中文名），而不是在流体那个物品上面直接显示名称都把图标覆盖住了」。
 *
 * <h2>5. ⚠️ 如实交代：拖动的手感没被验过</h2>
 * 红线禁止启动客户端 ⇒ 拖动只能靠"无头专服 ＋ JEI 落点记账"验（实例日志 {@code drop_accepts=7}）。
 * 本轮改的是【渲染】与【小窗】，没有动 §1 那条手势链。
 */
public final class ShanhaiIOWidget extends Widget implements IGhostIngredientTarget {

    private static final String PREFIX = "[SHANHAI-EDIT] editor";

    /** 格子边长（原版槽位就是 18）。 */
    public static final int CELL = 18;

    /** 外框：物品格（灰）。 */
    public static final int FRAME_ITEM = 0xFF8B8B8B;
    /** 外框：流体格（蓝）。 */
    public static final int FRAME_FLUID = 0xFF3A7BD5;
    /** 内框：正在改数量的那一格（金）。 */
    public static final int FRAME_SELECTED = 0xFFFFD400;
    /** 内框：鼠标悬停（白）。 */
    public static final int FRAME_HOVER = 0xFFFFFFFF;
    /** 格子底色。 */
    private static final int CELL_BG = 0xFF373737;
    /** 概率文字的黄色（JEI 里"10.00%"就是黄的）。 */
    private static final int CHANCE_YELLOW = 0xFFFFD400;
    /** 催化剂角标的金色。 */
    private static final int CATALYST_GOLD = 0xFFFFC400;

    /** 客户端动作号。 */
    private static final int ACT_OFFER = 1;
    private static final int ACT_CLEAR = 2;
    private static final int ACT_OPEN_COUNT = 3;

    private final ShanhaiRecipeEditorWorkspace session;
    /**
     * 本控件对应哪一个格子。<b>用供应商而不是定值</b>：格子数随配方类型变化、又按页翻，
     * 而 LDLib 的控件表在 UI 建好之后不能再改 ⇒ 控件数固定、下标动态算。
     */
    private final java.util.function.IntSupplier indexSupplier;
    private final int fixedIndex;

    /** 🆕 "指到哪一格"那一行的落点（由面板装进来；客户端渲染时才被调用）。 */
    private Consumer<String> hoverSink;

    public ShanhaiIOWidget(ShanhaiRecipeEditorWorkspace session, int index, int x, int y) {
        this(session, () -> index, x, y);
    }

    public ShanhaiIOWidget(ShanhaiRecipeEditorWorkspace session, java.util.function.IntSupplier indexSupplier, int x, int y) {
        super(x, y, CELL, CELL);
        this.session = session;
        this.indexSupplier = indexSupplier;
        this.fixedIndex = -1;
    }

    /** 面板把"格子下面那一行"的 setter 装进来。 */
    public void setHoverSink(Consumer<String> sink) {
        this.hoverSink = sink;
    }

    /** 本控件此刻代表的下标（-1 = 这一页没这一格，画成空格子且不收东西）。 */
    private int index() {
        try {
            return indexSupplier == null ? fixedIndex : indexSupplier.getAsInt();
        } catch (Throwable t) {
            return -1;
        }
    }

    private ShanhaiIoTable.Cell cell() {
        final int i = index();
        return session == null || i < 0 ? null : session.cell(i);
    }

    /** 这一格是不是流体格（空格子也要判得出来 —— 外框颜色靠它）。 */
    private boolean fluidKind() {
        final ShanhaiIoTable.Cell c = cell();
        if (c != null) {
            return !c.itemKind;
        }
        // 格子对象取不到（下标越界）⇒ 按版面位置判：输入栏的格子 0..itemIn-1 是物品、其余是流体
        final ShanhaiIoTable t = session == null ? null : session.ioTable();
        final int i = index();
        if (t == null || i < 0) {
            return false;
        }
        final int inSection = t.inSection();
        if (i < inSection) {
            return i >= t.itemIn();
        }
        return (i - inSection) >= t.itemOut();
    }

    // ------------------------------------------------------------------ 渲染

    @Override
    public void drawInBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
        if (session == null || !session.stageIsEdit()) {
            return;         // 列表那两屏不画格子（也不吃不响应鼠标，见 mouseClicked）
        }
        final int x = getPositionX();
        final int y = getPositionY();
        final boolean hovered = mouseX >= x && mouseX < x + CELL && mouseY >= y && mouseY < y + CELL;
        final boolean selected = session.countCell() == index();

        // ── 外框 = 种类（物品灰 / 流体蓝）────────────────────────────────────
        graphics.fill(x, y, x + CELL, y + CELL, fluidKind() ? FRAME_FLUID : FRAME_ITEM);
        graphics.fill(x + 1, y + 1, x + CELL - 1, y + CELL - 1, CELL_BG);
        // ── 内框 = 状态（金＝正在改数量 / 白＝悬停），画在外框【里侧】，不盖掉种类色 ──
        if (selected) {
            ring(graphics, x + 1, y + 1, CELL - 2, FRAME_SELECTED);
        } else if (hovered) {
            ring(graphics, x + 1, y + 1, CELL - 2, FRAME_HOVER);
        }

        final ShanhaiIoTable.Cell c = cell();
        if (c == null || c.empty()) {
            if (hovered && hoverSink != null) {
                hoverSink.accept("§8(空" + (fluidKind() ? "流体" : "物品") + "格)");
            }
            return;
        }
        final Minecraft mc = Minecraft.getInstance();
        if (c.itemKind) {
            graphics.renderItem(c.item, x + 1, y + 1);
            graphics.renderItemDecorations(mc.font, c.item, x + 1, y + 1);
        } else {
            drawFluidSprite(graphics, c.fluid, x + 1, y + 1);
            // 数量只在右下角（0.5 倍小字），不写名字 —— 名字在格子外面那一行
            smallText(graphics, mc, shortAmount(c.fluid.getAmount()), x + CELL - 1, y + CELL - 1, 0xFFFFFFFF, true);
        }
        // ── 概率：左下角黄字（JEI 里就是黄的）────────────────────────────────
        final String pct = c.chanceText();
        if (!pct.isEmpty()) {
            smallText(graphics, mc, trimPct(pct), x + 1, y + CELL - 1, CHANCE_YELLOW, false);
        }
        // ── 催化剂（不消耗）：左上角金角标 ──────────────────────────────────
        if (c.notConsumable) {
            catalystBadge(graphics, x + 1, y + 1);
        }
        if (hovered && hoverSink != null) {
            hoverSink.accept(describeCell(c));
        }
    }

    /** 外框里侧画一圈 1px 的边（不盖掉外面的种类色）。 */
    private static void ring(GuiGraphics g, int x, int y, int size, int color) {
        g.fill(x, y, x + size, y + 1, color);
        g.fill(x, y + size - 1, x + size, y + size, color);
        g.fill(x, y, x + 1, y + size, color);
        g.fill(x + size - 1, y, x + size, y + size, color);
    }

    /**
     * 画流体：<b>still 贴图 ＋ tint</b>，与 JEI 的流体图标同一个来源。
     *
     * <p>拿不到贴图（没装渲染扩展 / 该流体没有 still 贴图）时退回"纯色块"，
     * <b>绝不静默画成空白</b>。
     */
    static void drawFluidSprite(GuiGraphics g, FluidStack stack, int x, int y) {
        ResourceLocation still = null;
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
                    g.blit(x, y, 0, CELL - 2, CELL - 2, sprite);
                    RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
                    RenderSystem.disableBlend();
                    drawn = true;
                }
            } catch (Throwable ignored) {
                drawn = false;
            }
        }
        if (!drawn) {
            g.fill(x, y, x + CELL - 2, y + CELL - 2, 0xC0000000 | (tint & 0x00FFFFFF));
        }
    }

    /**
     * 0.5 倍小字。
     *
     * @param alignRight true = 文本右边缘贴 {@code (ax, ay)}；false = 左边缘贴
     */
    static void smallText(GuiGraphics g, Minecraft mc, String text, int ax, int ay,
                          int color, boolean alignRight) {
        if (text == null || text.isEmpty()) {
            return;
        }
        final float scale = 0.5f;
        final int w = mc.font.width(text);
        final int dx = alignRight ? ax - (int) (w * scale) - 1 : ax;
        g.pose().pushPose();
        g.pose().translate(dx, ay - 7, 300f);
        g.pose().scale(scale, scale, 1f);
        g.drawString(mc.font, text, 0, 0, color, true);
        g.pose().popPose();
    }

    /** 格子里塞不下"10.00%"（18px），格子上只留整数百分比；完整值在下面那行与 tooltip 里。 */
    private static String trimPct(String pct) {
        final int dot = pct.indexOf('.');
        return dot > 0 ? pct.substring(0, dot) + "%" : pct;
    }

    /**
     * 催化剂角标（左上角的小三角）。
     *
     * <p>⚠️ 如实交代：JEI 那个"不消耗"标记用的是 JEI 自己的贴图（本工程在 JEI 15.49 的 jar 里
     * 只找到 {@code gui/catalyst_tab.png} 与 {@code gui/recipe_catalyst_slot_background.png}，
     * 没有那个"不消耗"小图标），所以这里画的是一个<b>同位置的等价角标</b>（左上角金色三角），
     * 完整的字面说明在"指到哪一格"那一行与 tooltip 里（{@code 催化剂·不消耗}）。
     */
    private static void catalystBadge(GuiGraphics g, int x, int y) {
        final int s = 6;
        for (int r = 0; r < s; r++) {
            g.fill(x, y + r, x + (s - r), y + r + 1, CATALYST_GOLD);
        }
        g.fill(x, y, x + 1, y + s, 0xFF000000);
    }

    /** 格子外面那一行要显示的字（用户点名要 id ＋ 中文名）。 */
    private String describeCell(ShanhaiIoTable.Cell c) {
        final StringBuilder sb = new StringBuilder();
        if (c.itemKind) {
            final ResourceLocation id = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(c.item.getItem());
            sb.append("§f").append(displayName(c.item)).append(" §8(").append(id).append(")")
                    .append(" §7×").append(c.item.getCount());
        } else {
            final ResourceLocation id = net.minecraft.core.registries.BuiltInRegistries.FLUID.getKey(c.fluid.getFluid());
            sb.append("§b").append(com.shanhai.common.text.ShanhaiTextParser
                            .stripStyleCode(c.fluid.getDisplayName().getString()))
                    .append(" §8(").append(id).append(")")
                    .append(" §7").append(c.fluid.getAmount()).append("mB");
        }
        if (c.notConsumable) {
            sb.append(" §6· 催化剂(不消耗)");
        }
        final String pct = c.chanceText();
        if (!pct.isEmpty()) {
            sb.append(" §e· 概率 ").append(pct);
        }
        if (c.tierChanceBoost > 0) {
            sb.append(String.format(java.util.Locale.ROOT, " §e· 随电压 +%.2f%%/档",
                    (double) c.tierChanceBoost * 100.0 / 10000.0));
        }
        sb.append(" §8· 第 ").append(index()).append(" 格");
        return sb.toString();
    }

    private static String displayName(ItemStack stack) {
        try {
            return com.shanhai.common.text.ShanhaiTextParser
                    .stripStyleCode(stack.getHoverName().getString());
        } catch (Throwable t) {
            return stack.getHoverName().getString();
        }
    }

    /** hover 时把 tooltip 刷成本格的真实内容（LDLib 画 tooltip 时会读这个 list）。 */
    @Override
    public void drawInForeground(GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
        if (session != null && session.stageIsEdit() && isMouseOverElement(mouseX, mouseY)) {
            final ShanhaiIoTable.Cell c = cell();
            tooltipTexts.clear();
            if (c != null && !c.empty()) {
                tooltipTexts.add(Component.literal(describeCell(c)));
                tooltipTexts.add(Component.literal("§8外框：§7灰§8=物品格 · §9蓝§8=流体格"));
                tooltipTexts.add(Component.literal("§8内框：§e金§8=正在改数量的那一格 · §f白§8=鼠标悬停"));
                tooltipTexts.add(Component.literal("§8左键从 JEI 拖入 · 右键删 · 中键改数量/概率/催化剂"));
            }
        }
        super.drawInForeground(graphics, mouseX, mouseY, partialTicks);
    }

    /** {@code 1000 → 1B}、{@code 144 → 144}（只用于格子角上的小字）。 */
    static String shortAmount(long mb) {
        if (mb >= 1000L) {
            final long b = mb / 1000L;
            return b >= 1000L ? (b / 1000L) + "kB" : b + "B";
        }
        return Long.toString(mb);
    }

    // ------------------------------------------------------------------ 鼠标

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (session == null || !session.stageIsEdit()) {
            return false;
        }
        if (!isMouseOverElement(mouseX, mouseY)) {
            return false;
        }
        if (button == 1) {              // 右键 = 删掉这一格
            if (isRemote()) {
                session.clearCell(index());                     // 乐观显示（服务端会推回权威值）
                writeClientAction(ACT_CLEAR, buf -> buf.writeVarInt(index()));
            } else {
                session.clearCell(index());
            }
            return true;
        }
        if (button == 2) {              // 中键 = 改这一格（数量 / 概率 / 递增 / 催化剂）
            if (isRemote()) {
                session.openCountEditor(index());
                writeClientAction(ACT_OPEN_COUNT, buf -> buf.writeVarInt(index()));
            } else {
                session.openCountEditor(index());
            }
            return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ JEI 拖动落点

    @Override
    public List<Target> getPhantomTargets(Object ingredient) {
        if (session == null) {
            return List.of();
        }
        // 🔴 2026-10-05：这里【不再】按 stageIsEdit() 提前返回。
        //    理由（JEI 字节码取证，GhostIngredientDragManager#handleClickGhostIngredient 偏移 60-116）：
        //        targets = handler.getTargetsTyped(screen, ingredient, /*doStart=*/true);
        //        if (targets.isEmpty()) return false;      ← 拖动【根本不开始】
        //    而 LDLib 的 ModularUIJeiHandler#getTargetsTyped 在 mainGroup 的落点为空时返回 emptyList。
        //    ⇒ 任何"这一帧不该收"的提前返回，都会让用户看到「右边 JEI 里的东西根本拖不动」。
        //    落点是否【生效】改由 accept() 里的屏判断负责（收不了会打一行 drop_rejected，不静默）。
        final ShanhaiIoTable.Cell c = cell();
        if (c == null) {
            return List.of();
        }
        final Object accepted = normalize(ingredient, c.itemKind);
        if (accepted == null) {
            return List.of();       // 类型不匹配（例如往只有物品格的屏上拖流体）⇒ 不留落点
        }
        final ShanhaiIOWidget self = this;
        final Target target = new Target() {
            @Override
            public net.minecraft.client.renderer.Rect2i getArea() {
                // ⚠️ 口径与 LDLib 自己的 PhantomSlotWidget#getPhantomTargets 逐字一致：
                //    它也是 `new Rect2i(getPositionX(), getPositionY(), w, h)`（= toRectangleBox()）。
                //    JEI 的 JEITarget.getArea() 是【原样透传】我们的 Rect2i（字节码：直接 aload/getfield/areturn），
                //    所以这里必须是"控件自己的矩形"，不能自作主张加偏移。
                return new net.minecraft.client.renderer.Rect2i(
                        self.getPositionX(), self.getPositionY(), CELL, CELL);
            }

            @Override
            public void accept(Object dropped) {
                final Object v = normalize(dropped, c.itemKind);
                if (v == null) {
                    ShanhaiDragStats.dropRejected("ingredient_kind_mismatch");
                    return;
                }
                if (!session.stageIsEdit()) {
                    // 收得下但不该收（还在列表屏）—— 明确记账，不许静默
                    ShanhaiDragStats.dropRejected("not_edit_stage");
                    return;
                }
                ShanhaiDragStats.dropAccepted();
                // 乐观：先改客户端这份（立刻看得见），再把动作发给服务端（权威）
                session.offerIngredient(index(), v);
                final int idxForAction = index();
                self.writeClientAction(ACT_OFFER, buf -> {
                    buf.writeVarInt(idxForAction);
                    if (v instanceof ItemStack stack) {
                        buf.writeBoolean(true);
                        buf.writeItem(stack);
                    } else {
                        buf.writeBoolean(false);
                        ((FluidStack) v).writeToBuf(buf);
                    }
                });
                ShanhaiMod.LOGGER.info("{} io_drag_offer cell={} itemKind={} value={} totals({})",
                        PREFIX, idxForAction, c.itemKind, describe(v), ShanhaiDragStats.statsLine());
            }
        };
        ShanhaiDragStats.phantomCall(1);
        return List.of(target);
    }

    /**
     * JEI/LDLib 递过来的东西 ⇒ 我们认的那两种之一（物品栈 / 流体栈）。
     *
     * <h4>🔴 2026-10-05 复查：这一条就是"拖不动 + 放进去没反应"的根因</h4>
     * LDLib 的 {@code ModularUIJeiHandler#getTargetsTyped} 传给
     * {@code WidgetGroup.getPhantomTargets(Object)} 的<b>不是</b>裸的物品栈，而是
     * {@code mezz.jei.api.ingredients.ITypedIngredient}（字节码：{@code aload_2 → getPhantomTargets}，
     * 中间没有任何 {@code getIngredient()} 调用）。LDLib 自己的落点也确实是按这个口径收的：
     * {@code PhantomSlotWidget#getPhantomTargets} 里第一件事就是
     * {@code if (ingredient instanceof ITypedIngredient) ingredient = ((ITypedIngredient) ingredient).getItemStack().orElse(EMPTY);}
     * <p>老代码只认裸 {@code ItemStack} / {@code FluidStack} ⇒ 每一个格子都返回空 ⇒
     * <b>整个面板连一个落点都没有</b> ⇒ JEI 连拖都不开始（见 {@link #getPhantomTargets} 里的取证），
     * 而 {@code PhantomFluidWidget.checkJEIIngredient} 也救不了：它的实现只认
     * {@code net.minecraftforge.fluids.FluidStack}（字节码 {@code instanceof net/minecraftforge/fluids/FluidStack}），
     * 对 {@code ITypedIngredient} 原样返回。
     * <p>现场读数支持这条：用户实例日志 {@code io_drag_offer=0}、{@code io_offer_applied=0}，
     * 而同一次会话 {@code io_clear_applied=1}（右键删格子是通的）。
     */
    static Object normalize(Object ingredient, boolean itemKind) {
        // ① JEI 的标准形状：先拆 ITypedIngredient（这是 LDLib 实际递进来的那个）
        final Object unwrapped = unwrapTyped(ingredient);
        if (itemKind) {
            if (unwrapped instanceof ItemStack stack && !stack.isEmpty()) {
                return stack;
            }
            if (unwrapped instanceof net.minecraft.world.item.Item item) {
                return new ItemStack(item);
            }
            return null;
        }
        if (unwrapped instanceof FluidStack fluid && !fluid.isEmpty()) {
            return fluid;
        }
        // ② Forge 的 FluidStack ⇒ LDLib 的 FluidStack（LDLib 自己那条转换，不自己拼）
        final Object wrapped = com.lowdragmc.lowdraglib.gui.widget.PhantomFluidWidget.checkJEIIngredient(unwrapped);
        return wrapped instanceof FluidStack fluid && !fluid.isEmpty() ? fluid : null;
    }

    /**
     * 🆕 2026-10-06（第 14 刀）：把 JEI 递进来的一坨东西剥成<b>物品栈或流体栈</b>（两样都收）。
     *
     * <p>存在的理由：第一面那个查询框原先只认物品（用户原话「<b>无法拖动流体到查询物品框中</b>」），
     * 而"JEI 的壳怎么剥 + Forge 流体怎么转成 LDLib 流体"这件事在本类里<b>已经验证过一遍</b>
     * （IO 格子那条链，见上面 {@link #normalize} 的整段取证）⇒ 直接复用，不另写一份
     * （本工程的纪律：已有且已验证的转换不许重写）。
     *
     * @return {@code ItemStack} 或 {@code FluidStack}；两样都不是 ⇒ {@code null}
     */
    static Object normalizeAny(Object ingredient) {
        final Object item = normalize(ingredient, true);
        if (item != null) {
            return item;
        }
        return normalize(ingredient, false);
    }

    /** {@code ITypedIngredient} ⇒ 它里面那个原料对象；不是的话原样返回。 */
    private static Object unwrapTyped(Object ingredient) {
        if (!(ingredient instanceof mezz.jei.api.ingredients.ITypedIngredient<?> typed)) {
            return ingredient;
        }
        try {
            final net.minecraft.world.item.ItemStack stack = typed.getItemStack().orElse(ItemStack.EMPTY);
            if (!stack.isEmpty()) {
                return stack;
            }
        } catch (Throwable ignored) {
            // 不是物品型 ⇒ 继续按流体型试
        }
        try {
            final net.minecraftforge.fluids.FluidStack forge =
                    typed.getIngredient(mezz.jei.api.forge.ForgeTypes.FLUID_STACK).orElse(null);
            if (forge != null && !forge.isEmpty()) {
                return forge;
            }
        } catch (Throwable ignored) {
            // 不是流体型 ⇒ 返回原始对象，交给上游判
        }
        return ingredient;
    }

    private static String describe(Object v) {
        if (v instanceof ItemStack stack) {
            return net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem())
                    + " x" + stack.getCount();
        }
        if (v instanceof FluidStack fluid) {
            return net.minecraft.core.registries.BuiltInRegistries.FLUID.getKey(fluid.getFluid())
                    + " " + fluid.getAmount() + "mB";
        }
        return String.valueOf(v);
    }

    // ------------------------------------------------------------------ 服务端收动作

    @Override
    public void handleClientAction(int id, FriendlyByteBuf buffer) {
        if (session == null) {
            return;
        }
        try {
            switch (id) {
                case ACT_OFFER -> {
                    final int idx = buffer.readVarInt();
                    final Object v;
                    if (buffer.readBoolean()) {
                        v = buffer.readItem();
                    } else {
                        v = FluidStack.readFromBuf(buffer);
                    }
                    final boolean ok = session.offerIngredient(idx, v);
                    ShanhaiMod.LOGGER.info("{} io_offer_applied cell={} ok={} value={}",
                            PREFIX, idx, ok, describe(v));
                }
                case ACT_CLEAR -> {
                    final int idx = buffer.readVarInt();
                    final boolean ok = session.clearCell(idx);
                    ShanhaiMod.LOGGER.info("{} io_clear_applied cell={} ok={}", PREFIX, idx, ok);
                }
                case ACT_OPEN_COUNT -> {
                    final int idx = buffer.readVarInt();
                    session.openCountEditor(idx);
                    ShanhaiMod.LOGGER.info("{} io_count_open cell={} value={} chance={} boost={} catalyst={}",
                            PREFIX, idx, session.pendingCount(), session.pendingChance(),
                            session.pendingBoost(), session.pendingCatalyst());
                }
                default -> ShanhaiMod.LOGGER.warn("{} io_action_unknown id={}", PREFIX, id);
            }
        } catch (Throwable t) {
            ShanhaiMod.LOGGER.error("{} io_action_failed id={} err={}", PREFIX, id, t.toString(), t);
        }
    }
}
