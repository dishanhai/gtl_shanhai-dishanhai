package com.dishanhai.gt_shanhai.common.machine.wave;

import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.material.Fluid;
import net.minecraftforge.registries.ForgeRegistries;

import com.gregtechceu.gtceu.api.capability.recipe.IO;
import com.gregtechceu.gtceu.api.gui.GuiTextures;
import com.gregtechceu.gtceu.api.gui.fancy.ConfiguratorPanel;
import com.gregtechceu.gtceu.api.gui.fancy.IFancyConfiguratorButton;
import com.gregtechceu.gtceu.api.machine.IMachineBlockEntity;
import com.gregtechceu.gtceu.api.machine.TickableSubscription;
import com.gregtechceu.gtceu.api.machine.trait.NotifiableItemStackHandler;
import com.gregtechceu.gtceu.api.machine.trait.NotifiableFluidTank;
import com.gregtechceu.gtceu.api.recipe.GTRecipeType;
import com.gregtechceu.gtceu.common.machine.multiblock.part.FluidHatchPartMachine;
import com.lowdragmc.lowdraglib.side.fluid.FluidStack;

import com.dishanhai.gt_shanhai.api.recipe.DShanhaiRecipeTypes;

import com.gtladd.gtladditions.api.machine.wireless.GTLAddWirelessWorkableElectricMultipleRecipesMachine;

import com.lowdragmc.lowdraglib.gui.texture.IGuiTexture;
import com.lowdragmc.lowdraglib.gui.texture.ResourceTexture;
import com.lowdragmc.lowdraglib.gui.widget.SlotWidget;
import com.lowdragmc.lowdraglib.gui.widget.Widget;
import com.lowdragmc.lowdraglib.gui.widget.WidgetGroup;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.UUID;

/**
 * 引力波天线发射器
 * 倒悬于大地之上的审判之钉——威慑在此建立，恐惧在此安家。
 * <p>
 * 双模式多方块机器：
 * <ul>
 *   <li><b>生产模式（PRODUCTION）</b>：标准配方加工，产出物品/流体</li>
 *   <li><b>广播模式（BROADCAST）</b>：发射引力波，为范围内机器提供无损超频、</li>
 *       引力透镜复制、怪物生成阻止</li>
 * </ul>
 * <p>
 * 使用 {@link GTLAddWirelessWorkableElectricMultipleRecipesMachine} 基类，直接从无线电网取电。
 * <p>
 * 透镜槽：放入 {@code dishanhai:gravitational_lens}（最多 16 个），
 * 每片透镜提供 +3x 产出倍率 + 5% 复制概率。
 */
public class GravitationalWaveAntennaTransmitter extends GTLAddWirelessWorkableElectricMultipleRecipesMachine {

    private static final Logger LOG = LoggerFactory.getLogger("gt_shanhai:gravitational_wave");
    private static final String KEY_MODE = "broadcastMode";
    private static final int MAX_LENS = 16;

    private static Item LENS_ITEM;

    private NotifiableItemStackHandler lensStorage;
    private int lensCount = 0;
    private int lastLensCount = 0; // 用于检测透镜变化实时更新广播源
    private TickableSubscription lensScanSubs;

    public enum Mode {
        PRODUCTION,
        BROADCAST
    }

    private Mode mode = Mode.PRODUCTION;
    private TickableSubscription broadcastSubs;
    private boolean sourceRegistered = false;

    public GravitationalWaveAntennaTransmitter(IMachineBlockEntity holder) {
        super(holder);
        if (getUuid() == null) setUuid(UUID.randomUUID());
        initLensItem();
        lensStorage = createLensStorage();
    }

    // ========== 透镜物品初始化 ==========

    private static void initLensItem() {
        if (LENS_ITEM != null) return;
        LENS_ITEM = ForgeRegistries.ITEMS.getValue(new ResourceLocation("dishanhai", "gravitational_lens"));
    }

    private NotifiableItemStackHandler createLensStorage() {
        var handler = new NotifiableItemStackHandler(this, 1, IO.NONE, IO.BOTH) {
            @Override
            public int getSlotLimit(int slot) {
                return MAX_LENS;
            }
        };
        handler.setFilter(stack -> {
            if (stack == null || stack.isEmpty()) return true;
            return stack.getItem() == LENS_ITEM;
        });
        return handler;
    }

