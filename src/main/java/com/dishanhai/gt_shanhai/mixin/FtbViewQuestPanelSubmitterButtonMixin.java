package com.dishanhai.gt_shanhai.mixin;

import com.dishanhai.gt_shanhai.network.FtbqSubmitterListRequestPacket;
import com.dishanhai.gt_shanhai.network.ShanhaiNetwork;
import dev.ftb.mods.ftblibrary.icon.Icons;
import dev.ftb.mods.ftblibrary.ui.SimpleButton;
import dev.ftb.mods.ftblibrary.ui.TextField;
import dev.ftb.mods.ftblibrary.ui.Widget;
import dev.ftb.mods.ftbquests.client.gui.quests.ViewQuestPanel;
import dev.ftb.mods.ftbquests.quest.Quest;
import dev.ftb.mods.ftbquests.quest.QuestObjectBase;
import dev.ftb.mods.ftbquests.quest.task.ItemTask;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = ViewQuestPanel.class, remap = false)
public abstract class FtbViewQuestPanelSubmitterButtonMixin {
    @Shadow private Quest quest;
    @Shadow private TextField titleField;

    @Inject(method = "addWidgets", at = @At("TAIL"))
    private void shanhai$addSubmitterQueueButton(CallbackInfo ci) {
        if (quest == null) return;
        ItemTask itemTask = shanhai$firstQueueableItemTask(quest);
        if (itemTask == null) return;
        ViewQuestPanel panel = (ViewQuestPanel) (Object) this;
        int iconSize = titleField == null ? 16 : Math.min(16, titleField.height + 2);
        SimpleButton button = new SimpleButton(panel, Component.literal("登记到提交器"), Icons.ADD, (widget, mouseButton) -> {
            ShanhaiNetwork.CHANNEL.sendToServer(new FtbqSubmitterListRequestPacket(QuestObjectBase.getID(itemTask)));
        });
        panel.add(button);
        button.setPosAndSize(shanhai$freeLeftX(panel, iconSize), 4, iconSize, iconSize);
    }

    /**
     * 顶栏左侧第一个空位。任务图标占 [4, 4+iconSize]（面板自己 drawBackground 画的，不是控件），
     * 跨章节查看时 FTBQ 还会紧挨着放一个 {@code GotoLinkedQuestButton}
     * （{@code addWidgets} 里 {@code setPosAndSize(iconSize + 4, 0, ...)}）——原来写死 x=24 正好跟它重叠。
     *
     * <p>这里不去读 {@code QuestScreen.selectedChapter}（包私有，跨包取不到），改成扫已经排好的控件：
     * 左半边、y=0 的那个就是它。右上角那排（链接/置顶/关闭）x 都在面板右半边，不会误判。</p>
     */
    private static int shanhai$freeLeftX(ViewQuestPanel panel, int iconSize) {
        int x = iconSize + 8; // 任务图标之后
        for (Widget widget : panel.getWidgets()) {
            if (widget.getY() == 0 && widget.getX() < panel.width / 2) {
                x = Math.max(x, widget.getX() + widget.width + 2);
            }
        }
        return x;
    }

    private ItemTask shanhai$firstQueueableItemTask(Quest quest) {
        for (dev.ftb.mods.ftbquests.quest.task.Task task : quest.getTasks()) {
            if (task instanceof ItemTask itemTask && itemTask.consumesResources()) {
                return itemTask;
            }
        }
        return null;
    }
}
