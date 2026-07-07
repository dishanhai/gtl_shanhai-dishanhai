package com.dishanhai.gt_shanhai.client;

import com.dishanhai.gt_shanhai.common.machine.misc.FtbqAeSubmitterMachine;
import com.dishanhai.gt_shanhai.network.FtbqQueueTaskPacket;
import com.dishanhai.gt_shanhai.network.ShanhaiNetwork;
import dev.ftb.mods.ftblibrary.icon.Icons;
import dev.ftb.mods.ftblibrary.ui.BaseScreen;
import dev.ftb.mods.ftblibrary.ui.ContextMenuItem;
import dev.ftb.mods.ftblibrary.util.client.ClientUtils;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class FtbqSubmitterSelectionClient {
    private FtbqSubmitterSelectionClient() {}

    public static void open(long taskId, List<FtbqAeSubmitterMachine.SubmitterEntry> entries) {
        BaseScreen screen = ClientUtils.getCurrentGuiAs(BaseScreen.class);
        if (screen == null) {
            return;
        }
        List<ContextMenuItem> menu = new ArrayList<>();
        if (entries.isEmpty()) {
            menu.add(new ContextMenuItem(Component.literal("没有已绑定本队伍的 FTBQ 自动提交器"), Icons.INFO_GRAY, null).setCloseMenu(false));
        } else {
            for (FtbqAeSubmitterMachine.SubmitterEntry entry : entries) {
                UUID id = entry.id();
                String shortId = id.toString().substring(0, 8);
                String online = entry.online() ? "在线" : "离线";
                Component title = Component.literal(shortId + "  " + entry.pos() + "  队列 " + entry.queueSize() + "  " + online);
                menu.add(new ContextMenuItem(title, Icons.ADD, button -> {
                    ShanhaiNetwork.CHANNEL.sendToServer(new FtbqQueueTaskPacket(id, taskId));
                }));
            }
        }
        screen.openContextMenu(menu);
    }
}
