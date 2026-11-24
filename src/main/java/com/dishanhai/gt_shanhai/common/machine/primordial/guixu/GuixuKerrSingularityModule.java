package com.dishanhai.gt_shanhai.common.machine.primordial.guixu;

import com.dishanhai.gt_shanhai.common.item.DShanhaiItems;
import com.dishanhai.gt_shanhai.common.machine.primordial.PrimordialOmegaEngineModuleBase;
import com.gregtechceu.gtceu.api.capability.recipe.IO;
import com.gregtechceu.gtceu.api.gui.GuiTextures;
import com.gregtechceu.gtceu.api.machine.IMachineBlockEntity;
import com.gregtechceu.gtceu.api.machine.MetaMachine;
import com.gregtechceu.gtceu.api.machine.TickableSubscription;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import com.gregtechceu.gtceu.common.data.GTRecipeTypes;
import com.lowdragmc.lowdraglib.gui.texture.GuiTextureGroup;
import com.lowdragmc.lowdraglib.gui.texture.TextTexture;
import com.lowdragmc.lowdraglib.gui.widget.ButtonWidget;
import com.lowdragmc.lowdraglib.gui.widget.Widget;
import com.lowdragmc.lowdraglib.gui.widget.WidgetGroup;
import it.unimi.dsi.fastutil.objects.Reference2ObjectOpenHashMap;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.Containers;
import net.minecraft.world.item.ItemStack;

import java.util.List;

import static com.dishanhai.gt_shanhai.common.machine.primordial.guixu.GuixuKerrOrbit.*;

/** A direct Guixu module. The selected injection direction applies to the next batch. */
public final class GuixuKerrSingularityModule extends PrimordialOmegaEngineModuleBase {
    private static final String TEXT = "gt_shanhai.machine.guixu_kerr_singularity_engine.";
    private static final String NBT = "ShanhaiKerrOrbit";
    private static final long EU_PER_STEP = 2_097_152L;

    private Track injection = Track.INNER;
    private boolean finishedOnly;
    private Batch batch;
    private TickableSubscription orbitSubscription;
    private GTRecipe inputRecipe;
    private GTRecipe outputRecipe;
    private String status = "idle";

    public GuixuKerrSingularityModule(IMachineBlockEntity holder, Object... args) {
        super(holder, args);
    }

    @Override
    public boolean isValidHost(MetaMachine machine) {
        return machine instanceof GuixuVisionCoreMachine && super.isValidHost(machine);
    }

    @Override
    public boolean keepSubscribing() { return false; }

    @Override
    protected boolean showsModuleAndThreadSlots() { return false; }

    @Override
    protected boolean showsStandaloneThreadSlot() { return false; }

    @Override
    public void onStructureFormed() {
        super.onStructureFormed();
        if (!isRemote()) orbitSubscription = subscribeServerTick(orbitSubscription, this::orbitTick);
    }

    @Override
    public void onStructureInvalid() {
        stopOrbitTick();
        super.onStructureInvalid();
    }

    @Override
    public void onUnload() {
        stopOrbitTick();
        super.onUnload();
    }

    @Override
    public void onMachineRemoved() {
        if (!isRemote() && batch != null && getLevel() != null) {
            ItemStack recovered = productStack(batch.complete() ? batch.product() : Product.PREFORM);
            batch = null;
            var pos = getPos();
            Containers.dropItemStack(getLevel(), pos.getX() + .5, pos.getY() + .5, pos.getZ() + .5, recovered);
        }
        super.onMachineRemoved();
    }

    private void stopOrbitTick() {
        if (orbitSubscription == null) return;
        orbitSubscription.unsubscribe();
        orbitSubscription = null;
    }

    private GuixuVisionCoreMachine host() {
        var level = getLevel();
        if (level == null) return null;
        return getHost() instanceof GuixuVisionCoreMachine guixu && guixu.isFormed()
                && guixu.getLevel() == level && level.hasChunkAt(guixu.getPos())
                && guixu.getModuleSet().contains(this) ? guixu : null;
    }

    int orbitProgress(Track track) {
        return batch != null && batch.track() == track ? 1 + batch.steps() : 0;
    }

    private void orbitTick() {
        if (getOffsetTimer() % STEP_TICKS != 0) return;
        status = "paused";
        GuixuVisionCoreMachine host = host();
        if (host == null || !getRecipeLogic().isWorkingEnabled()
                || !host.getRecipeLogic().isWorkingEnabled()) return;
        GuixuPenroseContainmentModule fieldModule = host.getEffectiveContainment();
        GuixuContainmentField field = fieldModule == null ? null : fieldModule.field();
        if (!canProcess(field, host.isContainmentLocked())) return;

        if (batch != null && batch.complete()) {
            status = "output_full";
            if (outputRecipe == null) outputRecipe = ioRecipe(productStack(batch.product()), IO.OUT);
            if (transfer(outputRecipe, IO.OUT)) {
                host.recordKerrRecovery(batch.track());
                batch = null;
                outputRecipe = null;
                status = "idle";
                markDirty();
            }
            return;
        }
        if (batch == null) {
            status = "idle";
            if (finishedOnly) {
                Product projected = nextBatch(host, field).product();
                if (projected != Product.CORE && projected != Product.LAMINATE) {
                    status = "quality_wait";
                    return;
                }
            }
            if (inputRecipe == null) inputRecipe = ioRecipe(DShanhaiItems.KERR_ORBIT_BLANK.asStack(), IO.IN);
            if (!transfer(inputRecipe, IO.IN)) return;
            batch = Batch.start(injection);
            host.accreteKerrSpin(injection);
            markDirty();
        }

        status = "no_energy";
        if (!host.getWirelessNetworkEnergyHandler().consumeEnergy(-EU_PER_STEP)) return;
        batch = batch.advance(host.getKerrSpin(), field.stability());
        status = "orbiting";
        markDirty();
    }

