package com.dishanhai.gt_shanhai.common.machine.primordial.module.generator;
import com.dishanhai.gt_shanhai.common.machine.primordial.*;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraftforge.registries.ForgeRegistries;

import com.google.common.primitives.Ints;

import com.dishanhai.gt_shanhai.api.DShanhaiTextUtil;

import com.gregtechceu.gtceu.api.capability.IEnergyContainer;
import com.gregtechceu.gtceu.api.capability.forge.GTCapability;
import com.gregtechceu.gtceu.api.capability.recipe.IO;
import com.gregtechceu.gtceu.api.machine.IMachineBlockEntity;
import com.gregtechceu.gtceu.api.machine.TickableSubscription;
import com.gregtechceu.gtceu.api.machine.trait.NotifiableItemStackHandler;

import com.dishanhai.gt_shanhai.common.machine.misc.DShanhaiWirelessPowerTerminalSavedData;

import com.gtladd.gtladditions.utils.CommonUtils;

import com.hepdd.gtmthings.api.misc.WirelessEnergyManager;

import com.lowdragmc.lowdraglib.gui.texture.TextTexture;
import com.lowdragmc.lowdraglib.gui.widget.ButtonWidget;
import com.lowdragmc.lowdraglib.gui.widget.SlotWidget;
import com.lowdragmc.lowdraglib.gui.widget.Widget;
import com.lowdragmc.lowdraglib.gui.widget.WidgetGroup;

import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.common.util.LazyOptional;

import java.math.BigInteger;
import java.util.List;
import java.util.UUID;

/**
 * 原初质能核心 — 原初引擎发电模块
 *
 * 与"零点能"（电路号驱动、指数增长）独立互补的第二条发电路线：
 * 插入物质模块决定产电档位，插入即产电、不消耗任何物品。
 */
public class PrimordialMassEnergyCore extends PrimordialOmegaEngineModuleBase {

    private static final java.util.Map<Item, Long> ITEM_PARALLEL_MAP = new java.util.HashMap<>();
    private static final long DEFAULT_PARALLEL = 64L;
    private static final long EU_PER_TIER_UNIT = 1048576L; // 2^20，与零点能理论并行单位保持数量级一致

    private static Item ITEM_WZRM, ITEM_WZJC, ITEM_WZCZ1, ITEM_WZSB, ITEM_WZCZ2;
    private static Item ITEM_WZQS, ITEM_WZGL, ITEM_WZSW, ITEM_WZCX, ITEM_WZYH, ITEM_WZCZ3, ITEM_CREATE_MK, ITEM_WZAX, ITEM_WZXC, ITEM_WZHY, ITEM_WZDF, ITEM_REALITY_ANCHOR;

    // 发电输出分配模式
    public static final byte MODE_GRID_ONLY = 0;       // 全电网模式：不为周围机器供电
    public static final byte MODE_HALF_BROADCAST = 1;  // 半网半广播模式（默认）
    public static final byte MODE_BROADCAST_ONLY = 2;  // 完全广播模式：广播吃不下的剩余仍会回落到电网，不浪费
    private static final String KEY_DISTRIBUTION_MODE = "sh_distribution_mode";
    private byte distributionMode = MODE_HALF_BROADCAST;

    private long currentParallel = DEFAULT_PARALLEL;
    private TickableSubscription parallelScanSubs;
    private TickableSubscription powerGenSubs;
    private final NotifiableItemStackHandler machineStorage;

    public PrimordialMassEnergyCore(IMachineBlockEntity holder, Object... args) {
        super(holder, args);
        if (getUuid() == null) setUuid(UUID.randomUUID());
        initItems();
        machineStorage = createMachineStorage();
    }

    private NotifiableItemStackHandler createMachineStorage() {
        var handler = new NotifiableItemStackHandler(this, 1, IO.NONE, IO.BOTH);
        handler.setFilter(stack -> stack == null || stack.isEmpty() || ITEM_PARALLEL_MAP.containsKey(stack.getItem()));
        return handler;
    }

