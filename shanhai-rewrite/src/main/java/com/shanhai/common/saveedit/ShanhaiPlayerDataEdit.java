package com.shanhai.common.saveedit;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * "想改成什么"（纯 JDK：不引用任何 NBT / Minecraft 类型）。
 *
 * <h2>一、空 = 不改</h2>
 * 每一个字段都是装箱类型，{@code null} 表示<b>这个字段不动</b>。面板上的输入框
 * 留空就是这个语义 —— 玩家只填想改的那几格，其余一律原样。
 *
 * <h2>二、为什么要有 {@link #parse(Map)} 而不直接在面板里 {@code parseInt}</h2>
 * 面板上打错字（{@code "3o"}、{@code "1.2.3"}）必须<b>当场报错并拒绝</b>，绝不静默当成
 * "不改" —— 后者会让玩家以为改好了、其实什么也没发生。所以 {@link #parse(Map)}
 * 把每一条解析失败都记进 {@link #errors}，调用方看到非空就拒绝暂存/拒绝确认。
 *
 * <h2>三、本期字段（用户定案的首期范围）</h2>
 * <table border="1">
 *   <tr><th>面板</th><th>playerdata 键</th><th>类型</th><th>范围</th></tr>
 *   <tr><td>血量</td><td>{@code Health}</td><td>float</td><td>[0, 1024]</td></tr>
 *   <tr><td>经验等级</td><td>{@code XpLevel}（连带 {@code XpTotal}）</td><td>int</td><td>[0, 21863]</td></tr>
 *   <tr><td>饥饿</td><td>{@code foodLevel}</td><td>int</td><td>[0, 20]</td></tr>
 *   <tr><td>饱和</td><td>{@code foodSaturationLevel}</td><td>float</td><td>[0, 20]</td></tr>
 *   <tr><td>游戏模式</td><td>{@code playerGameType}</td><td>int</td><td>[0, 3]</td></tr>
 *   <tr><td>维度</td><td>{@code Dimension}</td><td>string</td><td>{@code namespace:path}</td></tr>
 *   <tr><td>X/Y/Z</td><td>{@code Pos[0..2]}</td><td>double</td><td>±30,000,000</td></tr>
 *   <tr><td>yaw/pitch</td><td>{@code Rotation[0..1]}</td><td>float</td><td>±3600</td></tr>
 *   <tr><td>清空槽位</td><td>{@code Inventory} 里 {@code Slot} 命中者</td><td>int[]</td>
 *       <td>0–35 主背包 / 100–103 盔甲 / <b>-106</b> 副手（{@code 150} 是兼容别名）</td></tr>
 *   <tr><td>逐格补丁</td><td>{@code Inventory} / {@code EnderItems}</td><td>{@link SlotPatch}</td>
 *       <td>背包 41 格 / 末影箱 27 格</td></tr>
 * </table>
 *
 * <h2>四、物品栏 41 格 + 末影箱（第二期 · 只做数据层，不碰 UI）</h2>
 *
 * 槽号映射是<b>实测硬事实</b>（{@code temp\saveedit-p0\实体数据清单.md} §6.3：16 份真实档案、
 * 155 条 {@code Inventory} 条目，没有一条落在这个集合之外）：
 * <pre>
 *   0 .. 8      快捷栏 9 格（左→右 = 0→8）
 *   9 .. 35     主背包 3×9（9 是第一行最左）
 *   100 / 101 / 102 / 103     靴子 / 护腿 / 胸甲 / 头盔   ← 顺序是实测的，不是猜的
 *   -106        副手 1 格                                ← 🔴 负数！{@code Slot} 是 <b>byte</b>
 *   合计 9 + 27 + 4 + 1 = <b>41</b> 格
 * </pre>
 *
 * 🔴 <b>{@code Slot} 是 byte，副手是负数</b>：无符号读（{@code getByte("Slot") & 0xFF}）会得到
 * {@code 150}，那与 {@code -106} 是同一个字节、但不是同一个「槽号约定」。本类内部一律用
 * {@link #canonicalSlot(int)} 归一，{@link #slotOf} 那一侧读<b>有符号</b> byte。
 * {@code 150} 只作为<b>兼容别名</b>保留（面板"清空槽位"的提示文字写的就是 150，而面板不在本次范围）。
 *
 * <p><b>末影箱（{@code EnderItems}）与背包完全同形</b>：都是 {@code List<Compound>}，条目都是
 * {@code {Slot:<byte>, id:<string>, Count:<byte>, tag?:<compound>}}，区别只有「读哪个键」和
 * 「槽号合法集不同」。🔴 末影箱的槽号范围<b>在本机样本上未实测</b>（16/16 份真实档案的
 * {@code EnderItems} 都是空列表）⇒ 按原版 0..26 口径实现，并在结果里标注，见
 * {@link #ENDER_RANGE_VERIFIED}。
 *
 * <p><b>空格的表示</b>：该 {@code Slot} <b>根本没有条目</b> —— 不是 {@code {Slot:n}} 空壳，
 * 也不是 {@code id:"minecraft:air"}，更不是 {@code Count:0}。
 */
public final class ShanhaiPlayerDataEdit {

    /** 快捷栏 9 格（{@code Slot} 0..8）。 */
    public static final int HOTBAR_SLOTS = 9;
    /** 主背包槽位数（{@code Inventory.items} = 36，含快捷栏；{@code Slot} 0..35）。 */
    public static final int MAIN_SLOTS = 36;
    /** 主背包 3×9（{@code Slot} 9..35）。 */
    public static final int BACKPACK_SLOTS = 27;
    /** 盔甲槽位数（{@code Inventory.armor} = 4，存档里 Slot = 100..103）。 */
    public static final int ARMOR_SLOTS = 4;
    /** 盔甲槽在 NBT 里的起始 {@code Slot} 值（100 靴 / 101 护腿 / 102 胸甲 / 103 头盔）。 */
    public static final int ARMOR_BASE = 100;
    /**
     * 副手槽在 NBT 里的 {@code Slot} 值 = <b>-106</b>。
     *
     * <p>🔴 不是 {@code 150}：{@code Slot} 是 <b>byte</b>，{@code x86} 意义上的无符号读会把
     * -106 读成 150。实测来源：{@code temp\saveedit-p0\实体数据清单.md} §6.3（真实档案里副手
     * 那一条就是 {@code Slot=-106}，物品 {@code gtmadvancedhatch:net_data_stick}）。
     */
    public static final int OFFHAND_SLOT = -106;
    /**
     * 旧代码用的「无符号 150」（{@code -106 & 0xFF}）—— <b>只是兼容别名</b>。
     *
     * <p>为什么保留：面板上"清空槽位"的提示文字写的是 150（{@code ShanhaiSaveEditPanel} 不在
     * 本期可改范围），直接判 150 非法会让用户按提示输入却报错。内部一律先过
     * {@link #canonicalSlot(int)}，所以 150 与 -106 落到<b>同一格</b>。
     */
    public static final int OFFHAND_SLOT_UNSIGNED_LEGACY = 150;
    /** 玩家物品栏格子总数：快捷栏 9 + 主背包 27 + 盔甲 4 + 副手 1 = <b>41</b>。 */
    public static final int INVENTORY_GRID_SLOTS = 41;
    /** 末影箱格子数（原版口径 {@code Slot} 0..26）。🔴 本机样本未实测。 */
    public static final int ENDER_SLOTS = 27;
    /**
     * 末影箱槽号范围<b>是否</b>在真实数据上验证过 = <b>false</b>。
     *
     * <p>{@code temp\saveedit-p0\实体数据清单.md} §6.7：16/16 份真实档案的 {@code EnderItems}
     * 都是空列表（元素类型 0），一条真实条目都没有 ⇒ "0..26" 只是按原版口径，<b>没有实测</b>。
     * 所有涉及末影箱的结果/快照都会带上这个标记，不许当成"已验证"。
     */
    public static final boolean ENDER_RANGE_VERIFIED = false;
    /** 一格的堆叠下限（真实样本 155/155 都在 1..64；{@code Count=0} 不是"空格"的表示）。 */
    public static final int COUNT_MIN = 1;
    /** 一格的堆叠上限（超 64 的形态本机没有样本 ⇒ 不接受，也就没有"游戏会不会钳回"的风险）。 */
    public static final int COUNT_MAX = 64;

    /** 存活上限（原版默认 20；给了个大余量，但仍是有限值 —— 别让人填 1e9 把客户端搞崩）。 */
    public static final float HEALTH_MAX = 1024.0F;
    /** 原版经验等级上限（{@code Integer.MAX_VALUE} 不现实；21863 是原版可达的高位）。 */
    public static final int XP_LEVEL_MAX = 21863;
    /** 坐标硬上限（原版世界边界）。 */
    public static final double COORD_MAX = 30_000_000.0D;

    /** 血量（{@code Health}）。 */
    public Float health;
    /** 经验等级（{@code XpLevel}；改它时会连带重算 {@code XpTotal}）。 */
    public Integer xpLevel;
    /** 饥饿（{@code foodLevel}）。 */
    public Integer foodLevel;
    /** 饱和（{@code foodSaturationLevel}）。 */
    public Float saturation;
    /** 游戏模式（{@code playerGameType}：0 生存 / 1 创造 / 2 冒险 / 3 旁观）。 */
    public Integer gameMode;
    /** 维度（{@code Dimension}，如 {@code minecraft:overworld}）。 */
    public String dimension;
    /** 位置。 */
    public Double x, y, z;
    /** 朝向。 */
    public Float yaw, pitch;
    /** 要清空的槽位（{@code Slot} 值，见类注释的表；升序、无重复）。 */
    public int[] clearSlots = new int[0];

    // ------------------------------------------------------------------ 逐格补丁（物品栏 41 格 / 末影箱）

    /**
     * 一格的改动类型。
     *
     * <p>三种操作用<b>同一个</b>补丁对象表示（而不是三个 Map），因为写盘时它们共享同一段逻辑：
     * 按 {@code Slot} 找到那条条目，然后"摘掉 / 只改 {@code Count} / 整条换掉"。
     */
    public enum SlotOp {
        /** 摘掉该 {@code Slot} 的条目（其余条目连对象都不换）。 */
        REMOVE,
        /** 只改 {@code Count}（条目里别的键一个字节都不动）。 */
        SET_COUNT,
        /** 写回该 {@code Slot} 的完整条目 {@code {Slot, id, Count, tag?}}。 */
        REPLACE
    }

    /**
     * 一个格子的补丁（<b>纯 JDK</b>：物品的 {@code tag} 用 NBT 字节表示，不引入任何 NBT 类型）。
     *
     * <h2>为什么 tag 用 {@code byte[]}</h2>
     * ① 本类的契约是"纯 JDK"，引一个 {@code CompoundTag} 就把 NBT 依赖带进来了；
     * ② 🔴 <b>替换只能写 NBT，绝不许 {@code ItemStack.of(tag)} 再序列化回来</b>
     * （{@code ShanhaiPlayerDataEditor} 类注释第 37 行）：字节是"原样搬运"，
     * 而 1.20.1 的 {@code ItemStack#save} 会把 {@code id} 按注册表重写、并且<b>不写 {@code Slot}</b>。
     * ③ 字节由 {@code ShanhaiPlayerDataEditor#encodeTagBytes/decodeTagBytes}（{@code NbtIo.write/read}）
     * 产生与解析，两侧是同一对方法，因此可以做到<b>树级等价</b>。
     */
    public static final class SlotPatch {

        /** 规范槽号（{@link ShanhaiPlayerDataEdit#canonicalSlot(int)} 之后的值）。 */
        public final int slot;
        public final SlotOp op;
        /** {@link SlotOp#SET_COUNT} / {@link SlotOp#REPLACE} 的数量（{@link SlotOp#REMOVE} 时无意义）。 */
        public final int count;
        /** {@link SlotOp#REPLACE} 的物品注册名（如 {@code minecraft:stone}）。 */
        public final String id;
        /** {@link SlotOp#REPLACE} 的物品 {@code tag}（NBT 字节；{@code null} = 这条物品没有 tag）。 */
        public final byte[] tagNbt;

        private SlotPatch(int slot, SlotOp op, int count, String id, byte[] tagNbt) {
            this.slot = ShanhaiPlayerDataEdit.canonicalSlot(slot);
            this.op = op;
            this.count = count;
            this.id = id;
            this.tagNbt = tagNbt;
        }

        /** 删掉这一格。 */
        public static SlotPatch remove(int slot) {
            return new SlotPatch(slot, SlotOp.REMOVE, 0, null, null);
        }

        /** 只改这一格的数量。 */
        public static SlotPatch setCount(int slot, int count) {
            return new SlotPatch(slot, SlotOp.SET_COUNT, count, null, null);
        }

        /** 把这一格整条换成 {@code id}×{@code count}（{@code tagNbt} 可空）。 */
        public static SlotPatch replace(int slot, String id, int count, byte[] tagNbt) {
            return new SlotPatch(slot, SlotOp.REPLACE, count, id, tagNbt);
        }

        /** 这条补丁带不带物品 NBT。 */
        public boolean hasTag() {
            return this.tagNbt != null && this.tagNbt.length > 0;
        }

        /** 一行中文（{@code describe()} / 日志 / 摘要用）。 */
        public String describe() {
            switch (this.op) {
                case REMOVE:
                    return "删";
                case SET_COUNT:
                    return "数量→" + this.count;
                default:
                    return "换成 " + this.id + "×" + this.count + (hasTag() ? "（带 tag）" : "");
            }
        }

        @Override
        public String toString() {
            return ShanhaiPlayerDataEdit.slotName(this.slot) + "=" + describe();
        }
    }

    /**
     * 背包（{@code Inventory}）的补丁表：<b>规范槽号 → 补丁</b>。
     *
     * <p>同一格给两次 ⇒ <b>最后一次为准</b>（UI 上"先删、又改成改数量"是正常操作，不该报错）；
     * 文本协议（{@link #parse}）则把重复当输入错误，见 {@code parseSlotPatches}。
     */
    public final Map<Integer, SlotPatch> inventoryPatches = new LinkedHashMap<>();
    /** 末影箱（{@code EnderItems}）的补丁表：规范槽号 → 补丁（与背包同一套代码，只是读另一个键）。 */
    public final Map<Integer, SlotPatch> enderPatches = new LinkedHashMap<>();


    /**
     * 第二页（{@code level.dat}）的改动（可空 = 这一页没填东西）。
     *
     * <p>为什么挂在一期这个对象上：落盘窗口只有一次，{@code ShanhaiSaveEditReload#applyOnIo}
     * 那条已经验过的路径只会调 {@link ShanhaiPlayerDataEditor#apply}。把三页的改动装在同一个
     * 请求对象上，才能做到<b>一次窗口、一次确认、三页一起落盘</b>，而不必去改那条 reopen 路径。
     */
    public ShanhaiLevelDataEdit levelEdit;
    /** 第三页（区块方块/方块实体）的请求（可空 = 这一页没填东西）。 */
    public ShanhaiRegionEdit.Request regionRequest;
    /**
     * 第四页（{@code entities/*.mca}）的删除请求（可空 = 这一页没填东西）。
     *
     * <p>第四期加的三页都挂在同一个请求对象上，理由与二/三页一样：落盘窗口只有一次，
     * {@code ShanhaiSaveEditReload#applyOnIo} 只会调 {@link ShanhaiPlayerDataEditor#apply}。
     */
    public ShanhaiEntityEdit.Request entityRequest;
    /** 第五页（{@code advancements/<uuid>.json}）的请求（可空 = 这一页没填东西）。 */
    public ShanhaiAdvancementEdit.Request advancementRequest;
    /** 第六页（{@code stats/<uuid>.json}）的请求（可空 = 这一页没填东西）。 */
    public ShanhaiStatsEdit.Request statsRequest;
    /**
     * 第七页（存档体检 · 双份登记）的动作请求（可空 = 这一页没选东西）。
     *
     * <p>与四~七页同一条口径：只有按过「暂存」才参与落盘，而且<b>还是同一个停服窗口</b>
     * （{@link ShanhaiSaveEditWindow} 的第 ⑧ 段），不另开一条写盘路径。
     */
    public com.shanhai.common.saveedit.audit.ShanhaiSaveEditAuditFix.Request auditRequest;

    // ================================================================== 累积清单（暂存到"关闭面板"才清）

    /**
     * 四~七页的<b>累积清单</b>：用户在面板上暂存了 N 条，就一次落盘 N 条
     * （同一个停服窗口、逐条备份 + 原子写 + 回读，见 {@link ShanhaiSaveEditWindow}）。
     *
     * <p>为什么要有它们：暂存从"单槽"改成"累积到关闭面板"之后，一个请求对象里只放一条已经装不下了。
     * 兼容口径：<b>清单为空时回退到上面那些单条字段</b>（{@link #effectiveEntityRequests()} 等），
     * 所以老调用方（含离线判据）的行为一个字节都不变。
     */
    public final List<ShanhaiEntityEdit.Request> entityRequests = new ArrayList<>();
    /** 成就页的累积清单（键 = 成就 id；见 {@code ShanhaiSaveEditFactory.Holder}）。 */
    public final List<ShanhaiAdvancementEdit.Request> advancementRequests = new ArrayList<>();
    /** 统计页的累积清单（键 = 分类/键）。 */
    public final List<ShanhaiStatsEdit.Request> statsRequests = new ArrayList<>();
    /** 体检页的累积清单（键 = 动作|UUID）。 */
    public final List<com.shanhai.common.saveedit.audit.ShanhaiSaveEditAuditFix.Request> auditRequests
            = new ArrayList<>();
    /** 实体字段补丁的累积清单（每个目标一条）。 */
    public final List<EntityPatchTarget> entityPatchTargets = new ArrayList<>();

    /** 一条"改某个实体字段"的目标：维度 + UUID + 合并后的补丁（一次窗口里可以有好几个目标）。 */
    public record EntityPatchTarget(String dimension, String uuid, ShanhaiEntityEdit.Patch patch) {

        /** 一行读数（日志/回显用）。 */
        public String line() {
            return (this.patch == null ? "-" : this.patch.keys()) + " @ " + this.dimension + " / " + this.uuid;
        }
    }

    // ================================================================== 第五期续：第 ④ 页覆盖层「写入」

    /**
     * 第 ④ 页覆盖层「写入」要改的那 <b>10 项</b>里的若干项（可空 = 这一页没按过写入）。
     *
     * <p>为什么必须另开一个字段：{@link #entityRequest} 只能表达"删一条"（按类型 / 按 UUID），
     * 表达不了 Pos / Rotation / Motion / CustomName / 四个开关 / Health / Item 这些<b>值</b>。
     * 落盘点仍然是<b>同一个停服窗口</b>（{@code ShanhaiSaveEditWindow} 的 ⑤b 段），不另开写盘路径。
     */
    public ShanhaiEntityEdit.Patch entityPatch;
    /** {@link #entityPatch} 的目标维度（如 {@code minecraft:overworld}）—— 数据层按「维度 + UUID」定位。 */
    public String entityPatchDimension;
    /** {@link #entityPatch} 的目标 UUID。 */
    public String entityPatchUuid;

    /** 解析失败的原因（空 = 全部合法）。 */
    public final List<String> errors = new ArrayList<>();

    public ShanhaiPlayerDataEdit() {}

    /** 一期那些字段里有没有要改的（不含二/三页）。 */
    public boolean hasPlayerFields() {
        return this.health != null || this.xpLevel != null || this.foodLevel != null
                || this.saturation != null || this.gameMode != null
                || (this.dimension != null && !this.dimension.isEmpty())
                || this.x != null || this.y != null || this.z != null
                || this.yaw != null || this.pitch != null
                || (this.clearSlots != null && this.clearSlots.length > 0)
                // 🔴 第二期的逐格补丁必须算进 ① 页：不算的话，"只暂存物品栏格子"会被
                //    applyPlayerOnly 判成 empty_edit（零写入），正是 2026-11-15 刚修掉的那个坑。
                || hasSlotPatches();
    }

    /** 有没有逐格补丁（背包或末影箱任一）。 */
    public boolean hasSlotPatches() {
        return !this.inventoryPatches.isEmpty() || !this.enderPatches.isEmpty();
    }

    // ------------------------------------------------------------------ 逐格补丁：入口（程序化）

    /** 从背包里删掉一格（槽号非法 ⇒ 记进 {@link #errors} 并返回 false）。 */
    public boolean removeSlot(int slot) {
        return putPatch(false, SlotPatch.remove(slot));
    }

    /** 从末影箱里删掉一格。 */
    public boolean removeEnderSlot(int slot) {
        return putPatch(true, SlotPatch.remove(slot));
    }

    /** 只改背包某一格的数量。 */
    public boolean setSlotCount(int slot, int count) {
        return putPatch(false, SlotPatch.setCount(slot, count));
    }

    /** 只改末影箱某一格的数量。 */
    public boolean setEnderSlotCount(int slot, int count) {
        return putPatch(true, SlotPatch.setCount(slot, count));
    }

    /**
     * 把背包某一格整条换成 {@code id}×{@code count}。
     *
     * @param tagNbt 物品 {@code tag} 的 NBT 字节（{@code null} = 没有 tag）。
     *               想要"连 tag 一起原样搬"，用它自己的读取结果即可：
     *               {@code readInventory(file).at(src).tagNbt()}
     */
    public boolean replaceSlot(int slot, String id, int count, byte[] tagNbt) {
        return putPatch(false, SlotPatch.replace(slot, id, count, tagNbt));
    }

    /** 把末影箱某一格整条换掉。 */
    public boolean replaceEnderSlot(int slot, String id, int count, byte[] tagNbt) {
        return putPatch(true, SlotPatch.replace(slot, id, count, tagNbt));
    }

    /**
     * 收一条补丁（校验 + 归一槽号 + 入表）。
     *
     * <p>校验分两层：这一层是"不需要读文件就知道"的（槽号合法、数量区间、注册名形状）；
     * "这一格是不是空的"必须读文件才知道，在 {@code ShanhaiPlayerDataEditor#validateSlotPatches}。
     */
    private boolean putPatch(boolean ender, SlotPatch patch) {
        final int slot = canonicalSlot(patch.slot);
        // 名字本身已经带"背包/盔甲/副手"或"末影箱"，不要再加前缀（否则会出现"背包背包4"）
        final String name = ender ? enderSlotName(slot) : slotName(slot);
        if (ender ? !isEnderSlot(slot) : !isInventorySlot(slot)) {
            this.errors.add((ender ? "末影箱" : "背包") + "槽位 " + patch.slot + " 不存在（" + (ender
                    ? "末影箱只认 0–26（该范围未在本机样本上实测）"
                    : "背包只认 0–35 快捷栏+主背包 / 100–103 靴腿胸头 / -106 副手")
                    + "）");
            return false;
        }
        if (patch.op == SlotOp.SET_COUNT || patch.op == SlotOp.REPLACE) {
            if (patch.count < COUNT_MIN || patch.count > COUNT_MAX) {
                this.errors.add(name + " 的数量要落在 [" + COUNT_MIN + ", " + COUNT_MAX
                        + "] 里，现在是 " + patch.count + "（空格不是 Count=0，是「根本没有条目」）");
                return false;
            }
        }
        if (patch.op == SlotOp.REPLACE) {
            if (!isResourceLocation(patch.id)) {
                this.errors.add(name + " 要换成物品「" + patch.id
                        + "」，那不是合法的注册名（要 namespace:path，例如 minecraft:stone）");
                return false;
            }
            if (patch.tagNbt != null && patch.tagNbt.length == 0) {
                this.errors.add(name + " 给了空的 tag 字节（要么传 null，要么传完整 NBT）");
                return false;
            }
        }
        (ender ? this.enderPatches : this.inventoryPatches).put(slot, patch);
        return true;
    }

    /** 二页有没有要改的。 */
    public boolean hasLevel() {
        return this.levelEdit != null && !this.levelEdit.isEmpty();
    }

    /** 三页有没有要改的。 */
    public boolean hasRegion() {
        return this.regionRequest != null && !this.regionRequest.isEmpty();
    }

    /** 四页（实体）有没有要改的。 */
    public boolean hasEntities() {
        return (this.entityRequest != null && !this.entityRequest.isEmpty()) || !this.entityRequests.isEmpty();
    }

    /** 五页（成就）有没有要改的。 */
    public boolean hasAdvancement() {
        return (this.advancementRequest != null && !this.advancementRequest.isEmpty())
                || !this.advancementRequests.isEmpty();
    }

    /** 六页（统计）有没有要改的。 */
    public boolean hasStats() {
        return (this.statsRequest != null && !this.statsRequest.isEmpty()) || !this.statsRequests.isEmpty();
    }

    /** 七页（存档体检 · 双份登记）有没有要改的。 */
    public boolean hasAudit() {
        return (this.auditRequest != null && !this.auditRequest.isEmpty()) || !this.auditRequests.isEmpty();
    }

    /** 第 ④ 页覆盖层有没有"要改的 10 项"（第五期续；累积清单也算）。 */
    public boolean hasEntityPatch() {
        if (!this.entityPatchTargets.isEmpty()) {
            return true;
        }
        return this.entityPatch != null && !this.entityPatch.isEmpty();
    }

    // ------------------------------------------------------------------ 累积清单的"这次真要落哪些"

    /** 这次真正要落的实体删除请求（<b>清单优先</b>；清单空则回退到单条 {@link #entityRequest}）。 */
    public List<ShanhaiEntityEdit.Request> effectiveEntityRequests() {
        if (!this.entityRequests.isEmpty()) {
            return new ArrayList<>(this.entityRequests);
        }
        return this.entityRequest == null || this.entityRequest.isEmpty()
                ? List.of() : List.of(this.entityRequest);
    }

    /** 这次真正要落的成就请求（清单优先；空则回退到单条）。 */
    public List<ShanhaiAdvancementEdit.Request> effectiveAdvancementRequests() {
        if (!this.advancementRequests.isEmpty()) {
            return new ArrayList<>(this.advancementRequests);
        }
        return this.advancementRequest == null || this.advancementRequest.isEmpty()
                ? List.of() : List.of(this.advancementRequest);
    }

    /** 这次真正要落的统计请求（清单优先；空则回退到单条）。 */
    public List<ShanhaiStatsEdit.Request> effectiveStatsRequests() {
        if (!this.statsRequests.isEmpty()) {
            return new ArrayList<>(this.statsRequests);
        }
        return this.statsRequest == null || this.statsRequest.isEmpty()
                ? List.of() : List.of(this.statsRequest);
    }

    /** 这次真正要落的体检请求（清单优先；空则回退到单条）。 */
    public List<com.shanhai.common.saveedit.audit.ShanhaiSaveEditAuditFix.Request> effectiveAuditRequests() {
        if (!this.auditRequests.isEmpty()) {
            return new ArrayList<>(this.auditRequests);
        }
        return this.auditRequest == null || this.auditRequest.isEmpty()
                ? List.of() : List.of(this.auditRequest);
    }

    /** 这次真正要落的实体字段补丁（清单优先；空则回退到"单条三件套"）。 */
    public List<EntityPatchTarget> effectiveEntityPatchTargets() {
        if (!this.entityPatchTargets.isEmpty()) {
            return new ArrayList<>(this.entityPatchTargets);
        }
        if (this.entityPatch == null || this.entityPatch.isEmpty()) {
            return List.of();
        }
        return List.of(new EntityPatchTarget(this.entityPatchDimension, this.entityPatchUuid, this.entityPatch));
    }

    /**
     * 累积清单里第一条不合格的目标的原因（{@code null} = 全部合格）。
     *
     * <p>与 {@link #entityPatchProblem()} 同一条理由（缺维度/缺 UUID 必须挡在停服<b>之前</b>），
     * 只是这里逐条查一遍 —— 多个目标时任意一条缺一半都算不合格。
     */
    public String entityPatchTargetsProblem() {
        for (EntityPatchTarget target : effectiveEntityPatchTargets()) {
            if (target.dimension() == null || target.dimension().isEmpty()) {
                return "覆盖层写入没有维度（entityPatchDimension）";
            }
            if (target.uuid() == null || target.uuid().isEmpty()) {
                return "覆盖层写入没有 UUID（entityPatchUuid）";
            }
            if (ShanhaiSaveEditDimension.normalize(target.dimension()) == null) {
                return "覆盖层写入的维度写法不认识：" + target.dimension();
            }
            if (ShanhaiEntityEdit.parseUuid(target.uuid()) == null) {
                return "覆盖层写入的 UUID 写法不认识：" + target.uuid();
            }
            if (target.patch() == null || target.patch().isEmpty()) {
                return "覆盖层写入的补丁是空的（" + target.dimension() + " / " + target.uuid() + "）";
            }
        }
        return null;
    }

    /**
     * 覆盖层写入的目标（维度 + UUID）齐不齐：{@code null} = 齐；否则是中文原因。
     *
     * <p>🔴 这一问必须在<b>停服之前</b>发生（{@code ShanhaiSaveEditWindow} 的预检段）：
     * 数据层是按「维度 + UUID」定位的，缺任何一半都要等到窗口里才发现 ——
     * 那就是"停完服、一个字节都没写"，最亏的一种失败。
     */
    public String entityPatchProblem() {
        if (!hasEntityPatch()) {
            return null;
        }
        if (this.entityPatchDimension == null || this.entityPatchDimension.isEmpty()) {
            return "覆盖层写入没有维度（entityPatchDimension）";
        }
        if (this.entityPatchUuid == null || this.entityPatchUuid.isEmpty()) {
            return "覆盖层写入没有 UUID（entityPatchUuid）";
        }
        if (ShanhaiSaveEditDimension.normalize(this.entityPatchDimension) == null) {
            return "覆盖层写入的维度写法不认识：" + this.entityPatchDimension;
        }
        if (ShanhaiEntityEdit.parseUuid(this.entityPatchUuid) == null) {
            return "覆盖层写入的 UUID 写法不认识：" + this.entityPatchUuid;
        }
        return null;
    }

    /**
     * 一行读数（日志/回显）：{@code 实体字段 [Pos, Health] @ minecraft:overworld / <uuid>}。
     *
     * <p>🔴 必须走 {@link #effectiveEntityPatchTargets()}（<b>清单优先</b>），不许直接读
     * {@link #entityPatch}：第 ④ 页改成累积清单后，单条字段恒为 {@code null}，而
     * {@link #hasEntityPatch()} 判的是"清单<b>或</b>单条"就是 {@code true}
     * ⇒ 旧写法必 NPE（2026-11-15 用户实机撞到：
     * {@code Cannot invoke "…ShanhaiEntityEdit$Patch.keys()" because "this.entityPatch" is null}）。
     * 这是 {@link #describe()} 那一族的同一个坑，修一处必须扫全同类读点。
     */
    public String entityPatchLine() {
        final List<EntityPatchTarget> targets = effectiveEntityPatchTargets();
        if (targets.isEmpty()) {
            return "-";
        }
        final StringBuilder out = new StringBuilder("实体字段 ");
        for (int i = 0; i < targets.size(); i++) {
            if (i > 0) {
                out.append(" ；");
            }
            final EntityPatchTarget target = targets.get(i);
            out.append(target.patch().keys()).append(" @ ").append(target.dimension())
                    .append(" / ").append(target.uuid());
        }
        if (!this.entityPatchTargets.isEmpty()) {
            out.append("（").append(targets.size()).append(" 条累积）");
        }
        return out.toString();
    }

    /**
     * ②~⑦ 页里有没有要改的（<b>不含</b> ① 玩家页）。
     *
     * <p>🔴 这六页<b>只有</b> {@code ShanhaiSaveEditWindow} 会写（窗口里第 ③~⑧ 段，
     * 各自"备份 → 原子写 → 回读"）。所以 {@code ShanhaiPlayerDataEditor#apply} 的分流条件
     * 必须问这一个方法，而<b>不是</b>只问 ②③（{@link #hasLevel()} / {@link #hasRegion()}）：
     * 旧的 {@code hasLevel() || hasRegion()} 会把"只暂存 ④⑤⑥⑦"送进
     * {@code applyPlayerOnly}，那里 {@code !hasPlayerFields()} 直接返回 {@code empty_edit}
     * ⇒ 一个字节都不写；而"只暂存 ④ + ① 也填了字段"更糟：只写玩家页、④ 被静默丢掉。
     *
     * <p>（{@link #isEmpty()} 是"七页全空"，{@link #hasPlayerFields()} 是"只有 ① 有东西"，
     * 三个方法各管一段，别互相替代。）
     */
    public boolean hasWindowPages() {
        return hasLevel() || hasRegion() || hasEntities()
                || hasAdvancement() || hasStats() || hasAudit()
                // 第五期续：覆盖层「写入」（改 10 项）也由同一个停服窗口落盘，
                // 不把它算进来的话，"只暂存了实体字段"会落进 applyPlayerOnly 并被判成 empty_edit。
                || hasEntityPatch();
    }

    /** 有没有任何一项要改（七页合起来）。 */
    public boolean isEmpty() {
        return !hasPlayerFields() && !hasLevel() && !hasRegion()
                && !hasEntities() && !hasAdvancement() && !hasStats() && !hasAudit()
                && !hasEntityPatch();
    }

    /**
     * 把二/三页的解析错误<b>并进</b> {@link #errors}。
     *
     * <p>{@code ShanhaiSaveEditBridge#confirm} 只看 {@code edit.errors}（那是它启动前唯一的闸门，
     * 过了这道门就要真停服了）。所以面板在交出去之前必须把另两页的错误也倒进来 ——
     * 否则会出现"停完服才发现第三页填错了"。
     */
    public void absorbExtraErrors() {
        if (this.levelEdit != null && this.levelEdit.errors != null) {
            for (String e : this.levelEdit.errors) {
                if (!this.errors.contains(e)) {
                    this.errors.add(e);
                }
            }
        }
        // 四~七页的请求各自会 validate()（面板暂存时已过一遍，这里再兜一层：
        // 一旦放了错的东西进 confirm，就是"停完服才发现填错"）
        absorbOne(this.entityRequest, this.errors);
        absorbOne(this.advancementRequest, this.errors);
        absorbOne(this.statsRequest, this.errors);
        absorbOne(this.auditRequest, this.errors);
        // 累积清单：每一条都要在停服之前过一遍 validate —— 一条填错就不许进窗口
        for (ShanhaiEntityEdit.Request r : this.entityRequests) {
            absorbOne(r, this.errors);
        }
        for (ShanhaiAdvancementEdit.Request r : this.advancementRequests) {
            absorbOne(r, this.errors);
        }
        for (ShanhaiStatsEdit.Request r : this.statsRequests) {
            absorbOne(r, this.errors);
        }
        for (com.shanhai.common.saveedit.audit.ShanhaiSaveEditAuditFix.Request r : this.auditRequests) {
            absorbOne(r, this.errors);
        }
        // ⑤b 覆盖层「写入」：目标（维度 + UUID）齐不齐也要在停服之前问（单个/累积都问）
        final String patchWhy = entityPatchTargetsProblem();
        if (patchWhy != null && !this.errors.contains(patchWhy)) {
            this.errors.add(patchWhy);
        }
    }

    /** 把一个请求的校验错误并进 {@code errors}（{@code null} 请求 = 这一页不参与）。 */
    private static void absorbOne(Object request, List<String> errors) {
        if (request == null) {
            return;
        }
        try {
            if (request instanceof ShanhaiEntityEdit.Request r) {
                r.validate();
            } else if (request instanceof ShanhaiAdvancementEdit.Request r) {
                r.validate();
            } else if (request instanceof ShanhaiStatsEdit.Request r) {
                r.validate();
            } else if (request instanceof com.shanhai.common.saveedit.audit.ShanhaiSaveEditAuditFix.Request r) {
                r.validate();
            }
        } catch (IllegalArgumentException iae) {
            final String msg = iae.getMessage() == null ? "参数不合法" : iae.getMessage();
            if (!errors.contains(msg)) {
                errors.add(msg);
            }
        }
    }

    /** 动了位置吗（位置必须 x/y/z 三个一起给，见 {@link #parse(Map)}）。 */
    public boolean movesPlayer() {
        return this.x != null && this.y != null && this.z != null;
    }

    // ================================================================== 解析

    /**
     * 从面板文本解析。
     *
     * <p>键（大小写不敏感，取第一条命中的）：{@code health / xp / food / saturation /
     * gamemode / dimension / x / y / z / yaw / pitch / clear}。
     * 空串 = 不改。解析失败写进 {@link #errors}。
     */
    public static ShanhaiPlayerDataEdit parse(Map<String, String> raw) {
        final ShanhaiPlayerDataEdit edit = new ShanhaiPlayerDataEdit();
        if (raw == null) {
            return edit;
        }
        final Map<String, String> in = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : raw.entrySet()) {
            if (e.getKey() != null && e.getValue() != null) {
                in.put(e.getKey().toLowerCase(java.util.Locale.ROOT), e.getValue());
            }
        }
        edit.health = boxedFloat(edit, in, "health", 0.0F, HEALTH_MAX, "血量");
        edit.xpLevel = boxedInt(edit, in, "xp", 0, XP_LEVEL_MAX, "经验等级");
        edit.foodLevel = boxedInt(edit, in, "food", 0, 20, "饥饿");
        edit.saturation = boxedFloat(edit, in, "saturation", 0.0F, 20.0F, "饱和");
        edit.gameMode = boxedInt(edit, in, "gamemode", 0, 3, "游戏模式");
        edit.x = boxedDouble(edit, in, "x", -COORD_MAX, COORD_MAX, "X");
        edit.y = boxedDouble(edit, in, "y", -2048.0D, 2048.0D, "Y");
        edit.z = boxedDouble(edit, in, "z", -COORD_MAX, COORD_MAX, "Z");
        edit.yaw = boxedFloat(edit, in, "yaw", -3600.0F, 3600.0F, "yaw");
        edit.pitch = boxedFloat(edit, in, "pitch", -3600.0F, 3600.0F, "pitch");

        // 维度：只收 namespace:path（避免把随便一串字写进 Dimension 让玩家登不进来）
        final String dim = trimmed(in.get("dimension"));
        if (!dim.isEmpty()) {
            if (isResourceLocation(dim)) {
                edit.dimension = dim;
            } else {
                edit.errors.add("维度要写成 namespace:path（例如 minecraft:overworld），现在是「" + dim + "」");
            }
        }

        // 清空槽位：逗号/顿号/空格都认；逐个校验范围
        final String clear = trimmed(in.get("clear"));
        if (!clear.isEmpty()) {
            final List<Integer> slots = new ArrayList<>();
            for (String token : clear.split("[,，、;；\\s]+")) {
                final String s = token.trim();
                if (s.isEmpty()) {
                    continue;
                }
                try {
                    final int slot = Integer.parseInt(s);
                    if (!isPlayerSlot(slot)) {
                        edit.errors.add("清空槽位 " + slot + " 不存在（只认 0–35 快捷栏+主背包 / 100–103 盔甲 / "
                                + OFFHAND_SLOT + " 副手，旧写法 " + OFFHAND_SLOT_UNSIGNED_LEGACY + " 也认）");
                        continue;
                    }
                    if (!slots.contains(slot)) {
                        slots.add(slot);
                    }
                } catch (NumberFormatException nfe) {
                    edit.errors.add("清空槽位「" + s + "」不是整数");
                }
            }
            slots.sort(Integer::compareTo);
            final int[] arr = new int[slots.size()];
            for (int i = 0; i < arr.length; i++) {
                arr[i] = slots.get(i);
            }
            edit.clearSlots = arr;
        }

        // 背包 / 末影箱的逐格补丁（"删一格 / 只改数量 / 整条替换"）
        parseSlotPatches(edit, in, false);
        parseSlotPatches(edit, in, true);

        // 位置必须三个一起给：只给一个的话另两个会用旧值，玩家很难预期
        final int posGiven = (edit.x == null ? 0 : 1) + (edit.y == null ? 0 : 1) + (edit.z == null ? 0 : 1);
        if (posGiven != 0 && posGiven != 3) {
            edit.errors.add("位置要 X / Y / Z 三个一起填（现在只填了 " + posGiven + " 个）");
            // 🔴 半截的那一组<b>整组丢掉</b>：报错就意味着"这一项不会生效"，
            //    绝不允许"报错了但其实改了一半"。
            edit.x = null;
            edit.y = null;
            edit.z = null;
        }
        final int rotGiven = (edit.yaw == null ? 0 : 1) + (edit.pitch == null ? 0 : 1);
        if (rotGiven == 1) {
            edit.errors.add("朝向要 yaw / pitch 两个一起填（现在只填了 1 个）");
            edit.yaw = null;
            edit.pitch = null;
        }
        return edit;
    }

    /**
     * 把「无符号别名 150」归一到规范槽号（{@link #OFFHAND_SLOT} = -106）；其余原样返回。
     *
     * <p>🔴 归一之后，{@code 150} 与 {@code -106} 落到<b>同一格</b>；但读文件那一侧仍然必须用
     * <b>有符号</b> byte（{@code (int) entry.getByte("Slot")}），不许 {@code & 0xFF} ——
     * 否则"同一个字节有两种槽号约定"这件事会散到各处。
     */
    public static int canonicalSlot(int slot) {
        return slot == OFFHAND_SLOT_UNSIGNED_LEGACY ? OFFHAND_SLOT : slot;
    }

    /** 41 格里的槽号（快捷栏 0–8 / 主背包 9–35 / 盔甲 100–103 / 副手 -106；150 是别名）。 */
    public static boolean isInventorySlot(int slot) {
        final int s = canonicalSlot(slot);
        return (s >= 0 && s < MAIN_SLOTS)
                || (s >= ARMOR_BASE && s < ARMOR_BASE + ARMOR_SLOTS)
                || s == OFFHAND_SLOT;
    }

    /** 末影箱的槽号（原版口径 0–26）。🔴 该范围本机样本未实测，见 {@link #ENDER_RANGE_VERIFIED}。 */
    public static boolean isEnderSlot(int slot) {
        return slot >= 0 && slot < ENDER_SLOTS;
    }

    /**
     * 末影箱槽位的中文名（{@code 末影箱1} … {@code 末影箱27}）。
     *
     * <p>为什么不复用 {@link #slotName(int)}：它把 0–35 叫"背包N" —— 末影箱第 14 格会显示成
     * "背包14"，日志里两条链路就分不开了（这是实测踩到的：末影箱 slot 13 的 note 写成"背包14"）。
     */
    public static String enderSlotName(int slot) {
        return isEnderSlot(slot) ? ("末影箱" + (slot + 1)) : ("未知末影箱槽" + slot);
    }

    /** 合法的玩家槽位（旧名，语义 = {@link #isInventorySlot(int)}；一期"清空槽位"仍在用它）。 */
    public static boolean isPlayerSlot(int slot) {
        return isInventorySlot(slot);
    }

    /** 槽位的中文名（日志/摘要用；{@code 150} 与 {@code -106} 都叫"副手"）。 */
    public static String slotName(int slot) {
        final int s = canonicalSlot(slot);
        if (s >= 0 && s < MAIN_SLOTS) {
            return "背包" + (s + 1);
        }
        if (s >= ARMOR_BASE && s < ARMOR_BASE + ARMOR_SLOTS) {
            return "盔甲" + (s - ARMOR_BASE + 1);
        }
        if (s == OFFHAND_SLOT) {
            return "副手";
        }
        return "未知槽" + slot;
    }

    /**
     * 41 格的中文名（比 {@link #slotName(int)} 细一档，给格子 UI 的悬浮提示用）。
     *
     * <p>为什么不改 {@code slotName}：它已经被日志/摘要/既有判据当字符串断言过了
     * （例如 {@code "背包4"} / {@code "盔甲1"} / {@code "副手"}），换文案会把别人的断言弄红。
     */
    public static String slotLabel(int slot) {
        final int s = canonicalSlot(slot);
        if (s >= 0 && s < HOTBAR_SLOTS) {
            return "快捷栏" + (s + 1);
        }
        if (s >= HOTBAR_SLOTS && s < MAIN_SLOTS) {
            return "背包" + (s - HOTBAR_SLOTS + 1);
        }
        if (s >= ARMOR_BASE && s < ARMOR_BASE + ARMOR_SLOTS) {
            switch (s - ARMOR_BASE) {
                case 0:
                    return "靴子";
                case 1:
                    return "护腿";
                case 2:
                    return "胸甲";
                default:
                    return "头盔";
            }
        }
        if (s == OFFHAND_SLOT) {
            return "副手";
        }
        return "未知槽" + slot;
    }

    // ------------------------------------------------------------------ 格子顺序（UI 用同一份）

    /** 41 格的槽号顺序：0–35（快捷栏+主背包）→ 100–103（靴/腿/胸/头）→ -106（副手）。 */
    private static final int[] INVENTORY_GRID = buildInventoryGrid();
    /** 末影箱 27 格的槽号顺序（0–26）。 */
    private static final int[] ENDER_GRID = buildEnderGrid();

    private static int[] buildInventoryGrid() {
        final int[] out = new int[INVENTORY_GRID_SLOTS];
        for (int i = 0; i < MAIN_SLOTS; i++) {
            out[i] = i;
        }
        for (int i = 0; i < ARMOR_SLOTS; i++) {
            out[MAIN_SLOTS + i] = ARMOR_BASE + i;
        }
        out[INVENTORY_GRID_SLOTS - 1] = OFFHAND_SLOT;
        return out;
    }

    private static int[] buildEnderGrid() {
        final int[] out = new int[ENDER_SLOTS];
        for (int i = 0; i < ENDER_SLOTS; i++) {
            out[i] = i;
        }
        return out;
    }

    /** 41 格的槽号表（拷一份，防外面改静态数组）。 */
    public static int[] inventoryGrid() {
        return INVENTORY_GRID.clone();
    }

    /** 末影箱 27 格的槽号表（拷一份）。 */
    public static int[] enderGrid() {
        return ENDER_GRID.clone();
    }

    /** 第 {@code index} 个格子是哪个槽号（越界 ⇒ -1）。 */
    public static int inventorySlotAt(int index) {
        return index >= 0 && index < INVENTORY_GRID.length ? INVENTORY_GRID[index] : -1;
    }

    /** 这个槽号是第几个格子（不在 41 格里 ⇒ -1；{@code 150} 按别名归一后能查到副手那格）。 */
    public static int inventoryGridIndex(int slot) {
        final int s = canonicalSlot(slot);
        for (int i = 0; i < INVENTORY_GRID.length; i++) {
            if (INVENTORY_GRID[i] == s) {
                return i;
            }
        }
        return -1;
    }

    /** 末影箱第 {@code index} 个格子是哪个槽号（越界 ⇒ -1）。 */
    public static int enderSlotAt(int index) {
        return index >= 0 && index < ENDER_GRID.length ? ENDER_GRID[index] : -1;
    }

    /** 末影箱这个槽号是第几个格子（不在 0–26 ⇒ -1）。 */
    public static int enderGridIndex(int slot) {
        return isEnderSlot(slot) ? slot : -1;
    }

    /** {@code namespace:path}（只做形状校验，不查注册表 —— 磁盘侧不依赖运行期注册表）。 */
    public static boolean isResourceLocation(String s) {
        if (s == null || s.isEmpty()) {
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

    // ================================================================== 读数

    /** 一行"要改什么"（暂存/确认时回显与日志都用它）。 */
    public String describe() {
        final StringBuilder sb = new StringBuilder();
        if (this.health != null) sb.append("血=").append(fmt(this.health)).append(' ');
        if (this.xpLevel != null) sb.append("等级=").append(this.xpLevel).append(' ');
        if (this.foodLevel != null) sb.append("饥饿=").append(this.foodLevel).append(' ');
        if (this.saturation != null) sb.append("饱和=").append(fmt(this.saturation)).append(' ');
        if (this.gameMode != null) sb.append("模式=").append(gameModeName(this.gameMode)).append(' ');
        if (this.dimension != null && !this.dimension.isEmpty()) sb.append("维度=").append(this.dimension).append(' ');
        if (movesPlayer()) {
            sb.append("位置=(").append(fmt(this.x)).append(',').append(fmt(this.y)).append(',')
                    .append(fmt(this.z)).append(") ");
        }
        if (this.yaw != null && this.pitch != null) {
            sb.append("朝向=").append(fmt(this.yaw)).append('/').append(fmt(this.pitch)).append(' ');
        }
        if (this.clearSlots != null && this.clearSlots.length > 0) {
            sb.append("清空=[");
            for (int i = 0; i < this.clearSlots.length; i++) {
                sb.append(i == 0 ? "" : ",").append(slotName(this.clearSlots[i]));
            }
            sb.append("] ");
        }
        // 逐格补丁也要出现在 describe() 里：暂存/确认/日志都靠这一行回显，
        // 一个"改了什么都看不见"的字段等于让玩家盲改（任务书点名要求 describe/hasPlayerFields 都覆盖）。
        if (!this.inventoryPatches.isEmpty()) {
            sb.append("背包改=").append(patchLine(this.inventoryPatches, false)).append(' ');
        }
        if (!this.enderPatches.isEmpty()) {
            sb.append("末影箱改=").append(patchLine(this.enderPatches, true))
                    .append(ENDER_RANGE_VERIFIED ? "" : "（槽号范围 0–26 未在本机样本实测）").append(' ');
        }
        if (hasLevel()) {
            sb.append("｜世界页：").append(this.levelEdit.describe()).append(' ');
        }
        if (hasRegion()) {
            sb.append("｜方块页：").append(this.regionRequest.line()).append(' ');
        }
        // 🔴 这三段必须走 effective* 清单（不能只打单条字段）：hasEntities()/hasAdvancement()/
        //    hasStats() 的判据是"单条字段**或**累积清单"，而第 ④⑤⑥ 页的暂存本来就是累积容器
        //    ⇒ 走累积清单时 `this.entityRequest` 等单条字段恒为 null ⇒ 旧写法必 NPE。
        //    现场（GTL山海9.10test / 2026-11-15 14:13:03.744）：
        //      panel_button_failed err=java.lang.NullPointerException: Cannot invoke
        //      "com.shanhai.common.saveedit.ShanhaiEntityEdit$Request.line()" because
        //      "this.entityRequest" is null
        //    它抛在 Bridge.begin() 成功之后 0.4 秒 ⇒ 那一次的 flow 一直 active ⇒ 之后每一次
        //    「确认修改并重载」都 confirm_rejected reason=busy（用户看到的"另一次还没结束"）。
        if (hasEntities()) {
            sb.append("｜实体页：");
            appendRequestLines(sb, effectiveEntityRequests(), ShanhaiEntityEdit.Request::line);
            sb.append(' ');
        }
        if (hasEntityPatch()) {
            sb.append("｜实体字段：").append(entityPatchLine()).append(' ');
        }
        if (hasAdvancement()) {
            sb.append("｜成就页：");
            appendRequestLines(sb, effectiveAdvancementRequests(), ShanhaiAdvancementEdit.Request::line);
            sb.append(' ');
        }
        if (hasStats()) {
            sb.append("｜统计页：");
            appendRequestLines(sb, effectiveStatsRequests(), ShanhaiStatsEdit.Request::line);
            sb.append(' ');
        }
        final String s = sb.toString().trim();
        return s.isEmpty() ? "（没有任何改动）" : s;
    }

    /**
     * 把一串请求的 {@code line()} 用 {@code ", "} 连起来接在 {@code sb} 后面。
     *
     * <p>为什么不用"单条字段"那句：累积清单一次可能带好几条，而单条字段在累积语义下可以是
     * {@code null}（见 {@link #describe()} 里记的那次 NPE 现场）。
     */
    private static <T> void appendRequestLines(StringBuilder sb, List<T> list,
                                               java.util.function.Function<T, String> lineOf) {
        if (list == null || list.isEmpty()) {
            sb.append("—");
            return;
        }
        for (int i = 0; i < list.size(); i++) {
            final T item = list.get(i);
            sb.append(i == 0 ? "" : ", ").append(item == null ? "—" : lineOf.apply(item));
        }
    }

    /** 补丁表的一行（{@code [背包1=删, 盔甲1=数量→3]}）。 */
    private static String patchLine(Map<Integer, SlotPatch> patches, boolean ender) {
        final StringBuilder sb = new StringBuilder("[");
        int i = 0;
        for (Map.Entry<Integer, SlotPatch> e : patches.entrySet()) {
            sb.append(i++ == 0 ? "" : ", ")
                    .append(ender ? enderSlotName(e.getKey()) : slotName(e.getKey()))
                    .append('=').append(e.getValue().describe());
        }
        return sb.append(']').toString();
    }

    /** 游戏模式中文名。 */
    public static String gameModeName(int id) {
        switch (id) {
            case 0: return "生存";
            case 1: return "创造";
            case 2: return "冒险";
            case 3: return "旁观";
            default: return "未知(" + id + ")";
        }
    }

    static String fmt(Number n) {
        if (n == null) {
            return "-";
        }
        final double d = n.doubleValue();
        if (d == Math.rint(d) && Math.abs(d) < 1.0E9D) {
            return String.valueOf((long) d);
        }
        return String.valueOf(Math.round(d * 100.0D) / 100.0D);
    }

    // ================================================================== 解析小工具

    private static String trimmed(String s) {
        return s == null ? "" : s.trim();
    }

    private static Float boxedFloat(ShanhaiPlayerDataEdit edit, Map<String, String> in, String key,
                                    float min, float max, String label) {
        final String raw = trimmed(in.get(key));
        if (raw.isEmpty()) {
            return null;
        }
        try {
            final float v = Float.parseFloat(raw);
            if (Float.isNaN(v) || Float.isInfinite(v) || v < min || v > max) {
                edit.errors.add(label + " 要落在 [" + fmt(min) + ", " + fmt(max) + "] 里，现在是「" + raw + "」");
                return null;
            }
            return v;
        } catch (NumberFormatException nfe) {
            edit.errors.add(label + " 不是数字：「" + raw + "」");
            return null;
        }
    }

    private static Integer boxedInt(ShanhaiPlayerDataEdit edit, Map<String, String> in, String key,
                                    int min, int max, String label) {
        final String raw = trimmed(in.get(key));
        if (raw.isEmpty()) {
            return null;
        }
        try {
            final int v = Integer.parseInt(raw);
            if (v < min || v > max) {
                edit.errors.add(label + " 要落在 [" + min + ", " + max + "] 里，现在是「" + raw + "」");
                return null;
            }
            return v;
        } catch (NumberFormatException nfe) {
            edit.errors.add(label + " 不是整数：「" + raw + "」");
            return null;
        }
    }

    private static Double boxedDouble(ShanhaiPlayerDataEdit edit, Map<String, String> in, String key,
                                      double min, double max, String label) {
        final String raw = trimmed(in.get(key));
        if (raw.isEmpty()) {
            return null;
        }
        try {
            final double v = Double.parseDouble(raw);
            if (Double.isNaN(v) || Double.isInfinite(v) || v < min || v > max) {
                edit.errors.add(label + " 要落在 [" + fmt(min) + ", " + fmt(max) + "] 里，现在是「" + raw + "」");
                return null;
            }
            return v;
        } catch (NumberFormatException nfe) {
            edit.errors.add(label + " 不是数字：「" + raw + "」");
            return null;
        }
    }

    // ================================================================== 逐格补丁的文本协议

    /** 背包补丁的键（大小写不敏感，取第一个有值的）。 */
    static final String[] INV_KEYS = {"inv", "inventory", "backpack"};
    /** 末影箱补丁的键。 */
    static final String[] ENDER_KEYS = {"ender", "enderitems", "enderchest"};

    /**
     * 文本协议：{@code <槽号>:<操作>}，多条用 {@code ;} / {@code |} / 换行 分隔。
     *
     * <pre>
     *   3:del                      删掉背包第 4 格
     *   3:count=32                 只把数量改成 32
     *   3:=minecraft:stone         换成 1 个石头（没有 tag）
     *   3:=minecraft:stone*16      换成 16 个石头
     *   -106:del                   删掉副手（🔴 负数：150 也认，见 canonicalSlot）
     *   ender = 0:del; 26:count=1  末影箱第 1 / 第 27 格
     * </pre>
     *
     * <p>🔴 文本协议<b>不支持带 tag 的替换</b>：一格的 tag 是深嵌套 compound（真实样本里最深的
     * 有 AE 终端 {@code craftingGrid[8]} 那种），写成一行文本既容易被分隔符撕碎、也没法校验。
     * 带 tag 的替换走程序化入口 {@link #replaceSlot(int, String, int, byte[])}，tag 用
     * {@code readInventory(...).at(slot).tagNbt()} 原样搬。文本里出现 {@code {…}} 会当场报错，
     * <b>不会</b>静默丢掉 tag。
     */
    private static void parseSlotPatches(ShanhaiPlayerDataEdit edit, Map<String, String> in, boolean ender) {
        final String raw = trimmed(firstNonEmpty(in, ender ? ENDER_KEYS : INV_KEYS));
        if (raw.isEmpty()) {
            return;
        }
        final String where = ender ? "末影箱" : "背包";
        final Set<Integer> seen = new LinkedHashSet<>();
        for (String token : splitTopLevel(raw)) {
            final String t = token.trim();
            if (t.isEmpty()) {
                continue;
            }
            if (t.indexOf('{') >= 0 || t.indexOf('}') >= 0) {
                edit.errors.add(where + "补丁「" + t + "」里带 {…}：文本协议不支持带 tag 的替换"
                        + "（带 tag 请走 replaceSlot(slot, id, count, tagNbt) 传 NBT 字节）");
                continue;
            }
            final int colon = t.indexOf(':');
            if (colon <= 0 || colon == t.length() - 1) {
                edit.errors.add(where + "补丁「" + t + "」要写成 槽号:操作"
                        + "（例如 3:del / 3:count=32 / 3:=minecraft:stone*16）");
                continue;
            }
            final String slotText = t.substring(0, colon).trim();
            final String opText = t.substring(colon + 1).trim();
            final int slot;
            try {
                slot = Integer.parseInt(slotText);
            } catch (NumberFormatException nfe) {
                edit.errors.add(where + "补丁的槽号「" + slotText + "」不是整数（副手写 " + OFFHAND_SLOT + "）");
                continue;
            }
            if (!seen.add(canonicalSlot(slot))) {
                edit.errors.add(where + "槽位 " + slot + " 给了两次操作（同一格只能有一个操作）");
                continue;
            }
            final String lower = opText.toLowerCase(java.util.Locale.ROOT);
            if (lower.equals("del") || lower.equals("rm") || lower.equals("remove") || lower.equals("-")) {
                if (ender) {
                    edit.removeEnderSlot(slot);
                } else {
                    edit.removeSlot(slot);
                }
                continue;
            }
            if (lower.startsWith("count=") || lower.startsWith("*")) {
                final String countText = lower.startsWith("count=") ? opText.substring(6).trim()
                        : opText.substring(1).trim();
                try {
                    final int count = Integer.parseInt(countText);
                    if (ender) {
                        edit.setEnderSlotCount(slot, count);
                    } else {
                        edit.setSlotCount(slot, count);
                    }
                } catch (NumberFormatException nfe) {
                    edit.errors.add(where + slotName(canonicalSlot(slot)) + " 的数量「" + countText + "」不是整数");
                }
                continue;
            }
            if (opText.startsWith("=")) {
                String rest = opText.substring(1).trim();
                int count = 1;
                final int star = rest.lastIndexOf('*');
                if (star > 0) {
                    final String countText = rest.substring(star + 1).trim();
                    rest = rest.substring(0, star).trim();
                    try {
                        count = Integer.parseInt(countText);
                    } catch (NumberFormatException nfe) {
                        edit.errors.add(where + slotName(canonicalSlot(slot)) + " 的数量「" + countText + "」不是整数");
                        continue;
                    }
                }
                if (ender) {
                    edit.replaceEnderSlot(slot, rest, count, null);
                } else {
                    edit.replaceSlot(slot, rest, count, null);
                }
                continue;
            }
            edit.errors.add(where + "补丁「" + t + "」的操作「" + opText
                    + "」不认识（只认 del / count=N / *N / =物品注册名[*数量]）");
        }
    }

    /** 按 {@code ;} / {@code |} / 换行 切分，但跳过 {@code {…}} 内部的分隔符。 */
    private static List<String> splitTopLevel(String raw) {
        final List<String> out = new ArrayList<>();
        final StringBuilder sb = new StringBuilder();
        int depth = 0;
        for (int i = 0; i < raw.length(); i++) {
            final char c = raw.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}' && depth > 0) {
                depth--;
            }
            if (depth == 0 && (c == ';' || c == '|' || c == '\n' || c == '\r')) {
                out.add(sb.toString());
                sb.setLength(0);
            } else {
                sb.append(c);
            }
        }
        out.add(sb.toString());
        return out;
    }

    /** 一组别名键里第一个非空的值。 */
    private static String firstNonEmpty(Map<String, String> in, String[] keys) {
        for (String k : keys) {
            final String v = trimmed(in.get(k));
            if (!v.isEmpty()) {
                return v;
            }
        }
        return "";
    }
}
