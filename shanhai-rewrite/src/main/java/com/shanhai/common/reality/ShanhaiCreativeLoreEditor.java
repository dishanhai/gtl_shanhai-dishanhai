package com.shanhai.common.reality;

import com.gregtechceu.gtceu.api.gui.GuiTextures;
import com.lowdragmc.lowdraglib.gui.widget.LabelWidget;
import com.lowdragmc.lowdraglib.gui.widget.WidgetGroup;
import com.shanhai.item.RealityShellItem;

import java.util.ArrayList;
import java.util.List;

/**
 * 「提示」的多行编辑器（覆盖在「自己新增物品」面板上的一层）。
 *
 * <h2>为什么要单开一层</h2>
 * LDLib 的 {@code TextFieldWidget} 是<b>单行</b>控件，而提示（lore）在数据层本来就是
 * <b>按换行拆成多行 tooltip</b>（{@code RealityShellItem#appendHoverText} 用 {@code split("\\R")}）。
 *
 * <h2>🔴 行数不限 ＋ 分页（用户 2026-10-17 第二次追加：「不要有最大 10 行的限制，超出范围了就下一页」）</h2>
 * <ul>
 *   <li>模型是 {@link #lines}／{@link #channel} 两个列表，<b>行数不设上限</b>；</li>
 *   <li>屏幕上固定 {@link #PAGE} 行（一格一框），**翻页**看别的行；最后一页的空框也能直接打字
 *       （打字 = 新增行，见 {@link #commitPage()}）；</li>
 *   <li>每行一个频道按钮：<b>普 → Shift → Ctrl</b> 循环；落盘时行首加
 *       {@link RealityShellItem#TAG_SHIFT} / {@link RealityShellItem#TAG_CTRL}（复用同一个 lore 字段）；</li>
 *   <li>总长仍受 {@code MAX_LORE_CHARS}=512 字约束（行数不限但字数是硬的）。</li>
 * </ul>
 *
 * <h2>🔴 与文本框相关的既有教训（2026-10-17 实锤两次）</h2>
 * LDLib 文本框的 {@code textResponder} <b>不一定在客户端这份上被调</b> ⇒ 这里<b>一律在用的那一刻</b>
 * 从各控件 {@code getCurrentString()} 现取，不依赖回调。
 *
 * <h2>🆕 2026-11-01：颜色/样式选择器（用户原话：「我想办法让它可以打 §f…你加一个特效选择器，
 * 像 ftb 任务一样，可以快捷选择输入」）</h2>
 * 这里原来只有 10 个行框 —— <b>§ 根本打不进去</b>（原版 {@code SharedConstants} 把它当非法聊天字符，
 * 手敲和粘贴两条路都被拦）。现在补两条：
 * <ul>
 *   <li>行框换成 {@link ShanhaiTokenField}：能把 token <b>插到光标处</b>（走 {@code setValue}，§ 存活）；</li>
 *   <li>下方两排共 39 个按钮（{@link ShanhaiTokenPicker}）：16 色 ＋ 6 格式 ＋ 16 山海样式 ＋ {@code &r}。</li>
 * </ul>
 * 版面：10 个行框（y=36..214，宽 250）<b>一个像素没动</b>；按钮带 y=240..274；「接受/取消」从 y=268 下移到
 * y=278（278..296）；原来 y=244 的「共 N 行」并进第 1 行说明。
 */
public final class ShanhaiCreativeLoreEditor extends WidgetGroup {

    /** 每页几行（= 屏幕上的框数）。 */
    static final int PAGE = 10;

    private static final int CH_PLAIN = 0;
    private static final int CH_SHIFT = 1;
    private static final int CH_CTRL = 2;

