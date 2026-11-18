package com.dishanhai.gt_shanhai.common.saveedit;

import com.gregtechceu.gtceu.api.gui.GuiTextures;
import com.lowdragmc.lowdraglib.gui.widget.LabelWidget;
import com.lowdragmc.lowdraglib.gui.widget.TextFieldWidget;
import com.lowdragmc.lowdraglib.gui.widget.Widget;
import com.lowdragmc.lowdraglib.gui.widget.WidgetGroup;
import com.dishanhai.gt_shanhai.common.patternmover.compat.ShanhaiLdlText;
import net.minecraft.network.chat.Component;


/**
 * 第 ④ 页（实体）的<b>详情覆盖层</b>：铺满 400×232、压在整页之上，点某一行实体才弹出来。
 *
 * <h2>一、交互照本工程已有的 {@code ShanhaiCreativeIconPicker}</h2>
 * 那个类就是"面板内弹出的子 {@link WidgetGroup}"的现成范式，本类照抄它四条：
 * <ol>
 *   <li><b>自带背景、铺满整块面板</b>；</li>
 *   <li>{@code open()} / {@code close()} 只管 {@code setVisible}/{@code setActive}；</li>
 *   <li>🔴 <b>整层吃掉点击</b>（{@link #mouseClicked}）—— 否则"点在覆盖层空白处"会穿到底下的
 *       页签/「确认修改并重载」，玩家会看到"我在详情里点了一下，怎么世界重载了"；</li>
 *   <li>底部一组明确的动作按钮（这里是「写入」「删除这条」「取消」）。</li>
 * </ol>
 *
 * <h2>二、这一层<b>只管画</b></h2>
 * 状态机、16 个格子的解析、预演、带闸的写/删全在纯 JDK 的
 * {@link ShanhaiSaveEditEntityUi} 里（那样离线判据才能驱动"打开→编辑→关闭"）。
 * 本类只做三件事：照 {@link ShanhaiSaveEditLayout#entityOverlaySlots()} 建控件、把模型里的字
 * 显示出来、把三个按钮的点击转给面板。
 *
 * <h2>三、🔴 这 16 个框<b>预填现值</b>，但没有 textSupplier</h2>
 * LDLib 的 {@code updateScreen} 只在 {@code isClientSideWidget && textSupplier != null} 时覆盖文本
 * —— 给玩家正在打字的框挂一个 supplier，会把他刚敲的字每 tick 冲掉一次。
 * 所以回填走的是"服务端写 + 一次 update-info"（{@link PrefillField#pushText}，就是
 * {@code TextFieldWidget.readUpdateInfo(1)} 认的那个包，与面板里的 {@code FillField} 同一条通道）：
 * 打开详情时服务端把 16 个框都填成<b>盘上的现值</b>，客户端那一份跟着收到同一批字。
 *
 * <h2>四、读数与 dangers 为什么是"推来的字"</h2>
 * 与第 ②③④ 页同一条口径：服务端读盘之后把字面串写进模型，{@code LabelWidget} 的
 * update-info 通道（{@link ShanhaiLdlText#sup}）把它推到客户端那份。
 * 覆盖层里<b>没有一个</b> {@code SlotWidget}，也不涉及任何物品格子。
 */
public final class ShanhaiSaveEditEntityOverlay extends WidgetGroup {

    /**
     * 两个动作按钮上的字（🔴 <b>没有"再按一次"那一步</b>：用户明确要求"除非我指定，否则都不加二次确认"，
     * 所以 {@code Arm} 机制连同"再按=写入 / 再按=删"的提示一起删掉了）。
     */
    private static final String WRITE_IDLE = "gt_shanhai.saveedit.text.5c783c4679655185";
    private static final String DELETE_IDLE = "gt_shanhai.saveedit.text.ae163c95ea366065";

    /**
     * 本条 update-info 的 id：**开 / 关**。
     *
     * <p>为什么需要它：{@code setVisible} 只作用于本侧，而"该不该关"只有服务端知道
     * （例如"删除已经暂存成功，回列表"）。没有这条通道，服务端关掉了、客户端那份还盖在上面
     * —— 玩家会以为没删成。与 {@code AuditButton.pushActive} 同一条通道。
     */
    static final int UPDATE_OPEN = 79;

    /**
     * 本条 update-info 的 id：**标题的原料**（类型注册名 + UUID）。
     *
     * <p>为什么标题也要开一条通道：{@code 中文在前} 的名字只有客户端查得到（服务端没有语言表）。
     * 若把它做成普通（服务端推字）的标签，服务端算出来的 {@code id〔无译名〕} 会把客户端的
     * 本地化结果盖掉。所以标题是 {@code setClientSideWidget()} 的，只推原料。
     */
    static final int UPDATE_TITLE = 80;

    private final ShanhaiSaveEditPanel panel;
    /** 本侧是不是客户端那份（只有服务端那份才 push）。 */
    private final boolean remote;

