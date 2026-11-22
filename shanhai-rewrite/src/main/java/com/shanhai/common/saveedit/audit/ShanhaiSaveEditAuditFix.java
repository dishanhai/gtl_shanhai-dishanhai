package com.shanhai.common.saveedit.audit;

import com.shanhai.common.saveedit.ShanhaiSaveEditFlow;
import com.shanhai.common.saveedit.ShanhaiSaveEditStorage;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * 「存档体检」的<b>唯一动作</b>：把白名单之外的一条异常（文件 / 目录）<b>删掉</b>。
 *
 * <h2>一、语义（用户 2026-11-15 拍板）</h2>
 * 「若不是白名单中的数据，全部列为异常数据，并且允许玩家删除」⇒ 本类只做删除：
 * <ul>
 *   <li>文件 ⇒ 直接删；</li>
 *   <li>目录 ⇒ <b>递归删</b>（先把"将删 N 个文件 / M 字节"算出来，再删）；</li>
 *   <li>🔴 <b>不弹二次确认</b>（用户明确讨厌自加的确认弹窗）；</li>
 *   <li>🔴 <b>必须可撤回</b>：删之前把原件<b>复制</b>到
 *       {@code <游戏根>/config/shanhai/audit-trash/<存档名>-<时间戳>/<相对路径>}
 *       （<b>不在存档里</b> —— 否则回收副本自己又变成一条异常数据）；</li>
 *   <li>🔴 <b>只在停服窗口里执行</b>（窗口判据沿用 {@link ShanhaiSaveEditFlow#tryApply(String)}，
 *       {@link ShanhaiSaveEditWindow} 那条既有路径）；</li>
 *   <li>🔴 删完<b>回读</b>：目标路径必须真的不存在（否则如实报失败）。</li>
 * </ul>
 *
 * <h2>二、🔴 白名单是硬闸门（负对照就靠它）</h2>
 * {@link ShanhaiSaveEditAuditScan#whitelisted(String)} 为真 ⇒ 一律拒绝（{@code reason=whitelisted}），
 * 且<b>一个字节都不动</b>。探针的负对照就是"删 {@code region/r.0.0.mca} 必须被拒"。
 *
 * <h2>三、两个入口（窗口语义各一半）</h2>
 * <ul>
 *   <li>{@link #drop}：<b>带闸</b>（命令/探针自己开窗）—— 先 {@code tryApply}，没放行就零字节拒绝；</li>
 *   <li>{@link #dropInWindow} / {@link #applyRequestInWindow}：<b>不带闸</b>（面板页挂在既有停服流程后面，
 *       闸在上一层已经过）。</li>
 * </ul>
 */
public final class ShanhaiSaveEditAuditFix {

    /** 动作名（进日志，形如 {@code audit_drop}）。 */
    public static final String KIND_DROP = "drop";

    /** 唯一动作的名字（{@link Request} 里也用它，判据/日志直接对字面串）。 */
    public static final String ACTION_DROP = KIND_DROP;

    /** 窗口没开 ⇒ 拒绝（零字节变更）。 */
    public static final String REASON_WINDOW_CLOSED = "window_closed";
    /** 白名单内的原版布局 ⇒ 拒绝。 */
    public static final String REASON_WHITELISTED = "whitelisted";
    /** 存档里已经没有这条了。 */
    public static final String REASON_NOT_FOUND = "not_found";
    /** 相对路径不合法（绝对路径 / 含 {@code ..} / 空）。 */
    public static final String REASON_BAD_PATH = "bad_path";
    /** 符号链接 / 特殊文件 ⇒ 不动它。 */
    public static final String REASON_SPECIAL = "special_file";
    /** 复制到回收目录失败 ⇒ 一个字节都没删。 */
    public static final String REASON_COPY_FAILED = "trash_copy_failed";
    /** 删到一半失败 ⇒ 已用回收副本盖回去。 */
    public static final String REASON_DELETE_FAILED = "delete_failed";

    /** 闸门上记的"谁在请求"（进 {@link ShanhaiSaveEditFlow.ApplyAttempt}）。 */
    public static final String WHO = "audit:drop";

    /** 一次最多数多少个子文件（防"异常目录里躺着几十万个小文件"）。 */
    public static final int MAX_PLAN_ENTRIES = 200_000;

    /** 回收目录的段：{@code <游戏根>/config/shanhai/audit-trash}。 */
    public static final String TRASH_DIR = "audit-trash";

    private ShanhaiSaveEditAuditFix() {}

    // ================================================================== 结果

    /** 一次删除的读数（每一位都是观察出来的）。 */
    public record Result(String kind, boolean ok, boolean windowClosed, String reason, String message,
                         List<String> files, List<String> notes, int plannedFiles, long plannedBytes,
                         int deletedFiles, long deletedBytes, Path trashDir) {

        /** 一行机器可判的读数（命令/探针/日志都打它）。 */
        public String logLine() {
            return "audit_" + this.kind.replace('-', '_')
                    + " ok=" + this.ok
                    + " window_closed=" + this.windowClosed
                    + " reason=" + (this.reason == null || this.reason.isEmpty() ? "-" : this.reason)
                    + " files=" + this.files
                    + " planned_files=" + this.plannedFiles
                    + " planned_bytes=" + this.plannedBytes
                    + " deleted_files=" + this.deletedFiles
                    + " deleted_bytes=" + this.deletedBytes
                    + " trash=" + (this.trashDir == null ? "-" : this.trashDir)
                    + (this.notes == null || this.notes.isEmpty() ? "" : " notes=" + this.notes);
        }

        /** 面板/聊天栏里的一句话。 */
        public String shortMessage() {
            return (this.ok ? "§a" : "§c") + this.message;
        }
    }

    // ================================================================== 请求（面板暂存 / 命令参数共用）

    /**
     * 一条删除请求：<b>相对存档根</b>的路径（{@code /} 分隔）。
     *
     * <p>旧版本这里是 {@code (动作, UUID)} 两件套（删区块里那份 / 删旁挂那行）；
     * 双份登记判据删掉之后只剩"删这一条"，所以请求也只剩一个字段。
     */
    public record Request(String rel) {

        /** 唯一动作名（老调用方还能用 {@code Request.ACTION_DROP} 写日志）。 */
        public static final String ACTION_DROP = ShanhaiSaveEditAuditFix.ACTION_DROP;

        /** 什么都没选。 */
        public boolean isEmpty() {
            return this.rel == null || this.rel.trim().isEmpty();
        }

        /** 归一路径（{@code \} → {@code /}、去掉首尾 {@code /} 与 {@code .} 段）；非法 ⇒ {@code null}。 */
        public String normalizedRel() {
            return normalize(this.rel);
        }

        /**
         * 校验（面板暂存前 + 停服前 + 落盘前各一次）。
         *
         * @throws IllegalArgumentException 说清楚哪里不对（面板直接回显）
         */
        public void validate() {
            if (isEmpty()) {
                throw new IllegalArgumentException("体检页：先在清单里选一条，再点「删除选中项」");
            }
            final String rel = normalizedRel();
            if (rel == null || rel.isEmpty()) {
                throw new IllegalArgumentException("相对路径不合法（不许绝对路径 / 不许含 ..）：" + this.rel);
            }
        }

        public String line() {
            return "action=" + ACTION_DROP + " rel=" + this.rel;
        }
    }

    // ================================================================== 路径

    /**
     * 归一成"相对存档根的 {@code /} 路径"。
     *
     * @return {@code null} = 非法（绝对路径 / 含 {@code ..} / 空）
     */
    public static String normalize(String raw) {
        if (raw == null) {
            return null;
        }
        final String s = raw.trim().replace('\\', '/');
        if (s.isEmpty()) {
            return "";
        }
        // 绝对路径：盘符（C:/）或前导 /
        if (s.length() >= 2 && s.charAt(1) == ':') {
            return null;
        }
        if (s.startsWith("/")) {
            return null;
        }
        final List<String> parts = new ArrayList<>();
        for (String part : s.split("/")) {
            if (part.isEmpty() || ".".equals(part)) {
                continue;
            }
            if ("..".equals(part)) {
                return null;
            }
            parts.add(part);
        }
        return String.join("/", parts);
    }

    /** 回收目录：{@code <游戏根>/config/shanhai/audit-trash/<存档名>-<时间戳>}。 */
    public static Path trashRoot(Path worldDir, String stamp) {
        final Path world = worldDir == null ? Paths.get("world").toAbsolutePath() : worldDir.toAbsolutePath().normalize();
        Path base = world.getParent();
        if (base != null && base.getFileName() != null && "saves".equalsIgnoreCase(base.getFileName().toString())) {
            base = base.getParent();                      // 客户端：<游戏根>/saves/<世界> ⇒ 退两层
        }
        if (base == null) {
            base = Paths.get("").toAbsolutePath();
        }
        final String name = world.getFileName() == null ? "world" : world.getFileName().toString();
        final String when = stamp == null || stamp.isEmpty() ? "nostamp" : stamp;
        return base.resolve("config").resolve("shanhai").resolve(TRASH_DIR).resolve(name + "-" + when);
    }

    // ================================================================== 动作（带闸：命令/探针）

    /**
     * 删掉白名单之外的这条（<b>必须先在停服窗口里</b>）。
     *
     * @param flow 当前运行；{@code null} 也视为"窗口没开"（照样拒绝、零字节变更）
     */
    public static Result drop(Path worldDir, String relative, ShanhaiSaveEditFlow flow, String stamp) {
        final ShanhaiSaveEditFlow.ApplyAttempt attempt = flow == null ? null : flow.tryApply(WHO);
        if (attempt == null || !attempt.permitted()) {
            return new Result(KIND_DROP, false, true, REASON_WINDOW_CLOSED,
                    "窗口没开（" + (flow == null ? "当前没有 saveedit 运行"
                            : "现在在 " + flow.phase() + " 阶段") + "）⇒ 一个字节都没动，已拒绝",
                    List.of(), List.of("闸门未放行：" + (attempt == null ? "no_flow" : attempt.toString())),
                    0, 0L, 0, 0L, null);
        }
        final Result r = dropInWindow(worldDir, relative, stamp);
        if (r.ok()) {
            flow.markApplied();
        }
        return r;
    }

    // ================================================================== 动作（不带闸：面板在窗口里）

    /**
     * 同 {@link #drop}，但<b>不带闸</b>：调用方必须已经在窗口里（面板页就是这条）。
     */
    public static Result dropInWindow(Path worldDir, String relative, String stamp) {
        final String rel = normalize(relative);
        if (rel == null || rel.isEmpty()) {
            return fail(REASON_BAD_PATH, "相对路径不合法（不许绝对路径 / 不许含 ..）：" + relative);
        }
        // 🔴 白名单闸门先于一切 IO：判据只看路径，所以"删 region/r.0.0.mca"在任何环境下都必须被拒
        if (ShanhaiSaveEditAuditScan.whitelisted(rel)) {
            return fail(REASON_WHITELISTED, "这是白名单里的原版布局，不允许删：" + rel);
        }
        if (worldDir == null || !Files.isDirectory(worldDir)) {
            return fail("no_world", "存档目录不存在：" + worldDir);
        }
        final Path root = worldDir.toAbsolutePath().normalize();
        final Path target = root.resolve(rel).normalize();
        if (!target.startsWith(root) || target.equals(root)) {
            return fail(REASON_BAD_PATH, "解析出来的路径跑到存档外面了：" + target);
        }
        final boolean isDir = Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS);
        final boolean isFile = Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS);
        if (!isDir && !isFile) {
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                return fail(REASON_SPECIAL, "这条不是普通文件/目录（符号链接或特殊文件），不动它：" + rel);
            }
            return fail(REASON_NOT_FOUND, "存档里已经没有这条了（可能已经删过）：" + rel);
        }
        final Plan plan;
        try {
            plan = isDir ? planTree(root, target, rel) : planFile(target, rel);
        } catch (Throwable t) {
            return fail("plan_failed", "数不清这条要删多少东西（未动手）：" + rel + "（" + t + "）");
        }
        if (!plan.ok()) {
            return fail(REASON_SPECIAL, "这条目录里有符号链接/特殊文件（不跟链接、不动手）："
                    + plan.blocked());
        }
        // ── ① 复制到回收目录（复制任何一份失败 ⇒ 一个字节都不删）
        final Path trash = trashRoot(root, stamp);
        try {
            for (String childRel : plan.files()) {
                final Path src = root.resolve(childRel);
                final Path dst = trash.resolve(childRel);
                Files.createDirectories(dst.getParent());
                Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
                final String a = ShanhaiSaveEditStorage.sha256(src);
                final String b = ShanhaiSaveEditStorage.sha256(dst);
                if (a == null || !a.equals(b)) {
                    return fail(REASON_COPY_FAILED, "回收副本校验不过（未删任何东西）：" + childRel);
                }
            }
            for (String dirRel : plan.dirs()) {
                Files.createDirectories(trash.resolve(dirRel));   // 空目录也留一份，撤回才完整
            }
        } catch (Throwable t) {
            return fail(REASON_COPY_FAILED, "复制到回收目录失败（未删任何东西）：" + rel + "（" + t + "）");
        }
        // ── ② 删（目录先子后父）
        final List<String> done = new ArrayList<>();
        int deletedFiles = 0;
        long deletedBytes = 0L;
        try {
            for (String childRel : plan.files()) {
                final Path child = root.resolve(childRel);
                final long size = sizeOf(child);
                Files.deleteIfExists(child);
                done.add(childRel);
                deletedFiles++;
                deletedBytes += size;
            }
            for (int i = plan.dirs().size() - 1; i >= 0; i--) {
                Files.deleteIfExists(root.resolve(plan.dirs().get(i)));
            }
        } catch (Throwable t) {
            final int back = restoreFromTrash(root, trash, done);
            return new Result(KIND_DROP, false, false, REASON_DELETE_FAILED,
                    "删到一半失败（已用回收副本盖回 " + back + " 个文件）：" + rel + "（" + t + "）",
                    List.of(rel), List.of("删前计划：将删 " + plan.files().size() + " 个文件 / " + plan.bytes()
                            + " 字节；回收目录=" + trash), plan.files().size(), plan.bytes(),
                    deletedFiles, deletedBytes, trash);
        }
        // ── ③ 回读：必须真的没了
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            final int back = restoreFromTrash(root, trash, done);
            return new Result(KIND_DROP, false, false, "readback_present",
                    "删完回读还在（已用回收副本盖回 " + back + " 个文件）：" + rel,
                    List.of(rel), List.of("回收目录=" + trash), plan.files().size(), plan.bytes(),
                    0, 0L, trash);
        }
        final String what = isDir
                ? "将删 " + plan.files().size() + " 个文件 / " + plan.bytes() + " 字节 ⇒ 递归删完，回读确认已消失"
                : "1 个文件 / " + plan.bytes() + " 字节 ⇒ 已删，回读确认已消失";
        return new Result(KIND_DROP, true, false, "",
                "§f已删「" + rel + "」：" + what + "；删前的原件在回收目录 " + trash + "（可撤回）",
                List.of(rel), planNotes(plan, trash), plan.files().size(), plan.bytes(),
                plan.files().size(), plan.bytes(), trash);
    }

    /**
     * 面板页把暂存的删除请求交给停服窗口。
     *
     * <p>🔴 这里<b>不再</b>过闸 —— 闸在上一层 {@code tryApply} 已经过了。
     */
    public static Result applyRequestInWindow(Path worldDir, Request request, String stamp) {
        if (request == null || request.isEmpty()) {
            return fail("empty_request", "体检页没有任何动作");
        }
        try {
            request.validate();
        } catch (IllegalArgumentException iae) {
            return fail("bad_request", iae.getMessage());
        }
        return dropInWindow(worldDir, request.normalizedRel(), stamp);
    }

    // ================================================================== 预检（停服之前）

    /**
     * 🔴 <b>停服之前的预检</b>（能提前知道的失败，绝不等到停完服才发现）。
     *
     * @return {@code null} = 可以继续；否则是中文原因
     */
    public static String precheck(Path worldDir, Request request) {
        if (request == null || request.isEmpty()) {
            return null;                                  // 这一页不参与
        }
        try {
            request.validate();
        } catch (IllegalArgumentException iae) {
            return iae.getMessage();
        }
        if (worldDir == null || !Files.isDirectory(worldDir)) {
            return "存档目录不存在：" + worldDir;
        }
        final String rel = request.normalizedRel();
        if (ShanhaiSaveEditAuditScan.whitelisted(rel)) {
            return REASON_WHITELISTED + "：这是白名单里的原版布局，不允许删（" + rel + "）";
        }
        final Path target = worldDir.toAbsolutePath().normalize().resolve(rel).normalize();
        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            return "存档里已经没有这条了（可能已经删过）：" + rel;
        }
        if (!Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)
                && !Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
            return REASON_SPECIAL + "：不是普通文件/目录（符号链接或特殊文件），不动它：" + rel;
        }
        return null;
    }

    // ================================================================== 计划（数清"将删 N 个文件 / M 字节"）

    /** 一条删除的计划：要删哪些文件（相对存档根）、哪些目录（子先父后）、共多少字节。 */
    public record Plan(List<String> files, List<String> dirs, long bytes, String blocked) {

        boolean ok() {
            return this.blocked == null;
        }
    }

    private static Plan planFile(Path target, String rel) {
        long size = 0L;
        try {
            size = Files.size(target);
        } catch (Throwable ignored) {
            // 读不到大小就不计（不是失败条件）
        }
        return new Plan(List.of(rel), List.of(), size, null);
    }

    private static Plan planTree(Path root, Path target, String rel) {
        final List<String> files = new ArrayList<>();
        final List<String> dirs = new ArrayList<>();
        final long[] bytes = new long[] {0L};
        final String[] blocked = new String[] {null};
        collect(root, target, rel, files, dirs, bytes, blocked, 0);
        return new Plan(List.copyOf(files), List.copyOf(dirs), bytes[0], blocked[0]);
    }

    /**
     * 递归数一条目录的子树。
     *
     * <p>🔴 撞到<b>符号链接 / 特殊文件</b>就整条拒绝（{@code blocked} 记下是谁）：
     * 复制副本时 Windows 会顺着链接把目标内容拷进来（可能很大、也可能在存档外），
     * 而删掉链接本身又救不回来 —— 这种"说不清"的东西一律不动。
     */
    private static void collect(Path root, Path dir, String rel, List<String> files, List<String> dirs,
                                long[] bytes, String[] blocked, int depth) {
        if (blocked[0] != null || depth > 64 || files.size() >= MAX_PLAN_ENTRIES) {
            return;
        }
        dirs.add(rel);
        for (Path c : list(dir)) {
            final String name = c.getFileName() == null ? "" : c.getFileName().toString();
            final String childRel = rel + "/" + name;
            if (Files.isSymbolicLink(c)) {
                blocked[0] = childRel;
                return;
            }
            if (Files.isDirectory(c, LinkOption.NOFOLLOW_LINKS)) {
                collect(root, c, childRel, files, dirs, bytes, blocked, depth + 1);
                continue;
            }
            if (!Files.isRegularFile(c, LinkOption.NOFOLLOW_LINKS)) {
                blocked[0] = childRel;
                return;
            }
            files.add(childRel);
            try {
                bytes[0] += Files.size(c);
            } catch (Throwable ignored) {
                // 读不到大小就不计（不是失败条件）
            }
        }
    }

    private static long sizeOf(Path p) {
        try {
            return Files.size(p);
        } catch (Throwable t) {
            return 0L;
        }
    }

    private static List<Path> list(Path dir) {
        final List<Path> out = new ArrayList<>();
        try (java.nio.file.DirectoryStream<Path> ds = Files.newDirectoryStream(dir)) {
            for (Path p : ds) {
                out.add(p);
            }
        } catch (Throwable t) {
            return out;
        }
        out.sort(java.util.Comparator.comparing(p -> p.getFileName() == null ? "" : p.getFileName().toString()));
        return out;
    }

    /** 用回收副本把已经删掉的那几个盖回去（尽力而为；返回成功条数）。 */
    private static int restoreFromTrash(Path root, Path trash, List<String> done) {
        int n = 0;
        if (done == null) {
            return 0;
        }
        for (String rel : done) {
            final Path src = trash.resolve(rel);
            final Path dst = root.resolve(rel);
            if (!Files.isRegularFile(src)) {
                continue;
            }
            try {
                if (dst.getParent() != null) {
                    Files.createDirectories(dst.getParent());
                }
                Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING);
                n++;
            } catch (Throwable ignored) {
                // 盖不回去也不改结论（结论已经是失败）
            }
        }
        return n;
    }

    private static List<String> planNotes(Plan plan, Path trash) {
        final List<String> notes = new ArrayList<>();
        notes.add("回收目录=" + trash);
        notes.add("计划/已完成：" + plan.files().size() + " 个文件 / " + plan.bytes() + " 字节");
        int shown = 0;
        for (String rel : plan.files()) {
            if (shown >= 20) {
                notes.add("…还有 " + (plan.files().size() - shown) + " 个文件（见回收目录）");
                break;
            }
            notes.add("  - " + rel);
            shown++;
        }
        return notes;
    }

    /** 失败（不动盘）的通用构造。 */
    public static Result fail(String reason, String message) {
        return new Result(KIND_DROP, false, false, reason, message, List.of(), List.of(), 0, 0L, 0, 0L, null);
    }

    @Override
    public String toString() {
        return "ShanhaiSaveEditAuditFix[action=" + ACTION_DROP + "]";
    }
}
