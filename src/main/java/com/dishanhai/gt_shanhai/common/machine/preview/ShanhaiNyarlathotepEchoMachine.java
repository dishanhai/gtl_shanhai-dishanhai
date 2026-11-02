package com.dishanhai.gt_shanhai.common.machine.preview;

import com.dishanhai.gt_shanhai.GTDishanhaiRegistration;
import com.dishanhai.gt_shanhai.api.recipe.DShanhaiRecipeTypes;
import com.dishanhai.gt_shanhai.common.machine.primordial.guixu.GuixuNyarlathotepLink;
import com.dishanhai.gt_shanhai.common.machine.wave.GravitationalWaveBroadcastManager;
import com.dishanhai.gt_shanhai.common.machine.wave.NyarlathotepEchoBroadcastProfile;
import com.gregtechceu.gtceu.api.GTValues;
import com.gregtechceu.gtceu.api.capability.recipe.IO;
import com.gregtechceu.gtceu.api.data.RotationState;
import com.gregtechceu.gtceu.api.gui.GuiTextures;
import com.gregtechceu.gtceu.api.machine.IMachineBlockEntity;
import com.gregtechceu.gtceu.api.machine.MultiblockMachineDefinition;
import com.gregtechceu.gtceu.api.machine.TickableSubscription;
import com.gregtechceu.gtceu.api.machine.multiblock.WorkableElectricMultiblockMachine;
import com.gregtechceu.gtceu.api.machine.trait.NotifiableItemStackHandler;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import com.lowdragmc.lowdraglib.gui.widget.ComponentPanelWidget;
import com.lowdragmc.lowdraglib.gui.widget.DraggableScrollableWidgetGroup;
import com.lowdragmc.lowdraglib.gui.widget.LabelWidget;
import com.lowdragmc.lowdraglib.gui.widget.SlotWidget;
import com.lowdragmc.lowdraglib.gui.widget.Widget;
import com.lowdragmc.lowdraglib.gui.widget.WidgetGroup;
import com.lowdragmc.lowdraglib.syncdata.annotation.DescSynced;
import com.lowdragmc.lowdraglib.syncdata.annotation.Persisted;
import com.lowdragmc.lowdraglib.syncdata.field.ManagedFieldHolder;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraftforge.registries.ForgeRegistries;
import java.util.List;
import java.util.Locale;

/** 山海：本機倉口供能／供料的遠端回響；標準配方邏輯保留廣播配方實際時長。 */
public final class ShanhaiNyarlathotepEchoMachine extends WorkableElectricMultiblockMachine {
    public static final String ID = "nyarlathoteps_echo";
    private static final String LANG = "gt_shanhai.nyarlathotep.echo.";
    private static final ManagedFieldHolder FIELDS = new ManagedFieldHolder(ShanhaiNyarlathotepEchoMachine.class, MANAGED_FIELD_HOLDER);
    @Persisted @DescSynced private String linkedNyarlathotepDim = "";
    @Persisted @DescSynced private long linkedNyarlathotepPos;
    @DescSynced private boolean nyarlathotepReady;
    @DescSynced private boolean guixuReady;
    @DescSynced private boolean broadcastActive;
    @DescSynced private int storedLenses;
    @DescSynced private int effectiveLenses;
    @DescSynced private int radius;
    @DescSynced private int outputMultiplier = 2;
    @DescSynced private int powerLevel;
    @DescSynced private double selfFactor = 1;
    @Persisted @DescSynced private double paidFactor = 1;
    @DescSynced private int remainingTicks;
    private final NotifiableItemStackHandler lenses;
    private boolean echoLoaded;
    private TickableSubscription broadcastSubscription;
    private NyarlathotepEchoBroadcastProfile.Profile registeredProfile;

    public ShanhaiNyarlathotepEchoMachine(IMachineBlockEntity holder) {
        super(holder);
        lenses = new NotifiableItemStackHandler(this, 1, IO.NONE, IO.BOTH) {
            @Override public int getSlotLimit(int slot) { return 64; }
            @Override public void onContentsChanged() {
                super.onContentsChanged();
                if (!isRemote()) { getRecipeLogic().markLastRecipeDirty(); ShanhaiNyarlathotepEchoMachine.this.markDirty(); }
            }
        };
        lenses.setFilter(stack -> stack.isEmpty() || new ResourceLocation("dishanhai", "gravitational_lens")
                .equals(ForgeRegistries.ITEMS.getKey(stack.getItem())));
    }

