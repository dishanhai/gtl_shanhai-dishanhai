package com.shanhai.common.reality;

import com.gregtechceu.gtceu.api.gui.GuiTextures;
import com.lowdragmc.lowdraglib.gui.widget.LabelWidget;
import com.lowdragmc.lowdraglib.gui.widget.TextFieldWidget;
import com.lowdragmc.lowdraglib.gui.widget.WidgetGroup;
import com.shanhai.common.itemedit.ShanhaiItemOverrideTable;
import net.minecraft.resources.ResourceLocation;

/**
 * 🆕 2026-10-17：三页签面板的第 ② 页 —— <b>「修改已有物品」</b>。
 *
 * <h2>一屏长什么样（340×300，坐标相对本页组）</h2>
 * <pre>
 *   y=2      标题
 *   y=4..156 搜索结果块（{@link ShanhaiItemSearchPane}：搜索框 ＋ 5 行 ＋ 翻页 ＋ 计数）
 *   y=160    选中的 id
 *   y=178    名字输入框
 *   y=196    描述（「编辑描述…」按钮 ＋ 多行摘要）—— 复用 {@link ShanhaiCreativeLoreEditor}
 *   y=214    堆叠上限（数字框 ＋ ＋/－/最大）＋ 描述模式（普通追加 / 替换原描述）
 *   y=238    保存 / 清除（恢复原样）/ 关闭
 *   y=260    状态行
 *   y=276    灰字提示（128 个壳不吃覆盖 / 堆叠留空 = 不改）
 * </pre>
 *
 * <h2>🔴 服务端权威怎么落的</h2>
 * 与 {@code ShanhaiCreativeItemsPanel} 同一条口径（那个面板已经验收过）：
 * <b>控件只存在于客户端这份</b>（LDLib 1.0.33 的自绘控件不会把点击发给服务端），
 * 所以编辑缓冲在客户端，点「保存」时把整包字段发一条 C2S 消息
 * （{@link ShanhaiCreativeItemsNetwork#requestOverride}），<b>落盘与校验全在服务端</b>。
 * 服务端那份页组只用来对齐容器（它的按钮回调一律 {@code !remote ⇒ return}）。
 *
 * <h2>🔴 落盘立刻生效</h2>
 * 表是"每 200ms stat 一次文件、变了就重载"（{@link ShanhaiItemOverrideTable#refresh()}），
 * 保存后文件名/描述/堆叠在物品栏、tooltip、JEI 里下一次渲染就是新的 —— 不需要 /reload。
 */
final class ShanhaiItemOverridePage extends WidgetGroup {

    /** 本页的"页内"版式常量（不含页签那 20px —— 页签由外层容器负责）。 */
    static final int TOP_ID_LINE = 160;

    private final boolean remote;

    private ShanhaiItemSearchPane pane;
    private ResourceLocation selectedId;

    // ---- 编辑缓冲（客户端权威）----
    private String editName = "";
    private String editLore = "";
    private String editMode = ShanhaiItemOverrideTable.MODE_APPEND;

    private TextFieldWidget nameField;
    private TextFieldWidget stackField;
    private ShanhaiCreativeWidgets.TextButton modeButton;
    private LabelWidget idLabel;
    private LabelWidget loreLabel;
    private LabelWidget statusLabel;
    private LabelWidget hintLabel;
    private ShanhaiCreativeLoreEditor loreEditor;

