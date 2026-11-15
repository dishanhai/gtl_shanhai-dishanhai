package com.shanhai.common.saveedit;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.nio.file.Files;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;

/**
 * 「存档直改 · 第四期」：{@code advancements/<uuid>.json}（成就进度，<b>纯 JSON</b>）。
 *
 * <h2>一、格式（用本机真实存档反解 + 原版字节码核对，两处一致）</h2>
 * <pre>
 * {
 *   "minecraft:story/mine_diamond": {
 *     "criteria": { "mine_diamond": "2026-09-10 19:18:44 +0800" },
 *     "done": true
 *   },
 *   "minecraft:adventure/adventuring_time": { "criteria": {…}, "done": false },
 *   "DataVersion": 3465                       ← 🔴 顶层混着一个非成就键！
 * }
 * </pre>
 * 两条<b>从真实样本里读出来的</b>事实（不读会踩）：
 * <ol>
 *   <li>顶层除了成就 id 还有 {@code DataVersion} —— "列出"时必须把它剔掉，
 *       否则面板上会出现一个叫 {@code DataVersion} 的"成就"；</li>
 *   <li>{@code done:false} 也会写出来（不是"只有完成了才写 done"）。</li>
 * </ol>
 *
 * <h2>二、🔴 "给成就"必须三样都写对（否则游戏里显示未完成）</h2>
 * 原版 {@code AdvancementProgress$Serializer}（字节码取证）：
 * <ul>
 *   <li>{@code serialize}：先写 {@code criteria}（只写<b>已达成</b>的那几条，值是
 *       {@code "yyyy-MM-dd HH:mm:ss Z"} 的时间串），再写 {@code done = isDone()}；</li>
 *   <li>{@code deserialize}：<b>只读 {@code criteria}</b> —— {@code done} 根本没被读回来，
 *       游戏里的"完成/未完成"是拿 criteria 与成就定义的 requirements <b>现算</b>的。</li>
 * </ul>
 * ⇒ 只写 {@code done:true} 而不写 {@code criteria}：文件看着像完成了，游戏里是<b>未完成</b>。
 * 判据 c11 的负对照就是把这一种写法喂进校验器，它必须红（{@link #verifyGrant}）。
 * <p>所以 {@link #grant} 一次做三件事：写 {@code criteria} 里的<b>每一条</b>条件（时间串）、
 * 写 {@code done:true}、保留顶层其它键（含 {@code DataVersion}）。
 * 把<b>所有</b> criteria 都置达成 ⇒ 任何 requirements 分组都被满足（不需要自己解 requirements）。
 *
 * <h2>三、撤成就 = 整条删</h2>
 * 原版读的时候 {@code criteria} 一条都没有 ⇒ 进度为空；而文件里留着一条空壳反而会被
 * 原版在下次保存时丢掉。所以撤就是 {@code root.remove(id)}（判据 c11 断言"整条消失"）。
 *
 * <h2>四、本类<b>不</b>判窗口</h2>
 * 同一条红线：调用方必须先过 {@link ShanhaiSaveEditFlow#tryApply(String)}。
 */
public final class ShanhaiAdvancementEdit {

    /** 日志前缀。 */
    public static final String PREFIX = ShanhaiSaveEditFlow.PREFIX;

    /** 动作：给。 */
    public static final String ACTION_GRANT = "grant";
    /** 动作：撤。 */
    public static final String ACTION_REVOKE = "revoke";

    /** 进度对象里的条件表键名（真实样本 + 字节码双向证实）。 */
    public static final String KEY_CRITERIA = "criteria";
    /** 进度对象里的"完成"键名（原版只写不读，见类注释 §二）。 */
    public static final String KEY_DONE = "done";
    /** 顶层的非成就键（真实样本里有它）。 */
    public static final String KEY_DATA_VERSION = "DataVersion";

    /** 原版 {@code CriterionProgress.DATE_FORMAT} 的形状（字节码里的 ldc 字面串）。 */
    public static final String DATE_PATTERN = "yyyy-MM-dd HH:mm:ss Z";

