package com.dishanhai.gt_shanhai.mixin;

import com.dishanhai.gt_shanhai.client.JeiCopyShortcutHelper;
import mezz.jei.api.runtime.IIngredientManager;
import mezz.jei.common.config.IClientToggleState;
import mezz.jei.common.input.IInternalKeyMappings;
import mezz.jei.gui.input.IClickableIngredientInternal;
import mezz.jei.gui.input.CombinedRecipeFocusSource;
import mezz.jei.gui.input.IUserInputHandler;
import mezz.jei.gui.input.UserInput;
import mezz.jei.gui.input.handlers.SameElementInputHandler;
import mezz.jei.gui.util.CommandUtil;
import mezz.jei.gui.util.GiveAmount;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Optional;

@Mixin(targets = "mezz.jei.gui.input.handlers.FocusInputHandler", remap = false)
public class JEICopyShortcutMixin {
    private static final Logger GTSHANHAI_JEI_LOGGER = LogManager.getLogger("gt_shanhai_jei_infinity");
    
    static {
        LogManager.getLogger("gt_shanhai_jei_infinity").info("[STATIC] JEICopyShortcutMixin class loaded");
    }

    @Shadow
    @Final
    private CombinedRecipeFocusSource focusSource;

    @Shadow
    @Final
    private IIngredientManager ingredientManager;

    @Shadow
    @Final
    private IClientToggleState toggleState;

    @Shadow
    @Final
    private CommandUtil commandUtil;

    @Inject(method = "handleUserInput", at = @At("HEAD"), cancellable = true, remap = false)
    private void gtShanhai$handleCopyShortcuts(Screen screen, UserInput input, IInternalKeyMappings keyMappings,
            CallbackInfoReturnable<Optional<IUserInputHandler>> cir) {
        Optional<IUserInputHandler> infinityHandler = gtShanhai$handleInfinityCellGive(screen, input, keyMappings);
        if (infinityHandler.isPresent()) {
            cir.setReturnValue(infinityHandler);
            return;
        }

        int action = gtShanhai$getAction(input);
        if (action == 0) return;

        boolean handled = focusSource.getIngredientUnderMouse(input, keyMappings)
                .findFirst()
                .map(clickable -> JeiCopyShortcutHelper.handle(clickable.getTypedIngredient(), ingredientManager, action))
                .orElse(false);
        if (handled) {
            cir.setReturnValue(Optional.of((IUserInputHandler) (Object) this));
        }
    }

    private Optional<IUserInputHandler> gtShanhai$handleInfinityCellGive(Screen screen, UserInput input,
            IInternalKeyMappings keyMappings) {
        if (!(screen instanceof AbstractContainerScreen)) return Optional.empty();

        if (input.is(keyMappings.getCheatItemStack())) {
            GTSHANHAI_JEI_LOGGER.info("focus handler cheat click: simulate={}, ctrl={}, cheat={}, mouse=({}, {})",
                    input.isSimulate(), Screen.hasControlDown(), toggleState.isCheatItemsEnabled(),
                    input.getMouseX(), input.getMouseY());
        }

        if (!toggleState.isCheatItemsEnabled()) return Optional.empty();
        if (!gtShanhai$isCtrlLeftCheat(input, keyMappings)) return Optional.empty();

        return focusSource.getIngredientUnderMouse(input, keyMappings)
                .<IUserInputHandler>mapMulti((clickable, consumer) -> gtShanhai$tryGiveInfinityCell(input, clickable, consumer))
                .findFirst();
    }

    private void gtShanhai$tryGiveInfinityCell(UserInput input, IClickableIngredientInternal<?> clickable,
            java.util.function.Consumer<IUserInputHandler> consumer) {
        ItemStack infinityCell = JeiCopyShortcutHelper.createInfinityCellStack(clickable.getTypedIngredient());
        if (infinityCell.isEmpty()) {
            GTSHANHAI_JEI_LOGGER.info("focus handler ctrl-left matched but infinity cell stack is empty");
            return;
        }

        GTSHANHAI_JEI_LOGGER.info("focus handler infinity target resolved: {}", infinityCell.getTag());

        if (input.isSimulate()) {
            consumer.accept(new SameElementInputHandler(gtShanhai$createDeferredGiveHandler(infinityCell), clickable::isMouseOver));
            return;
        }

        commandUtil.giveStack(infinityCell, GiveAmount.MAX);
        GTSHANHAI_JEI_LOGGER.info("focus handler infinity giveStack executed: {}", infinityCell.getTag());
        gtShanhai$showClientMessage(infinityCell);
        consumer.accept(new SameElementInputHandler((IUserInputHandler) (Object) this, clickable::isMouseOver));
    }

    private static boolean gtShanhai$isCtrlLeftCheat(UserInput input, IInternalKeyMappings keyMappings) {
        return Screen.hasControlDown() && input.is(keyMappings.getCheatItemStack());
    }

    private IUserInputHandler gtShanhai$createDeferredGiveHandler(final ItemStack infinityCell) {
        return new IUserInputHandler() {
            @Override
            public Optional<IUserInputHandler> handleUserInput(Screen nextScreen, UserInput nextInput,
                    IInternalKeyMappings nextKeyMappings) {
                if (nextInput.isSimulate()) {
                    return Optional.of(this);
                }
                commandUtil.giveStack(infinityCell, GiveAmount.MAX);
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

    private static int gtShanhai$getAction(UserInput input) {
        if ((input.getModifiers() & GLFW.GLFW_MOD_CONTROL) == 0) return 0;

        int key = input.getKey().getValue();
        if (key == GLFW.GLFW_KEY_C) return JeiCopyShortcutHelper.COPY_NAME;
        if (key == GLFW.GLFW_KEY_D) return JeiCopyShortcutHelper.COPY_TAG;
        if (key == GLFW.GLFW_KEY_L) return JeiCopyShortcutHelper.SHARE_TO_CHAT;
        if (key == GLFW.GLFW_KEY_X) return JeiCopyShortcutHelper.COPY_ID;
        return 0;
    }
}
