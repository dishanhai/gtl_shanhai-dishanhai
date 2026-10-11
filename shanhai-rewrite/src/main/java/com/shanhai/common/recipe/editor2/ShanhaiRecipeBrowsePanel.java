package com.shanhai.common.recipe.editor2;

import com.gregtechceu.gtceu.api.gui.GuiTextures;
import com.lowdragmc.lowdraglib.gui.texture.ItemStackTexture;
import com.lowdragmc.lowdraglib.gui.widget.Widget;
import com.lowdragmc.lowdraglib.gui.widget.WidgetGroup;
import com.mojang.blaze3d.systems.RenderSystem;
import com.shanhai.ShanhaiMod;
import com.shanhai.common.recipe.editor.ShanhaiRecipeEditorHolder;
import com.shanhai.common.recipe.editor.ShanhaiRecipeQuery;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/**
 * 山海重构 · <b>新版（上游那套）配方编辑器 —— 只读浏览页</b>（第 1 步的界面件）。
 *
 * <h2>1. 它是什么 / 不是什么</h2>
 * <b>是</b>：上游那套界面的<b>浏览屏</b>搬过来的形状 —— 左边一排<b>配方类型</b>、右边一列 <b>28px 配方卡片</b>、
 * 两个列表各自<b>翻页</b>、顶上一条读数。
 * <b>不是</b>：本步<b>只读</b>。点卡片<b>不进编辑</b>（只在本地显示一行"这版还没接"），
 * 没有落盘、没有 {@code ShanhaiRecipeEditorOps}、没碰服务端状态。
 *
 * <h2>2. 🔴 为什么这个类里没有一行是照他的 API 写的</h2>
 * 他的 UI 直接调他的后端（{@code new ShanhaiRecipeBase(...)} / {@code ShanhaiRecipeQuery.SearchMode} /
 * {@code result.cards()} / {@code ShanhaiIoTable.fromJson(...)} …），那批签名我们同名类里<b>一个都没有</b>
 * （对账：{@code temp/upstream-editor/对账.md} ③ 的 30 条；试编译 E6 = 260 个错误）。
 * ⇒ 本类只搬他的<b>画法</b>（配色、28px 卡、悬停高亮、文字排版），数据一律读
 * {@link ShanhaiRecipeBrowseState}（它再走我们自己的读口）。
 *
 * <h2>3. 两侧各跑一次（这条决定了很多写法）</h2>
 * {@code ShanhaiRecipeEditorFactory.buildUI} 在服务端与客户端<b>各建一次</b>同一屏
 * ⇒ 本类不许引用只有一侧才有的东西（{@code @OnlyIn(CLIENT)}）；数据由
 * {@link Pump} 从服务端推下来。客户端那份的 {@link ShanhaiRecipeBrowseState} 里 {@code ws == null}。
 *
 * <h2>4. 点击怎么上行</h2>
 * 走 LDLib 既有的 {@code writeClientAction} / {@code handleClientAction}（<b>不新开网络包</b>）。
 * ⚠️ <b>动作号绝不能取 1</b>：{@code WidgetGroup.handleClientAction} 把 id==1 用于"子控件下标分发"
 * （本工程既有实证见 {@code ShanhaiCatalystPanel} 类注释）⇒ 本类用 11/12/13。
 */
public final class ShanhaiRecipeBrowsePanel extends WidgetGroup {

    public static final String PREFIX = "[SHANHAI-EDIT] editor2";

    // ------------------------------------------------------------ 几何（一处定义，画与点都读它）

    public static final int W = 420;
    public static final int H = 300;

    /** 类型行高。 */
    public static final int ROW_H = 18;
    /** 卡片行高（他的卡片就是 28）。 */
    public static final int CARD_H = 28;

    public static final int LEFT_X = 4;
    public static final int LEFT_W = 196;
    public static final int RIGHT_X = 204;
    public static final int RIGHT_W = 212;
    public static final int BODY_Y = 32;
    /** 翻页按钮那一行的高。 */
    public static final int NAV_H = 16;

