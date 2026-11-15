package com.shanhai.common.saveedit;

import com.gregtechceu.gtceu.api.gui.GuiTextures;
import com.lowdragmc.lowdraglib.gui.widget.LabelWidget;
import com.lowdragmc.lowdraglib.gui.widget.TextFieldWidget;
import com.lowdragmc.lowdraglib.gui.widget.Widget;
import com.lowdragmc.lowdraglib.gui.widget.WidgetGroup;
import com.shanhai.common.recipe.editor.ShanhaiLdlText;
import net.minecraft.network.chat.Component;

/**
 * 第 ⑥ 页（统计）的<b>详情覆盖层</b>：铺满 400×232、压在整页之上，点某一行统计才弹出来。
 *
 * <h2>一、与第 ④ / ⑤ 页的覆盖层同一套骨架</h2>
 * 自带背景、{@code open()/close()} 只管显隐、🔴 <b>整层吃掉点击</b>（{@link #mouseClicked}）、
 * 底部一组明确的动作按钮（这里是「写入」「动作：设值/清零」「取消」）。
 * 状态机、预演、带闸的写全在纯 JDK 的 {@link ShanhaiSaveEditStatsUi} 里（这一层只管画）。
 *
 * <h2>二、🔴 标题是"客户端自己画"的</h2>
 * {@code minecraft:jump} 要显示成「跳跃次数」—— 那只有<b>客户端</b>查得到（服务端没有语言表）。
 * 所以标题 {@code setClientSideWidget()}、原料（分类 + 键）走 {@link #UPDATE_TITLE} 推过来，
 * 画的时候现拼（中文在前）。
 *
 * <h2>三、🔴 这个输入框<b>没有</b> textSupplier</h2>
 * LDLib 的 {@code updateScreen} 只在 {@code isClientSideWidget && textSupplier != null} 时覆盖文本
 * —— 给玩家正在打字的框挂一个 supplier，会把他刚敲的字每 tick 冲掉一次。
 * 所以"新值"那个格子只用初始数据通道（连初始值都不写：<b>留空 = 不改</b>）。
 *
 * <h2>四、本层没有任何物品格子</h2>
 * 一个 {@code SlotWidget} 都没有（LDLib 也没有 {@code ItemStackWidget}；{@code SlotWidget} 会吃掉点击）。
 */
public final class ShanhaiSaveEditStatsOverlay extends WidgetGroup {

    /**
     * 按钮上的字。
     *
     * <p>🔴 原来这里是"已经预演过、再按一次就真做"的两段式（{@code §e再按=写入}），
     * 与 {@code Model.Arm} 一起删掉了：用户 2026-11-15 的口径是「除非我指定需要二次确认，否则都不加」
     * ⇒ 点一次就是一次真做，按钮上的字永远是动作名本身，没有第二个状态。
     */
    private static final String WRITE_IDLE = "写入";

    /** 本条 update-info 的 id：**开 / 关**（与第 ④⑤ 页两条覆盖层的号错开）。 */
    static final int UPDATE_OPEN = 85;

    /** 本条 update-info 的 id：**标题的原料**（分类 + 键）。 */
    static final int UPDATE_TITLE = 86;

    private final ShanhaiSaveEditPanel panel;
    /** 本侧是不是客户端那份（只有服务端那份才 push）。 */
    private final boolean remote;

    /** 标题（客户端自己画）与 8 行读数（服务端推字）。 */
    private LabelWidget title;
    private final LabelWidget[] notes = new LabelWidget[ShanhaiSaveEditLayout.OV2_NOTE_LINES];
    /** "新值"输入框（🔴 没有 textSupplier）。 */
    private TextFieldWidget valueField;

    private final ShanhaiSaveEditStatsUi.Model model = new ShanhaiSaveEditStatsUi.Model();

    public ShanhaiSaveEditStatsOverlay(ShanhaiSaveEditPanel panel, boolean remote) {
        super(0, 0, ShanhaiSaveEditLayout.W, ShanhaiSaveEditLayout.H);
        this.panel = panel;
        this.remote = remote;
        this.setBackground(GuiTextures.BACKGROUND);

        for (ShanhaiSaveEditLayout.Slot slot : ShanhaiSaveEditLayout.statsOverlaySlots()) {
            this.addWidget(build(slot));
        }
        this.setVisible(false);
        this.setActive(false);
    }

