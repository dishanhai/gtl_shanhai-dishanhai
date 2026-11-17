package com.shanhai.common.saveedit;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

/**
 * 存档直改的<b>写盘纪律</b>（纯 JDK：{@code java.nio.file} + {@code MessageDigest}）。
 *
 * <h2>一、为什么是"备份 → 同目录 .tmp → ATOMIC_MOVE"</h2>
 * 照本工程既有的 {@code ShanhaiRealityInputStore#writeMap}（{@code :359-372}）那一套：
 * <ol>
 *   <li><b>先备份</b>：把目标文件的<b>原始字节</b>拷进
 *       {@code <备份根>\<时间戳>\<文件名>}，然后<b>把备份读回来算哈希并与原文件哈希比对</b>。
 *       不相等 ⇒ 立刻中止，一个字节都不写（"备份了但备份是坏的那就没备份"）。</li>
 *   <li><b>写同目录 .tmp</b>：绝不原地覆盖目标。判据靠 {@code tmpCreated} 观察，不靠调用方自述。</li>
 *   <li><b>ATOMIC_MOVE 覆盖</b>：失败退回 {@code REPLACE_EXISTING}（Windows 上跨卷/被占用时的兜底，
 *       与既有实现同口径）。</li>
 *   <li><b>写完回读校验</b>：目标文件的 sha256 必须等于我们写进去的内容的 sha256；
 *       内容本身还要由调用方再解析一次（NBT 能解、字段只变了预期的那些）。</li>
 * </ol>
 *
 * <h2>二、🔴 只说"我备份了"不算数</h2>
 * {@link WriteResult} 的每个布尔位都是<b>从文件系统观察出来的事实</b>：
 * {@code tmpCreated} = 我们真的建过那个 .tmp；{@code tmpVanished} = 操作结束后它真的没了
 * （被 move 吃掉了）；{@code targetSha256After} = 真的从盘上读回来的哈希。
 * ⇒ <b>把原子写换成"直接覆盖"时，{@code tmpCreated/tmpVanished} 必然是 false</b>，
 * 判据 ③ 当场变红 —— 这就是负对照能生效的原因（见 {@link #criterion3(WriteResult)}）。
 *
 * <h2>三、备份目录在哪</h2>
 * 用户口径：{@code temp\saveedit-backup\<时间戳>\}。
 * <ul>
 *   <li>游戏内：{@code <游戏根目录>\temp\saveedit-backup\<时间戳>\}（游戏根目录取自
 *       {@code MinecraftServer#getServerDirectory()}）；</li>
 *   <li>离线判据：{@code <仓库>\temp\saveedit-backup\<时间戳>\}（探针自己传根目录）。</li>
 * </ul>
 */
public final class ShanhaiSaveEditStorage {

    /** 临时文件后缀（同目录，保证 ATOMIC_MOVE 不跨卷）。 */
    public static final String TMP_SUFFIX = ".shanhai-tmp";

    private ShanhaiSaveEditStorage() {}

    /** 一次备份的读数。 */
    public record Backup(Path path, String originalSha256, String backupSha256, long bytes) {
        /** 备份是否可信：文件存在 + 两份哈希逐字符相同。 */
        public boolean trusted() {
            return this.path != null && Files.isRegularFile(this.path)
                    && this.originalSha256 != null && this.originalSha256.equals(this.backupSha256);
        }

        public String line() {
            return "backup=" + (this.path == null ? "-" : this.path.getFileName().toString())
                    + " bytes=" + this.bytes
                    + " orig_sha=" + shortSha(this.originalSha256)
                    + " backup_sha=" + shortSha(this.backupSha256)
                    + " trusted=" + trusted();
        }
    }

    /** 一次写盘的读数（每一位都是观察出来的，不是调用方自述的）。 */
    public record WriteResult(boolean ok, boolean tmpCreated, boolean tmpVanished,
                              Path backupPath, String originalSha256, String backupSha256,
                              String contentSha256, String targetSha256After, String error) {
        public String line() {
            return "ok=" + this.ok
                    + " tmp_created=" + this.tmpCreated
                    + " tmp_vanished=" + this.tmpVanished
                    + " backup=" + (this.backupPath == null ? "-" : this.backupPath.getFileName().toString())
                    + " orig_sha=" + shortSha(this.originalSha256)
                    + " backup_sha=" + shortSha(this.backupSha256)
                    + " content_sha=" + shortSha(this.contentSha256)
                    + " target_sha_after=" + shortSha(this.targetSha256After)
                    + (this.error == null || this.error.isEmpty() ? "" : " error=" + this.error);
        }
    }

