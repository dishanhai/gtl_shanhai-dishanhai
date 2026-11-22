package com.shanhai.add.config;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import com.mojang.logging.LogUtils;
import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.fml.loading.FMLPaths;
import org.slf4j.Logger;

/**
 * 扩展配置（{@code config/shanhai_add-common.toml}）的<b>唯一写入／回读出口</b>。
 *
 * <h2>为什么需要这个类（用户 2026-11-22 报告的原话）</h2>
 * 「我发现山海add的这些例如热修改的配置无法自动保存，就是你重开游戏之后这个配置就会重置为默认，
 * 你把他改成可以保存的」
 *
 * <p>取证结论：{@code gravity.multiplier} 与 {@code guard_module.*} 早就有一份
 * {@code ConfigValue#set(…)} ＋ {@code COMMON_SPEC.save()} —— <b>它真的会写盘</b>（2026-11-22 实测：
 * 指令改完再停服，文件里 {@code multiplier = 0.37} / {@code momentum_guard = false}）。
 * 真正没落盘的是<b>热修改那一族</b>：{@code hotfix.auto} 连配置键都没有（只有一个静态
 * {@code AtomicBoolean}），{@code hotfix.chain_wipe} 有键但命令只翻内存里的 static
 * ⇒ 重启按文件重置为默认。
 *
 * <h2>本类的三条纪律</h2>
 * <ol>
 *   <li><b>写值 = 写配置 ＋ 立刻落盘 ＋ 回读磁盘</b>：{@link ForgeConfigSpec#save()} 走的是
 *       {@code CommentedFileConfig#save()}（1.20.1-47.4.16 的 {@code ForgeConfigSpec#save()} 里
 *       {@code childConfig instanceof FileConfig} 为真，已用 javap ＋ 真跑双证）。</li>
 *   <li><b>不信内存，只信盘上的字节</b>：写完立刻把 toml 重新读一遍，用<b>文件里的原值</b>判定
 *       {@code verified}。这样"内存改了、回显新值、文件没变"这种假成功在日志里是
 *       {@code saved=true verified=false}，不可能再被当成成功。</li>
 *   <li><b>启动时把"文件里有"与"回落默认"分开</b>：{@code cfg_load … source=file|default} 的依据是
 *       <b>Forge 加载/修复这份 toml 之前</b>拍下的文件原文（{@link #snapshotBeforeLoad}，
 *       在 {@code ShanhaiAddConfig} 的静态块里第一句调用 ⇒ 一定早于配置加载）。
 *       不用"当前值 == 默认值"来猜 —— 玩家自己把值设成默认值时那个判据就错了。</li>
 * </ol>
 *
 * <h2>日志（机器可判，用户点名要的两行）</h2>
 * <pre>
 *   [SHANHAI-ADD] cfg_write key=hotfix.auto value=false file=config/shanhai_add-common.toml saved=true verified=true
 *   [SHANHAI-ADD] cfg_load key=gravity.multiplier value=0.37 source=file default=1.0 file_raw=0.37
 * </pre>
 * {@code saved=} 的口径：<b>配置写入 + 落盘调用没抛异常</b>；{@code verified=} 的口径：
 * <b>盘上重新读出来就是新值</b>。{@link #writeBoolean} 等方法返回的也是 verified
 * （调用方回显时只许用它，不许用内存值）。
 */
public final class AddConfigFiles {

    /** 与扩展其它新类一致的前缀（{@code ShanhaiAdd} ／ 迁移 ／ 本类共用）。 */
    public static final String PREFIX = "[SHANHAI-ADD]";

    /**
     * 相对路径（日志里给人读的那一份）。
     *
     * <p>{@link ShanhaiAddConfig#FILE_NAME} 是编译期常量 ⇒ 这里引用它不会触发那个类的静态块
     * （也就不会在快照之前把配置类初始化）。
     */
    public static final String FILE_REL = "config/" + ShanhaiAddConfig.FILE_NAME;

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Forge 加载/修复这份 toml <b>之前</b>的文件原文；{@code null} = 没拍到 / 文件不存在。 */
    private static volatile String bootText;

