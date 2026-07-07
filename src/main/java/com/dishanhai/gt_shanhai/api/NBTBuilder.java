package com.dishanhai.gt_shanhai.api;

import net.minecraft.nbt.ByteTag;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.FloatTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongTag;
import net.minecraft.nbt.ShortTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.nbt.TagParser;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * 通用 NBT 构建器 — 链式 API，支持任意嵌套结构。
 * Rhino 兼容（无 lambda），通过 push/pop 管理嵌套层级。
 * 所有数字方法接收 Number，兼容 Rhino 传 int/double/long。
 *
 * 用法示例（JS 侧）：
 * <pre>
 * var NBTBuilder = Java.loadClass('com.dishanhai.gt_shanhai.api.NBTBuilder');
 *
 * // 1. 从零构建
 * var nbt = NBTBuilder.create()
 *     .putString('mode', 'PROCESSING')
 *     .putByte('crafting', 1)
 *     .pushList('encodedInputs')
 *         .addPatternEntry(4, 'ae2:i', 'minecraft:beef')
 *         .addPatternEntryFromString('1000x gtceu:milk')
 *     .pop()
 *     .pushCompound('accessPoint')
 *         .putString('dimension', 'minecraft:overworld')
 *         .putIntArray('pos', [6, 68, 6])
 *     .pop()
 *     .build();
 * Item.of('ae2wtlib:wireless_universal_terminal', nbt);
 *
 * // 2. 从现有 SNBT 修改（游戏复制的 NBT）
 * var nbt2 = NBTBuilder.parse('{mode:"PROCESSING",crafting:1b,...}')
 *     .putString('mode', 'CRAFTING')
 *     .remove('craft_if_missing')
 *     .build();
 *
 * // 3. 嵌套 List 中的 Compound
 * NBTBuilder.create()
 *     .pushList('upgrades')
 *         .addCompound()
 *             .putByte('Count', 1)
 *             .putInt('Slot', 0)
 *             .putString('id', 'ae2wtlib:quantum_bridge_card')
 *         .pop()
 *         .addCompound()
 *             .putByte('Count', 1)
 *             .putInt('Slot', 1)
 *             .putString('id', 'ae2wtlib:magnet_card')
 *         .pop()
 *     .pop()
 *     .build();
 * </pre>
 */
public class NBTBuilder {

    private static class Frame {
        final boolean isList;
        final CompoundTag compound;
        final ListTag list;
        Frame(CompoundTag c) { isList = false; compound = c; list = null; }
        Frame(ListTag l) { isList = true; compound = null; list = l; }
    }

    private final Deque<Frame> stack = new ArrayDeque<>();

    private NBTBuilder() {
        stack.push(new Frame(new CompoundTag()));
    }

    private NBTBuilder(CompoundTag root) {
        stack.push(new Frame(root));
    }

    // ========== 创建 ==========

    public static NBTBuilder create() {
        return new NBTBuilder();
    }

    /**
     * 从 SNBT 字符串解析（游戏复制的 NBT），失败返回空 builder。
     * 自动兼容以下格式：
     *   - "{...}"               标准 SNBT
     *   - ",tag:{...}"          KJS 粘贴板格式（带逗号前缀）
     *   - "tag:{...}"           带 tag: 前缀
     *   - ",{...}"              带逗号前缀
     */
    public static NBTBuilder parse(String snbt) {
        if (snbt == null || snbt.isEmpty()) return new NBTBuilder();
        String cleaned = snbt.trim();
        // 去掉开头的逗号
        if (cleaned.startsWith(",")) cleaned = cleaned.substring(1).trim();
        // 剥离 tag: 前缀，取后面的 {...}
        if (cleaned.startsWith("tag:")) cleaned = cleaned.substring(4).trim();
        try {
            Tag tag = TagParser.parseTag(cleaned);
            if (tag instanceof CompoundTag) return new NBTBuilder((CompoundTag) tag);
        } catch (Exception ignored) {}
        return new NBTBuilder();
    }

    // ========== 内部辅助 ==========

    private CompoundTag currentCompound() {
        Frame f = stack.peek();
        if (f == null || f.isList) throw new IllegalStateException("当前不在 Compound 层");
        return f.compound;
    }

    private ListTag currentList() {
        Frame f = stack.peek();
        if (f == null || !f.isList) throw new IllegalStateException("当前不在 List 层");
        return f.list;
    }

    // ========== Compound 层：基本类型 ==========

    public NBTBuilder putString(String key, String val) {
        currentCompound().putString(key, val != null ? val : "");
        return this;
    }

    public NBTBuilder putByte(String key, Number val) {
        currentCompound().putByte(key, val != null ? val.byteValue() : (byte) 0);
        return this;
    }

    public NBTBuilder putShort(String key, Number val) {
        currentCompound().putShort(key, val != null ? val.shortValue() : (short) 0);
        return this;
    }