    /** 目录名。 */
    public static final String DIR = "advancements";

    private ShanhaiAdvancementEdit() {}

    // ================================================================== 路径

    /** {@code <存档>/advancements/<uuid>.json}。 */
    public static Path fileOf(Path worldDir, String playerUuid) {
        if (worldDir == null || playerUuid == null || playerUuid.isEmpty()) {
            return null;
        }
        return worldDir.resolve(DIR).resolve(playerUuid + ".json");
    }

    // ================================================================== 请求

    /**
     * 一次成就改动。
     *
     * @param action         {@link #ACTION_GRANT} / {@link #ACTION_REVOKE}
     * @param advancementId  成就 id（如 {@code minecraft:story/mine_diamond}）
     * @param criteria       该成就定义里的<b>全部</b>条件名（由游戏侧从
     *                       {@code Advancement#getCriteria()} 取；离线判据直接给）
     */
    public record Request(String action, String advancementId, List<String> criteria) {

        /** 要不要写。 */
        public boolean isEmpty() {
            return this.advancementId == null || this.advancementId.trim().isEmpty();
        }

        /** 是不是"给"。 */
        public boolean isGrant() {
            return ACTION_GRANT.equals(this.action);
        }

        /** 归一化后的成就 id（去空白）。 */
        public String id() {
            return this.advancementId == null ? "" : this.advancementId.trim();
        }

        /** 一行读数。 */
        public String line() {
            return "action=" + this.action + " id=" + id()
                    + " criteria=" + (this.criteria == null ? 0 : this.criteria.size())
                    + (this.criteria == null || this.criteria.isEmpty() ? "" : this.criteria.toString());
        }

        /**
         * 校验（面板暂存前 + 落盘前各一次）。
         *
         * @throws IllegalArgumentException 中文原因（面板直接回显）
         */
        public void validate() {
            if (isEmpty()) {
                throw new IllegalArgumentException("成就 id 是空的");
            }
            if (!ACTION_GRANT.equals(this.action) && !ACTION_REVOKE.equals(this.action)) {
                throw new IllegalArgumentException("动作只能是 " + ACTION_GRANT + " 或 " + ACTION_REVOKE
                        + "，现在是「" + this.action + "」");
            }
            if (!isValidId(id())) {
                throw new IllegalArgumentException("成就 id 要写成 namespace:path（例如 "
                        + "minecraft:story/mine_diamond），现在是「" + id() + "」");
            }
            if (KEY_DATA_VERSION.equals(id())) {
                throw new IllegalArgumentException("DataVersion 不是一个成就（它是文件里的版本号）");
            }
            if (isGrant()) {
                if (this.criteria == null || this.criteria.isEmpty()) {
                    throw new IllegalArgumentException("给成就必须知道它的全部条件名（criteria）；"
                            + "游戏里查不到这条成就的定义就会这样 —— 请确认 id 拼写");
                }
                for (String c : this.criteria) {
                    if (c == null || c.trim().isEmpty() || c.indexOf(' ') >= 0) {
                        throw new IllegalArgumentException("条件名不合法：「" + c + "」");
                    }
                }
            }
        }
    }

    // ================================================================== 列出

    /** 一条成就的读数。 */
    public record Entry(String id, boolean present, boolean done, int criteria) {
        public String line() {
            return (present ? (done ? "✔ " : "… ") : "✘ ") + id
                    + (present ? "  criteria=" + criteria : "  （文件里没有）");
        }
    }