    ShanhaiItemOverridePage(boolean remote) {
        super(0, 0, ShanhaiCreativeItemsPanel.W, ShanhaiCreativeItemsPanel.H);
        this.remote = remote;
        this.setBackground(GuiTextures.BACKGROUND);

        this.addWidget(new LabelWidget(6, 2, "§f修改已有物品 §7· 搜 id → 选中 → 改名/描述/堆叠 → 保存"));

        this.pane = new ShanhaiItemSearchPane(remote, this::select);
        this.pane.install(this);

        // ---- 选中的 id ----
        this.idLabel = new LabelWidget(6, TOP_ID_LINE, "§8（还没选物品）");
        this.addWidget(this.idLabel);

        // ---- 名字 ----
        this.addWidget(new LabelWidget(6, 182, "§7名字"));
        this.nameField = new TextFieldWidget(46, 178, 288, 16, () -> this.editName, value -> {
            // 真值以保存时现取为准（TextResponder 在客户端这份不一定被调）
        });
        this.nameField.setMaxStringLength(ShanhaiItemOverrideTable.MAX_NAME_CHARS);
        this.addWidget(this.nameField);

        // ---- 描述（复用现成的多行编辑器）----
        this.addWidget(new LabelWidget(6, 200, "§7描述"));
        this.addWidget(new ShanhaiCreativeWidgets.TextButton(46, 196, 96, 16,
                () -> "§f编辑描述…", this::openLoreEditor));
        this.loreLabel = new LabelWidget(150, 200, "§8（空）");
        this.addWidget(this.loreLabel);

        // ---- 堆叠 + 描述模式 ----
        this.addWidget(new LabelWidget(6, 218, "§7堆叠"));
        this.stackField = new TextFieldWidget(46, 214, 74, 16, () -> "",
                value -> { /* 保存时现取 */ });
        this.stackField.setMaxStringLength(10);
        this.addWidget(this.stackField);
        this.addWidget(new ShanhaiCreativeWidgets.TextButton(124, 214, 16, 16, () -> "§f+",
                () -> bumpStack(1)));
        this.addWidget(new ShanhaiCreativeWidgets.TextButton(142, 214, 16, 16, () -> "§f-",
                () -> bumpStack(-1)));
        this.addWidget(new ShanhaiCreativeWidgets.TextButton(162, 214, 40, 16, () -> "§f最大",
                () -> setStackText(Integer.toString(ShanhaiItemOverrideTable.MAX_STACK))));
        this.addWidget(new LabelWidget(208, 218, "§7描述模式"));
        this.modeButton = new ShanhaiCreativeWidgets.TextButton(264, 214, 70, 16,
                this::modeWord, this::toggleMode);
        this.addWidget(this.modeButton);

        // ---- 底部 ----
        this.addWidget(new ShanhaiCreativeWidgets.TextButton(6, 238, 70, 18,
                () -> "§a保存", this::save));
        this.addWidget(new ShanhaiCreativeWidgets.TextButton(80, 238, 110, 18,
                () -> "§e清除（恢复原样）", this::clearOverride));
        this.addWidget(new ShanhaiCreativeWidgets.TextButton(194, 238, 60, 18,
                () -> "§f关闭", this::close));

        this.statusLabel = new LabelWidget(6, 262, "§7在上面搜一个 id，点一行选中它");
        this.addWidget(this.statusLabel);
        this.hintLabel = new LabelWidget(6, 278, "§8堆叠留空 = 不改；128 个现实物品壳不吃覆盖（请用「新增」页）");
        this.addWidget(this.hintLabel);

        // ---- 描述编辑层（最后加 = 画在最上面；默认隐藏）----
        this.loreEditor = new ShanhaiCreativeLoreEditor(new ShanhaiCreativeLoreEditor.Target() {
            @Override
            public String lore() {
                return ShanhaiItemOverridePage.this.editLore;
            }

            @Override
            public void accepted(String lore) {
                ShanhaiItemOverridePage.this.editLore = lore == null ? "" : lore.trim();
                ShanhaiItemOverridePage.this.refreshLoreSummary();
            }
        });
        this.addWidget(this.loreEditor);
    }

    // ------------------------------------------------------------------ 选中