    /**
     * 🆕 2026-10-17：<b>这一层要读写谁的提示</b>（为了让「修改已有物品」页复用同一套多行编辑器）。
     *
     * <p>原来的唯一调用方是「新增」页（{@link ShanhaiCreativeItemsPanel#editLore}）。
     * 「修改」页要的是"读我自己的缓冲、把结果写回我自己的缓冲"，而<b>不落盘</b>
     * （落盘由那一页的「保存」按钮统一负责）⇒ 抽这两个方法，行为与原来逐字等价。
     */
    public interface Target {
        /** 当前这份提示（{@code \n} 分隔，已含行首频道标记）。 */
        String lore();

        /** 编辑器按了「接受」：把拼好的整串交回去。 */
        void accepted(String lore);
    }

    private final Target target;
    private final ShanhaiTokenField[] fields = new ShanhaiTokenField[PAGE];
    private final LabelWidget[] labels = new LabelWidget[PAGE];
    private final ShanhaiCreativeWidgets.TextButton[] channelButtons =
            new ShanhaiCreativeWidgets.TextButton[PAGE];

    /**
     * 🔴 插入目标 = <b>当前有焦点的那个行框</b>；没焦点时 = <b>最后点过的那个框</b>（用户 2026-11-01 的要求）。
     *
     * <p>为什么必须有这个字段：点一下右侧的选择器按钮时，LDLib 可能已经把焦点从行框上拿走
     * （{@code Widget#setFocus} 的执行路径见 {@code ModularUIGuiContainer.lastFocus}）
     * ⇒ 那时"哪个框有焦点"是问不出答案的，只能记住"最后点过哪个"。
     * 写入面：{@link ShanhaiTokenField} 在 {@code mouseClicked}/{@code onFocusChanged} 里回调回来。
     */
    private ShanhaiTokenField lastFocused;

    /** 真正的数据：每行的文本与频道（行数不限）。 */
    private final List<String> lines = new ArrayList<>();
    private final List<Integer> channel = new ArrayList<>();
    private int page = 0;

    /**
     * 第 1 行的说明 —— 🔴 <b>动态</b>（行数/带条件的行数都随模型变），必须每次 {@code loadPage} 现写。
     *
     * <p>2026-11-01：原来 y=244 那个「共 N 行」标签被<b>并进这一行</b>（它的位置让给了选择器的
     * 「原版格式」组标题）。为什么没并进页数标签：页数标签在 x=286，右边只剩 48px，
     * 塞不下「共 N 行 · 带条件 M」。
     */
    private LabelWidget titleLabel;

    /** 页码 —— 必须每次动作后 setText 现写（supplier 懒取不刷新）。 */
    private LabelWidget pageLabel;

    /** 2026-11-01：下方的颜色/样式选择器（39 个按钮 ＋ 两行小字，坐标见 {@code ShanhaiTokenLibrary.CHIPS}）。 */
    private ShanhaiTokenPicker tokenPicker;


    /**
     * 既有入口（「新增」页）：<b>行为与 2026-10-17 之前完全一致</b> —— 读 {@code panel.editLore}、
     * 接受后调 {@code panel.acceptLore(...)}（那一步会立刻走一次保存）。
     */
    public ShanhaiCreativeLoreEditor(ShanhaiCreativeItemsPanel panel) {
        this(new Target() {
            @Override
            public String lore() {
                return panel.editLore;
            }

            @Override
            public void accepted(String lore) {
                panel.acceptLore(lore);
            }
        });
    }