    /** 一次"列出"的读数。 */
    public record Listing(boolean ok, String error, Path path, int present, int unlocked,
                          int known, List<Entry> entries, String dataVersion, int bytes) {
        public String line() {
            return "ok=" + this.ok
                    + (this.error == null || this.error.isEmpty() ? "" : " error=" + this.error)
                    + " file=" + (this.path == null ? "-" : this.path.getFileName())
                    + " bytes=" + this.bytes
                    + " present=" + this.present + " unlocked=" + this.unlocked
                    + (this.known > 0 ? " known=" + this.known : "")
                    + " data_version=" + (this.dataVersion == null ? "-" : this.dataVersion);
        }

        /** 面板明细。 */
        public List<String> lines(int max) {
            final List<String> out = new ArrayList<>();
            if (!this.ok) {
                out.add("§c" + (this.error == null ? "列不出来" : this.error));
                return out;
            }
            out.add("§7文件里 " + this.present + " 条 · 已完成 " + this.unlocked
                    + (this.known > 0 ? " · 游戏里共 " + this.known + " 条 ⇒ 未解锁 "
                    + Math.max(0, this.known - this.unlocked) : "")
                    + " · DataVersion " + (this.dataVersion == null ? "-" : this.dataVersion));
            int i = 0;
            for (Entry e : this.entries) {
                if (out.size() >= Math.max(1, max)) {
                    out.add("§8…还有 " + Math.max(0, this.entries.size() - i) + " 条没显示");
                    break;
                }
                out.add(e.line());
                i++;
            }
            return out;
        }
    }

    /**
     * 列出某个玩家的成就文件。
     *
     * @param knownIds 游戏里<b>全部</b>成就 id（可为空 = 只看文件里有什么）；给了才能报"未解锁几条"
     */
    public static Listing listFile(Path file, Collection<String> knownIds, int maxEntries) {
        if (file == null || !Files.isRegularFile(file)) {
            return new Listing(false, "还没有成就文件（这个玩家还没进过这个世界）："
                    + (file == null ? "-" : file.getFileName()), file, 0, 0, 0, List.of(), null, 0);
        }
        final JsonObject root;
        final int bytes;
        try {
            bytes = (int) Files.size(file);
            root = ShanhaiJsonText.readObject(file);
        } catch (Throwable t) {
            return new Listing(false, "读成就文件失败：" + t, file, 0, 0, 0, List.of(), null, 0);
        }
        final List<Entry> entries = new ArrayList<>();
        int present = 0;
        int unlocked = 0;
        final TreeSet<String> ids = new TreeSet<>();
        for (Map.Entry<String, JsonElement> en : root.entrySet()) {
            if (KEY_DATA_VERSION.equals(en.getKey())) {
                continue;                       // 🔴 顶层混着的版本号不是成就
            }
            ids.add(en.getKey());
        }
        for (String id : ids) {
            final JsonObject entry = ShanhaiJsonText.objectOf(root, id);
            final boolean done = entry != null && entry.has(KEY_DONE)
                    && entry.get(KEY_DONE).isJsonPrimitive() && entry.get(KEY_DONE).getAsBoolean();
            final int criteria = entry == null ? 0
                    : (ShanhaiJsonText.objectOf(entry, KEY_CRITERIA) == null ? 0
                    : ShanhaiJsonText.objectOf(entry, KEY_CRITERIA).size());
            present++;
            if (done) {
                unlocked++;
            }
            if (entries.size() < Math.max(0, maxEntries)) {
                entries.add(new Entry(id, true, done, criteria));
            }
        }
        if (knownIds != null && !knownIds.isEmpty()) {
            for (String id : new TreeSet<>(knownIds)) {
                if (ids.contains(id)) {
                    continue;
                }
                if (entries.size() < Math.max(0, maxEntries)) {
                    entries.add(new Entry(id, false, false, 0));
                }
            }
        }
        final String dv = root.has(KEY_DATA_VERSION)
                ? root.get(KEY_DATA_VERSION).getAsString() : null;
        return new Listing(true, "", file, present, unlocked,
                knownIds == null ? 0 : knownIds.size(), entries, dv, bytes);
    }

    // ================================================================== 写

