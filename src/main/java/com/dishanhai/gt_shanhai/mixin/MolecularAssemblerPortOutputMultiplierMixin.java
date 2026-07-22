package com.dishanhai.gt_shanhai.mixin;

import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.crafting.ICraftingProvider;

import com.dishanhai.gt_shanhai.common.item.PatternRecipeTypeHelper;
import com.dishanhai.gt_shanhai.common.item.VirtualPatternEncodingHelper;
import com.dishanhai.gt_shanhai.common.machine.primordial.PrimordialOmegaEngineMachine;
import com.dishanhai.gt_shanhai.common.machine.primordial.PrimordialOmegaEngineModuleBase;
import com.google.common.collect.BiMap;
import com.gregtechceu.gtceu.api.gui.fancy.ConfiguratorPanel;
import com.gregtechceu.gtceu.api.gui.fancy.IFancyConfigurator;
import com.lowdragmc.lowdraglib.gui.texture.IGuiTexture;
import com.lowdragmc.lowdraglib.gui.texture.TextTexture;
import com.lowdragmc.lowdraglib.gui.widget.ButtonWidget;
import com.lowdragmc.lowdraglib.gui.widget.LabelWidget;
import com.lowdragmc.lowdraglib.gui.widget.Widget;
import com.lowdragmc.lowdraglib.gui.widget.WidgetGroup;
import com.lowdragmc.lowdraglib.syncdata.annotation.DescSynced;
import com.lowdragmc.lowdraglib.syncdata.annotation.Persisted;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import org.gtlcore.gtlcore.common.machine.multiblock.part.ae.MEMolecularAssemblerIOPartMachine;
import org.gtlcore.gtlcore.integration.lowdragmc.misc.MutableItemTransferList;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Mixin(value = MEMolecularAssemblerIOPartMachine.class, priority = 900, remap = false)
public abstract class MolecularAssemblerPortOutputMultiplierMixin {

    @Shadow
    @Final
    private BiMap<IPatternDetails, Integer> patternSlotMap;

    @Shadow
    @Final
    private MutableItemTransferList mutableItemTransferList;

    @Unique
    @DescSynced
    @Persisted
    private boolean gtShanhai$outputMultiplierModeEnabled;

    @Unique
    @DescSynced
    @Persisted
    private int gtShanhai$cachedHostOutputMultiplier = 1;

    @Unique
    private final Map<IPatternDetails, IPatternDetails> gtShanhai$effectiveToSource =
            new IdentityHashMap<>();

    @Unique
    private final Map<CompoundTag, IPatternDetails> gtShanhai$effectiveDefinitionToSource =
            new HashMap<>();

    @Unique
    private List<IPatternDetails> gtShanhai$sourceSnapshot = List.of();

    @Unique
    private List<IPatternDetails> gtShanhai$effectivePatternCache = List.of();

    @Unique
    private int gtShanhai$cachedViewMultiplier = -1;

    @Inject(method = "getAvailablePatterns", at = @At("RETURN"), cancellable = true)
    private void gtShanhai$publishOutputMultiplierView(
            CallbackInfoReturnable<List<IPatternDetails>> cir) {
        if (!gtShanhai$outputMultiplierModeEnabled || gtShanhai$cachedHostOutputMultiplier <= 1) {
            gtShanhai$invalidateEffectivePatterns();
            return;
        }

        List<IPatternDetails> sources = cir.getReturnValue();
        if (sources == null || sources.isEmpty()) {
            gtShanhai$invalidateEffectivePatterns();
            return;
        }
        if (gtShanhai$cachedViewMultiplier == gtShanhai$cachedHostOutputMultiplier
                && gtShanhai$sameSources(sources)) {
            cir.setReturnValue(gtShanhai$effectivePatternCache);
            return;
        }

        MEMolecularAssemblerIOPartMachine self = (MEMolecularAssemblerIOPartMachine) (Object) this;
        if (self.getLevel() == null) return;

        gtShanhai$effectiveToSource.clear();
        gtShanhai$effectiveDefinitionToSource.clear();
        List<IPatternDetails> effectivePatterns = new ArrayList<>(sources.size());
        for (IPatternDetails source : sources) {
            IPatternDetails effective = gtShanhai$createEffectivePattern(self, source);
            if (effective == null) effective = source;
            effectivePatterns.add(effective);
            if (effective != source) {
                gtShanhai$effectiveToSource.put(effective, source);
                gtShanhai$effectiveDefinitionToSource.put(gtShanhai$definitionTag(effective), source);
            }
        }
        gtShanhai$sourceSnapshot = List.copyOf(sources);
        gtShanhai$effectivePatternCache = List.copyOf(effectivePatterns);
        gtShanhai$cachedViewMultiplier = gtShanhai$cachedHostOutputMultiplier;
        cir.setReturnValue(gtShanhai$effectivePatternCache);
    }