    public NBTBuilder putInt(String key, Number val) {
        currentCompound().putInt(key, val != null ? val.intValue() : 0);
        return this;
    }

    public NBTBuilder putLong(String key, Number val) {
        currentCompound().putLong(key, val != null ? val.longValue() : 0L);
        return this;
    }

    public NBTBuilder putFloat(String key, Number val) {
        currentCompound().putFloat(key, val != null ? val.floatValue() : 0f);
        return this;
    }

    public NBTBuilder putDouble(String key, Number val) {
        currentCompound().putDouble(key, val != null ? val.doubleValue() : 0d);
        return this;
    }

    public NBTBuilder putBoolean(String key, boolean val) {
        currentCompound().putByte(key, (byte) (val ? 1 : 0));
        return this;
    }

    // ========== Compound 层：数组 ==========

    public NBTBuilder putIntArray(String key, int[] val) {
        currentCompound().putIntArray(key, val != null ? val : new int[0]);
        return this;
    }

    public NBTBuilder putLongArray(String key, long[] val) {
        currentCompound().putLongArray(key, val != null ? val : new long[0]);
        return this;
    }

    public NBTBuilder putByteArray(String key, byte[] val) {
        currentCompound().putByteArray(key, val != null ? val : new byte[0]);
        return this;
    }

    /** 从 JS 数组设置 int[]（Rhino List 转 int[]） */
    public NBTBuilder putIntArrayFromList(String key, java.util.List<Integer> val) {
        if (val == null) { currentCompound().putIntArray(key, new int[0]); return this; }
        int[] arr = new int[val.size()];
        for (int i = 0; i < val.size(); i++) arr[i] = val.get(i) != null ? val.get(i) : 0;
        currentCompound().putIntArray(key, arr);
        return this;
    }

    /** 从 JS 数组设置 long[]（Rhino List 转 long[]） */
    public NBTBuilder putLongArrayFromList(String key, java.util.List<Number> val) {
        if (val == null) { currentCompound().putLongArray(key, new long[0]); return this; }
        long[] arr = new long[val.size()];
        for (int i = 0; i < val.size(); i++) arr[i] = val.get(i) != null ? val.get(i).longValue() : 0L;
        currentCompound().putLongArray(key, arr);
        return this;
    }

    // ========== 嵌套 Compound ==========

    /** 在当前 Compound 中创建子 Compound 并进入 */
    public NBTBuilder pushCompound(String key) {
        CompoundTag child = new CompoundTag();
        currentCompound().put(key, child);
        stack.push(new Frame(child));
        return this;
    }

    // ========== 嵌套 List ==========

    /** 在当前 Compound 中创建子 List 并进入 */
    public NBTBuilder pushList(String key) {
        ListTag child = new ListTag();
        currentCompound().put(key, child);
        stack.push(new Frame(child));
        return this;
    }

    // ========== List 层：添加基本类型 ==========

    public NBTBuilder addString(String val) {
        currentList().add(StringTag.valueOf(val != null ? val : ""));
        return this;
    }

    public NBTBuilder addByte(Number val) {
        currentList().add(ByteTag.valueOf(val != null ? val.byteValue() : (byte) 0));
        return this;
    }

    public NBTBuilder addShort(Number val) {
        currentList().add(ShortTag.valueOf(val != null ? val.shortValue() : (short) 0));
        return this;
    }

    public NBTBuilder addInt(Number val) {
        currentList().add(IntTag.valueOf(val != null ? val.intValue() : 0));
        return this;
    }

    public NBTBuilder addLong(Number val) {
        currentList().add(LongTag.valueOf(val != null ? val.longValue() : 0L));
        return this;
    }

    public NBTBuilder addFloat(Number val) {
        currentList().add(FloatTag.valueOf(val != null ? val.floatValue() : 0f));
        return this;
    }

    public NBTBuilder addDouble(Number val) {
        currentList().add(DoubleTag.valueOf(val != null ? val.doubleValue() : 0d));
        return this;
    }

    /** 在当前 List 中添加空 Compound 并进入（用于 List 中的嵌套对象） */
    public NBTBuilder addCompound() {
        CompoundTag child = new CompoundTag();
        currentList().add(child);
        stack.push(new Frame(child));
        return this;
    }

    /** 在当前 List 中添加子 List 并进入 */
    public NBTBuilder addList() {
        ListTag child = new ListTag();
        currentList().add(child);
        stack.push(new Frame(child));
        return this;
    }

    // ========== AE2 样板编码便捷方法 ==========

