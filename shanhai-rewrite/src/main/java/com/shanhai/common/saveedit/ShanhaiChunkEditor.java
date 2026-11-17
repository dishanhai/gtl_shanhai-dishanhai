package com.shanhai.common.saveedit;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.LongArrayTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 区块 NBT 里的<b>方块 / 方块实体</b>编辑（只依赖 {@code net.minecraft.nbt}，离线可跑）。
 *
 * <h2>一、区块 NBT 里我们只碰这两个地方</h2>
 * <pre>
 *   sections[]                     每个 16×16×16 层
 *     Y                            层号（byte；地下是负的）
 *     block_states
 *       palette[]                  调色板：按顺序的方块状态（Name + Properties?）
 *       data[]                     调色板下标，按位打包（1.18+ 口径）
 *   block_entities[]               方块实体（x/y/z + id + 各自字段）
 * </pre>
 *
 * <h2>二、位打包口径（1.18+，与 {@code PalettedContainer} 一致）</h2>
 * <ul>
 *   <li>{@code bits = max(4, ceil(log2(palette.size())))}；palette 只有 1 项时 {@code data} 可以整个缺席；</li>
 *   <li>每个 long 里放 {@code 64 / bits} 个值，<b>值不跨 long</b>；低位在前；</li>
 *   <li>层内下标 = {@code (y&15)<<8 | (z&15)<<4 | (x&15)}。</li>
 * </ul>
 * ⇒ 本类用一个自检把这条口径<b>反过来验证</b>：把真实区块的每个 section 解开再按同一位宽打包，
 * 结果必须与原 {@code data} 数组<b>逐字节相同</b>（{@link #selfCheckPacking(CompoundTag)}）。
 * 口径要是错了，这个自检当场红 —— 而不是等到进游戏加载区块才炸。
 *
 * <h2>三、🔴 只改一个方块</h2>
 * {@link #setBlock} 只改目标 section 的 {@code palette/data} 两个键；其余 section、其余键
 * （{@code biomes}、{@code Heightmaps}、{@code entities}…）一个字节都不碰。判据 ① 的
 * {@link #verifyBlock} 就是按"其余键 NBT 逐条等价 + 目标 section 逐格只差 1 格"来断的。
 */
public final class ShanhaiChunkEditor {

    /** 空气。 */
    public static final String AIR = "minecraft:air";
    /** 一层 16³。 */
    public static final int SECTION_VOLUME = 4096;
    /** 1.8+ 区块 NBT 的 section 键名前缀。 */
    public static final String SECTIONS = "sections";
    /** 方块实体列表键名。 */
    public static final String BLOCK_ENTITIES = "block_entities";

    private ShanhaiChunkEditor() {}

    // ================================================================== 位置 / 状态

    /** 世界坐标里的一个方块位置。 */
    public record Pos(int x, int y, int z) {
        /** 它属于哪一层。 */
        public int sectionY() {
            return this.y >> 4;
        }

        /** 层内局部坐标（0..15）。 */
        public int localX() {
            return this.x & 15;
        }

        public int localY() {
            return this.y & 15;
        }

        public int localZ() {
            return this.z & 15;
        }

        /** 层内下标。 */
        public int index() {
            return (localY() << 8) | (localZ() << 4) | localX();
        }

        public String line() {
            return "(" + this.x + "," + this.y + "," + this.z + ")";
        }
    }

    /** 一个方块状态：{@code name} + 可选的属性表（键按字典序，保证回显稳定）。 */
    public record State(String name, Map<String, String> props) {

        /** 规范化文本：{@code minecraft:chest[facing=north,type=single]}；无属性时只有名字。 */
        @Override
        public String toString() {
            if (this.props == null || this.props.isEmpty()) {
                return this.name;
            }
            final StringBuilder sb = new StringBuilder(this.name).append('[');
            boolean first = true;
            for (Map.Entry<String, String> e : new TreeMap<>(this.props).entrySet()) {
                if (!first) {
                    sb.append(',');
                }
                sb.append(e.getKey()).append('=').append(e.getValue());
                first = false;
            }
            return sb.append(']').toString();
        }

        /** 空气。 */
        public static State air() {
            return new State(AIR, Map.of());
        }

        /** 从 NBT 的 palette 条目读。 */
        public static State of(CompoundTag tag) {
            if (tag == null) {
                return air();
            }
            final String name = tag.getString("Name");
            final Map<String, String> props = new LinkedHashMap<>();
            final Tag p = tag.get("Properties");
            if (p instanceof CompoundTag c) {
                for (String k : c.getAllKeys()) {
                    props.put(k, c.getString(k));
                }
            }
            return new State(name.isEmpty() ? AIR : name, props);
        }

        /** 写回 NBT 的 palette 条目（{@code Properties} 为空就不写）。 */
        public CompoundTag toTag() {
            final CompoundTag tag = new CompoundTag();
            tag.putString("Name", this.name);
            if (this.props != null && !this.props.isEmpty()) {
                final CompoundTag p = new CompoundTag();
                for (Map.Entry<String, String> e : new TreeMap<>(this.props).entrySet()) {
                    p.putString(e.getKey(), e.getValue());
                }
                tag.put("Properties", p);
            }
            return tag;
        }

        /**
         * 解析 {@code minecraft:stone} 或 {@code minecraft:oak_log[axis=y,waterlogged=false]}。
         *
         * <p>只做形状校验（不查注册表 —— 磁盘侧不许依赖运行期注册表；查注册表是面板在游戏里做的事）。
         *
         * @throws IllegalArgumentException 形状不合法（消息是给人看的中文）
         */
        public static State parse(String text) {
            final String s = text == null ? "" : text.trim();
            if (s.isEmpty()) {
                throw new IllegalArgumentException("方块 id 是空的");
            }
            String name = s;
            final Map<String, String> props = new LinkedHashMap<>();
            final int bracket = s.indexOf('[');
            if (bracket >= 0) {
                if (!s.endsWith("]")) {
                    throw new IllegalArgumentException("方块状态要用 [k=v,…] 收尾：" + s);
                }
                name = s.substring(0, bracket).trim();
                final String inside = s.substring(bracket + 1, s.length() - 1).trim();
                if (!inside.isEmpty()) {
                    for (String pair : inside.split(",")) {
                        final String kv = pair.trim();
                        if (kv.isEmpty()) {
                            continue;
                        }
                        final int eq = kv.indexOf('=');
                        if (eq <= 0 || eq == kv.length() - 1) {
                            throw new IllegalArgumentException("方块状态里这一项不是 k=v：" + kv);
                        }
                        final String k = kv.substring(0, eq).trim();
                        final String v = kv.substring(eq + 1).trim();
                        if (!isName(k) || !isName(v)) {
                            throw new IllegalArgumentException("属性名/值只认小写字母数字下划线：" + kv);
                        }
                        props.put(k, v);
                    }
                }
            }
            if (!isBlockId(name)) {
                throw new IllegalArgumentException("方块 id 要写成 namespace:path（小写字母/数字/_-./），现在是「"
                        + name + "」");
            }
            return new State(name, props);
        }

        /** 是不是合法的 {@code namespace:path}。 */
        public static boolean isBlockId(String s) {
            if (s == null) {
                return false;
            }
            final int colon = s.indexOf(':');
            if (colon <= 0 || colon == s.length() - 1 || s.indexOf(':', colon + 1) >= 0) {
                return false;
            }
            for (int i = 0; i < s.length(); i++) {
                final char c = s.charAt(i);
                final boolean ok = (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                        || c == '_' || c == '-' || c == '.' || c == ':' || c == '/';
                if (!ok) {
                    return false;
                }
            }
            return true;
        }

        private static boolean isName(String s) {
            if (s == null || s.isEmpty()) {
                return false;
            }
            for (int i = 0; i < s.length(); i++) {
                final char c = s.charAt(i);
                if (!((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_' || c == '-')) {
                    return false;
                }
            }
            return true;
        }
    }

    /** 一次编辑的结果（{@code changed=false} 表示"本来就是它"）。 */
    public record Result(boolean ok, boolean changed, String error, List<String> notes) {
        static Result fail(String error) {
            return new Result(false, false, error, List.of());
        }

        static Result ok(boolean changed, List<String> notes) {
            return new Result(true, changed, "", notes == null ? List.of() : notes);
        }

        public String line() {
            return "ok=" + this.ok + " changed=" + this.changed
                    + (this.error == null || this.error.isEmpty() ? "" : " error=" + this.error)
                    + (this.notes == null || this.notes.isEmpty() ? "" : " notes=" + this.notes);
        }
    }

    // ================================================================== 读当前方块

    /**
     * 目标位置现在的方块（palette 里的 {@code Name[Properties]}）。
     *
     * @return 区块 / section 缺失 ⇒ 空气（{@code minecraft:air}）
     */
    public static State blockAt(CompoundTag chunk, Pos pos) {
        if (chunk == null || pos == null) {
            return State.air();
        }
        final CompoundTag section = findSection(chunk, pos.sectionY());
        if (section == null) {
            return State.air();
        }
        final Tag bsTag = section.get("block_states");
        if (!(bsTag instanceof CompoundTag bs)) {
            return State.air();
        }
        final ListTag palette = bs.getList("palette", 10);
        if (palette.isEmpty()) {
            return State.air();
        }
        final State[] states = decodeSection(section);
        final int idx = pos.index();
        final State s = states[idx];
        return s == null ? State.air() : s;
    }

    /** 目标位置现在有没有方块实体；有就返回它的 {@code id}（没有 ⇒ 空串）。 */
    public static String blockEntityIdAt(CompoundTag chunk, Pos pos) {
        final CompoundTag be = findBlockEntity(chunk, pos);
        return be == null ? "" : be.getString("id");
    }

    // ================================================================== 改方块

    /**
     * 把一个方块设成 {@code target}。
     *
     * @param clearBlockEntity 新方块"没有方块实体"时是否顺手清掉旧的（面板在游戏里按
     *                         {@code BlockState#hasBlockEntity()} 决定；离线判据直接给）
     */
    public static Result setBlock(CompoundTag chunk, Pos pos, State target, boolean clearBlockEntity) {
        final List<String> notes = new ArrayList<>();
        if (chunk == null) {
            return Result.fail("chunk_null");
        }
        if (pos == null || target == null) {
            return Result.fail("pos_or_state_null");
        }
        if (pos.y() < -2048 || pos.y() > 2047) {
            return Result.fail("Y=" + pos.y() + " 超出可编辑范围 [-2048, 2047]");
        }
        if ((pos.x() >> 4) != chunk.getInt("xPos") || (pos.z() >> 4) != chunk.getInt("zPos")) {
            return Result.fail("坐标 " + pos.line() + " 不在这个区块里（xPos=" + chunk.getInt("xPos")
                    + ", zPos=" + chunk.getInt("zPos") + "）");
        }
        final int sy = pos.sectionY();
        CompoundTag section = findSection(chunk, sy);
        if (section == null) {
            section = new CompoundTag();
            section.putByte("Y", (byte) sy);
            final CompoundTag bs = new CompoundTag();
            bs.put("palette", paletteOf(List.of(target)));
            section.put("block_states", bs);
            insertSection(chunk, section);
            notes.add("新建了 Y=" + sy + " 这一层（该层原来整层是空气）");
            if (clearBlockEntity) {
                removeBlockEntity(chunk, pos, notes);
            }
            return Result.ok(true, notes);
        }
        CompoundTag bs = section.contains("block_states", 10) ? section.getCompound("block_states") : null;
        if (bs == null) {
            bs = new CompoundTag();
            bs.put("palette", paletteOf(List.of(State.air())));
            section.put("block_states", bs);
            notes.add("Y=" + sy + " 这一层没有 block_states ⇒ 按整层空气补上");
        }
        final List<State> palette = paletteOf(bs.getList("palette", 10));
        if (palette.isEmpty()) {
            palette.add(State.air());
            notes.add("Y=" + sy + " 这一层的 palette 是空的 ⇒ 按空气补上");
        }
        final State current = decodeSection(section)[pos.index()];
        if (sameState(current, target)) {
            if (clearBlockEntity && removeBlockEntity(chunk, pos, notes)) {
                return Result.ok(true, notes);
            }
            notes.add("这个位置本来就是 " + target);
            return Result.ok(false, notes);
        }
        // 找/加 palette 下标
        int idx = indexOf(palette, target);
        if (idx < 0) {
            idx = palette.size();
            palette.add(target);
        }
        final int[] values = decodeIndices(section, palette.size());
        values[pos.index()] = idx;
        final int bits = bitsFor(palette.size());
        // 写回
        bs.put("palette", paletteOf(palette));
        if (palette.size() <= 1) {
            bs.remove("data");
        } else {
            bs.put("data", new LongArrayTag(pack(values, bits)));
        }
        section.put("block_states", bs);
        if (clearBlockEntity) {
            removeBlockEntity(chunk, pos, notes);
        }
        return Result.ok(true, notes);
    }

    // ================================================================== 方块实体

    /** 找目标位置的方块实体（没有 ⇒ {@code null}）。 */
    public static CompoundTag findBlockEntity(CompoundTag chunk, Pos pos) {
        if (chunk == null || pos == null) {
            return null;
        }
        final ListTag list = chunk.getList(BLOCK_ENTITIES, 10);
        for (int i = 0; i < list.size(); i++) {
            final CompoundTag be = list.getCompound(i);
            if (be.getInt("x") == pos.x() && be.getInt("y") == pos.y() && be.getInt("z") == pos.z()) {
                return be;
            }
        }
        return null;
    }

    /** 清空目标位置的方块实体（返回真的删掉了几条）。 */
    public static int clearBlockEntity(CompoundTag chunk, Pos pos) {
        if (chunk == null || pos == null) {
            return 0;
        }
        final ListTag old = chunk.getList(BLOCK_ENTITIES, 10);
        final ListTag kept = new ListTag();
        int removed = 0;
        for (int i = 0; i < old.size(); i++) {
            final CompoundTag be = old.getCompound(i);
            if (be.getInt("x") == pos.x() && be.getInt("y") == pos.y() && be.getInt("z") == pos.z()) {
                removed++;
                continue;
            }
            kept.add(be);
        }
        if (removed > 0) {
            chunk.put(BLOCK_ENTITIES, kept);
        }
        return removed;
    }

    /**
     * 用一整份 NBT <b>替换</b>目标位置的方块实体（第一版只做这一种：不做逐字段编辑）。
     *
     * <p>强制覆盖 {@code x/y/z}，并要求 {@code id} 是 {@code namespace:path}。
     */
    public static Result replaceBlockEntity(CompoundTag chunk, Pos pos, CompoundTag be) {
        final List<String> notes = new ArrayList<>();
        if (chunk == null || pos == null || be == null) {
            return Result.fail("chunk_or_be_null");
        }
        final String id = be.getString("id");
        if (!State.isBlockId(id)) {
            return Result.fail("方块实体 NBT 必须带合法的 id（namespace:path），现在是「" + id + "」");
        }
        be.putInt("x", pos.x());
        be.putInt("y", pos.y());
        be.putInt("z", pos.z());
        final ListTag old = chunk.getList(BLOCK_ENTITIES, 10);
        final ListTag out = new ListTag();
        boolean placed = false;
        for (int i = 0; i < old.size(); i++) {
            final CompoundTag e = old.getCompound(i);
            if (e.getInt("x") == pos.x() && e.getInt("y") == pos.y() && e.getInt("z") == pos.z()) {
                if (!placed) {
                    out.add(be);
                    placed = true;
                }
                notes.add("替换了原有的方块实体 " + e.getString("id"));
                continue;
            }
            out.add(e);
        }
        if (!placed) {
            out.add(be);
            notes.add("这个位置原来没有方块实体 ⇒ 新增一条");
        }
        chunk.put(BLOCK_ENTITIES, out);
        return Result.ok(true, notes);
    }

    private static boolean removeBlockEntity(CompoundTag chunk, Pos pos, List<String> notes) {
        final int n = clearBlockEntity(chunk, pos);
        if (n > 0 && notes != null) {
            notes.add("顺手清掉了这个位置原来的方块实体（新方块不是方块实体）");
        }
        return n > 0;
    }

    /** 一行方块实体摘要（面板/日志用）。 */
    public static String blockEntityLine(CompoundTag chunk, Pos pos) {
        final CompoundTag be = findBlockEntity(chunk, pos);
        if (be == null) {
            return "";
        }
        return be.getString("id") + "（" + be.getAllKeys().size() + " 个键）";
    }

    // ================================================================== section / palette

    /** 找 Y 层对应的 section。 */
    public static CompoundTag findSection(CompoundTag chunk, int sectionY) {
        if (chunk == null) {
            return null;
        }
        final ListTag sections = chunk.getList(SECTIONS, 10);
        for (int i = 0; i < sections.size(); i++) {
            final CompoundTag s = sections.getCompound(i);
            if ((s.getByte("Y") & 0xFF) == (sectionY & 0xFF)) {
                return s;
            }
        }
        return null;
    }

    /** 按 Y 升序插入一个 section。 */
    private static void insertSection(CompoundTag chunk, CompoundTag section) {
        final ListTag sections = chunk.getList(SECTIONS, 10);
        final List<CompoundTag> list = new ArrayList<>();
        for (int i = 0; i < sections.size(); i++) {
            list.add(sections.getCompound(i));
        }
        list.add(section);
        list.sort(Comparator.comparingInt(s -> s.getByte("Y")));
        final ListTag out = new ListTag();
        for (CompoundTag s : list) {
            out.add(s);
        }
        chunk.put(SECTIONS, out);
    }

    /** palette 列表 → {@code List<State>}。 */
    public static List<State> paletteOf(ListTag palette) {
        final List<State> out = new ArrayList<>();
        if (palette == null) {
            return out;
        }
        for (int i = 0; i < palette.size(); i++) {
            out.add(State.of(palette.getCompound(i)));
        }
        return out;
    }

    /** {@code List<State>} → palette 列表。 */
    public static ListTag paletteOf(List<State> states) {
        final ListTag out = new ListTag();
        for (State s : states) {
            out.add(s.toTag());
        }
        return out;
    }

    /** 在 palette 里找同一个状态（Name 与 Properties 完全相同）。 */
    public static int indexOf(List<State> palette, State target) {
        for (int i = 0; i < palette.size(); i++) {
            if (sameState(palette.get(i), target)) {
                return i;
            }
        }
        return -1;
    }

    /** 两个状态是不是同一个（属性表按 map 比，与顺序无关）。 */
    public static boolean sameState(State a, State b) {
        if (a == null || b == null) {
            return a == b;
        }
        if (!a.name().equals(b.name())) {
            return false;
        }
        final Map<String, String> pa = a.props() == null ? Map.of() : a.props();
        final Map<String, String> pb = b.props() == null ? Map.of() : b.props();
        return pa.equals(pb);
    }

    // ================================================================== 位打包

    /** 一个调色板大小需要多少位（palette ≤ 1 按 4 位算，与 {@code PalettedContainer} 的 MIN_BITS 一致）。 */
    public static int bitsFor(int paletteSize) {
        if (paletteSize <= 1) {
            return 4;
        }
        return Math.max(4, ceilLog2(paletteSize));
    }

    /** ⌈log2(v)⌉（v ≥ 1）：17→5、16→4、2→1。 */
    public static int ceilLog2(int v) {
        if (v <= 1) {
            return 0;
        }
        return 32 - Integer.numberOfLeadingZeros(v - 1);
    }

    /** 一个 long 里放几个值。 */
    public static int valuesPerLong(int bits) {
        return 64 / bits;
    }

    /** 这么多值需要多少个 long。 */
    public static int expectedLongs(int bits, int count) {
        return (count + valuesPerLong(bits) - 1) / valuesPerLong(bits);
    }

    /** 按位打包（低位在前、值不跨 long）。 */
    public static long[] pack(int[] values, int bits) {
        final int vpl = valuesPerLong(bits);
        final long mask = (1L << bits) - 1L;
        final long[] out = new long[(values.length + vpl - 1) / vpl];
        for (int i = 0; i < values.length; i++) {
            final int li = i / vpl;
            final int off = (i % vpl) * bits;
            out[li] |= ((long) values[i] & mask) << off;
        }
        return out;
    }

    /** 按位解包（与 {@link #pack} 严格互逆）。 */
    public static int[] unpack(long[] data, int bits, int count) {
        final int vpl = valuesPerLong(bits);
        final long mask = (1L << bits) - 1L;
        final int[] out = new int[count];
        for (int i = 0; i < count; i++) {
            final int li = i / vpl;
            final int off = (i % vpl) * bits;
            final long word = li < data.length ? data[li] : 0L;
            out[i] = (int) ((word >>> off) & mask);
        }
        return out;
    }

    /** 解出一层 4096 个方块状态（缺失 / 单色 palette ⇒ 全同）。 */
    public static State[] decodeSection(CompoundTag section) {
        final State[] out = new State[SECTION_VOLUME];
        if (section == null) {
            Arrays.fill(out, State.air());
            return out;
        }
        final Tag bsTag = section.get("block_states");
        if (!(bsTag instanceof CompoundTag bs)) {
            Arrays.fill(out, State.air());
            return out;
        }
        final List<State> palette = paletteOf(bs.getList("palette", 10));
        if (palette.isEmpty()) {
            Arrays.fill(out, State.air());
            return out;
        }
        if (palette.size() == 1 || !bs.contains("data")) {
            Arrays.fill(out, palette.get(0));
            return out;
        }
        final long[] data = bs.getLongArray("data");
        int bits = bitsFor(palette.size());
        if (data.length != expectedLongs(bits, SECTION_VOLUME)) {
            // 防御：palette 大小与数组长度对不上时，按数组长度反推位宽（并在读值处夹到 palette 范围内）
            final int derived = deriveBits(data.length);
            if (derived > 0) {
                bits = derived;
            }
        }
        final int[] idx = unpack(data, bits, SECTION_VOLUME);
        for (int i = 0; i < SECTION_VOLUME; i++) {
            final int k = idx[i];
            out[i] = k >= 0 && k < palette.size() ? palette.get(k) : State.air();
        }
        return out;
    }

    /** 解出一层 4096 个调色板下标（缺失 ⇒ 全 0）。 */
    public static int[] decodeIndices(CompoundTag section, int paletteSize) {
        final int[] out = new int[SECTION_VOLUME];
        if (section == null) {
            return out;
        }
        final Tag bsTag = section.get("block_states");
        if (!(bsTag instanceof CompoundTag bs) || paletteSize <= 1 || !bs.contains("data")) {
            return out;
        }
        final long[] data = bs.getLongArray("data");
        int bits = bitsFor(paletteSize);
        if (data.length != expectedLongs(bits, SECTION_VOLUME)) {
            final int derived = deriveBits(data.length);
            if (derived > 0) {
                bits = derived;
            }
        }
        final int[] idx = unpack(data, bits, SECTION_VOLUME);
        for (int i = 0; i < SECTION_VOLUME; i++) {
            out[i] = idx[i] >= 0 && idx[i] < paletteSize ? idx[i] : 0;
        }
        return out;
    }

    /** 由 long 数组长度反推位宽（4..8；对不上 ⇒ -1）。 */
    public static int deriveBits(int dataLength) {
        for (int bits = 4; bits <= 8; bits++) {
            if (expectedLongs(bits, SECTION_VOLUME) == dataLength) {
                return bits;
            }
        }
        return -1;
    }

    // ================================================================== 校验（判据 ①）

    /** 校验读数。 */
    public record Verify(boolean ok, List<String> problems, List<String> changes) {
        public String line() {
            return "ok=" + this.ok + " changes=" + this.changes
                    + (this.problems.isEmpty() ? "" : " problems=" + this.problems);
        }
    }

    /**
     * <b>判据 ①</b>：改完的区块里只有目标方块变了。
     *
     * <p>三段：
     * <ol>
     *   <li>把目标 section 与 {@code block_entities} 从两份 NBT 里摘掉之后，
     *       {@code diffTags} 必须为空 ⇒ 其余 section、其余所有键（Heightmaps/entities/biomes…）逐条等价；</li>
     *   <li>目标 section 的 4096 格里，<b>恰好 1 格</b>变了，且它就在目标下标上；</li>
     *   <li>那一格的新值必须等于预期状态。</li>
     * </ol>
     */
    public static Verify verifyBlock(CompoundTag before, CompoundTag after, Pos pos, State expected) {
        final List<String> problems = new ArrayList<>();
        final List<String> changes = new ArrayList<>();
        if (before == null || after == null || pos == null) {
            problems.add("before/after/pos 有空值");
            return new Verify(false, problems, changes);
        }
        final int sy = pos.sectionY();
        final List<String> outside = diffOutside(before, after, sy, true);
        if (!outside.isEmpty()) {
            problems.add("目标 section 之外还有 " + outside.size() + " 处不同：" + clamp(outside, 6));
        }
        final State[] b = decodeSection(findSection(before, sy));
        final State[] a = decodeSection(findSection(after, sy));
        int diffs = 0;
        int at = -1;
        for (int i = 0; i < SECTION_VOLUME; i++) {
            if (!sameState(b[i], a[i])) {
                diffs++;
                at = i;
            }
        }
        if (diffs != 1) {
            problems.add("目标 section 里改了 " + diffs + " 格（判据要求恰好 1 格）");
        } else if (at != pos.index()) {
            problems.add("变的是下标 " + at + "，不是目标下标 " + pos.index());
        } else if (expected != null && !sameState(a[at], expected)) {
            problems.add("目标下标的新值是 " + a[at] + "，预期 " + expected);
        } else {
            changes.add("[" + pos.line() + "] " + b[at] + " -> " + a[at]);
        }
        // 顺手把 palette 的变化也记下来（人看：是不是只加了一项）
        final int paletteBefore = paletteSize(findSection(before, sy));
        final int paletteAfter = paletteSize(findSection(after, sy));
        if (paletteBefore != paletteAfter) {
            changes.add("palette " + paletteBefore + " -> " + paletteAfter);
        }
        return new Verify(problems.isEmpty(), problems, changes);
    }

    /**
     * <b>方块实体</b>的校验：摘掉 {@code block_entities} 后其余 NBT 必须逐条等价；
     * 目标位置那一条要么被删、要么被替换成预期，其它条目逐条等价。
     */
    public static Verify verifyBlockEntity(CompoundTag before, CompoundTag after, Pos pos, CompoundTag expected,
                                           boolean shouldExist) {
        final List<String> problems = new ArrayList<>();
        final List<String> changes = new ArrayList<>();
        if (before == null || after == null || pos == null) {
            problems.add("before/after/pos 有空值");
            return new Verify(false, problems, changes);
        }
        final List<String> outside = diffOutside(before, after, Integer.MIN_VALUE, true);
        if (!outside.isEmpty()) {
            problems.add("方块实体之外还有 " + outside.size() + " 处不同：" + clamp(outside, 6));
        }
        final List<CompoundTag> b = entriesExcept(before, pos);
        final List<CompoundTag> a = entriesExcept(after, pos);
        if (b.size() != a.size() || !sameEntries(b, a)) {
            problems.add("其它位置的方块实体条目被动了（before=" + b.size() + " after=" + a.size() + "）");
        }
        final CompoundTag now = findBlockEntity(after, pos);
        if (shouldExist) {
            if (now == null) {
                problems.add("预期有方块实体，但改完之后没有");
            } else if (expected != null) {
                final List<String> d = ShanhaiPlayerDataEditor.diffTags(expected, now);
                if (!d.isEmpty()) {
                    problems.add("方块实体与预期不一致：" + clamp(d, 6));
                }
            }
            changes.add("[" + pos.line() + "] 方块实体 -> " + (now == null ? "?" : now.getString("id")));
        } else if (now != null) {
            problems.add("预期清空方块实体，但改完之后还在：" + now.getString("id"));
        } else {
            changes.add("[" + pos.line() + "] 方块实体已清空");
        }
        return new Verify(problems.isEmpty(), problems, changes);
    }

    /**
     * 两份区块 NBT 的差异，<b>摘掉</b>目标 section（以及可选地 {@code block_entities}）之后再比。
     *
     * @param sectionY        {@link Integer#MIN_VALUE} = 不摘任何 section
     * @param dropBlockEntity 是否整体摘掉 {@code block_entities}
     */
    public static List<String> diffOutside(CompoundTag before, CompoundTag after, int sectionY,
                                           boolean dropBlockEntity) {
        final CompoundTag a = sanitize(before, sectionY, dropBlockEntity);
        final CompoundTag b = sanitize(after, sectionY, dropBlockEntity);
        return ShanhaiPlayerDataEditor.diffTags(a, b);
    }

    private static CompoundTag sanitize(CompoundTag chunk, int sectionY, boolean dropBlockEntity) {
        final CompoundTag copy = chunk.copy();
        if (dropBlockEntity) {
            copy.remove(BLOCK_ENTITIES);
        }
        if (sectionY != Integer.MIN_VALUE) {
            final ListTag sections = copy.getList(SECTIONS, 10);
            final ListTag kept = new ListTag();
            for (int i = 0; i < sections.size(); i++) {
                final CompoundTag s = sections.getCompound(i);
                if ((s.getByte("Y") & 0xFF) != (sectionY & 0xFF)) {
                    kept.add(s);
                }
            }
            copy.put(SECTIONS, kept);
        }
        return copy;
    }

    /** 目标位置以外的方块实体条目。 */
    private static List<CompoundTag> entriesExcept(CompoundTag chunk, Pos pos) {
        final List<CompoundTag> out = new ArrayList<>();
        final ListTag list = chunk.getList(BLOCK_ENTITIES, 10);
        for (int i = 0; i < list.size(); i++) {
            final CompoundTag be = list.getCompound(i);
            if (be.getInt("x") == pos.x() && be.getInt("y") == pos.y() && be.getInt("z") == pos.z()) {
                continue;
            }
            out.add(be);
        }
        return out;
    }

    private static boolean sameEntries(List<CompoundTag> a, List<CompoundTag> b) {
        if (a.size() != b.size()) {
            return false;
        }
        for (int i = 0; i < a.size(); i++) {
            if (!ShanhaiPlayerDataEditor.diffTags(a.get(i), b.get(i)).isEmpty()) {
                return false;
            }
        }
        return true;
    }

    private static int paletteSize(CompoundTag section) {
        if (section == null) {
            return 0;
        }
        final Tag bs = section.get("block_states");
        return bs instanceof CompoundTag c ? c.getList("palette", 10).size() : 0;
    }

    private static String clamp(List<String> list, int max) {
        if (list.size() <= max) {
            return list.toString();
        }
        return list.subList(0, max) + "…(+" + (list.size() - max) + ")";
    }

    // ================================================================== 自检（口径）

    /**
     * 位打包口径的自证（离线判据调用；也可在游戏里跑）。
     *
     * <p>对区块里每一个 section：解出来再按同一位宽打包回去，结果必须与原 {@code data}
     * <b>逐字节相同</b>；再加一组人工构造的正/负对照（{@code pack→unpack} 必须还原、
     * 改一个值必须变）。
     *
     * @return {@code null} = 全过；否则是第一条失败说明
     */
    public static String selfCheckPacking(CompoundTag chunk) {
        // A1 pack/unpack 互逆
        for (int bits = 4; bits <= 8; bits++) {
            final int[] v = new int[SECTION_VOLUME];
            for (int i = 0; i < v.length; i++) {
                v[i] = i % (1 << bits);
            }
            final int[] back = unpack(pack(v, bits), bits, v.length);
            if (!Arrays.equals(v, back)) {
                return "pack/unpack 在 bits=" + bits + " 下不互逆";
            }
        }
        // A2 负对照：故意错一位宽，必须解出不同的东西（否则自检看不见错）
        final int[] v = new int[SECTION_VOLUME];
        for (int i = 0; i < v.length; i++) {
            v[i] = i % 32;
        }
        if (Arrays.equals(unpack(pack(v, 5), 5, v.length), unpack(pack(v, 5), 4, v.length))) {
            return "负对照失效：5 位与 4 位解出来一样";
        }
        if (chunk == null) {
            return null;
        }
        // A3 真实数据：解开再打包必须逐字节等于原数组
        final ListTag sections = chunk.getList(SECTIONS, 10);
        for (int i = 0; i < sections.size(); i++) {
            final CompoundTag s = sections.getCompound(i);
            final Tag bsTag = s.get("block_states");
            if (!(bsTag instanceof CompoundTag bs) || !bs.contains("data")) {
                continue;
            }
            final List<State> palette = paletteOf(bs.getList("palette", 10));
            final long[] data = bs.getLongArray("data");
            final int bits = bitsFor(palette.size());
            final int[] values = unpack(data, bits, SECTION_VOLUME);
            final long[] again = pack(values, bits);
            if (!Arrays.equals(data, again)) {
                return "section Y=" + s.getByte("Y") + " 解开再打包与原 data 不一致（palette="
                        + palette.size() + " bits=" + bits + " longs=" + data.length + "）";
            }
        }
        return null;
    }
}