    @Override
    public Widget createUIWidget() {
        Widget widget = super.createUIWidget();
        if (widget instanceof WidgetGroup group) {
            var size = group.getSize();
            var slot = new SlotWidget(
                lensStorage, 0,
                size.width - 30, size.height - 30,
                true, true
            );
            slot.setBackground(GuiTextures.SLOT);
            group.addWidget(slot);
        }
        return widget;
    }

    // ========== 透镜扫描 ==========

    private void scanLensCount() {
        var stack = lensStorage.storage.getStackInSlot(0);
        if (stack.isEmpty() || stack.getItem() != LENS_ITEM) {
            lensCount = 0;
        } else {
            lensCount = Math.min(MAX_LENS, stack.getCount());
        }
    }

    public int getLensCount() { return lensCount; }

    // ========== 基础属性 ==========

    @Override
    public int getTier() { return 9; }

    @Override
    public long getMaxVoltage() { return Long.MAX_VALUE; }

    @Override
    public int getMaxParallel() { return 1; }

    @Override
    public int getAdditionalThread() { return 0; }

    @Override
    public int getMaxOverclockTier() { return 9; }

    // ========== 配方类型 ==========

    @Override
    public GTRecipeType getRecipeType() {
        return mode == Mode.BROADCAST
                ? DShanhaiRecipeTypes.GRAVITATIONAL_WAVE_CONSUMPTION
                : DShanhaiRecipeTypes.GRAVITATIONAL_WAVE_PRODUCTION;
    }

    // ========== 模式管理 ==========

    public Mode getMode() { return mode; }

    public void setMode(Mode newMode) {
        if (this.mode != newMode) {
            this.mode = newMode;
            var logic = getRecipeLogic();
            if (logic != null) logic.resetRecipeLogic();
            updateBroadcastSubscription();
            if (newMode == Mode.BROADCAST) {
                sourceRegistered = false;
            } else {
                removeBroadcastSource();
            }
            notifyBlockUpdate();
        }
    }

    public void autoSwitchMode(Mode newMode) {
        if (this.mode != newMode) {
            this.mode = newMode;
            updateBroadcastSubscription();
            if (newMode == Mode.BROADCAST) {
                sourceRegistered = false;
            } else {
                removeBroadcastSource();
            }
            notifyBlockUpdate();
        }
    }

    // ========== NBT 持久化 ==========

    private static final String KEY_LENS = "lensStorage";

    @Override
    public void saveCustomPersistedData(CompoundTag tag, boolean saveConfig) {
        super.saveCustomPersistedData(tag, saveConfig);
        tag.putString(KEY_MODE, mode.name());
        tag.put(KEY_LENS, lensStorage.storage.serializeNBT());
    }

    @Override
    public void loadCustomPersistedData(CompoundTag tag) {
        super.loadCustomPersistedData(tag);
        if (tag.contains(KEY_MODE)) {
            try {
                mode = Mode.valueOf(tag.getString(KEY_MODE));
            } catch (IllegalArgumentException e) {
                mode = Mode.PRODUCTION;
            }
        }
        if (tag.contains(KEY_LENS)) {
            lensStorage.storage.deserializeNBT(tag.getCompound(KEY_LENS));
        }
        scanLensCount();
    }

    // ========== 生命周期 ==========

    @Override
    public void onLoad() {
        super.onLoad();
        if (getUuid() == null) setUuid(UUID.randomUUID());
        scanLensCount();
        updateBroadcastSubscription();
    }

    @Override
    public void onUnload() {
        super.onUnload();
        removeBroadcastSource();
        if (broadcastSubs != null) {
            broadcastSubs.unsubscribe();
            broadcastSubs = null;
        }
        if (lensScanSubs != null) {
            lensScanSubs.unsubscribe();
            lensScanSubs = null;
        }
    }

    @Override
    public void onStructureFormed() {
        super.onStructureFormed();
        sourceRegistered = false;
        scanLensCount();
        updateBroadcastSubscription();
        if (lensScanSubs == null) {
            lensScanSubs = subscribeServerTick(lensScanSubs, () -> {
                if (getOffsetTimer() % 40 == 0) scanLensCount();
            });
        }
    }

    @Override
    public void onStructureInvalid() {
        super.onStructureInvalid();
        removeBroadcastSource();
        if (broadcastSubs != null) {
            broadcastSubs.unsubscribe();
            broadcastSubs = null;
        }
        if (lensScanSubs != null) {
            lensScanSubs.unsubscribe();
            lensScanSubs = null;
        }
    }