    /**
     * 添加 AE2 样板条目到当前 List。
     * 格式: {"#":amountL,"#c":category,"id":itemId}
     *
     * @param amount   数量
     * @param category "ae2:i"（物品）或 "ae2:f"（流体）
     * @param itemId   物品/流体 ID
     */
    public NBTBuilder addPatternEntry(long amount, String category, String itemId) {
        CompoundTag entry = new CompoundTag();
        entry.putLong("#", amount);
        entry.putString("#c", category);
        entry.putString("id", itemId);
        currentList().add(entry);
        return this;
    }

    /**
     * 从字符串解析并添加 AE2 样板条目，自动判断 item/fluid。
     * 支持格式: "4x minecraft:beef" / "1000x gtceu:milk" / "minecraft:beef"
     */
    public NBTBuilder addPatternEntryFromString(String entry) {
        if (entry == null || entry.isEmpty()) return this;
        String trimmed = entry.trim();
        long amount = 1;
        String itemId = trimmed;
        int spaceIdx = trimmed.indexOf(' ');
        if (spaceIdx > 0) {
            String prefix = trimmed.substring(0, spaceIdx);
            if (prefix.endsWith("x") || prefix.endsWith("X")) {
                try {
                    amount = Long.parseLong(prefix.substring(0, prefix.length() - 1));
                    itemId = trimmed.substring(spaceIdx + 1).trim();
                } catch (NumberFormatException ignored) {}
            }
        }
        String category = DShanhaiNBTAPI.isRegisteredFluid(itemId) ? "ae2:f" : "ae2:i";
        return addPatternEntry(amount, category, itemId);
    }

    /** 批量添加 AE2 样板条目（JS 数组） */
    public NBTBuilder addPatternEntriesFromList(java.util.List<String> entries) {
        if (entries == null) return this;
        for (Object e : entries) addPatternEntryFromString(e != null ? e.toString() : "");
        return this;
    }

    /** 添加空 {} 占位（AE2 样板空槽位），count 为数量 */
    public NBTBuilder addEmptyEntries(int count) {
        ListTag list = currentList();
        for (int i = 0; i < count; i++) list.add(new CompoundTag());
        return this;
    }

    /**
     * 填充 AE2 样板槽位到固定大小，剩余位置用空 {} 补齐。
     * @param totalSlots 总槽位数（如 processing_pattern 输入=81，输出=27）
     */
    public NBTBuilder fillPatternSlots(int totalSlots) {
        ListTag list = currentList();
        int current = list.size();
        if (current < totalSlots) {
            for (int i = current; i < totalSlots; i++) list.add(new CompoundTag());
        }
        return this;
    }

    // ========== AE2 处理样板便捷构建 ==========

    /**
     * 构建 AE2 处理样板 NBT（ae2:processing_pattern）。
     * 自动填充 in=81 槽、out=27 槽，空位补 {}。
     *
     * @param encodePlayer 编码玩家名（可 null）
     * @param inputs       输入条目，格式 ["14x kubejs:hv_universal_circuit", "64x dishanhai:photon", ...]
     * @param outputs      输出条目，格式 ["1x dishanhai:wl_board_hv", ...]
     * @return SNBT 字符串，可直接 Item.of('ae2:processing_pattern', nbt)
     */
    public static String buildProcessingPattern(String encodePlayer,
                                                 String[] inputs, String[] outputs) {
        NBTBuilder b = NBTBuilder.create();
        if (encodePlayer != null && !encodePlayer.isEmpty()) b.putString("encodePlayer", encodePlayer);
        b.pushList("in");
        if (inputs != null) for (String e : inputs) b.addPatternEntryFromString(e);
        b.fillPatternSlots(81);
        b.pop();
        b.pushList("out");
        if (outputs != null) for (String e : outputs) b.addPatternEntryFromString(e);
        b.fillPatternSlots(27);
        b.pop();
        return b.build();
    }

    // ========== 通用操作 ==========

    public NBTBuilder remove(String key) {
        currentCompound().remove(key);
        return this;
    }

    public boolean contains(String key) {
        return currentCompound().contains(key);
    }

    public String getString(String key) {
        return currentCompound().getString(key);
    }

    public int getInt(String key) {
        return currentCompound().getInt(key);
    }

    public long getLong(String key) {
        return currentCompound().getLong(key);
    }

    /** 返回上一层（从 Compound 或 List 退回父级） */
    public NBTBuilder pop() {
        if (stack.size() > 1) stack.pop();
        return this;
    }

    /** 回到 root 层（弹出所有嵌套层） */
    public NBTBuilder popAll() {
        while (stack.size() > 1) stack.pop();
        return this;
    }

    /** 当前嵌套深度（root=1） */
    public int depth() {
        return stack.size();
    }

    // ========== 输出 ==========

    public CompoundTag toTag() {
        while (stack.size() > 1) stack.pop();
        return stack.peek().compound;
    }

    public String build() {
        return toTag().toString();
    }

    @Override
    public String toString() {
        return build();
    }
}
