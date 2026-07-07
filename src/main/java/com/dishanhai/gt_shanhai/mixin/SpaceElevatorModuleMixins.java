package com.dishanhai.gt_shanhai.mixin;

import com.dishanhai.gt_shanhai.api.ShanhaiTextAPI;
import com.dishanhai.gt_shanhai.config.DShanhaiConfig;
import com.gregtechceu.gtceu.api.machine.IMachineBlockEntity;
import com.gregtechceu.gtceu.api.machine.MetaMachine;
import com.gregtechceu.gtceu.api.machine.multiblock.WorkableElectricMultiblockMachine;
import com.gregtechceu.gtceu.api.recipe.OverclockingLogic;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import com.gregtechceu.gtceu.api.recipe.RecipeHelper;
import com.gregtechceu.gtceu.api.recipe.logic.OCParams;
import com.gregtechceu.gtceu.api.recipe.logic.OCResult;
import com.gregtechceu.gtceu.common.data.GTRecipeModifiers;
import com.gtladd.gtladditions.api.machine.logic.GTLAddMultipleRecipesLogic;
import com.gtladd.gtladditions.common.machine.multiblock.controller.AdvancedSpaceElevatorModuleMachine;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import com.gregtechceu.gtceu.utils.FormattingUtil;
import org.gtlcore.gtlcore.api.machine.multiblock.IModularMachineModule;
import org.gtlcore.gtlcore.common.machine.multiblock.electric.SpaceElevatorMachine;
import org.gtlcore.gtlcore.common.machine.multiblock.electric.SpaceElevatorModuleMachine;
import org.gtlcore.gtlcore.common.data.GTLRecipeModifiers;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

public final class SpaceElevatorModuleMixins {

    private SpaceElevatorModuleMixins() {}

    @Mixin(value = AdvancedSpaceElevatorModuleMachine.class, remap = false)
    public static class AdvancedModule {

        @Inject(method = "createRecipeLogic", at = @At("HEAD"), cancellable = true, remap = false)
        @SuppressWarnings({"rawtypes", "unchecked"})
        private void shanhai$fixLogic(CallbackInfoReturnable<com.gregtechceu.gtceu.api.machine.trait.RecipeLogic> cir) {
            if (!DShanhaiConfig.COMMON.modulesWorkWithoutHost.get()) return;

            AdvancedSpaceElevatorModuleMachine self = (AdvancedSpaceElevatorModuleMachine) (Object) this;
            if (self.getHost() != null) return;
            cir.setReturnValue(new GTLAddMultipleRecipesLogic(self, (java.util.function.BiPredicate) null,
                    (java.util.function.Predicate<com.gregtechceu.gtceu.api.machine.feature.IRecipeLogicMachine>) machine -> true));
        }

        @Inject(method = "getMaxParallel", at = @At("RETURN"), cancellable = true)
        private void shanhai$fixParallel(CallbackInfoReturnable<Integer> cir) {
            AdvancedSpaceElevatorModuleMachine self = (AdvancedSpaceElevatorModuleMachine) (Object) this;
            var host = self.getHost();
            if (host != null) {
                int parallel = shanhai$advancedParallelFromHost(host.getCasingTier());
                cir.setReturnValue(parallel);
                return;
            }
            Integer value = cir.getReturnValue();
            if (DShanhaiConfig.COMMON.modulesWorkWithoutHost.get()
                    && (value == null || value <= 0)) {
                cir.setReturnValue(64);
                return;
            }
            if (value == null || value < 1) {
                cir.setReturnValue(1);
            }
        }

        private static int shanhai$advancedParallelFromHost(int casingTier) {
            int tier = Math.max(1, casingTier);
            double value = Math.pow(8.0D, tier - 1);
            if (value >= Integer.MAX_VALUE) return Integer.MAX_VALUE;
            return Math.max(1, (int) value);
        }

        @Inject(method = "addDisplayText", at = @At("RETURN"))
        private void shanhai$fixDisplay(List<Component> textList, CallbackInfo ci) {
            if (!DShanhaiConfig.COMMON.modulesWorkWithoutHost.get()) return;
            textList.removeIf(c -> c.getString().contains("未连接"));
            boolean hasIndependent = false;
            for (Component c : textList) {
                if (c.getString().contains("独立运行")) {
                    hasIndependent = true;
                    break;
                }
            }
            if (!hasIndependent) textList.add(ShanhaiTextAPI.ultimateRainbow("◆ 独立运行中"));
            textList.add(ShanhaiTextAPI.inline("{bodySilver}解除模块对主机的依赖关系，允许其在无中枢联动的情况下独立执行任务。{/}"));
            textList.add(ShanhaiTextAPI.inline("{bodySilver}切断检测通道，绕过权限验证——模块将不再等待主机响应，而是直接读取本地世线缓存并自主决策。{/}"));
            textList.add(ShanhaiTextAPI.inline("{bodySilver}警告：独立运行会丧失协同增益，但可获得{/}{ultimateRainbow}绝对的行动自由{/}{bodySilver}。{/}"));
        }
    }

    @Mixin(value = SpaceElevatorModuleMachine.class, remap = false)
    public abstract static class TimeModifierRemove extends WorkableElectricMultiblockMachine {

        protected TimeModifierRemove(IMachineBlockEntity holder, Object... args) {
            super(holder, args);
        }

