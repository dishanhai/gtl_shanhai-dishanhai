package com.dishanhai.gt_shanhai.common.machine.preview;

import com.dishanhai.gt_shanhai.GTDishanhaiRegistration;
import com.gregtechceu.gtceu.api.GTValues;
import com.gregtechceu.gtceu.api.data.RotationState;
import com.gregtechceu.gtceu.api.machine.IMachineBlockEntity;
import com.gregtechceu.gtceu.api.machine.MultiblockMachineDefinition;
import com.dishanhai.gt_shanhai.api.recipe.DShanhaiRecipeTypes;
import com.dishanhai.gt_shanhai.common.machine.primordial.PrimordialOmegaEngineMachine;
import com.dishanhai.gt_shanhai.common.machine.primordial.PrimordialOmegaEngineModuleBase;
import com.dishanhai.gt_shanhai.common.machine.primordial.guixu.GuixuNyarlathotepLink;
import com.dishanhai.gt_shanhai.common.machine.primordial.guixu.GuixuVisionCoreMachine;
import com.dishanhai.gt_shanhai.common.machine.primordial.module.forge.PrimordialCoinForgeModule;
import com.dishanhai.gt_shanhai.common.machine.wave.GravitationalWaveBroadcastManager;
import com.gregtechceu.gtceu.api.capability.recipe.IO;
import com.gregtechceu.gtceu.api.gui.GuiTextures;
import com.gregtechceu.gtceu.api.machine.MetaMachine;
import com.gregtechceu.gtceu.api.machine.TickableSubscription;
import com.gregtechceu.gtceu.api.machine.trait.NotifiableItemStackHandler;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import com.gregtechceu.gtceu.utils.GTUtil;
import com.gtladd.gtladditions.api.machine.trait.IWirelessNetworkEnergyHandler;
import com.lowdragmc.lowdraglib.gui.widget.SlotWidget;
import com.lowdragmc.lowdraglib.gui.widget.LabelWidget;
import com.lowdragmc.lowdraglib.gui.widget.Widget;
import com.lowdragmc.lowdraglib.gui.widget.WidgetGroup;
import com.lowdragmc.lowdraglib.syncdata.annotation.DescSynced;
import com.lowdragmc.lowdraglib.syncdata.annotation.Persisted;
import com.lowdragmc.lowdraglib.syncdata.field.ManagedFieldHolder;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import java.util.List;

/** 山海：保留舊預覽 ID 的正式奈亞；獨立倉口供能供料，可接歸墟共享增益。 */
public final class ShanhaiNyarlathotepPreviewMachine extends PrimordialCoinForgeModule {
    public static final String ID = "nyarlathoteps_tentacle_preview";
    private static final ManagedFieldHolder FIELDS = new ManagedFieldHolder(
            ShanhaiNyarlathotepPreviewMachine.class,
            new ManagedFieldHolder(PrimordialOmegaEngineModuleBase.class, MANAGED_FIELD_HOLDER));
    @Persisted @DescSynced private String linkedGuixuDim = "";
    @Persisted @DescSynced private long linkedGuixuPos;
    @DescSynced private boolean nyarlathotepLinkReady;
    @DescSynced private long storedLocalEnergy;
    @DescSynced private int effectiveOutputMultiplier = 1;
    @DescSynced private long effectiveParallel;
    private boolean linkLoaded;
    private TickableSubscription linkSubscription;
    private IWirelessNetworkEnergyHandler localEnergy;
    // 保留舊版附加倉儲的索引，升級既有存檔後可取出透鏡轉入回響。
    private final NotifiableItemStackHandler legacyLenses;

    public ShanhaiNyarlathotepPreviewMachine(IMachineBlockEntity holder) {
        super(holder);
        legacyLenses = new NotifiableItemStackHandler(this, 1, IO.NONE, IO.OUT);
        legacyLenses.setFilter(stack -> stack.isEmpty());
    }

