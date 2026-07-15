package com.dishanhai.gt_shanhai;

import com.gregtechceu.gtceu.api.GTCEuAPI;
import com.gregtechceu.gtceu.api.recipe.GTRecipeType;

import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Rarity;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

import com.dishanhai.gt_shanhai.common.item.EternalGregTechWorkshopDataModuleItem;
import com.dishanhai.gt_shanhai.common.item.GravitonShardItem;
import com.dishanhai.gt_shanhai.common.item.GuideBookItem;
import com.dishanhai.gt_shanhai.common.item.SuperDiskArrayItem;
import com.dishanhai.gt_shanhai.common.item.SuperDiskArrayCellHandler;
import com.dishanhai.gt_shanhai.common.item.VirtualItemProviderItem;

import net.minecraft.world.item.Item;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.function.Consumer;

@Mod(GTDishanhaiMod.MOD_ID)
public class GTDishanhaiMod {

    public static final String MOD_ID = "gt_shanhai";
    public static final String NAME = "dishanhai";
    public static final Logger LOGGER = LogManager.getLogger(MOD_ID);

    // SDA 启动自动同步的延迟触发 tick（-1 表示未安排）。首次访问 DShanhaiVirtualCellSavedData 会同步
    // 加载+解析整个存档 NBT（大存档实测 9MB+ 解压后要 3 秒以上），不能直接放在 ServerStartedEvent 里做，
    // 否则会卡住玩家登录；改成记录目标 tick，交给下面的 ServerTickEvent 延迟触发，登录流程不再等它。
    private static long sdaAutoSyncTargetTick = -1;

