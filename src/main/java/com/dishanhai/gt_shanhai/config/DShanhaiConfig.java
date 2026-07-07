package com.dishanhai.gt_shanhai.config;

import net.minecraftforge.common.ForgeConfigSpec;

import java.util.List;

public final class DShanhaiConfig {

    private DShanhaiConfig() {}

    public static final ForgeConfigSpec COMMON_SPEC;
    public static final ConfigValues COMMON = new ConfigValues();

    static {
        var builder = new ForgeConfigSpec.Builder();
        COMMON.init(builder);
        COMMON_SPEC = builder.build();
    }

    public static class ConfigValues {
        public enum VirtualProviderMode {
            AE_TARGET_CHECK,
            SUPPLY_MACHINE
        }

        public enum RecipeTypePatternSwitchMode {
            PROGRAMMABLE_HATCH_REQUIRED,
            VIRTUAL_ACTIVE_TYPE
        }

        /** 特大号标签过滤总线 — UI 每页显示槽位数 */
        public ForgeConfigSpec.IntValue tagBusSlotsPerPage;
        /** 特大号标签过滤总线 — 最大页数 */
        public ForgeConfigSpec.IntValue tagBusMaxPages;
        /** 特大号标签过滤总线 — 最大拉取种类数 */
        public ForgeConfigSpec.IntValue tagBusSlotsCount;
        /** 山海维护仓 — 是否启用绕过功能 */
        public ForgeConfigSpec.BooleanValue maintenanceHatchEnabled;
        /** 并行覆写 — 是否启用全面覆写模式（默认精准覆写，仅已知类） */
        public ForgeConfigSpec.BooleanValue parallelForceMode;

        /** 枢纽行为 — 是否启用枢纽并行/线程值作为产出倍率（默认禁用=普通并行方案） */
        public ForgeConfigSpec.BooleanValue hubOutputMultiplier;

        /** 超级并行 — 配方倍率补偿系数 */
        public ForgeConfigSpec.DoubleValue superParallelMultiplier;

        /** 大明科技 — 全配方模式（true=每大类聚合搜索全部子类型，false=仅搜索主类型） */
        public ForgeConfigSpec.BooleanValue nineIndustrialShuffle;
        /** 模块机器 — 是否允许脱离主机独立运行 */
        public ForgeConfigSpec.BooleanValue modulesWorkWithoutHost;
        /** 递归反演阵列 — 是否破除已连接子模块内部运行限制 */
        public ForgeConfigSpec.BooleanValue recursiveReverseArrayBypassModuleRestrictions;
        /** ME 磁盘仓室 — 槽位数 */
        public ForgeConfigSpec.IntValue meDiskHatchSlots;
        /** 虚拟物品提供器 — AE 下单校验模式 */
        public ForgeConfigSpec.EnumValue<VirtualProviderMode> virtualProviderMode;
        /** 虚拟物品提供器 — 自动包裹排除物品 ID */
        public ForgeConfigSpec.ConfigValue<List<? extends String>> virtualProviderAutoWrapExclusions;
        /** 配方类型样板总成 — 主机配方类型联动模式 */
        public ForgeConfigSpec.EnumValue<RecipeTypePatternSwitchMode> recipeTypePatternSwitchMode;
        /** 配方类型样板总成 — UI 每行样板槽位数 */
        public ForgeConfigSpec.IntValue recipeTypePatternsPerRow;
        /** 配方类型样板总成 — UI 每页行数 */
        public ForgeConfigSpec.IntValue recipeTypeRowsPerPage;
        /** 配方类型样板总成 — 最大页数 */
        public ForgeConfigSpec.IntValue recipeTypeMaxPages;

