// Author: dishanhai
package com.dishanhai.gt_shanhai.client.gui;

import com.dishanhai.sh_common_lib.api.client.announcement.AnnouncementCatalog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ChangelogCompatibilityTest {
    @TempDir
    Path directory;

    @Test
    void readsExistingBilingualChangelog() throws Exception {
        var source = Path.of("config/gt_shanhai/changelog/Update-4.9.1.md");
        Files.copy(source, directory.resolve(source.getFileName()));
        var document = new AnnouncementCatalog(directory).documents().get(0);

        assertEquals("4.9.1", document.version());
        assertEquals("gt_shanhai 4.9.1 更新公告", document.title(false));
        assertEquals("gt_shanhai 4.9.1 Release Notes", document.title(true));
        assertTrue(document.body(false).contains("## 联机兼容"));
        assertTrue(document.body(true).contains("## Multiplayer Compatibility"));
        assertFalse(document.body(true).contains("</details>"));
        assertEquals(7, document.links().size());
        assertTrue(document.links().get(0).label(true).contains("GitHub Repository"));
    }

    @Test
    void sortsByFileVersionAndReusesExistingSeenFile() throws Exception {
        Files.writeString(directory.resolve("Update-4.9.0.md"), "# Old");
        Path nested = Files.createDirectory(directory.resolve("history"));
        Path latest = nested.resolve("Update-4.10.0.md");
        Files.writeString(latest, "---\nversion: current\nenabled: false\n---\n# New");
        Path seen = directory.resolve("changelog_seen.txt");
        Files.writeString(seen, "4.9.0\n");

        var catalog = new AnnouncementCatalog(directory);
        var document = catalog.documents().get(0);
        assertEquals("current", document.version());
        assertFalse(document.enabled());
        assertEquals("4.9.0", catalog.seenVersion());

        assertTrue(catalog.markSeen(document));
        assertEquals("current", Files.readString(seen));
        assertEquals("current", new AnnouncementCatalog(directory).seenVersion());
    }
}