    /** 一次改动结果。 */
    public record Result(boolean ok, String error, List<String> changed, List<String> notes,
                         ShanhaiSaveEditStorage.WriteResult write, String verifyLine) {
        public String line() {
            return "ok=" + this.ok
                    + (this.error == null || this.error.isEmpty() ? "" : " error=" + this.error)
                    + " changed=" + this.changed
                    + (this.notes == null || this.notes.isEmpty() ? "" : " notes=" + this.notes)
                    + " verify=[" + (this.verifyLine == null ? "" : this.verifyLine) + "]"
                    + (this.write == null ? "" : " | " + this.write.line());
        }
    }

    /** 按世界目录 + 玩家 uuid 写（会写盘）。 */
    public static Result apply(Path worldDir, String playerUuid, Request req, Path backupRoot, String stamp) {
        final Path file = fileOf(worldDir, playerUuid);
        if (file == null) {
            return fail("算不出成就文件路径（worldDir/uuid 缺失）");
        }
        if (!Files.isRegularFile(file)) {
            return fail("还没有成就文件（这个玩家还没进过这个世界）：" + file);
        }
        return applyToFile(file, req, backupRoot, stamp);
    }

    /**
     * 直接对一个 {@code advancements/<uuid>.json} 做改动（离线判据走这条）。
     *
     * <p>流程：读盘 → 解 JSON（before）→ 改（after）→ <b>写盘前先在内存里校验</b>
     * → {@link ShanhaiSaveEditStorage#writeAtomic} → 回读 → 再做一次校验 + JSON 等价。
     */
    public static Result applyToFile(Path file, Request req, Path backupRoot, String stamp) {
        final List<String> notes = new ArrayList<>();
        final List<String> changed = new ArrayList<>();
        if (file == null || req == null) {
            return fail("null_arg");
        }
        if (req.isEmpty()) {
            return fail("empty_request（成就 id 是空的）");
        }
        try {
            req.validate();
        } catch (IllegalArgumentException iae) {
            return fail(iae.getMessage());
        }
        final JsonObject before;
        try {
            before = ShanhaiJsonText.readObject(file);
        } catch (Throwable t) {
            return fail("读成就文件失败：" + t);
        }
        final JsonObject after = before.deepCopy();
        final String timestamp = formatTimestamp(System.currentTimeMillis());
        if (req.isGrant()) {
            grant(after, req.id(), req.criteria(), timestamp);
        } else {
            revoke(after, req.id());
        }
        // 写盘前先在内存里校验（不通过 ⇒ 一个字节都不写）
        final Verify pre = req.isGrant()
                ? verifyGrant(after, req.id(), req.criteria())
                : verifyRevoke(after, req.id());
        if (!pre.ok()) {
            return fail("改完的进度没通过校验（未写盘）：" + pre.line());
        }
        // JSON 等价：除目标成就这一条以外，逐字段相同
        final List<String> intended = ShanhaiJsonText.diffJson(before, after);
        if (!ShanhaiJsonText.allDiffsUnder(intended, req.id())) {
            return fail("算出来的差异超出了目标成就（未写盘）："
                    + ShanhaiJsonText.firstDiffOutside(intended, req.id()) + " intended=" + intended);
        }
        if (intended.isEmpty()) {
            return fail("no_effective_change（这条成就本来就是这个状态）");
        }
        changed.addAll(intended);
        final byte[] content = ShanhaiJsonText.utf8(ShanhaiJsonText.pretty(after));
        final ShanhaiSaveEditStorage.WriteResult write =
                ShanhaiSaveEditStorage.writeAtomic(file, content, backupRoot, stamp);
        if (!write.ok()) {
            return new Result(false, write.error(), List.of(), notes, write, "");
        }
        // 回读校验
        try {
            final JsonObject disk = ShanhaiJsonText.readObject(file);
            final Verify post = req.isGrant()
                    ? verifyGrant(disk, req.id(), req.criteria())
                    : verifyRevoke(disk, req.id());
            if (!post.ok()) {
                restore(write, file);
                return fail("回读校验没过 ⇒ 已用备份盖回：" + post.line());
            }
            final List<String> observed = ShanhaiJsonText.diffJson(before, disk);
            if (!observed.equals(intended)) {
                restore(write, file);
                return fail("回读不一致：intended=" + intended + " observed=" + observed + " ⇒ 已用备份盖回");
            }
            notes.add("除「" + req.id() + "」外逐字段相同（差异 " + observed.size() + " 条都在它下面）");
            notes.add("文件 " + content.length + " 字节（缩进写，与原版 PlayerAdvancements 同口径）");
            return new Result(true, "", changed, notes, write, pre.line());
        } catch (Throwable t) {
            restore(write, file);
            return fail("回读阶段抛了（已用备份盖回）：" + t);
        }
    }

