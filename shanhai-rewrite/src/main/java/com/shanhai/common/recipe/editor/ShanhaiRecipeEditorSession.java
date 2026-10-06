package com.shanhai.common.recipe.editor;

import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import com.gregtechceu.gtceu.api.recipe.GTRecipeType;
import com.shanhai.common.text.ShanhaiLangLookup;
import com.shanhai.common.text.ShanhaiTextParser;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * 面板的<b>全部状态与动作</b>，与 LDLib 的控件完全解耦。
 *
 * <h2>为什么要拆出这一层（不是为了好看）</h2>
 * 红线禁止启动客户端 ⇒ <b>没有 GUI 可以点</b>。如果状态和动作长在 {@code Widget} 里，
 * 那么"列出配方 / 选中 / 改时长 / 保存 / 删除"这条链在整个冒烟流程里<b>一步都验不到</b>。
 * 把它们抽到一个不依赖任何控件的类里之后：
 * <ul>
 *   <li>无头专服里可以<b>直接 new 一个 session</b>（有真的 {@code MinecraftServer}）把整条链跑一遍；</li>
 *   <li>{@link ShanhaiRecipeEditorPanel} 退化成"把 session 的读数贴到控件上 + 把点击转给 session"，
 *       没有自己的逻辑 ⇒ 界面上看到什么 = 自检里验过什么。</li>
 * </ul>
 *
 * <h2>🔴 客户端那份 session 是空的，而且【故意】是空的</h2>
 * 客户端不持有配方表（红线禁止开客户端，本刀也不做数据下行）。客户端面板上每一行文字都由
 * <b>服务端那份 session</b> 经 {@code LabelWidget} 的
 * {@code writeInitialData / detectAndSendChanges} 推下去。所以：
 * <ul>
 *   <li>客户端 session 的 {@code server == null} ⇒ {@link #available()} 为假，所有动作方法直接返回；</li>
 *   <li>所有 {@code xxxText()} 在客户端返回空串 —— 它<b>不会</b>被画出来（画的是服务端推来的那份）。</li>
 * </ul>
 * 这条边界很重要：它保证"客户端不可能凭本地状态显示一个服务端并不认可的数"——
 * 也就是本工程红线里那条「活的界面上不许放假数据」。
 */
public final class ShanhaiRecipeEditorSession {

    /** 一页画几行（面板行数 = 这个值）。 */
    public static final int ROWS = 8;

    // ---- 一行的【显示宽度预算】（2026-10-05 B2 改：从"字符数"改成"显示宽度"）----
    //   起因（用户实测报的）：列表行里的配方类型被截成 `(matter_modu...)`——
    //   旧写法用"字符数"裁剪，而中文配方类型名一个字占两个半角宽，
    //   拿字符数当预算会让中文行【看起来】没超、实际却溢出面板。
    //   现在：ASCII 记 1 个单位、其余（中日韩全角/全角标点）记 2 个单位。

    /**
     * 一行的显示宽度预算（单位 = 半角字符宽）。
     * <p>面板宽 {@link ShanhaiRecipeEditorPanel#W} = 224 px，原版默认字体一个 ASCII 字形约 6 px
     * ⇒ 224 ÷ 6 ≈ 37 个单位；留一点余量取 <b>36</b>。
     */
    public static final int ROW_UNITS = 36;

    /** id 至少保留多少个单位（再挤也不许低于它，否则一行里什么都认不出来了）。 */
    public static final int MIN_ID_UNITS = 8;

    /** 中文配方类型名最多占多少个单位（中文名一般 4~8 字 = 8~16 个单位）。 */
    public static final int MAX_TYPE_UNITS = 14;

    private final MinecraftServer server;
    private final ResourceLocation itemId;
    private final Item item;

    private final List<GTRecipe> pick = new ArrayList<>();
    private int page = 0;
    private int selected = -1;
    private int pendingDuration = 0;
    private String status1 = "§7正在读出配方表…";
    private String status2 = "§8改完点「保存这条」＝立刻生效；重启后由覆盖层重放";
    private boolean loaded = false;

    public ShanhaiRecipeEditorSession(MinecraftServer server, ResourceLocation itemId) {
        this.server = server;
        this.itemId = itemId;
        this.item = itemId == null ? null : BuiltInRegistries.ITEM.get(itemId);
    }

    // ---------------------------------------------------------------- 读数

    public boolean available() {
        return server != null;
    }

    public ResourceLocation itemId() {
        return itemId;
    }

    public int total() {
        return pick.size();
    }

    public int page() {
        return page;
    }

    public int pageCount() {
        return Math.max(1, (pick.size() + ROWS - 1) / ROWS);
    }

    public int selected() {
        return selected;
    }

    public int pendingDuration() {
        return pendingDuration;
    }

    public void setPendingDuration(int v) {
        pendingDuration = v;
    }

    public GTRecipe selectedRecipe() {
        return selected >= 0 && selected < pick.size() ? pick.get(selected) : null;
    }

    public String status1() {
        return status1;
    }

    public String status2() {
        return status2;
    }

    private void say(String a, String b) {
        status1 = a;
        status2 = b;
    }

    // ---------------------------------------------------------------- 载入 / 翻页

    /** 重新拉一遍「用这个物品的配方」。<b>只有服务端那份 session 调得动</b>。 */
    public void reload() {
        if (server == null) {
            return;
        }
        ShanhaiRecipeReverseIndex.ensure(server);
        pick.clear();
        if (item != null) {
            pick.addAll(ShanhaiRecipeReverseIndex.query(server, item));
        }
        loaded = true;
        if (page > pageCount() - 1) {
            page = pageCount() - 1;
        }
        if (page < 0) {
            page = 0;
        }
        if (selected < 0 && !pick.isEmpty()) {
            selected = 0;
        }
        if (selected >= pick.size()) {
            selected = pick.isEmpty() ? -1 : pick.size() - 1;
        }
        syncPendingFromSelection();
        say("§a命中 §f" + pick.size() + " §a条使用 §f" + shortItem() + " §a的配方",
                "§8反查来源=" + (ShanhaiRecipeReverseIndex.isVerified() ? "自建索引" : "线性扫(索引未通过自证)")
                        + " · " + ShanhaiRecipeReverseIndex.statsLine());
    }

    public boolean selectRow(int row) {
        if (server == null) {
            return false;
        }
        final int idx = page * ROWS + row;
        if (idx < 0 || idx >= pick.size()) {
            return false;
        }
        selected = idx;
        syncPendingFromSelection();
        final GTRecipe r = pick.get(idx);
        say("§a已选中 §f" + shortId(r.id), "§8当前时长 = §f" + r.duration + "§8 · 改完点「保存这条」");
        return true;
    }

    public boolean prevPage() {
        if (server == null || page <= 0) {
            return false;
        }
        page--;
        say("§7第 §f" + (page + 1) + "§7/§f" + pageCount() + " §7页", status2);
        return true;
    }

    public boolean nextPage() {
        if (server == null || page >= pageCount() - 1) {
            return false;
        }
        page++;
        say("§7第 §f" + (page + 1) + "§7/§f" + pageCount() + " §7页", status2);
        return true;
    }

    private void syncPendingFromSelection() {
        final GTRecipe r = selectedRecipe();
        if (r != null) {
            pendingDuration = r.duration;
        }
    }

    // ---------------------------------------------------------------- 动作

    /** 「保存这条」= 立刻生效 + 落盘。 */
    public ShanhaiRecipeEditorOps.Result save() {
        if (server == null) {
            return new ShanhaiRecipeEditorOps.Result(false, "客户端不可写", "client side", 0, 0, false, false, null);
        }
        final GTRecipe sel = selectedRecipe();
        if (sel == null || sel.id == null) {
            say("§c没有选中任何配方", "§8先在下面的列表里点一条");
            return new ShanhaiRecipeEditorOps.Result(false, "没有选中配方", "selected < 0", 0, 0, false, false, null);
        }
        // 🔴 重新按 id 取一次【活的】对象：列表是上一次查询的引用，期间可能被 /reload 或别处替换过。
        final GTRecipe live = ShanhaiRecipeReverseIndex.byId(server, sel.id);
        if (live == null) {
            say("§c这条配方已经不在了", "§8可能刚被删掉或配方表重载过；点「重读配方表」再来");
            return new ShanhaiRecipeEditorOps.Result(false, "配方已不存在", "id=" + sel.id, 0, 0, false, false, null);
        }
        final ShanhaiRecipeEditorOps.Result r =
                ShanhaiRecipeEditorOps.setDuration(server, live, pendingDuration, true, true);
        say((r.ok() ? "§a" : "§c") + r.message(), "§8" + r.detail() + " · " + fpNote(r));
        reload();
        return r;
    }

    /** 「删除这条」= 索引 + 原版两表都摘掉 + 落盘。 */
    public ShanhaiRecipeEditorOps.Result deleteSelected() {
        if (server == null) {
            return new ShanhaiRecipeEditorOps.Result(false, "客户端不可写", "client side", 0, 0, false, false, null);
        }
        final GTRecipe sel = selectedRecipe();
        if (sel == null || sel.id == null) {
            say("§c没有选中任何配方", "§8先在下面的列表里点一条");
            return new ShanhaiRecipeEditorOps.Result(false, "没有选中配方", "selected < 0", 0, 0, false, false, null);
        }
        final GTRecipe live = ShanhaiRecipeReverseIndex.byId(server, sel.id);
        if (live == null) {
            say("§c这条配方已经不在了", "§8点「重读配方表」刷新列表");
            return new ShanhaiRecipeEditorOps.Result(false, "配方已不存在", "id=" + sel.id, 0, 0, false, false, null);
        }
        final ShanhaiRecipeEditorOps.Result r = ShanhaiRecipeEditorOps.removeRecipe(server, live, true);
        say((r.ok() ? "§a" : "§c") + r.message(), "§8" + r.detail() + " · " + fpNote(r));
        selected = -1;
        reload();
        return r;
    }

    private static String fpNote(ShanhaiRecipeEditorOps.Result r) {
        if (ShanhaiRecipeFingerprint.UNKNOWN.equals(r.baseFp())) {
            return "§c指纹取不到（KubeJS 那条链没通）⇒ 未写盘";
        }
        return r.persisted() ? "§a已写进 config/shanhai/recipe_overrides.json" : "§e未写盘";
    }

    // ---------------------------------------------------------------- 文本（贴到控件上）

    public String headerText() {
        if (server == null) {
            return "";
        }
        return headerLine(item == null ? null
                : new ItemStack(item).getHoverName().getString(), shortItem());
    }

    /**
     * <b>纯函数</b>版的目标物品那一行（自检拿它跑"已知带码的样本"做正对照）。
     *
     * <p>🔴 2026-10-05 B2：物品显示名里带本工程的 {@code &$…-} 风格码
     * （例：{@code &$magic-虚像物质模块}），原样贴到 LabelWidget 上会把码画出来
     * （用户实测原话：「虚像物质模块前面又漏码了」）。
     * 这条路径是 {@code Font.drawInBatch(String,…)}，<b>不经过</b> {@code ShanhaiTextParser.parse}
     * （理由见 {@code ShanhaiTextParser#stripStyleCode} 的 §2026-10-01 更正）
     * ⇒ 唯一安全的形态就是<b>零 {@code &}</b>：这里先剥码，再拼字面量。
     *
     * @param rawName 物品显示名原文（可能带码；{@code null} = 物品不存在）
     * @param shortId 物品注册名的 path（第二段）
     */
    public static String headerLine(String rawName, String shortId) {
        final String name = rawName == null
                ? "(未注册的物品)"
                : ShanhaiTextParser.stripStyleCode(rawName);
        return "§7目标物品：§f" + clip(name, 18) + " §8(" + (shortId == null ? "?" : shortId) + ")";
    }

    public String pageText() {
        if (server == null) {
            return "";
        }
        return "§7第 §f" + (page + 1) + "§7/§f" + pageCount() + " §7页 · 命中 §f" + pick.size()
                + " §7条 · §8" + (ShanhaiRecipeReverseIndex.isVerified() ? "自建索引" : "线性控制路");
    }

    public String rowText(int row) {
        return absoluteRowText(page * ROWS + row);
    }

    /** 按<b>绝对下标</b>取一行文字（页码无关）。控制台 {@code /shanhai edit list} 用它。 */
    public String absoluteRowText(int idx) {
        final Row row = absoluteRow(idx);
        return row == null ? "" : row.text();
    }

    /** 同上，但返回的是<b>带了机器可判读数</b>{@link Row}（显示宽度、有没有被截、类型名从哪来）。 */
    public Row absoluteRow(int idx) {
        if (server == null) {
            return null;
        }
        if (idx < 0 || idx >= pick.size()) {
            return null;
        }
        final GTRecipe r = pick.get(idx);
        final TypeName tn = typeName(r.getType());
        return composeRow(idx == selected, shortId(r.id), tn.text(), tn.source(), r.duration);
    }

    public String deleteText() {
        if (server == null) {
            return "";
        }
        final GTRecipe r = selectedRecipe();
        return r == null ? "§8删除选中的这条（先选一条）" : "§c删除这条：§7" + clip(shortId(r.id), 26);
    }

    // ---------------------------------------------------------------- 配方类型的中文名 + 行宽

    /**
     * 配方类型名的解析结果。
     *
     * @param text   要显示的名字（<b>绝不编</b>：查不到就是原始 id）
     * @param source 从哪来的，机器可判：{@code live}（活的语言表，单机=客户端语言）／
     *               {@code zh_cn}（直接读 {@code assets/<modid>/lang/zh_cn.json}）／
     *               {@code fallback-id}（两条都查不到 ⇒ 原始 id）
     */
    public record TypeName(String text, String source) {
    }

    /** 一行的成品 + 读数。 */
    public record Row(String text, String plain, int units, boolean fits,
                      boolean idClipped, boolean typeClipped, String typeSource) {
    }

    /** GTCEu 的配方类型名约定：{@code gtceu.<类型注册路径>}（证据：gtceu/gtlcore/shanhai 三份 lang 都用它）。 */
    public static final String TYPE_LANG_PREFIX = "gtceu.";

    /**
     * 把配方类型解析成<b>中文名</b>（用户点单：「配方种类我希望是中文的，而不是英文的」）。
     *
     * <h4>三条路，按顺序</h4>
     * <ol>
     *   <li><b>活的翻译</b> {@code Component.translatable("gtceu." + path)} —— 单机时 language 就是客户端语言
     *       ⇒ 他选中文就得到中文、选英文就得到英文。<b>返回值等于 key</b> 就说明没这个条目 ⇒ 走 ②。</li>
     *   <li><b>直接查 zh_cn</b> {@link ShanhaiLangLookup}（读各 mod 的 {@code assets/<modid>/lang/zh_cn.json}）
     *       —— 无头专服也能出中文，于是"中文名解析成什么"变成一条可验读数。</li>
     *   <li><b>回落 = 原始 id</b>（英文）。<b>绝不编一个中文名</b>（本工程血账：宁可缺，不可假）。</li>
     * </ol>
     */
    public static TypeName typeName(GTRecipeType type) {
        if (type == null || type.registryName == null) {
            return new TypeName("?", "fallback-id");
        }
        return typeName(type.registryName.getPath());
    }

    /** 同上，但直接给<b>类型的注册路径</b>（自检要拿假路径做负对照时用它）。 */
    public static TypeName typeName(String path) {
        if (path == null || path.isEmpty()) {
            return new TypeName("?", "fallback-id");
        }
        final String key = TYPE_LANG_PREFIX + path;
        try {
            final String live = Component.translatable(key).getString();
            if (live != null && !live.isEmpty() && !key.equals(live)) {
                return new TypeName(ShanhaiTextParser.stripStyleCode(live), "live");
            }
        } catch (Throwable ignored) {
            // 语言表还没就绪 ⇒ 走查表路（不是错误）
        }
        final String zh = ShanhaiLangLookup.zhCn(key);
        if (zh != null) {
            return new TypeName(ShanhaiTextParser.stripStyleCode(zh), "zh_cn");
        }
        return new TypeName(path, "fallback-id");
    }

    /** {@link #typeName} 的便捷版。 */
    public static String typeDisplayName(GTRecipeType type) {
        return typeName(type).text();
    }

    /**
     * 显示宽度：ASCII 记 1 个单位，其余（中日韩全角、全角标点、代理对）记 2。
     *
     * <p>⚠️ 省略号 {@code …}（U+2026）也算 <b>2</b> 个单位 —— 它是非 ASCII。
     * 🔴 这是冒烟第 1 局当场抓出来的：{@link #clipUnits} 当时按"留 1 个单位给省略号"预算，
     * 而这里按 2 算 ⇒ 每一行的真实宽度比预算多 <b>2</b> 个单位（实测 {@code units=38 budget=36}）。
     * ⇒ 两处必须用同一个 {@link #charUnits(char)}，不许各写各的。
     */
    public static int charUnits(char c) {
        return c <= 0x7F ? 1 : 2;
    }

    /** 一段文字的显示宽度（单位 = 半角字符宽）。 */
    public static int displayUnits(String s) {
        if (s == null || s.isEmpty()) {
            return 0;
        }
        int n = 0;
        for (int i = 0; i < s.length(); i++) {
            n += charUnits(s.charAt(i));
        }
        return n;
    }

    /** 按<b>显示宽度</b>裁剪（超了补一个省略号；省略号本身按 {@link #charUnits(char)} 算，也是 2）。 */
    public static String clipUnits(String s, int maxUnits) {
        if (s == null || s.isEmpty()) {
            return "";
        }
        if (displayUnits(s) <= maxUnits) {
            return s;
        }
        final int ell = charUnits('…');
        final StringBuilder sb = new StringBuilder();
        final int budget = Math.max(0, maxUnits - ell);
        int used = 0;
        for (int i = 0; i < s.length(); i++) {
            final char c = s.charAt(i);
            final int w = charUnits(c);
            if (used + w > budget) {
                break;
            }
            sb.append(c);
            used += w;
        }
        return sb.append('…').toString();
    }

    // ================================================================== 🆕 第 11 刀：文字像素尺

    /**
     * 🔴 <b>「一个字符占几个像素」的那把尺子</b>（卡片说明行 ＋ 顶部那一排机器名共用）。
     *
     * <p>口径与 Minecraft 默认字体一致，而且与上一轮"物品名美化"里实测出来的那套同值：
     * <b>半角 6 px</b>（字形推进 5 ＋ 间距 1）、<b>全角（中日韩）9 px</b>（unifont 字形 8 ＋ 间距 1）。
     * ⚠️ <b>如实交代：这是按上述口径算出来的上界，不是对 {@code Font.width} 的实测</b>
     * —— 无头专服没有字体（客户端类也进不去）。真正跑起来时
     * {@code ShanhaiRecipeCardWidget} / {@code ShanhaiRecipeTabBarWidget} 会<b>另外</b>打印
     * 一行 {@code font.width} 的真实读数（见那两个类的日志行），两条一起看才知道差多少。
     */
    public static final int PX_HALF = 6;
    /** 见 {@link #PX_HALF}。 */
    public static final int PX_WIDE = 9;

    /** 去掉 {@code §x} 颜色码（量宽度时必须去 —— 色码自己不占像素）。 */
    public static String stripColor(String s) {
        if (s == null || s.isEmpty()) {
            return "";
        }
        if (s.indexOf('§') < 0) {
            return s;
        }
        final StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            final char c = s.charAt(i);
            if (c == '§' && i + 1 < s.length()) {
                i++;
                continue;
            }
            sb.append(c);
        }
        return sb.toString();
    }

    /** 一段文字按 {@link #charUnits(char)} 换算成像素宽（先剥色码）。 */
    public static int textPx(String s) {
        final String plain = stripColor(s);
        int n = 0;
        for (int i = 0; i < plain.length(); i++) {
            n += charUnits(plain.charAt(i)) == 2 ? PX_WIDE : PX_HALF;
        }
        return n;
    }

    // ---------------------------------------------------------------- 顶部那一排的名字

    /** 顶部那一排：一格的宽度（与 {@code ShanhaiRecipeTabBarWidget.TAB_W} 同值 —— 改一处必须两处一起改）。 */
    public static final int TAB_CELL_PX = 74;
    /** 一格里的名字起点（图标槽 18 ＋ 间距 2）。 */
    public static final int TAB_NAME_X = 20;
    /** 名字右边留的白（免得贴着下一格的图标）。 */
    public static final int TAB_NAME_PAD = 2;

    /**
     * 🔴 <b>名字区真正可用的像素宽 ＝ 74 − 20 − 2 ＝ 52 px</b>。
     *
     * <h2>这一条是"先量再改"的那个量（用户报的 ③）</h2>
     * 用户原话（逐字）：「<b>还有那上面机器名字只显示了前面一个字</b>」，截图里是 {@code 碎…}。
     * 量的结果：<b>控件宽 74 px、名字区 52 px ⇒ 放得下 5 个汉字（45 px）；而"粉碎机"只要 27 px</b>
     * —— 也就是说 <b>格子宽度根本不是瓶颈</b>。
     * 真凶是老代码里写死的裁参数：{@code clip(label, 5)} ⇒ {@code clipUnits(label, 5)}，
     * 而单位口径是"汉字算 2、省略号算 2" ⇒ 预算 {@code 5−2 = 3} ⇒ <b>只塞得进 1 个汉字</b>，
     * 于是"粉碎机"（6 个单位）被裁成"碎…"。
     * 判据见自检 {@code tab_name_fit}：那里把<b>老的裁法</b>也跑一遍，结果必须<b>逐字</b>等于
     * {@code 碎…}（＝复现用户截图），才是"根因找对了"。
     */
    public static final int TAB_NAME_PX = TAB_CELL_PX - TAB_NAME_X - TAB_NAME_PAD;

    /**
     * 这一排该用多大的字号：<b>整排统一</b>（一格大一号、一格小一号会很难看），
     * 取"最长的那个名字也放得下"的最大的那一档。
     *
     * @return 1.0 / 0.75 / 0.5；三档都放不下 ⇒ 返回 0.5（调用方再用 {@link #fitTabLabel} 收尾）
     */
    public static float tabNameScale(java.util.List<String> labels) {
        final float[] scales = {1.0f, 0.75f, 0.5f};
        int widest = 0;
        if (labels != null) {
            for (String l : labels) {
                widest = Math.max(widest, textPx(l));
            }
        }
        if (widest <= 0) {
            return 1.0f;
        }
        for (float s : scales) {
            if (widest * s <= (float) TAB_NAME_PX) {
                return s;
            }
        }
        return 0.5f;
    }

    /**
     * 把一个名字裁进 {@link #TAB_NAME_PX}（先按 {@code scale} 缩，再按像素留省略号）。
     *
     * <p>放得下就<b>原样返回</b>（这是用户要的"完整中文名"）；
     * 放不下才裁，而且裁完至少留 2 个汉字 —— <b>绝不再出现"只有一个字"</b>。
     */
    public static String fitTabLabel(String label, float scale) {
        final String s = stripColor(label);
        if (s.isEmpty()) {
            return "";
        }
        final float sc = scale <= 0f ? 1f : scale;
        if (textPx(s) * sc <= (float) TAB_NAME_PX) {
            return s;
        }
        for (int keep = s.length() - 1; keep >= 1; keep--) {
            final String cut = s.substring(0, keep) + "…";
            if (textPx(cut) * sc <= (float) TAB_NAME_PX) {
                return cut;
            }
        }
        return s.substring(0, 1) + "…";   // 理论上到不了（52/9/0.5 ≥ 11 个汉字）
    }

    /**
     * 组装一行（<b>纯函数</b>：给定 选中与否 / id / 类型名 / 时长 ⇒ 一行文字 + 宽度读数）。
     *
     * <p>排版优先级（用户对 B2 的要求「中文名之后确认列宽够放；放不下就缩短，但不许退回只显示英文」）：
     * <b>先保证中文类型名放得下</b>（它最多占 {@link #MAX_TYPE_UNITS} 个单位，中文名一般只占 8~16），
     * 余下的宽度给 id；id 已经压到 {@link #MIN_ID_UNITS} 还不够时，<b>才</b>回头缩类型名
     * （但也至少保留 4 个单位 = 两个汉字，绝不清空、绝不退回英文）。
     */
    public static Row composeRow(boolean selected, String id, String type, String typeSource, int duration) {
        final String dur = Integer.toString(duration);
        final String idFull = id == null || id.isEmpty() ? "?" : id;
        final String typeFull = type == null || type.isEmpty() ? "?" : type;
        final String prefix = selected ? "▶ " : "· ";

        // 固定开销：符号+空格 + " (" + ")" + " d=" + 时长。
        // 🔴 一律用 displayUnits 量，绝不在注释里手写"这是 2 个单位"——
        //    `▶` / `·` / `…` 都是非 ASCII，按 charUnits 都算 2（冒烟第 1 局就是在这里多算漏了 2）。
        final int fixed = displayUnits(prefix) + displayUnits(" (") + displayUnits(")")
                + displayUnits(" d=") + displayUnits(dur);

        int typeUnits = displayUnits(typeFull);
        String typeShown = typeUnits > MAX_TYPE_UNITS ? clipUnits(typeFull, MAX_TYPE_UNITS) : typeFull;
        boolean typeClipped = !typeShown.equals(typeFull);
        typeUnits = displayUnits(typeShown);

        final int idBudget = Math.max(MIN_ID_UNITS, ROW_UNITS - fixed - typeUnits);
        String idShown = clipUnits(idFull, idBudget);
        boolean idClipped = !idShown.equals(idFull);

        // 还不够（id 已到底线）⇒ 回头缩类型名，至少留 4 个单位
        final int over = fixed + typeUnits + displayUnits(idShown) - ROW_UNITS;
        if (over > 0) {
            typeShown = clipUnits(typeShown, Math.max(4, typeUnits - over));
            typeClipped = true;
        }

        final String plain = prefix + idShown + " (" + typeShown + ") d=" + dur;
        final int units = displayUnits(plain);
        final String text = (selected ? "§e▶§f " : "§8·§7 ") + idShown + " §8(" + typeShown + ") §7d=§f" + dur;
        return new Row(text, plain, units, units <= ROW_UNITS, idClipped, typeClipped, typeSource);
    }

    // ---------------------------------------------------------------- 小工具

    private String shortItem() {
        return itemId == null ? "?" : itemId.getPath();
    }

    /** {@code gtceu:assembler/zpm_256a_laser_source_hatch} → {@code zpm_256a_laser_source_hatch}。 */
    public static String shortId(ResourceLocation id) {
        if (id == null) {
            return "?";
        }
        final String p = id.getPath();
        final int i = p.lastIndexOf('/');
        return i >= 0 && i + 1 < p.length() ? p.substring(i + 1) : p;
    }

    private static String clip(String s, int n) {
        if (s == null) {
            return "";
        }
        return s.length() <= n ? s : s.substring(0, n - 1) + "…";
    }

    public boolean isLoaded() {
        return loaded;
    }
}
