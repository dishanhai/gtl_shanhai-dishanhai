package com.dishanhai.gt_shanhai.common.patternmover;

import com.gregtechceu.gtceu.api.gui.GuiTextures;
import com.lowdragmc.lowdraglib.gui.texture.GuiTextureGroup;
import com.lowdragmc.lowdraglib.gui.texture.TextTexture;
import com.lowdragmc.lowdraglib.gui.widget.ButtonWidget;
import com.lowdragmc.lowdraglib.gui.widget.LabelWidget;
import com.lowdragmc.lowdraglib.gui.widget.WidgetGroup;
import com.dishanhai.gt_shanhai.common.patternmover.compat.ShanhaiLdlText;
import com.dishanhai.gt_shanhai.common.patternmover.compat.ShanhaiTextParser;
import com.dishanhai.gt_shanhai.common.patternmover.compat.ShanhaiRecipeTypeLabels;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * <b>确认面板</b>（放置前弹出来的那一张）—— 用户口径：「一个普通 LDLib 面板，三列信息」。
 *
 * <h2>一、三列是什么（逐字照做）</h2>
 * <pre>
 *   ① 目标总成（含所属模块中文名）        每台一行：坐标 · 模块名 · 槽数 · 空槽
 *   ② 将放入 N 块（其中覆盖 R 块）        新填 / 覆盖 / 合计 / 可用空槽
 *   ③ 放不下 M 块（原因分类）             未识别类型 / 无对应模块 / 无空槽 / 合计
 * </pre>
 * 底部两个按钮：「<b>确认放置</b>」（服务端真写机器）／「<b>取消</b>」（一个字节都不写）。
 * <p>🔴 这一版<b>没有勾选</b>（用户没要求），也不许有：面板只做展示与确认。
 *
 * <h2>二、🔴 LDLib 铁律（本工程踩过崩服，见第一刀 {@link HostPatternMoverPanel} 的类注释）</h2>
 * <ol>
 *   <li>文本控件一律 {@code Supplier} 构造器：{@code LabelWidget(int,int,Supplier<String>)}、
 *       {@code TextTexture(Supplier<String>)}。{@code (int,int,Component)} 那个重载
 *       <b>只</b> {@code putfield component}、{@code textSupplier} 留 null ⇒ 服务端
 *       {@code detectAndSendChanges} 里那句无条件 {@code textSupplier.get()} 当场 NPE
 *       （本工程另一处面板上真栽过：2026-10-31 的翻页崩服）。</li>
 *   <li>每一个字都过 {@link #sanitize}（剥样式码 ＋ 去 {@code &} ＋ 裸 {@code %} ⇒ {@code %%}）。</li>
 *   <li><b>控件数在构造时定死</b> ⇒ 总成行数有上限，超了用一行「…还有 N 台」收尾。</li>
 * </ol>
 *
 * <h2>三、文字为什么在面板里现算，而不是服务端算好塞过来</h2>
 * 服务端没有语言表（{@code Component.translatable(...).getString()} 会返回裸 key）。
 * 所以 Holder 里只带<b>id</b>（{@code shanhai:xxx} 这种），中文名由<b>客户端那份面板</b>
 * 现查 lang（LDLib 的界面在两侧各建一次，玩家看到的是客户端这次）。
 */
public final class HostPatternMoverConfirmPanel extends WidgetGroup {

    /** 面板宽（三列：112 / 106 / 96 ＋ 边距）。 */
    public static final int W = 340;

    /** 面板高（三列 ＋ 两个按钮 ＋ 状态 ＋ 清空入口）。 */
    public static final int H = 232;

    /** 每列最多画几行总成（超出用一行收尾）。 */
    private static final int MAX_ASSEMBLY_LINES = 6;

    private static final int LINE_H = 12;

    /** 三列的左边界与可用宽度（像素）。 */
    private static final int COL1_X = 6;
    private static final int COL1_W = 116;
    private static final int COL2_X = 128;
    private static final int COL2_W = 104;
    private static final int COL3_X = 238;
    private static final int COL3_W = 96;

    private static final int HEADER_Y = 30;
    private static final int BODY_Y = 42;

    private final HolderProxy holder;
    private final transient Player player;

    /** 服务端：动作已经在跑（防止双击连发两次写）。 */
    private transient boolean busy;

    private HostPatternMoverConfirmPanel(HostPatternMoverFactory.Holder holder, Player player) {
        super(0, 0, W, H);
        this.holder = holder == null ? null : new HolderProxy(holder);
        this.player = player;
        this.setBackground(GuiTextures.BACKGROUND);

        final HostPatternMoverFactory.PlacePreview preview = this.preview();
        // ── 标题 / 目标
        this.addWidget(label(6, 5, () -> "§b§l确认放置样板 §8· §7目标主机 §f"
                + posText(preview.pos())));
        // ── 三列表头
        this.addWidget(label(COL1_X, HEADER_Y, () -> "§7① 目标总成 §8(所属模块)"));
        this.addWidget(label(COL2_X, HEADER_Y, () -> "§7② 将放入"));
        this.addWidget(label(COL3_X, HEADER_Y, () -> "§7③ 放不下"));
        // ── 三列正文（服务端/客户端同结构；客户端那份 supplier 会随 update info 刷新）
        for (int row = 0; row < MAX_ASSEMBLY_LINES; row++) {
            final int index = row;
            this.addWidget(label(COL1_X, BODY_Y + row * LINE_H,
                    () -> assemblyLine(index)));
        }
        this.addWidget(label(COL1_X, BODY_Y + MAX_ASSEMBLY_LINES * LINE_H,
                this::assemblyOverflowLine));
        for (int row = 0; row < 4; row++) {
            final int index = row;
            this.addWidget(label(COL2_X, BODY_Y + row * LINE_H, () -> putLine(index)));
        }
        for (int row = 0; row < 5; row++) {
            final int index = row;
            this.addWidget(label(COL3_X, BODY_Y + row * LINE_H, () -> skipLine(index)));
        }
        // ── 行为说明（取消是结构性的：本面板什么都不写）
        this.addWidget(label(6, 126, () -> "§8确认后：先写机器的样板槽 → 再把已放入的从物品里删掉；"
                + "放不下的原样留在物品里"));
        this.addWidget(label(6, 138, () -> "§8取消：一个字节都不写（面板只算过一份计划，没碰任何槽）"));
        // ── 两个按钮
        this.addWidget(button(6, 152, 160, 20, "§a确认放置", () -> onConfirm()));
        this.addWidget(button(174, 152, 160, 20, "§c取消", this::onCancel));
        // ── 结果/状态行
        this.addWidget(label(6, 178, () -> "§7状态 §f" + status()));
        // ── G：清空这台主机及其模块（红色，点了会再弹一页确认）
        this.addWidget(label(6, 190, () -> "§8清空：点下面这个红按钮 → 会再弹一页确认"
                + "（取消 ⇒ 一个字节都不写）"));
        this.addWidget(button(6, 204, 328, 20, "§c清空这台主机及其模块的全部样板", this::onClearHost));
    }

    /** 建面板（两侧各建一份；结构完全一致 ⇒ 控件 id 一致 ⇒ 客户端动作能对上服务端控件）。 */
    public static HostPatternMoverConfirmPanel create(HostPatternMoverFactory.Holder holder, Player player) {
        return new HostPatternMoverConfirmPanel(holder, player);
    }

    // ================================================================ 按钮

    private void onConfirm() {
        if (this.busy) {
            return;
        }
        if (!(this.player instanceof ServerPlayer serverPlayer)) {
            return; // 客户端那次回调什么都不做（真正的写入只在服务端）
        }
        if (!this.holder.canInteract(serverPlayer)) {
            this.setState("§c目标已失效或无交互权限，请重新打开");
            return;
        }
        this.busy = true;
        final HostPatternMoverFactory.PlacePreview preview = this.preview();
        final ItemStack stack = this.holder.tool(serverPlayer);
        if (stack == null) {
            this.setState("§c搬运工具已更换或移动，请重新打开面板");
            this.busy = false;
            return;
        }
        try {
            final boolean ok = HostPatternMoverActions.confirmPlace(serverPlayer, stack, preview.pos());
            if (ok) {
                this.setState("§a已执行");
                // 🔴 延到下一个服务端 tick 再关界面：这里正处在 LDLib 的控件动作派发栈里，
                //    当场 closeContainer 会把控件树在派发途中拆掉（同类面板踩过）。
                closeNextTick(serverPlayer);
                return;
            }
        } catch (Throwable t) {
            HostPatternMoverLog.warn("{} 确认放置抛异常 err={}", HostPatternMoverLog.PREFIX, t.toString());
            this.setState("§c放置失败（看日志 [SHANHAI-MOVER]）");
        }
        this.busy = false;
    }

    private void onCancel() {
        if (!(this.player instanceof ServerPlayer serverPlayer)) {
            return;
        }
        // 取消 = 直接关界面；本面板在此之前【从未】调过任何写槽的东西
        HostPatternMoverLog.info("{} place_cancelled player={}",
                HostPatternMoverLog.PREFIX, serverPlayer.getName().getString());
        closeNextTick(serverPlayer);
    }

    /**
     * G：「清空这台主机及其模块的全部样板」——<b>只弹二次确认页</b>，这里一个槽都不写。
     *
     * <p>为什么延到下一个服务端 tick：这里正处在 LDLib 的控件动作派发栈里，
     * 当场再 {@code openUI}（换一整个界面）会把控件树在派发途中拆掉（同类面板踩过，
     * 见 {@link #closeNextTick} 的注释）。
     */
    private void onClearHost() {
        if (!(this.player instanceof ServerPlayer serverPlayer)) {
            return;
        }
        final net.minecraft.core.BlockPos pos = this.preview().pos();
        this.setState("§7正在算「清空」范围…（还没清任何东西）");
        final Runnable open = () -> {
            try {
                if (!this.holder.canInteract(serverPlayer)) return;
                ItemStack tool = this.holder.tool(serverPlayer);
                if (tool == null) return;
                HostPatternMoverActions.openClearConfirm(serverPlayer, pos, tool, this.holder.hand());
            } catch (Throwable t) {
                HostPatternMoverLog.warn("{} 打开清空确认页失败 pos={} err={}",
                        HostPatternMoverLog.PREFIX, pos, t.toString());
            }
        };
        try {
            if (serverPlayer.server != null) {
                serverPlayer.server.execute(open);
            } else {
                open.run();
            }
        } catch (Throwable t) {
            open.run();
        }
    }

    /** 排到下一个服务端 tick 再关（避免在控件回调栈里拆控件树）。 */
    private static void closeNextTick(ServerPlayer player) {
        try {
            player.server.execute(player::closeContainer);
        } catch (Throwable t) {
            // 拿不到 server（理论上不会）⇒ 退回当场关：关不掉也不许把已写好的数据回滚
            player.closeContainer();
        }
    }

    // ================================================================ 文本

    private HostPatternMoverFactory.PlacePreview preview() {
        return this.holder == null ? HostPatternMoverFactory.PlacePreview.EMPTY : this.holder.preview();
    }

    private String status() {
        final String s = this.holder == null ? "" : this.holder.status();
        return sanitize(s == null || s.isEmpty() ? "（还没执行）" : s);
    }

    private void setState(String text) {
        if (this.holder != null) {
            this.holder.setStatus(text);
        }
    }

    /** ① 第 {@code index} 台目标总成。 */
    private String assemblyLine(int index) {
        final HostPatternMoverFactory.PlacePreview preview = this.preview();
        if (index >= preview.infos().size()) {
            return "";
        }
        final HostPatternMoverTargets.AssemblyInfo info = preview.infos().get(index);
        final String module = moduleName(info.moduleId());
        final String zones = info.normal() >= 0
                ? "§8[" + info.normal() + "+" + info.wildcard() + "]"
                : "§8[" + info.slots() + "]";
        return sanitize("§f" + fit(info.label(), 44) + " §7" + fit(module, 60) + " " + zones);
    }

    /** ① 超出上限时的那一行。 */
    private String assemblyOverflowLine() {
        final int total = this.preview().infos().size();
        if (total <= MAX_ASSEMBLY_LINES) {
            return total == 0 ? "§8（这台机器上没有任何样板总成）" : "";
        }
        return sanitize("§8…还有 " + (total - MAX_ASSEMBLY_LINES) + " 台未显示");
    }

    /** ② 将放入（4 行）。 */
    private String putLine(int index) {
        final HostPatternMoverFactory.PlacePreview p = this.preview();
        return sanitize(switch (index) {
            case 0 -> "§7新填 §f" + p.putNew() + " §7块";
            case 1 -> "§7覆盖 §f" + p.putReplace() + " §7块 §8(同配方 id)";
            case 2 -> "§7合计 §a" + p.put() + " §7块 §8/ 物品里 " + p.requested();
            default -> "§7可用空槽 §f" + freeSlots() + " §7格";
        });
    }

    /** ③ 放不下（5 行）。 */
    private String skipLine(int index) {
        final HostPatternMoverFactory.PlacePreview p = this.preview();
        return sanitize(switch (index) {
            case 0 -> "§7未识别类型 §f" + p.skipUnknown() + " §7块 §8(不放入)";
            case 1 -> "§7无对应模块/当前类型不符 §f" + p.skipNoModule() + " §7块 §8(不放入)";
            case 2 -> "§7无空槽/校验不过 §f" + p.skipNoSlot() + " §7块";
            case 3 -> "§7合计 §c" + p.skip() + " §7块 §8(留在物品里)";
            default -> "§8判据：跨类型并行主机=模块声明的全部类型；老主机=模块当前选中的那一个";
        });
    }

    private int freeSlots() {
        int sum = 0;
        for (HostPatternMoverTargets.AssemblyInfo info : this.preview().infos()) {
            if (info != null) {
                sum += info.free();
            }
        }
        return sum;
    }

    private static String posText(net.minecraft.core.BlockPos pos) {
        return pos == null ? "?" : (pos.getX() + ", " + pos.getY() + ", " + pos.getZ());
    }

    /**
     * 模块 id → 中文名。
     *
     * <p>走 {@code block.<ns>.<path>} 这条原版键（GT 的机器物品就是它）—— 客户端能翻出中文，
     * 服务端翻不出来就显示 id 的 path（<b>宁可丑也不编</b>）。
     */
    static String moduleName(String moduleId) {
        if (moduleId == null || moduleId.trim().isEmpty()) {
            return "未知模块";
        }
        final String id = moduleId.trim();
        final String ns = ShanhaiRecipeTypeLabels.namespaceOf(id);
        final String path = ShanhaiRecipeTypeLabels.pathOf(id);
        if (path.isEmpty()) {
            return id;
        }
        try {
            final String key = "block." + (ns.isEmpty() ? "minecraft" : ns) + "." + path;
            final String shown = Component.translatable(key).getString();
            if (shown != null && !shown.isEmpty() && !shown.equals(key)) {
                return shown;
            }
        } catch (Throwable ignored) {
            // 语言表没就绪 ⇒ 落到 path
        }
        return path;
    }

    // ================================================================ 控件工厂

    private LabelWidget label(int x, int y, java.util.function.Supplier<String> text) {
        return new LabelWidget(x, y, ShanhaiLdlText.sup(() -> sanitize(text.get())));
    }

    /**
     * 带文字的按钮：{@code GuiTextureGroup(GuiTextures.BUTTON, TextTexture(Supplier))}。
     *
     * <p>为什么用 {@code TextTexture(Supplier<String>)}：与上面第 1 条铁律同因
     * （{@code TextTexture(String)} 那个重载在某些 LDLib 版本里会让
     * {@code textSupplier} 留在 null，服务端 tick 时 NPE）。
     */
    private ButtonWidget button(int x, int y, int w, int h, String text,
                                Runnable action) {
        final String fixed = sanitize(text);
        return new ButtonWidget(x, y, w, h,
                new GuiTextureGroup(GuiTextures.BUTTON,
                        new TextTexture((java.util.function.Supplier<String>) () -> fixed)),
                clickData -> {
                    // 客户端那次回调 isRemote=true ⇒ 只有服务端那次执行（见 HostPatternMoverActions 类注释 §三）
                    if (clickData != null && clickData.isRemote) {
                        return;
                    }
                    try {
                        action.run();
                    } catch (Throwable t) {
                        HostPatternMoverLog.warn("{} 确认面板按钮失败 err={}",
                                HostPatternMoverLog.PREFIX, t.toString());
                    }
                });
    }

    /** 送进 LDLib 之前的净化（剥样式码 ⇒ 去 {@code &} ⇒ 裸 {@code %} 转义）。 */
    static String sanitize(String raw) {
        if (raw == null) {
            return "";
        }
        String s = raw;
        try {
            s = ShanhaiTextParser.stripStyleCode(s);
        } catch (Throwable ignored) {
            // 剥码失败不影响显示
        }
        if (s.indexOf('&') >= 0) {
            s = s.replace("&", "");
        }
        return ShanhaiLdlText.esc(s);
    }

    /**
     * 按「显示宽度」截断（中文 9 px/字、ASCII 6 px/字），超了补 {@code …}。
     *
     * <p>为什么必须截：{@code LabelWidget} <b>不换行</b>，超宽会画到隔壁那一列上。
     */
    static String fit(String text, int maxPx) {
        if (text == null) {
            return "";
        }
        int px = 0;
        for (int i = 0; i < text.length(); i++) {
            final char c = text.charAt(i);
            px += c < 0x2E80 ? 6 : 9;
            if (px > maxPx) {
                return text.substring(0, Math.max(0, i - 1)) + "…";
            }
        }
        return text;
    }

    // ================================================================ 状态代理

    /**
     * 面板与 Holder 之间的一层薄代理。
     *
     * <p>它存在的理由：{@code Holder} 是 {@code UIFactory} 的同步载体（两侧各一份），
     * 面板只读它、只在服务端写它；把这层收在一处，面板里就不会出现"到底是哪一侧"的散乱判断。
     */
    private static final class HolderProxy {

        private final HostPatternMoverFactory.Holder holder;

        HolderProxy(HostPatternMoverFactory.Holder holder) {
            this.holder = holder;
        }

        HostPatternMoverFactory.PlacePreview preview() {
            return this.holder.preview();
        }

        ItemStack tool(Player player) { return holder.tool(player); }
        net.minecraft.world.InteractionHand hand() { return holder.hand(); }
        boolean canInteract(ServerPlayer player) { return holder.canInteract(player); }

        String status() {
            return this.holder.status();
        }

        void setStatus(String text) {
            this.holder.setStatus(text);
        }
    }

    /** 三列的调试读数（自检/日志用；不进界面）。 */
    public static List<String> describe(HostPatternMoverFactory.PlacePreview preview) {
        final List<String> out = new ArrayList<>();
        if (preview == null) {
            return out;
        }
        out.add("requested=" + preview.requested());
        out.add("put=" + preview.put() + "(new=" + preview.putNew() + ",replace=" + preview.putReplace() + ")");
        out.add("skip=" + preview.skip() + "(unknown=" + preview.skipUnknown()
                + ",noModule=" + preview.skipNoModule() + ",noSlot=" + preview.skipNoSlot() + ")");
        for (HostPatternMoverTargets.AssemblyInfo info : preview.infos()) {
            out.add("assembly " + info.label() + " module=" + info.moduleId() + " slots=" + info.slots()
                    + " free=" + info.free());
        }
        return out;
    }
}
