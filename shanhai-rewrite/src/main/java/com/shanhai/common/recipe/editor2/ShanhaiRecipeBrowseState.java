package com.shanhai.common.recipe.editor2;

import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import com.shanhai.ShanhaiMod;
import com.shanhai.common.recipe.editor.ShanhaiRecipeEditorWorkspace;
import com.shanhai.common.recipe.editor.ShanhaiRecipeQuery;
import com.shanhai.common.recipe.editor.ShanhaiRecipeReverseIndex;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.player.Player;

import java.util.ArrayList;
import java.util.List;

/**
 * 山海重构 · <b>新版（上游那套）浏览页的只读状态</b> —— 第 1 步的「适配层」。
 *
 * <h2>1. 为什么要有这一个类（而不是直接照上游的 API 写）</h2>
 * 上游那套界面直接调他的后端（{@code ShanhaiRecipeQuery.query(...)} / {@code new ShanhaiRecipeBase(...)} /
 * {@code result.cards()} / {@code result.mode()} / {@code ShanhaiRecipeFingerprint.of(...)}）——
 * 那批签名<b>在我们同名类里一个都不存在</b>（逐条对账见 {@code temp/upstream-editor/对账.md} ③
 * 的 30 条，试编译 E6 实测 260 个错误）。
 * ⇒ 本类<b>不</b>照他的形状写，而是把<b>我们自己的</b>读口（
 * {@link ShanhaiRecipeEditorWorkspace} / {@link ShanhaiRecipeReverseIndex} / {@link ShanhaiRecipeQuery}）
 * 适配成"浏览页要的那几件事"：<b>类型列表 · 某一类型的配方卡片 · 翻页</b>。他的类一行都没有被改。
 *
 * <h2>2. 🔴 数据方向（这是与上游最大的结构差）</h2>
 * 上游是"<b>拿着一个 base 到处走</b>"（值对象）；我们是"<b>拿着一个 id 去查</b>"（全静态注册表）
 * ⇒ 本类的口径照我们的来：<b>服务端算出"这一页要画什么"，然后推给客户端画</b>。
 * 客户端<b>不碰配方表</b>（{@code ws == null}），所以它画不出任何服务端没给它的东西。
 *
 * <h2>3. 只读（本步的红线）</h2>
 * 本类<b>只有</b>三个动作：选类型 / 类型页翻页 / 卡片页翻页。
 * <b>没有</b>任何落盘、没有 {@code ops}、没有 {@code setIo} —— 点卡片<b>不进编辑</b>（不"半接"）。
 *
 * <h2>4. 两侧同步走哪条路</h2>
 * 与既有大面板<b>同一条</b>：一个 {@code Widget} 的
 * {@code writeInitialData / readInitialData / detectAndSendChanges / readUpdateInfo}
 * （见 {@code ShanhaiRecipeEditorPanel.StatePump} 的实证写法）—— <b>不新开网络包</b>。
 * 客户端点击用 LDLib 既有的 {@code writeClientAction}/{@code handleClientAction} 上行。
 */
public final class ShanhaiRecipeBrowseState {

    public static final String PREFIX = "[SHANHAI-EDIT] editor2";

    /** 左边类型列表一页几行（行高 18 ⇒ 234 px，见面板几何）。 */
    public static final int TYPES_PER_PAGE = 13;

    /** 右边卡片一页几张（他的卡片行高 28）。 */
    public static final int CARDS_PER_PAGE = 6;

    // ------------------------------------------------------------ 两侧共有的"显示态"

    /** 类型总数（服务端真值；客户端由包带来）。 */
    private int typeTotal;
    private int typePage;
    private int typePageCount = 1;

    /** <b>本页</b>的类型行（长度 ≤ {@link #TYPES_PER_PAGE}）—— 服务端与客户端装的是同一批。 */
    private final List<String> pageTypeIds = new ArrayList<>();
    private final List<String> pageTypeNames = new ArrayList<>();
    private final List<Integer> pageTypeCounts = new ArrayList<>();

    private String selectedTypeId = "";
    private String selectedTypeName = "";

    /** 当前类型的配方总数。 */
    private int cardTotal;
    private int cardPage;
    private int cardPageCount = 1;

    /** <b>本页</b>的卡片（长度 ≤ {@link #CARDS_PER_PAGE}），用我们的 {@link ShanhaiRecipeQuery.Card} 原样承载。 */
    private final List<ShanhaiRecipeQuery.Card> cards = new ArrayList<>();

    /** 顶上一行说明文字（服务端算好，客户端原样画）。 */
    private String note = "";

    /** 状态版本号：变一次 = 该推一次。 */
    private int version;

    // ------------------------------------------------------------ 服务端专有

    /** 服务端那份数据引擎；<b>客户端恒为 null</b>（客户端没有配方表，也不许凭空显示数字）。 */
    private final ShanhaiRecipeEditorWorkspace ws;
    private final MinecraftServer server;
    private final boolean clientSide;