    @Override public ManagedFieldHolder getFieldHolder() { return FIELDS; }
    @Override public ShanhaiNyarlathotepRecipeLogic createRecipeLogic(Object... args) {
        return new ShanhaiNyarlathotepRecipeLogic(this);
    }
    @Override public ShanhaiNyarlathotepRecipeLogic getRecipeLogic() {
        return (ShanhaiNyarlathotepRecipeLogic) recipeLogic;
    }
    @Override public boolean canWorkWithoutHost() { return true; }
    @Override protected boolean canModuleWork() { return isFormed(); }
    @Override public BlockPos[] getHostScanPositions() { return new BlockPos[0]; }
    @Override public boolean isValidHost(MetaMachine machine) { return false; }
    @Override public GuixuVisionCoreMachine getHost() { return GuixuNyarlathotepLink.resolveHost(this); }
    @Override public long getMaxVoltage() {
        return energyContainer == null ? 0L : energyContainer.getHighestInputVoltage();
    }
    @Override public int getTier() { return GTUtil.getTierByVoltage(getMaxVoltage()); }

    @Override public IWirelessNetworkEnergyHandler getWirelessNetworkEnergyHandler() {
        if (localEnergy == null) localEnergy = new ShanhaiLocalEnergyHandler(
                () -> energyContainer == null ? 0L : energyContainer.getEnergyStored(),
                amount -> energyContainer == null ? 0L : energyContainer.removeEnergy(amount),
                () -> linkLoaded && !isRemote() && isFormed() && !isInValid());
        return localEnergy;
    }

    public String getLinkedGuixuDim() { return linkedGuixuDim; }
    public BlockPos getLinkedGuixuPos() { return BlockPos.of(linkedGuixuPos); }
    public boolean hasGuixuLink() { return !linkedGuixuDim.isEmpty(); }
    public boolean isGuixuLinkReady() { return nyarlathotepLinkReady; }
    public boolean isNyarlathotepLinkLoaded() { return linkLoaded; }
    public boolean isLinkedToGuixu(String dimension, BlockPos pos) {
        return hasGuixuLink() && linkedGuixuDim.equals(dimension) && linkedGuixuPos == pos.asLong();
    }
    public void setGuixuLink(String dimension, BlockPos pos) {
        if (isRemote()) return;
        linkedGuixuDim = dimension;
        linkedGuixuPos = pos.asLong();
        reconcileHost();
        getRecipeLogic().onModuleLevelChanged();
        markDirty();
    }
    public void clearGuixuLink() {
        if (isRemote()) return;
        linkedGuixuDim = "";
        linkedGuixuPos = 0L;
        reconcileHost();
        getRecipeLogic().onModuleLevelChanged();
        markDirty();
    }
    private void reconcileHost() {
        PrimordialOmegaEngineMachine old = super.getHost();
        GuixuVisionCoreMachine current = getHost();
        if (old != current) {
            if (old != null) old.removeModule(this);
            super.setHost(current);
            if (current != null) current.addModule(this);
            getRecipeLogic().onModuleLevelChanged();
        }
        if (nyarlathotepLinkReady != (current != null)) {
            nyarlathotepLinkReady = current != null;
            markDirty();
        }
    }

    @Override public void onLoad() {
        linkLoaded = true;
        super.onLoad();
        // 撤回舊版在本主機位置登記的廣播；新廣播只由回響的實際配方維持。
        if (getLevel() instanceof ServerLevel level)
            GravitationalWaveBroadcastManager.INSTANCE.removeSource(level, getPos());
        if (!isRemote()) linkSubscription = subscribeServerTick(linkSubscription, () -> {
            if (getOffsetTimer() % 20 == 0) {
                reconcileHost();
                long stored = energyContainer == null ? 0L : Math.max(0L, energyContainer.getEnergyStored());
                int multiplier = super.getHostOutputMultiplier();
                long parallel = isFormed() ? super.getAvailableRecipeParallel() : 0L;
                if (stored != storedLocalEnergy || multiplier != effectiveOutputMultiplier || parallel != effectiveParallel) {
                    storedLocalEnergy = stored;
                    effectiveOutputMultiplier = multiplier;
                    effectiveParallel = parallel;
                    notifyBlockUpdate();
                }
            }
        });
    }
    @Override public void onStructureFormed() {
        super.onStructureFormed();
        reconcileHost();
    }
    @Override public void onStructureInvalid() {
        super.onStructureInvalid();
        nyarlathotepLinkReady = false;
    }
    @Override public void onUnload() {
        linkLoaded = false;
        reconcileHost();
        if (linkSubscription != null) { linkSubscription.unsubscribe(); linkSubscription = null; }
        super.onUnload();
    }
    @Override public boolean beforeWorking(GTRecipe recipe) {
        reconcileHost();
        return super.beforeWorking(recipe);
    }

