package com.dishanhai.gt_shanhai.config;

import net.minecraftforge.fml.loading.FMLPaths;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

/**
 * 山海模组配置文件路径的唯一出口。
 *
 * <p>所有 config/gt_shanhai 下的文件都必须经过本类取路径，不要再在业务代码里手写
 * {@code new File("config/gt_shanhai", ...)} 或 {@code Path.of("config/...")}。
 * 手写相对路径在服务器上会落到启动目录而非 {@code FMLPaths.CONFIGDIR}，是历史遗留的坑。</p>
 *
 * <h2>目录结构</h2>
 * <pre>
 * config/gt_shanhai/
 * ├─ gt_shanhai-common.toml          Forge 配置（位置由 ModLoadingContext 注册决定，不可移动）
 * ├─ changelog/                     更新公告 Markdown
 * ├─ guides/                        GuideME 热重载目录
 * ├─ shop/
 * │  ├─ shop.json                   商品清单
 * │  ├─ shop_category_order.json   页签排序
 * │  ├─ shop_client_ui.json        客户端界面偏好
 * │  ├─ shop_quest_links.json      FTBQ 任务关联
 * │  ├─ shop_stage_requirements.json 分类阶段提交物
 * │  ├─ exchanges.json             兑换表
 * │  └─ currency/
 * │     ├─ currency_rates.json     货币汇率
 * │     └─ currency_stages.json    货币阶段
 * ├─ recipe/
 * │  ├─ recipe_overrides.json      配方覆写
 * │  ├─ strip_rules.json           剥离规则
 * │  ├─ replace_rules.json         替换规则
 * │  ├─ delete_rules.json          删除规则
 * │  ├─ active_presets.json        当前生效预设
 * │  └─ presets/                   预设文件
 * ├─ machine/
 * │  └─ multiblock_registry.json   多方块注册开关
 * └─ client/
 *    ├─ ae_terminal_favorites.nbt  AE 终端收藏
 *    └─ pattern-generator-presets/ 样板生成器预设
 * </pre>
 *
 * <h2>旧版本迁移</h2>
 * <p>4.6.0 及以前的全部文件平铺在 {@code config/gt_shanhai/} 根目录。首次访问任一子目录时，
 * {@link #migrateLegacyLayout()} 会把旧根目录下的同名文件移动到新位置：新位置已存在则保留新的、丢弃旧的，
 * 不会覆盖玩家已在新结构里改过的内容。整个流程幂等，可重复执行。</p>
 */
public final class ShanhaiConfigPaths {

    /** 模组配置根目录名。这里刻意写字面量而不是引用 GTDishanhaiMod.MOD_ID——
     *  后者会触发 Forge 注册表静态初始化，纯 JVM 单元测试下直接抛 Not bootstrapped。 */
    public static final String ROOT_DIR_NAME = "gt_shanhai";

    /** 根目录下保持原位的子目录。 */
    public static final String CHANGELOG = "changelog";
    public static final String GUIDES = "guides";

    /** 分组子目录名。 */
    public static final String SHOP_DIR_NAME = "shop";
    public static final String SHOP_CURRENCY_DIR_NAME = "currency";
    public static final String RECIPE_DIR_NAME = "recipe";
    public static final String RECIPE_PRESETS_DIR_NAME = "presets";
    public static final String MACHINE_DIR_NAME = "machine";
    public static final String CLIENT_DIR_NAME = "client";
    public static final String CLIENT_PATTERN_PRESETS_DIR_NAME = "pattern-generator-presets";