    // ------------------------------------------------------------ 动作号（⚠️ 不许取 1）

    public static final int ACTION_SELECT_TYPE = 11;
    public static final int ACTION_FLIP_TYPES = 12;
    public static final int ACTION_FLIP_CARDS = 13;

    // ------------------------------------------------------------ 状态

    private final ShanhaiRecipeBrowseState st;
    private final boolean clientSide;

    /** 只在客户端本地用的一行提示（点卡片时写它）—— 不进任何同步。 */
    private String localHint = "";

    private Pump pump;

    public ShanhaiRecipeBrowsePanel(ShanhaiRecipeEditorHolder holder, Player player) {
        super(0, 0, W, H);
        this.clientSide = player.level().isClientSide();
        this.st = new ShanhaiRecipeBrowseState(
                this.clientSide ? null : player.getServer(), player, this.clientSide);
        setBackground(GuiTextures.BACKGROUND);

        // 类型列表：13 行【预先建好】，越界的那种自己不画也不吃点击
        //   （这样就不需要"状态一变就重建控件表"——少一条会漂的路径）
        for (int i = 0; i < ShanhaiRecipeBrowseState.TYPES_PER_PAGE; i++) {
            addWidget(new TypeRowWidget(this, i, LEFT_X, BODY_Y + i * ROW_H, LEFT_W));
        }
        // 卡片：6 张【预先建好】，同上
        for (int i = 0; i < ShanhaiRecipeBrowseState.CARDS_PER_PAGE; i++) {
            addWidget(new CardRowWidget(this, i, RIGHT_X, BODY_Y + i * CARD_H, RIGHT_W));
        }
        final int navY = BODY_Y + ShanhaiRecipeBrowseState.TYPES_PER_PAGE * ROW_H + 2;
        final int cardNavY = BODY_Y + ShanhaiRecipeBrowseState.CARDS_PER_PAGE * CARD_H + 2;
        addWidget(new NavWidget(this, 0, -1, LEFT_X, navY, 60, NAV_H, "← 上页"));
        addWidget(new NavWidget(this, 0, +1, LEFT_X + 64, navY, 60, NAV_H, "下页 →"));
        addWidget(new NavWidget(this, 1, -1, RIGHT_X, cardNavY, 60, NAV_H, "← 上页"));
        addWidget(new NavWidget(this, 1, +1, RIGHT_X + 64, cardNavY, 60, NAV_H, "下页 →"));
        // ⚠️ 泵必须【最后】加：LDLib 的 WidgetGroup.mouseClicked 倒序遍历子控件，
        //    泵是 0×0 的无形件，不该参与命中（与既有大面板 StatePump 同一处理）
        pump = new Pump(st);
        addWidget(pump);
    }

    /** 供自检/命令直接拿到这一屏的状态（不经过渲染）。 */
    public ShanhaiRecipeBrowseState state() {
        return st;
    }

    /** 这一帧要画的那一行提示（本地那一条优先）。 */
    private String hintLine() {
        return localHint.isEmpty() ? "只读浏览页：点卡片暂不进入编辑" : localHint;
    }

    // ------------------------------------------------------------ 服务端：真正改状态的那几拍

    @Override
    public void handleClientAction(int id, FriendlyByteBuf buffer) {
        if (id == ACTION_SELECT_TYPE) {
            final int row = buffer.readVarInt();
            if (!st.isClientSide()) {
                st.selectType(st.typeIdAt(row));
            }
            return;
        }
        if (id == ACTION_FLIP_TYPES) {
            final int d = buffer.readVarInt();
            if (!st.isClientSide()) {
                st.flipTypePage(d);
            }
            return;
        }
        if (id == ACTION_FLIP_CARDS) {
            final int d = buffer.readVarInt();
            if (!st.isClientSide()) {
                st.flipCardPage(d);
            }
            return;
        }
        // ⚠️ 认不出的 id 必须转发给 super（它那一支是子控件下标分发）
        super.handleClientAction(id, buffer);
    }