    /** 照槽位表建一个控件（🔴 每一个槽位都必须<b>恰好</b>产出一个控件）。 */
    private Widget build(ShanhaiSaveEditLayout.Slot slot) {
        final int x = slot.x();
        final int y = slot.y();
        final int w = slot.w();
        final int h = slot.h();
        switch (slot.key()) {
            case "title": {
                final LabelWidget t = new LabelWidget(x, y,
                        () -> ShanhaiSaveEditPanel.fit(this.model.title(), w));
                t.setClientSideWidget();        // ⚠️ 返回类型是 Widget，所以要分两步
                this.title = t;
                return t;
            }
            case "note": {
                final int idx = slot.index();
                this.notes[idx] = new LabelWidget(x, y,
                        ShanhaiLdlText.sup(() -> ShanhaiSaveEditPanel.fit(this.model.note(idx), w)));
                return this.notes[idx];
            }
            case "in.label":
                return new LabelWidget(x, y, "新值")
                        .setClientSideWidget()
                        .setHoverTooltips(Component.literal("§e新值§7：要设成的非负整数；「清零」时不看这一格。留空 = 不改"));
            case "in.field": {
                final TextFieldWidget f = new TextFieldWidget(x, y, w, h, null, null);
                f.setMaxStringLength(20);
                f.setHoverTooltips(Component.literal("§8留空 = 不改。§7设值要填非负整数（例如 0 / 3490）"));
                this.valueField = f;
                return f;
            }
            case "mode":
                return ShanhaiSaveEditPanel.mkButton(x, y, w, h,
                        () -> this.model.actionText(), this.panel::onStatsOverlayCycleAction);
            case "write":
                return ShanhaiSaveEditPanel.mkButton(x, y, w, h, () -> WRITE_IDLE,
                        this.panel::onStatsOverlayWrite);
            case "cancel":
                return ShanhaiSaveEditPanel.mkButton(x, y, w, h, () -> "取消",
                        this.panel::onStatsOverlayCancel);
            default:
                throw new IllegalStateException("统计覆盖层槽位没被处理：" + slot.key());
        }
    }

    // ------------------------------------------------------------------ 开 / 关

    /** 打开（两侧各跑一次）：清空一切 —— 上一条统计的残留输入绝不许带进这一条。 */
    public void open() {
        this.model.open();
        clearInputs();
        this.setVisible(true);
        this.setActive(true);
        pushOpen(true);
    }

    /** 服务端读完盘之后调：把这一条统计的读数灌进模型（客户端那份靠 Label 的 push 看字）。 */
    public void openDetail(ShanhaiSaveEditStatsUi.Detail detail) {
        this.model.openDetail(detail);
        clearInputs();
        this.setVisible(true);
        this.setActive(true);
        pushOpen(true);
        pushTitle(detail == null ? "" : detail.cat(), detail == null ? "" : detail.statKey());
    }

    /** 🔴 只在服务端调：把"分类 + 键"推给客户端（标题由客户端现拼）。 */
    private void pushTitle(String category, String key) {
        if (this.remote) {
            return;
        }
        final String c = category == null ? "" : category;
        final String k = key == null ? "" : key;
        this.model.setTitlePayload(c, k);
        writeUpdateInfo(UPDATE_TITLE, buf -> {
            buf.writeUtf(c);
            buf.writeUtf(k);
        });
    }

    /** 关掉（两侧各跑一次）。 */
    public void close() {
        this.model.close();
        clearInputs();
        this.setVisible(false);
        this.setActive(false);
        pushOpen(false);
    }

    /** 🔴 只在服务端调：把"开着 / 关着"推给客户端那份。 */
    private void pushOpen(boolean open) {
        if (this.remote) {
            return;
        }
        writeUpdateInfo(UPDATE_OPEN, buf -> buf.writeBoolean(open));
    }

    @Override
    public void readUpdateInfo(int id, net.minecraft.network.FriendlyByteBuf buffer) {
        if (id == UPDATE_OPEN) {
            final boolean open = buffer.readBoolean();
            if (!open) {
                this.model.close();
                clearInputs();
            }
            this.setVisible(open);
            this.setActive(open);
            return;
        }
        if (id == UPDATE_TITLE) {
            this.model.setTitlePayload(buffer.readUtf(), buffer.readUtf());
            return;
        }
        super.readUpdateInfo(id, buffer);
    }

    /** 客户端在"点了一行"的瞬间先把覆盖层打开（此时还没有读数）。 */
    public void openPlaceholder() {
        open();
    }

    public ShanhaiSaveEditStatsUi.Model model() {
        return this.model;
    }

    /** 「新值」格子里的字（服务端提交前取一次）。 */
    public String readValue() {
        final String s = this.valueField == null ? null : this.valueField.getCurrentString();
        return s == null ? "" : s.trim();
    }

    /** 清空格子（两侧各清自己那份；服务端那份不用 push —— 文本方向是"客户端 → 服务端"）。 */
    private void clearInputs() {
        if (this.valueField != null) {
            this.valueField.setCurrentString("");
        }
    }

    /**
     * 🔴 <b>整层吃掉点击</b>（与 {@link ShanhaiSaveEditEntityOverlay#mouseClicked} 同一条理由）：
     * 落在覆盖层身上、但没打中任何子控件的那些点击，绝不能穿到底下的页签 / 「确认修改并重载」。
     */
    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        super.mouseClicked(mouseX, mouseY, button);
        return isMouseOverElement(mouseX, mouseY);
    }

    /** 给判据/日志用的一行读数（不依赖任何实例状态）。 */
    public static String layoutLine() {
        return "stats_overlay slots=" + ShanhaiSaveEditLayout.statsOverlaySlots().size()
                + " notes=" + ShanhaiSaveEditLayout.OV2_NOTE_LINES
                + " buttons=" + 3
                + " textfields=" + 1 + " textSupplier_on_fields=0 slotWidget=0";
    }

    /** 给面板读"这个覆盖层当前是不是开着"（切页时用）。 */
    public boolean isOpen() {
        return this.model.opened();
    }
}