    /** 旧版平铺文件名 → 新相对路径（相对模组配置根目录）。 */
    private static final List<LegacyFile> LEGACY_FILES = List.of(
            new LegacyFile("shop.json", "shop/shop.json"),
            new LegacyFile("shop_category_order.json", "shop/shop_category_order.json"),
            new LegacyFile("shop_client_ui.json", "shop/shop_client_ui.json"),
            new LegacyFile("shop_quest_links.json", "shop/shop_quest_links.json"),
            new LegacyFile("shop_stage_requirements.json", "shop/shop_stage_requirements.json"),
            new LegacyFile("exchanges.json", "shop/exchanges.json"),
            new LegacyFile("currency_rates.json", "shop/currency/currency_rates.json"),
            new LegacyFile("currency_stages.json", "shop/currency/currency_stages.json"),
            new LegacyFile("recipe_overrides.json", "recipe/recipe_overrides.json"),
            new LegacyFile("strip_rules.json", "recipe/strip_rules.json"),
            new LegacyFile("replace_rules.json", "recipe/replace_rules.json"),
            new LegacyFile("delete_rules.json", "recipe/delete_rules.json"),
            new LegacyFile("active_presets.json", "recipe/active_presets.json"),
            new LegacyFile("multiblock_registry.json", "machine/multiblock_registry.json"),
            new LegacyFile("ae_terminal_favorites.nbt", "client/ae_terminal_favorites.nbt"));

    /** 旧版平铺子目录 → 新相对路径。 */
    private static final List<LegacyFile> LEGACY_DIRS = List.of(
            new LegacyFile("presets", "recipe/presets"),
            new LegacyFile("pattern-generator-presets", "client/pattern-generator-presets"));

    /** 本类自己的 logger：引用 GTDishanhaiMod 会触发 Forge 注册表静态初始化，单元测试里直接炸。 */
    private static final Logger LOG = LogManager.getLogger(ROOT_DIR_NAME);

    private static volatile boolean migrated;

    private ShanhaiConfigPaths() {}

    // ====== 根路径 ======

    /** 模组配置根目录 {@code <CONFIGDIR>/gt_shanhai}。 */
    public static Path root() {
        return FMLPaths.CONFIGDIR.get().resolve(ROOT_DIR_NAME);
    }

    /** {@code <root>/changelog} */
    public static Path changelog() {
        return root().resolve(CHANGELOG);
    }

    /** {@code <root>/guides} */
    public static Path guides() {
        return root().resolve(GUIDES);
    }

    // ====== shop ======

    /** {@code <root>/shop} */
    public static Path shopDir() {
        return root().resolve(SHOP_DIR_NAME);
    }

    /** {@code <root>/shop/shop.json} */
    public static Path shop() {
        return shopDir().resolve("shop.json");
    }

    /** {@code <root>/shop/shop_category_order.json} */
    public static Path shopCategoryOrder() {
        return shopDir().resolve("shop_category_order.json");
    }

    /** {@code <root>/shop/shop_client_ui.json} */
    public static Path shopClientUi() {
        return shopDir().resolve("shop_client_ui.json");
    }

    /** {@code <root>/shop/shop_quest_links.json} */
    public static Path shopQuestLinks() {
        return shopDir().resolve("shop_quest_links.json");
    }

    /** {@code <root>/shop/shop_stage_requirements.json} */
    public static Path shopStageRequirements() {
        return shopDir().resolve("shop_stage_requirements.json");
    }

    /** {@code <root>/shop/exchanges.json} */
    public static Path shopExchanges() {
        return shopDir().resolve("exchanges.json");
    }

    /** {@code <root>/shop/currency} */
    public static Path shopCurrencyDir() {
        return shopDir().resolve(SHOP_CURRENCY_DIR_NAME);
    }

    /** {@code <root>/shop/currency/currency_rates.json} */
    public static Path currencyRates() {
        return shopCurrencyDir().resolve("currency_rates.json");
    }

    /** {@code <root>/shop/currency/currency_stages.json} */
    public static Path currencyStages() {
        return shopCurrencyDir().resolve("currency_stages.json");
    }

    // ====== recipe ======

    /** {@code <root>/recipe} */
    public static Path recipeDir() {
        return root().resolve(RECIPE_DIR_NAME);
    }

    /** {@code <root>/recipe/recipe_overrides.json} */
    public static Path recipeOverrides() {
        return recipeDir().resolve("recipe_overrides.json");
    }

    /** {@code <root>/recipe/strip_rules.json} */
    public static Path stripRules() {
        return recipeDir().resolve("strip_rules.json");
    }

    /** {@code <root>/recipe/replace_rules.json} */
    public static Path replaceRules() {
        return recipeDir().resolve("replace_rules.json");
    }

    /** {@code <root>/recipe/delete_rules.json} */
    public static Path deleteRules() {
        return recipeDir().resolve("delete_rules.json");
    }