    private static void restore(ShanhaiSaveEditStorage.WriteResult write, Path target) {
        ShanhaiSaveEditStorage.restore(write == null ? null : write.backupPath(), target);
    }

    // ================================================================== 改（纯内存）

    /**
     * 给一条成就：{@code criteria} 里每一条都置成"已达成 + 时间串"，并把 {@code done} 置真。
     *
     * <p>🔴 已有条件<b>不覆盖</b>（只补缺的那几条）：玩家真达成过的那几条必须留着原来的时间。
     */
    public static void grant(JsonObject root, String advancementId, List<String> criteria, String timestamp) {
        if (root == null || advancementId == null || advancementId.isEmpty()) {
            return;
        }
        final String ts = timestamp == null || timestamp.isEmpty()
                ? formatTimestamp(System.currentTimeMillis()) : timestamp;
        JsonObject entry = ShanhaiJsonText.objectOf(root, advancementId);
        if (entry == null) {
            entry = new JsonObject();
            root.add(advancementId, entry);
        }
        JsonObject criteriaObj = ShanhaiJsonText.objectOf(entry, KEY_CRITERIA);
        if (criteriaObj == null) {
            criteriaObj = new JsonObject();
            entry.add(KEY_CRITERIA, criteriaObj);
        }
        if (criteria != null) {
            for (String c : criteria) {
                if (c != null && !c.isEmpty() && !criteriaObj.has(c)) {
                    criteriaObj.addProperty(c, ts);
                }
            }
        }
        entry.addProperty(KEY_DONE, Boolean.TRUE);
    }

    /** 撤一条成就：整条删掉（原版读不到 criteria ⇒ 进度为空；下次保存也不会留下空壳）。 */
    public static void revoke(JsonObject root, String advancementId) {
        if (root == null || advancementId == null || advancementId.isEmpty()) {
            return;
        }
        root.remove(advancementId);
    }

    // ================================================================== 校验（判据的尺子）

    /** 一次校验的读数。 */
    public record Verify(boolean ok, List<String> problems, List<String> changes) {
        public String line() {
            return "ok=" + this.ok
                    + (this.changes.isEmpty() ? "" : " changes=" + this.changes)
                    + (this.problems.isEmpty() ? "" : " problems=" + this.problems);
        }
    }

