package com.dishanhai.gt_shanhai.client;

import com.dishanhai.gt_shanhai.GTDishanhaiMod;
import com.dishanhai.gt_shanhai.network.RecipeEditorExportPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;

/** 配方修改器导出路径的点击：用资源管理器选中那个 json，而不是用默认程序打开它。 */
public final class RecipeExportChatLink {

    private RecipeExportChatLink() {}

    public static boolean reveal(String command) {
        if (command == null || !command.startsWith(RecipeEditorExportPacket.REVEAL_COMMAND)) return false;

        String raw = command.substring(RecipeEditorExportPacket.REVEAL_COMMAND.length()).trim();
        Path file;
        try {
            file = Path.of(raw).toAbsolutePath().normalize();
        } catch (InvalidPathException ex) {
            tell("§c路径无效");
            return true;
        }
        if (!RecipeEditorExportPacket.revealable(file) || !Files.isRegularFile(file)) {
            tell("§c找不到导出的文件");
            return true;
        }
        openExplorer(file);
        return true;
    }

    private static void openExplorer(Path file) {
        String systemRoot = System.getenv("SystemRoot");
        String explorer = systemRoot == null || systemRoot.isEmpty()
                ? "explorer.exe"
                : systemRoot + "\\explorer.exe";
        try {
            new ProcessBuilder(explorer, "/select," + file.toAbsolutePath()).start();
        } catch (Exception e) {
            GTDishanhaiMod.LOGGER.warn("[配方修改器] 无法打开资源管理器: {}", file, e);
            tell("§c无法打开资源管理器");
        }
    }

    private static void tell(String text) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            mc.player.displayClientMessage(Component.literal(text), false);
        }
    }
}