    private boolean transfer(GTRecipe recipe, IO io) {
        var ingredients = io == IO.IN ? recipe.inputs : recipe.outputs;
        return recipe.matchRecipeContents(io, this, ingredients, false).isSuccess()
                && recipe.handleRecipeIO(io, this, new Reference2ObjectOpenHashMap<>());
    }

    private static GTRecipe ioRecipe(ItemStack stack, IO io) {
        var builder = GTRecipeTypes.DUMMY_RECIPES.recipeBuilder("guixu_kerr_orbit_transfer").duration(1);
        if (io == IO.IN) builder.inputItems(stack);
        else builder.outputItems(stack);
        return builder.buildRawRecipe();
    }

    private static ItemStack productStack(Product product) {
        return switch (product) {
            case PREFORM -> DShanhaiItems.KERR_ORBIT_PREFORM.asStack();
            case CORE -> DShanhaiItems.KERR_ORIENTED_CORE.asStack();
            case LAMINATE -> DShanhaiItems.KERR_SPACETIME_LAMINATE.asStack();
            case FRACTURED_CORE -> DShanhaiItems.KERR_FRACTURED_CORE.asStack();
        };
    }

    @Override
    public Widget createUIWidget() {
        Widget widget = super.createUIWidget();
        if (widget instanceof WidgetGroup page) {
            int x = page.getSize().width - 58;
            addTrackButton(page, x, 69, Track.INNER);
            addTrackButton(page, x, 87, Track.OUTER);
            var quality = Component.translatable(TEXT + "quality");
            page.addWidget(new ButtonWidget(x, 105, 54, 16,
                    new GuiTextureGroup(GuiTextures.BUTTON, new TextTexture(quality.getString())), click -> {
                        if (isRemote()) return;
                        finishedOnly = !finishedOnly;
                        markDirty();
                    }).setHoverTooltips(Component.translatable(TEXT + "quality.tooltip")));
        }
        return widget;
    }

    private void addTrackButton(WidgetGroup page, int x, int y, Track track) {
        var label = trackName(track);
        page.addWidget(new ButtonWidget(x, y, 54, 16,
                new GuiTextureGroup(GuiTextures.BUTTON, new TextTexture(label.getString())), click -> {
                    if (isRemote() || injection == track) return;
                    injection = track;
                    markDirty();
                }).setHoverTooltips(label));
    }

    private static Component trackName(Track track) {
        return Component.translatable(TEXT + (track == Track.INNER ? "inner" : "outer"));
    }

    private Batch nextBatch(GuixuVisionCoreMachine host, GuixuContainmentField field) {
        return Batch.start(injection).forecast(accrete(host.getKerrSpin(), injection), field.stability());
    }

    @Override
    public void addDisplayText(List<Component> lines) {
        lines.add(Component.translatable(TEXT + "name"));
        lines.add(Component.translatable(TEXT + "injection", trackName(injection)));
        if (finishedOnly) lines.add(Component.translatable(TEXT + "quality.enabled"));
        GuixuVisionCoreMachine host = host();
        if (host == null) {
            lines.add(Component.translatable(TEXT + "no_host"));
            return;
        }
        lines.add(Component.translatable(TEXT + "spin", host.getKerrSpin()));
        GuixuPenroseContainmentModule containment = host.getEffectiveContainment();
        GuixuContainmentField field = containment == null ? null : containment.field();
        if (field != null && field.isOpen()) {
            lines.add(Component.translatable(TEXT + "stability", (int) field.stability()));
        }
        lines.add(Component.translatable(TEXT + "status." + status));
        if (batch != null) {
            lines.add(Component.translatable(TEXT + "orbit", trackName(batch.track()), batch.steps(), STEPS));
        }
        if (canProcess(field, host.isContainmentLocked())) {
            Batch projected = batch == null ? nextBatch(host, field) : batch.forecast(host.getKerrSpin(), field.stability());
            lines.add(Component.translatable(TEXT + "preview", productStack(projected.product()).getHoverName()));
            lines.add(Component.translatable(TEXT + "margin", Math.round(projected.recoveryMargin() * 100)));
        }
    }

    @Override
    public void saveCustomPersistedData(CompoundTag tag, boolean forSyncing) {
        super.saveCustomPersistedData(tag, forSyncing);
        if (forSyncing) return;
        CompoundTag orbit = new CompoundTag();
        orbit.putBoolean("OuterInjection", injection == Track.OUTER);
        orbit.putBoolean("FinishedOnly", finishedOnly);
        if (batch != null) {
            orbit.putBoolean("Outer", batch.track() == Track.OUTER);
            orbit.putInt("Steps", batch.steps());
            orbit.putFloat("Exposure", batch.exposure());
            orbit.putFloat("Deviation", batch.deviation());
        }
        tag.put(NBT, orbit);
    }

    @Override
    public void loadCustomPersistedData(CompoundTag tag) {
        super.loadCustomPersistedData(tag);
        if (!tag.contains(NBT)) return;
        CompoundTag orbit = tag.getCompound(NBT);
        injection = orbit.getBoolean("OuterInjection") ? Track.OUTER : Track.INNER;
        finishedOnly = orbit.getBoolean("FinishedOnly");
        batch = orbit.contains("Steps") ? new Batch(orbit.getBoolean("Outer") ? Track.OUTER : Track.INNER,
                Math.clamp(orbit.getInt("Steps"), 0, STEPS), orbit.getFloat("Exposure"), orbit.getFloat("Deviation")) : null;
        outputRecipe = null;
    }
}
