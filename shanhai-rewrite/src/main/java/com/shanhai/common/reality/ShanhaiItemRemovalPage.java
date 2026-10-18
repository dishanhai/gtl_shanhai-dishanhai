package com.shanhai.common.reality;

import com.gregtechceu.gtceu.api.gui.GuiTextures;
import com.lowdragmc.lowdraglib.gui.widget.LabelWidget;
import com.lowdragmc.lowdraglib.gui.widget.WidgetGroup;
import com.shanhai.common.itemedit.ShanhaiItemOverrideTable;
import net.minecraft.resources.ResourceLocation;

/**
 * 🆕 2026-10-17：三页签面板的第 ③ 页 —— <b>「删除 / 抹除物品」</b>（只有界面，执行面全是现成的后端）。
 *
 * <h2>一屏长什么样（340×300，坐标相对本页组）</h2>
 * <pre>
 *   y=2      标题
 *   y=4..156 搜索结果块（与「修改」页共用 {@link ShanhaiItemSearchPane}，状态各自独立）
 *   y=160    选中的 id
 *   y=176    当前状态：正常 / 藏起来(hidden) / 已抹除(removed)
 *   y=192    「影响面（服务端现算）」标题
 *   y=206    作为产出：原版 N 条 / GT M 条
 *   y=220    作为输入：原版 N 条 / GT M 条
 *   y=234    只产它：N 条
 *   y=248    方块：N 个 / 不是方块
 *   y=262    状态行
 *   y=276    [看影响面] [藏起来] [抹除] [撤回] [关闭]
 * </pre>
 *
 * <h2>🔴 影响面为什么必须走服务端</h2>
 * {@code ShanhaiItemRemovalOps.impact(server, id)} 要一个 {@code MinecraftServer}（配方表在服务端那一侧）。
 * 在客户端那份页组里 {@code server == null} ⇒ 所有数字都是 0，那就是<b>假读数</b>
 * （本工程的纪律：宁可缺，不可假）⇒ 走一条 C2S/C2S-S2C 往返：
 * 点一下 ⇒ {@link ShanhaiCreativeItemsNetwork#requestImpact} ⇒ 服务端调现成的 {@code impact()}
 * ＋{@code blockCountLine()} ⇒ 回一条 S2C ⇒ 这里 {@code setText} 现写。
 *
 * <h2>🔴 「抹除要先看过影响面」这道闸门</h2>
 * 后端 {@code remove(server, id, impactOk)} 本来就有这个形参；本页在
 * <b>没有收到当前这个 id 的影响面结果</b>时按钮只给灰字提示、不发消息；
 * 服务端那一侧另外按"这个玩家这个 id 请求过 impact 没有"再判一次（同 {@code /shanhai item remove} 的闸门）。
 */
final class ShanhaiItemRemovalPage extends WidgetGroup {

    /** 客户端当前打开着的这一页（S2C 结果回来时找它）。只有 {@code remote} 那份会登记。 */
    private static volatile ShanhaiItemRemovalPage active;

    private final boolean remote;

    private ShanhaiItemSearchPane pane;
    private ResourceLocation selectedId;

    private LabelWidget idLabel;
    private LabelWidget stateLabel;
    private LabelWidget outLabel;
    private LabelWidget inLabel;
    private LabelWidget soleLabel;
    private LabelWidget blockLabel;
    private LabelWidget statusLabel;

    /** 影响面结果属于哪个 id（{@code null} = 还没算过）。 */
    private String impactId;
    private ShanhaiCreativeItemsNetwork.ImpactResult impact;