    @ModifyVariable(method = "pushPattern", at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private IPatternDetails gtShanhai$restoreSourcePatternBeforePush(IPatternDetails patternDetails) {
        return gtShanhai$sourcePattern(patternDetails);
    }

    @Unique
    private IPatternDetails gtShanhai$sourcePattern(IPatternDetails patternDetails) {
        if (patternDetails == null) return null;
        IPatternDetails source = gtShanhai$effectiveToSource.get(patternDetails);
        if (source != null) return source;
        source = gtShanhai$effectiveDefinitionToSource.get(gtShanhai$definitionTag(patternDetails));
        return source == null ? patternDetails : source;
    }

    @Inject(method = "update", at = @At("TAIL"))
    private void gtShanhai$autoDetectHostMultiplier(CallbackInfo ci) {
        MEMolecularAssemblerIOPartMachine self = (MEMolecularAssemblerIOPartMachine) (Object) this;
        if (!gtShanhai$outputMultiplierModeEnabled || self.isRemote()
                || self.getOffsetTimer() % 40L != 0L) return;
        int detected = gtShanhai$resolveHostOutputMultiplier(self);
        if (detected == gtShanhai$cachedHostOutputMultiplier) return;
        gtShanhai$cachedHostOutputMultiplier = detected;
        gtShanhai$invalidateEffectivePatterns();
        self.markDirty();
        ICraftingProvider.requestUpdate(self.getMainNode());
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void gtShanhai$invalidateAfterInit(Set<net.minecraft.core.BlockPos> proxies, CallbackInfo ci) {
        gtShanhai$invalidateEffectivePatterns();
    }

    @Inject(method = "onPatternChange", at = @At("TAIL"))
    private void gtShanhai$invalidateAfterPatternChange(int index, CallbackInfo ci) {
        gtShanhai$invalidateEffectivePatterns();
    }

    @Inject(method = "clear", at = @At("TAIL"))
    private void gtShanhai$invalidateAfterClear(CallbackInfo ci) {
        gtShanhai$invalidateEffectivePatterns();
    }

    @Inject(method = "attachConfigurators", at = @At("TAIL"))
    private void gtShanhai$attachOutputMultiplierConfigurator(
            ConfiguratorPanel configuratorPanel, CallbackInfo ci) {
        configuratorPanel.attachConfigurators(new OutputMultiplierConfigurator());
    }

    @Unique
    private IPatternDetails gtShanhai$createEffectivePattern(
            MEMolecularAssemblerIOPartMachine self, IPatternDetails source) {
        Integer slot = patternSlotMap.get(source);
        ItemStack stack = slot != null && slot >= 0 && slot < mutableItemTransferList.getSlots()
                ? mutableItemTransferList.getStackInSlot(slot)
                : source.getDefinition().toStack();
        return VirtualPatternEncodingHelper.rewritePatternOutputMultiplier(
                source, self.getLevel(), PatternRecipeTypeHelper.readRecipeTypeId(stack),
                gtShanhai$cachedHostOutputMultiplier);
    }

    @Unique
    private boolean gtShanhai$sameSources(List<IPatternDetails> sources) {
        if (sources.size() != gtShanhai$sourceSnapshot.size()) return false;
        for (int i = 0; i < sources.size(); i++) {
            if (sources.get(i) != gtShanhai$sourceSnapshot.get(i)) return false;
        }
        return true;
    }

    @Unique
    private static CompoundTag gtShanhai$definitionTag(IPatternDetails pattern) {
        return pattern == null ? new CompoundTag() : pattern.getDefinition().toStack().serializeNBT();
    }

    @Unique
    private static int gtShanhai$resolveHostOutputMultiplier(
            MEMolecularAssemblerIOPartMachine self) {
        int multiplier = 1;
        for (var controller : self.getControllers()) {
            if (controller instanceof PrimordialOmegaEngineModuleBase module) {
                multiplier = Math.max(multiplier, module.getHostOutputMultiplier());
            } else if (controller instanceof PrimordialOmegaEngineMachine host) {
                multiplier = Math.max(multiplier, host.getMountedOutputMultiplier());
            }
        }
        return Math.max(1, Math.min(1000, multiplier));
    }

    @Unique
    private void gtShanhai$toggleOutputMultiplierMode() {
        gtShanhai$outputMultiplierModeEnabled = !gtShanhai$outputMultiplierModeEnabled;
        if (gtShanhai$outputMultiplierModeEnabled) {
            gtShanhai$refreshOutputMultiplierNow();
            return;
        }
        MEMolecularAssemblerIOPartMachine self = (MEMolecularAssemblerIOPartMachine) (Object) this;
        gtShanhai$invalidateEffectivePatterns();
        self.markDirty();
        ICraftingProvider.requestUpdate(self.getMainNode());
    }

    @Unique
    private void gtShanhai$refreshOutputMultiplierNow() {
        MEMolecularAssemblerIOPartMachine self = (MEMolecularAssemblerIOPartMachine) (Object) this;
        if (self.isRemote()) return;
        gtShanhai$cachedHostOutputMultiplier = gtShanhai$resolveHostOutputMultiplier(self);
        gtShanhai$invalidateEffectivePatterns();
        self.markDirty();
        ICraftingProvider.requestUpdate(self.getMainNode());
    }

    @Unique
    private void gtShanhai$invalidateEffectivePatterns() {
        gtShanhai$effectiveToSource.clear();
        gtShanhai$effectiveDefinitionToSource.clear();
        gtShanhai$sourceSnapshot = List.of();
        gtShanhai$effectivePatternCache = List.of();
        gtShanhai$cachedViewMultiplier = -1;
    }

    @Unique
    private final class OutputMultiplierConfigurator implements IFancyConfigurator {

        @Override
        public Component getTitle() {
            return Component.translatable("gt_shanhai.machine.molecular_port.multiplier.title");
        }

        @Override
        public IGuiTexture getIcon() {
            return new TextTexture("x");
        }

        @Override
        public Widget createConfigurator() {
            WidgetGroup group = new WidgetGroup(0, 0, 160, 68);
            group.addWidget(new LabelWidget(6, 4, () -> gtShanhai$outputMultiplierModeEnabled
                    ? "§a" + Component.translatable(
                            "gt_shanhai.machine.recipe_type_pattern_buffer.foa_mode.enabled").getString()
                    : "§7" + Component.translatable(
                            "gt_shanhai.machine.recipe_type_pattern_buffer.foa_mode.disabled").getString()));
            group.addWidget(new LabelWidget(6, 24, () -> Component.translatable(
                    "gt_shanhai.machine.molecular_port.multiplier.current",
                    gtShanhai$cachedHostOutputMultiplier).getString()));
            group.addWidget(new ButtonWidget(6, 46, 70, 16,
                    new TextTexture("§f" + Component.translatable(
                            "gt_shanhai.machine.molecular_port.multiplier.toggle").getString(), -1),
                    click -> gtShanhai$toggleOutputMultiplierMode()));
            group.addWidget(new ButtonWidget(82, 46, 72, 16,
                    new TextTexture("§e" + Component.translatable(
                            "gt_shanhai.machine.molecular_port.multiplier.refresh").getString(), -1),
                    click -> gtShanhai$refreshOutputMultiplierNow()));
            return group;
        }
    }
}