    /**
     * 🔴 <b>判据 c11 的核心尺子</b>：给完之后
     * <ol>
     *   <li>这条成就<b>在</b>；</li>
     *   <li>有 {@code criteria} 对象（<b>没有就是"游戏里显示未完成"</b> —— 负对照正是喂这一种）；</li>
     *   <li>{@code criteria} 里每一条要求的条件都在，且值是<b>能被原版解析</b>的时间串；</li>
     *   <li>{@code done == true}；</li>
     *   <li>顶层的 {@code DataVersion} 没被碰掉（原来有就得还在）。</li>
     * </ol>
     */
    public static Verify verifyGrant(JsonObject after, String advancementId, List<String> criteria) {
        final List<String> problems = new ArrayList<>();
        final List<String> changes = new ArrayList<>();
        if (after == null) {
            return new Verify(false, List.of("root_null"), List.of());
        }
        final JsonObject entry = ShanhaiJsonText.objectOf(after, advancementId);
        if (entry == null) {
            return new Verify(false, List.of("这条成就不在文件里"), List.of());
        }
        final JsonObject criteriaObj = ShanhaiJsonText.objectOf(entry, KEY_CRITERIA);
        if (criteriaObj == null || criteriaObj.size() == 0) {
            problems.add("没有 criteria（游戏只会看 criteria 现算完成度 ⇒ 显示未完成）");
        } else {
            int good = 0;
            for (Map.Entry<String, JsonElement> en : criteriaObj.entrySet()) {
                if (!isTimestamp(en.getValue() == null ? null : en.getValue().getAsString())) {
                    problems.add("条件 " + en.getKey() + " 的时间串不是原版格式（"
                            + DATE_PATTERN + "）");
                } else {
                    good++;
                }
            }
            changes.add("criteria=" + good + "/" + criteriaObj.size() + " 条格式合法");
            if (criteria != null) {
                for (String c : criteria) {
                    if (c != null && !c.isEmpty() && !criteriaObj.has(c)) {
                        problems.add("缺条件：" + c);
                    }
                }
            }
        }
        if (!entry.has(KEY_DONE) || !entry.get(KEY_DONE).isJsonPrimitive()
                || !entry.get(KEY_DONE).getAsBoolean()) {
            problems.add("done 不是 true");
        } else {
            changes.add("done=true");
        }
        if (after.has(KEY_DATA_VERSION)) {
            changes.add("DataVersion=" + after.get(KEY_DATA_VERSION).getAsString() + " 保留");
        }
        return new Verify(problems.isEmpty(), problems, changes);
    }

    /** 撤完之后：这条成就整条不在了（且顶层其它键留着）。 */
    public static Verify verifyRevoke(JsonObject after, String advancementId) {
        final List<String> problems = new ArrayList<>();
        final List<String> changes = new ArrayList<>();
        if (after == null) {
            return new Verify(false, List.of("root_null"), List.of());
        }
        if (after.has(advancementId)) {
            problems.add("这条成就还在文件里");
        } else {
            changes.add("整条已删");
        }
        if (after.has(KEY_DATA_VERSION)) {
            changes.add("DataVersion=" + after.get(KEY_DATA_VERSION).getAsString() + " 保留");
        }
        return new Verify(problems.isEmpty(), problems, changes);
    }

    // ================================================================== 时间串

    /** 原版口径的时间串（{@code yyyy-MM-dd HH:mm:ss Z}，默认时区，与游戏里同一个）。 */
    public static String formatTimestamp(long epochMillis) {
        return new SimpleDateFormat(DATE_PATTERN, Locale.ROOT).format(new Date(epochMillis));
    }

    /** 能不能被原版 {@code CriterionProgress.fromJson} 解析（同一套 pattern + 宽松 flag）。 */
    public static boolean isTimestamp(String s) {
        if (s == null || s.trim().isEmpty()) {
            return false;
        }
        try {
            final SimpleDateFormat f = new SimpleDateFormat(DATE_PATTERN, Locale.ROOT);
            f.setLenient(true);
            return f.parse(s.trim()) != null;
        } catch (Throwable t) {
            return false;
        }
    }

    // ================================================================== 小工具

    /** 成就是不是 {@code ns:path} 形状（只看形状，不查注册表）。 */
    public static boolean isValidId(String id) {
        return ShanhaiJsonText.isResourceLocation(id);
    }

    /** 失败结果（不写盘）。 */
    public static Result fail(String why) {
        return new Result(false, why == null ? "failed" : why, List.of(), List.of(), null, "");
    }

    /** 面板上"给成就"时的一条读数：三样都写对了吗（给人看的短句）。 */
    public static String explainThreePieces(Request req) {
        if (req == null || req.criteria() == null) {
            return "criteria+done";
        }
        return "criteria(" + req.criteria().size() + " 条)+done=true";
    }

    /** 数字字面量（给别的类复用的最小件；避免各处各写一份）。 */
    public static JsonPrimitive num(long v) {
        return new JsonPrimitive(v);
    }
}