    @Override
    public Widget createUIWidget() {
        Widget w = super.createUIWidget();
        if (w instanceof WidgetGroup g) {
            var s = g.getSize();
            var slot = new SlotWidget(machineStorage.storage, 0, s.width - 30, s.height - 30, true, true);
            slot.setBackground(SlotWidget.ITEM_SLOT_TEXTURE);
            slot.setHoverTooltips(
                    Component.literal("§6§l质能档位槽"),
                    Component.literal("§7放入山海物质模块系列"),
                    Component.literal("§7决定本核心的发电档位"),
                    Component.literal("§7插入即产电，不消耗任何物品"));
            g.addWidget(slot);

            var gridBtn = new ButtonWidget(s.width - 78, s.height - 50, 24, 16,
                    new TextTexture("网", -1), clickData -> setDistributionMode(MODE_GRID_ONLY));
            gridBtn.setHoverTooltips(
                    Component.literal("§b§l全电网模式"),
                    Component.literal("§7发电全部写入无线电网"),
                    Component.literal("§7不为周围机器供电"));
            g.addWidget(gridBtn);

            var halfBtn = new ButtonWidget(s.width - 52, s.height - 50, 24, 16,
                    new TextTexture("半", -1), clickData -> setDistributionMode(MODE_HALF_BROADCAST));
            halfBtn.setHoverTooltips(
                    Component.literal("§a§l半网半广播模式（默认）"),
                    Component.literal("§7一半广播给已登记的可受电机器"),
                    Component.literal("§7另一半连同广播剩余写入无线电网"));
            g.addWidget(halfBtn);

            var broadcastBtn = new ButtonWidget(s.width - 26, s.height - 50, 24, 16,
                    new TextTexture("播", -1), clickData -> setDistributionMode(MODE_BROADCAST_ONLY));
            broadcastBtn.setHoverTooltips(
                    Component.literal("§d§l完全广播模式"),
                    Component.literal("§7发电全部尝试广播给已登记的可受电机器"),
                    Component.literal("§7广播吃不下的剩余部分仍会写入无线电网，不会浪费"));
            g.addWidget(broadcastBtn);
        }
        return w;
    }

    private void setDistributionMode(byte mode) {
        if (isRemote()) return;
        distributionMode = mode;
        markDirty();
    }

    @Override
    public void saveCustomPersistedData(CompoundTag tag, boolean forSyncing) {
        super.saveCustomPersistedData(tag, forSyncing);
        tag.putByte(KEY_DISTRIBUTION_MODE, distributionMode);
    }

    @Override
    public void loadCustomPersistedData(CompoundTag tag) {
        super.loadCustomPersistedData(tag);
        if (tag.contains(KEY_DISTRIBUTION_MODE)) {
            distributionMode = tag.getByte(KEY_DISTRIBUTION_MODE);
        }
    }

