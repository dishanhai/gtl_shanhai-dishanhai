package com.dishanhai.gt_shanhai.common.patternmover;

import com.gregtechceu.gtceu.api.gui.GuiTextures;
import com.lowdragmc.lowdraglib.gui.texture.GuiTextureGroup;
import com.lowdragmc.lowdraglib.gui.texture.TextTexture;
import com.lowdragmc.lowdraglib.gui.widget.ButtonWidget;
import com.lowdragmc.lowdraglib.gui.widget.LabelWidget;
import com.lowdragmc.lowdraglib.gui.widget.WidgetGroup;
import com.dishanhai.gt_shanhai.common.patternmover.compat.ShanhaiLdlText;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;

import java.util.function.Supplier;

/**
 * <b>清空二次确认页</b>（G）—— 用户原话：「这个加一个额外确认」。
 *
 * <h2>一、这一页写清三件事</h2>
 * <pre>
 *   ① 哪台主机：坐标 ＋ 主机种类（原始终焉引擎 / 归墟视界枢核）
 *   ② 几个总成：assemblies 台（下面逐台列出坐标 ＋ 模块名 ＋ 槽数）
 *   ③ 共 N 块样板：occupied（= 真有样板的那几格）
 * </pre>
 * 两个按钮：「<b>确认清空</b>」（服务端真清）／「<b>取消</b>」（一个字节都不写，直接关界面）。
 *
 * <h2>二、🔴 「取消」为什么是结构性的</h2>
 * 打开本页时只调过 {@link HostPatternMoverClear#plan}（只读）；真清只在
 * {@link HostPatternMoverActions#confirmClear}。⇒ 取消走完，机器指纹必然逐字节不变
 * （自检第 21 组就是拿这个当判据，并配一条"真清 ⇒ 指纹必须变"的负对照）。
 *
 * <h2>三、LDLib 铁律</h2>
 * 与 {@link HostPatternMoverConfirmPanel} 类注释 §二 同一条：文本控件一律 Supplier 构造器、
 * 每个字都过 {@code sanitize}、控件数在构造时定死（总成行数上限 6，超出用一行收尾）。
 */
public final class HostPatternMoverClearPanel extends WidgetGroup {

    /** 面板宽（与另外两张同宽）。 */
    public static final int W = 340;

    /** 面板高。 */
    public static final int H = 196;

    /** 最多画几行总成。 */
    private static final int MAX_ASSEMBLY_LINES = 6;

    private static final int LINE_H = 12;

    private final HostPatternMoverFactory.Holder holder;
    private final transient Player player;

    /** 服务端：已经在跑（防双击连发两次清空）。 */
    private transient boolean busy;

    private HostPatternMoverClearPanel(HostPatternMoverFactory.Holder holder, Player player) {
        super(0, 0, W, H);
        this.holder = holder;
        this.player = player;
        this.setBackground(GuiTextures.BACKGROUND);

        this.addWidget(label(6, 5, () -> "§c§l清空样板 §8· §7目标主机 §f" + posText(this.preview().pos())
                + " §8(" + hostName(this.preview().hostTag()) + ")"));
        this.addWidget(label(6, 20, this::summaryLine));
        this.addWidget(label(6, 34, () -> "§7① 目标总成 §8(所属模块 · 槽数)"));
        for (int row = 0; row < MAX_ASSEMBLY_LINES; row++) {
            final int index = row;
            this.addWidget(label(6, 46 + row * LINE_H, () -> assemblyLine(index)));
        }
        this.addWidget(label(6, 46 + MAX_ASSEMBLY_LINES * LINE_H, this::assemblyOverflowLine));
        this.addWidget(label(6, 124, () -> "§8清空 = 把这些总成的样板槽逐格设空"
                + "（上游自己会通知 AE 重算样板）"));
        this.addWidget(label(6, 136, () -> "§8⚠ 不可撤销：这批样板不会交回物品，也不会掉在地上"));
        this.addWidget(button(6, 154, 160, 20, "§c确认清空", this::onConfirm));
        this.addWidget(button(174, 154, 160, 20, "§a取消", this::onCancel));
        this.addWidget(label(6, 178, () -> "§7状态 §f" + status()));
    }

    /** 建面板（两侧各建一份；结构完全一致 ⇒ 控件 id 一致 ⇒ 客户端动作能对上服务端控件）。 */
    public static HostPatternMoverClearPanel create(HostPatternMoverFactory.Holder holder, Player player) {
        return new HostPatternMoverClearPanel(holder, player);
    }

    // ================================================================ 按钮

