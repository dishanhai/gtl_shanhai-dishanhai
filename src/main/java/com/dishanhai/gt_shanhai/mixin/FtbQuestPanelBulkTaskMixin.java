package com.dishanhai.gt_shanhai.mixin;

import com.dishanhai.gt_shanhai.api.ftbq.FtbqBulkTaskMenuHelper;
import dev.ftb.mods.ftblibrary.ui.ContextMenuItem;
import dev.ftb.mods.ftbquests.client.gui.quests.QuestPanel;
import dev.ftb.mods.ftbquests.client.gui.quests.QuestScreen;
import dev.ftb.mods.ftbquests.quest.Quest;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

import java.util.Collection;
import java.util.List;

@Mixin(value = QuestPanel.class, remap = false)
public class FtbQuestPanelBulkTaskMixin {

    @Shadow
    private QuestScreen questScreen;

    @ModifyArg(
            method = "mousePressed",
            at = @At(value = "INVOKE", target = "Ldev/ftb/mods/ftbquests/client/gui/quests/QuestScreen;openContextMenu(Ljava/util/List;)Ldev/ftb/mods/ftblibrary/ui/ContextMenu;"),
            index = 0
    )
    private List<ContextMenuItem> shanhai$addBulkTaskMenu(List<ContextMenuItem> original) {
        Collection<Quest> selectedQuests = this.questScreen.getSelectedQuests();
        Quest templateOwner = selectedQuests.isEmpty() ? null : selectedQuests.iterator().next();
        return FtbqBulkTaskMenuHelper.addBulkTaskMenu(original, this.questScreen, templateOwner, selectedQuests);
    }
}