    ShanhaiItemRemovalPage(boolean remote) {
        super(0, 0, ShanhaiCreativeItemsPanel.W, ShanhaiCreativeItemsPanel.H);
        this.remote = remote;
        this.setBackground(GuiTextures.BACKGROUND);

        this.addWidget(new LabelWidget(6, 2, "§f删除 / 抹除物品 §7· 搜 id → 看影响面 → 藏起来 / 抹除 / 撤回"));

        this.pane = new ShanhaiItemSearchPane(remote, this::select);
        this.pane.install(this);

        this.idLabel = new LabelWidget(6, 160, "§8（还没选物品）");
        this.addWidget(this.idLabel);
        this.stateLabel = new LabelWidget(6, 176, "§7当前状态：§8—");
        this.addWidget(this.stateLabel);
        this.addWidget(new LabelWidget(6, 192, "§7影响面（服务端现算，来自 ShanhaiItemRemovalOps）"));
        this.outLabel = new LabelWidget(6, 206, "§8作为产出：—");
        this.addWidget(this.outLabel);
        this.inLabel = new LabelWidget(6, 220, "§8作为输入：—");
        this.addWidget(this.inLabel);
        this.soleLabel = new LabelWidget(6, 234, "§8只产它：—");
        this.addWidget(this.soleLabel);
        this.blockLabel = new LabelWidget(6, 248, "§8方块：—");
        this.addWidget(this.blockLabel);

        this.statusLabel = new LabelWidget(6, 262, "§7选中一个物品后自动算影响面");
        this.addWidget(this.statusLabel);

        this.addWidget(new ShanhaiCreativeWidgets.TextButton(6, 276, 74, 16,
                () -> "§f看影响面", this::requestImpact));
        this.addWidget(new ShanhaiCreativeWidgets.TextButton(84, 276, 68, 16,
                () -> "§e藏起来", () -> action(ShanhaiCreativeItemsNetwork.ACTION_HIDE_ON)));
        this.addWidget(new ShanhaiCreativeWidgets.TextButton(156, 276, 64, 16,
                () -> "§c抹除", this::remove));
        this.addWidget(new ShanhaiCreativeWidgets.TextButton(224, 276, 56, 16,
                () -> "§a撤回", () -> action(ShanhaiCreativeItemsNetwork.ACTION_RESTORE)));
        this.addWidget(new ShanhaiCreativeWidgets.TextButton(284, 276, 50, 16,
                () -> "§f关闭", this::close));

        if (remote) {
            active = this;
        }
    }

    // ------------------------------------------------------------------ 选中

    private void select(ResourceLocation id) {
        this.selectedId = id;
        this.impactId = null;
        this.impact = null;
        ShanhaiItemOverrideTable.refresh();
        final ShanhaiItemOverrideTable.Entry e = ShanhaiItemOverrideTable.get(id);
        if (this.idLabel != null) {
            this.idLabel.setText("§7选中：§f" + id);
        }
        if (this.stateLabel != null) {
            this.stateLabel.setText(stateText(e));
        }
        clearImpactLines("§8正在服务端算影响面…");
        this.setStatus(ShanhaiItemOverrideTable.isReserved(id)
                ? "§c现实物品壳不吃覆盖 ⇒ 这一页对它无效（请用「新增」页）"
                : "§7已选中 §f" + id + " §8· 等影响面算完再决定藏还是抹");
        requestImpact();
        com.shanhai.ShanhaiMod.LOGGER.info("[SHANHAI-ITEMEDIT] page3_select id={} state={}",
                id, e == null ? "<none>" : e.signature());
    }

    private static String stateText(ShanhaiItemOverrideTable.Entry e) {
        if (e == null) {
            return "§7当前状态：§a正常（没有任何覆盖项）";
        }
        if (e.removed()) {
            return "§7当前状态：§c已抹除（removed：藏 ＋ 抹实例 ＋ 摘配方产出）";
        }
        if (e.hidden()) {
            return "§7当前状态：§e藏起来（hidden：JEI / 创造栏看不到，实例还在）";
        }
        return "§7当前状态：§a正常 §8（有别的覆盖项，但没藏也没抹）";
    }

    private void clearImpactLines(String note) {
        if (this.outLabel != null) {
            this.outLabel.setText(note);
        }
        if (this.inLabel != null) {
            this.inLabel.setText("");
        }
        if (this.soleLabel != null) {
            this.soleLabel.setText("");
        }
        if (this.blockLabel != null) {
            this.blockLabel.setText("");
        }
    }

    // ------------------------------------------------------------------ 影响面（服务端回填）

    private void requestImpact() {
        if (!this.remote) {
            return;
        }
        if (this.selectedId == null) {
            this.setStatus("§c先在上面选一个物品");
            return;
        }
        clearImpactLines("§8正在服务端算影响面…");
        this.setStatus("§7已请求影响面 §8（服务端算，几百毫秒内回来）");
        ShanhaiCreativeItemsNetwork.requestImpact(this.selectedId.toString());
    }

