package com.shanhai.add.config;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.mojang.logging.LogUtils;
import net.minecraftforge.fml.loading.FMLPaths;
import org.slf4j.Logger;

/**
 * 把旧本体配置里的 {@code [gravity] multiplier} <b>一次性</b>搬进扩展自己的配置（拆分设计 §4 / §11 待确认 6）。
 *
 * <h2>为什么必须迁（不能直接默认 1.0）</h2>
 * 拆分前 {@code /shanhai gravity 0.5} 把值写进 {@code config/shanhai-common.toml}；拆分后那个键归扩展的
 * {@code config/shanhai_add-common.toml}。不迁的话，玩家<b>当场调过的手感值</b>在升级那一刻静默回到 1.0
 * —— 而"重力坏了"与"重力被重置"在日志上长得一模一样。
 *
 * <h2>迁移规则（三条，全部机器可判）</h2>
 * <ol>
 *   <li><b>只搬非默认值</b>：旧文件里是 1.0（或读不到）⇒ 什么都不做；</li>
 *   <li><b>不覆盖玩家已经设过的新值</b>：新文件里 {@code multiplier != 1.0} ⇒ 保留新值，只记一行
 *       {@code skipped reason=new_value_already_set}；</li>
 *   <li><b>失败只记日志、绝不影响启动</b>：整段包 {@code catch (Throwable)}（读文件、解析、写配置任何一步
 *       出问题，代价都只是"重力回到 1.0"）。</li>
 * </ol>
 *
 * <h2>为什么在这里跑（{@code FMLCommonSetupEvent}，由重力装配点调用）</h2>
 * 配置由 Forge 在 mod 构造之后、common setup 之前加载完成 ⇒ 这一刻 {@code ShanhaiAddConfig} 一定可读可写；
 * 而 mod 构造器里它<b>还没加载</b>（{@code ConfigValue#get()} 会抛）。且 common setup 每个游戏实例只跑一次
 * ⇒ 天然满足"只迁一次"。{@link #runOnce()} 自己再加一道幂等闩（同一进程里被别人重复调用也无害）。
 *
 * <p>日志前缀统一 {@code [SHANHAI-ADD]}（设计 §5.1 末段：新类统一这个前缀）。
 */
public final class AddGravityConfigMigration {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** 本类日志前缀（与扩展其它新类一致）。 */
    public static final String PREFIX = "[SHANHAI-ADD]";

    /** 拆分前本体那份配置的文件名（Forge 按 modid 生成：{@code shanhai}-common.toml）。 */
    public static final String LEGACY_FILE_NAME = "shanhai-common.toml";

    /** 新文件的展示名（只用于日志）。 */
    public static final String NEW_FILE_NAME = ShanhaiAddConfig.FILE_NAME;

    /** 幂等闩：只迁一次。 */
    private static boolean ran;

    private AddGravityConfigMigration() {}

    /**
     * 跑一次迁移（重复调用直接返回）。
     *
     * <p>🔴 本方法<b>不会抛异常</b>，也不会改变"配置是否已加载"这个前提以外的任何状态。
     */
    public static synchronized void runOnce() {
        if (ran) {
            return;
        }
        try {
            // 🔴 只有"真的做出了决定"才置 ran：配置还没加载时【不】置，
            //    留给下一个调用点（服务端启动时）再试一次 —— 否则一次太早的调用
            //    会把迁移永久锁死在"跳过"上（那正是最坏形态：玩家静默丢手感值）。
            if (migrate()) {
                ran = true;
            }
        } catch (Throwable t) {
            ran = true;
            // 迁移失败 = 重力按默认 1.0 走，绝不许把启动带崩
            LOGGER.warn("{} gravity_migration failed err={}（忽略：扩展其余能力照常，重力按默认 {}）",
                    PREFIX, t.toString(), ShanhaiAddConfig.DEFAULT_GRAVITY_MULTIPLIER, t);
        }
    }

    /**
     * 真正干活。
     *
     * @return {@code true} = 这一轮已经得出结论（迁移完成／没有可迁的东西／值不用动）；
     *         {@code false} = 配置还没加载，<b>不算数</b>，留给下一个调用点重试
     */
    private static boolean migrate() throws Exception {
        final Path configDir = FMLPaths.CONFIGDIR.get();
        final Path legacy = configDir.resolve(LEGACY_FILE_NAME);
        if (!Files.isRegularFile(legacy)) {
            LOGGER.info("{} gravity_migration skipped reason=no_legacy_file path=config/{}",
                    PREFIX, LEGACY_FILE_NAME);
            return true;
        }
        if (!ShanhaiAddConfig.isLoaded()) {
            // ⚠️ 不置 ran：调用点还有一次（ShanhaiGravityEvents#onServerStarted）
            LOGGER.info("{} gravity_migration deferred reason=config_not_loaded path=config/{}",
                    PREFIX, NEW_FILE_NAME);
            return false;
        }

        final List<String> lines = Files.readAllLines(legacy, StandardCharsets.UTF_8);
        final double legacyValue = GravityTomlScan.parseGravityMultiplier(lines);
        final double newValue = ShanhaiAddConfig.gravityOr(ShanhaiAddConfig.DEFAULT_GRAVITY_MULTIPLIER);

        if (Double.isNaN(legacyValue)) {
            LOGGER.info("{} gravity_migration skipped reason=legacy_key_missing old=config/{} [{}]{}",
                    PREFIX, LEGACY_FILE_NAME, GravityTomlScan.SECTION, GravityTomlScan.KEY);
            return true;
        }
        if (legacyValue == ShanhaiAddConfig.DEFAULT_GRAVITY_MULTIPLIER) {
            LOGGER.info("{} gravity_migration skipped reason=legacy_is_default old={} new={} path=config/{}",
                    PREFIX, fmt(legacyValue), fmt(newValue), NEW_FILE_NAME);
            return true;
        }
        if (newValue != ShanhaiAddConfig.DEFAULT_GRAVITY_MULTIPLIER) {
            LOGGER.info("{} gravity_migration skipped reason=new_value_already_set old={} new={} path=config/{}",
                    PREFIX, fmt(legacyValue), fmt(newValue), NEW_FILE_NAME);
            return true;
        }

        ShanhaiAddConfig.COMMON.gravityMultiplier.set(legacyValue);
        ShanhaiAddConfig.COMMON_SPEC.save();

        final double written = ShanhaiAddConfig.gravityOr(ShanhaiAddConfig.DEFAULT_GRAVITY_MULTIPLIER);
        LOGGER.info("{} gravity_migration migrated value={} from=config/{} [{}]{} to=config/{} [{}]{} "
                        + "verified={}（旧文件不再被读取；新值以本行 + 新文件内容为准）",
                PREFIX, fmt(legacyValue), LEGACY_FILE_NAME, GravityTomlScan.SECTION, GravityTomlScan.KEY,
                NEW_FILE_NAME, GravityTomlScan.SECTION, GravityTomlScan.KEY, fmt(written));
        return true;
    }

    /** 与 {@code ShanhaiGravity.fmt} 同口径的读数格式（这里不能依赖 gravity 包，保持本类能单独看懂）。 */
    private static String fmt(double value) {
        if (value == Math.rint(value) && !Double.isInfinite(value)) {
            return String.valueOf((long) value);
        }
        return String.valueOf(value);
    }
}