        void init(ForgeConfigSpec.Builder builder) {
            builder.push("tag_filter_bus");
            tagBusSlotsPerPage = builder
                    .comment("特大号标签过滤总线UI每页显示的槽位数")
                    .defineInRange("slotsPerPage", 16, 1, 256);
            tagBusMaxPages = builder
                    .comment("特大号标签过滤总线最大页数")
                    .defineInRange("maxPages", 50, 1, 1000);
            tagBusSlotsCount = builder
                    .comment("特大号标签过滤总线最大拉取种类数（配置槽位总数）")
                    .defineInRange("slotsCount", 32, 1, 256);
            builder.pop();

            builder.push("maintenance_hatch");
            maintenanceHatchEnabled = builder
                    .comment("山海维护仓是否生效（设为 false 可禁用绕过功能，但维护仓仍可放置）")
                    .define("enabled", true);
            builder.pop();

            builder.push("parallel_override");
            parallelForceMode = builder
                    .comment("并行覆写模式：false=精准覆写（仅对已知机器类生效，默认），true=全面覆写（尝试覆写所有机器）",
                             "开启全面覆写后，可突破纳米核心(gtceu:nano_core)等机器内部的8192并行限制")
                    .define("forceMode", false);
            builder.pop();

            builder.push("hub_parallel_behavior");
            hubOutputMultiplier = builder
                    .comment("枢纽并行/线程值是否作为产出倍率（默认禁用=普通并行方案）",
                             "false=禁用枢纽并行/线程倍率产出，枢纽仅提供电压绕过和维护功能",
                             "true=启用枢纽并行/线程值作为产出倍率（原行为）")
                    .define("outputMultiplier", false);
            builder.pop();

            builder.push("super_parallel");
            superParallelMultiplier = builder
                    .comment("寰宇并行超限器——配方倍率补偿系数（默认 1.0）",
                             "1.0 = 每个配方独立 Long.MAX_VALUE 并行",
                             ">1.0 = 在 getMaxParallel 结果上乘此系数（超越枢纽/引力波倍率）",
                             "建议范围 1.0 ~ 1.0E12")
                    .defineInRange("multiplier", 1.0D, 1.0D, 1.0E15D);
            builder.pop();

            builder.push("nine_industrial");
            nineIndustrialShuffle = builder
                    .comment("大明科技全配方模式",
                             "true=每大类聚合搜索该大类下全部子配方类型",
                             "false=仅搜索当前大类的主配方类型（性能更好）")
                    .define("fullMode", true);
            builder.pop();

            builder.push("module_independence");
            modulesWorkWithoutHost = builder
                    .comment("模块机器是否可脱离主机独立运行（默认禁用）",
                             "false=模块必须有主机才能运行",
                             "true=模块脱离主机后可独立运行配方")
                    .define("workWithoutHost", false);
            builder.pop();

            builder.push("recursive_reverse_array");
            recursiveReverseArrayBypassModuleRestrictions = builder
                    .comment("递归反演阵列是否破除已连接子模块的内部限制（默认禁用）",
                             "false=保持 GTLAdditions 原版逻辑：催化剂、聚焦材料、温度窗口、模块运行状态全部正常检查",
                             "true=仅当子模块已成型并连接到递归反演阵列时，将其视作满状态参与递归反演增益",
                             "该配置不会让脱离阵列独立运行的模块参与递归反演增益")
                    .define("bypassModuleRestrictions", false);
            builder.pop();

            builder.push("me_disk_hatch");
            meDiskHatchSlots = builder
                    .comment("ME 磁盘仓室的槽位数（默认 108）",
                             "修改后需重新放置仓室生效")
                    .defineInRange("slots", 108, 1, 256);
            builder.pop();

            builder.push("virtual_item_provider");
            virtualProviderMode = builder
                    .comment("虚拟物品提供器模式",
                             "AE_TARGET_CHECK = AE 下单检查网络中的真实目标物，执行时把 provider 解成目标物镜像；不依赖虚拟物品供应机",
                             "SUPPLY_MACHINE = AE 下单检查同网络虚拟物品供应机槽内是否存在目标物；供应机不向 AE 普通库存暴露 provider key")
                    .defineEnum("mode", VirtualProviderMode.AE_TARGET_CHECK);
            virtualProviderAutoWrapExclusions = builder
                    .comment("自动写样板时不包裹为虚拟物品提供器的物品 ID 列表",
                             "例：gtceu:programmed_circuit。被排除物品会按原物品直接写入样板，保留自身 NBT")
                    .defineList("autoWrapExclusions", List.of("gtceu:programmed_circuit"), value -> value instanceof String);
            builder.pop();

            builder.push("recipe_type_pattern_buffer");
            recipeTypePatternSwitchMode = builder
                    .comment("配方类型识别 ME 样板总成联动主机配方类型的模式",
                             "PROGRAMMABLE_HATCH_REQUIRED = 必须有山海可编程仓随主机成型，由可编程仓实际切换主机配方类型；无可编程仓时不运行虚拟类型样板",
                             "VIRTUAL_ACTIVE_TYPE = 不要求可编程仓；样板下单时按虚拟目标类型直接切主机 activeRecipeType，但不写入可编程仓选择状态")
                    .defineEnum("switchMode", RecipeTypePatternSwitchMode.VIRTUAL_ACTIVE_TYPE);
            recipeTypePatternsPerRow = builder
                    .comment("星律样板总成 UI 每行样板槽位数（默认 9）",
                             "修改后需重新放置总成生效")
                    .defineInRange("patternsPerRow", 9, 1, 16);
            recipeTypeRowsPerPage = builder
                    .comment("星律样板总成 UI 每页行数（默认 6）",
                             "修改后需重新放置总成生效")
                    .defineInRange("rowsPerPage", 6, 1, 16);
            recipeTypeMaxPages = builder
                    .comment("星律样板总成最大页数（默认 3）",
                             "总槽位 = patternsPerRow × rowsPerPage × maxPages",
                             "修改后需重新放置总成生效")
                    .defineInRange("maxPages", 3, 1, 64);
            builder.pop();
        }
    }
}
