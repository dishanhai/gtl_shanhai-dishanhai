package com.dishanhai.gt_shanhai.mixin;

import com.dishanhai.gt_shanhai.client.JeiCopyShortcutHelper;
import mezz.jei.common.Internal;
import mezz.jei.common.input.IInternalKeyMappings;
import mezz.jei.gui.input.IUserInputHandler;
import mezz.jei.gui.input.UserInput;
import mezz.jei.gui.input.handlers.SameElementInputHandler;
import mezz.jei.gui.util.CommandUtil;
import mezz.jei.gui.util.GiveAmount;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Optional;

@Mixin(targets = "mezz.jei.gui.recipes.RecipeLayoutWithButtons$RecipeLayoutUserInputHandler", remap = false)
public class JEIRecipeGuiInfinityCellMixin {
    private static final Logger GTSHANHAI_JEI_LOGGER = LogManager.getLogger("gt_shanhai_jei_infinity");
    
    static {
        LogManager.getLogger("gt_shanhai_jei_infinity").info("[STATIC] JEIRecipeGuiInfinityCellMixin class loaded");
    }

    @Shadow
    @Final
    private mezz.jei.api.gui.IRecipeLayoutDrawable<?> recipeLayout;

    @Inject(method = "handleUserInput", at = @At("HEAD"), cancellable = true, remap = false)
    private void gtShanhai$handleInfinityCellCheat(Screen screen, UserInput input, IInternalKeyMappings keyBindings,
            CallbackInfoReturnable<Optional<IUserInputHandler>> cir) {
        if (!recipeLayout.isMouseOver(input.getMouseX(), input.getMouseY())) return;

        if (input.is(keyBindings.getLeftClick())) {
            GTSHANHAI_JEI_LOGGER.info("recipe gui click: simulate={}, ctrl={}, cheat={}, mouse=({}, {})",
                    input.isSimulate(), Screen.hasControlDown(), Internal.getClientToggleState().isCheatItemsEnabled(),
                    input.getMouseX(), input.getMouseY());
        }

        if (!Internal.getClientToggleState().isCheatItemsEnabled()) return;
        if (!Screen.hasControlDown() || !input.is(keyBindings.getLeftClick())) return;

        Optional<mezz.jei.api.gui.inputs.RecipeSlotUnderMouse> slot = recipeLayout.getSlotUnderMouse(input.getMouseX(), input.getMouseY());
        if (slot.isEmpty()) {
            GTSHANHAI_JEI_LOGGER.info("recipe gui ctrl-left matched but slot under mouse is empty");
            return;
        }

        ItemStack infinityCell = slot.get()
                .slot()
                .getDisplayedIngredient()
                .map(JeiCopyShortcutHelper::createInfinityCellStack)
                .orElse(ItemStack.EMPTY);
        if (infinityCell.isEmpty()) {
            GTSHANHAI_JEI_LOGGER.info("recipe gui ctrl-left matched but infinity cell stack is empty");
            return;
        }

        GTSHANHAI_JEI_LOGGER.info("recipe gui infinity target resolved: {}", infinityCell.getTag());

        if (input.isSimulate()) {
            cir.setReturnValue(Optional.of(new SameElementInputHandler(gtShanhai$createDeferredGiveHandler(infinityCell), slot.get()::isMouseOver)));
            return;
        }

        CommandUtil commandUtil = new CommandUtil(Internal.getJeiClientConfigs().getClientConfig(), Internal.getServerConnection());
        commandUtil.giveStack(infinityCell, GiveAmount.MAX);
        gtShanhai$showClientMessage(infinityCell);
        cir.setReturnValue(Optional.of(new SameElementInputHandler((IUserInputHandler) (Object) this, slot.get()::isMouseOver)));
    }

    private IUserInputHandler gtShanhai$createDeferredGiveHandler(final ItemStack infinityCell) {
        return new IUserInputHandler() {
            @Override
            public Optional<IUserInputHandler> handleUserInput(Screen nextScreen, UserInput nextInput,
                    IInternalKeyMappings nextKeyMappings) {
                if (nextInput.isSimulate()) {
                    return Optional.of(this);
                }
                CommandUtil commandUtil = new CommandUtil(Internal.getJeiClientConfigs().getClientConfig(), Internal.getServerConnection());
                commandUtil.giveStack(infinityCell, GiveAmount.MAX);
                GTSHANHAI_JEI_LOGGER.info("recipe gui infinity giveStack executed: {}", infinityCell.getTag());
                gtShanhai$showClientMessage(infinityCell);
                return Optional.of(this);
            }
        };
    }

    private static void gtShanhai$showClientMessage(ItemStack infinityCell) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        mc.player.displayClientMessage(
                Component.literal("[山海JEI] 已发送无限盘请求: ")
                        .withStyle(ChatFormatting.GRAY)
                        .append(infinityCell.getHoverName().copy().withStyle(ChatFormatting.YELLOW)),
                true);
    }
}
