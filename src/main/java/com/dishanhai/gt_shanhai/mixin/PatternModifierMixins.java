package com.dishanhai.gt_shanhai.mixin;

import com.lowdragmc.lowdraglib.gui.modular.ModularUI;
import com.lowdragmc.lowdraglib.gui.widget.LabelWidget;
import com.lowdragmc.lowdraglib.gui.widget.WidgetGroup;
import org.gtlcore.gtlcore.common.item.PatternModifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = PatternModifier.class, remap = false)
public class PatternModifierMixins {

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger("gt_shanhai:pattern");

    @Shadow(remap = false) private int Ae2PatternGeneratorScale;
    @Shadow(remap = false) private int Ae2PatternGeneratorDivScale;
    @Shadow(remap = false) private long Ae2PatternGeneratorMaxItemStack;
    @Shadow(remap = false) private long Ae2PatternGeneratorMaxFluidStack;

    @Unique private int ae2PatternGeneratorOutputScale = 1;
    @Unique private int ae2PatternGeneratorOutputDiv = 1;

    @Inject(method = "getNewPatternItemStack", cancellable = true, at = @At("HEAD"), remap = false, require = 0)
    private void logEntry(net.minecraft.server.level.ServerPlayer player,
                          net.minecraft.world.item.ItemStack patternStack,
                          CallbackInfoReturnable<net.minecraft.world.item.ItemStack> cir) {
        LOG.info("getNewPatternItemStack called: scale={} divScale={} outputScale={} outputDiv={} maxItem={} maxFluid={}",
                Ae2PatternGeneratorScale, Ae2PatternGeneratorDivScale,
                ae2PatternGeneratorOutputScale, ae2PatternGeneratorOutputDiv,
                Ae2PatternGeneratorMaxItemStack, Ae2PatternGeneratorMaxFluidStack);
    }

    @Inject(method = "getNewPatternItemStack", cancellable = true, at = @At("RETURN"), remap = false, require = 0)
    private void applyOutputScale(net.minecraft.server.level.ServerPlayer player,
                                  net.minecraft.world.item.ItemStack patternStack,
                                  CallbackInfoReturnable<net.minecraft.world.item.ItemStack> cir) {
        if (ae2PatternGeneratorOutputScale <= 1 && ae2PatternGeneratorOutputDiv <= 1) return;
        var result = cir.getReturnValue();
        if (result == null || result.isEmpty()) return;

        try {
            var aePattern = org.gtlcore.gtlcore.api.item.tool.ae2.patternTool.Ae2BaseProcessingPatternHelper
                    .decodeToAEProcessingPattern(result, player);
            if (aePattern == null) return;

            var outputs = aePattern.getOutputs();
            if (outputs == null || outputs.length == 0) return;

            var newOutputs = new appeng.api.stacks.GenericStack[outputs.length];
            for (int i = 0; i < outputs.length; i++) {
                if (outputs[i] == null) continue;
                long amount = outputs[i].amount();
                if (ae2PatternGeneratorOutputScale > 1) amount *= ae2PatternGeneratorOutputScale;
                if (ae2PatternGeneratorOutputDiv > 1) amount /= ae2PatternGeneratorOutputDiv;
                if (amount > Ae2PatternGeneratorMaxItemStack && outputs[i].what() instanceof appeng.api.stacks.AEItemKey) {
                    amount = Ae2PatternGeneratorMaxItemStack;
                }
                if (amount > Ae2PatternGeneratorMaxFluidStack && outputs[i].what() instanceof appeng.api.stacks.AEFluidKey) {
                    amount = Ae2PatternGeneratorMaxFluidStack;
                }
                LOG.info("applyOutputScale: output[{}] {} -> {}", i, outputs[i].amount(), amount);
                newOutputs[i] = new appeng.api.stacks.GenericStack(outputs[i].what(), amount);
            }

            var newStack = appeng.api.crafting.PatternDetailsHelper.encodeProcessingPattern(
                    aePattern.getSparseInputs(), newOutputs);
            if (newStack != null && !newStack.isEmpty()) {
                cir.setReturnValue(newStack);
                LOG.info("applyOutputScale: SUCCESS");
            }
        } catch (Exception e) {
            LOG.error("applyOutputScale: EXCEPTION: {}", e.getMessage(), e);
        }
    }

    @Inject(method = "createUI", at = @At("RETURN"), remap = false, require = 0)
    private void addOutputScaleUI(com.lowdragmc.lowdraglib.gui.factory.HeldItemUIFactory.HeldItemHolder holder,
                                  net.minecraft.world.entity.player.Player player,
                                  CallbackInfoReturnable<ModularUI> cir) {
        ModularUI ui = cir.getReturnValue();
        if (ui == null) return;
        WidgetGroup group = ui.mainGroup;
        if (group == null) return;

        try {
            group.addWidget(new LabelWidget(8, 93, "§e输出×:"));

            var inputMul = new com.gregtechceu.gtceu.integration.ae2.gui.widget.AETextInputButtonWidget(62, 91, 48, 12);
            inputMul.setText(String.valueOf(ae2PatternGeneratorOutputScale));
            inputMul.setOnConfirm(text -> ae2PatternGeneratorOutputScale = parseScaleInput(text));
            inputMul.setButtonTooltips(net.minecraft.network.chat.Component.literal("§7输出额外倍率"));
            group.addWidget(inputMul);

            group.addWidget(new LabelWidget(114, 93, "÷:"));

            var inputDiv = new com.gregtechceu.gtceu.integration.ae2.gui.widget.AETextInputButtonWidget(134, 91, 48, 12);
            inputDiv.setText(String.valueOf(ae2PatternGeneratorOutputDiv));
            inputDiv.setOnConfirm(text -> ae2PatternGeneratorOutputDiv = parseScaleInput(text));
            inputDiv.setButtonTooltips(net.minecraft.network.chat.Component.literal("§7输出额外除数"));
            group.addWidget(inputDiv);
        } catch (Exception e) {
            LOG.warn("UI error: {}", e.getMessage());
        }
    }

    private static int parseScaleInput(String text) {
        try {
            int value = Integer.parseInt(text);
            if (value < 1) return 1;
            return Math.min(value, 999999);
        } catch (Exception e) {
            LOG.warn("scale input error: {}", e.getMessage());
            return 1;
        }
    }
}