    /** {@code <root>/recipe/active_presets.json} */
    public static Path activePresets() {
        return recipeDir().resolve("active_presets.json");
    }

    /** {@code <root>/recipe/presets} */
    public static Path recipePresetsDir() {
        return recipeDir().resolve(RECIPE_PRESETS_DIR_NAME);
    }

    // ====== machine ======

    /** {@code <root>/machine} */
    public static Path machineDir() {
        return root().resolve(MACHINE_DIR_NAME);
    }

    /** {@code <root>/machine/multiblock_registry.json} */
    public static Path multiblockRegistry() {
        return machineDir().resolve("multiblock_registry.json");
    }

    // ====== client ======

    /** {@code <root>/client} */
    public static Path clientDir() {
        return root().resolve(CLIENT_DIR_NAME);
    }

    /** {@code <root>/client/ae_terminal_favorites.nbt} */
    public static Path aeTerminalFavorites() {
        return clientDir().resolve("ae_terminal_favorites.nbt");
    }

    /** {@code <root>/client/pattern-generator-presets} */
    public static Path patternGeneratorPresetsDir() {
        return clientDir().resolve(CLIENT_PATTERN_PRESETS_DIR_NAME);
    }

    // ====== 目录创建 ======

    /** 确保父目录存在，返回原 path。写文件前调用。 */
    public static Path ensureParent(Path path) {
        migrateLegacyLayout();
        Path parent = path.getParent();
        if (parent != null) {
            try {
                Files.createDirectories(parent);
            } catch (IOException e) {
                LOG.warn("[Config] 创建目录失败 {}: {}", parent, e.getMessage());
            }
        }
        return path;
    }

    /** 确保目录存在，返回原 path。 */
    public static Path ensureDir(Path dir) {
        migrateLegacyLayout();
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            LOG.warn("[Config] 创建目录失败 {}: {}", dir, e.getMessage());
        }
        return dir;
    }

    // ====== 旧版布局迁移 ======

    /**
     * 把 4.6.0 及以前的平铺布局迁到分组布局。幂等，可在任意时机重复调用。
     *
     * <p>策略：旧文件存在且新位置不存在 → 移动；两边都存在 → 保留新文件、把旧文件改名为
     * {@code *.legacy} 留档（不删玩家数据，也不让它继续和新文件并存造成误读）。</p>
     */
    public static void migrateLegacyLayout() {
        if (migrated) return;
        synchronized (ShanhaiConfigPaths.class) {
            if (migrated) return;
            migrated = true;
            try {
                Path root = root();
                if (!Files.isDirectory(root)) return;
                boolean changed = false;
                for (LegacyFile entry : LEGACY_FILES) {
                    changed |= migrateEntry(root, entry);
                }
                for (LegacyFile entry : LEGACY_DIRS) {
                    changed |= migrateEntry(root, entry);
                }
                if (changed) {
                    LOG.info("[Config] 已把旧版平铺配置迁移到 config/{}/ 分组结构", ROOT_DIR_NAME);
                }
            } catch (Exception e) {
                LOG.warn("[Config] 旧版配置迁移失败，将继续使用原路径: {}", e.getMessage());
            }
        }
    }

    private static boolean migrateEntry(Path root, LegacyFile entry) {
        Path source = root.resolve(entry.legacyName());
        Path target = root.resolve(entry.relativePath());
        if (!Files.exists(source)) return false;
        try {
            if (Files.exists(target)) {
                Path archived = source.resolveSibling(source.getFileName() + ".legacy");
                if (Files.exists(archived)) {
                    Files.delete(source);
                } else {
                    Files.move(source, archived, StandardCopyOption.REPLACE_EXISTING);
                }
                LOG.warn("[Config] {} 与新版 {} 并存，旧文件已改名为 {}",
                        source.getFileName(), entry.relativePath(), archived.getFileName());
            } else {
                Files.createDirectories(target.getParent());
                try {
                    Files.move(source, target);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
                    Files.deleteIfExists(source);
                }
            }
            return true;
        } catch (IOException e) {
            LOG.warn("[Config] 迁移 {} 失败: {}", entry.legacyName(), e.getMessage());
            return false;
        }
    }

    private record LegacyFile(String legacyName, String relativePath) {}
}
