package com.dishanhai.gt_shanhai.common.saveedit;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * 三页（玩家 / 世界 / 方块）在<b>同一个停服窗口</b>里一起落盘的调度器。
 *
 * <h2>一、为什么要这一层</h2>
 * 一期已经验过的落盘路径只有一条：{@code ShanhaiSaveEditReload#applyOnIo} →
 * {@link ShanhaiPlayerDataEditor#apply}。那条路径（以及它前面的 reopen 逻辑）<b>这次一个字节都不许改</b>。
 * 所以二/三期的改动必须"挂"进同一个请求对象（{@link ShanhaiPlayerDataEdit#levelEdit} /
 * {@link ShanhaiPlayerDataEdit#regionRequest}），再由 {@link ShanhaiPlayerDataEditor#apply}
 * 转到这里按顺序落盘。
 *
 * <h2>二、顺序与部分失败</h2>
 * <pre>
 *   ① 预检：二~七页的前置条件（文件存在 / 请求合法 / 目标真的还在）（level.dat / region/*.mca / entities/*.mca /
 *      advancements/<uuid>.json / stats/<uuid>.json）
 *   ② 玩家页（一期原样：备份 → 原子写 → 回读 diff）
 *   ③ 世界页（二期：同上）
 *   ④ 方块页（三期：同上，另外多两条"其它区块没动"的判据）
 *   ⑤ 实体页（四期：备份 → 原子写 → 回读，另外验"目标 UUID 不在 / 其它实体逐字段等价 /
 *      其它区块压缩字节 SHA 不变"）
 *   ⑥ 成就页（四期：纯 JSON，写前备份 + 写后 JSON 往返等价，且 criteria/done 三样都对）
 *   ⑦ 统计页（四期：纯 JSON，同上）
 *   ⑧ 体检页（五期：双份登记 —— 删区块里那份复用实体页的删除核；删旁挂那行是"字节级删行"，
 *      备份 → 原子写 → 回读①字节相等 ②该 UUID/坐标再也找不到 ③行数正好少掉删的那几行）
 * </pre>
 * 🔴 八部分<b>各自</b>都有"备份 + 原子写 + 回读"，彼此独立；任一部分失败 ⇒ 立刻停手、
 * 不再写后面的部分，并如实报"哪几页已经写进去了"。这比"整批回滚"更诚实：
 * 每一页写进去的内容都是自洽且已回读验证过的，回滚反而可能把一个已经验证通过的结果也弄坏。
 * （预检把"文件不存在""输入不合法"这类**可以提前知道**的失败挡在停服之前。）
 *
 * <h2>三、返回值</h2>
 * 仍然是 {@link ShanhaiPlayerDataEditor.Result}：{@code ShanhaiSaveEditReload} 只认这个类型
 * （它要 {@code ok()/line()/changed()/write().backupPath()}）。三页的细节折进
 * {@code changed/notes/error} 三个字段，日志里都能逐条读到。
 */
public final class ShanhaiSaveEditWindow {

    /** 日志前缀。 */
    public static final String PREFIX = ShanhaiSaveEditFlow.PREFIX;

    private ShanhaiSaveEditWindow() {}

    /**
     * 三页一起落盘。<b>调用方必须先过 {@link ShanhaiSaveEditFlow#tryApply(String)}。</b>
     *
     * @param playerDataFile {@code <存档>/playerdata/<uuid>.dat}（存档根由它推）
     */
    public static ShanhaiPlayerDataEditor.Result apply(Path playerDataFile, ShanhaiPlayerDataEdit edit,
                                                       Path backupRoot, String stamp) {
        ShanhaiSaveEditStorage.Transaction tx = ShanhaiSaveEditStorage.beginTransaction();
        ShanhaiPlayerDataEditor.Result result;
        try {
            result = applyInternal(playerDataFile, edit, backupRoot, stamp);
        } catch (Throwable failure) {
            List<Path> failures = tx.rollback();
            return new ShanhaiPlayerDataEditor.Result(false,
                    "write_exception:" + failure + (failures.isEmpty()
                            ? " rollback_complete" : " rollback_failed backups=" + failures),
                    List.of(), List.of(), null);
        }
        if (result.ok()) {
            tx.commit();
            return result;
        }
        List<Path> failures = tx.rollback();
        String suffix = failures.isEmpty() ? " rollback_complete" : " rollback_failed backups=" + failures;
        return new ShanhaiPlayerDataEditor.Result(false, result.error() + suffix,
                failures.isEmpty() ? List.of() : result.changed(), result.notes(), result.write());
    }

    private static ShanhaiPlayerDataEditor.Result applyInternal(Path playerDataFile, ShanhaiPlayerDataEdit edit,
                                                               Path backupRoot, String stamp) {
        final List<String> notes = new ArrayList<>();
        if (edit == null) {
            return new ShanhaiPlayerDataEditor.Result(false, "null_edit", List.of(), notes, null);
        }
        if (!edit.errors.isEmpty()) {
            return new ShanhaiPlayerDataEditor.Result(false, "invalid_edit:" + edit.errors, List.of(), notes, null);
        }
        // 二/三页自己的错误也必须在动任何文件之前挡住（面板会 absorb，这里再兜一层）
        if (edit.levelEdit != null && edit.levelEdit.errors != null && !edit.levelEdit.errors.isEmpty()) {
            return new ShanhaiPlayerDataEditor.Result(false, "invalid_level_edit:" + edit.levelEdit.errors,
                    List.of(), notes, null);
        }
        final Path worldDir = ShanhaiLevelDataEditor.worldDirOfPlayerData(playerDataFile);
        final Path levelDat = ShanhaiLevelDataEditor.levelDatOf(worldDir);

        // ── ① 预检（挡在停服之前：能提前知道的失败，绝不等到改盘那一步）
        if (edit.hasLevel()) {
            if (levelDat == null || !Files.isRegularFile(levelDat)) {
                return new ShanhaiPlayerDataEditor.Result(false,
                        "level_dat_missing:" + levelDat, List.of(), notes, null);
            }
        }
        if (edit.hasRegion()) {
            final Path regionFile = ShanhaiRegionEdit.regionFile(worldDir, edit.regionRequest.dimension(),
                    edit.regionRequest.x(), edit.regionRequest.z());
            if (regionFile == null) {
                return new ShanhaiPlayerDataEditor.Result(false,
                        "region_path_unresolved:" + edit.regionRequest.line(), List.of(), notes, null);
            }
            if (!Files.isRegularFile(regionFile)) {
                return new ShanhaiPlayerDataEditor.Result(false,
                        ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.9bbd3e34286fbaac") + regionFile, List.of(), notes, null);
            }
        }
        // 累积清单：一次窗口里可能有好几条（暂存到"关闭面板"才清）。下面每段都是"逐条落盘"。
        final List<ShanhaiEntityEdit.Request> entityReqs = edit.effectiveEntityRequests();
        final List<ShanhaiPlayerDataEdit.EntityPatchTarget> patchTargets = edit.effectiveEntityPatchTargets();
        final List<ShanhaiAdvancementEdit.Request> advReqs = edit.effectiveAdvancementRequests();
        final List<ShanhaiStatsEdit.Request> statsReqs = edit.effectiveStatsRequests();
        final List<com.dishanhai.gt_shanhai.common.saveedit.audit.ShanhaiSaveEditAuditFix.Request> auditReqs =
                edit.effectiveAuditRequests();
        for (ShanhaiEntityEdit.Request req : entityReqs) {
            final Path entityFile = ShanhaiSaveEditDimension.entitiesFile(worldDir,
                    req.dimension(), req.regionX(), req.regionZ());
            if (entityFile == null) {
                return new ShanhaiPlayerDataEditor.Result(false,
                        "entities_path_unresolved:" + req.line(), List.of(), notes, null);
            }
            if (!Files.isRegularFile(entityFile)) {
                return new ShanhaiPlayerDataEditor.Result(false,
                        ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.6823c323153eb301") + entityFile, List.of(), notes, null);
            }
        }
        // 成就/统计按<b>玩家</b>存（在存档根下），文件名就是 playerdata 去掉 .dat 的那个 uuid
        final String playerUuid = playerUuidOf(playerDataFile);
        for (ShanhaiAdvancementEdit.Request req : advReqs) {
            final Path advFile = ShanhaiAdvancementEdit.fileOf(worldDir, playerUuid);
            if (advFile == null) {
                return new ShanhaiPlayerDataEditor.Result(false,
                        "advancements_path_unresolved(uuid=" + playerUuid + ")", List.of(), notes, null);
            }
            if (!Files.isRegularFile(advFile)) {
                return new ShanhaiPlayerDataEditor.Result(false,
                        ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.3f34ca84a9600038") + advFile,
                        List.of(), notes, null);
            }
        }
        for (ShanhaiStatsEdit.Request req : statsReqs) {
            final Path statsFile = ShanhaiStatsEdit.fileOf(worldDir, playerUuid);
            if (statsFile == null) {
                return new ShanhaiPlayerDataEditor.Result(false,
                        "stats_path_unresolved(uuid=" + playerUuid + ")", List.of(), notes, null);
            }
            if (!Files.isRegularFile(statsFile)) {
                return new ShanhaiPlayerDataEditor.Result(false,
                        ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.b55fd1fc22d8885f") + statsFile,
                        List.of(), notes, null);
            }
        }
        // ⑧ 体检页（第七页）：窗口里才写；能提前知道的失败一律挡在停服之前
        for (com.dishanhai.gt_shanhai.common.saveedit.audit.ShanhaiSaveEditAuditFix.Request req : auditReqs) {
            final String why = com.dishanhai.gt_shanhai.common.saveedit.audit.ShanhaiSaveEditAuditFix.precheck(worldDir, req);
            if (why != null) {
                return new ShanhaiPlayerDataEditor.Result(false, "audit_precheck_failed:" + why,
                        List.of(), notes, null);
            }
        }
        // ⑤b 实体字段（第五期续：第 ④ 页覆盖层「写入」）：每一个目标（维度 + UUID）都必须在停服之前就齐
        final String patchWhy = edit.entityPatchTargetsProblem();
        if (patchWhy != null) {
            return new ShanhaiPlayerDataEditor.Result(false, "entity_patch_precheck_failed:" + patchWhy,
                    List.of(), notes, null);
        }

        final List<String> changed = new ArrayList<>();
        final LinkedHashSet<String> written = new LinkedHashSet<>();
        ShanhaiSaveEditStorage.WriteResult lastWrite = null;

        // ── ② 玩家页（一期原样；只有真的有字段时才动）
        if (edit.hasPlayerFields()) {
            final ShanhaiPlayerDataEditor.Result r =
                    ShanhaiPlayerDataEditor.applyPlayerOnly(playerDataFile, edit, backupRoot, stamp);
            if (r.write() != null) {
                lastWrite = r.write();
            }
            if (!r.ok()) {
                return new ShanhaiPlayerDataEditor.Result(false,
                        "player_page_failed:" + r.error(), r.changed(), merge(notes, r.notes()), lastWrite);
            }
            changed.addAll(r.changed());
            notes.addAll(nz(r.notes()));
            written.add(ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.dddc632c2c4eb7f4") + r.changed().size() + ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.86f651cc8866d21f"));
        }

        // ── ③ 世界页
        if (edit.hasLevel()) {
            final ShanhaiLevelDataEditor.Result r =
                    ShanhaiLevelDataEditor.apply(levelDat, edit.levelEdit, backupRoot, stamp);
            if (r.write() != null) {
                lastWrite = r.write();
            }
            if (!r.ok()) {
                return new ShanhaiPlayerDataEditor.Result(false,
                        "level_page_failed:" + r.error() + ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.75a5d37ccfc51e0c") + written + "]", r.changed(),
                        merge(notes, r.notes()), lastWrite);
            }
            for (String c : r.changed()) {
                changed.add("level.dat:" + c);
            }
            notes.addAll(nz(r.notes()));
            written.add(ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.4c0c68ae721b412b") + r.changed().size() + ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.86f651cc8866d21f"));
        }

        // ── ④ 方块页
        if (edit.hasRegion()) {
            final ShanhaiRegionEdit.Result r = ShanhaiRegionEdit.apply(worldDir, edit.regionRequest,
                    backupRoot, stamp);
            if (r.write() != null) {
                lastWrite = r.write();
            }
            if (!r.ok()) {
                return new ShanhaiPlayerDataEditor.Result(false,
                        "region_page_failed:" + r.error() + ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.75a5d37ccfc51e0c") + written + "]", r.changed(),
                        merge(notes, r.notes()), lastWrite);
            }
            for (String c : r.changed()) {
                changed.add("region:" + c);
            }
            notes.addAll(nz(r.notes()));
            written.add(ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.6a5b7873b723e9e8") + r.changed().size() + ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.86f651cc8866d21f"));
        }

        // ── ⑤ 实体页（第四期：entities/*.mca，只删不编辑）—— 累积清单逐条落盘
        int entityDone = 0;
        for (ShanhaiEntityEdit.Request req : entityReqs) {
            final ShanhaiEntityEdit.Result r = ShanhaiEntityEdit.apply(worldDir, req, backupRoot, stamp);
            if (r.write() != null) {
                lastWrite = r.write();
            }
            if (!r.ok()) {
                return new ShanhaiPlayerDataEditor.Result(false,
                        "entity_page_failed:" + r.error() + ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.89bb11b030bc8fff") + entityDone + "/" + entityReqs.size()
                                + ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.75a5d37ccfc51e0c") + written + "]", r.changed(),
                        merge(notes, r.notes()), lastWrite);
            }
            for (String c : r.changed()) {
                changed.add("entity:" + c);
            }
            notes.addAll(nz(r.notes()));
            entityDone++;
        }
        if (!entityReqs.isEmpty()) {
            written.add(ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.7b53c7fe89fbfb15") + entityDone + ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.86f651cc8866d21f"));
        }

        // ── ⑤b 实体字段（第五期续：第 ④ 页覆盖层「写入」的 10 项；逐个目标落盘）
        //    与 ⑤ 的区别：⑤ 是"删一条"（entityRequest），⑤b 是"改若干项"（entityPatch）
        //    数据层自己会做：按维度+UUID 定位、写前自检、原子写、写后回读、其它区块 sha 不变。
        int patchDone = 0;
        for (ShanhaiPlayerDataEdit.EntityPatchTarget target : patchTargets) {
            final ShanhaiEntityEdit.WriteOutcome o = ShanhaiEntityEdit.writeFields(worldDir,
                    target.dimension(), target.uuid(), target.patch(), backupRoot, stamp);
            if (o.write() != null) {
                lastWrite = o.write();
            }
            if (!o.ok()) {
                return new ShanhaiPlayerDataEditor.Result(false,
                        "entity_patch_failed:" + o.error() + ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.89bb11b030bc8fff") + patchDone + "/" + patchTargets.size()
                                + ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.75a5d37ccfc51e0c") + written + "]", o.changed(),
                        merge(notes, o.notes()), lastWrite);
            }
            for (String c : o.changed()) {
                changed.add("entity_patch:" + c);
            }
            notes.addAll(nz(o.notes()));
            // 🔴 dangers 必须进日志：例如"Item.id 改成了未知 id ⇒ 这条实体可能被丢弃（未实测）"
            //    —— 面板读过一次，但落盘窗口里的这一份才是真正负责的那一份。
            notes.addAll(nz(o.dangers()));
            patchDone++;
        }
        if (!patchTargets.isEmpty()) {
            written.add(ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.4ce94a5b97e6e992") + patchDone + ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.839bbdd42a70aff5"));
        }

        // ── ⑥ 成就页（第四期：advancements/<uuid>.json，纯 JSON）—— 累积清单逐条落盘
        int advDone = 0;
        for (ShanhaiAdvancementEdit.Request req : advReqs) {
            final ShanhaiAdvancementEdit.Result r = ShanhaiAdvancementEdit.apply(worldDir, playerUuid,
                    req, backupRoot, stamp);
            if (r.write() != null) {
                lastWrite = r.write();
            }
            if (!r.ok()) {
                return new ShanhaiPlayerDataEditor.Result(false,
                        "advancement_page_failed:" + r.error() + ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.89bb11b030bc8fff") + advDone + "/" + advReqs.size()
                                + ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.75a5d37ccfc51e0c") + written + "]", r.changed(),
                        merge(notes, r.notes()), lastWrite);
            }
            for (String c : r.changed()) {
                changed.add("advancement:" + c);
            }
            notes.addAll(nz(r.notes()));
            advDone++;
        }
        if (!advReqs.isEmpty()) {
            written.add(ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.04cc84365b33dd67") + advDone + ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.86f651cc8866d21f"));
        }

        // ── ⑦ 统计页（第四期：stats/<uuid>.json，纯 JSON）—— 累积清单逐条落盘
        int statsDone = 0;
        for (ShanhaiStatsEdit.Request req : statsReqs) {
            final ShanhaiStatsEdit.Result r = ShanhaiStatsEdit.apply(worldDir, playerUuid,
                    req, backupRoot, stamp);
            if (r.write() != null) {
                lastWrite = r.write();
            }
            if (!r.ok()) {
                return new ShanhaiPlayerDataEditor.Result(false,
                        "stats_page_failed:" + r.error() + ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.89bb11b030bc8fff") + statsDone + "/" + statsReqs.size()
                                + ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.75a5d37ccfc51e0c") + written + "]", r.changed(),
                        merge(notes, r.notes()), lastWrite);
            }
            for (String c : r.changed()) {
                changed.add("stats:" + c);
            }
            notes.addAll(nz(r.notes()));
            statsDone++;
        }
        if (!statsReqs.isEmpty()) {
            written.add(ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.cf5f738be5cb1379") + statsDone + ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.86f651cc8866d21f"));
        }

        // ── ⑧ 体检页（第七页 · 双份登记：删区块里那份 / 删旁挂那行）—— 累积清单逐条落盘
        int auditDone = 0;
        int auditEntities = 0;
        int auditLines = 0;
        for (com.dishanhai.gt_shanhai.common.saveedit.audit.ShanhaiSaveEditAuditFix.Request req : auditReqs) {
            final com.dishanhai.gt_shanhai.common.saveedit.audit.ShanhaiSaveEditAuditFix.Result r =
                    com.dishanhai.gt_shanhai.common.saveedit.audit.ShanhaiSaveEditAuditFix.applyRequestInWindow(
                            worldDir, req, backupRoot, stamp);
            if (r.write() != null) {
                lastWrite = r.write();
            }
            if (!r.ok()) {
                return new ShanhaiPlayerDataEditor.Result(false,
                        "audit_page_failed:" + r.reason() + " " + r.message()
                                + ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.89bb11b030bc8fff") + auditDone + "/" + auditReqs.size()
                                + ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.75a5d37ccfc51e0c") + written + "]",
                        r.files(), merge(notes, r.notes()), lastWrite);
            }
            for (String f : r.files()) {
                changed.add("audit:" + f);
            }
            notes.addAll(nz(r.notes()));
            auditEntities += r.removedEntities();
            auditLines += r.removedLines();
            auditDone++;
        }
        if (!auditReqs.isEmpty()) {
            written.add(ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.b9727286d9ec55a2") + auditDone + ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.bed7737121d52af0") + auditEntities + ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.3e3a04f74c8f0410") + auditLines + ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.0b73010a26fbd364"));
        }

        if (changed.isEmpty()) {
            return new ShanhaiPlayerDataEditor.Result(false, "no_effective_change", List.of(), notes, lastWrite);
        }
        notes.add(ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.82ca95e215cfacba") + String.join(" + ", written));
        return new ShanhaiPlayerDataEditor.Result(true, "", changed, notes, lastWrite);    }

    /** {@code <存档>/playerdata/<uuid>.dat} 里的 {@code <uuid>}（拿不到 ⇒ 空串）。 */
    public static String playerUuidOf(java.nio.file.Path playerDataFile) {
        if (playerDataFile == null || playerDataFile.getFileName() == null) {
            return "";
        }
        final String name = playerDataFile.getFileName().toString();
        return name.endsWith(".dat") ? name.substring(0, name.length() - 4) : name;
    }

    /** {@code null} 安全的 notes 合并。 */
    private static List<String> merge(List<String> base, List<String> extra) {
        final List<String> out = new ArrayList<>(base == null ? List.of() : base);
        out.addAll(nz(extra));
        return out;
    }

    private static List<String> nz(List<String> list) {
        return list == null ? List.of() : list;
    }
}
