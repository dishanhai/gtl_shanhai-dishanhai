package com.dishanhai.gt_shanhai.common.saveedit;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * {@code playerdata/<uuid>.dat} 的读 / 改 / 写（<b>离线可跑</b>：只依赖 {@code net.minecraft.nbt}）。
 *
 * <h2>一、为什么只碰这几个键（用户定案的首期范围）</h2>
 * 本类是"存档直改·第一期"的唯一落盘改动点，允许动的键<b>逐个列在这里</b>，
 * 别的一律原样：
 * <pre>
 *   Health                     float   血量
 *   XpLevel / XpTotal          int     经验等级（改等级时一并重算累计经验）
 *   foodLevel / foodSaturationLevel   int/float  饥饿 / 饱和
 *   playerGameType             int     游戏模式
 *   Dimension                  string  维度
 *   Pos[0..2]                  double  位置   （Rotation[0..1] = yaw/pitch）
 *   Inventory 这一条 list        ——      删掉某个 Slot / 只改某格的 Count / 整条换掉某格
 *   EnderItems 这一条 list       ——      与 Inventory <b>同一套代码</b>，只是读另一个键
 * </pre>
 * 🔴 <b>不动</b>：{@code DataVersion}、{@code stats/*}、{@code advancements/*}、{@code level.dat}、
 * {@code region/*.mca}。前两项用户/任务书明令禁止，后两项排到后面几期。
 *
 * <h2>二、背包条目"原样"是怎么保证的</h2>
 * 我们从不 {@code ItemStack.of(tag)} 再 {@code save(tag)} —— 那会经一遍数据组件/默认值，
 * 有可能把 mod 的额外 tag 洗掉。这里只做四件事：
 * <ol>
 *   <li>读：{@code get("Inventory")} 拿到的 {@link ListTag} 里的 {@link CompoundTag} <b>直接引用</b>，
 *       只读 {@code Slot} 字节与 {@code id}（摘要用）；</li>
 *   <li>删：要清空的槽位 ⇒ 把那些条目从 list 里<b>摘掉</b>，其余条目连对象都不换；</li>
 *   <li>改数量：在<b>原条目对象</b>上 {@code putByte("Count", …)}，别的键一个字节都不碰；</li>
 *   <li>替换：新建一条 {@code {Slot, id, Count, tag?}}，{@code tag} 从<b>NBT 字节</b>解出来
 *       （{@link #decodeTagBytes}），不经过 {@code ItemStack}。</li>
 * </ol>
 * ⇒ 判据 ② 的"改一个字段、其余逐字节等价"在 NBT 层是可验证的（{@link #diffTags}）。
 *
 * <h2>二·五、槽号是 <b>byte</b>，副手是负数</h2>
 * <pre>
 *   0 .. 8      快捷栏          9 .. 35     主背包
 *   100 靴 / 101 护腿 / 102 胸甲 / 103 头盔
 *   -106        副手（🔴 负数；无符号读会变成 150）
 * </pre>
 * 全类只允许用 {@link #slotOf(CompoundTag)} 读 {@code Slot}，它返回<b>有符号</b>值；
 * 比较前一律过 {@link ShanhaiPlayerDataEdit#canonicalSlot(int)}（兼容旧的无符号别名 150）。
 *
 * <h2>三、写盘顺序（🔴 只在停服窗口内被调用）</h2>
 * <pre>
 *   读 before → copy 成 after → 改 after → 编码成字节
 *     → {@link ShanhaiSaveEditStorage#writeAtomic}（备份 + tmp + ATOMIC_MOVE + 回读哈希）
 *     → 从盘上再读一次 reread
 *     → diff(before, reread) 必须**逐条等于** diff(before, after)
 *        相等 ⇒ ok；不等 ⇒ 用备份把原文件盖回去，报 reread_mismatch
 * </pre>
 * 最后那一步是"宁可没改，也不许改坏"：一旦发现盘上的结果和我想写的不一样，
 * 立刻回滚成原样，让玩家还能正常进世界。
 */
public final class ShanhaiPlayerDataEditor {

    /** 日志前缀。 */
    public static final String PREFIX = ShanhaiSaveEditFlow.PREFIX;

    /** 一次改盘的结果。 */
    public record Result(boolean ok, String error, List<String> changed, List<String> notes,
                         ShanhaiSaveEditStorage.WriteResult write) {
        public String line() {
            return "ok=" + this.ok
                    + (this.error == null || this.error.isEmpty() ? "" : " error=" + this.error)
                    + " changed=" + this.changed
                    + (this.notes == null || this.notes.isEmpty() ? "" : " notes=" + this.notes)
                    + (this.write == null ? "" : " | " + this.write.line());
        }
    }

    private ShanhaiPlayerDataEditor() {}

    // ================================================================== 读

    /** 读整个 playerdata（文件不存在 ⇒ {@code null}）。 */
    public static CompoundTag read(Path file) throws IOException {
        if (file == null || !Files.isRegularFile(file)) {
            return null;
        }
        return NbtIo.readCompressed(file.toFile());
    }

    /** 编码成 {@code gzip} 后的 NBT 字节（与原版 {@code NbtIo.writeCompressed} 同一格式）。 */
    public static byte[] encode(CompoundTag tag) throws IOException {
        final ByteArrayOutputStream out = new ByteArrayOutputStream(16 * 1024);
        NbtIo.writeCompressed(tag, out);
        return out.toByteArray();
    }

    // ================================================================== 格子快照（读给 UI / 读给探针）

    /**
     * 一个格子里的条目摘要（只读）。
     *
     * <p>{@code tagNbt} 是物品 {@code tag} 这一层 compound 的 NBT 字节，<b>可以直接喂回</b>
     * {@link ShanhaiPlayerDataEdit#replaceSlot(int, String, int, byte[])} —— 这样"把 A 格复制到
     * B 格"就是<b>树级等价</b>的搬运，完全不经 SNBT / 不经 {@code ItemStack}。
     */
    public record SlotEntry(int slot, String id, int count, boolean hasCount, boolean hasTag,
                            byte[] tagNbt, List<String> tagKeys) {

        public String line() {
            return ShanhaiPlayerDataEdit.slotLabel(this.slot) + "[" + this.slot + "]=" + shortId(this.id)
                    + "×" + this.count + (this.hasTag ? "{tag:" + this.tagKeys + "}" : "");
        }
    }

    /**
     * 一份「格子快照」：背包 41 格与末影箱 27 格<b>完全同形</b>（同一段代码，只是扫的键不同）。
     *
     * <p>{@code present} = <b>键在不在</b>（不是"有没有物品"）：真实的 16/16 份档案里凉影箱
     * {@code EnderItems} 都是"键存在 + 空列表"。{@code rangeVerified=false} 表示槽号合法集
     * <b>没有在本机真实数据上验证过</b>（末影箱就是这样）。
     */
    public record SlotGrid(String key, boolean ender, boolean present, String error, boolean rangeVerified,
                           int[] gridSlots, Map<Integer, SlotEntry> bySlot, List<Integer> unknownSlots,
                           List<Integer> duplicateSlots, int rawEntries, int nonCompoundEntries) {

        /** 有几格有东西。 */
        public int used() {
            return this.bySlot.size();
        }

        /** 某个槽号的条目（空格 = {@code null}）。 */
        public SlotEntry at(int slot) {
            return this.bySlot.get(ShanhaiPlayerDataEdit.canonicalSlot(slot));
        }

        /** 第 {@code index} 个格子的条目（空格 = {@code null}）。 */
        public SlotEntry atGrid(int index) {
            final int slot = this.ender ? ShanhaiPlayerDataEdit.enderSlotAt(index)
                    : ShanhaiPlayerDataEdit.inventorySlotAt(index);
            return slot < 0 ? null : at(slot);
        }

        public String line() {
            return this.key + " present=" + this.present + " rangeVerified=" + this.rangeVerified
                    + " used=" + used() + "/" + this.gridSlots.length
                    + " raw=" + this.rawEntries + " unknown=" + this.unknownSlots
                    + " dup=" + this.duplicateSlots + " nonCompound=" + this.nonCompoundEntries
                    + (this.error == null || this.error.isEmpty() ? "" : " error=" + this.error);
        }
    }

    /** 读 {@code Inventory} 的 <b>41 格</b>快照（槽号用文件里的原值，副手是 -106）。 */
    public static SlotGrid readInventory(Path file) {
        return readGrid(file, "Inventory", false);
    }

    /**
     * 读 {@code EnderItems} 的 27 格快照（与背包同形，槽号用文件里的原值）。
     *
     * <p>🔴 {@code rangeVerified=false}：本机 16/16 份真实档案的 {@code EnderItems} 都是空列表，
     * 一条真实条目都没有 ⇒ 0–26 是按原版口径写的，<b>未实测</b>。
     */
    public static SlotGrid readEnderItems(Path file) {
        return readGrid(file, "EnderItems", true);
    }

    /** 两份快照的唯一实现（"背包 / 末影箱共用同一套格子代码"就是在这里兑现的）。 */
    private static SlotGrid readGrid(Path file, String key, boolean ender) {
        final int[] grid = ender ? ShanhaiPlayerDataEdit.enderGrid() : ShanhaiPlayerDataEdit.inventoryGrid();
        final boolean rangeVerified = ender ? ShanhaiPlayerDataEdit.ENDER_RANGE_VERIFIED : true;
        final Map<Integer, SlotEntry> bySlot = new LinkedHashMap<>();
        final List<Integer> unknown = new ArrayList<>();
        final List<Integer> dup = new ArrayList<>();
        if (file == null || !Files.isRegularFile(file)) {
            return new SlotGrid(key, ender, false, ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.3703e47860f64a12"), rangeVerified, grid, bySlot,
                    unknown, dup, 0, 0);
        }
        final CompoundTag root;
        try {
            root = read(file);
        } catch (Throwable t) {
            return new SlotGrid(key, ender, false, ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.6687f349a978a8bc") + t, rangeVerified, grid, bySlot,
                    unknown, dup, 0, 0);
        }
        if (root == null) {
            return new SlotGrid(key, ender, false, ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.d350d798f368f180"), rangeVerified, grid, bySlot, unknown, dup, 0, 0);
        }
        final Tag raw = root.get(key);
        if (!(raw instanceof ListTag list)) {
            final String why = root.contains(key) ? (key + ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.707d41ca38707dc1") + raw.getId() + "）")
                    : (ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.cf771c8dec7e21fc") + key + ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.e66a4b11dbf57c0f"));
            return new SlotGrid(key, ender, false, why, rangeVerified, grid, bySlot, unknown, dup, 0, 0);
        }
        int nonCompound = 0;
        for (int i = 0; i < list.size(); i++) {
            if (!(list.get(i) instanceof CompoundTag entry)) {
                nonCompound++;          // 真实数据里是 0 条；出现就如实计数，不假装没看见
                continue;
            }
            final int slot = slotOf(entry);
            final SlotEntry one = new SlotEntry(slot, entry.getString("id"),
                    entry.contains("Count", 99) ? (int) entry.getByte("Count") : 0,
                    entry.contains("Count", 99), entry.contains("tag", 10),
                    entry.contains("tag", 10) ? encodeTagBytesSafe(entry.getCompound("tag")) : null,
                    entry.contains("tag", 10) ? new ArrayList<>(new TreeSet<>(entry.getCompound("tag").getAllKeys()))
                            : List.of());
            if (ender ? !ShanhaiPlayerDataEdit.isEnderSlot(slot) : !ShanhaiPlayerDataEdit.isInventorySlot(slot)) {
                unknown.add(slot);      // 41 格之外的槽号：不丢，单独报
            }
            final int canon = ShanhaiPlayerDataEdit.canonicalSlot(slot);
            if (bySlot.containsKey(canon)) {
                dup.add(slot);          // 实测 155/155 没有重复，但真要出现就得说
                continue;
            }
            bySlot.put(canon, one);
        }
        return new SlotGrid(key, ender, true, "", rangeVerified, grid, bySlot, unknown, dup, list.size(), nonCompound);
    }

    /** 把一格的 {@code tag} compound 编成 NBT 字节（供 {@code replaceSlot} 原样搬）。 */
    public static byte[] encodeTagBytes(CompoundTag tag) throws IOException {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        final DataOutputStream dos = new DataOutputStream(out);
        NbtIo.writeUnnamedTag(tag, dos);   // 与原版 NbtIo.write(CompoundTag, DataOutput) 同一条路
        dos.flush();
        return out.toByteArray();
    }

    /** {@link #encodeTagBytes} 的逆运算（无名根 compound）。 */
    public static CompoundTag decodeTagBytes(byte[] bytes) throws IOException {
        if (bytes == null || bytes.length == 0) {
            throw new IOException(ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.a43af4e58cbdde18"));
        }
        final DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes));
        final CompoundTag tag = NbtIo.read(in, NbtAccounter.UNLIMITED);
        if (in.available() > 0) {
            throw new IOException(ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.69012c96e1f61926") + in.available() + ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.7afad5532ca39091"));
        }
        return tag;
    }

    /** 摘要用：编码失败不让整份快照失败（返回 null = "拿不到字节"，读的时候会显示没 tag）。 */
    private static byte[] encodeTagBytesSafe(CompoundTag tag) {
        try {
            return encodeTagBytes(tag);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 读一条物品条目的 {@code Slot}：🔴 <b>有符号</b> byte。
     *
     * <p>副手在真实档案里是 <b>-106</b>。无符号读（{@code & 0xFF}）会得到 150 ——
     * 虽然 {@link ShanhaiPlayerDataEdit#canonicalSlot(int)} 能把 150 归一回 -106，
     * 但"读的时候就把符号丢了"会让 0–8 / 9–35 / 100–103 以外的槽号语义全乱。
     */
    private static int slotOf(CompoundTag entry) {
        return (int) entry.getByte("Slot");
    }

    // ================================================================== 摘要

    /** 读一份摘要（面板"读当前值"就是它）。任何异常都变成 {@code present=false} 的一条，不抛。 */
    public static ShanhaiPlayerDataSnapshot summarize(Path file) {
        final String name = file == null ? "-" : file.getFileName().toString();
        if (file == null || !Files.isRegularFile(file)) {
            return ShanhaiPlayerDataSnapshot.missing(name, ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.3703e47860f64a12"));
        }
        final long bytes;
        final CompoundTag root;
        try {
            bytes = Files.size(file);
            root = NbtIo.readCompressed(file.toFile());
        } catch (Throwable t) {
            return ShanhaiPlayerDataSnapshot.missing(name, ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.6687f349a978a8bc") + t);
        }
        if (root == null) {
            return ShanhaiPlayerDataSnapshot.missing(name, ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.d350d798f368f180"));
        }
        final double[] pos = readDoubleList(root, "Pos", 3);
        final float[] rot = readFloatList(root, "Rotation", 2);
        final List<String> preview = new ArrayList<>();
        final int used = countUsedSlots(root, preview, 5);
        return new ShanhaiPlayerDataSnapshot(name, true, bytes, "",
                String.valueOf(root.getInt("DataVersion")),
                root.getFloat("Health"),
                root.getInt("XpLevel"),
                root.getInt("foodLevel"),
                root.getFloat("foodSaturationLevel"),
                root.getInt("playerGameType"),
                root.contains("Dimension", 8) ? root.getString("Dimension") : "minecraft:overworld",
                pos[0], pos[1], pos[2],
                rot.length == 2 ? rot[0] : 0F, rot.length == 2 ? rot[1] : 0F,
                used, preview);
    }

    /** 数一下 41 个玩家槽位里有多少非空，并顺手取前 {@code maxPreview} 个做预览。 */
    private static int countUsedSlots(CompoundTag root, List<String> preview, int maxPreview) {
        final Tag inv = root.get("Inventory");
        if (!(inv instanceof ListTag list)) {
            return 0;
        }
        int used = 0;
        for (int i = 0; i < list.size(); i++) {
            final CompoundTag entry = list.getCompound(i);
            final int slot = slotOf(entry);     // 🔴 有符号（副手 -106；旧代码这里 & 0xFF 会读成 150）
            if (!ShanhaiPlayerDataEdit.isPlayerSlot(slot)) {
                continue;               // 别的槽（模组加的）不计数、也不显示
            }
            final String id = entry.getString("id");
            if (id.isEmpty()) {
                continue;
            }
            used++;
            if (preview.size() < maxPreview) {
                final int count = entry.contains("Count", 99) ? Math.max(1, entry.getByte("Count")) : 1;
                preview.add(ShanhaiPlayerDataEdit.slotName(slot) + "=" + shortId(id)
                        + (count > 1 ? "×" + count : ""));
            }
        }
        return used;
    }

    private static String shortId(String id) {
        if (id == null) {
            return "?";
        }
        return id.startsWith("minecraft:") ? id.substring("minecraft:".length()) : id;
    }

    /** 当前存档里 {@code generic.max_health} 的基值（取不到 = 20）。 */
    public static float maxHealth(CompoundTag root) {
        if (root == null) {
            return 20.0F;
        }
        final Tag attrs = root.get("Attributes");
        if (attrs instanceof ListTag list) {
            for (int i = 0; i < list.size(); i++) {
                final CompoundTag a = list.getCompound(i);
                final String key = a.getString("Name");
                if ("minecraft:generic.max_health".equals(key)) {
                    final double base = a.getDouble("Base");
                    if (base > 0.0D && base < 1.0E6D) {
                        return (float) base;
                    }
                }
            }
        }
        return 20.0F;
    }

    // ================================================================== 改 + 写

    /**
     * 把 {@code edit} 应用到 {@code file} 上（<b>会写盘</b>）。
     *
     * <p>🔴 调用方必须先通过 {@link ShanhaiSaveEditFlow#tryApply(String)} —— 本方法内部
     * <b>不</b>再判窗口（窗口是"这次运行走到哪一步"的属性，不是文件层的属性）。
     *
     * <p>若 {@code edit} 还带着<b>第二~七页</b>的改动（{@link ShanhaiPlayerDataEdit#levelEdit} /
     * {@link ShanhaiPlayerDataEdit#regionRequest} / {@code entityRequest} / {@code advancementRequest} /
     * {@code statsRequest} / {@code auditRequest}），这里转给 {@link ShanhaiSaveEditWindow}
     * 按"玩家 → 世界 → 方块 → 实体 → 成就 → 统计 → 体检"的顺序各写各的。单独改玩家数据时走的仍是
     * 本方法原来的那段代码（{@link #applyPlayerOnly}）—— 一期的行为一个字节都不变。
     *
     * <h2>🔴 分流判据是 {@link ShanhaiPlayerDataEdit#hasWindowPages()}，不是 ②③ 两页</h2>
     * 窗口是<b>唯一</b>会写 ④⑤⑥⑦ 的地方。旧条件 {@code hasLevel() || hasRegion()}（只有 ②③）
     * 漏掉了第四期加的 ④⑤⑥⑦，后果三条：
     * <ol>
     *   <li>只暂存 ④⑤⑥⑦ 中的任意几页 ⇒ 落到 {@code applyPlayerOnly} ⇒ {@code !hasPlayerFields()}
     *       ⇒ {@code empty_edit} ⇒ <b>一个字节都不写</b>（而且不报错，只是 ok=false）；</li>
     *   <li>只暂存 ④ 且 ① 也填了字段 ⇒ {@code applyPlayerOnly} 成功返回 ok=true，
     *       <b>④ 被静默丢掉</b>（连错误都没有）；</li>
     *   <li>{@code empty_edit} 不含 {@code no_effective_change} 字面 ⇒
     *       {@link ShanhaiSaveEditReopenGate#isNoEffectiveChange(String)} 不拦
     *       ⇒ 世界停服 + 重开，但盘上一个字节都没变。</li>
     * </ol>
     * 判据装置：{@code temp\saveedit-probe\SaveEditProbe.java} 的 <b>判据 ⑮（c15）P1~P4</b>。
     *
     * @param backupRoot 备份根目录（{@code temp\saveedit-backup}）
     * @param stamp      备份子目录名（时间戳）
     */
    public static Result apply(Path file, ShanhaiPlayerDataEdit edit, Path backupRoot, String stamp) {
        return ShanhaiSaveEditWindow.apply(file, edit, backupRoot, stamp);
    }

    /**
     * 玩家页的内部写入步骤；由 {@link ShanhaiSaveEditWindow} 的事务调用，
     * 其中还会处理 {@code level.dat} 的玩家标签。
     */
    static Result applyPlayerOnly(Path file, ShanhaiPlayerDataEdit edit, Path backupRoot, String stamp) {
        final List<String> notes = new ArrayList<>();
        if (file == null) {
            return new Result(false, "null_file", List.of(), notes, null);
        }
        if (edit == null || !edit.hasPlayerFields()) {
            // 🔴 "没有要改的东西" ⇒ 一个字节都不会写 ⇒ 必须落在 no_effective_change 这一族里，
            //    否则重开闸（ShanhaiSaveEditReopenGate#isNoEffectiveChange）认不出来 ⇒
            //    世界停服 + 重开，但盘上什么都没变（判据 ⑮ 的 P2 就是盯这一条）。
            //    （"empty" 这个词保留下来：日志里能一眼看出是"编辑是空的"而不是"改完发现没变化"。）
            return new Result(false, ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.2eb65c8c4e8f2ca9"),
                    List.of(), notes, null);
        }
        if (edit.errors != null && !edit.errors.isEmpty()) {
            return new Result(false, "invalid_edit:" + edit.errors, List.of(), notes, null);
        }
        final CompoundTag before;
        try {
            before = read(file);
        } catch (Throwable t) {
            return new Result(false, "read_before_failed:" + t, List.of(), notes, null);
        }
        if (before == null) {
            return new Result(false, "playerdata_missing:" + file, List.of(), notes, null);
        }
        // 逐格补丁的文件级校验（"这一格是空的吗"必须读了文件才知道）——
        // 放在 mutate 之前、写盘之前：不合格就一个字节都不写。
        final List<String> patchErrors = validateSlotPatches(before, edit);
        if (!patchErrors.isEmpty()) {
            return new Result(false, "invalid_edit:" + patchErrors, List.of(), notes, null);
        }
        final CompoundTag after = before.copy();
        mutate(after, edit, notes);
        final List<String> intended;
        try {
            intended = diffTags(before, after);
        } catch (Throwable t) {
            return new Result(false, "diff_failed:" + t, List.of(), notes, null);
        }
        if (intended.isEmpty()) {
            return new Result(false, "no_effective_change", List.of(), notes, null);
        }
        final byte[] content;
        try {
            content = encode(after);
        } catch (Throwable t) {
            return new Result(false, "encode_failed:" + t, List.of(), notes, null);
        }
        final ShanhaiSaveEditStorage.WriteResult write =
                ShanhaiSaveEditStorage.writeAtomic(file, content, backupRoot, stamp);
        if (!write.ok()) {
            return new Result(false, write.error(), List.of(), notes, write);
        }
        // 回读校验：盘上的结果必须和我想写的逐条一致
        final List<String> observed;
        try {
            final CompoundTag reread = read(file);
            observed = reread == null ? List.of("<unreadable>") : diffTags(before, reread);
        } catch (Throwable t) {
            return new Result(false, "reread_failed:" + t, List.of(), notes, write);
        }
        if (!observed.equals(intended)) {
            return new Result(false, "reread_mismatch intended=" + intended + " observed=" + observed,
                    observed, notes, write);
        }
        // ── 🔴 玩家页写完之后必须把 level.dat 里的 Data.Player 删掉（否则重开时它把 playerdata 盖回去）
        //    理由与字节码出处见 ShanhaiLevelDataEditor#dropPlayerTag。这里只负责"什么时候删 + 删的结果"：
        //    删失败 = 这次改动重开后会失效 ⇒ 如实报失败（不装作成功）。
        final Path worldDir = ShanhaiLevelDataEditor.worldDirOfPlayerData(file);
        final Path levelDat = ShanhaiLevelDataEditor.levelDatOf(worldDir);
        final ShanhaiLevelDataEditor.Result drop =
                ShanhaiLevelDataEditor.dropPlayerTag(levelDat, backupRoot, stamp);
        if (drop.notes() != null) {
            notes.addAll(drop.notes());
        }
        if (!drop.ok()) {
            return new Result(false, "leveldat_player_drop_failed:" + drop.error()
                    + ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.86008ecd22d22330"),
                    observed, notes, drop.write() != null ? drop.write() : write);
        }
        final List<String> changed = new ArrayList<>(observed);
        for (String c : drop.changed()) {
            changed.add("level.dat:" + c);
        }
        logRoundtrip(file, levelDat, write);
        return new Result(true, "", changed, notes, write);
    }

    /**
     * 诊断行 {@code panel_roundtrip}（<b>每次玩家页写入成功后打一行</b>）。
     *
     * <pre>
     *   panel_roundtrip playerdata_slots=27 leveldat_player_slots=absent playerdata_sha=4188b7c7dcb2
     * </pre>
     * 判据：
     * <ul>
     *   <li>{@code leveldat_player_slots=absent} ⇒ level.dat 里那份已经删掉/本来没有 ⇒ 重开不会被盖；</li>
     *   <li>只剩一个数字 ⇒ {@code Data.Player} 又回来了（游戏自愈写回，属正常；但如果它和
     *       {@code playerdata_slots} <b>不等</b>，就说明"还有第三份数据"在别处）；</li>
     *   <li>{@code playerdata_slots} 与 {@code leveldat_player_slots} 必须相等（都带数字时）。</li>
     * </ul>
     * ⚠️ 诊断不许影响主流程：自己 catch 一切，最坏打成 {@code panel_roundtrip_failed}。
     */
    private static void logRoundtrip(Path playerDataFile, Path levelDat,
                                     ShanhaiSaveEditStorage.WriteResult write) {
        try {
            final CompoundTag playerRoot = read(playerDataFile);
            final int playerSlots = countInventoryEntries(playerRoot);
            final String sha = write != null && write.targetSha256After() != null
                    ? write.targetSha256After() : ShanhaiSaveEditStorage.sha256(playerDataFile);
            final CompoundTag levelRoot = read(levelDat);
            final CompoundTag levelPlayer = ShanhaiLevelDataEditor.playerTagOf(levelRoot);
            final String levelSlots;
            if (levelRoot == null) {
                levelSlots = "<no_level_dat>";
            } else if (levelPlayer == null) {
                levelSlots = "absent";
            } else {
                levelSlots = String.valueOf(countInventoryEntries(levelPlayer));
            }
            com.dishanhai.gt_shanhai.GTDishanhaiMod.LOGGER.info(
                    "{} panel_roundtrip playerdata_slots={} leveldat_player_slots={} playerdata_sha={}",
                    PREFIX, playerSlots, levelSlots,
                    sha == null ? "-" : sha.substring(0, Math.min(12, sha.length())));
        } catch (Throwable t) {
            com.dishanhai.gt_shanhai.GTDishanhaiMod.LOGGER.warn("{} panel_roundtrip_failed err={}",
                    PREFIX, t.toString());
        }
    }

    /**
     * 一份玩家 NBT 里 {@code Inventory} + {@code EnderItems} 的条目数（诊断/判据用；拿不到 ⇒ 0）。
     *
     * <p>同一个数在 {@code playerdata/<uuid>.dat} 与 {@code level.dat} 的 {@code Data.Player}
     * 上各算一次 —— 两个数不相等就说明"还有第三份玩家数据"。
     */
    public static int countInventoryEntries(CompoundTag playerTag) {
        if (playerTag == null) {
            return 0;
        }
        return listSize(playerTag.get("Inventory")) + listSize(playerTag.get("EnderItems"));
    }

    /** 一个列表标签的元素数（不是列表 ⇒ 0）。 */
    private static int listSize(Tag tag) {
        return tag instanceof ListTag list ? list.size() : 0;
    }

    /**
     * 把 {@code edit} 应用到 {@code tag} 上（<b>纯内存</b>：判据 ② 的"改完的那份"就是它）。
     *
     * <p>拆分出来的原因：离线判据要先拿到"预期结果"再和盘上的回读比对，
     * 而生产路径两者用的是同一段代码 —— 不许有第二份实现。
     */
    public static void mutate(CompoundTag tag, ShanhaiPlayerDataEdit edit, List<String> notes) {
        if (tag == null || edit == null) {
            return;
        }
        final List<String> out = notes == null ? new ArrayList<>() : notes;

        if (edit.health != null) {
            final float cap = maxHealth(tag);
            float v = edit.health;
            if (v > cap) {
                out.add(ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.ed2ee7222c4a948f") + ShanhaiPlayerDataEdit.fmt(v) + ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.5c5f1ad3924526ed")
                        + ShanhaiPlayerDataEdit.fmt(cap) + ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.ad9b0d7393ca02ba"));
                v = cap;
            }
            if (v < 0.0F) {
                v = 0.0F;
            }
            tag.putFloat("Health", v);
        }

        if (edit.xpLevel != null) {
            tag.putInt("XpLevel", edit.xpLevel);
            // 累计经验跟着等级走，否则"总经验"会与等级自相矛盾
            tag.putInt("XpTotal", vanillaTotalExperience(edit.xpLevel));
            if (tag.contains("XpP", 99) && tag.getFloat("XpP") >= 1.0F) {
                tag.putFloat("XpP", 0.99F);
                out.add(ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.e7efa4101656a97f"));
            }
        }

        if (edit.foodLevel != null) {
            tag.putInt("foodLevel", edit.foodLevel);
        }
        if (edit.saturation != null) {
            float sat = edit.saturation;
            final int food = tag.contains("foodLevel", 99) ? tag.getInt("foodLevel") : 20;
            if (sat > food) {
                out.add(ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.bbacda3cda6ee640") + ShanhaiPlayerDataEdit.fmt(sat) + ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.a9e8a6da31010e64") + food + ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.ff0fd18afc8ad68c"));
                sat = food;
            }
            tag.putFloat("foodSaturationLevel", sat);
        }

        if (edit.gameMode != null) {
            tag.putInt("playerGameType", edit.gameMode);
            // ⚠️ previousPlayerGameType 故意不碰：它是"上一个模式"（F3+F4 切回去用），
            //    改了反而会让玩家的模式切换行为变得难以预料。
        }

        if (edit.dimension != null && !edit.dimension.isEmpty()) {
            tag.putString("Dimension", edit.dimension);
            if (!edit.movesPlayer()) {
                out.add(ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.aeeb30fca7df84c3"));
            }
        }

        if (edit.x != null && edit.y != null && edit.z != null) {
            final ListTag list = new ListTag();
            list.add(net.minecraft.nbt.DoubleTag.valueOf(edit.x));
            list.add(net.minecraft.nbt.DoubleTag.valueOf(edit.y));
            list.add(net.minecraft.nbt.DoubleTag.valueOf(edit.z));
            tag.put("Pos", list);
            // 位置变了却留着旧的 Motion/FallDistance ⇒ 落地瞬间被弹飞/摔死，这里清掉
            if (tag.contains("Motion")) {
                final ListTag zero = new ListTag();
                zero.add(net.minecraft.nbt.DoubleTag.valueOf(0.0D));
                zero.add(net.minecraft.nbt.DoubleTag.valueOf(0.0D));
                zero.add(net.minecraft.nbt.DoubleTag.valueOf(0.0D));
                tag.put("Motion", zero);
            }
            if (tag.contains("FallDistance", 99)) {
                tag.putFloat("FallDistance", 0.0F);
            }
        }

        if (edit.yaw != null && edit.pitch != null) {
            final ListTag list = new ListTag();
            list.add(net.minecraft.nbt.FloatTag.valueOf(edit.yaw));
            list.add(net.minecraft.nbt.FloatTag.valueOf(edit.pitch));
            tag.put("Rotation", list);
        }

        if (edit.clearSlots != null && edit.clearSlots.length > 0) {
            removeInventorySlots(tag, edit.clearSlots, out);
        }

        // 逐格补丁放在「清空槽位」之后：同一格既被清空又被替换时，补丁赢（顺序写死在这里）。
        if (edit.hasSlotPatches()) {
            applyPatches(tag, "Inventory", edit.inventoryPatches, false, out);
            applyPatches(tag, "EnderItems", edit.enderPatches, true, out);
        }
    }

    /** 从 {@code Inventory} 里摘掉给定 {@code Slot} 的条目（其余条目一个字节都不动）。 */
    private static void removeInventorySlots(CompoundTag tag, int[] slots, List<String> notes) {
        final Tag inv = tag.get("Inventory");
        if (!(inv instanceof ListTag list)) {
            notes.add(ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.7f4f2429f6d8e951"));
            return;
        }
        final Set<Integer> want = new TreeSet<>();
        for (int s : slots) {
            want.add(ShanhaiPlayerDataEdit.canonicalSlot(s));
        }
        final Set<Integer> removed = new LinkedHashSet<>();
        final ListTag kept = new ListTag();
        for (int i = 0; i < list.size(); i++) {
            final CompoundTag entry = list.getCompound(i);
            final int slot = ShanhaiPlayerDataEdit.canonicalSlot(slotOf(entry));
            if (want.contains(slot)) {
                removed.add(slot);
                continue;               // 摘掉（不重建、不改内容）
            }
            kept.add(entry);
        }
        for (int s : want) {
            if (!removed.contains(s)) {
                notes.add(ShanhaiPlayerDataEdit.slotName(s) + ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.cb5025d1b50e5875"));
            }
        }
        tag.put("Inventory", kept);
    }

    // ================================================================== 逐格补丁（删 / 改数量 / 替换）

    /**
     * 补丁的<b>文件级</b>校验（"这一格是不是空的"只有读了文件才知道）。
     *
     * <p>返回非空 ⇒ 调用方必须<b>一个字节都不写</b>。调用点在
     * {@link #applyPlayerOnly}：读完 {@code before} 之后、任何 {@code put}/写盘之前。
     * 槽号/数量/注册名的形状校验在 {@link ShanhaiPlayerDataEdit#putPatch} 那一层（不需要文件）。
     */
    public static List<String> validateSlotPatches(CompoundTag root, ShanhaiPlayerDataEdit edit) {
        final List<String> errors = new ArrayList<>();
        if (edit == null) {
            return errors;
        }
        validateOne(root, "Inventory", edit.inventoryPatches, false, errors);
        validateOne(root, "EnderItems", edit.enderPatches, true, errors);
        return errors;
    }

    private static void validateOne(CompoundTag root, String key, Map<Integer, ShanhaiPlayerDataEdit.SlotPatch> patches,
                                    boolean ender, List<String> errors) {
        if (patches == null || patches.isEmpty()) {
            return;
        }
        final Tag raw = root == null ? null : root.get(key);
        if (raw != null && !(raw instanceof ListTag)) {
            errors.add(key + ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.707d41ca38707dc1") + raw.getId() + ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.dee91e97b2cc4ba8"));
            return;
        }
        final Map<Integer, Integer> existing = new LinkedHashMap<>();
        if (raw instanceof ListTag list) {
            for (int i = 0; i < list.size(); i++) {
                if (list.get(i) instanceof CompoundTag entry) {
                    existing.put(ShanhaiPlayerDataEdit.canonicalSlot(slotOf(entry)), i);
                }
            }
        }
        for (ShanhaiPlayerDataEdit.SlotPatch patch : patches.values()) {
            final int slot = ShanhaiPlayerDataEdit.canonicalSlot(patch.slot);
            final String name = ender ? ShanhaiPlayerDataEdit.enderSlotName(slot)
                    : ShanhaiPlayerDataEdit.slotName(slot);
            final boolean legal = ender ? ShanhaiPlayerDataEdit.isEnderSlot(slot)
                    : ShanhaiPlayerDataEdit.isInventorySlot(slot);
            if (!legal) {
                errors.add(name + ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.79dc04b7295579d7") + key + ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.0e9bec985d894475")
                        + (ender ? ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.6dba95849bcec84c") : ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.e7c40a3b81dc29a2")) + "）");
                continue;
            }
            if (patch.op != ShanhaiPlayerDataEdit.SlotOp.REMOVE
                    && (patch.count < ShanhaiPlayerDataEdit.COUNT_MIN || patch.count > ShanhaiPlayerDataEdit.COUNT_MAX)) {
                errors.add(name + ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.a9dbdcca0b16d31c") + patch.count + ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.cb4c97113af5c4b1")
                        + ShanhaiPlayerDataEdit.COUNT_MIN + ", " + ShanhaiPlayerDataEdit.COUNT_MAX + "]");
            }
            if (patch.op == ShanhaiPlayerDataEdit.SlotOp.REPLACE) {
                if (!ShanhaiPlayerDataEdit.isResourceLocation(patch.id)) {
                    errors.add(name + ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.e08bc89af5dd45f6") + patch.id + ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.908acf3f0c9c842b"));
                }
                if (patch.hasTag()) {
                    try {
                        decodeTagBytes(patch.tagNbt);
                    } catch (Throwable t) {
                        errors.add(name + ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.2b95d88579fd2af0") + t);
                    }
                }
            }
            if (patch.op == ShanhaiPlayerDataEdit.SlotOp.SET_COUNT && !existing.containsKey(slot)) {
                errors.add(name + ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.49c3f6a621596a4d"));
            }
        }
    }

    /**
     * 把补丁应用到 {@code tag} 上（<b>纯内存</b>：{@link #mutate} 里调它，判据的"预期结果"也走它）。
     *
     * <p>背包与末影箱走<b>同一段</b>代码，区别只有 {@code key} 和"槽号合法集"。
     * 顺序：先处理文件里已有的条目（原索引不动，能替换就<b>原地换</b>，diff 才好读），
     * 再把"目标格本来是空的"那些 {@code REPLACE} 按槽号升序追加到表尾。
     */
    private static void applyPatches(CompoundTag tag, String key, Map<Integer, ShanhaiPlayerDataEdit.SlotPatch> patches,
                                     boolean ender, List<String> notes) {
        if (patches == null || patches.isEmpty()) {
            return;
        }
        if (ender && !ShanhaiPlayerDataEdit.ENDER_RANGE_VERIFIED) {
            notes.add(ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.7d0d74de50fb9bc8") + ShanhaiPlayerDataEdit.ENDER_SLOTS
                    + ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.f244bb16b1a0a794"));
        }
        final Tag raw = tag.get(key);
        final ListTag list = raw instanceof ListTag l ? l : null;
        final Set<Integer> done = new LinkedHashSet<>();
        final ListTag out = new ListTag();
        if (list != null) {
            for (int i = 0; i < list.size(); i++) {
                final Tag el = list.get(i);
                if (!(el instanceof CompoundTag entry)) {
                    out.add(el);            // 非 compound 的元素原样留着（真实数据里 0 条）
                    continue;
                }
                final int slot = ShanhaiPlayerDataEdit.canonicalSlot(slotOf(entry));
                final ShanhaiPlayerDataEdit.SlotPatch patch = patches.get(slot);
                if (patch == null) {
                    out.add(entry);         // 没被点到的条目：连对象都不换
                    continue;
                }
                done.add(slot);
                final String name = slotLabel(ender, slot);
                switch (patch.op) {
                    case REMOVE:
                        notes.add(name + ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.fe7650010b75c9ac") + shortId(entry.getString("id")));
                        break;
                    case SET_COUNT:
                        final int before = entry.contains("Count", 99) ? (int) entry.getByte("Count") : -1;
                        entry.putByte("Count", (byte) patch.count);   // 只动 Count 这一个键
                        notes.add(name + ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.6439b8659e506f21") + before + " → " + patch.count);
                        out.add(entry);
                        break;
                    default:
                        out.add(newEntry(patch));
                        notes.add(name + ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.456277b9f3e9ef4f") + shortId(patch.id) + "×" + patch.count
                                + (patch.hasTag() ? ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.a8b1fc57caf43424") : "") + ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.3d6b514fe5cc5460"));
                        break;
                }
            }
        }
        // 目标格本来是空的：只有「替换」能在空格上放东西（删/改数量在校验层已经拒了）
        final List<Integer> additions = new ArrayList<>();
        for (Integer slot : patches.keySet()) {
            if (!done.contains(slot) && patches.get(slot).op == ShanhaiPlayerDataEdit.SlotOp.REPLACE) {
                additions.add(slot);
            }
        }
        additions.sort(Integer::compareTo);
        for (Integer slot : additions) {
            final ShanhaiPlayerDataEdit.SlotPatch patch = patches.get(slot);
            out.add(newEntry(patch));
            notes.add(slotLabel(ender, slot) + ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.456277b9f3e9ef4f") + shortId(patch.id) + "×" + patch.count
                    + (patch.hasTag() ? ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.a8b1fc57caf43424") : "") + ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.8ada9dbbca5f2e4c"));
        }
        for (Integer slot : patches.keySet()) {
            if (!done.contains(slot) && !additions.contains(slot)) {
                notes.add(slotLabel(ender, slot) + ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.cb5025d1b50e5875"));
            }
        }
        if (list != null || !additions.isEmpty()) {
            tag.put(key, out);
        }
    }

    /**
     * 槽位的中文名：末影箱走 {@code 末影箱N}，背包走 {@code 背包N / 盔甲N / 副手}。
     *
     * <p>为什么要分：{@code slotName(13)} 是"背包14"，末影箱第 14 格也叫这个名 ⇒ 日志里两条链路
     * 分不开（实测踩到过一次）。
     */
    private static String slotLabel(boolean ender, int slot) {
        return ender ? ShanhaiPlayerDataEdit.enderSlotName(slot) : ShanhaiPlayerDataEdit.slotName(slot);
    }

    /**
     * 造一条新条目 {@code {Slot, id, Count, tag?}}。
     *
     * <p>🔴 {@code tag} 只从 NBT 字节解出来（{@link #decodeTagBytes}），<b>绝不</b>
     * {@code ItemStack.of(tag)} —— 1.20.1 的 {@code ItemStack#save} 会按注册表重写 {@code id}
     * （取不到就写 {@code minecraft:air}）、并且<b>根本不写 {@code Slot}</b>。
     */
    private static CompoundTag newEntry(ShanhaiPlayerDataEdit.SlotPatch patch) {
        final CompoundTag entry = new CompoundTag();
        entry.putByte("Slot", (byte) patch.slot);
        entry.putString("id", patch.id);
        entry.putByte("Count", (byte) patch.count);
        if (patch.hasTag()) {
            try {
                entry.put("tag", decodeTagBytes(patch.tagNbt));
            } catch (IOException ioe) {
                // 校验层已经解过一遍；走到这里说明字节在那之后被改了 ⇒ 直接抛，绝不留半条条目
                throw new IllegalStateException(ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.cc02cbac3cbcb6e8") + patch.slot + "）：" + ioe, ioe);
            }
        }
        return entry;
    }

    // ------------------------------------------------------------------ 原版经验公式

    /** 原版"从 {@code level} 升到 {@code level+1} 需要多少点"（{@code Player#getXpNeededForLevel}）。 */
    public static int xpNeededForLevel(int level) {
        if (level >= 30) {
            return 112 + (level - 30) * 9;
        }
        if (level >= 15) {
            return 37 + (level - 15) * 5;
        }
        return 7 + level * 2;
    }

    /** 原版"到 {@code level} 级为止的累计经验"（{@code Player#getXpNeededForNextLevel} 的累加）。 */
    public static int vanillaTotalExperience(int level) {
        long total = 0L;
        for (int l = 0; l < level; l++) {
            total += xpNeededForLevel(l);
            if (total >= Integer.MAX_VALUE) {
                return Integer.MAX_VALUE;
            }
        }
        return (int) total;
    }

    // ================================================================== NBT 比对（判据 ②）

    /**
     * 两份 NBT 的<b>逐条差异</b>（路径形式：{@code Health}、{@code Inventory[3].Count}、{@code Pos[1]}）。
     *
     * <p>🔴 这是判据 ② 的尺子，所以它自己必须先证明是灵敏的：
     * <ul>
     *   <li>叶子按 {@code getId()}＋{@code getAsString()} 比（能抓到 {@code IntTag 1} → {@code FloatTag 1.0} 这种类型变化）；</li>
     *   <li>Compound 取两边键的<b>并集</b>逐个下钻（能抓到"多了一个键"/"少了一个键"）；</li>
     *   <li>List 先比长度、再按索引下钻（能抓到顺序变化）。</li>
     * </ul>
     * 空列表 = 完全等价。
     */
    public static List<String> diffTags(CompoundTag a, CompoundTag b) {
        final List<String> out = new ArrayList<>();
        diffInto("", a, b, out);
        return out;
    }

    private static void diffInto(String path, Tag a, Tag b, List<String> out) {
        if (a == null && b == null) {
            return;
        }
        if (a == null) {
            out.add(path + " (+added)");
            return;
        }
        if (b == null) {
            out.add(path + " (-removed)");
            return;
        }
        if (a.getId() != b.getId()) {
            out.add(path + " (type " + a.getId() + "->" + b.getId() + ")");
            return;
        }
        if (a instanceof CompoundTag ca && b instanceof CompoundTag cb) {
            final Set<String> keys = new TreeSet<>(ca.getAllKeys());
            keys.addAll(cb.getAllKeys());
            for (String k : keys) {
                diffInto(path.isEmpty() ? k : path + "." + k, ca.get(k), cb.get(k), out);
            }
            return;
        }
        if (a instanceof ListTag la && b instanceof ListTag lb) {
            if (la.size() != lb.size()) {
                out.add(path + " (size " + la.size() + "->" + lb.size() + ")");
            }
            final int n = Math.min(la.size(), lb.size());
            for (int i = 0; i < n; i++) {
                diffInto(path + "[" + i + "]", la.get(i), lb.get(i), out);
            }
            return;
        }
        // 叶子：原版 StringTag/数值/数组的 getAsString 都是逐值展开的
        final String sa = a.getAsString();
        final String sb = b.getAsString();
        if (!sa.equals(sb)) {
            out.add(path + " (" + clamp(sa) + " -> " + clamp(sb) + ")");
        }
    }

    private static String clamp(String s) {
        if (s == null) {
            return "null";
        }
        return s.length() <= 60 ? s : s.substring(0, 57) + "...";
    }

    // ================================================================== 小工具

    private static double[] readDoubleList(CompoundTag root, String key, int want) {
        final Tag t = root.get(key);
        if (!(t instanceof ListTag list) || list.size() < want) {
            return new double[want];
        }
        final double[] out = new double[want];
        for (int i = 0; i < want; i++) {
            out[i] = list.getDouble(i);
        }
        return out;
    }

    private static float[] readFloatList(CompoundTag root, String key, int want) {
        final Tag t = root.get(key);
        if (!(t instanceof ListTag list) || list.size() < want) {
            return new float[0];
        }
        final float[] out = new float[want];
        for (int i = 0; i < want; i++) {
            out[i] = list.getFloat(i);
        }
        return out;
    }
}