    private void select(ResourceLocation id) {
        this.selectedId = id;
        ShanhaiItemOverrideTable.refresh();
        final ShanhaiItemOverrideTable.Entry e = ShanhaiItemOverrideTable.get(id);
        this.editName = e == null || !e.hasName() ? "" : e.name();
        this.editLore = e == null || !e.hasLore() ? "" : e.lore();
        this.editMode = ShanhaiItemOverrideTable.sanitizeMode(e == null ? null : e.loreMode());
        if (this.nameField != null) {
            this.nameField.setCurrentString(this.editName);
        }
        if (this.stackField != null) {
            this.stackField.setCurrentString(e != null && e.hasStack() ? Integer.toString(e.maxStack()) : "");
        }
        if (this.idLabel != null) {
            this.idLabel.setText("§7选中：§f" + id + (reserved(id) ? " §c（现实物品壳，不吃覆盖）" : ""));
        }
        if (this.hintLabel != null) {
            this.hintLabel.setText(reserved(id)
                    ? "§c这是「现实物品」壳（shanhai:reality_item_1..128），不吃覆盖 ⇒ 请用「新增」页改它"
                    : "§8堆叠留空 = 不改；32 位整数上限 " + ShanhaiItemOverrideTable.MAX_STACK);
        }
        this.setStatus("§7已选中 §f" + id + " §8· 现在改哪一项都行，最后点「保存」");
        this.refreshLoreSummary();
        com.shanhai.ShanhaiMod.LOGGER.info("[SHANHAI-ITEMEDIT] page2_select id={} name_len={} lore_len={} "
                        + "mode={} stack={}",
                id, this.editName.length(), this.editLore.length(), this.editMode,
                this.stackField == null ? "?" : this.stackField.getCurrentString());
    }

    private static boolean reserved(ResourceLocation id) {
        return ShanhaiItemOverrideTable.isReserved(id);
    }

    // ------------------------------------------------------------------ 描述

    private void openLoreEditor() {
        if (this.loreEditor != null) {
            this.loreEditor.open();
        }
    }

    private void refreshLoreSummary() {
        if (this.loreLabel == null) {
            return;
        }
        final String lore = this.editLore == null ? "" : this.editLore;
        if (lore.isBlank()) {
            this.loreLabel.setText("§8（空）");
            return;
        }
        final String[] parts = lore.split("\\R", -1);
        String first = "";
        for (String raw : parts) {
            String line = raw;
            if (line.startsWith(com.shanhai.item.RealityShellItem.TAG_SHIFT)) {
                line = line.substring(com.shanhai.item.RealityShellItem.TAG_SHIFT.length());
            } else if (line.startsWith(com.shanhai.item.RealityShellItem.TAG_CTRL)) {
                line = line.substring(com.shanhai.item.RealityShellItem.TAG_CTRL.length());
            }
            if (!line.isBlank()) {
                first = line;
                break;
            }
        }
        final String head = first.length() > 16 ? first.substring(0, 16) + "…" : first;
        this.loreLabel.setText("§8" + parts.length + " 行 §7" + head);
    }

    // ------------------------------------------------------------------ 堆叠 / 模式