    /** 判据 ③ 的读数。 */
    public record Criterion3(boolean pass, boolean backupExists, boolean backupHashEqualsOriginal,
                             boolean atomicWitnessed, boolean contentLanded, String detail) {
        public String line() {
            return "criterion3 pass=" + this.pass
                    + " backup_exists=" + this.backupExists
                    + " backup_hash_eq_original=" + this.backupHashEqualsOriginal
                    + " atomic_witnessed=" + this.atomicWitnessed
                    + " content_landed=" + this.contentLanded
                    + (this.detail == null || this.detail.isEmpty() ? "" : " detail=" + this.detail);
        }
    }

    // ================================================================== 基础件

    /** 目标文件对应的临时文件路径（同目录 + 固定后缀）。 */
    public static Path tmpPathFor(Path target) {
        return target.resolveSibling(target.getFileName().toString() + TMP_SUFFIX);
    }

    /** 文件的 sha256（十六进制小写）；文件不存在返回 {@code null}。 */
    public static String sha256(Path file) {
        if (file == null || !Files.isRegularFile(file)) {
            return null;
        }
        try {
            final MessageDigest md = MessageDigest.getInstance("SHA-256");
            try (java.io.InputStream in = Files.newInputStream(file)) {
                final byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) {
                    md.update(buf, 0, n);
                }
            }
            return hex(md.digest());
        } catch (Throwable t) {
            return null;
        }
    }

    /** 字节数组的 sha256。 */
    public static String sha256(byte[] content) {
        if (content == null) {
            return null;
        }
        try {
            return hex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (Throwable t) {
            return null;
        }
    }

    private static String hex(byte[] digest) {
        final StringBuilder sb = new StringBuilder(digest.length * 2);
        for (byte b : digest) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    static String shortSha(String sha) {
        return sha == null ? "-" : (sha.length() <= 12 ? sha : sha.substring(0, 12));
    }

    // ================================================================== 备份

    /**
     * 把 {@code target} 的原始字节备份到 {@code backupRoot/<stamp>/}，并当场回读校验哈希。
     *
     * @throws IOException 备份失败 / 哈希对不上（两种都算"不许继续写"）
     */
    public static Backup backup(Path target, Path backupRoot, String stamp) throws IOException {
        if (target == null || !Files.isRegularFile(target)) {
            throw new IOException("target_not_a_file: " + target);
        }
        final String originalSha = sha256(target);
        if (originalSha == null) {
            throw new IOException("original_sha_failed: " + target);
        }
        final String safeStamp = (stamp == null || stamp.isEmpty()) ? "nostamp" : stamp;
        final Path dir = backupRoot.resolve(safeStamp);
        Files.createDirectories(dir);
        final Path backupFile = dir.resolve(target.getFileName().toString());
        Files.copy(target, backupFile, StandardCopyOption.REPLACE_EXISTING);
        final String backupSha = sha256(backupFile);
        final long bytes = Files.size(target);
        final Backup backup = new Backup(backupFile, originalSha, backupSha, bytes);
        if (!originalSha.equals(backupSha)) {
            throw new IOException("backup_hash_mismatch: orig=" + shortSha(originalSha)
                    + " backup=" + shortSha(backupSha));
        }
        return backup;
    }

    /** 时间戳目录名（只给备份目录用；同时进日志，便于人找）。 */
    public static String stamp(long epochMillis) {
        return new java.text.SimpleDateFormat("yyyyMMdd-HHmmss-SSS").format(new java.util.Date(epochMillis));
    }

    // ================================================================== 原子写

    /** 把 {@code tmp} 原子搬到 {@code target}（失败退回普通替换；两条都失败才算失败）。 */
    public static boolean commit(Path tmp, Path target) {
        try {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            return true;
        } catch (Throwable atomicFailed) {
            try {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
                return true;
            } catch (Throwable t) {
                return false;
            }
        }
    }

    /**
     * 完整的一次"备份 + 原子写 + 回读"。
     *
     * <p>🔴 本方法<b>不</b>判窗口：窗口判据在 {@link ShanhaiSaveEditFlow#tryApply(String)}，
     * 调用方必须先拿到放行才允许进这里（生产路径唯一调用点
     * {@code ShanhaiSaveEditReload#applyOnIo}，它前面就是 {@code tryApply}）。
     */
    public static WriteResult writeAtomic(Path target, byte[] content, Path backupRoot, String stamp) {
        if (target == null || content == null) {
            return new WriteResult(false, false, false, null, null, null, null, null, "null_arg");
        }
        final String contentSha = sha256(content);
        Backup backup = null;
        final Path tmp = tmpPathFor(target);
        boolean tmpCreated = false;
        boolean tmpVanished = false;
        try {
            backup = backup(target, backupRoot, stamp);
        } catch (Throwable t) {
            // 备份拿不到 ⇒ 一个字节都不写（这是判据 ③ 的第一段）
            return new WriteResult(false, false, false, null, null, null, contentSha, null,
                    "backup_failed:" + t);
        }
        try {
            Files.write(tmp, content);
            tmpCreated = Files.isRegularFile(tmp);
            if (!tmpCreated) {
                throw new IOException("tmp_not_created: " + tmp);
            }
            if (!commit(tmp, target)) {
                throw new IOException("commit_failed: " + tmp + " -> " + target);
            }
            tmpVanished = !Files.exists(tmp);
        } catch (Throwable t) {
            try {
                Files.deleteIfExists(tmp);
            } catch (Throwable ignored) {
                // 删不掉也不改结论：目标文件没被碰过
            }
            return new WriteResult(false, tmpCreated, false, backup.path(), backup.originalSha256(),
                    backup.backupSha256(), contentSha, sha256(target), "write_failed:" + t);
        }
        final String after = sha256(target);
        final boolean ok = contentSha != null && contentSha.equals(after);
        return new WriteResult(ok, tmpCreated, tmpVanished, backup.path(), backup.originalSha256(),
                backup.backupSha256(), contentSha, after, ok ? "" : "readback_mismatch");
    }

    /** 用备份把目标原样盖回去（回读校验失败时用；尽力而为，返回值只进日志）。 */
    public static boolean restore(Path backupFile, Path target) {
        if (backupFile == null || target == null || !Files.isRegularFile(backupFile)) {
            return false;
        }
        try {
            Files.copy(backupFile, target, StandardCopyOption.REPLACE_EXISTING);
            final String a = sha256(backupFile);
            final String b = sha256(target);
            return a != null && a.equals(b);
        } catch (Throwable t) {
            return false;
        }
    }

    // ================================================================== 判据 ③（离线）

    /**
     * <b>判据 ③</b>：写之前必须有备份文件，且备份哈希 = 原文件哈希；再加上"原子写真的发生了"。
     *
     * <p>四个位：
     * <ol>
     *   <li>{@code backupExists} —— 备份文件在盘上存在；</li>
     *   <li>{@code backupHashEqualsOriginal} —— 两份 sha256 逐字符相同；</li>
     *   <li>{@code atomicWitnessed} —— {@code tmpCreated && tmpVanished}
     *       （<b>直接覆盖的实现这两个位必然是 false</b> ⇒ 负对照在这里变红）；</li>
     *   <li>{@code contentLanded} —— 写完之后从盘上读回来的哈希 = 新内容的哈希。</li>
     * </ol>
     */
    public static Criterion3 criterion3(WriteResult r) {
        if (r == null) {
            return new Criterion3(false, false, false, false, false, "no_result");
        }
        final boolean exists = r.backupPath() != null && Files.isRegularFile(r.backupPath());
        final boolean hashEq = r.originalSha256() != null && r.originalSha256().equals(r.backupSha256());
        final boolean atomic = r.tmpCreated() && r.tmpVanished();
        final boolean landed = r.contentSha256() != null && r.contentSha256().equals(r.targetSha256After());
        final List<String> why = new ArrayList<>();
        if (!exists) why.add("backup_missing");
        if (!hashEq) why.add("backup_hash_ne_original");
        if (!atomic) why.add("not_atomic(tmp_created=" + r.tmpCreated() + ",tmp_vanished=" + r.tmpVanished() + ")");
        if (!landed) why.add("content_not_on_disk");
        final boolean pass = r.ok() && exists && hashEq && atomic && landed;
        return new Criterion3(pass, exists, hashEq, atomic, landed, String.join("+", why));
    }
}