    @Override public ManagedFieldHolder getFieldHolder() { return FIELDS; }
    @Override public ShanhaiNyarlathotepEchoRecipeLogic createRecipeLogic(Object... args) { return new ShanhaiNyarlathotepEchoRecipeLogic(this); }
    @Override public ShanhaiNyarlathotepEchoRecipeLogic getRecipeLogic() { return (ShanhaiNyarlathotepEchoRecipeLogic) recipeLogic; }
    public boolean isEchoLoaded() { return echoLoaded; }
    public boolean hasNyarlathotepLink() { return !linkedNyarlathotepDim.isEmpty(); }
    public String getLinkedNyarlathotepDim() { return linkedNyarlathotepDim; }
    public BlockPos getLinkedNyarlathotepPos() { return BlockPos.of(linkedNyarlathotepPos); }
    public void setNyarlathotepLink(String dimension, BlockPos pos) {
        if (isRemote()) return;
        linkedNyarlathotepDim = dimension; linkedNyarlathotepPos = pos.asLong();
        getRecipeLogic().markLastRecipeDirty(); markDirty(); refreshDisplayAndSource();
    }
    public void clearNyarlathotepLink() {
        if (isRemote()) return;
        linkedNyarlathotepDim = ""; linkedNyarlathotepPos = 0;
        getRecipeLogic().markLastRecipeDirty(); markDirty(); refreshDisplayAndSource();
    }

    private NyarlathotepEchoBroadcastProfile.Profile currentProfile() {
        var target = ShanhaiNyarlathotepEchoLink.resolveTarget(this);
        boolean linked = target != null && GuixuNyarlathotepLink.resolveHost(target) != null;
        return NyarlathotepEchoBroadcastProfile.calculate(lenses.storage.getStackInSlot(0).getCount(), linked, getTier());
    }

    /** Definition modifier 在 matchRecipe／扣料之前執行；普通機器配方不經此方法。 */
    public GTRecipe modifyRecipe(GTRecipe recipe) {
        if (recipe == null || recipe.recipeType != DShanhaiRecipeTypes.VOICE_OF_THE_FACELESS) return null;
        return ShanhaiNyarlathotepEchoRecipeScale.apply(recipe, currentProfile().selfFactor());
    }

    @Override public boolean beforeWorking(GTRecipe recipe) {
        paidFactor = Math.max(1, Math.min(5, recipe.data.getDouble(ShanhaiNyarlathotepEchoRecipeScale.FACTOR_KEY)));
        return super.beforeWorking(recipe);
    }
    @Override public void onWaiting() { stopBroadcast(); super.onWaiting(); }
    @Override public void afterWorking() { stopBroadcast(); super.afterWorking(); }
    @Override public void setWorkingEnabled(boolean enabled) { super.setWorkingEnabled(enabled); if (!enabled) stopBroadcast(); }
    @Override public void onLoad() {
        echoLoaded = true;
        super.onLoad();
        if (!isRemote()) broadcastSubscription = subscribeServerTick(broadcastSubscription, this::refreshDisplayAndSource);
    }
    @Override public void onUnload() {
        echoLoaded = false; stopBroadcast();
        if (broadcastSubscription != null) { broadcastSubscription.unsubscribe(); broadcastSubscription = null; }
        super.onUnload();
    }
    @Override public void onStructureInvalid() { stopBroadcast(); super.onStructureInvalid(); }
    @Override public void onPartUnload() { stopBroadcast(); super.onPartUnload(); }

    /** 只能由實際處理成功、進度前進的RecipeLogic呼叫true。 */
    public void refreshBroadcast(boolean paidTick) {
        if (!paidTick || !isWorkingEnabled() || !isFormed()) { stopBroadcast(); return; }
        broadcastActive = true;
        refreshDisplayAndSource();
    }
    private void refreshDisplayAndSource() {
        if (!(getLevel() instanceof ServerLevel level)) return;
        var target = ShanhaiNyarlathotepEchoLink.resolveTarget(this);
        boolean targetReady = target != null;
        boolean linked = targetReady && GuixuNyarlathotepLink.resolveHost(target) != null;
        int stored = lenses.storage.getStackInSlot(0).getCount();
        var next = NyarlathotepEchoBroadcastProfile.calculate(stored, linked, getTier());
        if (next.selfFactor() != selfFactor || targetReady != nyarlathotepReady || linked != guixuReady)
            getRecipeLogic().markLastRecipeDirty();
        nyarlathotepReady = targetReady; guixuReady = linked; storedLenses = stored; selfFactor = next.selfFactor();
        if (!echoLoaded || !isFormed() || !isWorkingEnabled() || !getRecipeLogic().isWorking()) stopBroadcast();
        int paidLensLimit = paidFactor <= 1 ? 16 : Math.min(64, 16 + (int) Math.floor((paidFactor - 1) * 12 + 1e-6));
        var actual = broadcastActive ? NyarlathotepEchoBroadcastProfile.calculate(Math.min(stored, paidLensLimit), linked, getTier()) : next;
        effectiveLenses = actual.effectiveLenses(); radius = actual.radius(); outputMultiplier = actual.outputMultiplier(); powerLevel = actual.powerLevel();
        remainingTicks = broadcastActive ? Math.max(0, getRecipeLogic().getDuration() - getRecipeLogic().getProgress()) : 0;
        if (broadcastActive && !actual.equals(registeredProfile)) {
            GravitationalWaveBroadcastManager.INSTANCE.addEchoSource(level, getPos(), actual.radius(), actual.powerLevel(),
                    actual.effectiveLenses(), actual.outputMultiplier());
            registeredProfile = actual;
        }
        if (getOffsetTimer() % 20 == 0) notifyBlockUpdate();
    }
    public void stopBroadcast() {
        if (getLevel() instanceof ServerLevel level && registeredProfile != null)
            GravitationalWaveBroadcastManager.INSTANCE.removeSource(level, getPos());
        registeredProfile = null;
        if (broadcastActive) { broadcastActive = false; remainingTicks = 0; notifyBlockUpdate(); }
    }