    @Override protected NotifiableItemStackHandler[] getPersistedStorages() {
        var parent = super.getPersistedStorages();
        var result = java.util.Arrays.copyOf(parent, parent.length + 1);
        result[parent.length] = legacyLenses;
        return result;
    }
    @Override public Widget createUIWidget() {
        Widget widget = super.createUIWidget();
        if (widget instanceof WidgetGroup group) {
            group.addWidget(new SlotWidget(legacyLenses.storage, 0, 160, 32, true, false)
                    .setBackground(GuiTextures.SLOT)
                    .setHoverTooltips(Component.literal("§d旧版透镜取回"),
                            Component.literal("§7广播已迁移至奈亚拉托提普之回响"),
                            Component.literal("§7此槽只允许取出，请将透镜放入回响")));
            group.addWidget(new LabelWidget(136, 36, "取回"));
        }
        return widget;
    }
    @Override public java.math.BigInteger getWirelessGridEnergy() {
        return isRemote() ? java.math.BigInteger.valueOf(storedLocalEnergy)
                : getWirelessNetworkEnergyHandler().getMaxAvailableEnergy();
    }
    @Override public int getHostOutputMultiplier() {
        return isRemote() ? effectiveOutputMultiplier : super.getHostOutputMultiplier();
    }
    @Override public long getAvailableRecipeParallel() {
        return isRemote() ? effectiveParallel : super.getAvailableRecipeParallel();
    }
    @Override public void addDisplayText(List<Component> text) {
        if (!isFormed()) { text.add(Component.translatable("gtceu.multiblock.invalid_structure")); return; }
        text.add(Component.translatable("block.gt_shanhai." + ID));
        text.add(Component.translatable("gt_shanhai.nyarlathotep.link." +
                (isGuixuLinkReady() ? "ready" : hasGuixuLink() ? "waiting" : "standalone")));
        addHostStatusDisplay(text);
        text.add(Component.literal("EU: " + getWirelessGridEnergy()));
        text.add(Component.literal("§b并行 " + getAvailableRecipeParallel() + "　产出 ×" + getHostOutputMultiplier()));
        addThreadBoostDisplay(text);
        addWorkingStatus(text);
        text.add(Component.literal("§d广播：请使用远程链接的奈亚拉托提普之回响"));
    }

    public static MultiblockMachineDefinition register() {
        return GTDishanhaiRegistration.REGISTRATE
                .multiblock(ID, ShanhaiNyarlathotepPreviewMachine::new)
                .rotationState(RotationState.ALL)
                .tier(GTValues.UEV)
                .recipeTypes(DShanhaiRecipeTypes.SPACETIME_DISTORTION, DShanhaiRecipeTypes.COIN_FORGE)
                .pattern(ShanhaiNyarlathotepPreviewStructure::createPattern)
                .appearanceBlock(ShanhaiNyarlathotepPreviewStructure::appearanceBlock)
                .workableCasingRenderer(new ResourceLocation("gtlcore", "block/molecular_casing"),
                        new ResourceLocation("gtceu", "block/multiblock/fusion_reactor"))
                .renderer(() -> new com.dishanhai.gt_shanhai.client.renderer.preview.ShanhaiNyarlathotepInstabilityRenderer(
                        new ResourceLocation("gtlcore", "block/molecular_casing"),
                        new ResourceLocation("gtceu", "block/multiblock/fusion_reactor")))
                .hasTESR(true)
                .tooltips(
                        Component.translatable("gt_shanhai.multiblock." + ID + ".tooltip.0"),
                        Component.translatable("gt_shanhai.multiblock." + ID + ".tooltip.1"),
                        Component.translatable("gt_shanhai.multiblock." + ID + ".tooltip.2"),
                        Component.translatable("gt_shanhai.multiblock." + ID + ".tooltip.3"))
                .register();
    }
}