        @Overwrite
        public static GTRecipe recipeModifier(MetaMachine machine, GTRecipe recipe, OCParams params, OCResult result) {
            if (!(machine instanceof SpaceElevatorModuleMachine self)) return null;

            int speedTier = shanhai$speedTier(self);
            if (speedTier < 1) return null;

            int parallelTier = shanhai$parallelTier(self);
            if (shanhai$requiresModuleTier(self)
                    && recipe.data.getInt("SEPMTier") > parallelTier) {
                return null;
            }

            GTRecipe modified = GTLRecipeModifiers.reduction(machine, recipe, 1.0D,
                    Math.pow(0.8D, speedTier - 1));
            if (modified == null) return null;

            var pair = GTRecipeModifiers.accurateParallel(machine, modified,
                    shanhai$parallelFromTier(parallelTier, 4.0D), false);
            if (pair == null || pair.getFirst() == null) return null;

            return RecipeHelper.applyOverclock(OverclockingLogic.NON_PERFECT_OVERCLOCK_SUBTICK,
                    pair.getFirst(), self.getOverclockVoltage(), params, result);
        }

        @Overwrite
        public boolean onWorking() {
            return super.onWorking();
        }

        private static int shanhai$baseParallel(SpaceElevatorModuleMachine self) {
            return shanhai$parallelFromTier(shanhai$parallelTier(self), 4.0D);
        }

        private static int shanhai$speedTier(SpaceElevatorModuleMachine self) {
            SpaceElevatorMachine host = self.getHost();
            if (host != null) {
                return Math.max(1, host.getTier() - 7);
            }
            int cachedTier = shanhai$spaceElevatorTierField(self);
            if (cachedTier > 0) return cachedTier;
            return DShanhaiConfig.COMMON.modulesWorkWithoutHost.get() ? 1 : 0;
        }

        private static int shanhai$parallelTier(SpaceElevatorModuleMachine self) {
            int tier = shanhai$hostCasingTier(self);
            if (tier <= 0) tier = shanhai$moduleTierField(self);
            return Math.max(1, tier);
        }

        private static int shanhai$hostCasingTier(SpaceElevatorModuleMachine self) {
            IModularMachineModule<?, ?> module = (IModularMachineModule<?, ?>) self;
            Object host = module.getHost();
            if (host instanceof SpaceElevatorMachine elevator) {
                return elevator.getCasingTier();
            }
            return 0;
        }

        private static int shanhai$moduleTierField(SpaceElevatorModuleMachine self) {
            try {
                var field = self.getClass().getDeclaredField("moduleTier");
                field.setAccessible(true);
                return field.getInt(self);
            } catch (Exception ignored) {
                return 0;
            }
        }

        private static int shanhai$spaceElevatorTierField(SpaceElevatorModuleMachine self) {
            try {
                var field = self.getClass().getDeclaredField("spaceElevatorTier");
                field.setAccessible(true);
                return field.getInt(self);
            } catch (Exception ignored) {
                return 0;
            }
        }

        private static boolean shanhai$requiresModuleTier(SpaceElevatorModuleMachine self) {
            try {
                var field = self.getClass().getDeclaredField("sepmTier");
                field.setAccessible(true);
                return field.getBoolean(self);
            } catch (Exception ignored) {
                return false;
            }
        }

        private static int shanhai$parallelFromTier(int tierValue, double base) {
            int tier = Math.max(1, tierValue);
            double value = Math.pow(base, tier - 1);
            if (value >= Integer.MAX_VALUE) return Integer.MAX_VALUE;
            return Math.max(1, (int) value);
        }

        @Inject(method = "addDisplayText", at = @At("RETURN"), remap = false)
        private void shanhai$fixBaseModuleParallelDisplay(List<Component> textList, CallbackInfo ci) {
            SpaceElevatorModuleMachine self = (SpaceElevatorModuleMachine) (Object) this;
            int connectedIndex = shanhai$findSpaceElevatorConnectionLine(textList);
            if (connectedIndex <= 0) return;

            int parallelIndex = shanhai$findParallelLineBefore(textList, connectedIndex);
            if (parallelIndex < 0) return;

            int tier = shanhai$hostCasingTier(self);
            if (tier <= 0) tier = shanhai$moduleTierField(self);
            int parallel = shanhai$parallelFromTier(tier, 4.0D);

            textList.set(parallelIndex, Component.translatable("gtceu.multiblock.parallel",
                    Component.literal(FormattingUtil.formatNumbers(parallel)).withStyle(ChatFormatting.DARK_PURPLE))
                    .withStyle(ChatFormatting.GRAY));

            int durationIndex = shanhai$findDurationLineAfter(textList, connectedIndex);
            if (durationIndex >= 0) {
                int speedTier = shanhai$speedTier(self);
                textList.set(durationIndex, Component.translatable("gtceu.machine.duration_multiplier.tooltip",
                        FormattingUtil.formatPercent(Math.pow(0.8D, speedTier - 1))));
            }
        }

        private static int shanhai$findSpaceElevatorConnectionLine(List<Component> textList) {
            for (int i = 0; i < textList.size(); i++) {
                String key = shanhai$translationKey(textList.get(i));
                if ("tooltip.gtlcore.space_elevator_connected".equals(key)
                        || "tooltip.gtlcore.space_elevator_not_connected".equals(key)) {
                    return i;
                }
            }
            return -1;
        }

        private static int shanhai$findParallelLineBefore(List<Component> textList, int endIndex) {
            for (int i = endIndex - 1; i >= 0; i--) {
                if ("gtceu.multiblock.parallel".equals(shanhai$translationKey(textList.get(i)))) {
                    return i;
                }
            }
            return -1;
        }

        private static int shanhai$findDurationLineAfter(List<Component> textList, int startIndex) {
            for (int i = startIndex + 1; i < textList.size(); i++) {
                if ("gtceu.machine.duration_multiplier.tooltip".equals(shanhai$translationKey(textList.get(i)))) {
                    return i;
                }
            }
            return -1;
        }

        private static String shanhai$translationKey(Component component) {
            if (component.getContents() instanceof TranslatableContents contents) {
                return contents.getKey();
            }
            return "";
        }
    }
}