    /** 🆕 通用入口（「修改」页）：读写调用方自己的缓冲，接受时<b>不落盘</b>。 */
    public ShanhaiCreativeLoreEditor(Target target) {
        super(0, 0, ShanhaiCreativeItemsPanel.W, ShanhaiCreativeItemsPanel.H);
        this.target = target;
        this.setBackground(GuiTextures.BACKGROUND);

        // 🔴 第 1 行是【动态】的（行数/带条件行数/每行上限都在这里）——LDLib 的 LabelWidget
        //    默认只在构造时取一次文本，所以一律在 loadPage() 里 setText 现写。
        this.titleLabel = new LabelWidget(6, 6, "§f编辑提示 §7· 共 0 行 · 每行 ≤ "
                + ShanhaiRealityItemTable.MAX_LORE_CHARS + " 字");
        this.addWidget(this.titleLabel);
        // ⚠️ y=18 / y=27 两行小字由选择器自己铺（「同一行只能选一套」＋ 状态行），见 ShanhaiTokenPicker。

        for (int i = 0; i < PAGE; i++) {
            final int row = i;
            this.labels[i] = new LabelWidget(6, 38 + i * 18, "§7行 1");
            this.addWidget(this.labels[i]);
            // 🔴 2026-11-01：从 TextFieldWidget 换成 ShanhaiTokenField（多了"插到光标处"的能力）。
            //    宽度 250 一个像素没动（这是任务书硬约束：选择器不许挤压行框）。
            this.fields[i] = new ShanhaiTokenField(40, 36 + i * 18, 250, 16, () -> "", value -> {
                // 回调照留（有些环境会走），真值以用的时候现取为准
            }, field -> this.lastFocused = field);
            this.fields[i].setMaxStringLength(ShanhaiRealityItemTable.MAX_LORE_CHARS);
            this.addWidget(this.fields[i]);
            this.channelButtons[i] = new ShanhaiCreativeWidgets.TextButton(292, 36 + i * 18, 42, 16,
                    () -> "§f" + channelWord(channelAt(this.page * PAGE + row)),
                    () -> cycleChannel(this.page * PAGE + row));
            this.addWidget(this.channelButtons[i]);
        }

        this.addWidget(new ShanhaiCreativeWidgets.TextButton(6, 222, 74, 16, () -> "§f＋ 加一行", this::addLine));
        this.addWidget(new ShanhaiCreativeWidgets.TextButton(84, 222, 74, 16, () -> "§f－ 删一行", this::removeLine));
        this.addWidget(new ShanhaiCreativeWidgets.TextButton(166, 222, 52, 16, () -> "§f◀ 上一页",
                () -> turnPage(-1)));
        this.addWidget(new ShanhaiCreativeWidgets.TextButton(222, 222, 58, 16, () -> "§f下一页 ▶",
                () -> turnPage(1)));
        // 🔴 页数【不能用 supplier 懒取】—— LDLib 的 LabelWidget 默认只在构造时取一次文本，
        //    所以改成在 loadPage() 里 setText 现写（用户报过"页数从来不变"）。
        this.pageLabel = new LabelWidget(286, 226, "§8 1/1");
        this.addWidget(this.pageLabel);

        // 🆕 2026-11-01：颜色/样式选择器（y=240..274 两排按钮 ＋ y=18/27 两行小字）。
        //    🔴 它<b>不碰</b> 10 个行框（一个像素都没动），只占用原「共 N 行」标签与
        //    「接受/取消」之间的空间；「接受/取消」按任务书从 y=268 下移到 y=278（278..296）。
        this.tokenPicker = new ShanhaiTokenPicker(this, new ShanhaiTokenPicker.FieldSource() {
            @Override
            public ShanhaiTokenField target() {
                return targetField();
            }

            @Override
            public int rowOf(ShanhaiTokenField field) {
                return rowNumberOf(field);
            }
        }, ShanhaiRealityItemTable.MAX_LORE_CHARS);

        this.addWidget(new ShanhaiCreativeWidgets.TextButton(176, 278, 74, 18, () -> "§a接受", this::accept));
        this.addWidget(new ShanhaiCreativeWidgets.TextButton(254, 278, 74, 18, () -> "§c取消", this::cancel));

        this.setVisible(false);
        this.setActive(false);
        this.loadPage();
    }

    // ------------------------------------------------------------------ 模型读写

    private static String channelWord(int ch) {
        return switch (ch) {
            case CH_SHIFT -> "Shift";
            case CH_CTRL -> "Ctrl";
            default -> "普";
        };
    }

    private int channelAt(int index) {
        return index >= 0 && index < this.channel.size() ? this.channel.get(index) : CH_PLAIN;
    }