    /** 点了一行类型（客户端那一拍：只把行号发上去）。 */
    void clickTypeRow(int row) {
        if (row < 0 || row >= st.typeRowCount()) {
            return;
        }
        writeClientAction(ACTION_SELECT_TYPE, buf -> buf.writeVarInt(row));
        localHint = "选中：" + st.typeNameAt(row) + "（等这一页刷新）";
        ShanhaiMod.LOGGER.info("{} browse_click_type row={} id={} name={} client={}",
                PREFIX, row, st.typeIdAt(row), st.typeNameAt(row), clientSide);
    }

    /** 点了翻页（客户端那一拍）。 */
    void clickFlip(int list, int delta) {
        final boolean ok = list == 0 ? st.canTypePage(delta) : st.canCardPage(delta);
        if (!ok) {
            return;
        }
        writeClientAction(list == 0 ? ACTION_FLIP_TYPES : ACTION_FLIP_CARDS,
                buf -> buf.writeVarInt(delta));
        ShanhaiMod.LOGGER.info("{} browse_click_flip list={} delta={} client={}",
                PREFIX, list == 0 ? "types" : "cards", delta, clientSide);
    }

    /** 点了一张卡片 —— 🔴 本步【只读】：本地出一行提示，**不发包、不改任何状态**。 */
    void clickCard(int index) {
        final ShanhaiRecipeQuery.Card c = st.cardAt(index);
        if (c == null) {
            return;
        }
        localHint = "这条是 " + c.id() + " —— 新版界面的编辑动作这版还没接（只读）";
        ShanhaiMod.LOGGER.info("{} browse_click_card_readonly index={} id={} dur={} eut={} "
                        + "(本步只读：没有发任何包、没有改任何状态)",
                PREFIX, index, c.id(), c.duration(), c.eut());
    }

    // ------------------------------------------------------------ 画

