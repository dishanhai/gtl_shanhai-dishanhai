package com.shanhai.common.recipe.editor;

import com.lowdragmc.lowdraglib.gui.ingredient.IGhostIngredientTarget;
import com.lowdragmc.lowdraglib.gui.ingredient.Target;
import com.lowdragmc.lowdraglib.gui.widget.Widget;
import com.shanhai.ShanhaiMod;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/**
 * 第一屏底部那个 <b>「随便放一个物品」的框</b>（用户点单的功能 A 的入口）。
 *
 * <h2>用户原话与它对得上的地方</h2>
 * <blockquote>「第一：在这下面加一个允许我放任意物品的框（我可以把物品通过jei拖入），
 * 下面有3个按钮：1：获取途径，2：作为物品的用处，3：作为机器的用处」</blockquote>
 *
 * <h4>🔴 为什么它必须是"幽灵槽"（收了不消耗）</h4>
 * 它只是一个<b>查询条件</b>，不是背包格 —— 拖进来只记下"是哪个物品"，绝不从 JEI 或背包里扣东西。
 *
 * <h4>🔴 为什么面板必须把它加进"落点转发"那一圈</h4>
 * LDLib 拿落点的唯一入口 {@code ModularUIJeiHandler.getTargetsTyped} 只问
 * {@code mainGroup.getPhantomTargets(ingredient)}，而 {@code WidgetGroup.getPhantomTargets}
 * <b>只看直接子控件、不递归</b>（这条在 {@link ShanhaiIOWidget} 的类注释里有字节码取证）。
 * 所以面板那一层的转发里少写一个控件，结果就是"这个框怎么拖都拖不进去"<b>而且不报错</b>。
 *
 * <h4>⚠️ JEI 那一层壳在这里剥</h4>
 * LDLib 递进来的其实是 {@code ITypedIngredient}（不是裸 {@code ItemStack}）。
 * 剥壳放在这一层是因为本类是<b>客户端专用</b>（只在渲染与拖动路径上跑），JEI 一定在；
 * 公共侧的 {@link ShanhaiRecipeEditorWorkspace#toItemStack} 只认裸物品，免得无 JEI 的专服上
 * 触发 {@code NoClassDefFoundError}。
 */
public final class ShanhaiQuerySlotWidget extends Widget implements IGhostIngredientTarget {

    private static final String PREFIX = "[SHANHAI-EDIT] editor";

    /** 格子边长（与原版槽位一致）。 */
    public static final int CELL = 18;

    /** 外框：空（灰）。 */
    private static final int FRAME_EMPTY = 0xFF8B8B8B;
    /** 外框：有东西（青）。 */
    private static final int FRAME_FILLED = 0xFF35D0C0;
    /** 底。 */
    private static final int CELL_BG = 0xFF373737;

    private static final int ACT_SET = 11;
    private static final int ACT_CLEAR = 12;

    private final ShanhaiRecipeEditorWorkspace session;

    public ShanhaiQuerySlotWidget(ShanhaiRecipeEditorWorkspace session, int x, int y) {
        super(x, y, CELL, CELL);
        this.session = session;
    }