    private void ensureIndex(int index) {
        while (this.lines.size() <= index) {
            this.lines.add("");
            this.channel.add(CH_PLAIN);
        }
    }

    /** 当前页【可见】的框数 = 这一页真实存在的行数（不是恒为 PAGE）。 */
    private int shownInPage() {
        return Math.max(0, Math.min(PAGE, this.lines.size() - this.page * PAGE));
    }

    /** 把当前页【看得见】的那些框写回模型 —— 框数 = 行数，所以这里不会凭空造行。 */
    private void commitPage() {
        final int shown = shownInPage();
        for (int i = 0; i < shown; i++) {
            final int idx = this.page * PAGE + i;
            String text = this.fields[i].getCurrentString();
            if (text == null) {
                text = "";
            }
            this.lines.set(idx, text.trim());
        }
    }

    private int pageCount() {
        return Math.max(1, (this.lines.size() + PAGE - 1) / PAGE);
    }

    /** 把模型当前页写进框里：**框数严格 = 这一页的行数**（删一行就真的少一个框，不是只清字）。 */
    private void loadPage() {
        this.page = Math.max(0, Math.min(this.page, pageCount() - 1));
        final int shown = shownInPage();
        for (int i = 0; i < PAGE; i++) {
            final int idx = this.page * PAGE + i;
            final boolean on = i < shown;
            this.labels[i].setVisible(on);
            this.fields[i].setVisible(on);
            this.channelButtons[i].setVisible(on);
            if (on) {
                this.labels[i].setText("§7行 " + (idx + 1));
                this.fields[i].setCurrentString(this.lines.get(idx));
            } else {
                this.fields[i].setCurrentString("");
            }
        }
        if (this.pageLabel != null) {
            this.pageLabel.setText("§8" + (this.page + 1) + "/" + pageCount());
        }
        // 2026-11-01：「共 N 行 §8· 带条件 M」原来是 y=244 的独立标签，它的位置让给了
        // 选择器的「原版格式」组标题 ⇒ 并进第 1 行说明（页数标签右边只有 48px，塞不下）。
        if (this.titleLabel != null) {
            int gated = 0;
            for (int ch : this.channel) {
                if (ch != CH_PLAIN) {
                    gated++;
                }
            }
            this.titleLabel.setText("§f编辑提示 §7· 共 " + this.lines.size() + " 行"
                    + " · 带条件 " + gated
                    + " · 每行 ≤ " + ShanhaiRealityItemTable.MAX_LORE_CHARS + " 字");
        }
    }

    // ------------------------------------------------------------------ 选择器：插到哪个框

    /**
     * 选择器点下去时"往哪个框插"—— 判据顺序<b>就是用户原话的顺序</b>：
     * <ol>
     *   <li>有焦点的那个行框（{@code isFocus()}，且必须<b>可见</b>，不然会插进另一页的框）；</li>
     *   <li>没有焦点 ⇒ {@link #lastFocused}（最后点过的那个；同样必须可见）；</li>
     *   <li>都没有 ⇒ {@code null}（选择器会明说「先点一下要编辑的那一行」，不静默丢弃）。</li>
     * </ol>
     */
    private ShanhaiTokenField targetField() {
        for (ShanhaiTokenField field : this.fields) {
            if (field != null && field.isVisible() && field.isFocus()) {
                return field;
            }
        }
        if (this.lastFocused != null && this.lastFocused.isVisible()) {
            return this.lastFocused;
        }
        return null;
    }

    /** 这个框是屏幕上第几行（1 基；只给状态行文案用，认不出来给 0）。 */
    private int rowNumberOf(ShanhaiTokenField field) {
        if (field == null) {
            return 0;
        }
        for (int i = 0; i < PAGE; i++) {
            if (this.fields[i] == field) {
                return this.page * PAGE + i + 1;
            }
        }
        return 0;
    }

    // ------------------------------------------------------------------ 按钮

    private void cycleChannel(int index) {
        commitPage();
        ensureIndex(index);
        this.channel.set(index, (channelAt(index) + 1) % 3);
        loadPage();
    }