    // ========== 配方逻辑 ==========

    @Override
    public GravitationalWaveAntennaTransmitterLogic createRecipeLogic(Object... args) {
        return new GravitationalWaveAntennaTransmitterLogic(this);
    }

    @Override
    public GravitationalWaveAntennaTransmitterLogic getRecipeLogic() {
        return (GravitationalWaveAntennaTransmitterLogic) super.getRecipeLogic();
    }

    // ========== GUI 配置器（模式切换） ==========

    @Override
    public void attachConfigurators(ConfiguratorPanel panel) {
        super.attachConfigurators(panel);
        panel.attachConfigurators(createModeToggle());
    }

    private IFancyConfiguratorButton createModeToggle() {
        IGuiTexture productionIcon = new ResourceTexture(
                "gtceu:textures/gui/icon/io_config/cover_settings.png");
        IGuiTexture broadcastIcon = new ResourceTexture(
                "gtceu:textures/gui/icon/distribution_mode/insert_first.png");

        var toggle = new IFancyConfiguratorButton.Toggle(
            productionIcon,
            broadcastIcon,
            () -> getMode() == Mode.BROADCAST,
            (cd, newState) -> setMode(newState ? Mode.BROADCAST : Mode.PRODUCTION)
        );
        toggle.setTooltipsSupplier(state -> {
            if (state) {
                return List.of(
                    Component.translatable("gt_shanhai.machine.gravitational_wave_antenna_transmitter.config.broadcast"),
                    Component.translatable("gt_shanhai.machine.gravitational_wave_antenna_transmitter.config.broadcast.desc",
                        getBroadcastRadius(), Math.min(50, getBoostLevel() / 2))
                );
            }
            return List.of(
                Component.translatable("gt_shanhai.machine.gravitational_wave_antenna_transmitter.config.production"),
                Component.translatable("gt_shanhai.machine.gravitational_wave_antenna_transmitter.config.production.desc")
            );
        });
        return toggle;
    }

    // ========== 广播参数 ==========

    public int getBroadcastRadius() {
        int tier = Math.max(1, getTier());
        int base = 16 + tier * 16;
        // 每片透镜 +10 格，16 片时达 320
        return Math.min(320, base + lensCount * 10);
    }

    public int getBoostLevel() {
        int tier = Math.max(1, getTier());
        return Math.min(100, tier * 10 + 10);
    }

    public long getBroadcastEuConsumption() {
        int tier = Math.max(1, getTier());
        return (long) Math.pow(4, Math.min(tier, 12)) * 64L;
    }

    private void removeBroadcastSource() {
        if (sourceRegistered && getLevel() instanceof ServerLevel serverLevel) {
            GravitationalWaveBroadcastManager.INSTANCE.removeSource(serverLevel, getPos());
            sourceRegistered = false;
        }
    }

    // ========== 广播 tick ==========

    private void updateBroadcastSubscription() {
        boolean shouldRun = isFormed() && mode == Mode.BROADCAST;
        if (shouldRun) {
            if (broadcastSubs == null) {
                broadcastSubs = subscribeServerTick(broadcastSubs, this::broadcastTick);
            }
        } else {
            if (broadcastSubs != null) {
                broadcastSubs.unsubscribe();
                broadcastSubs = null;
            }
        }
    }

    private void broadcastTick() {
        if (getLevel().isClientSide()) return;
        if (!isFormed() || mode != Mode.BROADCAST) {
            updateBroadcastSubscription();
            return;
        }

        if (!(getLevel() instanceof ServerLevel serverLevel)) return;

        // 仅在工作时维持广播源
        if (!getRecipeLogic().isWorking()) {
            if (sourceRegistered) {
                removeBroadcastSource();
            }
            return;
        }

        // 每 10 秒消耗广播燃料
        if (getLevel().getGameTime() % 200 == 0) {
            if (!drainBroadcastFuel()) {
                removeBroadcastSource();
                return;
            }
        }

        // 透镜数量变化时重新注册
        if (sourceRegistered && lensCount != lastLensCount) {
            removeBroadcastSource();
        }
        if (!sourceRegistered) {
            GravitationalWaveBroadcastManager.INSTANCE.addSource(
                serverLevel, getPos(), getBroadcastRadius(), getBoostLevel(), lensCount);
            lastLensCount = lensCount;
            sourceRegistered = true;
            LOG.info(">>> Broadcast REGISTERED at {}, radius={}, power={}, lenses={}",
                    getPos(), getBroadcastRadius(), getBoostLevel(), lensCount);
        }
    }

