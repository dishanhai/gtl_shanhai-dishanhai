package com.dishanhai.gt_shanhai.mixin;

import com.dishanhai.gt_shanhai.network.FtbqSubmitterListRequestPacket;
import com.dishanhai.gt_shanhai.network.ShanhaiNetwork;
import dev.ftb.mods.ftblibrary.icon.Icons;
import dev.ftb.mods.ftblibrary.ui.SimpleButton;
import dev.ftb.mods.ftbquests.client.gui.quests.QuestScreen;
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

    @Inject(method = "addWidgets", at = @At("TAIL"))
    private void shanhai$addSubmitterQueueButton(CallbackInfo ci) {
        if (quest == null) return;
        ItemTask itemTask = shanhai$firstQueueableItemTask(quest);
        if (itemTask == null) return;
        ViewQuestPanel panel = (ViewQuestPanel) (Object) this;
        int iconSize = 16;
        int buttonX = 24;
        SimpleButton button = new SimpleButton(panel, Component.literal("登记到提交器"), Icons.ADD, (widget, mouseButton) -> {
            ShanhaiNetwork.CHANNEL.sendToServer(new FtbqSubmitterListRequestPacket(QuestObjectBase.getID(itemTask)));
        });
        panel.add(button);
        button.setPosAndSize(buttonX, 4, iconSize, iconSize);
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
