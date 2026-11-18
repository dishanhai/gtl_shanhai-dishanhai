package com.dishanhai.gt_shanhai.common.saveedit.audit;

import com.dishanhai.gt_shanhai.common.saveedit.ShanhaiEntityEdit;
import com.dishanhai.gt_shanhai.common.saveedit.ShanhaiSaveEditFlow;
import com.dishanhai.gt_shanhai.common.saveedit.ShanhaiSaveEditStorage;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 「双份登记」的两个修改动作 —— <b>都必须在停服窗口里跑</b>。
 *
 * <h2>一、两个动作（缺一不可）</h2>
 * <ol>
 *   <li>{@link #dropEntity} —— 把<b>区块文件里</b>那条实体删掉。复用既有的实体删除能力
 *       （{@link ShanhaiEntityEdit#applyToFile}）：它只摘目标实体、同区块其它条目<b>连对象都不换</b>，
 *       其它区块的压缩字节原样搬，写完还会自己回读校验一次。</li>
 *   <li>{@link #dropSidecar} —— 把<b>纯文本旁挂文件</b>里"含该 UUID（或匹配坐标）的整行"删掉。
 *       做法是<b>字节级删行</b>（按 {@code \n} 切区间，只去掉命中的那几段），
 *       于是行尾风格（{@code \r\n} / {@code \n}）、其余行的每一个字节、末尾有没有换行都原样保留。</li>
 * </ol>
 * 🔴 只做一半是不行的：只删区块那份 ⇒ 旁挂文件里的登记还在（再扫一次会以
 * {@link ShanhaiSaveEditAuditScan#KIND_SIDECAR_ONLY} 报回来）；只删旁挂那行 ⇒ 区块里那条实体还在
 * （再扫一次还是 {@link ShanhaiSaveEditAuditScan#KIND_DOUBLE}）。这就是判据里那条负对照。
 *
 * <h2>二、🔴 窗口语义</h2>
 * {@link #dropEntity} / {@link #dropSidecar} 是<b>带闸</b>的入口：先
 * {@link ShanhaiSaveEditFlow#tryApply(String)}，没放行就<b>一个字节都不写</b>，并回报
 * {@code window_closed=true}、{@code bytes_written=0}。
 * 另外两个 {@code …InWindow} 入口<b>不带闸</b>，留给"已经在窗口里、并且已经由调用方过了闸"的场合
 * （面板页就是这样：它挂在既有的停服流程后面，闸在上一层）。两条路径都调用同一段实现。
 *
 * <h2>三、备份与原子写</h2>
 * 一律走 {@link ShanhaiSaveEditStorage}：备份 → 同目录 {@code .tmp} → ATOMIC_MOVE → 回读哈希。
 * 本类自己再加两道回读：① 盘上字节必须等于"我算出来的新字节"；② 新文件里那条 UUID/坐标
 * 必须<b>再也找不到</b>（否则用备份盖回去，并如实报失败）。
 *
 * <h2>四、二进制旁挂只报不改</h2>
 * 命中的旁挂文件是二进制/NBT ⇒ 一个字都不动，回报 {@code reason=sidecar_binary_skipped} 并写明
 * "需你手动处理"，同时把命中的 tagPath 列出来，方便人去文件里定位那一处。
 */
public final class ShanhaiSaveEditAuditFix {

    /** 动作名（进日志，形如 {@code audit_drop_entity}）。 */
    public static final String KIND_DROP_ENTITY = "drop-entity";
    public static final String KIND_DROP_SIDECAR = "drop-sidecar";

    /** 窗口没开 ⇒ 拒绝（零字节写入）。 */
    public static final String REASON_WINDOW_CLOSED = "window_closed";
    /** 命中的旁挂是二进制/NBT ⇒ 只报不改。 */
    public static final String REASON_BINARY = "sidecar_binary_skipped";
    /** 找不到要删的东西。 */
    public static final String REASON_NOT_FOUND = "not_found";

    /** 闸门上记的"谁在请求"（进 {@link ShanhaiSaveEditFlow.ApplyAttempt}）。 */
    public static final String WHO_ENTITY = "audit:drop-entity";
    public static final String WHO_SIDECAR = "audit:drop-sidecar";

    private ShanhaiSaveEditAuditFix() {}

    // ================================================================== 结果

    /** 一次修改的读数（每一位都是观察出来的）。 */
    public record Result(String kind, boolean ok, boolean windowClosed, String reason, String message,
                         List<String> files, List<String> notes, int removedEntities, int removedLines,
                         long bytesWritten, String shaBefore, String shaAfter,
                         List<ShanhaiSaveEditStorage.WriteResult> writes) {

        public ShanhaiSaveEditStorage.WriteResult write() {
            return this.writes == null || this.writes.isEmpty() ? null : this.writes.get(0);
        }

        /** 一行机器可判的读数（命令/探针/日志都打它）。 */
        public String logLine() {
            return "audit_" + this.kind.replace('-', '_')
                    + " ok=" + this.ok
                    + " window_closed=" + this.windowClosed
                    + " reason=" + (this.reason == null || this.reason.isEmpty() ? "-" : this.reason)
                    + " files=" + this.files
                    + " removed_entities=" + this.removedEntities
                    + " removed_lines=" + this.removedLines
                    + " bytes_written=" + this.bytesWritten
                    + " sha_before=" + shortSha(this.shaBefore)
                    + " sha_after=" + shortSha(this.shaAfter)
                    + (this.notes == null || this.notes.isEmpty() ? "" : " notes=" + this.notes);
        }

        /** 面板/聊天栏里的一句话。 */
        public String shortMessage() {
            return (this.ok ? "§a" : "§c") + this.message;
        }
    }

    // ================================================================== 动作一：删区块里那份（带闸）

    /**
     * 删掉区块文件里的那条实体（<b>必须先在窗口里</b>）。
     *
     * @param flow 当前运行；{@code null} 也视为"窗口没开"（照样拒绝、零字节写入）
     */
    public static Result dropEntity(Path worldDir, String uuid, ShanhaiSaveEditFlow flow,
                                    Path backupRoot, String stamp) {
        final String canonical = ShanhaiSaveEditAuditUuid.canonical(uuid);
        if (canonical == null) {
            return fail(KIND_DROP_ENTITY, "bad_uuid", com.dishanhai.gt_shanhai.common.saveedit.ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.59aed53da1da10f2") + uuid);
        }
        final ShanhaiSaveEditFlow.ApplyAttempt attempt = flow == null ? null : flow.tryApply(WHO_ENTITY);
        if (attempt == null || !attempt.permitted()) {
            return new Result(KIND_DROP_ENTITY, false, true, REASON_WINDOW_CLOSED,
                    com.dishanhai.gt_shanhai.common.saveedit.ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.d262ebb4bff1fcea") + (flow == null ? com.dishanhai.gt_shanhai.common.saveedit.ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.1ce51dbb319c0d8c")
                            : com.dishanhai.gt_shanhai.common.saveedit.ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.eb2385a0625b543e") + flow.phase() + com.dishanhai.gt_shanhai.common.saveedit.ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.137fe8c8500b7b78")) + com.dishanhai.gt_shanhai.common.saveedit.ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.c79b9dbaaea95db9"),
                    List.of(), List.of(com.dishanhai.gt_shanhai.common.saveedit.ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.bfd9137842b9bd4c") + (attempt == null ? "no_flow" : attempt.toString())),
                    0, 0, 0L, null, null, List.of());
        }
        final Result r = dropEntityInWindow(worldDir, canonical, backupRoot, stamp);
        if (r.ok()) {
            flow.markApplied();
        }
        return r;
    }

    /**
     * 同 {@link #dropEntity}，但<b>不带闸</b>：调用方必须已经过
     * {@link ShanhaiSaveEditFlow#tryApply(String)}（面板页下一轮就是这样接）。
     */
    public static Result dropEntityInWindow(Path worldDir, String uuid, Path backupRoot, String stamp) {
        final String canonical = ShanhaiSaveEditAuditUuid.canonical(uuid);
        if (canonical == null) {
            return fail(KIND_DROP_ENTITY, "bad_uuid", com.dishanhai.gt_shanhai.common.saveedit.ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.59aed53da1da10f2") + uuid);
        }
        if (worldDir == null || !Files.isDirectory(worldDir)) {
            return fail(KIND_DROP_ENTITY, "no_world", com.dishanhai.gt_shanhai.common.saveedit.ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.e89945a2e80e0290") + worldDir);
        }
        final ShanhaiSaveEditAuditScan.EntityRef target = ShanhaiSaveEditAuditScan.findEntity(worldDir, canonical);
        if (target == null) {
            return fail(KIND_DROP_ENTITY, "entity_not_found",
                    com.dishanhai.gt_shanhai.common.saveedit.ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.027d0386530cb83e") + canonical);
        }
        final Path file = worldDir.resolve(target.file());
        if (!Files.isRegularFile(file)) {
            return fail(KIND_DROP_ENTITY, "entity_file_missing", com.dishanhai.gt_shanhai.common.saveedit.ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.d70a68154d59d382") + target.file());
        }
        final int[] rz = regionCoordsOf(target.file());
        final String shaBefore = ShanhaiSaveEditStorage.sha256(file);
        final ShanhaiEntityEdit.Result r;
        try {
            r = ShanhaiEntityEdit.applyToFile(file, new ShanhaiEntityEdit.Request(target.dim(), rz[0], rz[1],
                    null, canonical), backupRoot, stamp);
        } catch (Throwable t) {
            return fail(KIND_DROP_ENTITY, "exception", com.dishanhai.gt_shanhai.common.saveedit.ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.72146a5777142ec1") + t);
        }
        final String shaAfter = ShanhaiSaveEditStorage.sha256(file);
        final List<String> notes = new ArrayList<>();
        if (r.notes() != null) {
            notes.addAll(r.notes());
        }
        notes.add(com.dishanhai.gt_shanhai.common.saveedit.ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.4d2d25a2888d3e7c") + target.line());
        final List<ShanhaiSaveEditStorage.WriteResult> writes = r.write() == null ? List.of() : List.of(r.write());
        if (!r.ok()) {
            return new Result(KIND_DROP_ENTITY, false, false, "drop_failed", com.dishanhai.gt_shanhai.common.saveedit.ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.26be7901ef29d6f0") + r.error(),
                    List.of(target.file()), notes, 0, 0, 0L, shaBefore, shaAfter, writes);
        }
        return new Result(KIND_DROP_ENTITY, true, false, "", com.dishanhai.gt_shanhai.common.saveedit.ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.1d604f812cff944d") + target.file()
                + com.dishanhai.gt_shanhai.common.saveedit.ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.8bae4c1446d1ab8b"),
                List.of(target.file()), notes, 1, 0, sizeOf(file), shaBefore, shaAfter, writes);
    }

    // ================================================================== 动作二：删旁挂那行（带闸）

    /**
     * 把文本旁挂文件里"含该 UUID（或匹配坐标）的整行"删掉（<b>必须先在窗口里</b>）。
     *
     * <p>二进制/NBT 旁挂<b>只报不改</b>（回报 {@code sidecar_binary_skipped} + "需你手动处理"）。
     */
    public static Result dropSidecar(Path worldDir, String uuid, ShanhaiSaveEditFlow flow,
                                     Path backupRoot, String stamp) {
        final String canonical = ShanhaiSaveEditAuditUuid.canonical(uuid);
        if (canonical == null) {
            return fail(KIND_DROP_SIDECAR, "bad_uuid", com.dishanhai.gt_shanhai.common.saveedit.ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.59aed53da1da10f2") + uuid);
        }
        final ShanhaiSaveEditFlow.ApplyAttempt attempt = flow == null ? null : flow.tryApply(WHO_SIDECAR);
        if (attempt == null || !attempt.permitted()) {
            return new Result(KIND_DROP_SIDECAR, false, true, REASON_WINDOW_CLOSED,
                    com.dishanhai.gt_shanhai.common.saveedit.ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.d262ebb4bff1fcea") + (flow == null ? com.dishanhai.gt_shanhai.common.saveedit.ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.1ce51dbb319c0d8c")
                            : com.dishanhai.gt_shanhai.common.saveedit.ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.eb2385a0625b543e") + flow.phase() + com.dishanhai.gt_shanhai.common.saveedit.ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.137fe8c8500b7b78")) + com.dishanhai.gt_shanhai.common.saveedit.ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.c79b9dbaaea95db9"),
                    List.of(), List.of(com.dishanhai.gt_shanhai.common.saveedit.ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.bfd9137842b9bd4c") + (attempt == null ? "no_flow" : attempt.toString())),
                    0, 0, 0L, null, null, List.of());
        }
        final Result r = dropSidecarInWindow(worldDir, canonical, backupRoot, stamp);
        if (r.ok()) {
            flow.markApplied();
        }
        return r;
    }

    /** 同 {@link #dropSidecar}，但<b>不带闸</b>（调用方已经过 {@code tryApply}）。 */
    public static Result dropSidecarInWindow(Path worldDir, String uuid, Path backupRoot, String stamp) {
        final String canonical = ShanhaiSaveEditAuditUuid.canonical(uuid);
        if (canonical == null) {
            return fail(KIND_DROP_SIDECAR, "bad_uuid", com.dishanhai.gt_shanhai.common.saveedit.ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.59aed53da1da10f2") + uuid);
        }
        if (worldDir == null || !Files.isDirectory(worldDir)) {
            return fail(KIND_DROP_SIDECAR, "no_world", com.dishanhai.gt_shanhai.common.saveedit.ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.e89945a2e80e0290") + worldDir);
        }
        // 区块里可能已经删掉了 ⇒ 坐标拿不到就只按 UUID 匹配
        final ShanhaiSaveEditAuditScan.EntityRef target = ShanhaiSaveEditAuditScan.findEntity(worldDir, canonical);
        final double[] pos = target == null ? null : target.pos();
        final List<ShanhaiSaveEditAuditScan.SidecarHit> hits =
                ShanhaiSaveEditAuditScan.sidecarHits(worldDir, canonical, pos);
        return applySidecarHits(hits, canonical, pos, backupRoot, stamp);
    }

    static Result applySidecarHits(List<ShanhaiSaveEditAuditScan.SidecarHit> hits, String canonical,
                                   double[] pos, Path backupRoot, String stamp) {
        if (ShanhaiSaveEditStorage.hasActiveTransaction()) {
            return applySidecarHitsInternal(hits, canonical, pos, backupRoot, stamp);
        }
        ShanhaiSaveEditStorage.Transaction tx = ShanhaiSaveEditStorage.beginTransaction();
        Result result;
        try {
            result = applySidecarHitsInternal(hits, canonical, pos, backupRoot, stamp);
        } catch (Throwable failure) {
            result = fail(KIND_DROP_SIDECAR, "sidecar_exception", String.valueOf(failure));
        }
        if (result.ok()) {
            tx.commit();
            return result;
        }
        List<Path> failures = tx.rollback();
        String recovery = failures.isEmpty()
                ? com.dishanhai.gt_shanhai.common.saveedit.ShanhaiSaveEditText.text("gt_shanhai.saveedit.rollback_complete")
                : com.dishanhai.gt_shanhai.common.saveedit.ShanhaiSaveEditText.text("gt_shanhai.saveedit.rollback_failed") + failures;
        return new Result(result.kind(), false, result.windowClosed(), result.reason(), result.message() + " · " + recovery,
                failures.isEmpty() ? List.of() : result.files(), result.notes(), 0, 0, 0,
                result.shaBefore(), result.shaAfter(), result.writes());
    }

    private record PreparedSidecar(ShanhaiSaveEditAuditScan.SidecarHit hit, byte[] before) {}

    private static Result applySidecarHitsInternal(List<ShanhaiSaveEditAuditScan.SidecarHit> hits, String canonical,
                                                   double[] pos, Path backupRoot, String stamp) {
        if (hits.isEmpty()) {
            return fail(KIND_DROP_SIDECAR, "no_match",
                    com.dishanhai.gt_shanhai.common.saveedit.ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.8cfa67d29c7829e1") + canonical + "）");
        }
        final List<ShanhaiSaveEditAuditScan.SidecarHit> text = new ArrayList<>();
        final List<ShanhaiSaveEditAuditScan.SidecarHit> binary = new ArrayList<>();
        for (ShanhaiSaveEditAuditScan.SidecarHit h : hits) {
            (h.binary() ? binary : text).add(h);
        }
        if (text.isEmpty()) {
            return new Result(KIND_DROP_SIDECAR, false, false, REASON_BINARY,
                    com.dishanhai.gt_shanhai.common.saveedit.ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.b07c04ae4f81fbdd") + describe(binary),
                    rels(binary), List.of(REASON_BINARY + "=" + binary.size() + com.dishanhai.gt_shanhai.common.saveedit.ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.bad88580f7a79bab")),
                    0, 0, 0L, null, null, List.of());
        }

        // Read and revalidate every matched text sidecar before changing any file.
        final List<PreparedSidecar> prepared = new ArrayList<>();
        for (ShanhaiSaveEditAuditScan.SidecarHit hit : text) {
            final byte[] before;
            try {
                before = Files.readAllBytes(hit.file());
            } catch (Throwable failure) {
                return fail(KIND_DROP_SIDECAR, "sidecar_read_failed",
                        com.dishanhai.gt_shanhai.common.saveedit.ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.5ddca7a192901a85")
                                + hit.rel() + " (" + failure + ")");
            }
            String current = ShanhaiSaveEditAuditScan.decodeText(before);
            if (current == null || !ShanhaiSaveEditAuditScan.matchingLines(current, canonical, pos).equals(hit.lines())) {
                return fail(KIND_DROP_SIDECAR, "sidecar_changed",
                        com.dishanhai.gt_shanhai.common.saveedit.ShanhaiSaveEditText.text("gt_shanhai.saveedit.sidecar_changed") + hit.rel());
            }
            prepared.add(new PreparedSidecar(hit, before));
        }

        final List<String> files = new ArrayList<>();
        final List<String> notes = new ArrayList<>();
        final List<ShanhaiSaveEditStorage.WriteResult> writes = new ArrayList<>();
        int removedLines = 0;
        long bytes = 0L;
        String shaBefore = null;
        String shaAfter = null;
        for (PreparedSidecar entry : prepared) {
            final ShanhaiSaveEditAuditScan.SidecarHit h = entry.hit();
            final byte[] before = entry.before();
            final String fileShaBefore = ShanhaiSaveEditStorage.sha256(before);
            if (!fileShaBefore.equals(ShanhaiSaveEditStorage.sha256(h.file()))) {
                return fail(KIND_DROP_SIDECAR, "sidecar_changed",
                        com.dishanhai.gt_shanhai.common.saveedit.ShanhaiSaveEditText.text("gt_shanhai.saveedit.sidecar_changed") + h.rel());
            }
            final byte[] after = removeLines(before, h.lines());
            if (after == null || after.length == before.length) {
                notes.add(com.dishanhai.gt_shanhai.common.saveedit.ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.751497e61531a163") + h.rel());
                continue;
            }
            final ShanhaiSaveEditStorage.WriteResult w =
                    ShanhaiSaveEditStorage.writeAtomic(h.file(), after, backupRoot, stamp);
            if (w == null || !w.ok()) {
                return new Result(KIND_DROP_SIDECAR, false, false, "write_failed",
                        com.dishanhai.gt_shanhai.common.saveedit.ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.c0a0e930c2b24a17") + h.rel() + "）：" + (w == null ? "-" : w.error()),
                        files, notes, 0, removedLines, bytes, shaBefore, shaAfter, writes);
            }
            writes.add(w);
            // 回读①：盘上的字节必须就是"我算出来的新字节"
            final byte[] disk;
            try {
                disk = Files.readAllBytes(h.file());
            } catch (Throwable t) {
                ShanhaiSaveEditStorage.restore(w.backupPath(), h.file());
                return new Result(KIND_DROP_SIDECAR, false, false, "readback_failed",
                        com.dishanhai.gt_shanhai.common.saveedit.ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.4cb54b04d4e835dd") + h.rel(), files, notes, 0, removedLines, bytes,
                        shaBefore, shaAfter, writes);
            }
            if (!Arrays.equals(disk, after)) {
                ShanhaiSaveEditStorage.restore(w.backupPath(), h.file());
                return new Result(KIND_DROP_SIDECAR, false, false, "readback_mismatch",
                        com.dishanhai.gt_shanhai.common.saveedit.ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.c25b787a8958b3e7") + h.rel(), files, notes, 0,
                        removedLines, bytes, shaBefore, shaAfter, writes);
            }
            // 回读②：这条 UUID / 这组坐标在新文件里必须再也匹配不到
            final String afterText = ShanhaiSaveEditAuditScan.decodeText(disk);
            final List<Integer> left = afterText == null ? List.of()
                    : ShanhaiSaveEditAuditScan.matchingLines(afterText, canonical, pos);
            if (!left.isEmpty()) {
                ShanhaiSaveEditStorage.restore(w.backupPath(), h.file());
                return new Result(KIND_DROP_SIDECAR, false, false, "still_matched",
                        com.dishanhai.gt_shanhai.common.saveedit.ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.5208e46dedc51b90") + left.size() + com.dishanhai.gt_shanhai.common.saveedit.ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.f9da1e5cefffb607") + h.rel()
                                + com.dishanhai.gt_shanhai.common.saveedit.ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.da356630e5f21849") + left, files, notes, 0, removedLines, bytes, shaBefore, shaAfter, writes);
            }
            // 回读③：行数必须正好少掉删掉的那几行，其余行逐字节不变（= 上面的 Arrays.equals）
            final int linesBefore = countLines(before);
            final int linesAfter = countLines(disk);
            if (linesAfter != linesBefore - h.lines().size()) {
                ShanhaiSaveEditStorage.restore(w.backupPath(), h.file());
                return new Result(KIND_DROP_SIDECAR, false, false, "line_count_mismatch",
                        com.dishanhai.gt_shanhai.common.saveedit.ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.67edafb4b4d0b70f") + linesBefore + "→" + linesAfter + com.dishanhai.gt_shanhai.common.saveedit.ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.13502840cef2fd5c") + h.lines().size()
                                + com.dishanhai.gt_shanhai.common.saveedit.ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.105482f906d1c04e"), files, notes, 0, removedLines, bytes, shaBefore,
                        shaAfter, writes);
            }
            if (shaBefore == null) {
                shaBefore = fileShaBefore;
                shaAfter = ShanhaiSaveEditStorage.sha256(disk);
            }
            removedLines += h.lines().size();
            bytes += after.length;
            files.add(h.rel());
            notes.add(h.rel() + com.dishanhai.gt_shanhai.common.saveedit.ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.c33a353f2084f55d") + h.lines().size() + com.dishanhai.gt_shanhai.common.saveedit.ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.338024ab84e529e2") + linesBefore + "→" + linesAfter
                    + com.dishanhai.gt_shanhai.common.saveedit.ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.0bd81a403a8c2e0b") + (w.backupPath() == null ? "-"
                            : w.backupPath().getFileName().toString())
                    + com.dishanhai.gt_shanhai.common.saveedit.ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.2ebb8e232750c222") + (w.originalSha256() != null
                            && w.originalSha256().equals(w.backupSha256())));
        }
        if (files.isEmpty()) {
            return new Result(KIND_DROP_SIDECAR, false, false, "no_effective_change",
                    com.dishanhai.gt_shanhai.common.saveedit.ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.8cd5f7fbb5b1b105"), rels(hits), notes, 0, 0, 0L,
                    shaBefore, shaAfter, writes);
        }
        if (!binary.isEmpty()) {
            notes.add(REASON_BINARY + "=" + binary.size() + com.dishanhai.gt_shanhai.common.saveedit.ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.8d64aff145ea26f5")
                    + describe(binary));
        }
        return new Result(KIND_DROP_SIDECAR, true, false, "", com.dishanhai.gt_shanhai.common.saveedit.ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.dbe8aae1c13e7178") + removedLines
                + com.dishanhai.gt_shanhai.common.saveedit.ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.338024ab84e529e2") + String.join(", ", files) + "）" + (binary.isEmpty() ? ""
                        : com.dishanhai.gt_shanhai.common.saveedit.ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.384a6714159fb2b0") + binary.size() + com.dishanhai.gt_shanhai.common.saveedit.ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.92eb1771c6325b47")),
                files, notes, 0, removedLines, bytes, shaBefore, shaAfter, writes);
    }

    // ================================================================== 字节级删行

    /**
     * 按行号删掉几行，<b>其余字节一个不动</b>。
     *
     * <p>行号口径与 {@link ShanhaiSaveEditAuditScan#matchingLines} 完全一致：按 {@code \n} 切段、
     * 1 起计数、行尾的 {@code \n} 属于该行（{@code \r\n} 因此天然保留）。
     *
     * @return 新字节；没有可删 ⇒ 原样返回（{@code ==} 同一个数组，调用方能按长度判"没变"）
     */
    public static byte[] removeLines(byte[] data, Collection<Integer> lineNos) {
        if (data == null || lineNos == null || lineNos.isEmpty()) {
            return data;
        }
        final Set<Integer> drop = new HashSet<>(lineNos);
        final ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(32, data.length));
        int lineNo = 0;
        int start = 0;
        for (int i = 0; i <= data.length; i++) {
            if (i == data.length || data[i] == '\n') {
                lineNo++;
                final int end = (i == data.length) ? data.length : i + 1;
                if (!drop.contains(lineNo) && end > start) {
                    out.write(data, start, end - start);
                }
                start = end;
            }
        }
        return out.toByteArray();
    }

    /** 与 {@link #removeLines} 同一口径的行数（最后那个可能为空的段也计一行）。 */
    public static int countLines(byte[] data) {
        if (data == null || data.length == 0) {
            return 0;
        }
        int n = 0;
        for (int i = 0; i <= data.length; i++) {
            if (i == data.length || data[i] == '\n') {
                n++;
            }
        }
        return n;
    }

    // ================================================================== 面板请求（第 ⑦ 页）

    /**
     * 面板页暂存的一次动作（<b>一次只做一件事</b>）。
     *
     * <p>为什么做成"动作 + UUID"两条：面板上选中的是一条 finding，而 finding 有两个可选动作；
     * 把"选哪条"和"动哪一份"分开，暂存与回退都只是换一个请求对象，不会出现"删了一半"。
     */
    public record Request(String action, String uuid) {

        /** 删区块文件里那份。 */
        public static final String DROP_ENTITY = KIND_DROP_ENTITY;
        /** 删旁挂文本文件里那一行。 */
        public static final String DROP_SIDECAR = KIND_DROP_SIDECAR;

        /** 什么都没选。 */
        public boolean isEmpty() {
            return this.action == null || this.action.isEmpty()
                    || this.uuid == null || this.uuid.trim().isEmpty();
        }

        /** 归一后的 UUID（非法 ⇒ {@code null}）。 */
        public String normalizedUuid() {
            return ShanhaiSaveEditAuditUuid.canonical(this.uuid);
        }

        /**
         * 校验（面板暂存前 + 落盘前各一次）。
         *
         * @throws IllegalArgumentException 说清楚哪里不对（面板直接回显）
         */
        public void validate() {
            if (isEmpty()) {
                throw new IllegalArgumentException(com.dishanhai.gt_shanhai.common.saveedit.ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.593c35ffc97e4a45"));
            }
            if (!DROP_ENTITY.equals(this.action) && !DROP_SIDECAR.equals(this.action)) {
                throw new IllegalArgumentException(com.dishanhai.gt_shanhai.common.saveedit.ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.c055c6a90cbbff29")
                        + this.action + "」");
            }
            if (normalizedUuid() == null) {
                throw new IllegalArgumentException(com.dishanhai.gt_shanhai.common.saveedit.ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.59aed53da1da10f2") + this.uuid);
            }
        }

        public String line() {
            return "action=" + this.action + " uuid=" + normalizedUuid();
        }
    }

    /**
     * 🔴 <b>停服之前的预检</b>（能提前知道的失败，绝不等到停完服才发现）。
     *
     * @return {@code null} = 可以继续；否则是中文原因
     */
    public static String precheck(Path worldDir, Request request) {
        if (request == null || request.isEmpty()) {
            return null;                            // 这一页不参与
        }
        try {
            request.validate();
        } catch (IllegalArgumentException iae) {
            return iae.getMessage();
        }
        if (worldDir == null || !Files.isDirectory(worldDir)) {
            return com.dishanhai.gt_shanhai.common.saveedit.ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.e89945a2e80e0290") + worldDir;
        }
        final String uuid = request.normalizedUuid();
        if (Request.DROP_ENTITY.equals(request.action())) {
            final ShanhaiSaveEditAuditScan.EntityRef target =
                    ShanhaiSaveEditAuditScan.findEntity(worldDir, uuid);
            return target == null ? com.dishanhai.gt_shanhai.common.saveedit.ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.027d0386530cb83e") + uuid : null;
        }
        final ShanhaiSaveEditAuditScan.EntityRef target =
                ShanhaiSaveEditAuditScan.findEntity(worldDir, uuid);
        final List<ShanhaiSaveEditAuditScan.SidecarHit> hits = ShanhaiSaveEditAuditScan.sidecarHits(
                worldDir, uuid, target == null ? null : target.pos());
        if (hits.isEmpty()) {
            return com.dishanhai.gt_shanhai.common.saveedit.ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.692248a702427673") + uuid;
        }
        boolean anyText = false;
        for (ShanhaiSaveEditAuditScan.SidecarHit h : hits) {
            anyText |= !h.binary();
        }
        return anyText ? null : REASON_BINARY + com.dishanhai.gt_shanhai.common.saveedit.ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.a94bc75c4fce1c41");
    }

    /**
     * 面板页把暂存的请求交给停服窗口：按 {@code action} 分派到两个 {@code …InWindow}
     * （🔴 那里<b>不再</b>过闸 —— 闸在上一层 {@code tryApply} 已经过了）。
     */
    public static Result applyRequestInWindow(Path worldDir, Request request, Path backupRoot, String stamp) {
        if (request == null || request.isEmpty()) {
            return fail("audit", "empty_request", com.dishanhai.gt_shanhai.common.saveedit.ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.4b003926812dba0d"));
        }
        try {
            request.validate();
        } catch (IllegalArgumentException iae) {
            return fail("audit", "bad_request", iae.getMessage());
        }
        if (Request.DROP_ENTITY.equals(request.action())) {
            return dropEntityInWindow(worldDir, request.normalizedUuid(), backupRoot, stamp);
        }
        return dropSidecarInWindow(worldDir, request.normalizedUuid(), backupRoot, stamp);
    }

    // ================================================================== 小工具

    /** {@code entities/r.<x>.<z>.mca} → {@code [x,z]}（读不出来 ⇒ {@code [0,0]}）。 */
    private static int[] regionCoordsOf(String relativeFile) {
        final String rel = relativeFile == null ? "" : relativeFile.replace('\\', '/');
        final int slash = rel.lastIndexOf('/');
        final String n = slash >= 0 ? rel.substring(slash + 1) : rel;
        try {
            if (n.startsWith("r.") && n.toLowerCase(Locale.ROOT).endsWith(".mca")) {
                final String[] parts = n.substring(2, n.length() - 4).split("\\.");
                if (parts.length == 2) {
                    return new int[] {Integer.parseInt(parts[0]), Integer.parseInt(parts[1])};
                }
            }
        } catch (Throwable ignored) {
            // 落到 [0,0]
        }
        return new int[] {0, 0};
    }

    private static long sizeOf(Path file) {
        try {
            return Files.size(file);
        } catch (Throwable t) {
            return 0L;
        }
    }

    private static List<String> rels(List<ShanhaiSaveEditAuditScan.SidecarHit> hits) {
        final List<String> out = new ArrayList<>();
        for (ShanhaiSaveEditAuditScan.SidecarHit h : hits) {
            out.add(h.rel());
        }
        return out;
    }

    /** 二进制命中的位置（{@code file#tagPath}），供人手动处理。 */
    private static String describe(List<ShanhaiSaveEditAuditScan.SidecarHit> binary) {
        final List<String> parts = new ArrayList<>();
        for (ShanhaiSaveEditAuditScan.SidecarHit h : binary) {
            parts.add(h.rel() + "#" + String.join("|", h.tagPaths()));
        }
        return String.join(", ", parts);
    }

    private static String shortSha(String sha) {
        return sha == null ? "-" : (sha.length() <= 12 ? sha : sha.substring(0, 12));
    }

    /** 失败（不写盘）；{@code bytesWritten=0}。 */
    public static Result fail(String kind, String reason, String message) {
        return new Result(kind, false, false, reason, message, List.of(), List.of(), 0, 0, 0L,
                null, null, List.of());
    }

    @Override
    public String toString() {
        return "ShanhaiSaveEditAuditFix[]";
    }
}