    /** 快照是否已经尝试过（只拍一次：第一次调用点就是"最早"的那个）。 */
    private static volatile boolean captured;

    private AddConfigFiles() {}

    // ═══════════════════════════════ 启动前快照 ═══════════════════════════════

    /**
     * 拍下"Forge 碰它之前"的文件原文（幂等：只记第一次调用）。
     *
     * <p>为什么必须早：Forge 在加载配置时会用 spec 里的键<b>补齐</b>缺项并<b>立刻写回</b>
     * （{@code ForgeConfigSpec#setConfig} → {@code correct(…)} → {@code FileConfig#save()}）。
     * 一旦它补过，"文件里到底有没有这一行"就再也分不出来了。
     *
     * @param why 调用点（{@code class_init} / 命令手动触发），只进日志
     */
    public static synchronized void snapshotBeforeLoad(String why) {
        if (captured) {
            return;
        }
        captured = true;
        try {
            final Path path = configPath();
            if (path != null && Files.isRegularFile(path)) {
                bootText = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
            }
            LOGGER.info("{} cfg_snapshot why={} file={} exists={} bytes={}",
                    PREFIX, why, FILE_REL, bootText != null, bootText == null ? 0 : bootText.length());
        } catch (Throwable t) {
            LOGGER.warn("{} cfg_snapshot why={} file={} failed err={}（source= 一律按 default 计）",
                    PREFIX, why, FILE_REL, t.toString());
        }
    }

    /** 这份配置文件的真实路径（拿不到 ⇒ {@code null}，绝不猜）。 */
    public static Path configPath() {
        try {
            return FMLPaths.CONFIGDIR.get().resolve(ShanhaiAddConfig.FILE_NAME);
        } catch (Throwable t) {
            return null;
        }
    }

    // ═══════════════════════════════ 写：配置 + 落盘 + 回读 ═══════════════════════════════

    /**
     * 写一个布尔键（{@code hotfix.auto} / {@code guard_module.*} / {@code hotfix.chain_wipe}）。
     *
     * @return {@code true} = 盘上重新读出来就是 {@code want}（调用方回显只许用它）
     */
    public static boolean writeBoolean(String key, ForgeConfigSpec.BooleanValue value, boolean want) {
        return write(key, want ? "true" : "false", () -> value.set(want));
    }

    /** 写一个整数键（{@code guard_module.stack_radar_interval_ms} / {@code …_depth}）。 */
    public static boolean writeInt(String key, ForgeConfigSpec.IntValue value, int want) {
        return write(key, Integer.toString(want), () -> value.set(want));
    }

    /** 写一个浮点键（{@code gravity.multiplier}）。 */
    public static boolean writeDouble(String key, ForgeConfigSpec.DoubleValue value, double want) {
        return write(key, fmt(want), () -> value.set(want));
    }

    /**
     * 共用实现：改内存值 ⇒ 落盘 ⇒ 回读磁盘 ⇒ 打一行 {@code cfg_write}。
     *
     * <p>🔴 不许在别处再写一份"set + save"：那会让 {@code saved=} 有第二种口径。
     */
    private static boolean write(String key, String rendered, Runnable apply) {
        if (!ShanhaiAddConfig.isLoaded()) {
            // 配置还没加载（= 不该发生：所有入口都在命令/服务端生命周期里）
            LOGGER.warn("{} cfg_write key={} value={} file={} saved=false reason=config_not_loaded",
                    PREFIX, key, rendered, FILE_REL);
            return false;
        }
        try {
            apply.run();
            // ForgeConfigSpec#save(): childConfig 是 CommentedFileConfig（implements FileConfig）
            // ⇒ 真的写盘。⚠️ 2026-11-22 负对照实测：即使把这一行注释掉，只要 ConfigValue#set() 跑过，
            // nightconfig 的 autosave 仍然会把文件写出去 —— 所以【落盘与否的判据只能是盘上的字节】，
            // 不能拿"有没有调 save()"当判据（这也是 saved= 用回读值的原因）。
            ShanhaiAddConfig.COMMON_SPEC.save();
        } catch (Throwable t) {
            LOGGER.warn("{} cfg_write key={} value={} file={} saved=false err={}",
                    PREFIX, key, rendered, FILE_REL, t.toString());
            return false;
        }
        // 🔴 saved 的口径 = 【盘上重新读出来就是新值】。不是"save() 没抛异常"：
        //    内存改了、回显新值、文件没变 —— 那种情况必须在这里被判成 false。
        final String back = rawValue(key);
        final boolean saved = rendered.equals(back);
        if (saved) {
            LOGGER.info("{} cfg_write key={} value={} file={} saved=true verified=true",
                    PREFIX, key, rendered, FILE_REL);
        } else {
            LOGGER.warn("{} cfg_write key={} value={} file={} saved=false verified=false readback={}",
                    PREFIX, key, rendered, FILE_REL, back == null ? "(missing)" : back);
        }
        return saved;
    }

