package com.dishanhai.gt_shanhai.mixin;

import com.dishanhai.gt_shanhai.api.ftbq.FtbqBulkTaskMenuHelper;
import dev.ftb.mods.ftblibrary.ui.ContextMenuItem;
import dev.ftb.mods.ftbquests.client.gui.quests.QuestButton;
import dev.ftb.mods.ftbquests.client.gui.quests.QuestScreen;
import dev.ftb.mods.ftbquests.quest.Quest;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

import java.util.List;

@Mixin(value = QuestButton.class, remap = false)
public class FtbQuestButtonBulkTaskMixin {

    @Shadow
    protected QuestScreen questScreen;

    @Shadow
    Quest quest;

    @ModifyArg(
            method = "onClicked",
            at = @At(value = "INVOKE", target = "Ldev/ftb/mods/ftblibrary/ui/BaseScreen;openContextMenu(Ljava/util/List;)Ldev/ftb/mods/ftblibrary/ui/ContextMenu;"),
            index = 0
    )
    private List<ContextMenuItem> shanhai$addBulkTaskMenu(List<ContextMenuItem> original) {
        return FtbqBulkTaskMenuHelper.addBulkTaskMenu(original, this.questScreen, this.quest, this.questScreen.getSelectedQuests());
    }
}