    private void addLine() {
        commitPage();
        this.lines.add("");
        this.channel.add(CH_PLAIN);
        this.page = (this.lines.size() - 1) / PAGE;
        loadPage();
    }

    private void removeLine() {
        commitPage();
        if (!this.lines.isEmpty()) {
            this.lines.remove(this.lines.size() - 1);
            this.channel.remove(this.channel.size() - 1);
        }
        loadPage();
    }

    private void turnPage(int delta) {
        commitPage();
        this.page += delta;
        loadPage();
    }

    // ------------------------------------------------------------------ 打开 / 关闭 / 接受

    /** 打开：把槽位现有的提示按行装进模型（识别行首标记）。 */
    void open() {
        this.lines.clear();
        this.channel.clear();
        final String lore = this.target == null || this.target.lore() == null ? "" : this.target.lore();
        if (!lore.isBlank()) {
            for (String raw : lore.split("\\R", -1)) {
                String text = raw;
                int ch = CH_PLAIN;
                if (text.startsWith(RealityShellItem.TAG_SHIFT)) {
                    ch = CH_SHIFT;
                    text = text.substring(RealityShellItem.TAG_SHIFT.length());
                } else if (text.startsWith(RealityShellItem.TAG_CTRL)) {
                    ch = CH_CTRL;
                    text = text.substring(RealityShellItem.TAG_CTRL.length());
                }
                this.lines.add(text);
                this.channel.add(ch);
            }
        }
        // 🔴 框数 = 行数：本来没有提示就先给 1 行（不然面板上一个框都没有、没处写）；
        //    除此之外【绝不自动加框】——「＋ 加一行」才是造框的入口（用户 2026-10-17 定的）。
        if (this.lines.isEmpty()) {
            this.lines.add("");
            this.channel.add(CH_PLAIN);
        }
        this.page = Math.max(0, (this.lines.size() - 1) / PAGE);
        // 🔴 每次打开都清掉"最后点过的那个框"：这个编辑器实例是复用的，
        //    否则上一次留在别的页上的框会被当成插入目标（虽然 targetField() 还会查 isVisible()）。
        this.lastFocused = null;
        loadPage();
        if (this.tokenPicker != null) {
            this.tokenPicker.resetStatus();
        }
        this.setVisible(true);
        this.setActive(true);
        com.shanhai.ShanhaiMod.LOGGER.info("{} lore_editor_open lines={} pages={}",
                ShanhaiRealityItemTable.PREFIX, this.lines.size(), pageCount());
    }

    void close() {
        this.lastFocused = null;
        this.setVisible(false);
        this.setActive(false);
    }

    /** 接受：commit 之后按模型拼（跳空行、带频道标记、用 \n 连接），写回槽位并保存。 */
    private void accept() {
        commitPage();
        final StringBuilder sb = new StringBuilder();
        int used = 0;
        for (int i = 0; i < this.lines.size(); i++) {
            String t = this.lines.get(i);
            if (t == null || t.isBlank()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append('\n');
            }
            final int ch = channelAt(i);
            if (ch == CH_SHIFT) {
                sb.append(RealityShellItem.TAG_SHIFT);
            } else if (ch == CH_CTRL) {
                sb.append(RealityShellItem.TAG_CTRL);
            }
            sb.append(t.trim());
            used++;
        }
        com.shanhai.ShanhaiMod.LOGGER.info("{} lore_accept_editor lines={} gated={} chars={}",
                ShanhaiRealityItemTable.PREFIX, used,
                this.channel.stream().filter(c -> c != CH_PLAIN).count(), sb.length());
        if (this.target != null) {
            this.target.accepted(sb.toString());
        }
        this.close();
    }

    private void cancel() {
        this.close();
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        super.mouseClicked(mouseX, mouseY, button);
        // 铺满整个面板 ⇒ 吃掉落在它身上的点击，别穿到底层
        return isMouseOverElement(mouseX, mouseY);
    }
}