    /** 现在（写完立刻）盘上这个键的原始右值；没有 ⇒ {@code null}。 */
    public static String rawValue(String key) {
        return rawValueIn(readNow(), key);
    }

    // ═══════════════════════════════ 启动读数 ═══════════════════════════════

    /**
     * 一次性把这份配置里<b>所有</b>键的实际值打出来（每个键一行 {@code cfg_load}）。
     *
     * <p>调用点：{@link AddConfigBoot}（{@code FMLCommonSetupEvent}，配置已加载、世界还没起）。
     * 这也正是玩家"重开游戏后看这一行就知道有没有被重置"的那一行。
     */
    public static void logLoadAll() {
        LOGGER.info("{} cfg_load file={} loaded={} snapshot={}",
                PREFIX, FILE_REL, ShanhaiAddConfig.isLoaded(),
                bootText == null ? "absent" : "captured");
        logLoad(ShanhaiAddConfig.KEY_GRAVITY,
                fmt(ShanhaiAddConfig.DEFAULT_GRAVITY_MULTIPLIER),
                fmt(ShanhaiAddConfig.gravityOr(ShanhaiAddConfig.DEFAULT_GRAVITY_MULTIPLIER)));
        logLoad(ShanhaiAddConfig.KEY_MOMENTUM,
                fmt(ShanhaiAddConfig.DEFAULT_MOMENTUM_GUARD),
                fmt(ShanhaiAddConfig.momentumOr(ShanhaiAddConfig.DEFAULT_MOMENTUM_GUARD)));
        logLoad(ShanhaiAddConfig.KEY_POS,
                fmt(ShanhaiAddConfig.DEFAULT_POS_GUARD),
                fmt(ShanhaiAddConfig.posOr(ShanhaiAddConfig.DEFAULT_POS_GUARD)));
        logLoad(ShanhaiAddConfig.KEY_INV,
                fmt(ShanhaiAddConfig.DEFAULT_INV_GUARD),
                fmt(ShanhaiAddConfig.invOr(ShanhaiAddConfig.DEFAULT_INV_GUARD)));
        logLoad(ShanhaiAddConfig.KEY_STACK_RADAR,
                fmt(ShanhaiAddConfig.DEFAULT_STACK_RADAR),
                fmt(ShanhaiAddConfig.stackRadarOr(ShanhaiAddConfig.DEFAULT_STACK_RADAR)));
        logLoad(ShanhaiAddConfig.KEY_STACK_RADAR_INTERVAL,
                Integer.toString(ShanhaiAddConfig.DEFAULT_STACK_RADAR_INTERVAL_MS),
                Integer.toString(ShanhaiAddConfig.stackRadarIntervalMsOr(
                        ShanhaiAddConfig.DEFAULT_STACK_RADAR_INTERVAL_MS)));
        logLoad(ShanhaiAddConfig.KEY_STACK_RADAR_DEPTH,
                Integer.toString(ShanhaiAddConfig.DEFAULT_STACK_RADAR_DEPTH),
                Integer.toString(ShanhaiAddConfig.stackRadarDepthOr(
                        ShanhaiAddConfig.DEFAULT_STACK_RADAR_DEPTH)));
        logLoad(ShanhaiAddConfig.KEY_CHAIN_WIPE,
                fmt(ShanhaiAddConfig.DEFAULT_CHAIN_WIPE),
                fmt(ShanhaiAddConfig.chainWipeOr(ShanhaiAddConfig.DEFAULT_CHAIN_WIPE)));
        logLoad(ShanhaiAddConfig.KEY_HOTFIX_AUTO,
                fmt(ShanhaiAddConfig.DEFAULT_HOTFIX_AUTO),
                fmt(ShanhaiAddConfig.hotfixAutoOr(ShanhaiAddConfig.DEFAULT_HOTFIX_AUTO)));
    }