    /** 16 个输入框（下标 = {@link ShanhaiSaveEditEntityUi#inputs()} 的下标）。 */
    private final PrefillField[] fields = new PrefillField[ShanhaiSaveEditEntityUi.inputs().size()];
    /** 标题 / 5 行读数 / 3 行 dangers 的标签（都是"服务端推字"的那一类）。 */
    private LabelWidget title;
    private final LabelWidget[] notes = new LabelWidget[ShanhaiSaveEditLayout.OV_NOTE_LINES];
    private final LabelWidget[] dangers = new LabelWidget[ShanhaiSaveEditLayout.OV_DANGER_LINES];

    private final ShanhaiSaveEditEntityUi.Model model = new ShanhaiSaveEditEntityUi.Model();

    public ShanhaiSaveEditEntityOverlay(ShanhaiSaveEditPanel panel, boolean remote) {
        super(0, 0, ShanhaiSaveEditLayout.W, ShanhaiSaveEditLayout.H);
        this.panel = panel;
        this.remote = remote;
        this.setBackground(GuiTextures.BACKGROUND);

        for (ShanhaiSaveEditLayout.Slot slot : ShanhaiSaveEditLayout.entityOverlaySlots()) {
            this.addWidget(build(slot));
        }
        this.setVisible(false);
        this.setActive(false);
    }

