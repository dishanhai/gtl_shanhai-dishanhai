package com.dishanhai.gt_shanhai.api.ftbq;

import dev.ftb.mods.ftblibrary.icon.Icons;
import dev.ftb.mods.ftblibrary.ui.ContextMenuItem;
import dev.ftb.mods.ftblibrary.ui.Panel;
import dev.ftb.mods.ftbquests.net.CreateObjectMessage;
import dev.ftb.mods.ftbquests.quest.Quest;
import dev.ftb.mods.ftbquests.quest.QuestObjectBase;
import dev.ftb.mods.ftbquests.quest.task.Task;
import dev.ftb.mods.ftbquests.quest.task.TaskType;
import dev.ftb.mods.ftbquests.quest.task.TaskTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

public final class FtbqBulkTaskMenuHelper {
    private FtbqBulkTaskMenuHelper() {}

    public static List<ContextMenuItem> addBulkTaskMenu(List<ContextMenuItem> original, Panel panel, Quest templateOwner, Collection<Quest> selectedQuests) {
        if (selectedQuests == null || selectedQuests.size() <= 1 || templateOwner == null) {
            return original;
        }
        List<ContextMenuItem> menu = new ArrayList<>(original);
        menu.add(Math.min(1, menu.size()), new ContextMenuItem(Component.literal("全部添加目标......"), Icons.ADD,
                button -> panel.getGui().openContextMenu(createBulkTaskTypeMenu(panel, templateOwner, selectedQuests))));
        return menu;
    }

    private static List<ContextMenuItem> createBulkTaskTypeMenu(Panel panel, Quest templateOwner, Collection<Quest> selectedQuests) {
        List<ContextMenuItem> taskMenu = new ArrayList<>();
        for (TaskType taskType : TaskTypes.TYPES.values()) {
            if (taskType.getGuiProvider() == null) continue;
            taskMenu.add(new ContextMenuItem(taskType.getDisplayName(), taskType.getIconSupplier(), button -> {
                Quest templateQuest = new Quest(0L, templateOwner.getChapter());
                taskType.getGuiProvider().openCreationGui(panel, templateQuest,
                        templateTask -> sendBulkTaskCreate(selectedQuests, taskType, templateTask));
            }));
        }
        return taskMenu;
    }

    private static void sendBulkTaskCreate(Collection<Quest> selectedQuests, TaskType taskType, Task templateTask) {
        CompoundTag extra = new CompoundTag();
        extra.putString("type", taskType.getTypeForNBT());
        for (Quest targetQuest : selectedQuests) {
            Task copiedTask = QuestObjectBase.copy(templateTask, () -> taskType.createTask(0L, targetQuest));
            if (copiedTask != null) {
                new CreateObjectMessage(copiedTask, extra, false).sendToServer();
            }
        }
    }
}