    /** 一行 {@code cfg_load}：值来自配置 API，来源来自"加载前那份原文"。 */
    private static void logLoad(String key, String def, String value) {
        final String fromFile = rawValueIn(bootText, key);
        LOGGER.info("{} cfg_load key={} value={} source={} default={} file_raw={}",
                PREFIX, key, value, fromFile == null ? "default" : "file", def,
                fromFile == null ? "(missing)" : fromFile);
    }

    // ═══════════════════════════════ toml 文本解析（不引入任何新依赖） ═══════════════════════════════

    /**
     * 在 toml 原文里取 {@code key}（{@code "a.b"} 形式）的原始右值；取不到 ⇒ {@code null}。
     *
     * <p>为什么手写而不用 nightconfig：这里只想知道"<b>这一行在不在文件里</b>"，
     * 而按 spec 读出来的值会连带 Forge 补齐的缺项一起返回（那样判据就失效了）。
     * 只认 {@code [section]} 行 ＋ {@code key = value} 行，注释与行内注释都跳过。
     */
    public static String rawValueIn(String text, String key) {
        if (text == null || text.isEmpty() || key == null || key.isEmpty()) {
            return null;
        }
        final int dot = key.lastIndexOf('.');
        final String section = dot < 0 ? "" : key.substring(0, dot);
        final String leaf = dot < 0 ? key : key.substring(dot + 1);
        String current = "";
        for (final String rawLine : text.split("\r?\n", -1)) {
            final String line = rawLine.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            if (line.startsWith("[") && line.endsWith("]")) {
                current = line.substring(1, line.length() - 1).trim();
                continue;
            }
            if (!current.equals(section)) {
                continue;
            }
            final int eq = line.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            if (!line.substring(0, eq).trim().equals(leaf)) {
                continue;
            }
            return stripInlineComment(line.substring(eq + 1)).trim();
        }
        return null;
    }

    /** 去掉行内注释（引号里的 {@code #} 不算注释）。 */
    private static String stripInlineComment(String value) {
        boolean inString = false;
        for (int i = 0; i < value.length(); i++) {
            final char c = value.charAt(i);
            if (c == '"') {
                inString = !inString;
            } else if (c == '#' && !inString) {
                return value.substring(0, i);
            }
        }
        return value;
    }

    /** 现在这份文件的原文；读不到 ⇒ {@code null}（不算错误，只影响 verified 判据）。 */
    private static String readNow() {
        try {
            final Path path = configPath();
            if (path == null || !Files.isRegularFile(path)) {
                return null;
            }
            return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
        } catch (Throwable t) {
            return null;
        }
    }

    // ═══════════════════════════════ 渲染（日志 / 回读比较共用一个口径） ═══════════════════════════════

    /** 布尔/整数/浮点共用的一份渲染：与 nightconfig 写进 toml 的字面量一致。 */
    public static String fmt(Object value) {
        if (value instanceof Boolean b) {
            return b ? "true" : "false";
        }
        if (value instanceof Double d) {
            return fmtDouble(d);
        }
        return String.valueOf(value);
    }

    /** 浮点渲染：整数值给 {@code 1.0}（与 toml 一致），其余走 {@code Double.toString}。 */
    public static String fmtDouble(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return Double.toString(value);
        }
        if (value == Math.rint(value) && Math.abs(value) < 1.0E16D) {
            return String.format(Locale.ROOT, "%.1f", value);
        }
        return Double.toString(value);
    }
}