    @Override
    public void drawInBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
        final int x = getPositionX();
        final int y = getPositionY();
        final ItemStack stack = session == null ? ItemStack.EMPTY : session.queryItem();
        final boolean has = stack != null && !stack.isEmpty();
        graphics.fill(x, y, x + CELL, y + CELL, has ? FRAME_FILLED : FRAME_EMPTY);
        graphics.fill(x + 1, y + 1, x + CELL - 1, y + CELL - 1, CELL_BG);
        if (!has) {
            // 🔴 第 11 刀：空格子画一个明确的"这里要放东西"的记号。
            //    用户报「物品框里没有图标」时，最糟的情况是"空框"和"图标没画出来"长得一样
            //    （都是个空心方框，谁也说不清是哪种）⇒ 空态必须有自己的样子。
            graphics.fill(x + 5, y + 5, x + CELL - 5, y + CELL - 5, 0xFF4A4A4A);
            graphics.fill(x + 6, y + 12, x + CELL - 6, y + 13, 0xFF9A9A9A);
            measureOnce(null);
            return;
        }
        // 🔴 第 12 刀：改用 **LDLib 自己画槽位的那一个函数** —— 这是"图标画不出来"的修法。
        //    取证：LDLib 自己的 `SlotWidget.drawInBackground` 画物品走的就是
        //    `DrawerHelper.drawItemStack(graphics, stack, x+1, y+1, -1, null)`，
        //    它内部会先把 shader 颜色置白（`RenderSystem.setShaderColor(1,1,1,1)`）再
        //    `enableDepthTest()` / `depthMask(true)` / 抬高 z 再画。
        //    直接调 `graphics.renderItem` 会继承**调用方留下的状态**（我们的面板前面画过
        //    底色方框、又处在 LDLib 的半透明裁剪层里）⇒ 图标可能整块看不见/被盖住。
        //    用户原话「物品框里还是没有图标」正是这一类"服务端有、客户端也有、就是看不见"。
        com.lowdragmc.lowdraglib.gui.util.DrawerHelper.drawItemStack(
                graphics, stack, x + 1, y + 1, -1, null);
        graphics.renderItemDecorations(Minecraft.getInstance().font, stack, x + 1, y + 1);
        // 底下一行小字：这是"查询用的物品"（不是背包格，拖进来不会消耗）
        graphics.drawString(Minecraft.getInstance().font, "§8查", x + 2, y + CELL - 8, 0xFFFFFF, false);
        measureOnce(stack);
    }

    /**
     * 🔴 第 11 刀：把"这一帧到底有没有物品"量一行日志出来（每种物品只写一次）。
     *
     * <p>为什么必须有：用户报「框里没有图标」，而"服务端有、客户端没有"与"客户端也有、
     * 只是没画出来"是<b>两种完全不同的病</b>（前者是同步，后者是渲染），
     * 光看截图分不开。这一行会写进客户端日志 `logs/latest.log`：
     * <pre>
     *   query_slot_render has=false                      ⇒ 客户端这份是空的（同步问题）
     *   query_slot_render has=true item=minecraft:oak_planks  ⇒ 客户端有东西（那就是画的问题）
     * </pre>
     */
    private static void measureOnce(ItemStack stack) {
        final String key = stack == null || stack.isEmpty() ? "(empty)"
                : String.valueOf(net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem()));
        if (!MEASURED.add(key)) {
            return;
        }
        ShanhaiMod.LOGGER.info("{} query_slot_render has={} item={} count={}",
                PREFIX, stack != null && !stack.isEmpty(), key,
                stack == null ? 0 : stack.getCount());
    }

    /** 已经量过的键（每个物品只写一行，免得每帧刷屏）。 */
    private static final java.util.Set<String> MEASURED = new java.util.HashSet<>();


    @Override
    public void drawInForeground(GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
        if (isMouseOverElement(mouseX, mouseY)) {
            tooltipTexts.clear();
            final ItemStack stack = session == null ? ItemStack.EMPTY : session.queryItem();
            if (stack != null && !stack.isEmpty()) {
                tooltipTexts.add(Component.literal("§f查询物品：§7"
                        + com.shanhai.common.text.ShanhaiTextParser
                        .stripStyleCode(stack.getHoverName().getString())));
                tooltipTexts.add(Component.literal("§8右键清空 · 再从 JEI 拖一个进来换掉"));
            } else {
                tooltipTexts.add(Component.literal("§8从 JEI 拖一个物品到这里（不会消耗任何东西）"));
            }
        }
        super.drawInForeground(graphics, mouseX, mouseY, partialTicks);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (session == null || !isMouseOverElement(mouseX, mouseY)) {
            return false;
        }
        if (button == 1) {                  // 右键 = 清空
            if (isRemote()) {
                session.clearQueryItem();
                writeClientAction(ACT_CLEAR, buf -> {});
            } else {
                session.clearQueryItem();
            }
            return true;
        }
        return false;
    }

    @Override
    public List<Target> getPhantomTargets(Object ingredient) {
        if (session == null) {
            return List.of();
        }
        // 只认物品：三问都是按物品问的（流体进这个框没有对应语义）。
        // 收不下就不留落点 —— 但面板那一层已经记账（见 ShanhaiDragStats），不是静默。
        final ItemStack stack = ShanhaiRecipeEditorWorkspace.toItemStack(unwrapTyped(ingredient));
        if (stack == null || stack.isEmpty()) {
            return List.of();
        }
        final ShanhaiQuerySlotWidget self = this;
        final Target target = new Target() {
            @Override
            public net.minecraft.client.renderer.Rect2i getArea() {
                // 口径与 LDLib 自己的 PhantomSlotWidget#getPhantomTargets 一致：控件自己的矩形
                return new net.minecraft.client.renderer.Rect2i(
                        self.getPositionX(), self.getPositionY(), CELL, CELL);
            }

            @Override
            public void accept(Object dropped) {
                final ItemStack v = ShanhaiRecipeEditorWorkspace.toItemStack(unwrapTyped(dropped));
                if (v == null || v.isEmpty()) {
                    ShanhaiDragStats.dropRejected("query_slot_not_item");
                    return;
                }
                ShanhaiDragStats.dropAccepted();
                session.offerQueryItem(v);
                final ItemStack send = v.copy();
                self.writeClientAction(ACT_SET, buf -> buf.writeItem(send));
                ShanhaiMod.LOGGER.info("{} query_slot_drop item={} n={} totals({})",
                        PREFIX, net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(v.getItem()),
                        v.getCount(), ShanhaiDragStats.statsLine());
            }
        };
        ShanhaiDragStats.phantomCall(1);
        return List.of(target);
    }

    /** {@code ITypedIngredient} ⇒ 里面的物品栈（不是的话原样返回）。客户端专用。 */
    private static Object unwrapTyped(Object ingredient) {
        if (!(ingredient instanceof mezz.jei.api.ingredients.ITypedIngredient<?> typed)) {
            return ingredient;
        }
        try {
            final ItemStack stack = typed.getItemStack().orElse(ItemStack.EMPTY);
            if (!stack.isEmpty()) {
                return stack;
            }
        } catch (Throwable ignored) {
            // 不是物品型 ⇒ 原样返回交给下游判
        }
        return ingredient;
    }

    @Override
    public void handleClientAction(int id, FriendlyByteBuf buffer) {
        if (session == null) {
            return;
        }
        try {
            switch (id) {
                case ACT_SET -> {
                    final ItemStack v = buffer.readItem();
                    final boolean ok = session.offerQueryItem(v);
                    ShanhaiMod.LOGGER.info("{} query_slot_set ok={} item={}", PREFIX, ok,
                            net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(v.getItem()));
                }
                case ACT_CLEAR -> ShanhaiMod.LOGGER.info("{} query_slot_clear ok={}", PREFIX,
                        session.clearQueryItem());
                default -> ShanhaiMod.LOGGER.warn("{} query_slot_action_unknown id={}", PREFIX, id);
            }
        } catch (Throwable t) {
            ShanhaiMod.LOGGER.error("{} query_slot_action_failed id={} err={}", PREFIX, id, t.toString(), t);
        }
    }
}