    private static void initItems() {
        if (ITEM_WZRM != null) return;
        ITEM_WZRM = ForgeRegistries.ITEMS.getValue(new ResourceLocation("dishanhai", "wzrm"));
        ITEM_WZJC = ForgeRegistries.ITEMS.getValue(new ResourceLocation("dishanhai", "wzjc"));
        ITEM_WZCZ1 = ForgeRegistries.ITEMS.getValue(new ResourceLocation("dishanhai", "wzcz1"));
        ITEM_WZSB = ForgeRegistries.ITEMS.getValue(new ResourceLocation("dishanhai", "wzsb"));
        ITEM_WZCZ2 = ForgeRegistries.ITEMS.getValue(new ResourceLocation("dishanhai", "wzcz2"));
        ITEM_WZQS = ForgeRegistries.ITEMS.getValue(new ResourceLocation("dishanhai", "wzqs"));
        ITEM_WZGL = ForgeRegistries.ITEMS.getValue(new ResourceLocation("dishanhai", "wzgl"));
        ITEM_WZSW = ForgeRegistries.ITEMS.getValue(new ResourceLocation("dishanhai", "wzsw"));
        ITEM_WZCX = ForgeRegistries.ITEMS.getValue(new ResourceLocation("dishanhai", "wzcx"));
        ITEM_WZYH = ForgeRegistries.ITEMS.getValue(new ResourceLocation("dishanhai", "wzyh"));
        ITEM_WZCZ3 = ForgeRegistries.ITEMS.getValue(new ResourceLocation("dishanhai", "wzcz3"));
        ITEM_CREATE_MK = ForgeRegistries.ITEMS.getValue(new ResourceLocation("dishanhai", "create_mk"));
        ITEM_WZAX = ForgeRegistries.ITEMS.getValue(new ResourceLocation("dishanhai", "wzax"));
        ITEM_WZXC = ForgeRegistries.ITEMS.getValue(new ResourceLocation("dishanhai", "wzxc"));
        ITEM_WZHY = ForgeRegistries.ITEMS.getValue(new ResourceLocation("dishanhai", "wzhy"));
        ITEM_WZDF = ForgeRegistries.ITEMS.getValue(new ResourceLocation("dishanhai", "wzdf"));
        ITEM_REALITY_ANCHOR = ForgeRegistries.ITEMS.getValue(new ResourceLocation("dishanhai", "reality_anchor_module"));

        ITEM_PARALLEL_MAP.put(ITEM_WZRM, 128L);
        ITEM_PARALLEL_MAP.put(ITEM_WZJC, 256L);
        ITEM_PARALLEL_MAP.put(ITEM_WZCZ1, 512L);
        ITEM_PARALLEL_MAP.put(ITEM_WZSB, 2048L);
        ITEM_PARALLEL_MAP.put(ITEM_WZCZ2, 16384L);
        ITEM_PARALLEL_MAP.put(ITEM_WZQS, 65536L);
        ITEM_PARALLEL_MAP.put(ITEM_WZGL, 524288L);
        ITEM_PARALLEL_MAP.put(ITEM_WZSW, 2097152L);
        ITEM_PARALLEL_MAP.put(ITEM_WZCX, 268435456L);
        ITEM_PARALLEL_MAP.put(ITEM_WZYH, 2147483647L);
        ITEM_PARALLEL_MAP.put(ITEM_WZCZ3, 4611686018427387903L);
        ITEM_PARALLEL_MAP.put(ITEM_CREATE_MK, Long.MAX_VALUE);
        ITEM_PARALLEL_MAP.put(ITEM_REALITY_ANCHOR, 6917529027641081855L);
        ITEM_PARALLEL_MAP.put(ITEM_WZAX, 4096L);
        ITEM_PARALLEL_MAP.put(ITEM_WZXC, 1024L);
        ITEM_PARALLEL_MAP.put(ITEM_WZHY, 1048576L);
        ITEM_PARALLEL_MAP.put(ITEM_WZDF, 536870912L);
    }

    private void scanBoostItem() {
        var stack = machineStorage.storage.getStackInSlot(0);
        long base = ITEM_PARALLEL_MAP.getOrDefault(stack.getItem(), DEFAULT_PARALLEL);
        currentParallel = applyModuleCountParallelMultiplier(base, stack.getCount());
    }

    @Override
    public void onStructureFormed() {
        super.onStructureFormed();
        scanBoostItem();
        parallelScanSubs = subscribeServerTick(parallelScanSubs, () -> { if (getOffsetTimer() % 3 == 0) scanBoostItem(); });
        powerGenSubs = subscribeServerTick(powerGenSubs, this::generateTick);
    }

    @Override
    public void onStructureInvalid() {
        super.onStructureInvalid();
        if (parallelScanSubs != null) { parallelScanSubs.unsubscribe(); parallelScanSubs = null; }
        if (powerGenSubs != null) { powerGenSubs.unsubscribe(); powerGenSubs = null; }
    }

    private static final BigInteger TWO = BigInteger.valueOf(2L);

