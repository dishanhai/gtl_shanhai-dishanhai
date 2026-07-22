package com.dishanhai.gt_shanhai.client.config;

import com.dishanhai.gt_shanhai.config.DShanhaiConfig;
import com.dishanhai.gt_shanhai.config.DShanhaiConfig.ConfigValues.RecipeTypePatternSwitchMode;
import com.dishanhai.gt_shanhai.config.DShanhaiConfig.ConfigValues.VirtualProviderMode;

import me.shedaniel.clothconfig2.api.ConfigBuilder;
import me.shedaniel.clothconfig2.api.ConfigCategory;
import me.shedaniel.clothconfig2.api.ConfigEntryBuilder;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 山海模组配置界面（客户端，基于 cloth-config）。
 *
 * <p>模组列表「配置」按钮走 Forge 的 {@code ConfigScreenHandler} 扩展点，
 * 由 {@code ClientInit} 注册工厂指向本类 {@link #build(Screen)}。
 * 界面把 {@link DShanhaiConfig#COMMON} 的各 {@code ForgeConfigSpec.*Value}
 * 映射成 cloth 的可视条目，保存时通过每项 {@code setSaveConsumer} 调 {@code .set()} 写回，
 * ForgeConfigSpec 自动持久化到 {@code config/gt_shanhai/gt_shanhai-common.toml}。</p>
 *
 * <p>本类只在客户端加载（cloth-config 是客户端 GUI 库），服务端不触及。</p>
 */
public final class DShanhaiConfigScreen {

    private DShanhaiConfigScreen() {}

    public static Screen build(Screen parent) {
        var cfg = DShanhaiConfig.COMMON;
        ConfigBuilder builder = ConfigBuilder.create()
                .setParentScreen(parent)
                .setTitle(Component.literal("山海私货 配置"));
        ConfigEntryBuilder e = builder.entryBuilder();

        // ===== 特大号标签过滤总线 =====
        ConfigCategory tagBus = builder.getOrCreateCategory(Component.literal("标签过滤总线"));
        tagBus.addEntry(e.startIntField(Component.literal("每页槽位数"), cfg.tagBusSlotsPerPage.get())
                .setDefaultValue(16).setMin(1).setMax(256)
                .setTooltip(tip("UI 每页显示的槽位数"))
                .setSaveConsumer(cfg.tagBusSlotsPerPage::set).build());
        tagBus.addEntry(e.startIntField(Component.literal("最大页数"), cfg.tagBusMaxPages.get())
                .setDefaultValue(50).setMin(1).setMax(1000)
                .setTooltip(tip("最大页数"))
                .setSaveConsumer(cfg.tagBusMaxPages::set).build());
        tagBus.addEntry(e.startIntField(Component.literal("最大拉取种类数"), cfg.tagBusSlotsCount.get())
                .setDefaultValue(32).setMin(1).setMax(256)
                .setTooltip(tip("最大拉取种类数（配置槽位总数）"))
                .setSaveConsumer(cfg.tagBusSlotsCount::set).build());

        // ===== 维护仓 =====
        ConfigCategory maint = builder.getOrCreateCategory(Component.literal("维护仓"));
        maint.addEntry(e.startBooleanToggle(Component.literal("启用维护仓绕过"), cfg.maintenanceHatchEnabled.get())
                .setDefaultValue(true)
                .setTooltip(tip("设为 false 可禁用绕过功能，但维护仓仍可放置"))
                .setSaveConsumer(cfg.maintenanceHatchEnabled::set).build());

        // ===== 并行相关 =====
        ConfigCategory parallel = builder.getOrCreateCategory(Component.literal("并行"));
        parallel.addEntry(e.startBooleanToggle(Component.literal("并行全面覆写"), cfg.parallelForceMode.get())
                .setDefaultValue(false)
                .setTooltip(tip("false=精准覆写（仅已知机器类），true=全面覆写（尝试覆写所有机器）",
                        "开启后可突破纳米核心等机器内部的 8192 并行限制"))
                .setSaveConsumer(cfg.parallelForceMode::set).build());
        parallel.addEntry(e.startBooleanToggle(Component.literal("枢纽并行作为产出倍率"), cfg.hubOutputMultiplier.get())
                .setDefaultValue(false)
                .setTooltip(tip("false=禁用枢纽并行/线程倍率产出，枢纽仅提供电压绕过和维护",
                        "true=启用枢纽并行/线程值作为产出倍率（原行为）"))
                .setSaveConsumer(cfg.hubOutputMultiplier::set).build());
        parallel.addEntry(e.startDoubleField(Component.literal("超级并行倍率补偿系数"), cfg.superParallelMultiplier.get())
                .setDefaultValue(1.0D).setMin(1.0D).setMax(1.0E15D)
                .setTooltip(tip("寰宇并行超限器配方倍率补偿系数（默认 1.0）",
                        "1.0=每配方独立 Long.MAX 并行；>1.0=在结果上乘此系数",
                        "建议范围 1.0 ~ 1.0E12"))
                .setSaveConsumer(cfg.superParallelMultiplier::set).build());

        // ===== 机器行为 =====
        ConfigCategory machine = builder.getOrCreateCategory(Component.literal("机器行为"));
        machine.addEntry(e.startBooleanToggle(Component.literal("大明科技全配方模式"), cfg.nineIndustrialShuffle.get())
                .setDefaultValue(true)
                .setTooltip(tip("true=每大类聚合搜索全部子配方类型",
                        "false=仅搜索主配方类型（性能更好）"))
                .setSaveConsumer(cfg.nineIndustrialShuffle::set).build());
        machine.addEntry(e.startBooleanToggle(Component.literal("模块脱离主机独立运行"), cfg.modulesWorkWithoutHost.get())
                .setDefaultValue(false)
                .setTooltip(tip("false=模块必须有主机才能运行",
                        "true=模块脱离主机后可独立运行配方"))
                .setSaveConsumer(cfg.modulesWorkWithoutHost::set).build());
        machine.addEntry(e.startBooleanToggle(Component.literal("递归反演阵列破除子模块限制"), cfg.recursiveReverseArrayBypassModuleRestrictions.get())
                .setDefaultValue(false)
                .setTooltip(tip("false=保持原逻辑：催化剂/聚焦材料/温度/运行状态全部正常检查",
                        "true=子模块成型并连接阵列时视作满状态参与递归反演增益"))
                .setSaveConsumer(cfg.recursiveReverseArrayBypassModuleRestrictions::set).build());
        machine.addEntry(e.startIntField(Component.literal("ME 磁盘仓室槽位数"), cfg.meDiskHatchSlots.get())
                .setDefaultValue(108).setMin(1).setMax(256)
                .setTooltip(tip("默认 108，修改后需重新放置仓室生效"))
                .setSaveConsumer(cfg.meDiskHatchSlots::set).build());
        machine.addEntry(e.startBooleanToggle(Component.literal("AE 库存增量刷新缓存"), cfg.aeStorageDeltaCacheEnabled.get())
                .setDefaultValue(true)
                .setTooltip(tip("true=仅当 NetworkStorage.insert/extract 记录到变化时才全量重扫库存（省一个 tick 的重扫开销）",
                        "false=每 tick 强制全量重扫，保证取出磁盘、无限盘挂载等不走 insert/extract 的变化也及时刷新",
                        "⚠ 无限盘/ExtendedAE 库存闪烁、下单误报“材料不足”时请关闭此项",
                        "改动后需重进存档生效"))
                .setSaveConsumer(cfg.aeStorageDeltaCacheEnabled::set).build());

        // ===== 虚拟物品提供器 =====
        ConfigCategory vip = builder.getOrCreateCategory(Component.literal("虚拟物品提供器"));
        vip.addEntry(e.startEnumSelector(Component.literal("下单校验模式"), VirtualProviderMode.class, cfg.virtualProviderMode.get())
                .setDefaultValue(VirtualProviderMode.AE_TARGET_CHECK)
                .setTooltip(tip("AE_TARGET_CHECK=检查网络真实目标物并解成镜像；不依赖供应机",
                        "SUPPLY_MACHINE=检查同网络供应机槽内是否存在目标物"))
                .setSaveConsumer(cfg.virtualProviderMode::set).build());
        vip.addEntry(e.startStrList(Component.literal("自动包裹排除物品 ID"), new ArrayList<>(cfg.virtualProviderAutoWrapExclusions.get()))
                .setDefaultValue(List.of("gtceu:programmed_circuit"))
                .setTooltip(tip("这些物品写样板时不包裹为虚拟提供器，按原物品直接写入保留 NBT"))
                .setSaveConsumer(list -> cfg.virtualProviderAutoWrapExclusions.set(list)).build());

        // ===== 配方类型样板总成 =====
        ConfigCategory pattern = builder.getOrCreateCategory(Component.literal("配方类型样板总成"));
        pattern.addEntry(e.startEnumSelector(Component.literal("主机配方类型联动模式"), RecipeTypePatternSwitchMode.class, cfg.recipeTypePatternSwitchMode.get())
                .setDefaultValue(RecipeTypePatternSwitchMode.VIRTUAL_ACTIVE_TYPE)
                .setTooltip(tip("PROGRAMMABLE_HATCH_REQUIRED=需可编程仓随主机成型才切类型",
                        "VIRTUAL_ACTIVE_TYPE=不要求可编程仓，但默认只执行宿主支持的类型"))
                .setSaveConsumer(cfg.recipeTypePatternSwitchMode::set).build());
        pattern.addEntry(e.startBooleanToggle(Component.literal("允许执行宿主不支持的虚拟配方类型"),
                        cfg.recipeTypePatternAllowUnsupportedHostRecipeTypes.get())
                .setDefaultValue(false)
                .setTooltip(tip("默认关闭：样板类型必须存在于主机当前配方类型集合",
                        "开启后恢复旧行为，允许完整 GTRecipe 绕过主机配方类型限制直接执行"))
                .setSaveConsumer(cfg.recipeTypePatternAllowUnsupportedHostRecipeTypes::set).build());
        pattern.addEntry(e.startIntField(Component.literal("每行样板槽位数"), cfg.recipeTypePatternsPerRow.get())
                .setDefaultValue(9).setMin(1).setMax(16)
                .setTooltip(tip("默认 9，修改后需重新放置总成生效"))
                .setSaveConsumer(cfg.recipeTypePatternsPerRow::set).build());
        pattern.addEntry(e.startIntField(Component.literal("每页行数"), cfg.recipeTypeRowsPerPage.get())
                .setDefaultValue(6).setMin(1).setMax(16)
                .setTooltip(tip("默认 6，修改后需重新放置总成生效"))
                .setSaveConsumer(cfg.recipeTypeRowsPerPage::set).build());
        pattern.addEntry(e.startIntField(Component.literal("最大页数"), cfg.recipeTypeMaxPages.get())
                .setDefaultValue(3).setMin(1).setMax(64)
                .setTooltip(tip("总槽位 = 每行槽位 × 每页行数 × 最大页数，修改后需重新放置总成生效"))
                .setSaveConsumer(cfg.recipeTypeMaxPages::set).build());

        // ===== 山海商店 =====
        ConfigCategory shop = builder.getOrCreateCategory(Component.literal("商店"));
        shop.addEntry(e.startLongField(Component.literal("SDA 打包阈值"), cfg.shopSdaPackThreshold.get())
                .setDefaultValue(1000L).setMin(1L).setMax(Long.MAX_VALUE)
                .setTooltip(tip("非 AE 模式下，单次购买货物总量 ≥ 此值时打包成超级磁盘阵列赠送（而非塞背包）",
                        "默认 1000；设 1 则任何购买都给 SDA"))
                .setSaveConsumer(cfg.shopSdaPackThreshold::set).build());
        shop.addEntry(e.startBooleanToggle(Component.literal("AE 模式禁止注入"), cfg.shopAeDeliverDisabled.get())
                .setDefaultValue(false)
                .setTooltip(tip("开启后 AE 模式只用来拉取材料付款/检索库存，购买/兑换得到的物品一律正常交付（进背包/按 SDA 打包阈值打包），不再注入 AE 网络"))
                .setSaveConsumer(cfg.shopAeDeliverDisabled::set).build());
        shop.addEntry(e.startBooleanToggle(Component.literal("将SDA直接注入磁盘仓室"), cfg.shopSdaDirectDiskHatchInject.get())
                .setDefaultValue(true)
                .setTooltip(tip("开启后有内容 SDA 会优先放入同网 ME 磁盘仓室直接挂载",
                        "磁盘仓室必须与 FTBQ AE提交器或商店终端在同一 AE 网络；空 SDA 商品不挂载"))
                .setSaveConsumer(cfg.shopSdaDirectDiskHatchInject::set).build());

        return builder.build();
    }

    /** 把多行中文注释转成 cloth tooltip 需要的 Component 数组。 */
    private static Component[] tip(String... lines) {
        Component[] arr = new Component[lines.length];
        for (int i = 0; i < lines.length; i++) {
            arr[i] = Component.literal(lines[i]);
        }
        return arr;
    }
}
