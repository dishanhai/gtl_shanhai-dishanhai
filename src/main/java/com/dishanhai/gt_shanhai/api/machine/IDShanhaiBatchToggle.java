package com.dishanhai.gt_shanhai.api.machine;

import com.gregtechceu.gtceu.api.gui.GuiTextures;
import com.gregtechceu.gtceu.api.gui.fancy.ConfiguratorPanel;
import com.gregtechceu.gtceu.api.gui.fancy.IFancyConfiguratorButton;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * 批处理开关的最小接口：只约定开关存取，不约束实现方是控制器还是 part。
 * <p>
 * 两条生效路径消费此接口：
 * <ul>
 * <li>控制器自身：{@link IDShanhaiBatchable}（extends 本接口）在修饰链尾调 applyBatchMode；</li>
 * <li>part 载体：多方块的任一 part 实现本接口且开关开启时，
 * {@code DShanhaiBatchPartMixin} 在 RecipeModifierList#apply RETURN 处对宿主配方批处理
 * ——与 GTMAdvancedHatch 的电网仓同模式，装上即生效，宿主机器无需任何改动。</li>
 * </ul>
 * 实现方自持 {@code @Persisted} 布尔字段（接口无法带字段）。
 */
public interface IDShanhaiBatchToggle {

    boolean isBatchModeEnabled();

    void setBatchModeEnabled(boolean enabled);

    /** 挂批处理 GUI 开关（与 GTMA 同款按钮材质，玩家认知一致）。 */
    static void attachBatchConfigurator(ConfiguratorPanel panel, IDShanhaiBatchToggle machine) {
        panel.attachConfigurators(new IFancyConfiguratorButton.Toggle(
                GuiTextures.BUTTON_ALLOW_IMPORT_EXPORT.getSubTexture(0.0D, 0.0D, 1.0D, 0.5D),
                GuiTextures.BUTTON_ALLOW_IMPORT_EXPORT.getSubTexture(0.0D, 0.5D, 1.0D, 0.5D),
                machine::isBatchModeEnabled,
                (clickData, pressed) -> machine.setBatchModeEnabled(pressed.booleanValue()))
                .setTooltipsSupplier(pressed -> List.of(
                        Component.translatable("gt_shanhai.gui.batch_mode")
                                .withStyle(ChatFormatting.YELLOW)
                                .append(Component.translatable(pressed.booleanValue()
                                        ? "gt_shanhai.gui.batch_mode.on"
                                        : "gt_shanhai.gui.batch_mode.off")),
                        Component.translatable("gt_shanhai.gui.batch_mode.info"))));
    }
}
