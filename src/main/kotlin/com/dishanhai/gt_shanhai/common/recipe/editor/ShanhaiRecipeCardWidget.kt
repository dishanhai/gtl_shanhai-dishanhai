package com.dishanhai.gt_shanhai.common.recipe.editor

import com.dishanhai.gt_shanhai.client.gui.glass.GlassPalette
import com.dishanhai.gt_shanhai.client.gui.scaled.GuiMotion
import com.dishanhai.gt_shanhai.client.gui.scaled.Spring
import com.lowdragmc.lowdraglib.gui.texture.ItemStackTexture
import com.lowdragmc.lowdraglib.gui.widget.Widget
import com.mojang.blaze3d.systems.RenderSystem
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.renderer.texture.TextureAtlasSprite
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.core.registries.Registries
import net.minecraft.resources.ResourceLocation
import net.minecraft.tags.TagKey
import net.minecraft.world.inventory.InventoryMenu
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.material.Fluid
import net.minecraft.world.level.material.Fluids
import net.minecraftforge.client.extensions.common.IClientFluidTypeExtensions

/** One recipe row on the select page. Clicking it opens the edit page. */
class ShanhaiRecipeCardWidget(
    x: Int,
    y: Int,
    width: Int,
    private val card: ShanhaiRecipeQuery.Card,
    order: Int,
    private val open: Runnable?,
) : Widget(x, y, width, CH) {

    constructor(x: Int, y: Int, width: Int, card: ShanhaiRecipeQuery.Card, open: Runnable?) :
        this(x, y, width, card, 0, open)

    private val order: Int = maxOf(0, order)
    private val born: Long = ShanhaiRecipeEditorAnimation.nowMs()
    private val hover = Spring(Spring.HOVER)
    private var itemIcon: ItemStackTexture? = null

    override fun drawInBackground(graphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTicks: Float) {
        val enter = enterProgress()
        val x = getPositionX()
        val y = getPositionY()
        val width = getSizeWidth()
        val now = ShanhaiRecipeEditorAnimation.nowMs()
        hover.setTarget(if (isMouseOverElement(mouseX.toDouble(), mouseY.toDouble())) 1f else 0f, now)
        hover.update(now)
        val lift = hover.value().coerceIn(0f, 1f)
        graphics.pose().pushPose()
        graphics.pose().translate(0f, (1f - enter) * 8f, 0f)
        val fade = 0.2f + 0.8f * enter
        RenderSystem.setShaderColor(1f, 1f, 1f, fade)
        graphics.fill(x, y, x + width, y + CH,
            GuiMotion.lerpColor(GlassPalette.ROW_EDGE, GlassPalette.ACCENT, lift))
        graphics.fill(x + 1, y + 1, x + width - 1, y + CH - 1,
            GuiMotion.lerpColor(GlassPalette.ROW_FILL, GlassPalette.ROW_FILL_HOVER, lift))
        graphics.fill(x + 4, y + 4, x + 22, y + 22, GlassPalette.ROW_ICON_BG)
        drawOutputIcon(graphics, x + 5, y + 6)
        RenderSystem.setShaderColor(1f, 1f, 1f, fade)
        val font = Minecraft.getInstance().font
        val mark = "可编辑"
        val markX = x + width - 8 - font.width(mark)
        val name = fit(font, recipeLabel(card.recipeId()), maxOf(8, markX - (x + 28) - 4))
        graphics.drawString(font, name, x + 28, y + 4, GlassPalette.TEXT, false)
        val brief = ioBrief()
        val briefX = x + 28 + font.width(name) + 6
        if (brief.isNotEmpty() && briefX + 12 < markX) {
            graphics.drawString(font, fit(font, brief, markX - briefX - 4), briefX, y + 4, GlassPalette.TEXT_DIM, false)
        }
        graphics.drawString(font,
            trim(card.recipeTypeId() + "  " + card.duration() + "t  " + card.eut() + " EU/t", 48),
            x + 28, y + 15, GlassPalette.TEXT_DIM, false)
        graphics.drawString(font, mark, markX, y + 10, GlassPalette.OK, false)
        graphics.pose().popPose()
        RenderSystem.setShaderColor(1f, 1f, 1f, 1f)
    }

    private fun enterProgress(): Float {
        val elapsed = ShanhaiRecipeEditorAnimation.nowMs() - born - order * 28L
        if (elapsed <= 0L) return 0f
        val linear = minOf(1f, elapsed / ENTER_MS.toFloat())
        return linear * linear * (3f - 2f * linear)
    }

    override fun mouseClicked(mouseX: Double, mouseY: Double, button: Int): Boolean {
        if (button != 0 || !isMouseOverElement(mouseX, mouseY)) return false
        open?.run()
        return true
    }

    private fun drawOutputIcon(graphics: GuiGraphics, x: Int, y: Int) {
        val kind = card.iconKind()
        val id = ResourceLocation.tryParse(card.iconId())
        if (id == null || kind == null || kind.isEmpty()) return
        if ("fluid" == kind) {
            val fluid = BuiltInRegistries.FLUID.get(id)
            if (fluid !== Fluids.EMPTY) drawFluidSprite(graphics, fluid, x, y)
            return
        }
        val item = resolveItem(kind, id)
        if (item === Items.AIR) return
        val icon = itemIcon ?: ItemStackTexture(ItemStack(item)).also { itemIcon = it }
        icon.draw(graphics, 0, 0, x.toFloat(), y.toFloat(), 16, 16)
    }

    private fun resolveItem(kind: String, id: ResourceLocation): Item {
        if ("tag" == kind) {
            val named = BuiltInRegistries.ITEM.getTag(TagKey.create(Registries.ITEM, id))
            if (named.isEmpty()) return Items.AIR
            return named.get().firstOrNull()?.value() ?: Items.AIR
        }
        return BuiltInRegistries.ITEM.get(id)
    }

    private fun drawFluidSprite(graphics: GuiGraphics, fluid: Fluid, x: Int, y: Int) {
        var still: ResourceLocation? = null
        var tint = -1
        try {
            val ext = IClientFluidTypeExtensions.of(fluid)
            still = ext.getStillTexture()
            tint = ext.getTintColor()
        } catch (ignored: Throwable) {
            graphics.fill(x, y, x + 16, y + 16, 0xC0000000.toInt() or (tint and 0x00FFFFFF))
            return
        }
        if (still == null) {
            graphics.fill(x, y, x + 16, y + 16, 0xC0000000.toInt() or (tint and 0x00FFFFFF))
            return
        }
        val sprite = Minecraft.getInstance().getTextureAtlas(InventoryMenu.BLOCK_ATLAS).apply(still)
        blitSprite(graphics, sprite, x, y, tint)
    }

    private fun blitSprite(graphics: GuiGraphics, sprite: TextureAtlasSprite, x: Int, y: Int, tint: Int) {
        val rgb = if (tint < 0) 0xFFFFFF else tint
        RenderSystem.enableBlend()
        RenderSystem.defaultBlendFunc()
        RenderSystem.setShaderColor(((rgb shr 16) and 0xFF) / 255f, ((rgb shr 8) and 0xFF) / 255f,
            (rgb and 0xFF) / 255f, 1f)
        graphics.blit(x, y, 0, 16, 16, sprite)
        RenderSystem.setShaderColor(1f, 1f, 1f, 1f)
        RenderSystem.disableBlend()
    }

    private fun ioBrief(): String {
        if (card.inputBrief().isEmpty() && card.outputBrief().isEmpty()) return ""
        val inputs = if (card.inputBrief().isEmpty()) "-" else card.inputBrief()
        val outputs = if (card.outputBrief().isEmpty()) "-" else card.outputBrief()
        return "入 $inputs → 出 $outputs"
    }

    private fun fit(font: Font, value: String?, width: Int): String {
        if (value.isNullOrEmpty() || width < 8) return ""
        if (font.width(value) <= width) return value
        val ellipsis = "…"
        var keep = value.length
        while (keep > 0 && font.width(value.substring(0, keep) + ellipsis) > width) keep--
        return if (keep <= 0) "" else value.substring(0, keep) + ellipsis
    }

    /** Full recipe id, namespace included: `gtceu:qft/qft_hyper_stable_self_healing_adhesive`. */
    private fun recipeLabel(value: String?): String =
        if (value.isNullOrEmpty()) "未命名配方" else value

    private fun trim(value: String?, max: Int): String {
        if (value == null) return ""
        return if (value.length <= max) value else value.substring(0, maxOf(0, max - 3)) + "..."
    }

    companion object {
        const val CH = 28
        private const val ENTER_MS = 200L
    }
}