    private void onConfirm() {
        if (this.busy) {
            return;
        }
        if (!(this.player instanceof ServerPlayer serverPlayer)) {
            return; // 客户端那次什么都不做（真正的清空只在服务端）
        }
        if (!this.holder.canInteract(serverPlayer)) {
            this.setState("§c目标已失效或无交互权限，请重新打开");
            return;
        }
        this.busy = true;
        final HostPatternMoverFactory.ClearPreview preview = this.preview();
        try {
            final HostPatternMoverClear.Result r =
                    HostPatternMoverActions.confirmClear(serverPlayer, preview.pos());
            this.setState(r.complete() ? "§a已清空 " + r.cleared() + " 块"
                    : "§e已清空 " + r.cleared() + " / " + r.occupied() + " 块"
                    + (r.failed() > 0 ? "（" + r.failed() + " 格没清掉）" : ""));
            // 🔴 延到下一个服务端 tick 再关界面（理由见 ConfirmPanel#closeNextTick）
            closeNextTick(serverPlayer);
            return;
        } catch (Throwable t) {
            HostPatternMoverLog.warn("{} 清空主机失败 err={}", HostPatternMoverLog.PREFIX, t.toString());
            this.setState("§c清空失败（看日志 [SHANHAI-MOVER]）");
        }
        this.busy = false;
    }

    private void onCancel() {
        if (!(this.player instanceof ServerPlayer serverPlayer)) {
            return;
        }
        // 取消 = 直接关界面；本面板在此之前【从未】调过任何写槽的东西
        HostPatternMoverLog.info("{} clear_cancelled player={} pos={}",
                HostPatternMoverLog.PREFIX, serverPlayer.getName().getString(), this.preview().pos());
        closeNextTick(serverPlayer);
    }

    private static void closeNextTick(ServerPlayer player) {
        try {
            player.server.execute(player::closeContainer);
        } catch (Throwable t) {
            player.closeContainer();
        }
    }

    // ================================================================ 文本

    private HostPatternMoverFactory.ClearPreview preview() {
        return this.holder == null ? HostPatternMoverFactory.ClearPreview.EMPTY
                : this.holder.clearPreview();
    }

    private String status() {
        final String s = this.holder == null ? "" : this.holder.status();
        return HostPatternMoverConfirmPanel.sanitize(s == null || s.isEmpty() ? "（还没执行）" : s);
    }

    private void setState(String text) {
        if (this.holder != null) {
            this.holder.setStatus(text);
        }
    }

    private String summaryLine() {
        final HostPatternMoverFactory.ClearPreview p = this.preview();
        return HostPatternMoverConfirmPanel.sanitize("§7将清空 §c" + p.occupied() + " §7块样板 · §7总成 §f"
                + p.assemblies() + " §7台 · §7槽位共 §f" + p.slots() + " §7格");
    }

    private String assemblyLine(int index) {
        final HostPatternMoverFactory.ClearPreview p = this.preview();
        if (index >= p.infos().size()) {
            return "";
        }
        final HostPatternMoverTargets.AssemblyInfo info = p.infos().get(index);
        return HostPatternMoverConfirmPanel.sanitize("§f"
                + HostPatternMoverConfirmPanel.fit(info.label(), 96) + " §7"
                + HostPatternMoverConfirmPanel.fit(
                HostPatternMoverConfirmPanel.moduleName(info.moduleId()), 150)
                + " §8[" + info.slots() + " 格]");
    }

    private String assemblyOverflowLine() {
        final int total = this.preview().infos().size();
        if (total <= MAX_ASSEMBLY_LINES) {
            return total == 0 ? "§8（这台机器上没有任何样板总成 ⇒ 没东西可清）" : "";
        }
        return HostPatternMoverConfirmPanel.sanitize("§8…还有 " + (total - MAX_ASSEMBLY_LINES) + " 台未显示");
    }

    private static String hostName(String hostTag) {
        if (HostPatternMoverReader.HOST_GUIXU.equals(hostTag)) {
            return "归墟视界枢核";
        }
        if (HostPatternMoverReader.HOST_PRIMORDIAL.equals(hostTag)) {
            return "原始终焉引擎";
        }
        return hostTag == null || hostTag.isEmpty() ? "?" : hostTag;
    }

    private static String posText(net.minecraft.core.BlockPos pos) {
        return pos == null ? "?" : (pos.getX() + ", " + pos.getY() + ", " + pos.getZ());
    }

    // ================================================================ 控件工厂

    private LabelWidget label(int x, int y, Supplier<String> text) {
        return new LabelWidget(x, y, ShanhaiLdlText.sup(
                () -> HostPatternMoverConfirmPanel.sanitize(text.get())));
    }

    private ButtonWidget button(int x, int y, int w, int h, String text, Runnable action) {
        final String fixed = HostPatternMoverConfirmPanel.sanitize(text);
        return new ButtonWidget(x, y, w, h,
                new GuiTextureGroup(GuiTextures.BUTTON,
                        new TextTexture((Supplier<String>) () -> fixed)),
                clickData -> {
                    if (clickData != null && clickData.isRemote) {
                        return;
                    }
                    try {
                        action.run();
                    } catch (Throwable t) {
                        HostPatternMoverLog.warn("{} 清空面板按钮失败 err={}",
                                HostPatternMoverLog.PREFIX, t.toString());
                    }
                });
    }
}
