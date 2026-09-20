package com.yanshuwang.remoteworkbench.ui;

import com.yanshuwang.remoteworkbench.monitor.SystemProbeService.ServerMetrics;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.shape.Circle;

public final class MiniDashboardView extends HBox {
    private final Circle cpuDot = new Circle(4);
    private final Label cpuLabel = new Label("CPU --%");
    private final HBox cpuBadge = new HBox(6, cpuDot, cpuLabel);
    private final Tooltip cpuTooltip = new Tooltip("CPU 监控");

    private final Circle memDot = new Circle(4);
    private final Label memLabel = new Label("RAM --%");
    private final HBox memBadge = new HBox(6, memDot, memLabel);
    private final Tooltip memTooltip = new Tooltip("内存监控");

    private final Label netIcon = new Label("🌐");
    private final Label netLabel = new Label("↑ 0 B/s  ↓ 0 B/s");
    private final HBox netBadge = new HBox(6, netIcon, netLabel);
    private final Tooltip netTooltip = new Tooltip("网络实时流量");

    private final Label standbyLabel = new Label("监控就绪");

    public MiniDashboardView() {
        setAlignment(Pos.CENTER_RIGHT);
        setSpacing(8);
        getStyleClass().add("mini-dashboard-container");

        styleBadge(cpuBadge);
        styleBadge(memBadge);
        styleBadge(netBadge);

        cpuLabel.getStyleClass().add("mini-dashboard-text");
        memLabel.getStyleClass().add("mini-dashboard-text");
        netLabel.getStyleClass().add("mini-dashboard-text");
        netIcon.getStyleClass().add("mini-dashboard-icon");

        Tooltip.install(cpuBadge, cpuTooltip);
        Tooltip.install(memBadge, memTooltip);
        Tooltip.install(netBadge, netTooltip);

        standbyLabel.getStyleClass().add("status-muted");
        standbyLabel.setStyle("-fx-font-size: 11px;");

        resetToStandby();
    }

    private void styleBadge(HBox badge) {
        badge.setAlignment(Pos.CENTER);
        badge.getStyleClass().add("mini-dashboard-badge");
    }

    public void updateMetrics(ServerMetrics metrics) {
        if (metrics == null || !metrics.available()) {
            resetToStandby();
            return;
        }

        if (getChildren().contains(standbyLabel)) {
            getChildren().setAll(cpuBadge, memBadge, netBadge);
        }

        // 1. CPU
        double cpu = metrics.cpuPercent();
        cpuLabel.setText(String.format("CPU %.1f%%", cpu));
        setDotColor(cpuDot, cpu, 70.0, 85.0);
        cpuTooltip.setText(String.format("CPU 实时使用率：%.1f%%", cpu));

        // 2. Memory
        double memPct = metrics.memPercent();
        String memUsedStr = formatBytes(metrics.memUsedBytes());
        String memTotalStr = formatBytes(metrics.memTotalBytes());
        memLabel.setText(String.format("RAM %.0f%% (%s)", memPct, memUsedStr));
        setDotColor(memDot, memPct, 75.0, 90.0);
        memTooltip.setText(String.format("内存使用情况\n已用：%s / 总计：%s\n使用占比：%.1f%%", memUsedStr, memTotalStr, memPct));

        // 3. Network
        String upStr = formatRate(metrics.txBytesPerSec());
        String downStr = formatRate(metrics.rxBytesPerSec());
        netLabel.setText(String.format("↑ %s  ↓ %s", upStr, downStr));
        netTooltip.setText(String.format("网络实时吞吐量\n上行速率：%s\n下行速率：%s", upStr, downStr));
    }

    public void resetToStandby() {
        cpuDot.setFill(javafx.scene.paint.Color.web("#8f949f"));
        memDot.setFill(javafx.scene.paint.Color.web("#8f949f"));
        cpuLabel.setText("CPU --%");
        memLabel.setText("RAM --%");
        netLabel.setText("↑ 0 B/s  ↓ 0 B/s");
        getChildren().setAll(standbyLabel);
    }

    private static void setDotColor(Circle dot, double value, double warnThreshold, double alertThreshold) {
        if (value >= alertThreshold) {
            dot.setFill(javafx.scene.paint.Color.web("#f87171")); // Red
        } else if (value >= warnThreshold) {
            dot.setFill(javafx.scene.paint.Color.web("#fbbf24")); // Amber
        } else {
            dot.setFill(javafx.scene.paint.Color.web("#34d399")); // Emerald green
        }
    }

    public static String formatBytes(long bytes) {
        if (bytes <= 0) return "0 B";
        final String[] units = new String[]{"B", "KB", "MB", "GB", "TB"};
        int digitGroups = (int) (Math.log10(bytes) / Math.log10(1024));
        digitGroups = Math.max(0, Math.min(units.length - 1, digitGroups));
        return String.format("%.1f %s", bytes / Math.pow(1024, digitGroups), units[digitGroups]);
    }

    public static String formatRate(double bytesPerSec) {
        if (bytesPerSec <= 0) return "0 B/s";
        final String[] units = new String[]{"B/s", "KB/s", "MB/s", "GB/s"};
        int digitGroups = (int) (Math.log10(bytesPerSec) / Math.log10(1024));
        digitGroups = Math.max(0, Math.min(units.length - 1, digitGroups));
        return String.format("%.1f %s", bytesPerSec / Math.pow(1024, digitGroups), units[digitGroups]);
    }
}
