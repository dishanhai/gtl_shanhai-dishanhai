package com.dishanhai.gt_shanhai.client.config;

import com.dishanhai.gt_shanhai.config.DShanhaiConfig;
import com.dishanhai.gt_shanhai.config.DShanhaiConfig.ConfigValues.RecipeTypePatternSwitchMode;
import com.dishanhai.gt_shanhai.config.DShanhaiConfig.ConfigValues.SphereStyleOverride;
import com.dishanhai.gt_shanhai.config.DShanhaiConfig.ConfigValues.VirtualProviderMode;
import com.dishanhai.gt_shanhai.config.DShanhaiConfig.ConfigValues.JeiBookmarkMode;

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
        machine.addEntry(e.startBooleanToggle(Component.literal("原初模块旧式无限制模式"),
                        cfg.primordialModuleUnrestrictedMode.get())
                .setDefaultValue(false)
                .setTooltip(tip("默认关闭：检查超净间、重力、维度、线圈温度和恒星热力容器等级",
                        "研究/数据访问条件不作为原初模块的额外挂载限制",
                        "开启后恢复旧行为：除重力外不检查上述额外挂载限制"))
                .setSaveConsumer(cfg.primordialModuleUnrestrictedMode::set).build());
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
        machine.addEntry(e.startIntField(Component.literal("AE 库存强制重扫间隔"), cfg.aeStorageForceRescanTicks.get())
                .setDefaultValue(40).setMin(10).setMax(1200)
                .setTooltip(tip("仅在增量缓存开启时生效，单位 tick，默认 40（2 秒）",
                        "存储总线背后的箱子/机器被管道、漏斗或玩家直接改动时，不走 insert/extract",
                        "此安全网保证这类变化最迟 N tick 内被看见"))
                .setSaveConsumer(cfg.aeStorageForceRescanTicks::set).build());
        machine.addEntry(e.startEnumSelector(Component.literal("原始终焉引擎球体渲染风格"),
                        SphereStyleOverride.class, cfg.primordialSphereStyle.get())
                .setDefaultValue(SphereStyleOverride.FOLLOW_MACHINE)
                .setTooltip(tip("FOLLOW_MACHINE=跟随每台机器 GUI 侧栏的切换按钮（默认）",
                        "UNIVERSE=强制全部渲染成鸿蒙微型宇宙",
                        "NEUTRON_STAR=强制全部渲染成中子星",
                        "纯客户端显示偏好：只影响你自己看到的画面，不改写机器状态，也不影响其他玩家",
                        "保存后即时生效，无需重进存档"))
                .setSaveConsumer(cfg.primordialSphereStyle::set).build());

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
        vip.addEntry(e.startBooleanToggle(Component.literal("缺失不消耗输入时强制包裹"),
                        cfg.virtualProviderForceWrapOmittedNonConsumables.get())
                .setDefaultValue(true)
                .setTooltip(tip("开启：反查配方缺失不消耗输入时，自动补回虚拟供应器/流体标记（默认）",
                        "关闭：尊重玩家删除不消耗输入的操作，不主动补回虚拟供应器/流体标记"))
                .setSaveConsumer(cfg.virtualProviderForceWrapOmittedNonConsumables::set).build());

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
                        "⚠ 作弊/破限兼容：开启后恢复旧行为，允许完整 GTRecipe 绕过主机配方类型限制直接执行"))
                .setSaveConsumer(cfg.recipeTypePatternAllowUnsupportedHostRecipeTypes::set).build());
        pattern.addEntry(e.startBooleanToggle(Component.literal("作弊：允许星律虚拟执行链破限"),
                        cfg.recipeTypePatternAllowCheatVirtualExecution.get())
                .setDefaultValue(false)
                .setTooltip(tip("默认关闭：AE 已下单槽位正常执行，未下单槽位不会主动预填原料",
                        "默认关闭：遵守宿主配方类型、并行和机器条件",
                        "⚠ 作弊/破限兼容：开启后允许未下单首配预填，并跳过宿主 beforeWorking/part 检查",
                        "可能绕过内部小机器槽、模式/维护条件；普通耗材仍按宿主并行夹限"))
                .setSaveConsumer(cfg.recipeTypePatternAllowCheatVirtualExecution::set).build());
        pattern.addEntry(e.startStrList(Component.literal("星律共享搜索集"),
                        new ArrayList<>(cfg.recipeTypeSharedSearchSets.get()))
                .setDefaultValue(List.of("gtceu:chemical_reactor,gtceu:large_chemical_reactor"))
                .setTooltip(tip("每条 = 一组逗号分隔的配方类型ID（带命名空间），同组类型互相视为可共同搜索/执行",
                        "例：gtceu:chemical_reactor,gtceu:large_chemical_reactor",
                        "化反样板可在大型化反宿主执行、反之亦然；按组精确授权，不同于上面的全放开开关",
                        "空列表 = 保持严格隔离"))
                .setSaveConsumer(list -> cfg.recipeTypeSharedSearchSets.set(list)).build());
        pattern.addEntry(e.startIntField(Component.literal("卡死告警延迟（秒）"), cfg.recipeTypePatternStuckWarningSeconds.get())
                .setDefaultValue(10).setMin(1).setMax(3600)
                .setTooltip(tip("样板槽收到 AE 下单原料后，延迟结束时原料仍完整留在槽内，就向附近玩家与下单玩家广播",
                        "默认 10 秒，避开主机低并行刚开始吃料时的误报"))
                .setSaveConsumer(cfg.recipeTypePatternStuckWarningSeconds::set).build());
        pattern.addEntry(e.startIntField(Component.literal("每行样板槽位数"), cfg.recipeTypePatternsPerRow.get())
                .setDefaultValue(10).setMin(1).setMax(16)
                .setTooltip(tip("修改后重启生效：已放置的总成在下次区块加载时采用新配置",
                        "调小会裁剪槽位，被裁槽位里的样板会掉在机器位置，内部缓冲料无法保留，改小前请先清空高位槽"))
                .setSaveConsumer(cfg.recipeTypePatternsPerRow::set).build());
        pattern.addEntry(e.startIntField(Component.literal("每页行数"), cfg.recipeTypeRowsPerPage.get())
                .setDefaultValue(10).setMin(1).setMax(16)
                .setTooltip(tip("生效方式与裁剪风险同每行样板槽位数"))
                .setSaveConsumer(cfg.recipeTypeRowsPerPage::set).build());
        pattern.addEntry(e.startIntField(Component.literal("最大页数"), cfg.recipeTypeMaxPages.get())
                .setDefaultValue(10).setMin(1).setMax(64)
                .setTooltip(tip("总槽位 = 每行槽位 × 每页行数 × 最大页数",
                        "生效方式与裁剪风险同每行样板槽位数"))
                .setSaveConsumer(cfg.recipeTypeMaxPages::set).build());
        pattern.addEntry(e.startLongField(Component.literal("虚拟供料单次并行上限"), cfg.patternVirtualSupplyBatchParallel.get())
                .setDefaultValue(65536L).setMin(1L).setMax(Long.MAX_VALUE)
                .setTooltip(tip("首配路径单个样板槽位单次从无线 ME 网络真实提取的目标并行批量上限",
                        "实际并行取此值与网络真实库存的较小值，不会超过网络库存",
                        "不影响配方类型选择集本身的机器最大并行上限"))
                .setSaveConsumer(cfg.patternVirtualSupplyBatchParallel::set).build());

        // ===== 高级样板包装箱 =====
        ConfigCategory patternBox = builder.getOrCreateCategory(Component.literal("高级样板包装箱"));
        patternBox.addEntry(e.startIntField(Component.literal("每行样板槽位数"), cfg.advancedPatternBoxPatternsPerRow.get())
                .setDefaultValue(9).setMin(1).setMax(16)
                .setTooltip(tip("总容量 = 每行槽位 × 每页行数 × 最大页数，默认 9×6×4 = 216"))
                .setSaveConsumer(cfg.advancedPatternBoxPatternsPerRow::set).build());
        patternBox.addEntry(e.startIntField(Component.literal("每页行数"), cfg.advancedPatternBoxRowsPerPage.get())
                .setDefaultValue(6).setMin(1).setMax(8)
                .setTooltip(tip("调大会撑高 GUI，小分辨率或大 GUI 缩放下可能超出屏幕"))
                .setSaveConsumer(cfg.advancedPatternBoxRowsPerPage::set).build());
        patternBox.addEntry(e.startIntField(Component.literal("最大页数"), cfg.advancedPatternBoxMaxPages.get())
                .setDefaultValue(4).setMin(1).setMax(64)
                .setTooltip(tip("调大后下次打开包装箱即按新容量扩容",
                        "调小不会丢样板：高位槽有样板时实际槽位保持原尺寸，清空后才缩回"))
                .setSaveConsumer(cfg.advancedPatternBoxMaxPages::set).build());

        // ===== 样板总成工具箱 =====
        ConfigCategory toolkit = builder.getOrCreateCategory(Component.literal("样板总成工具箱"));
        toolkit.addEntry(e.startIntField(Component.literal("剪贴板预览上限"), cfg.patternBufferToolkitPreviewLimit.get())
                .setDefaultValue(270).setMin(9).setMax(2048)
                .setTooltip(tip("面板最多渲染的样板数，默认 270 = 9 列 × 30 行，面板内滚动查看",
                        "只影响预览，不限制剪贴板实际容量：超出部分照常复制/剪切/套用，只是不在面板里画出来",
                        "每个预览格都要同步给客户端，调太大会卡顿甚至撑爆数据包"))
                .setSaveConsumer(cfg.patternBufferToolkitPreviewLimit::set).build());

        // ===== JEI =====
        ConfigCategory jei = builder.getOrCreateCategory(Component.literal("JEI"));
        jei.addEntry(e.startEnumSelector(Component.literal("配方侧边收藏模式"),
                        JeiBookmarkMode.class, cfg.jeiBookmarkMode.get())
                .setDefaultValue(JeiBookmarkMode.MISSING_ITEMS)
                .setTooltip(tip("MISSING_ITEMS=收藏当前配方中 AE 网络存储低于单次需求量的输入物品或流体",
                        "NO_RECIPE_ITEMS=收藏当前配方中 AE 网络没有对应样板合成流程的输入物品或流体",
                        "COMBINED=仅在无 AE 主产物样板且数量不足时收藏；数量极高时视为足够",
                        "保存后下一次渲染 tooltip 即反映新模式"))
                .setSaveConsumer(cfg.jeiBookmarkMode::set).build());

        // ===== 山海商店 =====
        ConfigCategory shop = builder.getOrCreateCategory(Component.literal("商店"));
        shop.addEntry(e.startLongField(Component.literal("SDA 打包阈值"), cfg.shopSdaPackThreshold.get())
                .setDefaultValue(1000L).setMin(1L).setMax(Long.MAX_VALUE)
                .setTooltip(tip("非 AE 模式下，单次购买货物总量 ≥ 此值时打包成超级磁盘阵列赠送（而非塞背包）",
                        "默认 1000；设 1 则任何购买都给 SDA"))
                .setSaveConsumer(cfg.shopSdaPackThreshold::set).build());
        shop.addEntry(e.startLongField(Component.literal("奖励抽取次数上限"), cfg.shopRewardRollCap.get())
                .setDefaultValue(1_000_000L).setMin(1L).setMax(Long.MAX_VALUE)
                .setTooltip(tip("奖励表模式单次购买最多独立随机抽取的次数，超出部分留到下次购买",
                        "每次抽取都在服务端主线程同步跑完，调太大可能卡死甚至触发看门狗崩服",
                        "FTBQ 模式实际仍会夹到 Integer.MAX_VALUE"))
                .setSaveConsumer(cfg.shopRewardRollCap::set).build());
        shop.addEntry(e.startBooleanToggle(Component.literal("AE 模式禁止注入"), cfg.shopAeDeliverDisabled.get())
                .setDefaultValue(false)
                .setTooltip(tip("开启后 AE 模式只用来拉取材料付款/检索库存，购买/兑换得到的物品一律正常交付（进背包/按 SDA 打包阈值打包），不再注入 AE 网络"))
                .setSaveConsumer(cfg.shopAeDeliverDisabled::set).build());
        shop.addEntry(e.startBooleanToggle(Component.literal("将SDA直接注入磁盘仓室"), cfg.shopSdaDirectDiskHatchInject.get())
                .setDefaultValue(true)
                .setTooltip(tip("开启后有内容 SDA 会优先放入同网 ME 磁盘仓室直接挂载",
                        "磁盘仓室必须与 FTBQ AE提交器或商店终端在同一 AE 网络；空 SDA 商品不挂载"))
                .setSaveConsumer(cfg.shopSdaDirectDiskHatchInject::set).build());
        shop.addEntry(e.startIntField(Component.literal("出售回收比例（%）"), cfg.shopSellRatioPercent.get())
                .setDefaultValue(70).setMin(1).setMax(100)
                .setTooltip(tip("出售拿回买价的百分比，默认 70，即卖出价 = 买价 × 70%",
                        "买卖价差防止买了立刻卖回零损耗；100 = 无价差"))
                .setSaveConsumer(cfg.shopSellRatioPercent::set).build());

        // ===== 商店银行 =====
        ConfigCategory bank = builder.getOrCreateCategory(Component.literal("商店银行"));
        bank.addEntry(e.startIntField(Component.literal("存款每小时基点"), cfg.shopBankDepositRateBpPerHour.get())
                .setDefaultValue(5).setMin(0).setMax(10_000)
                .setTooltip(tip("万分之 N，默认 5 ≈ 0.05%/小时，约 1.2%/天",
                        "线性单利，利息不滚入本金，按系统时间惰性结算"))
                .setSaveConsumer(cfg.shopBankDepositRateBpPerHour::set).build());
        bank.addEntry(e.startIntField(Component.literal("贷款每小时基点"), cfg.shopBankLoanRateBpPerHour.get())
                .setDefaultValue(15).setMin(0).setMax(10_000)
                .setTooltip(tip("万分之 N，默认 15 ≈ 0.15%/小时，约 3.6%/天",
                        "高于存款利率。还款先冲利息再冲本金，不做强制追讨"))
                .setSaveConsumer(cfg.shopBankLoanRateBpPerHour::set).build());
        bank.addEntry(e.startLongField(Component.literal("最大欠款（星火）"), cfg.shopBankMaxLoanSpark.get())
                .setDefaultValue(100_000_000L).setMin(0L).setMax(Long.MAX_VALUE)
                .setTooltip(tip("单玩家本金加利息达到上限后借不出新的，默认 1 亿"))
                .setSaveConsumer(cfg.shopBankMaxLoanSpark::set).build());
        bank.addEntry(e.startIntField(Component.literal("贷款期限（小时）"), cfg.shopBankLoanTermHours.get())
                .setDefaultValue(24).setMin(1).setMax(8760)
                .setTooltip(tip("从这笔贷款开始起算，默认 24 小时",
                        "到期后只要本金或利息还在，就拒绝下一笔，直到还清",
                        "期限内追加借款不延后到期时间"))
                .setSaveConsumer(cfg.shopBankLoanTermHours::set).build());

        // ===== 缓存与诊断 =====
        ConfigCategory cache = builder.getOrCreateCategory(Component.literal("缓存与诊断"));
        cache.addEntry(e.startBooleanToggle(Component.literal("运行期配方缓存统计"), cfg.runtimeRecipeCacheDiagnostics.get())
                .setDefaultValue(false)
                .setTooltip(tip("统计 hit/miss/negativeHit/clear，不影响缓存读写",
                        "关闭时计数器不自增；开启后用 /shanhai cache stats 查看"))
                .setSaveConsumer(cfg.runtimeRecipeCacheDiagnostics::set).build());
        cache.addEntry(e.startBooleanToggle(Component.literal("KJS 配方库磁盘缓存"), cfg.kjsRecipeLibraryCacheEnabled.get())
                .setDefaultValue(false)
                .setTooltip(tip("默认关闭。开启有风险，一般只用于开发环境加快进游戏",
                        "正常游玩或生产环境不建议开启",
                        "修改后需重启游戏或服务端"))
                .setSaveConsumer(cfg.kjsRecipeLibraryCacheEnabled::set).build());

        // ===== 开发 =====
        ConfigCategory developer = builder.getOrCreateCategory(Component.literal("开发"));
        developer.addEntry(e.startBooleanToggle(Component.literal("开发模式"), cfg.developerMode.get())
                .setDefaultValue(false)
                .setTooltip(tip("关闭：配方修改器导出 json 写到游戏目录 kubejs/data/Exported_Recipe/",
                        "开启：直接写入本机 gt_shanhai 源码 recipes 目录",
                        "只在这台开发机打开。其他机器没有该目录，导出会失败"))
                .setSaveConsumer(cfg.developerMode::set).build());

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
