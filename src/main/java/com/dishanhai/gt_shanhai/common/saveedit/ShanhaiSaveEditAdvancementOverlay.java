package com.dishanhai.gt_shanhai.common.saveedit;

import com.gregtechceu.gtceu.api.gui.GuiTextures;
import com.lowdragmc.lowdraglib.gui.widget.LabelWidget;
import com.lowdragmc.lowdraglib.gui.widget.Widget;
import com.lowdragmc.lowdraglib.gui.widget.WidgetGroup;
import com.dishanhai.gt_shanhai.common.patternmover.compat.ShanhaiLdlText;

/**
 * 第 ⑤ 页（成就）的<b>详情覆盖层</b>：铺满 400×232、压在整页之上，点某一行成就才弹出来。
 *
 * <h2>一、与第 ④ 页的覆盖层同一套骨架</h2>
 * 照 {@link ShanhaiSaveEditEntityOverlay} 抄四条（那个类又照的是本工程既有的
 * {@code ShanhaiCreativeIconPicker}）：
 * <ol>
 *   <li>自带背景、铺满整块面板；</li>
 *   <li>{@code open()} / {@code close()} 只管 {@code setVisible}/{@code setActive}；</li>
 *   <li>🔴 <b>整层吃掉点击</b>（{@link #mouseClicked}）—— 否则"点在覆盖层空白处"会穿到底下的
 *       页签/「确认修改并重载」，玩家会看到"我在详情里点了一下，怎么世界重载了"；</li>
 *   <li>底部一组明确的动作按钮（这里是「写入（给）」「撤成就」「取消」）。</li>
 * </ol>
 *
 * <h2>二、这一层<b>只管画</b></h2>
 * 状态机、预演、带闸的写全在纯 JDK 的 {@link ShanhaiSaveEditAdvancementUi} 里
 * （那样离线判据才能驱动"打开 → 预演 → 关闭"）。
 *
 * <h2>三、🔴 标题是"客户端自己画"的</h2>
 * 成就的标题要在<b>客户端</b>查语言表（服务端只会拿到 {@code advancements.story.mine_block.title}
 * 这样的键），所以标题 {@code setClientSideWidget()}、原料（成就 id + 游戏里在不在）走
 * {@link #UPDATE_TITLE} 推过来，画的时候现拼（中文在前）。
 *
 * <h2>四、本层没有输入框</h2>
 * "改一条成就"是两个动作（给 / 撤），不是填值 ⇒ 这里<b>一个</b> {@code TextFieldWidget} 都没有，
 * 更没有 {@code SlotWidget}。
 */
public final class ShanhaiSaveEditAdvancementOverlay extends WidgetGroup {

    /**
     * 按钮上的字。
     *
     * <p>🔴 原来这里是"已经预演过、再按一次就真做"的两段式（{@code §e再按=给} / {@code §c再按=撤}），
     * 与 {@code Model.Arm} 一起删掉了：用户 2026-11-15 的口径是「除非我指定需要二次确认，否则都不加」
     * ⇒ 点一次就是一次真做，按钮上的字永远是动作名本身，没有第二个状态。
     */
    private static final String GRANT_IDLE = "gt_shanhai.saveedit.text.318471730b272098";
    private static final String REVOKE_IDLE = "gt_shanhai.saveedit.text.656b517a378fb5e6";

    /**
     * 本条 update-info 的 id：**开 / 关**。
     *
     * <p>为什么需要它：{@code setVisible} 只作用于本侧，而"该不该关"只有服务端知道。
     * 与 {@code ShanhaiSaveEditEntityOverlay#UPDATE_OPEN} 同一条通道（id 错开，免得两份覆盖层
     * 碰巧共用同一个号时读错包）。
     */
    static final int UPDATE_OPEN = 83;

    /** 本条 update-info 的 id：**标题的原料**（成就 id + 游戏里在不在）。 */
    static final int UPDATE_TITLE = 84;

    private final ShanhaiSaveEditPanel panel;
    /** 本侧是不是客户端那份（只有服务端那份才 push）。 */
    private final boolean remote;

    /** 标题（客户端自己画）与 8 行读数（服务端推字）。 */
    private LabelWidget title;
    private final LabelWidget[] notes = new LabelWidget[ShanhaiSaveEditLayout.OV2_NOTE_LINES];

