package com.dishanhai.gt_shanhai.mixin

import com.dishanhai.gt_shanhai.client.JeiShortcutInputInterceptor
import mezz.jei.common.input.IInternalKeyMappings
import mezz.jei.gui.input.IUserInputHandler
import mezz.jei.gui.input.UserInput
import mezz.jei.gui.input.handlers.CheatInputHandler
import net.minecraft.client.gui.screens.Screen
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable
import java.util.Optional

/**
 * 山海 JEI 交互优化（容器界面 / JEI 物品栏面板 / 书签栏）。
 *
 * 注入点选在 [CheatInputHandler] 而不是原来的 `FocusInputHandler`：JEI 全局输入路由顺序是
 * `EditInputHandler → CheatInputHandler → 物品栏 → 书签栏 → FocusInputHandler`，
 * 而 JEI 自带的作弊取物快捷键 `getCheatItemStack()`（Shift+左键，Ctrl 不影响匹配）
 * 会先一步吞掉点击。所以旧实现里「Ctrl+Shift+左键」在容器界面根本拿不到无限盘，
 * 只会拿到普通物品。
 *
 * 现在在本处理器 HEAD 处优先拦截（实现见 [JeiShortcutInputInterceptor]）：
 * - Ctrl+Shift+左键 → 发放对应的 EAE 无限盘（作弊模式开启且处于容器界面）
 * - Ctrl+C / D / L / X → 复制名称 / 标签 / 聊天链接 / ID
 *
 * 其余情况原样放过，JEI 行为不变。
 */
@Mixin(value = [CheatInputHandler::class], remap = false)
open class JEICopyShortcutMixin {

    @Inject(method = ["handleUserInput"], at = [At("HEAD")], cancellable = true, remap = false)
    open fun gtShanhai_handleUserInput(
        screen: Screen,
        input: UserInput,
        keyMappings: IInternalKeyMappings,
        cir: CallbackInfoReturnable<Optional<IUserInputHandler>>
    ) {
        val handled = JeiShortcutInputInterceptor.handleContainerInput(
            this as IUserInputHandler,
            this as? JeiCheatInputHandlerAccessor ?: return,
            screen,
            input,
            keyMappings
        )
        if (handled != null) cir.setReturnValue(handled)
    }
}
