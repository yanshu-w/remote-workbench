package com.yanshuwang.remoteworkbench.sftp;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SftpServiceTest {

    @Test
    void testNormalizeRemotePath() {
        assertEquals("/", SftpService.normalizeRemotePath(null));
        assertEquals("/", SftpService.normalizeRemotePath(""));
        assertEquals("/", SftpService.normalizeRemotePath("   "));
        assertEquals("/", SftpService.normalizeRemotePath("/"));
        assertEquals("/root", SftpService.normalizeRemotePath("/root"));
        assertEquals("/root", SftpService.normalizeRemotePath("/root/"));
        assertEquals("/var/log", SftpService.normalizeRemotePath("var/log"));
        assertEquals("/var/log", SftpService.normalizeRemotePath("/var//log///"));
        assertEquals("/c/users", SftpService.normalizeRemotePath("c:\\users"));
    }

    @Test
    void testGetParentDirectory() {
        assertEquals("/", SftpService.getParentDirectory("/"));
        assertEquals("/", SftpService.getParentDirectory("/root"));
        assertEquals("/var", SftpService.getParentDirectory("/var/log"));
        assertEquals("/var/log", SftpService.getParentDirectory("/var/log/nginx"));
    }

    @Test
    void testJoinPath() {
        assertEquals("/file.txt", SftpService.joinPath("/", "file.txt"));
        assertEquals("/root/file.txt", SftpService.joinPath("/root", "file.txt"));
        assertEquals("/var/log/nginx", SftpService.joinPath("/var/log/", "nginx"));
    }
}