    // 活性中子机械方块 (大明科技外壳)
    public static final DeferredRegister<Block> BLOCKS = DeferredRegister.create(ForgeRegistries.BLOCKS, MOD_ID);
    public static final DeferredRegister<Item> ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS, MOD_ID);
    public static final RegistryObject<Block> ACTIVE_NEUTRON_CASING = BLOCKS.register(
        "active_neutron_casing",
        () -> new Block(BlockBehaviour.Properties.of()
            .mapColor(MapColor.METAL).strength(50f, 1200f)
            .sound(SoundType.METAL).requiresCorrectToolForDrops()));
    public static final RegistryObject<Item> ACTIVE_NEUTRON_CASING_ITEM = ITEMS.register(
        "active_neutron_casing",
        () -> new BlockItem(ACTIVE_NEUTRON_CASING.get(), new Item.Properties()));
    public static final RegistryObject<Item> ETERNAL_WORKSHOP_DATA_MODULE = ITEMS.register(
        "eternal_workshop_data_module",
        () -> new EternalGregTechWorkshopDataModuleItem(new Item.Properties()
            .stacksTo(1)
            .rarity(Rarity.EPIC)));
    public static final RegistryObject<Item> GRAVITON_SHARD = ITEMS.register(
        "graviton_shard",
        () -> new GravitonShardItem(new Item.Properties()
            .stacksTo(64)
            .rarity(Rarity.EPIC)));

    public static final RegistryObject<Item> SUPER_DISK_ARRAY = ITEMS.register(
        "super_disk_array",
        () -> new SuperDiskArrayItem(new Item.Properties()
            .stacksTo(1)
            .rarity(Rarity.EPIC)
            .fireResistant()));

    public static final RegistryObject<Item> VIRTUAL_ITEM_PROVIDER = ITEMS.register(
        "virtual_item_provider",
        () -> new VirtualItemProviderItem(new Item.Properties()
            .stacksTo(1)
            .rarity(Rarity.RARE)));

    public static final RegistryObject<Item> GUIDE_BOOK = ITEMS.register(
        "guide_book",
        () -> new GuideBookItem(new Item.Properties()
            .stacksTo(1)
            .rarity(Rarity.UNCOMMON)));

    public static final RegistryObject<Item> WALLET = ITEMS.register(
        "wallet",
        () -> new com.dishanhai.gt_shanhai.common.item.WalletItem(new Item.Properties()
            .stacksTo(1)
            .rarity(Rarity.UNCOMMON)));

    /** 获取模组版本号，供 KubeJS 调用 */
    public static String getVersion() {
        return net.minecraftforge.fml.ModList.get().getModContainerById(MOD_ID)
                .map(c -> c.getModInfo().getVersion().toString())
                .orElse("unknown");
    }

    public GTDishanhaiMod() {
        LOGGER.info("=== GTDishanhaiMod 构造开始 ===");

        // 注册配置文件 (config/gt_shanhai/gt_shanhai-common.toml)
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, com.dishanhai.gt_shanhai.config.DShanhaiConfig.COMMON_SPEC, "gt_shanhai/gt_shanhai-common.toml");
        LOGGER.info("Config registered");

        // 加载剥离规则持久化
        com.dishanhai.gt_shanhai.api.DShanhaiRecipeModifierAPI.loadStripRules();
        com.dishanhai.gt_shanhai.api.DShanhaiRecipeModifierAPI.loadReplaceRules();
        com.dishanhai.gt_shanhai.api.DShanhaiRecipeModifierAPI.loadDeleteRules();

        var bus = FMLJavaModLoadingContext.get().getModEventBus();

        // 配方库缓存数据包：把 DShanhaiRecipeCache 落盘的 json 常驻注入成数据包，vanilla 原生加载
        bus.addListener(com.dishanhai.gt_shanhai.common.recipe.DShanhaiRecipePackFinder::onAddPackFinders);

        BLOCKS.register(bus);
        ITEMS.register(bus);
        com.dishanhai.gt_shanhai.common.block.DShanhaiAE2Blocks.init(bus);

        GTDishanhaiRegistration.REGISTRATE.registerRegistrate();
        bus.register(GTDishanhaiRegistration.REGISTRATE);

        Consumer<GTCEuAPI.RegisterEvent<?, GTRecipeType>> listener = event -> com.dishanhai.gt_shanhai.api.recipe.DShanhaiRecipeTypes.init();
        bus.addGenericListener(GTRecipeType.class, listener);

        // 在 InterModProcessEvent 中注册 PartAbility（此时方块已全部注册完成）
        bus.addListener((net.minecraftforge.fml.event.lifecycle.InterModProcessEvent event) -> {
            com.dishanhai.gt_shanhai.common.machine.DShanhaiMachines.registerPartAbilities();
        });

        // 网络通道初始化
        com.dishanhai.gt_shanhai.network.ShanhaiNetwork.init();
        LOGGER.info("ShanhaiNetwork.init() 完成");

        // 自定义磁盘槽掉落保护（服务端/单机均需注册）
        com.dishanhai.gt_shanhai.common.misc.DiskMachineDropHandler.init();

        appeng.api.storage.StorageCells.addCellHandler(SuperDiskArrayCellHandler.INSTANCE);

        // 注册 tooltip 事件（仅客户端有效 — DistExecutor 防止服务端加载客户端类）
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> {
            com.dishanhai.gt_shanhai.api.DShanhaiItemTooltipAPI.init();
            com.dishanhai.gt_shanhai.common.misc.HaloEndTooltipHandler.init();
            com.dishanhai.gt_shanhai.common.misc.TooltipEffectHandler.init();
            com.dishanhai.gt_shanhai.common.misc.SuperDiskArrayTooltipHandler.init();
            LOGGER.info("Tooltip handlers 注册完成");
        });

        // 服务端就绪后应用剥离+替换规则（配方类型已注册，JEI 尚未初始化）
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.addListener(
                (net.minecraftforge.event.server.ServerAboutToStartEvent e) -> {
                    com.dishanhai.gt_shanhai.common.shop.ShopConfig.reload();
                    com.dishanhai.gt_shanhai.api.DShanhaiRecipeModifierAPI.runPatternCacheInvalidationBatch("server-about-to-start", () -> {
                        com.dishanhai.gt_shanhai.api.DShanhaiRecipeModifierAPI.updateAllLookupRecipes();
                        com.dishanhai.gt_shanhai.api.DShanhaiRecipeModifierAPI.applyAllReplaceRules();
                        com.dishanhai.gt_shanhai.common.misc.MekanismFurnaceRecipeStripper.strip(e.getServer());
                    });
                    // 配方库缓存：山海的配方库.js 源文件/gtlcore配置没变就跳过重导出；
                    // 变了就把这次 Rhino 真实注册出的配方从 RecipeManager 取出，编码成标准数据包 json 落盘，
                    // 交给 DShanhaiRecipePackFinder 常驻注入，下次开服直接走 vanilla 原生加载。
                    com.dishanhai.gt_shanhai.common.recipe.DShanhaiRecipeCache.exportIfNeeded(e.getServer());
                });

        // 限购总量剩余次数按存档隔离回填/初始化，见 ShopLimitSavedData（不能再等 shop.json 里
        // 那份跨存档共享的数字说了算，否则不同存档会互相"继承"彼此的购买消耗，见反馈）。
        // 必须放 ServerStartingEvent 而不是上面的 ServerAboutToStartEvent——后者此时世界还没加载，
        // server.overworld() 是 null，ShopLimitSavedData.get() 里调 getDataStorage() 会直接 NPE 崩服。
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.addListener(
                (net.minecraftforge.event.server.ServerStartingEvent e) -> {
                    com.dishanhai.gt_shanhai.common.shop.ShopConfig.syncLimitsFromSave(e.getServer());
                });

        net.minecraftforge.common.MinecraftForge.EVENT_BUS.addListener(
                net.minecraftforge.eventbus.api.EventPriority.HIGHEST,
                (net.minecraftforge.event.entity.player.PlayerInteractEvent.RightClickBlock event) -> {
                    if (!(event.getItemStack().getItem() instanceof EternalGregTechWorkshopDataModuleItem item)) {
                        return;
                    }
                    var result = item.handleBlockUse(event.getLevel(), event.getEntity(), event.getItemStack(), event.getPos());
                    if (result.consumesAction()) {
                        event.setCanceled(true);
                        event.setCancellationResult(result);
                    }
                });

        // 服务端启动完成后自动同步所有 SDA 到 kubejs/data（彻底消除手动迁移需求）。
        // 不在 ServerStartedEvent 里同步做——只记录目标 tick，真正的加载+同步交给下面
        // 的 ServerTickEvent 延迟 100 tick（约5秒）后执行，避免卡住玩家登录。
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.addListener(
                (net.minecraftforge.event.server.ServerStartedEvent e) -> {
                    sdaAutoSyncTargetTick = e.getServer().getTickCount() + 100;
                });

        net.minecraftforge.common.MinecraftForge.EVENT_BUS.addListener(
                (net.minecraftforge.event.TickEvent.ServerTickEvent e) -> {
                    if (e.phase != net.minecraftforge.event.TickEvent.Phase.END) return;
                    if (sdaAutoSyncTargetTick < 0) return;
                    if (e.getServer().getTickCount() < sdaAutoSyncTargetTick) return;
                    sdaAutoSyncTargetTick = -1;
                    try {
                        com.dishanhai.gt_shanhai.api.ae.DShanhaiVirtualCellSavedData data =
                                com.dishanhai.gt_shanhai.api.ae.DShanhaiVirtualCellSavedData.get(e.getServer());
                        data.syncAllSdaToContentStore();
                    } catch (Exception ex) {
                        LOGGER.warn("[SDA] 启动自动同步失败: {}", ex.getMessage());
                    }
                });

        // 客户端初始化推迟到 FMLClientSetupEvent（此时 RegistryObject 已可用）
        FMLJavaModLoadingContext.get().getModEventBus().addListener(
            (net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent event) -> {
                com.dishanhai.gt_shanhai.client.ClientInit.init();
                LOGGER.info("ClientInit.init() 完成");
            });

    }
}
