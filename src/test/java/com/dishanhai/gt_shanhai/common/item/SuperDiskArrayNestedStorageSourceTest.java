package com.dishanhai.gt_shanhai.common.item;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class SuperDiskArrayNestedStorageSourceTest {

    private static final Path INVENTORY = Path.of("src", "main", "java", "com", "dishanhai",
            "gt_shanhai", "common", "item", "SuperDiskArrayInventory.java");
    private static final Path DISK_HATCH = Path.of("src", "main", "java", "com", "dishanhai",
            "gt_shanhai", "common", "machine", "part", "MEDiskHatchPartMachine.java");
    private static final Path DATA_HUB = Path.of("src", "main", "java", "com", "dishanhai",
            "gt_shanhai", "common", "machine", "misc", "SingularityDataHubMachine.java");

    @Test
    void sdaCannotBeInsertedIntoAnotherSda() throws IOException {
        String source = Files.readString(INVENTORY);
        assertTrue(source.contains("return false;"), "SDA 不得声明为可嵌套存储单元");
        assertTrue(source.contains("itemKey.getItem() instanceof SuperDiskArrayItem"),
                "SDA insert 必须拒绝另一个 SDA 载体");
    }

    @Test
    void diskHatchKeepsNestedSdaAsAnOrdinaryItem() throws IOException {
        String source = Files.readString(DISK_HATCH);
        assertTrue(source.contains("innerStack.getItem() instanceof SuperDiskArrayItem"),
                "磁盘仓室必须识别嵌套 SDA");
        assertTrue(source.contains("避免自引用挂载"),
                "磁盘仓室必须跳过嵌套 SDA 的递归挂载");
    }

    @Test
    void dataHubKeepsNestedSdaAsAnOrdinaryItem() throws IOException {
        String source = Files.readString(DATA_HUB);
        assertTrue(source.contains("innerStack.getItem() instanceof SuperDiskArrayItem"),
                "奇点数据中枢必须识别嵌套 SDA");
        assertTrue(source.contains("不能递归挂载其内部库存"),
                "奇点数据中枢必须跳过嵌套 SDA 的递归挂载");
    }
}
