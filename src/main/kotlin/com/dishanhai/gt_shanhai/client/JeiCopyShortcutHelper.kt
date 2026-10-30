package com.dishanhai.gt_shanhai.client

import com.mojang.blaze3d.platform.InputConstants
import mezz.jei.api.ingredients.IIngredientHelper
import mezz.jei.api.ingredients.ITypedIngredient
import mezz.jei.api.runtime.IIngredientManager
import mezz.jei.gui.input.UserInput
import net.minecraft.ChatFormatting
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.ChatScreen
import net.minecraft.client.gui.screens.Screen
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation
import net.minecraft.tags.TagKey
import net.minecraft.world.item.ItemStack
import net.minecraftforge.fluids.FluidStack
import net.minecraftforge.registries.ForgeRegistries
import java.util.Optional
import java.util.stream.Stream

/**
 * JEI 交互辅助。
 *
 * 1. Ctrl+Shift+左键 → 生成 EAE 无限盘（expatternprovider:infinity_cell），
 *    NBT 形如 `{record:{"#c":"ae2:f",id:"minecraft:water"}}`。
 * 2. Ctrl+C / Ctrl+D / Ctrl+L / Ctrl+X → 复制名称 / 标签 / 聊天链接 / ID。
 *
 * Kotlin 侧保持与原 Java 版一致的 FQCN 与静态入口（companion + @JvmStatic），
 * 现有 Java 调用方与测试无需改动。
 */
class JeiCopyShortcutHelper private constructor() {

    /** 无限盘要绑定的目标：物品或流体 registry id。 */
    data class InfinityCellTarget(val id: String, val fluid: Boolean)

