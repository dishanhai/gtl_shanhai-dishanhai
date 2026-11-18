package com.dishanhai.gt_shanhai.common.saveedit;

import com.google.gson.JsonObject;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 第 ⑥ 页（统计）与其详情覆盖层的 <b>"纯 JDK 那一半"</b>：
 * 列表 / 翻页 / 详情读数 / 预演（不写盘）/ 带闸的写 / 以及所有要显示的字。
 *
 * <h2>一、与第 ⑤ 页逐格同构（同一套骨架）</h2>
 * 版式、翻页算术、覆盖层状态机都照第 ⑤ 页与第 ④ 页，只有两处实质差别：
 * <ul>
 *   <li><b>"改"是写一个整数</b>：{@link ShanhaiStatsEdit#ACTION_SET}（设值，可写 0）或
 *       {@link ShanhaiStatsEdit#ACTION_CLEAR}（清零 = 整条键删掉，游戏读不到就当 0）；</li>
 *   <li><b>过滤是数据层给的</b>：{@code ShanhaiStatsEdit.listFile(file, category, max)}
 *       的第二个参数<b>就是</b>数据层的 filter（只列这个分类）⇒ 本页的"可搜索"直接用它的，
 *       <b>一行匹配代码都不写</b>（第 ⑤ 页因为数据层没有 filter 才不得不在 UI 层放一个，
 *       见 {@link ShanhaiSaveEditAdvancementUi} 类注释 §四）。</li>
 * </ul>
 *
 * <h2>二、🔴 名字中文在前（服务端只推原料）</h2>
 * 行/标题上的名字由<b>客户端</b>现拼：{@link #statNameOrNull(String, String)} 按原版口径查语言表
 * （{@code stat.minecraft.jump} ⇒ 「跳跃次数」）。查不到按"未注册 / 无译名"回退成
 * {@code id〔记号〕}，<b>绝不空白、绝不崩</b>。
 * <p>"注册"这件事查的是<b>公共注册表</b> {@code BuiltInRegistries.STAT_TYPE}（客户端也有那 9 个分类），
 * 所以本页不需要服务端额外推"在不在"：
 * <ul>
 *   <li>未注册 ⇒ 分类不在注册表里（模组写了一种类游戏没认的统计）；</li>
 *   <li>无译名 ⇒ 分类在、但语言表没这个键的译文（离线实测：取不到时 {@code getString()} 原样返回键）。</li>
 * </ul>
 *
 * <h2>三、数字不许被"美化"</h2>
 * 数据层用 {@code JsonPrimitive(long)}，没动过的键写回去仍是原来那串数字 ⇒
 * "除目标键外逐字节相同"这条判据才立得住（见 {@link #preview}）。
 *
 * <h2>四、本类<b>不</b>判窗口</h2>
 * 同一条红线：真正落盘那一步紧贴 {@link ShanhaiSaveEditFlow#tryApply(String)}，见 {@link #writeGated}。
 */
public final class ShanhaiSaveEditStatsUi {

    /** 闸门上记的"谁在请求"。 */
    public static final String WHO_SET = "panel:stats-set";
    public static final String WHO_CLEAR = "panel:stats-clear";

    /** 未注册的记号。 */
    public static final String MARK_UNREGISTERED = "gt_shanhai.saveedit.text.837a1485e0dc99c8";
    /** 注册了但当前语言没有这条译文。 */
    public static final String MARK_NO_NAME = "gt_shanhai.saveedit.text.4942f72afc63b551";
    /** 键本身是空的。 */
    public static final String MARK_NO_KEY = "gt_shanhai.saveedit.text.0ce7da6b9131b6cb";

    /** 一次「列出」最多从盘上取多少条（理由同第 ⑤ 页的 {@code LIST_CAP}）。 */
    public static final int LIST_CAP = 4000;

    private ShanhaiSaveEditStatsUi() {}

    // ================================================================== 名字（🔴 中文在前）

    /**
     * "把统计键翻成当前语言下的名字"这个能力（由 UI 层注入；离线判据喂假的，面板用 {@link #STAT_NAMER}）。
     */
    public interface Namer {
        /** 这个<b>分类</b>在统计类型注册表里吗（{@code false} ⇒ 模组被删 / 写错了）。 */
        boolean registered(String category);

        /** 当前语言下的名字；<b>取不到 ⇒ {@code null}</b>（不许返回空串、也不许返回翻译键）。 */
        String statName(String category, String key);
    }

    /** 统计类型注册表里有没有这个分类（公共注册表：客户端/服务端/离线判据都能查）。 */
    public static boolean categoryRegistered(String category) {
        final net.minecraft.resources.ResourceLocation rl = parseId(category);
        if (rl == null) {
            return false;
        }
        try {
            return net.minecraft.core.registries.BuiltInRegistries.STAT_TYPE.containsKey(rl);
        } catch (Throwable t) {
            return false;           // 注册表没起来（离线探针）/ 其它异常：一律当"没有"，不崩
        }
    }

    /**
     * 真·语言表查询：{@code minecraft:custom + minecraft:jump} ⇒ 「跳跃次数」。
     *
     * <p>键的口径是原版的 {@code stat.<namespace>.<path>}（{@code StatsScreen} 用的就是这一条）。
     * 非 {@code minecraft:custom} 的分类（mined / crafted / killed …）在原版里显示的是
     * <b>那个方块/物品/实体自己的名字</b>，所以再退两步查 {@code block./item./entity.} ——
     * 这是"名字对不对"的问题，不是"匹配口径"的问题（本页不做任何文本匹配）。
     */
    public static String statNameOrNull(String category, String key) {
        final String cat = category == null ? "" : category.trim();
        final String k = key == null ? "" : key.trim();
        if (cat.isEmpty() || k.isEmpty()) {
            return null;
        }
        if (!categoryRegistered(cat)) {
            return null;
        }
        final String dotted = dotName(k);
        if (dotted == null) {
            return null;
        }
        String s = langOrNull("stat." + dotted);
        if (s != null) {
            return s;
        }
        if (!ShanhaiStatsEdit.DEFAULT_CATEGORY.equals(cat)) {
            for (String prefix : new String[] {"block.", "item.", "entity."}) {
                s = langOrNull(prefix + dotted);
                if (s != null) {
                    return s;
                }
            }
        }
        return null;
    }

    /** 面板用的真实现。 */
    public static final Namer STAT_NAMER = new Namer() {
        @Override
        public boolean registered(String category) {
            return categoryRegistered(category);
        }

        @Override
        public String statName(String category, String key) {
            return statNameOrNull(category, key);
        }
    };

    /** {@code minecraft:jump} ⇒ {@code minecraft.jump}（原版 lang 键那段就是冒号换成点）。 */
    private static String dotName(String id) {
        final net.minecraft.resources.ResourceLocation rl = parseId(id);
        if (rl == null) {
            return null;
        }
        return rl.getNamespace() + "." + rl.getPath().replace('/', '.');
    }

    private static net.minecraft.resources.ResourceLocation parseId(String id) {
        if (id == null) {
            return null;
        }
        final String s = id.trim();
        if (s.isEmpty()) {
            return null;
        }
        try {
            return net.minecraft.resources.ResourceLocation.tryParse(s);
        } catch (Throwable t) {
            return null;
        }
    }

    private static String langOrNull(String key) {
        try {
            final String s = net.minecraft.network.chat.Component.translatable(key).getString();
            if (s == null || s.isEmpty() || s.equals(key)) {
                return null;
            }
            return s;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * <b>中文在前的名字</b>：{@code 跳跃次数（minecraft:jump）}。
     *
     * <p>回退（<b>绝不空白、绝不崩</b>）：分类未注册 ⇒ {@code id〔未注册〕}；
     * 有分类但语言表没译文 ⇒ {@code id〔无译名〕}。
     */
    public static String statLabel(String category, String key, Namer namer) {
        final String k = key == null ? "" : key.trim();
        if (k.isEmpty()) {
            return ShanhaiSaveEditText.text(MARK_NO_KEY);
        }
        if (namer == null || !namer.registered(category)) {
            return k + ShanhaiSaveEditText.text(MARK_UNREGISTERED);
        }
        final String name = namer.statName(category, k);
        if (name == null || name.isEmpty()) {
            return k + ShanhaiSaveEditText.text(MARK_NO_NAME);
        }
        return name + "（" + k + "）";
    }

    /** 只要名字那半（行按钮用，省宽度）；回退形态与 {@link #statLabel} 逐字相同。 */
    public static String statShort(String category, String key, Namer namer) {
        final String k = key == null ? "" : key.trim();
        if (k.isEmpty()) {
            return ShanhaiSaveEditText.text(MARK_NO_KEY);
        }
        if (namer == null || !namer.registered(category)) {
            return k + ShanhaiSaveEditText.text(MARK_UNREGISTERED);
        }
        final String name = namer.statName(category, k);
        if (name == null || name.isEmpty()) {
            return k + ShanhaiSaveEditText.text(MARK_NO_NAME);
        }
        return name;
    }

    // ================================================================== 行 / 概况

    /** 一行可点的统计：{@code 1. 跳跃次数 = 3490}（🔴 名字在前，id 进 tooltip）。 */
    public static String rowText(int index, String category, String key, long value, Namer namer) {
        final String name = statShort(category, key, namer);
        if (name.isEmpty()) {
            return "";
        }
        return "§8" + (index + 1) + ". §f" + name + " §7= §f" + value;
    }

    /** 同 {@link #rowText(int, String, String, long, Namer)}，直接吃数据层的一行。 */
    public static String rowText(int index, ShanhaiStatsEdit.Entry e, Namer namer) {
        if (e == null) {
            return "";
        }
        return rowText(index, e.category(), e.key(), e.value(), namer);
    }

    /** 「列出」之后"概况"那一行。 */
    public static String summaryLine(ShanhaiStatsEdit.Listing l, String filter) {
        if (l == null) {
            return ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.c87aec70ad10293d");
        }
        if (!l.ok()) {
            return ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.29232c9088bfed04") + (l.error() == null ? ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.f3610ee24ce44af0") : l.error());
        }
        final StringBuilder sb = new StringBuilder();
        sb.append(ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.2253daecd4a7438d")).append(l.count()).append(ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.85ac065f893ae213"))
                .append(l.category() == null || l.category().isEmpty() ? ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.69051217f49c19b7") : l.category());
        if (l.byCategory() != null && !l.byCategory().isEmpty()) {
            sb.append(" §8[").append(l.byCategory().size()).append(ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.b6ccda300a497c62"));
        }
        if (l.dataVersion() != null) {
            sb.append(" §8DataVersion ").append(l.dataVersion());
        }
        if (filter != null && !filter.isEmpty()) {
            sb.append(ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.852f41d49ff09d09")).append(filter).append("」");
        }
        return sb.toString();
    }

    /** 页码那一行（{@code 第 2/2 页}）。 */
    public static String pageText(int page, int matched) {
        return ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.9552fe3bd366b214") + (ShanhaiSaveEditLayout.clampPage(page, matched, ShanhaiSaveEditLayout.LP_ROWS) + 1)
                + "/" + ShanhaiSaveEditLayout.pageCount(matched, ShanhaiSaveEditLayout.LP_ROWS) + ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.85ca81cf6abd88f1");
    }

    /** 页码旁边那个计数（{@code 共 16 条 · 本页 8-14}）。 */
    public static String pageCountText(int page, int matched) {
        final int rows = ShanhaiSaveEditLayout.LP_ROWS;
        if (matched <= 0) {
            return ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.90f0ac8dc005be78");
        }
        final int p = ShanhaiSaveEditLayout.clampPage(page, matched, rows);
        return ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.48f44fb9bec3b16c") + matched + ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.b2ed6ba05d31594f") + (p * rows + 1) + "-" + Math.min(matched, (p + 1) * rows);
    }

    /**
     * 第 ⑥ 页说明那一行（客户端自己知道的字；🔴 必须放得进 386 px）。
     */
    public static String hintLine() {
        return ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.4c6f2aeae3ccaea5");
    }

    /** 第 ⑥ 页底部那行提示（同样必须放得进 392 px）。 */
    public static String footerLine() {
        return ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.cb79356ecf9034f2");
    }

    /** 从"数据层已经过滤好的列表"里切出第 {@code page} 页（不读盘、不改列表）。 */
    public static List<ShanhaiStatsEdit.Entry> pageRows(List<ShanhaiStatsEdit.Entry> all, int page) {
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

    /** 一条统计的详情读数。 */
    public record Detail(String category, String key, long value, boolean present, int fileEntries) {

        /** 归一化后的分类（永不 null）。 */
        public String cat() {
            final String c = this.category == null ? "" : this.category.trim();
            return c.isEmpty() ? ShanhaiStatsEdit.DEFAULT_CATEGORY : c;
        }

        /** 归一化后的键（永不 null）。 */
        public String statKey() {
            return this.key == null ? "" : this.key.trim();
        }
    }

    /** 把"列表里的一行"变成详情（点一行时调它）。 */
    public static Detail describe(ShanhaiStatsEdit.Entry e, int fileEntries) {
        if (e == null) {
            return new Detail(ShanhaiStatsEdit.DEFAULT_CATEGORY, "", 0L, false, fileEntries);
        }
        return new Detail(e.category(), e.key(), e.value(), true, fileEntries);
    }

    // ================================================================== 预演（不写一个字节）

    /** 一次"如果点写入会发生什么"的读数。 */
    public record Preview(boolean ok, String error, List<String> changed, List<String> notes) {

        public static Preview fail(String why) {
            return new Preview(false, why == null ? ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.7c6faabeea8a57c4") : why, List.of(), List.of());
        }

        public String changedLine() {
            if (!this.ok) {
                return "§c" + this.error;
            }
            return ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.a7f52a1e4f56df8d") + (this.changed.isEmpty() ? ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.78361548b4335e14") : String.join("／", this.changed));
        }
    }

    /**
     * 🔴 <b>预演</b>：只读地解一遍统计文件，把改动应用到一份<b>内存副本</b>上。
     *
     * <p>与数据层 {@code applyToFile} 的写前校验同源（同一批公开函数：
     * {@code set/clear} → {@code verifySet/verifyClear} → {@code diffJson/allDiffsUnder}），
     * 区别只有一处：<b>这里不调 {@code writeAtomic}</b>。
     */
    public static Preview preview(Path statsFile, ShanhaiStatsEdit.Request req) {
        if (statsFile == null || !Files.isRegularFile(statsFile)) {
            return Preview.fail(ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.92f7ef8c72c984ae")
                    + (statsFile == null ? "-" : statsFile.getFileName()));
        }
        if (req == null || req.isEmpty()) {
            return Preview.fail(ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.956a518e3170be7f"));
        }
        try {
            req.validate();
        } catch (IllegalArgumentException iae) {
            return Preview.fail(iae.getMessage());
        }
        final JsonObject before;
        try {
            before = ShanhaiJsonText.readObject(statsFile);
        } catch (Throwable t) {
            return Preview.fail(ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.241047958f59f8e3") + t);
        }
        final JsonObject after = before.deepCopy();
        if (req.isSet()) {
            ShanhaiStatsEdit.set(after, req.cat(), req.statKey(), req.value());
        } else {
            ShanhaiStatsEdit.clear(after, req.cat(), req.statKey());
        }
        final ShanhaiStatsEdit.Verify v = req.isSet()
                ? ShanhaiStatsEdit.verifySet(after, req.cat(), req.statKey(), req.value())
                : ShanhaiStatsEdit.verifyClear(after, req.cat(), req.statKey());
        if (!v.ok()) {
            return Preview.fail(ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.60d59f49b536ac3d") + v.line());
        }
        final String path = ShanhaiStatsEdit.KEY_STATS + "." + req.cat() + "." + req.statKey();
        final List<String> intended = ShanhaiJsonText.diffJson(before, after);
        if (!ShanhaiJsonText.allDiffsUnder(intended, path)) {
            return Preview.fail(ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.058bf48d5f03d9d1")
                    + ShanhaiJsonText.firstDiffOutside(intended, path) + " intended=" + intended);
        }
        if (intended.isEmpty()) {
            return Preview.fail(ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.00e11a67c1c99e16"));
        }
        return new Preview(true, "", List.copyOf(intended), List.copyOf(v.changes()));
    }

    // ================================================================== 带闸的写

    /** 一次"带闸"尝试的结果：{@code permitted=false} ⇒ 一个字节都没写。 */
    public record Apply(boolean permitted, String refusal, ShanhaiStatsEdit.Result write) {

        public boolean ok() {
            return this.permitted && this.write != null && this.write.ok();
        }

        public String line() {
            if (!this.permitted) {
                return "§e" + (this.refusal == null ? ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.7c6faabeea8a57c4") : this.refusal);
            }
            return this.write == null ? ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.4b19acc6e22b72da") : "§f" + this.write.line();
        }
    }

    /**
     * 设值 / 清零一条统计（<b>带闸</b>：{@code flow} 不在 STOPPED 就一个字节都不写）。
     *
     * <p>落盘仍然走数据层的 {@link ShanhaiStatsEdit#apply}（<b>没有另开写盘路径</b>）；
     * 游戏里的常态是"窗口没开 ⇒ 拒绝 ⇒ 面板暂存进 holder，等「确认修改并重载」"，
     * 那条路最终由 {@link ShanhaiSaveEditWindow} 的 ⑦ 段调同一个数据层入口。
     */
    public static Apply writeGated(Path worldDir, String playerUuid, ShanhaiStatsEdit.Request req,
                                   Path backupRoot, String stamp, ShanhaiSaveEditFlow flow) {
        if (worldDir == null || playerUuid == null || playerUuid.isEmpty()) {
            return new Apply(false, ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.5453468ae0bec9ce"), null);
        }
        if (req == null || req.isEmpty()) {
            return new Apply(false, ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.2c2359fe201a6709"), null);
        }
        final ShanhaiSaveEditFlow.ApplyAttempt attempt =
                flow == null ? null : flow.tryApply(req.isSet() ? WHO_SET : WHO_CLEAR);
        if (attempt == null || !attempt.permitted()) {
            return new Apply(false, windowClosed(flow), null);
        }
        final ShanhaiStatsEdit.Result o =
                ShanhaiStatsEdit.apply(worldDir, playerUuid, req, backupRoot, stamp);
        if (o != null && o.ok()) {
            flow.markApplied();
        }
        return new Apply(true, "", o);
    }

    /** 窗口没开时那句话（与第 ④⑤ 页同一个说法）。 */
    public static String windowClosed(ShanhaiSaveEditFlow flow) {
        return ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.47693be4e87af1d4") + (flow == null ? ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.1ce51dbb319c0d8c") : ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.eb2385a0625b543e") + flow.phase() + ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.137fe8c8500b7b78"))
                + ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.07746b7ed8cd0832");
    }

    // ================================================================== 覆盖层的状态机

    /**
     * 覆盖层的<b>状态机 + 读数缓冲</b>（两侧各一份；只有服务端那份会被喂进 {@link Detail}）。
     *
     * <pre>
     *   关闭 ──open()──▶ 打开（没有任何读数）──openDetail(d)──▶ 打开（有读数）
     *     ▲                                                        │
     *     └──────────────────────── close() ───────────────────────┘
     *   打开时按「写入」：<b>一次点击就是一次真做</b> —— 同一次点击里先算预演
     *   （拿它当"将改"读数与拒绝理由），预演过了才真写；窗口没开就交给面板暂存，
     *   等「确认修改并重载」。
     *   🔴 <b>没有"再点一次"这一步</b>（用户 2026-11-15 的原话：「除非我指定需要二次确认，否则都不加」）：
     *   原来那个 {@code arm} 字段已删除；失败/被拒只影响这一次点击（下一次还是从预演开始）。
     *   「动作」按钮在 设值 ↔ 清零 之间切（两侧各持一份，点一下两侧都翻）。
     * </pre>
     */
    public static final class Model {

        private boolean opened;
        private Detail detail;
        /** 标题用的"原始料"：分类 + 键（🔴 名字由客户端现拼）。 */
        private String titleCat = "";
        private String titleKey = "";
        private String newValue = "";
        private String action = ShanhaiStatsEdit.ACTION_SET;
        private String feedback = "";
        private final List<String> changed = new ArrayList<>();
        private final List<String> notes = new ArrayList<>();

        // ---------------------------------------------------------------- 开 / 关

        public void open() {
            reset();
            this.opened = true;
        }

        public void close() {
            reset();
            this.opened = false;
        }

        private void reset() {
            this.detail = null;
            this.titleCat = "";
            this.titleKey = "";
            this.newValue = "";
            this.feedback = "";
            this.changed.clear();
            this.notes.clear();
        }

        /** 服务端把这一条统计的读数灌进来（点一行之后）。 */
        public void openDetail(Detail d) {
            open();
            this.detail = d;
            this.titleCat = d == null ? "" : d.cat();
            this.titleKey = d == null ? "" : d.statKey();
        }

        /** 客户端收标题料（覆盖层的 {@code UPDATE_TITLE}）：分类 + 键，原样存着，画的时候再拼。 */
        public void setTitlePayload(String category, String key) {
            this.titleCat = category == null ? "" : category;
            this.titleKey = key == null ? "" : key;
        }

        public String titleCat() {
            return this.titleCat;
        }

        public String titleKeyValue() {
            return this.titleKey;
        }

        public boolean opened() {
            return this.opened;
        }

        public Detail detail() {
            return this.detail;
        }

        /** 输入框里的新值（原样；解析成整数由面板/数据层做）。 */
        public void setNewValue(String text) {
            this.newValue = text == null ? "" : text.trim();
        }

        public String newValue() {
            return this.newValue;
        }

        /** 动作：{@link ShanhaiStatsEdit#ACTION_SET} / {@link ShanhaiStatsEdit#ACTION_CLEAR}。 */
        public String action() {
            return this.action;
        }

        public void setAction(String value) {
            this.action = ShanhaiStatsEdit.ACTION_CLEAR.equals(value)
                    ? ShanhaiStatsEdit.ACTION_CLEAR : ShanhaiStatsEdit.ACTION_SET;
        }

        /** 切"设值 ↔ 清零"（返回切完之后的动作）。 */
        public String cycleAction() {
            setAction(ShanhaiStatsEdit.ACTION_CLEAR.equals(this.action)
                    ? ShanhaiStatsEdit.ACTION_SET : ShanhaiStatsEdit.ACTION_CLEAR);
            return this.action;
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

        // ---------------------------------------------------------------- 覆盖层上的字

        /**
         * 标题那一行：<b>中文在前</b> —— {@code 跳跃次数（minecraft:jump）}。
         *
         * <p>🔴 必须在<b>客户端</b>现拼（服务端没有语言表）；所以标题那个 {@code LabelWidget}
         * 是 {@code setClientSideWidget()} 的，只收 {@link #setTitlePayload} 推来的原料。
         * 永远不返回 {@code null}。
         */
        public String title() {
            if (this.titleKey.isEmpty()) {
                return ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.55630dd7399b7150");
            }
            return "§f" + statLabel(this.titleCat, this.titleKey, STAT_NAMER);
        }

        /** 动作按钮上的字。 */
        public String actionText() {
            return ShanhaiStatsEdit.ACTION_CLEAR.equals(this.action) ? com.dishanhai.gt_shanhai.common.saveedit.ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.ba27230528eee3fa") : com.dishanhai.gt_shanhai.common.saveedit.ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.dd46964db8652080");
        }

        /**
         * 第 {@code index} 行读数（0~7）。
         *
         * <p>行序：分类+键+当前值 / 动作 / "设值"的预期 / "清零"的预期 / 反馈 / 将改 / 文件 / 备注。
         */
        public String note(int index) {
            final Detail d = this.detail;
            switch (index) {
                case 0:
                    if (d == null) {
                        return ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.771028ef3d299fc2");
                    }
                    return ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.b2a0cf93ffb80681") + d.cat() + ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.9b36c03fb7b9c7c9") + d.statKey()
                            + ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.e19d5bab3c2982cd") + d.value() + (d.present() ? "" : ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.1811c5bd939a06af"));
                case 1:
                    return ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.bea75d538bc5661b") + (ShanhaiStatsEdit.ACTION_CLEAR.equals(this.action)
                            ? ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.140f06c7e32cf3d2") : ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.5eb30f52bd2d7917"));
                case 2:
                    return ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.f7b37cad102e916b") + (d == null ? "?" : d.cat()) + "."
                            + (d == null ? "?" : d.statKey()) + ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.5cd738396a25bf8b");
                case 3:
                    return ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.46de5dc2553fbe11");
                case 4:
                    return this.feedback.isEmpty() ? "" : this.feedback;
                case 5:
                    return this.changed.isEmpty() ? "" : ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.1eb215436a9ac853") + String.join("／", this.changed);
                case 6:
                    return ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.7644948835482277") + (d == null ? 0 : d.fileEntries()) + ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.af9bdac902a1ce93");
                case 7:
                    return ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.df0689fcecb62efa");
                default:
                    return "";
            }
        }
    }
}