    /** 遍历流体仓消耗广播燃料（星云同款模式），每 200 tick = 10s */
    private static final int FUEL_MB = 250;

    private boolean drainBroadcastFuel() {
        Fluid[] fuels = {
            ForgeRegistries.FLUIDS.getValue(new ResourceLocation("dishanhai", "matter_fluid_advanced")),
            ForgeRegistries.FLUIDS.getValue(new ResourceLocation("dishanhai", "matter_fluid_basic")),
            ForgeRegistries.FLUIDS.getValue(new ResourceLocation("dishanhai", "zero_point_energy")),
        };
        for (Fluid fluid : fuels) {
            if (fluid == null || fluid == net.minecraft.world.level.material.Fluids.EMPTY) continue;
            for (var part : getParts()) {
                if (!(part instanceof FluidHatchPartMachine hatch)) continue;
                NotifiableFluidTank tank = hatch.tank;
                for (int i = 0; i < tank.getTanks(); i++) {
                    FluidStack inTank = tank.getFluidInTank(i);
                    if (inTank == null || inTank.getFluid() != fluid) continue;
                    if (inTank.getAmount() < FUEL_MB) continue;
                    long remaining = inTank.getAmount() - FUEL_MB;
                    if (remaining <= 0) {
                        tank.setFluidInTank(i, FluidStack.empty());
                    } else {
                        tank.setFluidInTank(i, FluidStack.create(fluid, remaining));
                    }
                    return true;
                }
            }
        }
        return false;
    }

    // ========== 透镜效果查询（给 Mixin 用） ==========

    /**
     * 有透镜时 2x 倍率始终 100% 触发。
     * 3x 倍率概率 = lensCount * 100/16（16 片时 100%）
     */
    public float getLensDuplicationChance() {
        if (lensCount >= 1) return 1.0f;
        return 0f;
    }

    public float getLens3xChance() {
        if (lensCount <= 0) return 0f;
        return Math.min(1.0f, lensCount * (1.0f / MAX_LENS));
    }

    // ========== 显示信息 ==========

    @Override
    public void addDisplayText(List<Component> textList) {
        if (!isFormed()) {
            textList.add(Component.translatable("gtceu.multiblock.invalid_structure")
                    .withStyle(ChatFormatting.RED));
            return;
        }
        addEnergyDisplay(textList);

        if (lensCount > 0) {
            textList.add(Component.translatable("gt_shanhai.machine.gravitational_wave_antenna_transmitter.lens_count",
                    lensCount, MAX_LENS)
                    .withStyle(ChatFormatting.DARK_PURPLE));
            int pct3x = Math.round(getLens3xChance() * 100);
            textList.add(Component.translatable("gt_shanhai.machine.gravitational_wave_antenna_transmitter.lens_3x",
                    pct3x)
                    .withStyle(ChatFormatting.LIGHT_PURPLE));
        }

        if (mode == Mode.BROADCAST) {
            if (sourceRegistered) {
                textList.add(Component.translatable("gt_shanhai.machine.gravitational_wave_antenna_transmitter.broadcast_active")
                        .withStyle(ChatFormatting.GREEN));
            } else {
                textList.add(Component.translatable("gt_shanhai.machine.gravitational_wave_antenna_transmitter.broadcast_inactive")
                        .withStyle(ChatFormatting.RED));
            }
            textList.add(Component.translatable("gt_shanhai.machine.gravitational_wave_antenna_transmitter.mode.broadcast")
                    .withStyle(ChatFormatting.LIGHT_PURPLE));
            textList.add(Component.translatable("gt_shanhai.machine.gravitational_wave_antenna_transmitter.broadcast_info",
                    getBroadcastRadius(), getBoostLevel())
                    .withStyle(ChatFormatting.AQUA));
        } else {
            addWorkingStatus(textList);
            textList.add(Component.translatable("gt_shanhai.machine.gravitational_wave_antenna_transmitter.mode.production")
                    .withStyle(ChatFormatting.GOLD));
        }
    }
}
