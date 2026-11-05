package com.dishanhai.gt_shanhai.mixin;

import com.dishanhai.gt_shanhai.api.TextFormatParser;
import net.minecraft.client.gui.components.ComponentRenderUtils;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * 断行前 FCS 归一化（全局兜底）。
 *
 * wrapComponents(=m_94005_) 是 LDLib ComponentPanelWidget（GTCEu 机器显示面板）
 * 和聊天栏的断行入口，内部用 StringSplitter 量宽。含 &$theme- 码的文本会按
 * "胖宽度"（幽灵 ASCII 字符 ~18 个）计算断点，而渲染层 WobbleFontMixin 剥码后
 * 画的是瘦文本，两套宽度打架导致提前断行/孤字/溢出。
 *
 * 此 mixin 在断行前把树中含 & 码的节点替换为净文本 + fcs insertion（随 Style
 * 传进拆分子行），断行量到真实宽度，WobbleFontMixin 的 insertion 路径恢复色板。
 * 注意：KubeJS displayName 走 lang 键，物品名节点是 TranslatableContents，
 * 因此按"节点自身解析文本"判定，不能只认 LiteralContents。
 */
@Mixin(value = ComponentRenderUtils.class, remap = false)
public class ComponentWrapFcsNormalizeMixin {

    @ModifyVariable(method = "m_94005_", at = @At("HEAD"), argsOnly = true, remap = false, require = 0)
    private static FormattedText gtShanhai$normalizeFcs(FormattedText text) {
        if (!(text instanceof Component comp)) return text;
        try {
            String raw = comp.getString();
            if (raw == null || raw.isEmpty() || !TextFormatParser.containsSpecialFormatting(raw)) {
                return text;
            }
            return TextFormatParser.normalizeFcsComponent(comp);
        } catch (Exception e) {
            return text;
        }
    }
}
