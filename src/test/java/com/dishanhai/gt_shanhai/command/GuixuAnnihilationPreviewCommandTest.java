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

class GuixuAnnihilationPreviewCommandTest {
    @Test
    void registeredPreviewAcceptsLookTargetOrPositionAndRequiresAnOperator() {
        var dispatcher = new CommandDispatcher<CommandSourceStack>();
        DShanhaiCommands.onRegisterCommands(new RegisterCommandsEvent(dispatcher, Commands.CommandSelection.ALL, null));

        String command = "shanhai guixu annihilation preview";
        assertTrue(canRun(dispatcher, command, 2));
        assertTrue(canRun(dispatcher, command + " 72 21 0", 2));
        assertFalse(canRun(dispatcher, command + " 72 21", 2));
        assertFalse(canRun(dispatcher, command, 1));
        assertFalse(canRun(dispatcher, command + " 72 21 0", 1));
    }

    private static boolean canRun(CommandDispatcher<CommandSourceStack> dispatcher, String input, int permission) {
        var source = new CommandSourceStack(CommandSource.NULL, Vec3.ZERO, Vec2.ZERO, null,
                permission, "test", Component.empty(), null, null);
        var result = dispatcher.parse(input, source);
        return !result.getReader().canRead() && result.getContext().getCommand() != null;
    }
}