    /** 服务端那条 S2C 回来时由 {@link ShanhaiCreativeItemsNetwork} 调（客户端线程）。 */
    static void onImpactResult(ShanhaiCreativeItemsNetwork.ImpactResult result) {
        final ShanhaiItemRemovalPage page = active;
        if (page == null || result == null || !page.remote) {
            return;
        }
        page.acceptImpact(result);
    }

    private void acceptImpact(ShanhaiCreativeItemsNetwork.ImpactResult r) {
        if (this.selectedId != null && !this.selectedId.toString().equals(r.id())) {
            return;      // 已经切到别的物品了，旧结果丢掉（别把别人的数字显示成这个的）
        }
        this.impactId = r.id();
        this.impact = r;
        if (this.outLabel != null) {
            this.outLabel.setText("§7作为产出：原版 §f" + r.outVanilla() + " §7条 / GT §f" + r.outGt() + " §7条");
        }
        if (this.inLabel != null) {
            this.inLabel.setText("§7作为输入：原版 §f" + r.inVanilla() + " §7条 / GT §f" + r.inGt() + " §7条");
        }
        if (this.soleLabel != null) {
            this.soleLabel.setText("§7只产它：§f" + r.soleOutput()
                    + " §7条§8（摘掉产出后会变空 ⇒ 整条停用）");
        }
        if (this.blockLabel != null) {
            this.blockLabel.setText(r.blockTotal() < 0
                    ? "§7世界里：§8不是方块（not_a_block）"
                    : "§7世界里：§f" + r.blockTotal() + " §7个方块 §8（半径 " + r.blockRadius()
                            + " 区块 / 读到 " + r.blockChunks() + " 个"
                            + (r.blockPartial() ? " / §c撞到预算提前收工" : "") + "；只统计不删）");
        }
        this.setStatus("§a影响面已算完 §8· 现在可以点「藏起来」或「抹除」");
    }

    // ------------------------------------------------------------------ 三个动作

    /** 「抹除」必须先有当前这个 id 的影响面结果（界面上与后端同一道闸门）。 */
    private void remove() {
        if (this.selectedId == null) {
            this.setStatus("§c先选物品");
            return;
        }
        if (this.impact == null || this.impactId == null
                || !this.impactId.equals(this.selectedId.toString())) {
            this.setStatus("§c先点「看影响面」（抹除前必须看过影响面 —— 后端也会拒）");
            return;
        }
        action(ShanhaiCreativeItemsNetwork.ACTION_REMOVE);
    }

    private void action(int what) {
        if (!this.remote) {
            return;
        }
        if (this.selectedId == null) {
            this.setStatus("§c先选物品");
            return;
        }
        if (ShanhaiItemOverrideTable.isReserved(this.selectedId)) {
            this.setStatus("§c" + this.selectedId + " 是现实物品壳，这一页对它无效（后端会拒）");
            return;
        }
        ShanhaiCreativeItemsNetwork.requestRemoval(this.selectedId.toString(), what);
        this.setStatus(switch (what) {
            case ShanhaiCreativeItemsNetwork.ACTION_HIDE_ON -> "§e已请求「藏起来」…";
            case ShanhaiCreativeItemsNetwork.ACTION_HIDE_OFF -> "§e已请求「取消隐藏」…";
            case ShanhaiCreativeItemsNetwork.ACTION_REMOVE -> "§c已请求「抹除」…";
            default -> "§a已请求「撤回」…";
        });
        com.shanhai.ShanhaiMod.LOGGER.info("[SHANHAI-ITEMEDIT] page3_action id={} what={}",
                this.selectedId, what);
    }

    private void close() {
        if (this.remote && this.closeAction != null) {
            this.closeAction.run();
        }
    }

    private Runnable closeAction;

    void onClose(Runnable action) {
        this.closeAction = action;
    }

    // ------------------------------------------------------------------ 文本 / 生命周期

    private void setStatus(String text) {
        if (this.statusLabel != null) {
            this.statusLabel.setText(text == null ? "" : text);
        }
    }

    @Override
    public void updateScreen() {
        super.updateScreen();
        if (!this.remote) {
            return;
        }
        if (active != this) {
            active = this;       // 面板重开过一次（旧实例可能已经被丢掉）
        }
        if (this.pane != null) {
            this.pane.tick();
        }
    }

    static String layoutLine() {
        return "page_removal " + ShanhaiCreativeItemsPanel.W + "x" + ShanhaiCreativeItemsPanel.H
                + " impact=server_side actions=hide|remove|restore";
    }
}
