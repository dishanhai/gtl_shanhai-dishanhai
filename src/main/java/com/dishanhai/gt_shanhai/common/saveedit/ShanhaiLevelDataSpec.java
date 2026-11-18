package com.dishanhai.gt_shanhai.common.saveedit;

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
        @Override public String label() { return ShanhaiSaveEditText.text(label); }
        @Override public String why() { return ShanhaiSaveEditText.text(why); }

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
                return ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.dd81861811e57bf7");
            }
            if (this.kind == Kind.FLOAT) {
                return ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.75a7f49573c83da1");
            }
            if (bool()) {
                return ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.9252591b605c726f");
            }
            return "[" + this.min + ", " + this.max + "]";
        }
    }

    /** 所有可改字段（顺序 = 面板上的顺序）。 */
    public static final List<Field> FIELDS = List.of(
            field("Data.LevelName", Kind.STRING, 0, 0, "gt_shanhai.saveedit.text.4c478920e3169001",
                    "gt_shanhai.saveedit.text.4c11a483e14fda83"),
            field("Data.Time", Kind.LONG, 0L, Long.MAX_VALUE, "gt_shanhai.saveedit.text.36170ea1b7e9a4f3",
                    "gt_shanhai.saveedit.text.4270e72c3fe7d573"),
            field("Data.DayTime", Kind.LONG, 0L, Long.MAX_VALUE, "gt_shanhai.saveedit.text.fde486deff029cd3",
                    "gt_shanhai.saveedit.text.2442a3b02dc9f24c"),
            field("Data.Difficulty", Kind.BYTE, 0L, 3L, "gt_shanhai.saveedit.text.24a89036e3622039",
                    "gt_shanhai.saveedit.text.8a7beba0363cf75b"),
            field("Data.DifficultyLocked", Kind.BOOL, 0L, 1L, "gt_shanhai.saveedit.text.6bfb92be4194811d",
                    "gt_shanhai.saveedit.text.24137e7e0719a8f2"),
            field("Data.SpawnX", Kind.INT, -30_000_000L, 30_000_000L, "gt_shanhai.saveedit.text.a7b2064c5c0bcde8",
                    "gt_shanhai.saveedit.text.f2cc49c63ef71862"),
            field("Data.SpawnY", Kind.INT, -2048L, 2048L, "gt_shanhai.saveedit.text.fbe8abb563bca870",
                    "gt_shanhai.saveedit.text.2214f2389874385f"),
            field("Data.SpawnZ", Kind.INT, -30_000_000L, 30_000_000L, "gt_shanhai.saveedit.text.f018f8aed3c36469",
                    "gt_shanhai.saveedit.text.ee9d73290800ff40"),
            field("Data.SpawnAngle", Kind.FLOAT, 0L, 0L, "gt_shanhai.saveedit.text.d4cd85c430069a13",
                    "gt_shanhai.saveedit.text.b495ebcb4f36326e"),
            field("Data.clearWeatherTime", Kind.INT, 0L, 1_000_000L, "gt_shanhai.saveedit.text.15a354c2010cdf75",
                    "gt_shanhai.saveedit.text.67a0436a5ff56138"),
            field("Data.rainTime", Kind.INT, 0L, 1_000_000L, "gt_shanhai.saveedit.text.e56c36ef19073701",
                    "gt_shanhai.saveedit.text.53d3ac7e1065d217"),
            field("Data.raining", Kind.BOOL, 0L, 1L, "gt_shanhai.saveedit.text.af13c212fee84c78",
                    "gt_shanhai.saveedit.text.437796418f39af8e"),
            field("Data.thunderTime", Kind.INT, 0L, 1_000_000L, "gt_shanhai.saveedit.text.9db089c2449f6d46",
                    "gt_shanhai.saveedit.text.a766e3f7fcd31f4b"),
            field("Data.thundering", Kind.BOOL, 0L, 1L, "gt_shanhai.saveedit.text.fc6ba755d3dc2909",
                    "gt_shanhai.saveedit.text.02b379dbb2d92a4c"),
            field("Data.GameRules.keepInventory", Kind.RULE_BOOL, 0L, 1L, "gt_shanhai.saveedit.text.7ec93fca401c21d6",
                    "gt_shanhai.saveedit.text.d809d5b9d12b8504"),
            field("Data.GameRules.doDaylightCycle", Kind.RULE_BOOL, 0L, 1L, "gt_shanhai.saveedit.text.d9b718ff4c85c1a8",
                    "gt_shanhai.saveedit.text.d809d5b9d12b8504"),
            field("Data.GameRules.doMobSpawning", Kind.RULE_BOOL, 0L, 1L, "gt_shanhai.saveedit.text.9c83c3f83447c344",
                    "gt_shanhai.saveedit.text.d809d5b9d12b8504"),
            field("Data.GameRules.doFireTick", Kind.RULE_BOOL, 0L, 1L, "gt_shanhai.saveedit.text.7e67724e23c6d007",
                    "gt_shanhai.saveedit.text.d809d5b9d12b8504"),
            field("Data.GameRules.mobGriefing", Kind.RULE_BOOL, 0L, 1L, "gt_shanhai.saveedit.text.5b46f59506bebde8",
                    "gt_shanhai.saveedit.text.d809d5b9d12b8504"),
            field("Data.GameRules.naturalRegeneration", Kind.RULE_BOOL, 0L, 1L, "gt_shanhai.saveedit.text.38bacebb9e1daf6e",
                    "gt_shanhai.saveedit.text.d809d5b9d12b8504"),
            field("Data.GameRules.randomTickSpeed", Kind.RULE_INT, 0L, 1_000_000L, "gt_shanhai.saveedit.text.2aeaac7069c624c9",
                    "gt_shanhai.saveedit.text.d809d5b9d12b8504")
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
            return ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.100788a6d4835584");
        }
        final String id = rawId.trim();
        if (allowed(id)) {
            return null;
        }
        // ── Forge 的额外世界数据（根上的 fml）
        if (id.equals("fml") || id.startsWith("fml.")) {
            return id + ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.6d78bd761ef274c4")
                    + ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.ec17758d80e42cfa")
                    + ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.9967820b5d39a7be");
        }
        // ── 数据包启用表
        if (id.startsWith("Data.DataPacks")) {
            return ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.03aae597ce7c66cb")
                    + ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.e035bd458c05c277")
                    + ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.7a81f67c5f5282cb");
        }
        // ── 世界边界：一整组状态机
        if (id.startsWith("Data.Border")) {
            return id + ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.e211f2387c45cd16")
                    + ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.e7fbb5c81f7ab47c")
                    + ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.5c754e2a3b304570");
        }
        // ── 世界生成设置
        if (id.startsWith("Data.WorldGenSettings")) {
            return ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.de9639c6b278c1a5")
                    + ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.7dd234764c887afb");
        }
        // ── 世界里的玩家快照
        if (id.equals("Data.Player") || id.startsWith("Data.Player.")) {
            return ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.3c7e3dfb9040cdb5")
                    + ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.cf1b5dc7de2cad08");
        }
        // ── 运行期状态
        if (id.startsWith("Data.DragonFight") || id.startsWith("Data.ScheduledEvents")
                || id.startsWith("Data.CustomBossEvents") || id.startsWith("Data.WanderingTrader")) {
            return id + ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.2191a3f31eeda0fb")
                    + ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.dfb738eb8001df7c");
        }
        // ── 版本 / 元数据
        if (id.equals("Data.DataVersion") || id.equals("Data.Version") || id.equals("Data.version")
                || id.equals("Data.WasModded") || id.equals("Data.ServerBrands")
                || id.equals("Data.confirmedExperimentalSettings") || id.equals("Data.forgeLifecycle")
                || id.equals("Data.initialized") || id.equals("Data.LastPlayed")) {
            return id + ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.34822f3ff9ce6428")
                    + ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.3404c02da602ab4f");
        }
        // ── 模式 / 作弊 / 硬核：属于世界模型，但本期故意不做
        if (id.equals("Data.GameType")) {
            return ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.988574e0d97e53a6")
                    + ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.4e9451d13e19268e");
        }
        if (id.equals("Data.hardcore")) {
            return ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.82ed0d064530cec9");
        }
        if (id.equals("Data.allowCommands")) {
            return ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.f0265c0ec0fdc9af");
        }
        // ── 模组私有键
        if (id.startsWith("Data.ForgeCaps") || id.startsWith("Data.ForgeData")
                || id.startsWith("Data.ftb") || id.startsWith("Data.ae2") || id.startsWith("Data.gt")
                || id.startsWith("Data.KubeJS") || id.startsWith("Data.kubejs")) {
            return id + ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.417822320f7a0f3a")
                    + ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.1433c3689a3629e2");
        }
        return id + ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.c088b408ca4ce774")
                + ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.69a139b81348d6ef");
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
