package com.shanhai.common.saveedit;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code level.dat} 里<b>允许改的字段清单</b>（纯 JDK：只有字符串与枚举，不引用 NBT）。
 *
 * <h2>一、准入判据只有一条：它必须能活过"重开这个世界"</h2>
 * 重开时 {@code doWorldLoad:2013} 会把 level.dat <b>整份重写</b>：新标签由
 * {@code PrimaryLevelData#createTag(RegistryAccess, CompoundTag)} 现造，
 * 而它的实体是 {@code setTagData(...)}（{@code PrimaryLevelData.class} 反汇编：先 new 一个空
 * {@code CompoundTag}，再逐键 put）。⇒ <b>只有"先被读进内存模型、再被写回"的键才留得住</b>；
 * 不在 {@code setTagData} 里的键，重开一次就没了（哪怕它此刻在盘上）。
 *
 * <p>所以每个 {@link Field} 都带一条 {@code why}：<b>读</b>它的方法 ＋ <b>写回</b>它的方法，
 * 两条都点名到具体方法（{@code PrimaryLevelData.parse} / {@code LevelSettings.parse} /
 * {@code setTagData}），不接受"大概是世界数据吧"这种说法。
 *
 * <h2>二、为什么还要一张"显式拒绝表"</h2>
 * 用户口径：<b>不许静默</b>。填了一个不让改的键，必须<b>说清为什么不让改</b>——
 * 于是 {@link #rejectionReason(String)} 对每一类"看起来像世界数据但不收"的键给一条理由；
 * 不在任何一类里的，走兜底理由（白名单之外一律拒）。{@link ShanhaiLevelDataEdit#parse(Map)}
 * 会把理由原样放进 {@code errors}。
 *
 * <h2>三、本期收的 21 个字段</h2>
 * <table border="1">
 *   <tr><th>面板标签</th><th>路径</th><th>类型</th><th>为什么它活过重开</th></tr>
 *   <tr><td>世界名</td><td>{@code Data.LevelName}</td><td>string</td>
 *       <td>读 {@code LevelSettings.parse}；写回 {@code setTagData}(settings.levelName())</td></tr>
 *   <tr><td>已过刻</td><td>{@code Data.Time}</td><td>long</td>
 *       <td>读 {@code PrimaryLevelData.parse}；写回 {@code setTagData}(gameTime)</td></tr>
 *   <tr><td>时刻</td><td>{@code Data.DayTime}</td><td>long</td>
 *       <td>读 {@code PrimaryLevelData.parse}；写回 {@code setTagData}(dayTime)</td></tr>
 *   <tr><td>难度</td><td>{@code Data.Difficulty}</td><td>int 0-3</td>
 *       <td>读 {@code LevelSettings.parse}；写回 {@code setTagData}(settings.difficulty().getId())</td></tr>
 *   <tr><td>锁难度</td><td>{@code Data.DifficultyLocked}</td><td>bool</td>
 *       <td>读 {@code PrimaryLevelData.parse}；写回 {@code setTagData}(difficultyLocked)</td></tr>
 *   <tr><td>出生 X/Y/Z</td><td>{@code Data.SpawnX/Y/Z}</td><td>int</td>
 *       <td>读 {@code parse}；写回 {@code setTagData}(xSpawn/ySpawn/zSpawn)</td></tr>
 *   <tr><td>出生朝向</td><td>{@code Data.SpawnAngle}</td><td>float</td>
 *       <td>读 {@code parse}；写回 {@code setTagData}(spawnAngle)</td></tr>
 *   <tr><td>晴天计时</td><td>{@code Data.clearWeatherTime}</td><td>int</td>
 *       <td>读 {@code parse}；写回 {@code setTagData}(clearWeatherTime)</td></tr>
 *   <tr><td>下雨计时</td><td>{@code Data.rainTime}</td><td>int</td>
 *       <td>读 {@code parse}；写回 {@code setTagData}(rainTime)</td></tr>
 *   <tr><td>正在下雨</td><td>{@code Data.raining}</td><td>bool</td>
 *       <td>读 {@code parse}；写回 {@code setTagData}(raining)</td></tr>
 *   <tr><td>雷暴计时</td><td>{@code Data.thunderTime}</td><td>int</td>
 *       <td>读 {@code parse}；写回 {@code setTagData}(thunderTime)</td></tr>
 *   <tr><td>正在雷暴</td><td>{@code Data.thundering}</td><td>bool</td>
 *       <td>读 {@code parse}；写回 {@code setTagData}(thundering)</td></tr>
 *   <tr><td>7 条游戏规则</td><td>{@code Data.GameRules.*}</td><td>string("true"/数字)</td>
 *       <td>读 {@code LevelSettings.parse}(new GameRules(...))；写回
 *           {@code setTagData}(settings.gameRules().createTag())</td></tr>
 * </table>
 *
 * <p>⚠️ 反汇编出处：{@code javap -p -c net.minecraft.world.level.storage.PrimaryLevelData}
 * 的 {@code setTagData} 与 {@code parse}、{@code javap net.minecraft.world.level.LevelSettings}
 * 的 {@code parse}（本机 mapped forge jar：{@code forge-1.20.1-47.4.16_mapped_parchment…}）。
 */
public final class ShanhaiLevelDataSpec {

    /** level.dat 文件名。 */
    public static final String LEVEL_DAT = "level.dat";
    /** 旧档兜底文件名（只读；我们<b>不</b>写它）。 */
    public static final String LEVEL_DAT_OLD = "level.dat_old";

    /** 字段的值类型。 */
    public enum Kind {
        /** 任意非空字符串。 */
        STRING,
        /** 64 位整数。 */
        LONG,
        /** 32 位整数。 */
        INT,
        /** 8 位整数（原版 {@code Data.Difficulty} 就是 ByteTag）。 */
        BYTE,
        /** 单精度浮点。 */
        FLOAT,
        /** 布尔（NBT ByteTag 0/1）。 */
        BOOL,
        /** 游戏规则里的布尔（NBT 里是 StringTag "true"/"false"）。 */
        RULE_BOOL,
        /** 游戏规则里的整数（NBT 里是 StringTag 的数字串）。 */
        RULE_INT
    }

    /**
     * 一个可改字段。
     *
     * @param id    路径（也是面板/编辑模型里的键），如 {@code Data.DayTime}
     * @param kind  值类型
     * @param min   数值下限（STRING/FLOAT 不用）
     * @param max   数值上限（STRING/FLOAT 不用）
     * @param label 面板上的中文短标签
     * @param why   🔴 "为什么它能活过重开"——必须点名读与写回的方法
     */
    public record Field(String id, Kind kind, long min, long max, String label, String why) {

        /** 是不是数值型（要查范围）。 */
        public boolean numeric() {
            return this.kind == Kind.LONG || this.kind == Kind.INT || this.kind == Kind.BYTE
                    || this.kind == Kind.RULE_INT;
        }

        /** 是不是布尔型（面板上认 true/false/1/0）。 */
        public boolean bool() {
            return this.kind == Kind.BOOL || this.kind == Kind.RULE_BOOL;
        }

        /** 范围文字（面板/错误信息用）。 */
        public String rangeText() {
            if (this.kind == Kind.STRING) {
                return "文本";
            }
            if (this.kind == Kind.FLOAT) {
                return "小数";
            }
            if (bool()) {
                return "true/false（也可 1/0）";
            }
            return "[" + this.min + ", " + this.max + "]";
        }
    }

    /** 所有可改字段（顺序 = 面板上的顺序）。 */
    public static final List<Field> FIELDS = List.of(
            field("Data.LevelName", Kind.STRING, 0, 0, "世界名",
                    "读：LevelSettings.parse 取 LevelName 进 settings；写回：setTagData 用 settings.levelName()"),
            field("Data.Time", Kind.LONG, 0L, Long.MAX_VALUE, "已过刻",
                    "读：PrimaryLevelData.parse 取 Time 进 gameTime；写回：setTagData 用 gameTime"),
            field("Data.DayTime", Kind.LONG, 0L, Long.MAX_VALUE, "时刻",
                    "读：PrimaryLevelData.parse 取 DayTime 进 dayTime；写回：setTagData 用 dayTime"),
            field("Data.Difficulty", Kind.BYTE, 0L, 3L, "难度0-3",
                    "读：LevelSettings.parse 取 Difficulty 进 settings；写回：setTagData 用 settings.difficulty().getId()"),
            field("Data.DifficultyLocked", Kind.BOOL, 0L, 1L, "锁难度",
                    "读：PrimaryLevelData.parse 取 DifficultyLocked 进 difficultyLocked；写回：setTagData 用该字段"),
            field("Data.SpawnX", Kind.INT, -30_000_000L, 30_000_000L, "出生X",
                    "读：PrimaryLevelData.parse 取 SpawnX 进 xSpawn；写回：setTagData 用 xSpawn"),
            field("Data.SpawnY", Kind.INT, -2048L, 2048L, "出生Y",
                    "读：PrimaryLevelData.parse 取 SpawnY 进 ySpawn；写回：setTagData 用 ySpawn"),
            field("Data.SpawnZ", Kind.INT, -30_000_000L, 30_000_000L, "出生Z",
                    "读：PrimaryLevelData.parse 取 SpawnZ 进 zSpawn；写回：setTagData 用 zSpawn"),
            field("Data.SpawnAngle", Kind.FLOAT, 0L, 0L, "出生朝向",
                    "读：PrimaryLevelData.parse 取 SpawnAngle 进 spawnAngle；写回：setTagData 用 spawnAngle"),
            field("Data.clearWeatherTime", Kind.INT, 0L, 1_000_000L, "晴天计时",
                    "读：PrimaryLevelData.parse 取 clearWeatherTime 进同名字段；写回：setTagData 用该字段"),
            field("Data.rainTime", Kind.INT, 0L, 1_000_000L, "雨计时",
                    "读：PrimaryLevelData.parse 取 rainTime 进同名字段；写回：setTagData 用该字段"),
            field("Data.raining", Kind.BOOL, 0L, 1L, "正在下雨",
                    "读：PrimaryLevelData.parse 取 raining 进同名字段；写回：setTagData 用该字段"),
            field("Data.thunderTime", Kind.INT, 0L, 1_000_000L, "雷计时",
                    "读：PrimaryLevelData.parse 取 thunderTime 进同名字段；写回：setTagData 用该字段"),
            field("Data.thundering", Kind.BOOL, 0L, 1L, "正在雷暴",
                    "读：PrimaryLevelData.parse 取 thundering 进同名字段；写回：setTagData 用该字段"),
            field("Data.GameRules.keepInventory", Kind.RULE_BOOL, 0L, 1L, "死亡不掉落",
                    "读：LevelSettings.parse 用 Data.GameRules 建 GameRules；写回：setTagData 用 settings.gameRules().createTag()"),
            field("Data.GameRules.doDaylightCycle", Kind.RULE_BOOL, 0L, 1L, "时间流动",
                    "读：LevelSettings.parse 用 Data.GameRules 建 GameRules；写回：setTagData 用 settings.gameRules().createTag()"),
            field("Data.GameRules.doMobSpawning", Kind.RULE_BOOL, 0L, 1L, "自然刷怪",
                    "读：LevelSettings.parse 用 Data.GameRules 建 GameRules；写回：setTagData 用 settings.gameRules().createTag()"),
            field("Data.GameRules.doFireTick", Kind.RULE_BOOL, 0L, 1L, "火焰蔓延",
                    "读：LevelSettings.parse 用 Data.GameRules 建 GameRules；写回：setTagData 用 settings.gameRules().createTag()"),
            field("Data.GameRules.mobGriefing", Kind.RULE_BOOL, 0L, 1L, "生物破坏",
                    "读：LevelSettings.parse 用 Data.GameRules 建 GameRules；写回：setTagData 用 settings.gameRules().createTag()"),
            field("Data.GameRules.naturalRegeneration", Kind.RULE_BOOL, 0L, 1L, "自然回血",
                    "读：LevelSettings.parse 用 Data.GameRules 建 GameRules；写回：setTagData 用 settings.gameRules().createTag()"),
            field("Data.GameRules.randomTickSpeed", Kind.RULE_INT, 0L, 1_000_000L, "随机刻",
                    "读：LevelSettings.parse 用 Data.GameRules 建 GameRules；写回：setTagData 用 settings.gameRules().createTag()")
    );

    /** id → Field（面板/编辑模型都按 id 查）。 */
    public static final Map<String, Field> BY_ID = byId(FIELDS);

    private ShanhaiLevelDataSpec() {}

    private static Field field(String id, Kind kind, long min, long max, String label, String why) {
        return new Field(id, kind, min, max, label, why);
    }

    private static Map<String, Field> byId(List<Field> fields) {
        final Map<String, Field> map = new LinkedHashMap<>();
        for (Field f : fields) {
            map.put(f.id(), f);
        }
        return map;
    }

    /** 按 id 取字段（不认识 ⇒ {@code null}）。 */
    public static Field field(String id) {
        return id == null ? null : BY_ID.get(id.trim());
    }

    /** 这个 id 是不是允许改的。 */
    public static boolean allowed(String id) {
        return field(id) != null;
    }

    /**
     * 🔴 拒绝理由（{@code null} = 这个 id 在白名单里，可以改）。
     *
     * <p>每一类都点名"为什么它不在本期清单里"——用户口径是<b>不许静默</b>，
     * 所以宁可解释得啰嗦一点，也不许把玩家的输入悄悄丢掉。
     */
    public static String rejectionReason(String rawId) {
        if (rawId == null || rawId.trim().isEmpty()) {
            return "字段路径是空的";
        }
        final String id = rawId.trim();
        if (allowed(id)) {
            return null;
        }
        // ── Forge 的额外世界数据（根上的 fml）
        if (id.equals("fml") || id.startsWith("fml.")) {
            return id + " 是 Forge 的额外世界数据（LoadingModList / Registries），由 "
                    + "ForgeHooks.writeAdditionalLevelSaveData 写入、readAdditionalLevelSaveData 读回，"
                    + "不属于世界数据模型 ⇒ 改它不会按你的意图生效";
        }
        // ── 数据包启用表
        if (id.startsWith("Data.DataPacks")) {
            return "Data.DataPacks 是数据包启用表：它确实会被写回（settings.getDataConfiguration() 的 codec），"
                    + "但换包必须在开世界时重建 PackRepository，单改 level.dat 只会让下次进世界少/多包，"
                    + "而且和 datapacks 目录、server.properties 耦合 ⇒ 本期拒";
        }
        // ── 世界边界：一整组状态机
        if (id.startsWith("Data.Border")) {
            return id + " 属于世界边界：它会被 WorldBorder.Settings.write 写回，但 center/size/lerp 是一组互相"
                    + "耦合的状态，运行期每 tick 被 WorldBorder 推动，单改一项会被马上覆盖或造成半更新 "
                    + "⇒ 要改边界请走 /worldborder";
        }
        // ── 世界生成设置
        if (id.startsWith("Data.WorldGenSettings")) {
            return "Data.WorldGenSettings 会被写回（worldOptions 的编码结果），但改 seed/维度只影响"
                    + "新生成区块，已生成地形一个方块都不会变 ⇒ 语义误导，本期拒";
        }
        // ── 世界里的玩家快照
        if (id.equals("Data.Player") || id.startsWith("Data.Player.")) {
            return "Data.Player 是世界里最后下线玩家的快照；真正登录时以 playerdata/<uuid>.dat 为准"
                    + "（那条走一期的「玩家」页）⇒ 改这里等于白改";
        }
        // ── 运行期状态
        if (id.startsWith("Data.DragonFight") || id.startsWith("Data.ScheduledEvents")
                || id.startsWith("Data.CustomBossEvents") || id.startsWith("Data.WanderingTrader")) {
            return id + " 是运行期状态（末地龙战 / 定时事件 / BOSS 栏 / 流浪商人计时），"
                    + "由游戏自己每 tick 推进或重置 ⇒ 写进去也留不住";
        }
        // ── 版本 / 元数据
        if (id.equals("Data.DataVersion") || id.equals("Data.Version") || id.equals("Data.version")
                || id.equals("Data.WasModded") || id.equals("Data.ServerBrands")
                || id.equals("Data.confirmedExperimentalSettings") || id.equals("Data.forgeLifecycle")
                || id.equals("Data.initialized") || id.equals("Data.LastPlayed")) {
            return id + " 是版本/元数据，由游戏自己写（写错会让存档被判定成「需要升级」或「实验性」）"
                    + " ⇒ 本期不开放";
        }
        // ── 模式 / 作弊 / 硬核：属于世界模型，但本期故意不做
        if (id.equals("Data.GameType")) {
            return "Data.GameType 只决定「新玩家」的默认模式；已有玩家的模式以 playerdata 的 "
                    + "playerGameType 为准（一期「玩家」页）⇒ 改这里容易让人以为改了却没变，本期不开放";
        }
        if (id.equals("Data.hardcore")) {
            return "Data.hardcore 会改变死亡处置语义（旁观/封档），风险太大 ⇒ 本期不开放";
        }
        if (id.equals("Data.allowCommands")) {
            return "Data.allowCommands 等于开关作弊，牵涉玩家权限与统计 ⇒ 本期不开放";
        }
        // ── 模组私有键
        if (id.startsWith("Data.ForgeCaps") || id.startsWith("Data.ForgeData")
                || id.startsWith("Data.ftb") || id.startsWith("Data.ae2") || id.startsWith("Data.gt")
                || id.startsWith("Data.KubeJS") || id.startsWith("Data.kubejs")) {
            return id + " 是模组/Forge 的私有键：不属于世界数据模型，重开后由对应模组自己维护"
                    + "（或直接丢掉）⇒ 本期拒";
        }
        return id + " 不在本期白名单里（本工具只改会被 PrimaryLevelData#createTag 写回、"
                + "且属于世界数据模型的字段，见 ShanhaiLevelDataSpec.FIELDS 的 21 项）";
    }

    /** 全部字段的 id（面板/日志用）。 */
    public static List<String> ids() {
        final List<String> out = new ArrayList<>(FIELDS.size());
        for (Field f : FIELDS) {
            out.add(f.id());
        }
        return out;
    }

    /** 字段标签（按 id；不认识就返回 id 本身）。 */
    public static String label(String id) {
        final Field f = field(id);
        return f == null ? String.valueOf(id) : f.label();
    }
}