    /** 文本 → 堆叠上限；{@code 0} = 这个框是空的/非法（= 不改，不是"改成 0"）。 */
    private static int parseStack(String text) {
        if (text == null) {
            return 0;
        }
        final String t = text.trim();
        if (t.isEmpty()) {
            return 0;
        }
        try {
            final long v = Long.parseLong(t);
            if (v < ShanhaiItemOverrideTable.MIN_STACK) {
                return ShanhaiItemOverrideTable.MIN_STACK;
            }
            return (int) Math.min(v, ShanhaiItemOverrideTable.MAX_STACK);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private int liveStack() {
        return this.stackField == null ? 0 : parseStack(this.stackField.getCurrentString());
    }

    private void setStackText(String text) {
        if (this.stackField != null) {
            this.stackField.setCurrentString(text);
        }
    }

    private void bumpStack(int delta) {
        final int cur = liveStack();
        final long next = Math.max(ShanhaiItemOverrideTable.MIN_STACK,
                Math.min((long) cur + delta, ShanhaiItemOverrideTable.MAX_STACK));
        setStackText(Long.toString(next));
    }

    private String modeWord() {
        return "§f" + (ShanhaiItemOverrideTable.MODE_REPLACE.equals(this.editMode) ? "替换原描述" : "普通追加");
    }

    private void toggleMode() {
        this.editMode = ShanhaiItemOverrideTable.MODE_REPLACE.equals(this.editMode)
                ? ShanhaiItemOverrideTable.MODE_APPEND
                : ShanhaiItemOverrideTable.MODE_REPLACE;
        this.setStatus("§7描述模式：§f" + (ShanhaiItemOverrideTable.MODE_REPLACE.equals(this.editMode)
                ? "替换原描述（丢掉它自己的描述行，保留名字行）" : "普通追加（加在原描述下面）"));
    }

    // ------------------------------------------------------------------ 保存 / 清除 / 关闭

    private void save() {
        if (!this.remote) {
            return;      // 服务端那份只用来对齐容器
        }
        if (this.selectedId == null) {
            this.setStatus("§c还没选物品");
            return;
        }
        if (reserved(this.selectedId)) {
            this.setStatus("§c" + this.selectedId + " 是现实物品壳，不吃覆盖（后端也会拒）");
            return;
        }
        // 🔴 保存这一刻从控件现取（不依赖每 tick 的时序）
        final String liveName = this.nameField == null ? this.editName : this.nameField.getCurrentString();
        if (liveName != null) {
            this.editName = liveName;
        }
        final int liveStack = liveStack();
        ShanhaiCreativeItemsNetwork.requestOverride(this.selectedId.toString(), this.editName,
                this.editLore, this.editMode, liveStack);
        this.setStatus("§a已发送保存 §8· §f" + this.selectedId
                + (liveStack > 64 ? " §c⚠ >64 可能有未知 bug" : ""));
        com.shanhai.ShanhaiMod.LOGGER.info("[SHANHAI-ITEMEDIT] page2_save id={} name_len={} lore_len={} "
                        + "mode={} stack={}",
                this.selectedId, this.editName.length(), this.editLore.length(), this.editMode, liveStack);
    }

    private void clearOverride() {
        if (!this.remote) {
            return;
        }
        if (this.selectedId == null) {
            this.setStatus("§c还没选物品");
            return;
        }
        if (reserved(this.selectedId)) {
            this.setStatus("§c" + this.selectedId + " 是现实物品壳，本来就没有覆盖项");
            return;
        }
        // 「清除」= 把这一条覆盖整个删掉（走服务端：表会写文件，客户端那份不能自己写）
        ShanhaiCreativeItemsNetwork.requestClear(this.selectedId.toString());
        this.editName = "";
        this.editLore = "";
        this.editMode = ShanhaiItemOverrideTable.MODE_APPEND;
        if (this.nameField != null) {
            this.nameField.setCurrentString("");
        }
        setStackText("");
        refreshLoreSummary();
        this.setStatus("§e已发送「清除」§8（服务端删掉这一条覆盖 ⇒ 立刻恢复原样）");
    }

    private void close() {
        if (this.remote && this.minecraftPlayerClose != null) {
            this.minecraftPlayerClose.run();
        }
    }

    /** 由外层容器注入"关掉整个面板"的动作（本页拿不到 player 引用，避免再引一层）。 */
    private Runnable minecraftPlayerClose;

    void onClose(Runnable action) {
        this.minecraftPlayerClose = action;
    }

    // ------------------------------------------------------------------ 文本

    private void setStatus(String text) {
        if (this.statusLabel != null) {
            this.statusLabel.setText(text == null ? "" : text);
        }
    }

    // ------------------------------------------------------------------ 生命周期

    @Override
    public void updateScreen() {
        super.updateScreen();
        if (!this.remote) {
            return;
        }
        if (this.pane != null) {
            this.pane.tick();
        }
    }

    void closeOverlays() {
        if (this.loreEditor != null) {
            this.loreEditor.close();
        }
    }

    static String layoutLine() {
        return "page_override " + ShanhaiCreativeItemsPanel.W + "x" + ShanhaiCreativeItemsPanel.H
                + " lore_editor=reused mode=append|replace stack=1.." + ShanhaiItemOverrideTable.MAX_STACK;
    }
}
