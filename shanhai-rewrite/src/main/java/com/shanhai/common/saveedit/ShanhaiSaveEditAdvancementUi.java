package com.shanhai.common.saveedit;

import com.google.gson.JsonObject;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 第 ⑤ 页（成就）与其详情覆盖层的 <b>"纯 JDK 那一半"</b>：
 * 列表 / 翻页 / 搜索 / 详情读数 / 预演（不写盘）/ 带闸的写 / 以及所有要显示的字。
 *
 * <h2>一、为什么照 {@link ShanhaiSaveEditEntityUi} 抄这一层</h2>
 * 同一个理由（见那个类的注释 §一）：{@code ShanhaiSaveEditPanel} 继承 LDLib 的 {@code WidgetGroup}，
 * <b>离线加载不了</b>；而"打开 → 编辑 → 关闭"这条状态机、"给成就要写几样东西"、翻页算术
 * 必须能<b>离线判</b>。所以逻辑在这一层，控件那一层（{@link ShanhaiSaveEditAdvancementOverlay}）只负责画。
 *
 * <h2>二、🔴 这个页的"改"是两个动作，不是填值</h2>
 * <ul>
 *   <li><b>写入（给）</b> = {@link ShanhaiAdvancementEdit#ACTION_GRANT}：把这条成就定义里的<b>全部</b>
 *       criteria 写成"已达成 + 时间串"，并把 {@code done} 置真。三样缺一不可 ——
 *       只写 {@code done} 而不写 criteria，文件看着像完成了，<b>游戏里显示未完成</b>
 *       （原版 {@code AdvancementProgress$Serializer#deserialize} 只读 criteria，见数据层类注释 §二）。</li>
 *   <li><b>撤成就</b> = {@link ShanhaiAdvancementEdit#ACTION_REVOKE}：整条删掉。</li>
 * </ul>
 * ⇒ 所以覆盖层里没有输入框；criteria 名由游戏侧（{@code AdvancementManager}）提供，
 * 本类只经 {@link CriteriaSource} 拿它 —— 离线判据可以喂一份假的。
 *
 * <h2>三、🔴 名字中文在前（服务端只推原料）</h2>
 * 行与标题上的名字是<b>客户端现拼</b>的：{@link TitleSource#titleOrNull(String)} 取名字，
 * 取不到按"未注册 / 技术成就"回退成 {@code id〔记号〕}（<b>绝不空白、绝不崩</b>）。
 * 为什么不能在服务端拼好推过去：<b>服务端没有语言表</b>，
 * {@code Component.translatable(key).getString()} 在服务端会把翻译键原样返回
 * （离线实测：{@code advancements.story.mine_block.title} ⇒ 原样返回那个键）。
 *
 * <h3>名字的两层来源（{@link #register(TitleSource)} 那一层是本轮修的）</h3>
 * <ol>
 *   <li><b>第一层 = 客户端注册进来的真名字</b>（{@code com.shanhai.client.saveedit.ShanhaiSaveEditClientTitleSource}）：
 *       真 {@code display} 标题 → {@code rewards.recipes} 的产物名。这一层<b>只有客户端有</b>
 *       （它在客户端初始化处注册，专服上那个类根本不被加载）。</li>
 *   <li><b>第二层 = 语言表</b>（{@link #langTitleOrNull(String)}）：第一层没给名字时才查
 *       {@code advancements.<path>.title}。</li>
 * </ol>
 * 🔴 为什么必须有第一层：数据包型成就（{@code <mod>:recipes/…}）<b>没有 display</b>，
 * 语言表里也没有 {@code advancements.recipes.*.title}（0 条）⇒ 只靠第二层，占列表 93.3% 的行
 * 必然无名字；而它们的 {@code rewards.recipes} 有产物，产物自己有名字。
 * <b>{@code 〔技术成就〕} 这个记号的含义是"它本来就没有人类可读的标题"，不是"缺翻译"</b>。
 * <p>与第 ④ 页的差别只有一处：实体类型注册表是<b>公共</b>的
 * （{@code BuiltInRegistries.ENTITY_TYPE}，客户端也能 {@code containsKey}），
 * 而成就定义只在<b>服务端</b>的 {@code AdvancementManager} 里 ⇒ "这条成就在不在游戏里"
 * 这个事实由服务端随行一起推（{@code known} 那一位），名字仍然由客户端查语言表现拼。
 *
 * <h2>四、搜索为什么在这一层做（写清代价，别当没看见）</h2>
 * 数据层 {@code ShanhaiAdvancementEdit.listFile(file, knownIds, maxEntries)} <b>只有"取前 N 条"，
 * 没有 filter 参数</b>（第 ④ 页的 {@code listDimension} 有，这一页没有）。
 * 本轮的硬约束是"不许改数据层"，所以匹配只能放在这里，而且<b>只有这一个函数</b>
 * （{@link #filter(List, String)}，大小写不敏感、只匹配成就 id 的子串）。
 * 为什么只匹配 id 不匹配标题：搜索在<b>服务端</b>跑（由它决定推哪 7 行），而服务端没有语言表，
 * 拿标题匹配等于拿翻译键匹配。⇒ 要把它搬进数据层（4 行）说一声，代价是那边多一个 filter 参数。
 *
 * <h2>五、本类<b>不</b>判窗口</h2>
 * 与实体页同一条红线：真正落盘那一步必须紧贴 {@link ShanhaiSaveEditFlow#tryApply(String)}，
 * 见 {@link #writeGated}。
 */
public final class ShanhaiSaveEditAdvancementUi {

    /** 闸门上记的"谁在请求"（进 {@link ShanhaiSaveEditFlow.ApplyAttempt}）。 */
    public static final String WHO_GRANT = "panel:advancement-grant";
    public static final String WHO_REVOKE = "panel:advancement-revoke";

    /** 未注册的记号（模组被删 / id 拼错）。 */
    public static final String MARK_UNREGISTERED = "〔未注册〕";
    /**
     * 注册了、但<b>既没有 display 标题也没有配方产物</b>的记号。
     *
     * <p>🔴 不叫"无译名"：数据包型成就（{@code <mod>:recipes/…}）<b>本来就没有人类可读的标题</b>
     * —— 它的 JSON 里只有 criteria + rewards，连语言键都不存在（实测 {@code advancements.recipes.*.title} 0 条）。
     * 所以这不是"缺翻译"，是"它只有技术 id"。原样显示 id 才是诚实的信息。
     */
    public static final String MARK_TECHNICAL = "〔技术成就〕";
    /** id 本身是空的。 */
    public static final String MARK_NO_ID = "〔没有成就 id〕";

    /**
     * 一次「列出」最多从盘上取多少条。
     *
     * <p>翻页靠"取前 (page+1)×7 条再切最后一段"（数据层没有 offset 参数），所以这个数必须
     * <b>远大于</b>任何玩家可能翻到的页数 × 7；一个玩家的成就文件里最多几百条。
     */
    public static final int LIST_CAP = 4000;

    private ShanhaiSaveEditAdvancementUi() {}

    // ================================================================== 名字（🔴 中文在前）

    /**
     * "把成就 id 翻成当前语言下的标题"这个能力（由客户端提供）。
     *
     * <p>为什么要抽成接口：数据层只给<b>纯 id</b>；而离线判据需要一个"我喂什么就返回什么"的假实现
     * 来单测回退分支，客户端需要一个真实现（{@link #LANG_SOURCE}）。两者都实现这个接口。
     */
    public interface TitleSource {
        /** 当前语言下的标题；<b>取不到 ⇒ {@code null}</b>（不许返回空串、也不许返回翻译键）。 */
        String titleOrNull(String advancementId);
    }

    /** "这条成就定义里有哪些条件名"这个能力（服务端由 {@code AdvancementManager} 提供）。 */
    public interface CriteriaSource {
        List<String> criteriaOf(String advancementId);
    }

    /**
     * 成就 id → 原版 lang 键（<b>候选表，按优先顺序</b>）：
     * {@code minecraft:story/mine_block} ⇒ {@code advancements.story.mine_block.title}。
     *
     * <p>🔴 这里有一条<b>容易写错</b>的口径（离线实测踩过）：<b>原版成就的翻译键里没有命名空间</b>
     * （{@code {"translate":"advancements.story.mine_block.title"}}），而模组习惯写
     * {@code advancements.<modid>.<path>.title}。所以两种都试：
     * <ol>
     *   <li>{@code minecraft:} 开头 ⇒ 先试 {@code advancements.<path>}，再试 {@code advancements.minecraft.<path>}；</li>
     *   <li>其它命名空间 ⇒ 反过来。</li>
     * </ol>
     * 都不中就按"技术成就"回退（这是在<b>第二层</b>上回退 —— 第一层是
     * {@link #register(TitleSource)} 注册进来的客户端真名字，见类注释 §三）。
     *
     * @return 空表 = 这个 id 连 {@code namespace:path} 都不像
     */
    public static List<String> titleKeys(String advancementId) {
        final String id = advancementId == null ? "" : advancementId.trim();
        if (id.isEmpty()) {
            return List.of();
        }
        final int colon = id.indexOf(':');
        if (colon <= 0 || colon == id.length() - 1) {
            return List.of();
        }
        final String ns = id.substring(0, colon);
        final String dotted = id.substring(colon + 1).replace('/', '.');
        if ("minecraft".equals(ns)) {
            return List.of("advancements." + dotted + ".title",
                    "advancements." + ns + "." + dotted + ".title");
        }
        return List.of("advancements." + ns + "." + dotted + ".title",
                "advancements." + dotted + ".title");
    }

    /** 首选的那个 lang 键（{@code null} = 这个 id 连 {@code namespace:path} 都不像）。 */
    public static String titleKey(String advancementId) {
        final List<String> keys = titleKeys(advancementId);
        return keys.isEmpty() ? null : keys.get(0);
    }

    /**
     * 真·语言表查询（客户端才有中文）。取不到（没这条 / 服务端 / 注册表没起来）⇒ {@code null}。
     *
     * <p>这是名字的<b>第二层</b>；第一层是 {@link #register(TitleSource)} 注册进来的客户端真名字
     * （真 display → 配方产物）。只有第一层给不出名字时才走到这里。
     *
     * <p>服务端的判定：{@code getString()} 原样返回翻译键 ⇒ 按"没有这个名字"处理，
     * 所以服务端那份<b>永远</b>不会把 {@code advancements.xxx.title} 这样的键当成名字用。
     */
    public static String langTitleOrNull(String advancementId) {
        for (String key : titleKeys(advancementId)) {
            try {
                final String s = net.minecraft.network.chat.Component.translatable(key).getString();
                if (s != null && !s.isEmpty() && !s.equals(key)) {
                    return s;
                }
            } catch (Throwable t) {
                return null;            // 语言表没起来 / 其它异常：一律当"没名字"，不崩
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ 第一层：客户端注册的真名字

    /**
     * 客户端注册进来的"真名字来源"（{@code null} = 没注册）。
     *
     * <p>什么时候是 {@code null}：专服（那个客户端类根本不会被加载）、客户端初始化还没跑、
     * 以及离线判据（它自己喂假的 {@link TitleSource}，本来就不看这个字段）。
     */
    private static volatile TitleSource clientSource;

    /**
     * 注册"真名字来源"（<b>只在客户端初始化处调</b>；重复调用以最后一次为准）。
     *
     * <p>🔴 参数类型是<b>公共</b>接口 {@link TitleSource}，不是任何客户端类 ⇒ 本类（公共侧）
     * 没有任何一处引用客户端类型，专服上不可能因为这里而加载客户端类。
     */
    public static void register(TitleSource source) {
        clientSource = source;
    }

    /** 已经注册进来的那一个（{@code null} = 没注册；判据/调试用）。 */
    public static TitleSource registeredSource() {
        return clientSource;
    }

    /**
     * 🔴 <b>第一层 → 第二层</b>的派发：客户端真名字拿不到才查语言表。
     *
     * <p>这就是 {@link #LANG_SOURCE} 的实现，也就是面板/覆盖层实际用到的那条路
     * （它们传的是 {@code LANG_SOURCE}，见 {@code ShanhaiSaveEditPanel} 的行按钮与覆盖层标题）。
     * 所以"换名字来源"不需要改面板一行 —— 名字变了，是因为这个函数变了。
     *
     * <p>第一层抛异常只在它自己身上算"没名字"（一个坏配方不许把整行字弄崩），不影响第二层。
     */
    public static String titleOrNullNow(String advancementId) {
        final TitleSource source = clientSource;
        if (source != null) {
            try {
                final String name = source.titleOrNull(advancementId);
                if (name != null && !name.isEmpty()) {
                    return name;
                }
            } catch (Throwable t) {
                // 客户端那一层炸了 ⇒ 当它没给名字，继续走语言表（绝不冒泡到渲染里）
            }
        }
        return langTitleOrNull(advancementId);
    }

    /** 面板/覆盖层用的真实现：<b>客户端注册的真名字 → 语言表</b>（见 {@link #titleOrNullNow(String)}）。 */
    public static final TitleSource LANG_SOURCE = ShanhaiSaveEditAdvancementUi::titleOrNullNow;

    /**
     * <b>中文在前的标题</b>：{@code 石器时代（minecraft:story/mine_block）}。
     *
     * <p>查不到就回退（<b>绝不空白、绝不崩</b>）：服务端说"游戏里没有" ⇒ {@code id〔未注册〕}；
     * 有这条成就但两层都给不出名字 ⇒ {@code id〔技术成就〕}。
     */
    public static String titleLabel(String advancementId, boolean known, TitleSource src) {
        final String id = advancementId == null ? "" : advancementId.trim();
        if (id.isEmpty()) {
            return MARK_NO_ID;
        }
        if (!known) {
            return id + MARK_UNREGISTERED;
        }
        final String name = src == null ? null : src.titleOrNull(id);
        if (name == null || name.isEmpty()) {
            return id + MARK_TECHNICAL;
        }
        return name + "（" + id + "）";
    }

    /** 只要名字那半（行按钮用，省宽度）；回退形态与 {@link #titleLabel} 逐字相同。 */
    public static String titleShort(String advancementId, boolean known, TitleSource src) {
        final String id = advancementId == null ? "" : advancementId.trim();
        if (id.isEmpty()) {
            return MARK_NO_ID;
        }
        if (!known) {
            return id + MARK_UNREGISTERED;
        }
        final String name = src == null ? null : src.titleOrNull(id);
        if (name == null || name.isEmpty()) {
            return id + MARK_TECHNICAL;
        }
        return name;
    }

    // ================================================================== 行 / 概况

    /**
     * 一行可点的成就：{@code 1. 石器时代 ✔已完成 ·1 条}（🔴 名字在前，id 进 tooltip）。
     *
     * @param known 游戏里有没有这条成就的定义（服务端推来的<b>原料</b>之一）
     */
    public static String rowText(int index, String id, boolean present, boolean done, int criteria,
                                 boolean known, TitleSource src) {
        final String name = titleShort(id, known, src);
        if (name.isEmpty()) {
            return "";
        }
        final String state = done ? "§a✔ 已完成" : "§7… 未完成";
        return "§8" + (index + 1) + ". §f" + name
                + (present ? " " + state + " §8·" + criteria + " 条" : " §8[文件里还没有]");
    }

    /** 同 {@link #rowText(int, String, boolean, boolean, int, boolean, TitleSource)}，直接吃数据层的一行。 */
    public static String rowText(int index, ShanhaiAdvancementEdit.Entry e, boolean known, TitleSource src) {
        if (e == null) {
            return "";
        }
        return rowText(index, e.id(), e.present(), e.done(), e.criteria(), known, src);
    }

    /** 「列出」之后"概况"那一行。 */
    public static String summaryLine(ShanhaiAdvancementEdit.Listing l, int matched, String filter) {
        if (l == null) {
            return "§8还没列 · 点「列出」（只看这个玩家的成就文件）";
        }
        if (!l.ok()) {
            return "§c列不出来：" + (l.error() == null ? "（没给原因）" : l.error());
        }
        final StringBuilder sb = new StringBuilder();
        sb.append("§7文件里 ").append(l.present()).append(" 条 · 已完成 ").append(l.unlocked());
        if (l.known() > 0) {
            sb.append(" · 游戏里共 ").append(l.known()).append(" 条");
        }
        sb.append(" · 匹配 ").append(matched);
        // 🔴 这一行只有 360 px（槽位 summary.value 的宽 = W-32-8）⇒ 不许出现长词。
        //    "DataVersion 3465" 那一整段实测 102 px，把这一行顶到 372 px（被面板的 fit 从尾巴截掉）；
        //    换成短的 dv=（42 px），并且放在"搜索"之前：这样即使搜索词很长、尾巴被 fit 截掉，
        //    丢的也只是"和输入框里一模一样的那段搜索词"，dv 反而保住了。
        if (l.dataVersion() != null) {
            sb.append(" §8dv=").append(l.dataVersion());
        }
        if (filter != null && !filter.isEmpty()) {
            sb.append(" §8搜索「").append(filter).append("」");
        }
        return sb.toString();
    }

    /** 页码那一行（{@code 第 2/3 页}）。 */
    public static String pageText(int page, int matched) {
        return "第 " + (ShanhaiSaveEditLayout.clampPage(page, matched, ShanhaiSaveEditLayout.LP_ROWS) + 1)
                + "/" + ShanhaiSaveEditLayout.pageCount(matched, ShanhaiSaveEditLayout.LP_ROWS) + " 页";
    }

    /** 页码旁边那个计数（{@code 共 16 条 · 本页 8-14}）。 */
    public static String pageCountText(int page, int matched) {
        final int rows = ShanhaiSaveEditLayout.LP_ROWS;
        if (matched <= 0) {
            return "§8没有匹配的成就";
        }
        final int p = ShanhaiSaveEditLayout.clampPage(page, matched, rows);
        return "§8共 " + matched + " 条 · 本页 " + (p * rows + 1) + "-" + Math.min(matched, (p + 1) * rows);
    }

    /**
     * 第 ⑤ 页说明那一行（客户端自己知道的字）。
     *
     * <p>🔴 必须放得进 386 px（LDLib 不裁也不换行）—— 所以"是什么文件"只写半句，
     * "点一行进详情 / 落盘在停服窗口"这两件玩家必须知道的事写在最前面。
     */
    public static String hintLine() {
        return "§85 成就：点一行进详情；改完点右上「确认」才在停服窗口里落盘";
    }

    /** 第 ⑤ 页底部那行提示（同样必须放得进 392 px）。 */
    public static String footerLine() {
        return "§8·「写入」= 写满条件 + done；「撤」= 整条删掉";
    }

    // ================================================================== 搜索 / 翻页（纯下标运算）

    /**
     * 搜索：🔴 <b>本工程里唯一的那个匹配函数</b>（第 ⑤ 页用；理由见类注释 §四）。
     *
     * <p>口径：大小写不敏感、匹配成就 id 的子串（{@code story/mine_block} 这种）。
     * 不匹配标题 —— 搜索在服务端跑，服务端没有语言表。
     */
    public static List<ShanhaiAdvancementEdit.Entry> filter(List<ShanhaiAdvancementEdit.Entry> entries,
                                                           String term) {
        final List<ShanhaiAdvancementEdit.Entry> out = new ArrayList<>();
        if (entries == null) {
            return out;
        }
        final String f = term == null ? "" : term.trim().toLowerCase(Locale.ROOT);
        for (ShanhaiAdvancementEdit.Entry e : entries) {
            if (e == null) {
                continue;
            }
            if (f.isEmpty() || (e.id() != null && e.id().toLowerCase(Locale.ROOT).contains(f))) {
                out.add(e);
            }
        }
        return List.copyOf(out);
    }

    /**
     * 从"过滤后的全量列表"里切出第 {@code page} 页（不读盘、不改列表）。
     *
     * <p>为什么这里需要全量：数据层只支持"取前 N 条"，翻页 = 取前 (page+1)×7 再切；
     * 过滤之后下标会变，所以切页必须在过滤后的列表上做（见 {@link ShanhaiSaveEditLayout#fetchLimit}）。
     */
    public static List<ShanhaiAdvancementEdit.Entry> pageRows(List<ShanhaiAdvancementEdit.Entry> all, int page) {
        if (all == null || all.isEmpty()) {
            return List.of();
        }
        final int rows = ShanhaiSaveEditLayout.LP_ROWS;
        final int from = ShanhaiSaveEditLayout.pageFrom(page, all.size(), rows);
        if (from >= all.size()) {
            return List.of();
        }
        final int to = Math.min(all.size(), from + rows);
        return List.copyOf(all.subList(from, to));
    }

    // ================================================================== 详情（点一行之后）

    /**
     * 一条成就的详情读数。
     *
     * @param known          游戏里有没有这条成就的定义（{@code false} ⇒ 只能撤、不能给）
     * @param present        文件里有没有这一条
     * @param done           文件里 {@code done} 是不是 true
     * @param criteriaInFile 文件里已经写了几个条件
     * @param criteria       定义里的<b>全部</b>条件名（"给"必须写满这一串）
     */
    public record Detail(String id, boolean known, boolean present, boolean done, int criteriaInFile,
                         int fileEntries, List<String> criteria) {

        /** id 归一化（永不 null）。 */
        public String key() {
            return this.id == null ? "" : this.id.trim();
        }

        /** 能不能"给"：游戏里得有这条定义、且知道它的条件名（数据层也这么判，见 Request#validate）。 */
        public boolean canGrant() {
            return this.known && this.criteria != null && !this.criteria.isEmpty();
        }
    }

    /** 把"列表里的一行 + 游戏侧的定义"拼成详情（点一行时调它）。 */
    public static Detail describe(ShanhaiAdvancementEdit.Entry e, boolean known, CriteriaSource src,
                                  int fileEntries) {
        if (e == null) {
            return new Detail("", false, false, false, 0, fileEntries, List.of());
        }
        final List<String> criteria = new ArrayList<>();
        if (known && src != null && e.id() != null) {
            try {
                final List<String> got = src.criteriaOf(e.id());
                if (got != null) {
                    criteria.addAll(got);
                }
            } catch (Throwable t) {
                // 游戏侧查不动 ⇒ 空表（详情会如实说"给不了"，绝不用假条件名去写盘）
            }
        }
        return new Detail(e.id(), known, e.present(), e.done(), e.criteria(), fileEntries,
                List.copyOf(criteria));
    }

    // ================================================================== 预演（不写一个字节）

    /** 一次"如果点写入会发生什么"的读数。 */
    public record Preview(boolean ok, String error, List<String> changed, List<String> notes) {

        public static Preview fail(String why) {
            return new Preview(false, why == null ? "被拒绝" : why, List.of(), List.of());
        }

        /** 一行摘要（"将改：…"）。 */
        public String changedLine() {
            if (!this.ok) {
                return "§c" + this.error;
            }
            return "§7将改：§f" + (this.changed.isEmpty() ? "(没有变化)" : String.join("／", this.changed));
        }
    }

    /**
     * 🔴 <b>预演</b>：只读地解一遍成就文件，把改动应用到一份<b>内存副本</b>上。
     *
     * <p>与数据层 {@code applyToFile} 的<b>写前校验</b>逐条同源（同一批公开函数：
     * {@code grant/revoke} → {@code verifyGrant/verifyRevoke} → {@code diffJson/allDiffsUnder}），
     * 区别只有一处：<b>这里不调 {@code writeAtomic}</b>。所以"点写入之前就能看到将改什么"与
     * "数据层会怎么拒绝"同时成立，而盘上一个字节都不动。
     */
    public static Preview preview(Path advFile, ShanhaiAdvancementEdit.Request req) {
        if (advFile == null || !Files.isRegularFile(advFile)) {
            return Preview.fail("还没有成就文件（这个玩家还没进过这个世界）："
                    + (advFile == null ? "-" : advFile.getFileName()));
        }
        if (req == null || req.isEmpty()) {
            return Preview.fail("还没选中一条成就（成就 id 是空的）");
        }
        try {
            req.validate();
        } catch (IllegalArgumentException iae) {
            return Preview.fail(iae.getMessage());
        }
        final JsonObject before;
        try {
            before = ShanhaiJsonText.readObject(advFile);
        } catch (Throwable t) {
            return Preview.fail("读成就文件失败：" + t);
        }
        final JsonObject after = before.deepCopy();
        if (req.isGrant()) {
            ShanhaiAdvancementEdit.grant(after, req.id(), req.criteria(),
                    ShanhaiAdvancementEdit.formatTimestamp(System.currentTimeMillis()));
        } else {
            ShanhaiAdvancementEdit.revoke(after, req.id());
        }
        final ShanhaiAdvancementEdit.Verify v = req.isGrant()
                ? ShanhaiAdvancementEdit.verifyGrant(after, req.id(), req.criteria())
                : ShanhaiAdvancementEdit.verifyRevoke(after, req.id());
        if (!v.ok()) {
            return Preview.fail("改完的进度没通过校验（未写盘）：" + v.line());
        }
        final List<String> intended = ShanhaiJsonText.diffJson(before, after);
        if (!ShanhaiJsonText.allDiffsUnder(intended, req.id())) {
            return Preview.fail("算出来的差异超出了目标成就（未写盘）："
                    + ShanhaiJsonText.firstDiffOutside(intended, req.id()) + " intended=" + intended);
        }
        if (intended.isEmpty()) {
            return Preview.fail("no_effective_change（这条成就本来就是这个状态）");
        }
        return new Preview(true, "", List.copyOf(intended), List.copyOf(v.changes()));
    }

    // ================================================================== 带闸的写

    /** 一次"带闸"尝试的结果：{@code permitted=false} ⇒ 一个字节都没写。 */
    public record Apply(boolean permitted, String refusal, ShanhaiAdvancementEdit.Result write) {

        public boolean ok() {
            return this.permitted && this.write != null && this.write.ok();
        }

        public String line() {
            if (!this.permitted) {
                return "§e" + (this.refusal == null ? "被拒绝" : this.refusal);
            }
            return this.write == null ? "§c没有结果" : "§f" + this.write.line();
        }
    }

    /**
     * 给 / 撤一条成就（<b>带闸</b>：{@code flow} 不在 STOPPED 就一个字节都不写）。
     *
     * <p>为什么闸在这里而不在调用方：与 {@link ShanhaiSaveEditEntityUi#writeGated} 同一条口径
     * —— 覆盖层只负责"把字念给玩家听"，闸必须紧贴落盘那一行。
     * 落盘仍然走数据层的 {@link ShanhaiAdvancementEdit#apply}（<b>没有另开写盘路径</b>）；
     * 游戏里的常态是"窗口没开 ⇒ 拒绝 ⇒ 面板把它暂存进 holder，等「确认修改并重载」"，
     * 那条路最终由 {@link ShanhaiSaveEditWindow} 的 ⑥ 段调同一个数据层入口。
     */
    public static Apply writeGated(Path worldDir, String playerUuid, ShanhaiAdvancementEdit.Request req,
                                   Path backupRoot, String stamp, ShanhaiSaveEditFlow flow) {
        if (worldDir == null || playerUuid == null || playerUuid.isEmpty()) {
            return new Apply(false, "缺少存档路径或玩家 UUID", null);
        }
        if (req == null || req.isEmpty()) {
            return new Apply(false, "没有要改的成就（成就 id 是空的）", null);
        }
        final ShanhaiSaveEditFlow.ApplyAttempt attempt =
                flow == null ? null : flow.tryApply(req.isGrant() ? WHO_GRANT : WHO_REVOKE);
        if (attempt == null || !attempt.permitted()) {
            return new Apply(false, windowClosed(flow), null);
        }
        final ShanhaiAdvancementEdit.Result o =
                ShanhaiAdvancementEdit.apply(worldDir, playerUuid, req, backupRoot, stamp);
        if (o != null && o.ok()) {
            flow.markApplied();
        }
        return new Apply(true, "", o);
    }

    /** 窗口没开时那句话（与第 ④ 页同一个说法，玩家听两次不会以为是两件事）。 */
    public static String windowClosed(ShanhaiSaveEditFlow flow) {
        return "停服窗口没开（" + (flow == null ? "当前没有 saveedit 运行" : "现在在 " + flow.phase() + " 阶段")
                + "）⇒ 一个字节都没写";
    }

    // ================================================================== 覆盖层的状态机

    /**
     * 覆盖层的<b>状态机 + 读数缓冲</b>（两侧各一份；只有服务端那份会被喂进 {@link Detail}）。
     *
     * <pre>
     *   关闭 ──open()──▶ 打开（没有任何读数）──openDetail(d)──▶ 打开（有读数）
     *     ▲                                                        │
     *     └──────────────────────── close() ───────────────────────┘
     *   打开时按「写入（给）」/「撤成就」：<b>一次点击就是一次真做</b> —— 同一次点击里先算预演
     *   （拿它当"将改"读数与拒绝理由），预演过了才真写；窗口没开就交给面板暂存，
     *   等「确认修改并重载」。
     *   🔴 <b>没有"再点一次"这一步</b>（用户 2026-11-15 的原话：「除非我指定需要二次确认，否则都不加」）：
     *   原来那个 {@code arm} 字段已删除；失败/被拒只影响这一次点击（下一次还是从预演开始）。
     * </pre>
     */
    public static final class Model {

        private boolean opened;
        private Detail detail;
        /** 标题用的"原始料"：成就 id + 游戏里在不在（🔴 名字由客户端现拼，见类注释 §三）。 */
        private String titleId = "";
        private boolean titleKnown;
        private String feedback = "";
        private final List<String> changed = new ArrayList<>();
        private final List<String> notes = new ArrayList<>();

        // ---------------------------------------------------------------- 开 / 关

        /** 打开（清空一切：上一条成就的残留绝不许带进来）。 */
        public void open() {
            reset();
            this.opened = true;
        }

        /** 关掉（同样清空）。 */
        public void close() {
            reset();
            this.opened = false;
        }

        private void reset() {
            this.detail = null;
            this.titleId = "";
            this.titleKnown = false;
            this.feedback = "";
            this.changed.clear();
            this.notes.clear();
        }

        /** 服务端把这一条成就的读数灌进来（点一行之后）。 */
        public void openDetail(Detail d) {
            open();
            this.detail = d;
            this.titleId = d == null || d.id() == null ? "" : d.id();
            this.titleKnown = d != null && d.known();
        }

        /** 客户端收标题料（覆盖层的 {@code UPDATE_TITLE}）：id + 在不在，原样存着，画的时候再拼。 */
        public void setTitlePayload(String advancementId, boolean known) {
            this.titleId = advancementId == null ? "" : advancementId;
            this.titleKnown = known;
        }

        /** 标题料里的成就 id（判据/调试用）。 */
        public String titleId() {
            return this.titleId;
        }

        public boolean opened() {
            return this.opened;
        }

        public Detail detail() {
            return this.detail;
        }

        public String feedback() {
            return this.feedback == null ? "" : this.feedback;
        }

        public void setFeedback(String text) {
            this.feedback = text == null ? "" : text;
        }

        public void setPreview(Preview p) {
            this.changed.clear();
            this.notes.clear();
            if (p == null) {
                return;
            }
            if (p.ok()) {
                this.changed.addAll(p.changed());
                this.notes.addAll(p.notes());
            } else {
                this.feedback = "§c" + p.error();
            }
        }

        public List<String> changed() {
            return List.copyOf(this.changed);
        }

        public List<String> notes() {
            return List.copyOf(this.notes);
        }

        // ---------------------------------------------------------------- 覆盖层上的字

        /**
         * 标题那一行：<b>中文在前</b> —— {@code 石器时代（minecraft:story/mine_block）}。
         *
         * <p>🔴 这个名字必须在<b>客户端</b>现拼（服务端没有语言表）。所以本方法用的是
         * {@link #setTitlePayload(String, boolean)} 收到的"原始料"，而标题那个
         * {@code LabelWidget} 是 {@code setClientSideWidget()} 的（服务端不会把它的字推过来覆盖掉）。
         * 永远不返回 {@code null}（LDLib 的标签对 null 会 NPE）。
         */
        public String title() {
            if (this.titleId.isEmpty()) {
                return "成就详情";
            }
            return "§f" + titleLabel(this.titleId, this.titleKnown, LANG_SOURCE);
        }

        /**
         * 第 {@code index} 行读数（0~7）。
         *
         * <p>行序：状态 / 条件清单 / 条数对照 / "给"的预期 / "撤"的预期 / 反馈 / 将改 / 备注。
         */
        public String note(int index) {
            final Detail d = this.detail;
            switch (index) {
                case 0:
                    if (d == null) {
                        return "§8成就详情还没读出来（点一行，服务端会去盘上读）";
                    }
                    return "§7状态 " + (d.present()
                            ? (d.done() ? "§a✔ 已完成" : "§7… 未完成")
                            : "§8文件里还没有这条")
                            + " §8· 文件里 criteria §f" + d.criteriaInFile()
                            + " §8· 定义 criteria §f" + (d.criteria() == null ? 0 : d.criteria().size());
                case 1: {
                    if (d == null || d.criteria() == null || d.criteria().isEmpty()) {
                        return "§7条件 §8（游戏里查不到这条成就的定义 ⇒ 只能撤、不能给）";
                    }
                    return "§7条件 §f" + String.join("、", d.criteria());
                }
                case 2:
                    if (d == null) {
                        return "";
                    }
                    return "§8成就 id §7" + d.key() + " §8· 文件里共 " + d.fileEntries() + " 条";
                case 3:
                    return "§7「写入」= 把上面 §f"
                            + (d == null || d.criteria() == null ? 0 : d.criteria().size())
                            + " §7条条件全写达成 + done=true（其它成就一条不动）";
                case 4:
                    return "§7「撤成就」= 把这条从文件里整条删掉（其它键不动）";
                case 5:
                    return this.feedback.isEmpty() ? "" : this.feedback;
                case 6:
                    return this.changed.isEmpty() ? ""
                            : "§8将改：" + String.join("／", this.changed);
                case 7:
                    return "§8时间串口径 " + ShanhaiAdvancementEdit.DATE_PATTERN + "（原版 CriterionProgress 同一个）";
                default:
                    return "";
            }
        }
    }
}