    public ShanhaiRecipeBrowseState(MinecraftServer server, Player player, boolean clientSide) {
        this.clientSide = clientSide;
        this.server = clientSide ? null : server;
        // ⚠️ 客户端那份的 server 传 null（与既有大面板逐字同一口径）：
        //    它只是"装显示态"的容器，不许在任何路径上拿 server 去查表。
        this.ws = new ShanhaiRecipeEditorWorkspace(this.server, player);
        if (!clientSide) {
            reloadTypes();
        }
    }

    public boolean isClientSide() {
        return clientSide;
    }

    public int version() {
        return version;
    }

    // ------------------------------------------------------------ 服务端：算

    /** 重读类型列表（走我们既有的 {@code reloadTypes()} —— 它内含开机快照并集那条既有裁决）。 */
    public void reloadTypes() {
        if (clientSide) {
            return;
        }
        ws.reloadTypes();
        typeTotal = ws.typeRows().size();
        typePageCount = Math.max(1, (typeTotal + TYPES_PER_PAGE - 1) / TYPES_PER_PAGE);
        clampTypePage();
        rebuildTypePage();
        version++;
        ShanhaiMod.LOGGER.info("{} browse_reload_types total={} pages={} page={} note={}",
                PREFIX, typeTotal, typePageCount, typePage, ws.typeRows().isEmpty() ? "(空表)" : "ok");
    }

    private void clampTypePage() {
        if (typePage < 0) {
            typePage = 0;
        }
        if (typePage >= typePageCount) {
            typePage = typePageCount - 1;
        }
    }

    private void rebuildTypePage() {
        pageTypeIds.clear();
        pageTypeNames.clear();
        pageTypeCounts.clear();
        if (clientSide) {
            return;
        }
        final List<ShanhaiRecipeEditorWorkspace.TypeRow> rows = ws.typeRows();
        final int from = typePage * TYPES_PER_PAGE;
        final int to = Math.min(rows.size(), from + TYPES_PER_PAGE);
        for (int i = from; i < to; i++) {
            final ShanhaiRecipeEditorWorkspace.TypeRow r = rows.get(i);
            pageTypeIds.add(r.id().toString());
            pageTypeNames.add(r.name() == null ? r.id().getPath() : r.name());
            pageTypeCounts.add(r.count());
        }
        // 顶上一行读数：总类型数 + 第几页（客户端照抄，不许自己算第二份）
        note = "类型 " + typeTotal + " 种 · 第 " + (typePage + 1) + "/" + typePageCount + " 页";
    }

    /** 选一个类型 ⇒ 列它的配方（**只读**：不载入编辑、不落盘）。 */
    public boolean selectType(String idText) {
        if (clientSide) {
            return false;
        }
        final ResourceLocation id = ResourceLocation.tryParse(idText);
        if (id == null) {
            return false;
        }
        if (!ws.selectType(id)) {
            ShanhaiMod.LOGGER.warn("{} browse_select_type_failed id={}", PREFIX, id);
            return false;
        }
        selectedTypeId = idText;
        selectedTypeName = nameOf(idText, id.getPath());
        cardPage = 0;
        rebuildCards();
        version++;
        ShanhaiMod.LOGGER.info("{} browse_select_type id={} cards={} pages={}",
                PREFIX, id, cardTotal, cardPageCount);
        return true;
    }

    private String nameOf(String idText, String fallback) {
        for (int i = 0; i < pageTypeIds.size(); i++) {
            if (pageTypeIds.get(i).equals(idText)) {
                return pageTypeNames.get(i);
            }
        }
        return fallback;
    }

    /** 把"这一页要画的卡片"算出来（走我们的反查索引 + {@code ShanhaiRecipeQuery.cardOf}）。 */
    public void rebuildCards() {
        if (clientSide) {
            return;
        }
        cards.clear();
        final List<ResourceLocation> list = ws.recipeRows();
        cardTotal = list.size();
        cardPageCount = Math.max(1, (cardTotal + CARDS_PER_PAGE - 1) / CARDS_PER_PAGE);
        if (cardPage < 0) {
            cardPage = 0;
        }
        if (cardPage >= cardPageCount) {
            cardPage = cardPageCount - 1;
        }
        int skipped = 0;
        for (int i = 0; i < CARDS_PER_PAGE; i++) {
            final int idx = cardPage * CARDS_PER_PAGE + i;
            if (idx < 0 || idx >= list.size()) {
                continue;
            }
            final ResourceLocation rid = list.get(idx);
            final GTRecipe r = ShanhaiRecipeReverseIndex.byId(server, rid);
            if (r == null) {
                // 反查索引里没有 ⇒ 这一条【不画】（宁可少一张，也不画一张假的）
                skipped++;
                continue;
            }
            final ShanhaiRecipeQuery.Card c = ShanhaiRecipeQuery.cardOf(r, false);
            if (c != null) {
                cards.add(c);
            } else {
                skipped++;
            }
        }
        note = (selectedTypeName.isEmpty() ? "（未选类型）" : selectedTypeName)
                + " · 配方 " + cardTotal + " 条 · 第 " + (cardPage + 1) + "/" + cardPageCount + " 页"
                + (skipped > 0 ? " · 本页 " + skipped + " 条查不到（未画）" : "");
    }

