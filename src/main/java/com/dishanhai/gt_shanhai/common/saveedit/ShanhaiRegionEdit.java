package com.dishanhai.gt_shanhai.common.saveedit;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.TagParser;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 「存档直改 · 第三期」：<b>区块里的一个方块 / 一个方块实体</b>。
 *
 * <h2>一、一次编辑的完整流程（每一步都可以单独判）</h2>
 * <pre>
 *   维度 + x/y/z  ──▶ region 文件（r.&lt;rx&gt;.&lt;rz&gt;.mca）与槽位
 *        │            文件不存在 / 偏移表 0（未生成）⇒ 🔴 拒绝，绝不凭空生成区块
 *        ▼
 *   读盘 → 解压目标区块 → NBT（before）
 *        │  copy 成 after → 只改目标位置（{@link ShanhaiChunkEditor}）
 *        ▼
 *   回读校验（写盘前先在内存里验）：只有那一格变、其余 NBT 逐条等价
 *        │  不通过 ⇒ 一个字节都不写
 *        ▼
 *   编码 → zlib → {@link ShanhaiRegionFile#withChunk}（clone 原文件，只覆盖目标槽位）
 *        │  表格自检（{@link ShanhaiRegionFile#validate}）不通过 ⇒ 不写
 *        ▼
 *   {@link ShanhaiSaveEditStorage#writeAtomic}（备份 → tmp → ATOMIC_MOVE → 回读哈希）
 *        ▼
 *   从盘上重新解压目标区块：diff(before, ondisk) 必须逐条等于预期
 *   ＋ 其余 1023 个槽位的<b>压缩字节 sha256</b> 与写前逐槽相同
 * </pre>
 *
 * <h2>二、🔴 只在 STOPPED 窗口内写</h2>
 * 与一/二期同一条铁律：本类<b>不</b>判窗口，调用方必须先过
 * {@link ShanhaiSaveEditFlow#tryApply(String)}（生产路径在
 * {@code ShanhaiSaveEditWindow}，它由 {@code ShanhaiSaveEditReload#applyOnIo} 驱动）。
 *
 * <h2>三、本期已知边界（都会明确拒绝，不会静默）</h2>
 * <ul>
 *   <li>区块未生成（偏移表 0）⇒ 拒；</li>
 *   <li>region 文件本身不存在 / 目标槽位结构坏了 ⇒ 拒；</li>
 *   <li>Y 超出这个维度的高度范围 ⇒ 拒；</li>
 *   <li>方块 id / 状态串形状不对 ⇒ 拒（游戏里面板还会查注册表）；</li>
 *   <li>方块实体第一版只做「清空」与「整块替换」，不做逐字段编辑；</li>
 *   <li>自定义维度的高度范围只能从该区块已有的 section 推（推不出来就拒）。</li>
 * </ul>
 */
public final class ShanhaiRegionEdit {

    /** 日志前缀。 */
    public static final String PREFIX = ShanhaiSaveEditFlow.PREFIX;

    /** 三个内置维度（值来自 {@link ShanhaiSaveEditDimension} —— 映射的唯一来源）。 */
    public static final String DIM_OVERWORLD = ShanhaiSaveEditDimension.OVERWORLD;
    public static final String DIM_NETHER = ShanhaiSaveEditDimension.NETHER;
    public static final String DIM_END = ShanhaiSaveEditDimension.END;

    /** 方块实体动作。 */
    public static final String BE_NONE = "none";
    public static final String BE_CLEAR = "clear";
    public static final String BE_REPLACE = "replace";

    private ShanhaiRegionEdit() {}

    // ================================================================== 请求

    /**
     * 一次区块编辑请求。
     *
     * @param dimension          维度（{@code minecraft:overworld}/{@code the_nether}/{@code the_end} 或自定义 id）
     * @param blockId            目标方块 id（空 = 不改方块）
     * @param blockState         目标方块的属性串（可空，如 {@code facing=north,waterlogged=false}）
     * @param beAction           {@link #BE_NONE}/{@link #BE_CLEAR}/{@link #BE_REPLACE}
     * @param beSnbt             {@code beAction=replace} 时的整块 NBT（SNBT）
     * @param blockHasBlockEntity 游戏里知道"新方块本身带方块实体"时为 true（面板查 {@code BlockState#hasBlockEntity()}）；
     *                           为 false 且没有 BE 动作时，会把目标位置原有的方块实体顺手清掉
     */
    public record Request(String dimension, int x, int y, int z, String blockId, String blockState,
                          String beAction, String beSnbt, boolean blockHasBlockEntity) {

        /** 要不要改方块。 */
        public boolean hasBlock() {
            return this.blockId != null && !this.blockId.trim().isEmpty();
        }

        /** 要不要动方块实体。 */
        public boolean hasBe() {
            return this.beAction != null
                    && (this.beAction.equals(BE_CLEAR) || this.beAction.equals(BE_REPLACE));
        }

        /** 什么都没填。 */
        public boolean isEmpty() {
            return !hasBlock() && !hasBe();
        }

        /** 一行读数。 */
        public String line() {
            return "dim=" + this.dimension + " pos=(" + this.x + "," + this.y + "," + this.z + ")"
                    + " block=" + (hasBlock() ? (this.blockId + (this.blockState == null || this.blockState.isEmpty()
                    ? "" : "[" + this.blockState + "]")) : "-")
                    + " be=" + (this.beAction == null ? BE_NONE : this.beAction)
                    + (this.blockHasBlockEntity ? " block_has_be=true" : "");
        }
    }

    /** 一次编辑的结果。 */
    public record Result(boolean ok, String error, List<String> changed, List<String> notes,
                         ShanhaiSaveEditStorage.WriteResult write, String chunkLine, String tableLine) {
        public String line() {
            return "ok=" + this.ok
                    + (this.error == null || this.error.isEmpty() ? "" : " error=" + this.error)
                    + " changed=" + this.changed
                    + (this.notes == null || this.notes.isEmpty() ? "" : " notes=" + this.notes)
                    + " chunk=[" + (this.chunkLine == null ? "" : this.chunkLine) + "]"
                    + " table=[" + (this.tableLine == null ? "" : this.tableLine) + "]"
                    + (this.write == null ? "" : " | " + this.write.line());
        }
    }

    // ================================================================== 维度 / 路径

    /**
     * 维度 id 规范化（{@code overworld} 之类口语写法也认）；不认识 ⇒ {@code null}。
     *
     * <p>🔴 第四期起<b>委托</b> {@link ShanhaiSaveEditDimension#normalize(String)}
     * —— 方块、实体、面板三处必须认同一套写法，映射规则只许有一份。
     */
    public static String normalizeDimension(String raw) {
        return ShanhaiSaveEditDimension.normalize(raw);
    }

    /**
     * 维度对应的 region 目录。
     *
     * <ul>
     *   <li>主世界：{@code <存档>/region}</li>
     *   <li>下界：{@code <存档>/DIM-1/region}</li>
     *   <li>末地：{@code <存档>/DIM1/region}</li>
     *   <li>自定义：{@code <存档>/dimensions/<ns>/<path>/region}</li>
     * </ul>
     * 拼接本身在 {@link ShanhaiSaveEditDimension#regionDir(Path, String)}（唯一映射处）。
     */
    public static Path regionDir(Path worldDir, String dimension) {
        return ShanhaiSaveEditDimension.regionDir(worldDir, dimension);
    }

    /** 目标坐标对应的 region 文件（不保证存在）。 */
    public static Path regionFile(Path worldDir, String dimension, int x, int z) {
        final Path dir = ShanhaiSaveEditDimension.regionDir(worldDir, dimension);
        if (dir == null) {
            return null;
        }
        final int rx = ShanhaiRegionFile.regionCoord(x >> 4);
        final int rz = ShanhaiRegionFile.regionCoord(z >> 4);
        return dir.resolve(ShanhaiRegionFile.fileName(rx, rz));
    }

    /**
     * 这个维度允许的 Y 范围（闭区间）。
     *
     * <p>内置三维按原版：主世界 [-64, 319]，下界/末地 [0, 255]；自定义维度从该区块已有的
     * section 推（推不出来就返回 {@code null}，调用方拒绝）。
     */
    public static int[] yRange(CompoundTag chunk, String dimension) {
        final String dim = normalizeDimension(dimension);
        if (DIM_OVERWORLD.equals(dim)) {
            return new int[] {-64, 319};
        }
        if (DIM_NETHER.equals(dim) || DIM_END.equals(dim)) {
            return new int[] {0, 255};
        }
        if (chunk == null) {
            return null;
        }
        final var sections = chunk.getList(ShanhaiChunkEditor.SECTIONS, 10);
        if (sections.isEmpty()) {
            return null;
        }
        int min = Integer.MAX_VALUE;
        int max = Integer.MIN_VALUE;
        for (int i = 0; i < sections.size(); i++) {
            final int sy = sections.getCompound(i).getByte("Y");
            min = Math.min(min, sy);
            max = Math.max(max, sy);
        }
        return new int[] {min * 16, max * 16 + 15};
    }

    /** 一行 Y 范围说明（错误信息用）。 */
    public static String yRangeText(int[] range) {
        return range == null ? ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.4d8c1c5b42830791") : "[" + range[0] + ", " + range[1] + "]";
    }

    // ================================================================== 读当前

    /** 「读当前方块」的读数。 */
    public record Current(boolean ok, String block, String blockEntity, String error, String line) {
        public String display() {
            if (!this.ok) {
                return "§c" + this.error;
            }
            return this.block + (this.blockEntity == null || this.blockEntity.isEmpty()
                    ? "" : " §8+ §f" + this.blockEntity);
        }
    }

    /** 读一个坐标现在的方块（方块 + 方块实体 id）。**只读**：不创建文件、不生成区块。 */
    public static Current readCurrent(Path worldDir, Request req) {
        if (req == null) {
            return new Current(false, "", "", ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.bcc1971f5935199d"), "request=null");
        }
        final String dim = normalizeDimension(req.dimension());
        if (dim == null) {
            return new Current(false, "", "", ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.9d1ae4cd8436e6f7") + req.dimension(), "dim_invalid");
        }
        final Path file = regionFile(worldDir, dim, req.x(), req.z());
        if (file == null || !Files.isRegularFile(file)) {
            return new Current(false, "", "", ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.9a84d78e0f6fc60d")
                    + (file == null ? "-" : file.getFileName().toString()),
                    "region_missing path=" + file);
        }
        try {
            final ShanhaiRegionFile.Parse parse = ShanhaiRegionFile.parse(Files.readAllBytes(file));
            final int slot = ShanhaiRegionFile.slotOf(req.x() >> 4, req.z() >> 4);
            final ShanhaiRegionFile.Chunk chunk = parse.chunk(slot);
            if (chunk == null) {
                return new Current(false, "", "", ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.4d23180ddd09e804"),
                        "chunk_offset_zero slot=" + slot);
            }
            final CompoundTag tag = readChunk(chunk);
            final ShanhaiChunkEditor.Pos pos = new ShanhaiChunkEditor.Pos(req.x(), req.y(), req.z());
            final String block = ShanhaiChunkEditor.blockAt(tag, pos).toString();
            final String be = ShanhaiChunkEditor.blockEntityLine(tag, pos);
            return new Current(true, block, be, "",
                    "chunk_ok slot=" + slot + " block=" + block + (be.isEmpty() ? "" : " be=" + be));
        } catch (Throwable t) {
            return new Current(false, "", "", ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.f01922627c20191d") + t, "read_failed err=" + t);
        }
    }

    // ================================================================== 写

    /** 主入口：按世界目录 + 请求改一个方块 / 方块实体（会写盘）。 */
    public static Result apply(Path worldDir, Request req, Path backupRoot, String stamp) {
        if (worldDir == null || req == null) {
            return fail("null_arg");
        }
        final String dim = normalizeDimension(req.dimension());
        if (dim == null) {
            return fail(ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.9d1ae4cd8436e6f7") + req.dimension());
        }
        final Path file = regionFile(worldDir, dim, req.x(), req.z());
        if (file == null) {
            return fail(ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.625a0cc2384b59c9") + req.dimension() + "）");
        }
        if (!Files.isRegularFile(file)) {
            return fail(ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.9a84d78e0f6fc60d") + file);
        }
        return applyToFile(file, req, backupRoot, stamp);
    }

    /**
     * 直接对一个 region 文件做编辑（离线判据走这条：用副本，不碰真存档）。
     */
    public static Result applyToFile(Path regionFile, Request req, Path backupRoot, String stamp) {
        final List<String> notes = new ArrayList<>();
        final List<String> changed = new ArrayList<>();
        if (regionFile == null || req == null) {
            return fail("null_arg");
        }
        if (req.isEmpty()) {
            return fail("empty_request");
        }
        final int chunkX = req.x() >> 4;
        final int chunkZ = req.z() >> 4;
        final int slot = ShanhaiRegionFile.slotOf(chunkX, chunkZ);
        final byte[] beforeFile;
        final ShanhaiRegionFile.Parse beforeParse;
        final ShanhaiRegionFile.Chunk chunkRef;
        final CompoundTag before;
        try {
            beforeFile = Files.readAllBytes(regionFile);
            beforeParse = ShanhaiRegionFile.parse(beforeFile);
            chunkRef = beforeParse.chunk(slot);
            if (chunkRef == null) {
                if (beforeParse.offsets()[slot] == 0) {
                    return fail(ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.e86a10428bbed2a2") + slot + ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.366e3dd661c77dbd"));
                }
                return fail(ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.282680c9cde21464") + beforeParse.problems());
            }
            before = readChunk(chunkRef);
        } catch (Throwable t) {
            return fail(ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.ee36c192df6b85ba") + t);
        }
        final CompoundTag after = before.copy();
        final ShanhaiChunkEditor.Pos pos = new ShanhaiChunkEditor.Pos(req.x(), req.y(), req.z());
        final int[] range = yRange(after, req.dimension());
        if (req.y() < -2048 || req.y() > 2047) {
            return fail("Y=" + req.y() + ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.0e646ece61a22eb4"));
        }
        if (range != null && (req.y() < range[0] || req.y() > range[1])) {
            return fail("Y=" + req.y() + ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.95a178b080ce8297") + yRangeText(range)
                    + ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.f882d66924d9b0ce") + normalizeDimension(req.dimension()) + "）");
        }
        // ── 改方块
        ShanhaiChunkEditor.State target = null;
        if (req.hasBlock()) {
            try {
                target = parseState(req.blockId(), req.blockState());
            } catch (IllegalArgumentException iae) {
                return fail(ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.61bab19e4edd8d66") + iae.getMessage());
            }
            final boolean clear = !req.hasBe() && !req.blockHasBlockEntity();
            final ShanhaiChunkEditor.Result r = ShanhaiChunkEditor.setBlock(after, pos, target, clear);
            if (!r.ok()) {
                return fail(ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.6608d4607cea792d") + r.error());
            }
            notes.addAll(r.notes());
            if (!r.changed() && !req.hasBe()) {
                return fail(ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.e8531785e92624d8") + target + "）");
            }
        }
        // ── 改方块实体
        CompoundTag beExpected = null;
        boolean beShouldExist = false;
        if (req.hasBe()) {
            if (BE_CLEAR.equals(req.beAction())) {
                final int removed = ShanhaiChunkEditor.clearBlockEntity(after, pos);
                if (removed == 0) {
                    notes.add(ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.33dcf3716dd2cfb1"));
                } else {
                    notes.add(ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.3b1a621ba01fac2e") + removed + ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.624bee9cbd842744"));
                }
                beShouldExist = false;
            } else {
                if (req.beSnbt() == null || req.beSnbt().trim().isEmpty()) {
                    return fail(ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.4a54119bfffb9c9f"));
                }
                try {
                    beExpected = TagParser.parseTag(req.beSnbt());
                } catch (Throwable t) {
                    return fail(ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.c96614e217878918") + t.getMessage());
                }
                if (beExpected == null) {
                    return fail(ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.44fae3d685090758"));
                }
                // 预期的那份：替换会把 x/y/z 强制成目标坐标
                beExpected.putInt("x", req.x());
                beExpected.putInt("y", req.y());
                beExpected.putInt("z", req.z());
                final ShanhaiChunkEditor.Result r = ShanhaiChunkEditor.replaceBlockEntity(after, pos, beExpected);
                if (!r.ok()) {
                    return fail(ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.843e31cfe3544247") + r.error());
                }
                notes.addAll(r.notes());
                beShouldExist = true;
            }
        }
        // ── 写盘前先在内存里验一遍（不通过 ⇒ 一个字节都不写）
        if (target != null) {
            final ShanhaiChunkEditor.Verify v = ShanhaiChunkEditor.verifyBlock(before, after, pos, target);
            if (!v.ok()) {
                return fail(ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.bc47bdb165eae28f") + v.line());
            }
            changed.addAll(v.changes());
        }
        if (req.hasBe()) {
            final ShanhaiChunkEditor.Verify v = ShanhaiChunkEditor.verifyBlockEntity(before, after, pos,
                    beExpected, beShouldExist);
            if (!v.ok()) {
                return fail(ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.6a53ffbc5adb1538") + v.line());
            }
            changed.addAll(v.changes());
        }
        final List<String> intended = ShanhaiPlayerDataEditor.diffTags(before, after);
        if (intended.isEmpty()) {
            return fail(ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.27ff85e9029df74d"));
        }
        // ── 编码 + 只覆盖目标槽位
        final byte[] newFile;
        final ShanhaiRegionFile.Table table;
        try {
            final byte[] payload = ShanhaiRegionFile.deflate(encodeChunk(after));
            newFile = ShanhaiRegionFile.withChunk(beforeParse, slot, payload,
                    System.currentTimeMillis() / 1000L, notes);
            table = ShanhaiRegionFile.validate(newFile);
            if (!table.ok()) {
                return fail(ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.ead055d69cea205d") + table.line());
            }
        } catch (Throwable t) {
            return fail(ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.bc8ef6dbd09b9ef5") + t);
        }
        // ── 备份 + 原子写
        final ShanhaiSaveEditStorage.WriteResult write =
                ShanhaiSaveEditStorage.writeAtomic(regionFile, newFile, backupRoot, stamp);
        if (!write.ok()) {
            return new Result(false, write.error(), List.of(), notes, write, table.line(), table.line());
        }
        // ── 从盘上回读整份文件，逐条验
        try {
            final byte[] disk = Files.readAllBytes(regionFile);
            final ShanhaiRegionFile.Parse afterParse = ShanhaiRegionFile.parse(disk);
            final ShanhaiRegionFile.Chunk diskChunk = afterParse.chunk(slot);
            if (diskChunk == null) {
                restore(write, regionFile);
                return fail(ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.ecc99eace8a64ff4"));
            }
            final CompoundTag diskTag = readChunk(diskChunk);
            final List<String> observed = ShanhaiPlayerDataEditor.diffTags(before, diskTag);
            if (!observed.equals(intended)) {
                restore(write, regionFile);
                return fail(ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.31f6232e181821c4") + intended + " observed=" + observed + ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.d91cbe867beae1bd"));
            }
            if (target != null && !ShanhaiChunkEditor.verifyBlock(before, diskTag, pos, target).ok()) {
                restore(write, regionFile);
                return fail(ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.65ed48abb0568f1b"));
            }
            final ChunkDiff other = compareOtherChunks(beforeParse, afterParse, slot);
            if (!other.ok()) {
                restore(write, regionFile);
                return fail(ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.60d1f79f22820162") + other.problems());
            }
            notes.add(ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.c752991003d01944") + other.compared() + ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.fc71edc640ab7d14"));
            final ShanhaiRegionFile.Table diskTable = ShanhaiRegionFile.validate(disk);
            if (!diskTable.ok()) {
                restore(write, regionFile);
                return fail(ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.04f68d6c2965986d") + diskTable.line());
            }
            notes.add(ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.6969e0e84bc42022") + disk.length + ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.754e5a0f19d6d0bd") + afterParse.chunkCount() + ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.7aaf96ad52e049e5"));
            return new Result(true, "", changed, notes, write, other.line(), diskTable.line());
        } catch (Throwable t) {
            restore(write, regionFile);
            return fail(ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.d6f6c504a44d9002") + t);
        }
    }

    /** 回读失败时用备份盖回去（尽力而为）。 */
    private static void restore(ShanhaiSaveEditStorage.WriteResult write, Path target) {
        ShanhaiSaveEditStorage.restore(write == null ? null : write.backupPath(), target);
    }

    /** 两个 region 解析结果里"除目标槽位外"的比对（判据 ① 的"其它区块不变"）。 */
    public record ChunkDiff(boolean ok, int compared, List<String> problems) {
        public String line() {
            return "other_chunks=" + this.compared + " unchanged=" + this.ok
                    + (this.problems.isEmpty() ? "" : " problems=" + this.problems);
        }
    }

    /**
     * 逐槽比对"除目标槽位以外"的区块：两边都要么都没有，要么<b>压缩字节 sha256 相同</b>，
     * 而且偏移表项（起始扇区 + 扇区数）与时间戳都不许变。
     */
    public static ChunkDiff compareOtherChunks(ShanhaiRegionFile.Parse before, ShanhaiRegionFile.Parse after,
                                               int targetSlot) {
        final List<String> problems = new ArrayList<>();
        int problemCount = 0;
        int compared = 0;
        if (before == null || after == null) {
            return new ChunkDiff(false, 0, List.of("parse_null"));
        }
        for (int slot = 0; slot < ShanhaiRegionFile.SLOT_COUNT; slot++) {
            if (slot == targetSlot) {
                continue;
            }
            final ShanhaiRegionFile.Chunk a = before.chunk(slot);
            final ShanhaiRegionFile.Chunk b = after.chunk(slot);
            compared++;
            if (a == null && b == null) {
                continue;
            }
            final List<String> here = new ArrayList<>();
            if (a == null || b == null) {
                here.add("slot=" + slot + ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.8a1a1906ba77f9e4") + (a != null) + " after=" + (b != null) + "）");
            } else {
                final String sa = before.payloadSha(slot);
                final String sb = after.payloadSha(slot);
                if (sa == null || !sa.equals(sb)) {
                    here.add("slot=" + slot + ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.fc2a64893701c47d") + shortSha(sa) + " -> " + shortSha(sb) + "）");
                }
                if (a.offsetSector() != b.offsetSector() || a.sectorCount() != b.sectorCount()) {
                    here.add("slot=" + slot + ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.54da7c131e367a6a") + a.offsetSector() + "/" + a.sectorCount()
                            + " -> " + b.offsetSector() + "/" + b.sectorCount() + "）");
                }
                if (a.timestamp() != b.timestamp()) {
                    here.add("slot=" + slot + ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.1a5a1aa171fa9e6d") + a.timestamp() + " -> " + b.timestamp() + "）");
                }
            }
            for (String h : here) {
                problemCount++;
                if (problems.size() < 8) {          // 只留前 8 条：1023 个区块的清单不该刷屏
                    problems.add(h);
                }
            }
        }
        if (problemCount > problems.size()) {
            problems.add(ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.8b3b232c639ea4bf") + (problemCount - problems.size()) + ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.68b74f90cba15a87"));
        }
        return new ChunkDiff(problemCount == 0, compared, problems);
    }

    // ================================================================== 编码/解码

    /** 解出一个区块的 NBT（压缩类型 1/2/3 都认）。 */
    public static CompoundTag readChunk(ShanhaiRegionFile.Chunk chunk) throws IOException {
        return decodeChunk(ShanhaiRegionFile.inflate(chunk));
    }

    /** 原始 NBT 字节 → CompoundTag（区块里是"无名"根标签，与 {@code NbtIo.write(CompoundTag,DataOutput)} 对称）。 */
    public static CompoundTag decodeChunk(byte[] nbt) throws IOException {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(nbt))) {
            return NbtIo.read(in);
        }
    }

    /** CompoundTag → 原始 NBT 字节（<b>不</b>压缩）。 */
    public static byte[] encodeChunk(CompoundTag tag) throws IOException {
        final ByteArrayOutputStream out = new ByteArrayOutputStream(64 * 1024);
        try (DataOutputStream dos = new DataOutputStream(out)) {
            NbtIo.write(tag, dos);
        }
        return out.toByteArray();
    }

    // ================================================================== 小工具

    /** 把面板上的 {@code 方块 id} + 可选 {@code 状态串} 拼起来并解析。 */
    public static ShanhaiChunkEditor.State parseState(String blockId, String state) throws IllegalArgumentException {
        final String id = blockId == null ? "" : blockId.trim();
        final String st = state == null ? "" : state.trim();
        if (st.isEmpty()) {
            return ShanhaiChunkEditor.State.parse(id);
        }
        final String inside = st.startsWith("[") ? st.substring(1) : st;
        final String body = inside.endsWith("]") ? inside.substring(0, inside.length() - 1) : inside;
        return ShanhaiChunkEditor.State.parse(id + "[" + body + "]");
    }

    /** 改盘结果里"失败"的那一种（不写盘）。 */
    public static Result fail(String why) {
        return new Result(false, why == null ? "failed" : why, List.of(), List.of(), null, "", "");
    }

    /** 一行"这个请求会落到哪个文件/槽位"（面板日志用；不改任何东西）。 */
    public static String locate(Path worldDir, Request req) {
        if (req == null) {
            return "request=null";
        }
        final String dim = normalizeDimension(req.dimension());
        final Path file = regionFile(worldDir, dim, req.x(), req.z());
        return "dim=" + dim + " chunk=(" + (req.x() >> 4) + "," + (req.z() >> 4) + ")"
                + " slot=" + ShanhaiRegionFile.slotOf(req.x() >> 4, req.z() >> 4)
                + " file=" + (file == null ? "-" : file);
    }

    /** 面板上"维度"输入框的默认值。 */
    public static Map<String, String> emptyRequestFields() {
        final Map<String, String> m = new LinkedHashMap<>();
        m.put("dimension", DIM_OVERWORLD);
        return m;
    }

    private static String shortSha(String sha) {
        return sha == null ? "-" : (sha.length() <= 12 ? sha : sha.substring(0, 12));
    }
}
