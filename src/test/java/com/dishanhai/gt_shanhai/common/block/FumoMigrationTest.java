// Author: dishanhai
package com.dishanhai.gt_shanhai.common.block;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Equipable;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class FumoMigrationTest {
    @Test
    void forgeFumoItemSupportsVanillaRightClickEquipment() throws ReflectiveOperationException {
        var item = Class.forName("com.dishanhai.sh_common_lib.internal.block.ForgeFumoHeadItem",
                false, getClass().getClassLoader());

        assertTrue(BlockItem.class.isAssignableFrom(item));
        assertTrue(Equipable.class.isAssignableFrom(item));
        item.getDeclaredMethod("use", Level.class, Player.class, InteractionHand.class);
    }

    @Test
    void shanhaiCreativeTabIncludesAllThreeCanonicalPlushies() throws Exception {
        var root = Path.of("src/main/java/com/dishanhai/gt_shanhai/common");
        var creativeTab = Files.readString(root.resolve("DShanhaiCreativeModeTabs.java"));
        var fumos = Files.readString(root.resolve("block/DShanhaiFumos.java"));

        assertTrue(creativeTab.contains("DShanhaiFumos.addCreativeItems(output)"));
        assertTrue(fumos.contains("sh_common_lib\", \"dishanhai_fumo"));
        assertTrue(fumos.contains("sh_common_lib\", \"beibeipuo_fumo"));
        assertTrue(fumos.contains("output.accept(dgyjh02804Item())"));
    }
}
