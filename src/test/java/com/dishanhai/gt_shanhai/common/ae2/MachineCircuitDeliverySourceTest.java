package com.dishanhai.gt_shanhai.common.ae2;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

public class MachineCircuitDeliverySourceTest {

    @Test
    void patternProviderWritesCircuitOntoMachineSlotAfterPush() throws Exception {
        String delivery = Files.readString(Path.of(
                "src/main/java/com/dishanhai/gt_shanhai/common/ae2/MachineCircuitDelivery.java"));
        String mixin = Files.readString(Path.of(
                "src/main/java/com/dishanhai/gt_shanhai/mixin/PatternProviderLogicCircuitMixin.java"));
        String config = Files.readString(Path.of("src/main/resources/gt_shanhai.mixin.json"));

        assertTrue(delivery.contains("pattern.getSparseInputs()"),
                "必須讀樣板編碼裡的編程電路，不能改走規劃輸入");
        assertTrue(delivery.contains("NotifiableCircuitItemStackHandler"),
                "只能寫 GTLCore 電路槽，不能把電路塞進普通物品欄");
        assertTrue(delivery.contains("handler.insertItem(0, incoming, false, true)"),
                "必須走電路槽的覆蓋寫入，才能改掉槽裡已有的電路號");
        assertTrue(delivery.contains("handler.onContentsChanged()"),
                "電路槽的 setStackInSlot 不會自己通知機器");
        assertTrue(delivery.contains("ItemStack.isSameItemSameTags(existing, incoming)"),
                "電路號沒變時不要反覆覆蓋");
        assertTrue(mixin.contains("shift = At.Shift.AFTER"),
                "必須等實料發進機器之後再寫電路，避免舊料配上新電路先開工");
        assertTrue(mixin.contains("MachineCircuitDelivery.deliver(this.host, direction, patternDetails)"),
                "必須寫進這次實際接收發料的那一側");
        assertTrue(config.contains("\"PatternProviderLogicCircuitMixin\""),
                "Mixin 必須註冊");
    }
}
