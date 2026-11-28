package com.dishanhai.gt_shanhai.integration.jei;

// Author: dishanhai

import com.lowdragmc.lowdraglib.gui.widget.Widget;
import com.lowdragmc.lowdraglib.gui.widget.WidgetGroup;
import mezz.jei.api.gui.ingredient.IRecipeSlotDrawable;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import org.junit.jupiter.api.Test;

import java.io.DataInputStream;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class AdaptiveRecipeUITest {
    @Test
    void keepsRoomForTheLastPartialRow() {
        assertEquals(0, AdaptiveRecipeUI.sectionHeight(0, 6));
        assertEquals(29, AdaptiveRecipeUI.sectionHeight(1, 6));
        assertEquals(29, AdaptiveRecipeUI.sectionHeight(6, 6));
        assertEquals(47, AdaptiveRecipeUI.sectionHeight(7, 6));
        assertEquals(389, AdaptiveRecipeUI.sectionHeight(121, 6));
    }

    @Test
    void jeiOnlyHitsTheRowCurrentlyInsideTheViewport() {
        var viewport = new AdaptiveRecipeUI.ScrollViewport(12, 40, 18, 18);
        var firstRow = new WidgetGroup(0, 0, 18, 18);
        var lastRow = new WidgetGroup(0, 18, 18, 18);
        var first = new Widget(0, 0, 18, 18);
        var last = new Widget(0, 0, 18, 18);
        firstRow.addWidget(first);
        lastRow.addWidget(last);
        viewport.addWidget(firstRow);
        viewport.addWidget(lastRow);

        var drawable = (IRecipeSlotDrawable) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] { IRecipeSlotDrawable.class }, (proxy, method, args) -> null);
        var firstJeiSlot = new AdaptiveRecipeJeiSlot(first, drawable);
        var lastJeiSlot = new AdaptiveRecipeJeiSlot(last, drawable);
        assertTrue(firstJeiSlot.getSlotUnderMouse(4, 16).isPresent());
        assertTrue(lastJeiSlot.getSlotUnderMouse(4, 34).isEmpty());

        viewport.setScrollYOffset(9);
        assertTrue(firstJeiSlot.getSlotUnderMouse(4, 8).isEmpty());
        assertTrue(lastJeiSlot.getSlotUnderMouse(4, 29).isEmpty());

        viewport.mouseWheelMove(4, 16, -1);
        assertEquals(18, viewport.getScrollYOffset());
        assertTrue(firstJeiSlot.getSlotUnderMouse(4, 8).isEmpty());
        assertTrue(lastJeiSlot.getSlotUnderMouse(4, 16).isPresent());
        assertTrue(lastJeiSlot.getSlotUnderMouse(4, 34).isEmpty());
    }

    @Test
    void templatesProvideIndependentScrollRegionsAndProgress() throws Exception {
        var resources = Path.of("src/main/resources/assets");
        for (var template : Set.of("gtceu/ui/recipe_type/stellar_deconstruction.rtui",
                "gt_shanhai/ui/recipe_type/adaptive.rtui")) {
            try (var input = new DataInputStream(Files.newInputStream(resources.resolve(template)))) {
                var root = NbtIo.read(input).getCompound("root");
                assertEquals(-1, input.read());
                var rootSize = root.getCompound("size");
                var ids = new HashSet<String>();
                int previousRight = 0;
                for (var child : root.getList("children", Tag.TAG_COMPOUND)) {
                    var data = ((net.minecraft.nbt.CompoundTag) child).getCompound("data");
                    var pos = data.getCompound("selfPosition");
                    var size = data.getCompound("size");
                    assertTrue(ids.add(data.getString("id")));
                    assertTrue(pos.getInt("x") >= previousRight);
                    assertTrue(pos.getInt("y") >= 0);
                    previousRight = pos.getInt("x") + size.getInt("width");
                    assertTrue(previousRight <= rootSize.getInt("width"));
                    assertTrue(pos.getInt("y") + size.getInt("height") <= rootSize.getInt("height"));
                }
                assertEquals(Set.of("adaptive_inputs", "progress", "adaptive_outputs"), ids);
            }
        }
    }
}