    /**
     * 纯 Tick 驱动直接产电，不经过 GTCEu 配方系统：
     * 只要结构成型且模块可工作（连了主机或独立模式），每 tick 按当前质能档位产出的 EU
     * 按 {@link #distributionMode} 分配：
     * - {@link #MODE_GRID_ONLY}：全部写入无线电网，不广播；
     * - {@link #MODE_HALF_BROADCAST}（默认）：一半广播给全服已自动登记的可受电 GTCEu 机器
     *   （任意模组，只要暴露能量胶囊、加载进世界即自动登记，详见
     *   {@link com.dishanhai.gt_shanhai.mixin.DShanhaiAutoPowerRegistryMixin} 与
     *   {@link DShanhaiWirelessPowerTerminalSavedData}，不限距离/维度），另一半写入电网；
     * - {@link #MODE_BROADCAST_ONLY}：全部尝试广播。
     * 三种模式下，广播吃不下的剩余部分都会回落写入无线电网（GTM/gtladditions 共用同一张
     * 电网，原初系机器天然打通），不会被浪费。
     */
    private void generateTick() {
        if (!isFormed() || !canModuleWork()) return;
        BigInteger total = getGeneratedEuPerTick();
        if (total.signum() <= 0) return;

        BigInteger broadcastBudget = distributionMode == MODE_GRID_ONLY ? BigInteger.ZERO
                : distributionMode == MODE_BROADCAST_ONLY ? total
                : total.divide(TWO);
        BigInteger gridShare = total.subtract(broadcastBudget);
        BigInteger leftover = broadcastBudget.signum() > 0 ? broadcastEuToTerminals(broadcastBudget) : BigInteger.ZERO;
        BigInteger toGrid = gridShare.add(leftover);

        UUID targetUuid = resolveWirelessGridUuid();
        if (targetUuid == null || toGrid.signum() <= 0) return;
        try {
            WirelessEnergyManager.addEUToGlobalEnergyMap(targetUuid, toGrid, this);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * 遍历全服已登记的无线受电终端（跨维度），按登记数量均分预算，往每个终端的
     * GTCEu 原生能量胶囊（IEnergyContainer）里按对方自报的输入电压/安培数注能，
     * 不超过对方接受能力。返回本轮没花完的预算。
     */
    private BigInteger broadcastEuToTerminals(BigInteger budgetEu) {
        if (budgetEu == null || budgetEu.signum() <= 0) return budgetEu == null ? BigInteger.ZERO : budgetEu;
        if (!(getLevel() instanceof ServerLevel selfLevel)) return budgetEu;
        MinecraftServer server = selfLevel.getServer();
        var terminals = DShanhaiWirelessPowerTerminalSavedData.get(server).getAll();
        if (terminals.isEmpty()) return budgetEu;

        BigInteger share = budgetEu.divide(BigInteger.valueOf(terminals.size()));
        if (share.signum() <= 0) return budgetEu;

        BigInteger totalUsed = BigInteger.ZERO;
        for (var entry : terminals) {
            try {
                ServerLevel targetLevel = resolveLevel(server, entry.dimension);
                if (targetLevel == null || !targetLevel.isLoaded(entry.pos)) continue;
                BlockEntity target = targetLevel.getBlockEntity(entry.pos);
                if (target == null || target.isRemoved()) continue;
                LazyOptional<IEnergyContainer> opt = target.getCapability(GTCapability.CAPABILITY_ENERGY_CONTAINER, null);
                if (!opt.isPresent()) continue;
                IEnergyContainer container = opt.orElse(null);
                if (container == null) continue;

                long voltage = container.getInputVoltage();
                long amperage = container.getInputAmperage();
                if (voltage <= 0 || amperage <= 0) continue;

                BigInteger voltageBI = BigInteger.valueOf(voltage);
                BigInteger maxAmpsByShare = share.divide(voltageBI);
                if (maxAmpsByShare.signum() <= 0) continue;
                long ampsToSend = Math.min(amperage, saturatedLong(maxAmpsByShare));
                if (ampsToSend <= 0) continue;

                long accepted = container.acceptEnergyFromNetwork(null, voltage, ampsToSend);
                if (accepted > 0) {
                    totalUsed = totalUsed.add(voltageBI.multiply(BigInteger.valueOf(Math.min(accepted, ampsToSend))));
                }
            } catch (Exception ignored) {}
        }
        return budgetEu.subtract(totalUsed).max(BigInteger.ZERO);
    }

    private static ServerLevel resolveLevel(MinecraftServer server, String dimension) {
        try {
            ResourceKey<Level> key = ResourceKey.create(Registries.DIMENSION, new ResourceLocation(dimension));
            return server.getLevel(key);
        } catch (Exception e) {
            return null;
        }
    }

    private static long saturatedLong(BigInteger value) {
        return value.compareTo(BigInteger.valueOf(Long.MAX_VALUE)) > 0 ? Long.MAX_VALUE : value.longValue();
    }

    @Override
    protected NotifiableItemStackHandler[] getPersistedStorages() {
        return new NotifiableItemStackHandler[]{ machineStorage, threadBoostSlot };
    }

    @Override
    public int getMaxParallel() { return Ints.saturatedCast(currentParallel); }
    public long getCurrentParallel() { return currentParallel; }

    @Override
    public long getMaxVoltage() { return Long.MAX_VALUE; }
    @Override
    public int getTier() { return 9; }

    /** 本 tick 应产出的 EU（BigInteger，随质能档位线性增长，不做指数放大）。 */
    public BigInteger getGeneratedEuPerTick() {
        return BigInteger.valueOf(currentParallel).multiply(BigInteger.valueOf(EU_PER_TIER_UNIT));
    }

    @Override
    protected void addParallelDisplay(List<Component> textList) {
        var stack = machineStorage.storage.getStackInSlot(0);
        var itemName = stack.isEmpty()
            ? Component.translatable("gt_shanhai.machine.module_slot.empty").withStyle(ChatFormatting.GRAY)
            : stack.getHoverName().copy().withStyle(ChatFormatting.AQUA);
        textList.add(Component.literal("").append(DShanhaiTextUtil.createElectricText("已安装: ")).append(itemName));

        long p = getCurrentParallel();
        boolean inf = p >= Long.MAX_VALUE / 2;
        textList.add(Component.literal("").append(DShanhaiTextUtil.createElectricText("质能档位: "))
                .append(inf ? DShanhaiTextUtil.createUltimateRainbow("∞ 无限") : DShanhaiTextUtil.createAuroraText(String.format("%,d", p))));

        textList.add(Component.literal("").append(DShanhaiTextUtil.createElectricText("发电功率: "))
                .append(Component.literal(CommonUtils.formatBigIntegerFixed(getGeneratedEuPerTick()) + " EU/t")
                        .withStyle(ChatFormatting.GOLD)));

        String modeText = distributionMode == MODE_GRID_ONLY ? "全电网"
                : distributionMode == MODE_BROADCAST_ONLY ? "完全广播" : "半网半广播";
        textList.add(Component.literal("").append(DShanhaiTextUtil.createElectricText("输出模式: "))
                .append(Component.literal(modeText).withStyle(ChatFormatting.LIGHT_PURPLE)));
        addThreadBoostDisplay(textList);
    }

    @Override
    public void addDisplayText(List<Component> textList) {
        if (isFormed()) {
            addHostStatusDisplay(textList);
            if (!canModuleWork()) {
                textList.add(Component.translatable("gt_shanhai.machine.primordial_mass_energy_core.name")
                        .withStyle(ChatFormatting.GOLD));
                return;
            }
            addEnergyDisplay(textList);
            addParallelDisplay(textList);
            textList.add(Component.translatable("gt_shanhai.machine.primordial_mass_energy_core.mode")
                    .withStyle(ChatFormatting.GREEN));
        } else {
            textList.add(Component.translatable("gtceu.multiblock.invalid_structure")
                    .withStyle(ChatFormatting.RED));
        }
        textList.add(Component.translatable("gt_shanhai.machine.primordial_mass_energy_core.name")
                .withStyle(ChatFormatting.GOLD));
    }
}
