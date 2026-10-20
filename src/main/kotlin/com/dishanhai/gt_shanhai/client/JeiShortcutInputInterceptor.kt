package com.dishanhai.gt_shanhai.client

import com.dishanhai.gt_shanhai.mixin.JeiCheatInputHandlerAccessor
import mezz.jei.api.gui.IRecipeLayoutDrawable
import mezz.jei.common.Internal
import mezz.jei.common.input.IInternalKeyMappings
import mezz.jei.gui.input.IMouseOverable
import mezz.jei.gui.input.IUserInputHandler
import mezz.jei.gui.input.UserInput
import mezz.jei.gui.input.handlers.SameElementInputHandler
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.world.item.ItemStack
import java.util.Optional

/**
 * JEI 输入拦截实现（山海）。
 *
 * 逻辑放在普通 Kotlin 物件里，Mixin 类只做一行转发：
 * Mixin 会把 mixin 类的**全部**方法与字段（含 Kotlin lambda 助手、companion 静态字段）
 * 一并合并进 JEI 的目标类，能少合就少合。
 */
object JeiShortcutInputInterceptor {

    /**
     * 容器界面 / JEI 物品栏面板 / 书签栏 / 容器槽位：
     * Ctrl+Shift+左键取无限盘，Ctrl+C/D/L/X 复制名称 / 标签 / 聊天链接 / ID。
     *
     * 返回非 null 表示本次输入已被消费，调用方应把它作为 handleUserInput 的返回值。
     */
    @JvmStatic
    fun handleContainerInput(
        self: IUserInputHandler,
        accessor: JeiCheatInputHandlerAccessor,
        screen: Screen,
        input: UserInput,
        keyMappings: IInternalKeyMappings
    ): Optional<IUserInputHandler>? {
        // 与 JEI 一致：容器界面才走作弊取物；RecipesGui 由配方槽入口单独处理。
        if (Internal.getClientToggleState().isCheatItemsEnabled
            && screen is AbstractContainerScreen<*>
            && JeiCopyShortcutHelper.isInfinityCellShortcut(input)
        ) {
            giveInfinityCellFromFocus(accessor, input, keyMappings, "container")?.let { return it }
        }

        // 复制类快捷键都是键盘 IMMEDIATE 输入，没有 SIMULATE/EXECUTE 配对问题。
        val action = JeiCopyShortcutHelper.copyAction(input)
        if (action == 0) return null

        val handled = accessor.gtShanhaiFocusSource()
            .getIngredientUnderMouse(input, keyMappings)
            .findFirst()
            .map {
                JeiCopyShortcutHelper.handle(
                    it.typedIngredient, accessor.gtShanhaiIngredientManager(), action
                )
            }
            .orElse(false)
        return if (handled) Optional.of(self) else null
    }

    /**
     * RecipesGui 配方槽：Ctrl+Shift+左键取无限盘。
     *
     * RecipesGui 用自己独立的输入路由，不含全局 CheatInputHandler；配方槽 ingredient
     * 也不允许作弊取物（`allowsCheating == false`），所以必须单独处理。
     */
    @JvmStatic
    fun handleRecipeSlotInput(
        input: UserInput,
        recipeLayout: IRecipeLayoutDrawable<*>
    ): Optional<IUserInputHandler>? {
        if (!Internal.getClientToggleState().isCheatItemsEnabled) return null
        if (!JeiCopyShortcutHelper.isInfinityCellShortcut(input)) return null

        val mouseX = input.mouseX
        val mouseY = input.mouseY
        if (!recipeLayout.isMouseOver(mouseX, mouseY)) return null

        val slot = recipeLayout.getSlotUnderMouse(mouseX, mouseY).orElse(null) ?: return null
        val infinityCell = slot.slot().displayedIngredient
            .map { JeiCopyShortcutHelper.createInfinityCellStack(it) }
            .filter { !it.isEmpty }
            .orElse(ItemStack.EMPTY)
        if (infinityCell.isEmpty) return null

        return Optional.of(
            SameElementInputHandler(
                GiveOnExecute(infinityCell, "recipe"),
                IMouseOverable { x, y -> slot.isMouseOver(x, y) }
            )
        )
    }

    private fun giveInfinityCellFromFocus(
        accessor: JeiCheatInputHandlerAccessor,
        input: UserInput,
        keyMappings: IInternalKeyMappings,
        origin: String
    ): Optional<IUserInputHandler>? {
        // 逐个尝试悬停到的 ingredient，第一个能生成无限盘的就用（物品/流体之外的类型跳过）。
        val hit = accessor.gtShanhaiFocusSource()
            .getIngredientUnderMouse(input, keyMappings)
            .map { clickable -> clickable to JeiCopyShortcutHelper.createInfinityCellStack(clickable.typedIngredient) }
            .filter { (_, cell) -> !cell.isEmpty }
            .findFirst()
            .orElse(null) ?: return null

        val clickable = hit.first
        val infinityCell = hit.second
        return Optional.of(
            SameElementInputHandler(
                GiveOnExecute(infinityCell, origin),
                IMouseOverable { mouseX, mouseY -> clickable.isMouseOver(mouseX, mouseY) }
            )
        )
    }

    /**
     * 在鼠标释放（EXECUTE）时才真正发盘。
     *
     * 这样不依赖「松手瞬间 Ctrl/Shift 还按着」：按下时判定一次，松手时直接发放，
     * 与 JEI 自己「按下登记、松开执行」的两段式输入模型对齐。
     */
    private class GiveOnExecute(
        private val infinityCell: ItemStack,
        private val origin: String
    ) : IUserInputHandler {

        override fun handleUserInput(
            screen: Screen,
            userInput: UserInput,
            keyMappings: IInternalKeyMappings
        ): Optional<IUserInputHandler> {
            if (!userInput.isSimulate) {
                JeiInfinityCellBridge.giveInfinityCell(infinityCell, origin)
                JeiInfinityCellBridge.notifyGiven(infinityCell)
            }
            return Optional.of(this)
        }
    }
}