    companion object {
        const val COPY_NAME = 1
        const val COPY_TAG = 2
        const val SHARE_TO_CHAT = 3
        const val COPY_ID = 4

        private val INFINITY_CELL_ID: ResourceLocation? =
            ResourceLocation.tryParse("expatternprovider:infinity_cell")

        /** 与原版 ChatScreen.MAX_LENGTH 保持一致 */
        private const val MAX_CHAT_LENGTH = 256

        /**
         * 判定「取无限盘」组合键：鼠标左键 + Ctrl + Shift。
         *
         * 注意：Forge 的 `ForgeUserInput.fromEvent(MouseButtonPressed)` 构造 UserInput 时
         * 把 modifiers 写死为 0，所以这里只能读实时键盘状态（`Screen.hasControlDown()`），
         * 不能依赖 `UserInput#getModifiers()`。
         */
        @JvmStatic
        fun isInfinityCellShortcut(input: UserInput?): Boolean {
            if (input == null) return false
            val key = input.key
            if (key.type != InputConstants.Type.MOUSE || key.value != 0) return false
            return Screen.hasControlDown() && Screen.hasShiftDown()
        }

        /** 复制类快捷键：返回动作编号，非快捷键返回 0。 */
        @JvmStatic
        fun copyAction(input: UserInput?): Int {
            if (input == null) return 0
            val key = input.key
            if (key.type != InputConstants.Type.KEYSYM) return 0
            if (key.value == org.lwjgl.glfw.GLFW.GLFW_KEY_UNKNOWN) return 0
            // 鼠标事件拿不到 modifiers，键盘事件在部分界面也会传 0，所以两条路都认。
            val ctrl = (input.modifiers and org.lwjgl.glfw.GLFW.GLFW_MOD_CONTROL) != 0 || Screen.hasControlDown()
            if (!ctrl) return 0
            return when (key.value) {
                org.lwjgl.glfw.GLFW.GLFW_KEY_C -> COPY_NAME
                org.lwjgl.glfw.GLFW.GLFW_KEY_D -> COPY_TAG
                org.lwjgl.glfw.GLFW.GLFW_KEY_L -> SHARE_TO_CHAT
                org.lwjgl.glfw.GLFW.GLFW_KEY_X -> COPY_ID
                else -> 0
            }
        }

        @JvmStatic
        fun handle(
            typedIngredient: ITypedIngredient<*>?,
            ingredientManager: IIngredientManager?,
            action: Int
        ): Boolean {
            if (typedIngredient == null || ingredientManager == null) return false

            val text: String
            val label: String
            when (action) {
                COPY_NAME -> {
                    text = getDisplayName(typedIngredient, ingredientManager)
                    label = "名称"
                }
                COPY_TAG -> {
                    text = getFirstTag(typedIngredient, ingredientManager)
                    label = "标签"
                }
                SHARE_TO_CHAT -> {
                    text = getShareText(typedIngredient, ingredientManager)
                    label = "聊天链接"
                }
                COPY_ID -> {
                    text = getId(typedIngredient, ingredientManager)
                    label = "ID"
                }
                else -> return false
            }

            if (text.isEmpty()) {
                showMessage(Component.literal("[山海JEI] 没有可复制的$label").withStyle(ChatFormatting.RED))
                return true
            }

            if (action == SHARE_TO_CHAT) {
                val safe = sanitizeForChat(text)
                if (safe.isEmpty()) {
                    showMessage(Component.literal("[山海JEI] 名称含非法字符，无法分享到聊天").withStyle(ChatFormatting.RED))
                    return true
                }
                Minecraft.getInstance().setScreen(ChatScreen(safe))
            } else {
                Minecraft.getInstance().keyboardHandler.setClipboard(text)
            }
            showMessage(
                Component.literal("[山海JEI] 已复制$label: ").withStyle(ChatFormatting.GRAY)
                    .append(Component.literal(text).withStyle(ChatFormatting.YELLOW))
            )
            return true
        }

        /** 按 JEI 当前悬停的 ingredient 构造无限盘；不支持的类型返回空物品。 */
        @JvmStatic
        fun createInfinityCellStack(typedIngredient: ITypedIngredient<*>?): ItemStack {
            val target = resolveInfinityCellTarget(typedIngredient) ?: return ItemStack.EMPTY
            val cellId = INFINITY_CELL_ID ?: return ItemStack.EMPTY
            val item = ForgeRegistries.ITEMS.getValue(cellId) ?: return ItemStack.EMPTY
            val tag = buildInfinityCellTag(target) ?: return ItemStack.EMPTY
            return ItemStack(item).apply { this.tag = tag }
        }

        @JvmStatic
        fun resolveInfinityCellTarget(typedIngredient: ITypedIngredient<*>?): InfinityCellTarget? {
            val ingredient = typedIngredient?.ingredient ?: return null
            when (ingredient) {
                is ItemStack -> {
                    if (ingredient.isEmpty) return null
                    val id = BuiltInRegistries.ITEM.getKey(ingredient.item)
                    return InfinityCellTarget(id.toString(), false)
                }
                is FluidStack -> {
                    if (ingredient.isEmpty) return null
                    val id = BuiltInRegistries.FLUID.getKey(ingredient.fluid)
                    return InfinityCellTarget(id.toString(), true)
                }
            }
            return null
        }

        /**
         * 构造 record NBT：
         * 物品 → `{"#c":"ae2:i"}`，流体 → `{"#c":"ae2:f"}`，与 AE2 `AEKey#toTagGeneric()` 一致。
         */
        @JvmStatic
        fun buildInfinityCellTag(target: InfinityCellTarget?): CompoundTag? {
            val id = target?.id
            if (id.isNullOrEmpty()) return null
            val record = CompoundTag()
            record.putString("#c", if (target.fluid) "ae2:f" else "ae2:i")
            record.putString("id", id)
            return CompoundTag().apply { put("record", record) }
        }

        private fun getDisplayName(typedIngredient: ITypedIngredient<*>, ingredientManager: IIngredientManager): String {
            val ingredient = typedIngredient.ingredient
            if (ingredient is ItemStack) return ingredient.hoverName.string
            if (ingredient is FluidStack) return ingredient.displayName.string
            return helperOf(typedIngredient, ingredientManager).getDisplayName(ingredient as Any)
        }

        private fun getId(typedIngredient: ITypedIngredient<*>, ingredientManager: IIngredientManager): String {
            val ingredient = typedIngredient.ingredient
            if (ingredient is ItemStack) return BuiltInRegistries.ITEM.getKey(ingredient.item).toString()
            if (ingredient is FluidStack) return BuiltInRegistries.FLUID.getKey(ingredient.fluid).toString()
            return helperOf(typedIngredient, ingredientManager)
                .getResourceLocation(ingredient as Any)?.toString().orEmpty()
        }

        private fun getFirstTag(typedIngredient: ITypedIngredient<*>, ingredientManager: IIngredientManager): String {
            val ingredient = typedIngredient.ingredient
            if (ingredient is ItemStack) {
                return ingredient.tags.map(::formatTag).sorted().findFirst().orElse("")
            }
            if (ingredient is FluidStack) {
                return ingredient.fluid.builtInRegistryHolder().tags()
                    .map(::formatTag).sorted().findFirst().orElse("")
            }
            return helperOf(typedIngredient, ingredientManager)
                .getTagStream(ingredient as Any).map { "#$it" }.sorted().findFirst().orElse("")
        }

        private fun getShareText(typedIngredient: ITypedIngredient<*>, ingredientManager: IIngredientManager): String {
            val name = getDisplayName(typedIngredient, ingredientManager)
            val id = getId(typedIngredient, ingredientManager)
            if (name.isEmpty()) return id
            if (id.isEmpty()) return name
            return "[$name] $id"
        }

        private fun formatTag(tag: TagKey<*>): String = "#" + tag.location

        @Suppress("UNCHECKED_CAST")
        private fun helperOf(
            typedIngredient: ITypedIngredient<*>,
            ingredientManager: IIngredientManager
        ): IIngredientHelper<Any> =
            ingredientManager.getIngredientHelper(typedIngredient.type) as IIngredientHelper<Any>

        /**
         * 净化待发送到聊天框的文本：剔除 §（章节符）与其他原版禁止的聊天字符，
         * 并按 ChatScreen 输入上限截断。`new ChatScreen(text)` 预填文本会绕过 EditBox 的逐字过滤。
         */
        private fun sanitizeForChat(text: String): String {
            if (text.isEmpty()) return ""
            val sb = StringBuilder(text.length)
            for (c in text) {
                if (c == '\u00a7' || c < ' ' || c == '\u007f') continue
                sb.append(c)
            }
            return if (sb.length > MAX_CHAT_LENGTH) sb.substring(0, MAX_CHAT_LENGTH) else sb.toString()
        }

        private fun showMessage(message: Component) {
            Minecraft.getInstance().player?.displayClientMessage(message, true)
        }
    }
}
