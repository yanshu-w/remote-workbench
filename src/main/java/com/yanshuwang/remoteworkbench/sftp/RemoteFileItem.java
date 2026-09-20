package com.yanshuwang.remoteworkbench.sftp;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.Objects;

public record RemoteFileItem(
        String name,
        String path,
        long size,
        boolean isDirectory,
        boolean isLink,
        String permissions,
        long modifyTime
) implements Comparable<RemoteFileItem> {

    private static final DateTimeFormatter TIME_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    private static final Comparator<RemoteFileItem> COMPARATOR = Comparator
            .comparing(RemoteFileItem::isDirectory, Comparator.reverseOrder())
            .thenComparing(RemoteFileItem::name, String.CASE_INSENSITIVE_ORDER);

    public RemoteFileItem {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(path, "path");
        permissions = (permissions == null || permissions.isBlank()) ? "-" : permissions;
    }

    public String formattedSize() {
        if (isDirectory) {
            return "-";
        }
        if (size < 1024) {
            return size + " B";
        }
        int exp = (int) (Math.log(size) / Math.log(1024));
        char unit = "KMGTPE".charAt(exp - 1);
        return String.format("%.1f %cB", size / Math.pow(1024, exp), unit);
    }

    public String formattedModifyTime() {
        if (modifyTime <= 0) {
            return "-";
        }
        return TIME_FORMATTER.format(Instant.ofEpochMilli(modifyTime));
    }

    public String typeDisplayName() {
        if (isLink) {
            return isDirectory ? "符号链接目录" : "符号链接";
        }
        return isDirectory ? "文件夹" : "文件";
    }

    @Override
    public int compareTo(RemoteFileItem other) {
        return COMPARATOR.compare(this, other);
    }
}