    @Override public void saveCustomPersistedData(CompoundTag tag, boolean forSyncing) {
        super.saveCustomPersistedData(tag, forSyncing);
        if (!forSyncing) tag.put("sh_echo_lenses", lenses.storage.serializeNBT());
    }
    @Override public void loadCustomPersistedData(CompoundTag tag) {
        super.loadCustomPersistedData(tag);
        if (tag.contains("sh_echo_lenses")) lenses.storage.deserializeNBT(tag.getCompound("sh_echo_lenses"));
    }

    @Override public Widget createUIWidget() {
        var group = new WidgetGroup(0, 0, 190, 125);
        group.setBackground(GuiTextures.BACKGROUND_INVERSE);
        group.addWidget(new LabelWidget(8, 7, "无貌者之音"));
        group.addWidget(new SlotWidget(lenses.storage, 0, 160, 5, true, true).setBackground(GuiTextures.SLOT)
                .setHoverTooltips(Component.translatable(LANG + "lens.tooltip.0"), Component.translatable(LANG + "lens.tooltip.1"),
                        Component.translatable(LANG + "lens.tooltip.2"), Component.translatable(LANG + "lens.tooltip.3")));
        group.addWidget(new DraggableScrollableWidgetGroup(4, 26, 182, 95).setBackground(getScreenTexture())
                .addWidget(new ComponentPanelWidget(4, 4, this::addDisplayText)
                        .textSupplier(getLevel().isClientSide ? null : this::addDisplayText).setMaxWidthLimit(172)));
        return group;
    }
    @Override public void addDisplayText(List<Component> text) {
        if (!isFormed()) { text.add(Component.translatable("gtceu.multiblock.invalid_structure")); return; }
        text.add(Component.translatable(LANG + "link." + (guixuReady ? "guixu" : nyarlathotepReady ? "nyarlathotep" : hasNyarlathotepLink() ? "waiting" : "standalone")));
        if (hasNyarlathotepLink()) text.add(Component.literal("§8" + linkedNyarlathotepDim + " " + getLinkedNyarlathotepPos().toShortString()));
        text.add(Component.translatable(LANG + "lenses", storedLenses, effectiveLenses, guixuReady ? 64 : 16));
        text.add(Component.translatable(LANG + "effect", radius, outputMultiplier, Math.min(50, powerLevel / 2)));
        text.add(Component.translatable(LANG + "cost", String.format(Locale.ROOT, "%.2f", selfFactor)));
        if (broadcastActive) text.add(Component.translatable(LANG + "paid", String.format(Locale.ROOT, "%.2f", paidFactor)));
        text.add(Component.translatable(LANG + "remaining", String.format(Locale.ROOT, "%.1f", remainingTicks / 20.0)));
        text.add(Component.translatable(LANG + (broadcastActive ? "active" : "inactive")));
        text.add(Component.translatable(LANG + "receiver"));
        text.add(Component.translatable(LANG + "local"));
        super.addDisplayText(text);
    }
    public static MultiblockMachineDefinition register() {
        return GTDishanhaiRegistration.REGISTRATE.multiblock(ID, ShanhaiNyarlathotepEchoMachine::new)
                .rotationState(RotationState.NON_Y_AXIS).tier(GTValues.UEV)
                .recipeTypes(DShanhaiRecipeTypes.VOICE_OF_THE_FACELESS)
                .recipeModifier((machine, recipe, params, result) -> ((ShanhaiNyarlathotepEchoMachine) machine).modifyRecipe(recipe))
                .pattern(ShanhaiNyarlathotepEchoStructure::createPattern)
                .appearanceBlock(ShanhaiNyarlathotepEchoStructure::appearanceBlock)
                .workableCasingRenderer(new ResourceLocation("gtlcore", "block/molecular_casing"),
                        new ResourceLocation("gtceu", "block/multiblock/fusion_reactor"))
                .tooltips(Component.translatable(LANG + "tooltip.0"), Component.translatable(LANG + "tooltip.1"),
                        Component.translatable(LANG + "tooltip.2"), Component.translatable(LANG + "tooltip.3"),
                        Component.translatable(LANG + "tooltip.4"), Component.translatable(LANG + "tooltip.5"))
                .register();
    }
}
