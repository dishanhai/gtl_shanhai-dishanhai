package com.dishanhai.gt_shanhai.command;

import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.RegisterCommandsEvent;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GuixuStellarEndCommandTest {

    @Test
    void registeredCommandsRequireAnOperatorAndControllerCoordinates() {
        var dispatcher = new CommandDispatcher<CommandSourceStack>();
        DShanhaiCommands.onRegisterCommands(new RegisterCommandsEvent(dispatcher, Commands.CommandSelection.ALL, null));

        for (String action : new String[]{"start", "stop"}) {
            String input = "shanhai guixu stellar_end 72 21 0 " + action;
            assertTrue(canRun(dispatcher, input, 2));
            assertFalse(canRun(dispatcher, input, 1));
            assertFalse(canRun(dispatcher, "shanhai guixu stellar_end " + action, 2));
        }
    }

    @Test
    void blackHolePreviewSupportsCrosshairOrCoordinatesAndRequiresAnOperator() {
        var dispatcher = new CommandDispatcher<CommandSourceStack>();
        DShanhaiCommands.onRegisterCommands(new RegisterCommandsEvent(dispatcher, Commands.CommandSelection.ALL, null));
        String command = "shanhai guixu blackhole_preview";
        for (String suffix : new String[]{"", " start", " stop"}) {
            assertTrue(canRun(dispatcher, command + suffix, 2));
            assertFalse(canRun(dispatcher, command + suffix, 1));
            assertTrue(canRun(dispatcher, command + " 72 21 0" + suffix, 2));
            assertFalse(canRun(dispatcher, command + " 72 21 0" + suffix, 1));
        }
        assertFalse(canRun(dispatcher, command + " 72 21 0 invalid", 2));
    }

    private static boolean canRun(CommandDispatcher<CommandSourceStack> dispatcher, String input, int permission) {
        var source = new CommandSourceStack(CommandSource.NULL, Vec3.ZERO, Vec2.ZERO, null,
                permission, "test", Component.empty(), null, null);
        var result = dispatcher.parse(input, source);
        return !result.getReader().canRead() && result.getContext().getCommand() != null;
    }
}
