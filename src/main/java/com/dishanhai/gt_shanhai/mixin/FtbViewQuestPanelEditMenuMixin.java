package com.dishanhai.gt_shanhai.mixin;

import com.dishanhai.gt_shanhai.client.gui.shop.ShopQuestLinkMenu;

import dev.ftb.mods.ftblibrary.ui.ContextMenuItem;
import dev.ftb.mods.ftbquests.client.gui.quests.ViewQuestPanel;
import dev.ftb.mods.ftbquests.quest.Quest;
import dev.ftb.mods.ftbquests.quest.QuestObjectBase;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

import java.util.List;

/**
 * 把「绑定商店商品 / 解除商店绑定」挂进 FTBQ 任务详情页的「编辑 ▼」菜单（山海署名，仅客户端）。
 *
 * <p>该菜单本身只在 {@code canEdit} 时才有入口（{@code addButtonBar} 里判过），所以这里不再重复判权限；
 * 服务端收包时另有一道 {@code ShopEditPermission#canEdit} 校验，客户端菜单显隐不作数。</p>
 *
 * <p>用 {@code @ModifyArg} 改 {@code openContextMenu} 的入参而不是 {@code @Inject} + 捕获局部变量：
 * 菜单列表是方法内 {@code new ArrayList<>()} 现构的，改参数拿到的就是同一个可变列表，
 * 比 LocalCapture 稳（不受 FTBQ 更新时局部变量表变动影响），也不用管注入点前后顺序。</p>
 */
@Mixin(value = ViewQuestPanel.class, remap = false)
public abstract class FtbViewQuestPanelEditMenuMixin {

    @ModifyArg(method = "openEditButtonContextMenu",
            at = @At(value = "INVOKE",
                    target = "Ldev/ftb/mods/ftblibrary/ui/BaseScreen;openContextMenu(Ljava/util/List;)"
                            + "Ldev/ftb/mods/ftblibrary/ui/ContextMenu;"),
            index = 0)
    private List<ContextMenuItem> shanhai$appendShopLinkItems(List<ContextMenuItem> items) {
        ViewQuestPanel panel = (ViewQuestPanel) (Object) this;
        Quest viewed = panel.getViewedQuest();
        if (viewed == null) return items;
        ShopQuestLinkMenu.appendTo(items, panel.getGui(), QuestObjectBase.getCodeString(viewed));
        return items;
    }
}