    @Override
    public void drawInBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
        super.drawInBackground(graphics, mouseX, mouseY, partialTicks);
        final var font = Minecraft.getInstance().font;
        graphics.drawString(font, "配方浏览 · 新版界面（只读）", 6, 6, 0xFFEAF7FF, false);
        graphics.drawString(font, "界面开关：" + ShanhaiRecipeUiSwitch.cellText()
                + "    " + st.note(), 6, 18, 0xFF8AA6B9, false);
        // 空表时如实写"空"，不画假数据
        if (st.typeRowCount() == 0) {
            graphics.drawString(font, "（类型列表为空）", LEFT_X + 4, BODY_Y + 4, 0xFF6E8494, false);
        }
        if (st.cardRowCount() == 0) {
            graphics.drawString(font, "（左边点一个配方类型）", RIGHT_X + 4, BODY_Y + 4, 0xFF6E8494, false);
        }
        graphics.drawString(font, hintLine(), 6, H - 14, 0xFF91F7BC, false);
    }

    // ============================================================ 三个小控件

    /** 左边类型列表的一行（点它 = 选这个类型）。 */
    static final class TypeRowWidget extends Widget {

        private final ShanhaiRecipeBrowsePanel host;
        private final int index;

        TypeRowWidget(ShanhaiRecipeBrowsePanel host, int index, int x, int y, int w) {
            super(x, y, w, ROW_H);
            this.host = host;
            this.index = index;
        }

        private boolean present() {
            return index < host.st.typeRowCount();
        }

        private boolean selected() {
            return present() && host.st.typeIdAt(index).equals(host.st.selectedTypeId());
        }

        @Override
        public void drawInBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
            if (!present()) {
                return;
            }
            final int x = getPositionX();
            final int y = getPositionY();
            final int w = getSizeWidth();
            final boolean hover = isMouseOverElement(mouseX, mouseY);
            final boolean sel = selected();
            final int bg = sel ? 0xFF1A3549 : (hover ? 0xFF19324A : 0xFF111B2D);
            final int edge = hover ? 0xFF69E8FF : 0xFF29445A;
            graphics.fill(x, y, x + w, y + ROW_H - 1, edge);
            graphics.fill(x + 1, y + 1, x + w - 1, y + ROW_H - 2, bg);
            final var font = Minecraft.getInstance().font;
            final String name = fit(font, host.st.typeNameAt(index), w - 52);
            graphics.drawString(font, name, x + 5, y + 5, sel ? 0xFFEAF7FF : 0xFFB7C9D6, false);
            graphics.drawString(font, host.st.typeCountAt(index) + " 条", x + w - 30, y + 5, 0xFF8AA6B9, false);
        }

        @Override
        public boolean mouseClicked(double mouseX, double mouseY, int button) {
            if (button != 0 || !present() || !isMouseOverElement(mouseX, mouseY)) {
                return false;
            }
            host.clickTypeRow(index);
            return true;
        }
    }

    /** 右边一张配方卡（28px；本步只读）。 */
    static final class CardRowWidget extends Widget {

        private final ShanhaiRecipeBrowsePanel host;
        private final int index;
        private ItemStackTexture icon;
        private String iconFor = "";

        CardRowWidget(ShanhaiRecipeBrowsePanel host, int index, int x, int y, int w) {
            super(x, y, w, CARD_H);
            this.host = host;
            this.index = index;
        }

        private ShanhaiRecipeQuery.Card card() {
            return index < host.st.cardRowCount() ? host.st.cardAt(index) : null;
        }

        @Override
        public void drawInBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
            final ShanhaiRecipeQuery.Card c = card();
            if (c == null) {
                return;
            }
            final int x = getPositionX();
            final int y = getPositionY();
            final int w = getSizeWidth();
            final boolean hover = isMouseOverElement(mouseX, mouseY);
            graphics.fill(x, y, x + w, y + CARD_H - 2, hover ? 0xFF69E8FF : 0xFF29445A);
            graphics.fill(x + 1, y + 1, x + w - 1, y + CARD_H - 3, hover ? 0xFF19324A : 0xFF111B2D);
            graphics.fill(x + 4, y + 4, x + 22, y + 22, 0xFF20374B);
            drawOutputIcon(graphics, x + 5, y + 6, c);

            final var font = Minecraft.getInstance().font;
            final String mark = "只读";
            final int markX = x + w - 6 - font.width(mark);
            graphics.drawString(font, fit(font, String.valueOf(c.id()), Math.max(8, markX - (x + 28) - 4)),
                    x + 28, y + 4, 0xFFEAF7FF, false);
            graphics.drawString(font, trim(String.valueOf(c.typeName()) + "  " + c.duration()
                    + "t  " + c.eut() + " EU/t", 48), x + 28, y + 15, 0xFF8AA6B9, false);
            graphics.drawString(font, mark, markX, y + 10, 0xFF91F7BC, false);
        }

        private void drawOutputIcon(GuiGraphics graphics, int x, int y, ShanhaiRecipeQuery.Card c) {
            if (c.outs() == null || c.outs().isEmpty()) {
                return;
            }
            final ShanhaiRecipeQuery.Chip chip = c.outs().get(0);
            if (!chip.item()) {
                // 流体图标本步【没搬】（理由写在报告里）：画一个中性色块，绝不用错的贴图冒名顶替
                graphics.fill(x, y, x + 16, y + 16, 0x8030506A);
                return;
            }
            final String key = "i" + chip.id();
            if (!key.equals(iconFor) || icon == null) {
                ItemStack stack = ItemStack.EMPTY;
                try {
                    stack = ShanhaiRecipeQuery.itemOf(chip);
                } catch (Throwable ignored) {
                    stack = ItemStack.EMPTY;
                }
                icon = stack == null || stack.isEmpty() ? null : new ItemStackTexture(stack);
                iconFor = key;
            }
            if (icon != null) {
                RenderSystem.enableBlend();
                icon.draw(graphics, 0, 0, x, y, 16, 16);
                RenderSystem.disableBlend();
            }
        }

        @Override
        public boolean mouseClicked(double mouseX, double mouseY, int button) {
            if (button != 0 || card() == null || !isMouseOverElement(mouseX, mouseY)) {
                return false;
            }
            host.clickCard(index);
            return true;
        }
    }

    /** 翻页小按钮（{@code list}: 0 = 类型列表，1 = 卡片列表）。 */
    static final class NavWidget extends Widget {

        private final ShanhaiRecipeBrowsePanel host;
        private final int list;
        private final int delta;
        private final String label;

        NavWidget(ShanhaiRecipeBrowsePanel host, int list, int delta, int x, int y, int w, int h, String label) {
            super(x, y, w, h);
            this.host = host;
            this.list = list;
            this.delta = delta;
            this.label = label;
        }

        private boolean enabled() {
            return list == 0 ? host.st.canTypePage(delta) : host.st.canCardPage(delta);
        }

        @Override
        public void drawInBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
            final boolean on = enabled();
            final int x = getPositionX();
            final int y = getPositionY();
            final int w = getSizeWidth();
            final boolean hover = on && isMouseOverElement(mouseX, mouseY);
            graphics.fill(x, y, x + w, y + NAV_H - 1, hover ? 0xFF69E8FF : 0xFF29445A);
            graphics.fill(x + 1, y + 1, x + w - 1, y + NAV_H - 2, hover ? 0xFF19324A : 0xFF111B2D);
            final var font = Minecraft.getInstance().font;
            graphics.drawString(font, label, x + 5, y + 4,
                    on ? (hover ? 0xFFEAF7FF : 0xFFB7C9D6) : 0xFF4A5A66, false);
        }

        @Override
        public boolean mouseClicked(double mouseX, double mouseY, int button) {
            if (button != 0 || !enabled() || !isMouseOverElement(mouseX, mouseY)) {
                return false;
            }
            host.clickFlip(list, delta);
            return true;
        }
    }

    /**
     * 状态泵 —— 与既有大面板的 {@code StatePump} <b>同一条路</b>（本工程已验证过的写法）：
     * 服务端 {@code writeInitialData} 推第一屏、之后版本一变就 {@code writeUpdateInfo(1, …)}；
     * 客户端 {@code readInitialData}/{@code readUpdateInfo} 读回来。
     */
    static final class Pump extends Widget {

        private final ShanhaiRecipeBrowseState st;
        private int sentVersion = -1;

        Pump(ShanhaiRecipeBrowseState st) {
            super(0, 0, 0, 0);
            this.st = st;
        }

        @Override
        public void writeInitialData(FriendlyByteBuf buffer) {
            super.writeInitialData(buffer);
            if (!isRemote()) {
                st.writeState(buffer);
                sentVersion = st.version();
            }
        }

        @Override
        public void readInitialData(FriendlyByteBuf buffer) {
            super.readInitialData(buffer);
            st.readState(buffer);
        }

        @Override
        public void detectAndSendChanges() {
            super.detectAndSendChanges();
            if (isRemote()) {
                return;
            }
            if (st.version() == sentVersion) {
                return;
            }
            sentVersion = st.version();
            writeUpdateInfo(1, buffer -> st.writeState(buffer));
        }

        @Override
        public void readUpdateInfo(int id, FriendlyByteBuf buffer) {
            if (id == 1) {
                st.readState(buffer);
                return;
            }
            super.readUpdateInfo(id, buffer);
        }
    }

    // ------------------------------------------------------------ 文本小工具

    private static String fit(net.minecraft.client.gui.Font font, String value, int width) {
        if (value == null || value.isEmpty() || width < 8) {
            return "";
        }
        if (font.width(value) <= width) {
            return value;
        }
        final String ellipsis = "…";
        int keep = value.length();
        while (keep > 0 && font.width(value.substring(0, keep) + ellipsis) > width) {
            keep--;
        }
        return keep <= 0 ? "" : value.substring(0, keep) + ellipsis;
    }

    private static String trim(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.length() <= max ? value : value.substring(0, Math.max(0, max - 3)) + "...";
    }
}
