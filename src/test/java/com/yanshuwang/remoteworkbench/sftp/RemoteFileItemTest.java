package com.yanshuwang.remoteworkbench.sftp;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RemoteFileItemTest {

    @Test
    void testFormattedSize() {
        RemoteFileItem dir = new RemoteFileItem("docs", "/home/user/docs", 4096, true, false, "drwxr-xr-x", 0);
        assertEquals("-", dir.formattedSize());

        RemoteFileItem smallFile = new RemoteFileItem("test.txt", "/test.txt", 500, false, false, "-rw-r--r--", 0);
        assertEquals("500 B", smallFile.formattedSize());

        RemoteFileItem kbFile = new RemoteFileItem("test.txt", "/test.txt", 1024 * 5, false, false, "-rw-r--r--", 0);
        assertEquals("5.0 KB", kbFile.formattedSize());

        RemoteFileItem mbFile = new RemoteFileItem("app.jar", "/app.jar", 1024 * 1024 * 12 + 500000, false, false, "-rw-r--r--", 0);
        assertTrue(mbFile.formattedSize().startsWith("12."));
        assertTrue(mbFile.formattedSize().endsWith("MB"));
    }

    @Test
    void testSortingDirectoriesFirstThenAlphabetical() {
        RemoteFileItem fileB = new RemoteFileItem("b.txt", "/b.txt", 100, false, false, "-", 0);
        RemoteFileItem dirZ = new RemoteFileItem("z_folder", "/z_folder", 0, true, false, "-", 0);
        RemoteFileItem fileA = new RemoteFileItem("a.txt", "/a.txt", 200, false, false, "-", 0);
        RemoteFileItem dirA = new RemoteFileItem("a_folder", "/a_folder", 0, true, false, "-", 0);

        List<RemoteFileItem> items = new ArrayList<>(List.of(fileB, dirZ, fileA, dirA));
        Collections.sort(items);

        assertEquals("a_folder", items.get(0).name());
        assertEquals("z_folder", items.get(1).name());
        assertEquals("a.txt", items.get(2).name());
        assertEquals("b.txt", items.get(3).name());
    }

    @Test
    void testTypeDisplayName() {
        RemoteFileItem dir = new RemoteFileItem("folder", "/folder", 0, true, false, "-", 0);
        RemoteFileItem file = new RemoteFileItem("file.txt", "/file.txt", 0, false, false, "-", 0);
        RemoteFileItem link = new RemoteFileItem("link", "/link", 0, false, true, "-", 0);
        RemoteFileItem linkDir = new RemoteFileItem("linkDir", "/linkDir", 0, true, true, "-", 0);

        assertEquals("文件夹", dir.typeDisplayName());
        assertEquals("文件", file.typeDisplayName());
        assertEquals("符号链接", link.typeDisplayName());
        assertEquals("符号链接目录", linkDir.typeDisplayName());
    }
}