    /** 照槽位表建一个控件（🔴 每一个槽位都必须<b>恰好</b>产出一个控件 —— 数控件就靠这条）。 */
    private Widget build(ShanhaiSaveEditLayout.Slot slot) {
        final int x = slot.x();
        final int y = slot.y();
        final int w = slot.w();
        final int h = slot.h();
        switch (slot.key()) {
            case "title": {
                // 🔴 标题是"客户端自己画"的：类型名要在客户端查语言表（服务端只会拿到
                //    entity.minecraft.xxx 这种键），所以它 setClientSideWidget()（服务端不推字），
                //    原料（类型注册名 + UUID）走 UPDATE_TITLE 推过来，画的时候现拼（中文在前）。
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
            case "danger": {
                final int idx = slot.index();
                this.dangers[idx] = new LabelWidget(x, y,
                        ShanhaiLdlText.sup(() -> ShanhaiSaveEditPanel.fit(this.model.dangerLine(idx), w)));
                return this.dangers[idx];
            }
            case "in.label": {
                final ShanhaiSaveEditEntityUi.Input in = ShanhaiSaveEditEntityUi.inputs().get(slot.index());
                return new LabelWidget(x, y, in.label())
                        .setClientSideWidget()
                        .setHoverTooltips(Component.literal("§e" + in.label() + "§7：" + in.hint()));
            }
            case "in.field": {
                final int idx = slot.index();
                final PrefillField f = new PrefillField(x, y, w, h);
                f.setMaxStringLength(ShanhaiSaveEditEntityUi.maxChars(idx));
                f.setHoverTooltips(Component.literal(com.dishanhai.gt_shanhai.common.saveedit.ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.4f4fd85e088ea0be")
                        + ShanhaiSaveEditEntityUi.inputs().get(idx).hint()));
                this.fields[idx] = f;
                return f;
            }
            case "write":
                return ShanhaiSaveEditPanel.mkButton(x, y, w, h, () -> ShanhaiSaveEditText.text(WRITE_IDLE),
                        this.panel::onOverlayWrite);
            case "delete":
                return ShanhaiSaveEditPanel.mkButton(x, y, w, h, () -> ShanhaiSaveEditText.text(DELETE_IDLE),
                        this.panel::onOverlayDelete);
            case "cancel":
                return ShanhaiSaveEditPanel.mkButton(x, y, w, h, () -> com.dishanhai.gt_shanhai.common.saveedit.ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.2cd0f3be8738a86c"), this.panel::onOverlayCancel);
            default:
                throw new IllegalStateException(com.dishanhai.gt_shanhai.common.saveedit.ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.861f382ef2a08a8c") + slot.key());
        }
    }

    /**
     * 能把文字<b>推给客户端</b>的输入框（与 {@code ShanhaiSaveEditPanel.FillField} 同一条通道）。
     *
     * <p>为什么需要它：LDLib 的 {@code TextFieldWidget} 只在自己有 {@code textSupplier} 时才会
     * 把值推给客户端（{@code detectAndSendChanges} 里 {@code writeUpdateInfo(1, …)}）；没有 supplier
     * 的输入框全是"客户端 → 服务端"单向。而回填<b>必须</b>是服务端 → 客户端（详情只有服务端读得到），
     * 所以只能：服务端 {@code setCurrentString} + 手动 {@code writeUpdateInfo(1, buf.writeUtf(value))}
     * —— 客户端 {@code TextFieldWidget.readUpdateInfo(1)} 认的就是这个包（字节码实证，见 FillField）。
     */
    private static final class PrefillField extends TextFieldWidget {

        PrefillField(int x, int y, int w, int h) {
            super(x, y, w, h, null, null);
        }

        /** 🔴 只在服务端调：把 {@code text} 同时写到本侧控件与客户端那一份。 */
        void pushText(String text) {
            final String v = text == null ? "" : text;
            setCurrentString(v);
            writeUpdateInfo(1, buf -> buf.writeUtf(v));
        }
    }

    // ------------------------------------------------------------------ 开 / 关

    /** 打开（两侧各跑一次）：清空 16 个格子 —— 上一条实体的残留输入绝不许带进这一条。 */
    public void open() {
        this.model.open();
        clearInputs();
        this.setVisible(true);
        this.setActive(true);
        pushOpen(true);
    }

    /**
     * 服务端读完盘之后调：把这一条实体的读数灌进模型，并把 16 个框<b>预填成盘上的现值</b>
     * （客户端那一份靠每个框自己的 1 号 update-info 收到同样的字）。
     */
    public void openDetail(ShanhaiEntityEdit.Detail detail, int chunkEntityCount) {
        this.model.openDetail(detail, chunkEntityCount);
        applyPrefill();
        this.setVisible(true);
        this.setActive(true);
        pushOpen(true);
        // 标题的原料（客户端自己拼：中文在前）
        pushTitle(detail == null ? "" : detail.id(), detail == null ? "" : detail.uuid());
    }

    /**
     * 🔴 把 16 个框填成"盘上的现值"（{@link ShanhaiSaveEditEntityUi.Model#prefill()}）。
     *
     * <p><b>只在服务端有意义</b>：本侧控件与客户端那一份一起写（{@link PrefillField#pushText}）。
     * 读不出来的项给的是空串 ⇒ 顺带把上一条实体的残留清掉。
     */
    private void applyPrefill() {
        if (this.remote) {
            return;
        }
        final String[] prefill = this.model.prefill();
        for (int i = 0; i < this.fields.length; i++) {
            final PrefillField f = this.fields[i];
            if (f != null) {
                f.pushText(i < prefill.length ? prefill[i] : "");
            }
        }
    }

    /** 🔴 只在服务端调：把"类型注册名 + UUID"推给客户端（标题由客户端现拼）。 */
    private void pushTitle(String typeId, String uuid) {
        if (this.remote) {
            return;
        }
        final String id = typeId == null ? "" : typeId;
        final String u = uuid == null ? "" : uuid;
        this.model.setTitlePayload(id, u);
        writeUpdateInfo(UPDATE_TITLE, buf -> {
            buf.writeUtf(id);
            buf.writeUtf(u);
        });
    }

    /** 关掉（两侧各跑一次）：模型与 16 个格子一起清空。 */
    public void close() {
        this.model.close();
        clearInputs();
        this.setVisible(false);
        this.setActive(false);
        pushOpen(false);
    }

    /** 🔴 只在服务端调：把"开着 / 关着"推给客户端那份（{@code setVisible} 只作用于本侧）。 */
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
            // 客户端收"标题原料"（类型注册名 + UUID），画的时候用 typeLabel 现拼（中文在前）
            this.model.setTitlePayload(buffer.readUtf(), buffer.readUtf());
            return;
        }
        super.readUpdateInfo(id, buffer);
    }

    /** 客户端在"点了一行"的瞬间先把覆盖层打开（此时还没有读数）。 */
    public void openPlaceholder(String rowText) {
        open();
        this.model.openPlaceholder(rowText);
    }

    public ShanhaiSaveEditEntityUi.Model model() {
        return this.model;
    }

    /** 16 个格子里的字（一次取全，交给模型解析）。 */
    public String[] readInputs() {
        final String[] out = new String[this.fields.length];
        for (int i = 0; i < this.fields.length; i++) {
            final String s = this.fields[i] == null ? null : this.fields[i].getCurrentString();
            out[i] = s == null ? "" : s.trim();
        }
        return out;
    }

    /** 清空 16 个格子（两侧各清自己那份；服务端那份不用 push —— 文本方向是"客户端 → 服务端"）。 */
    private void clearInputs() {
        for (PrefillField f : this.fields) {
            if (f != null) {
                f.setCurrentString("");
            }
        }
    }

    /**
     * 🔴 <b>整层吃掉点击</b>（与 {@code ShanhaiCreativeIconPicker#mouseClicked} 同一条理由）：
     * 落在覆盖层身上、但没打中任何子控件的那些点击，绝不能穿到底下的页签 / 「确认修改并重载」。
     */
    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        super.mouseClicked(mouseX, mouseY, button);
        return isMouseOverElement(mouseX, mouseY);
    }

    /** 给判据/日志用的一行读数（不依赖任何实例状态）。 */
    public static String layoutLine() {
        return "entity_overlay slots=" + ShanhaiSaveEditLayout.entityOverlaySlots().size()
                + " inputs=" + ShanhaiSaveEditEntityUi.inputs().size()
                + " notes=" + ShanhaiSaveEditLayout.OV_NOTE_LINES
                + " dangers=" + ShanhaiSaveEditLayout.OV_DANGER_LINES
                + " buttons=" + 3
                + " textSupplier_on_fields=0 slotWidget=0";
    }

    /** 给面板读"这个覆盖层当前是不是开着"（切页时用）。 */
    public boolean isOpen() {
        return this.model.opened();
    }

}