    private final ShanhaiSaveEditAdvancementUi.Model model = new ShanhaiSaveEditAdvancementUi.Model();

    public ShanhaiSaveEditAdvancementOverlay(ShanhaiSaveEditPanel panel, boolean remote) {
        super(0, 0, ShanhaiSaveEditLayout.W, ShanhaiSaveEditLayout.H);
        this.panel = panel;
        this.remote = remote;
        this.setBackground(GuiTextures.BACKGROUND);

        for (ShanhaiSaveEditLayout.Slot slot : ShanhaiSaveEditLayout.advancementOverlaySlots()) {
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
            case "grant":
                return ShanhaiSaveEditPanel.mkButton(x, y, w, h, () -> ShanhaiSaveEditText.text(GRANT_IDLE),
                        this.panel::onAdvancementOverlayGrant);
            case "revoke":
                return ShanhaiSaveEditPanel.mkButton(x, y, w, h, () -> ShanhaiSaveEditText.text(REVOKE_IDLE),
                        this.panel::onAdvancementOverlayRevoke);
            case "cancel":
                return ShanhaiSaveEditPanel.mkButton(x, y, w, h, () -> com.dishanhai.gt_shanhai.common.saveedit.ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.2cd0f3be8738a86c"),
                        this.panel::onAdvancementOverlayCancel);
            default:
                throw new IllegalStateException(com.dishanhai.gt_shanhai.common.saveedit.ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.09e419b61fbfbc7f") + slot.key());
        }
    }

    // ------------------------------------------------------------------ 开 / 关

    /** 打开（两侧各跑一次）：清空一切 —— 上一条成就的残留绝不许带进这一条。 */
    public void open() {
        this.model.open();
        this.setVisible(true);
        this.setActive(true);
        pushOpen(true);
    }

    /** 服务端读完盘之后调：把这一条成就的读数灌进模型（客户端那份靠 Label 的 push 看字）。 */
    public void openDetail(ShanhaiSaveEditAdvancementUi.Detail detail) {
        this.model.openDetail(detail);
        this.setVisible(true);
        this.setActive(true);
        pushOpen(true);
        pushTitle(detail == null ? "" : detail.id(), detail != null && detail.known());
    }

    /** 🔴 只在服务端调：把"成就 id + 在不在"推给客户端（标题由客户端现拼）。 */
    private void pushTitle(String id, boolean known) {
        if (this.remote) {
            return;
        }
        final String v = id == null ? "" : id;
        this.model.setTitlePayload(v, known);
        writeUpdateInfo(UPDATE_TITLE, buf -> {
            buf.writeUtf(v);
            buf.writeBoolean(known);
        });
    }

    /** 关掉（两侧各跑一次）。 */
    public void close() {
        this.model.close();
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
            }
            this.setVisible(open);
            this.setActive(open);
            return;
        }
        if (id == UPDATE_TITLE) {
            this.model.setTitlePayload(buffer.readUtf(), buffer.readBoolean());
            return;
        }
        super.readUpdateInfo(id, buffer);
    }

    /** 客户端在"点了一行"的瞬间先把覆盖层打开（此时还没有读数）。 */
    public void openPlaceholder() {
        open();
    }

    public ShanhaiSaveEditAdvancementUi.Model model() {
        return this.model;
    }

    /**
     * 🔴 <b>整层吃掉点击</b>（与 {@link ShanhaiSaveEditEntityOverlay#mouseClicked} 同一条理由）。
     */
    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        super.mouseClicked(mouseX, mouseY, button);
        return isMouseOverElement(mouseX, mouseY);
    }

    /** 给判据/日志用的一行读数（不依赖任何实例状态）。 */
    public static String layoutLine() {
        return "adv_overlay slots=" + ShanhaiSaveEditLayout.advancementOverlaySlots().size()
                + " notes=" + ShanhaiSaveEditLayout.OV2_NOTE_LINES
                + " buttons=" + 3
                + " textfields=0 slotWidget=0";
    }

    /** 给面板读"这个覆盖层当前是不是开着"（切页时用）。 */
    public boolean isOpen() {
        return this.model.opened();
    }
}