    // ------------------------------------------------------------ 服务端：翻页（只读）

    public boolean flipTypePage(int delta) {
        if (clientSide || delta == 0) {
            return false;
        }
        final int want = typePage + delta;
        if (want < 0 || want >= typePageCount) {
            return false;
        }
        typePage = want;
        rebuildTypePage();
        version++;
        return true;
    }

    public boolean flipCardPage(int delta) {
        if (clientSide || delta == 0) {
            return false;
        }
        final int want = cardPage + delta;
        if (want < 0 || want >= cardPageCount) {
            return false;
        }
        cardPage = want;
        rebuildCards();
        version++;
        return true;
    }

    // ------------------------------------------------------------ 两侧：读写

    /** 服务端 → 客户端。<b>字段顺序与 {@link #readState} 逐字对应</b>（这条纪律的由来见大面板的 P0 注释）。 */
    public void writeState(FriendlyByteBuf buf) {
        buf.writeVarInt(version);
        buf.writeUtf(note == null ? "" : note);
        buf.writeUtf(selectedTypeId);
        buf.writeUtf(selectedTypeName);
        buf.writeVarInt(typeTotal);
        buf.writeVarInt(typePage);
        buf.writeVarInt(typePageCount);
        buf.writeVarInt(pageTypeIds.size());
        for (int i = 0; i < pageTypeIds.size(); i++) {
            buf.writeUtf(pageTypeIds.get(i));
            buf.writeUtf(pageTypeNames.get(i));
            buf.writeVarInt(pageTypeCounts.get(i));
        }
        buf.writeVarInt(cardTotal);
        buf.writeVarInt(cardPage);
        buf.writeVarInt(cardPageCount);
        buf.writeVarInt(cards.size());
        for (ShanhaiRecipeQuery.Card c : cards) {
            ShanhaiRecipeQuery.writeCard(buf, c);
        }
    }

    /** 客户端：把自己那份显示态换成服务端推来的这一份。 */
    public void readState(FriendlyByteBuf buf) {
        version = buf.readVarInt();
        note = buf.readUtf();
        selectedTypeId = buf.readUtf();
        selectedTypeName = buf.readUtf();
        typeTotal = buf.readVarInt();
        typePage = buf.readVarInt();
        typePageCount = buf.readVarInt();
        pageTypeIds.clear();
        pageTypeNames.clear();
        pageTypeCounts.clear();
        final int tn = buf.readVarInt();
        for (int i = 0; i < tn; i++) {
            pageTypeIds.add(buf.readUtf());
            pageTypeNames.add(buf.readUtf());
            pageTypeCounts.add(buf.readVarInt());
        }
        cardTotal = buf.readVarInt();
        cardPage = buf.readVarInt();
        cardPageCount = buf.readVarInt();
        cards.clear();
        final int cn = buf.readVarInt();
        for (int i = 0; i < cn; i++) {
            cards.add(ShanhaiRecipeQuery.readCard(buf));
        }
    }

    // ------------------------------------------------------------ 读口（画的时候用）

    public String note() {
        return note == null ? "" : note;
    }

    public int typeRowCount() {
        return pageTypeIds.size();
    }

    public String typeIdAt(int i) {
        return i < 0 || i >= pageTypeIds.size() ? "" : pageTypeIds.get(i);
    }

    public String typeNameAt(int i) {
        return i < 0 || i >= pageTypeNames.size() ? "" : pageTypeNames.get(i);
    }

    public int typeCountAt(int i) {
        return i < 0 || i >= pageTypeCounts.size() ? -1 : pageTypeCounts.get(i);
    }

    public int typeTotal() {
        return typeTotal;
    }

    public int typePage() {
        return typePage;
    }

    public int typePageCount() {
        return typePageCount;
    }

    public boolean canTypePage(int delta) {
        final int want = typePage + delta;
        return want >= 0 && want < typePageCount;
    }

    public String selectedTypeId() {
        return selectedTypeId == null ? "" : selectedTypeId;
    }

    public String selectedTypeName() {
        return selectedTypeName == null ? "" : selectedTypeName;
    }

    public int cardRowCount() {
        return cards.size();
    }

    public ShanhaiRecipeQuery.Card cardAt(int i) {
        return i < 0 || i >= cards.size() ? null : cards.get(i);
    }

    public int cardTotal() {
        return cardTotal;
    }

    public int cardPage() {
        return cardPage;
    }

    public int cardPageCount() {
        return cardPageCount;
    }

    public boolean canCardPage(int delta) {
        final int want = cardPage + delta;
        return want >= 0 && want < cardPageCount;
    }

    /** 一行机器可读读数（自检与日志共用）。 */
    public String statsLine() {
        return "client=" + clientSide + " types=" + typeTotal + " type_page=" + typePage + "/" + typePageCount
                + " rows_on_page=" + pageTypeIds.size() + " sel=" + (selectedTypeId.isEmpty() ? "-" : selectedTypeId)
                + " cards=" + cardTotal + " card_page=" + cardPage + "/" + cardPageCount
                + " cards_on_page=" + cards.size() + " v=" + version;
    }
}
