package com.yanshuwang.remoteworkbench.ui;

import com.yanshuwang.remoteworkbench.connection.ConnectionProfile;
import com.yanshuwang.remoteworkbench.sftp.SftpService;
import com.yanshuwang.remoteworkbench.ssh.SshConnectionService;
import com.yanshuwang.remoteworkbench.ui.terminal.CursorStyle;
import com.yanshuwang.remoteworkbench.ui.terminal.TerminalTheme;
import com.yanshuwang.remoteworkbench.ui.theme.ThemeManager;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;

import java.util.Objects;

/**
 * Encapsulates the complete workspace (Terminal Tabs + SFTP Browser) for a specific SSH connection profile.
 * Allows multiple servers to remain connected and active simultaneously without interfering with each other.
 */
public final class ProfileWorkspace implements AutoCloseable {
    private final ConnectionProfile profile;
    private final TerminalSessionTabPane terminalSessionPane;
    private final SftpBrowserView sftpBrowserView;
    private final TabPane workspaceTabs;
    private final Tab terminalTab;
    private final Tab sftpTab;

    public ProfileWorkspace(
            ConnectionProfile profile,
            String fontFamily,
            int fontSize,
            TerminalTheme theme,
            CursorStyle cursorStyle,
            boolean cursorBlink
    ) {
        this.profile = Objects.requireNonNull(profile, "profile");
        this.terminalSessionPane = new TerminalSessionTabPane();
        this.terminalSessionPane.applyFont(fontFamily, fontSize);
        this.terminalSessionPane.applyTerminalSettings(theme != null ? theme.resolveEffectiveTheme(ThemeManager.isDarkMode()) : null, cursorStyle, cursorBlink);
        this.sftpBrowserView = new SftpBrowserView();

        this.terminalTab = new Tab("终端 (SSH)", terminalSessionPane);
        this.sftpTab = new Tab("文件管理 (SFTP)", sftpBrowserView);

        this.terminalSessionPane.setOnOpenInSftpRequested(this::openInSftp);

        this.workspaceTabs = new TabPane(terminalTab, sftpTab);
        this.workspaceTabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        this.workspaceTabs.getStyleClass().add("workspace-tabs");
    }

    public void openInSftp(String path) {
        workspaceTabs.getSelectionModel().select(sftpTab);
        if (path != null && !path.isBlank()) {
            sftpBrowserView.navigateToAndSelect(path);
        }
    }

    public void selectTerminalTab() {
        workspaceTabs.getSelectionModel().select(terminalTab);
    }

    public void selectSftpTab() {
        workspaceTabs.getSelectionModel().select(sftpTab);
    }

    public ConnectionProfile getProfile() {
        return profile;
    }

    public TabPane getRoot() {
        return workspaceTabs;
    }

    public TerminalSessionTabPane getTerminalSessionPane() {
        return terminalSessionPane;
    }

    public SftpBrowserView getSftpBrowserView() {
        return sftpBrowserView;
    }

    public TerminalView getActiveTerminalView() {
        return terminalSessionPane.getActiveTerminalView();
    }

    public void setOnActiveWorkingDirectoryChanged(java.util.function.Consumer<String> listener) {
        terminalSessionPane.setOnActiveWorkingDirectoryChanged(listener);
    }

    public void setOnActiveTerminalSizeChanged(java.util.function.Consumer<String> listener) {
        terminalSessionPane.setOnActiveTerminalSizeChanged(listener);
    }

    public void setOnMetricsListener(java.util.function.Consumer<com.yanshuwang.remoteworkbench.monitor.SystemProbeService.ServerMetrics> listener) {
        terminalSessionPane.setOnMetricsListener(listener);
    }

    public String getActiveWorkingDirectory() {
        return terminalSessionPane.getActiveWorkingDirectory();
    }

    public String getActiveTerminalSize() {
        return terminalSessionPane.getActiveTerminalSize();
    }

    public void attach(SshConnectionService sshService, SftpService sftpService) {
        terminalSessionPane.attachConnection(profile, sshService);
        sftpBrowserView.attachConnection(profile, sftpService);
    }

    public void detach() {
        terminalSessionPane.detach();
        sftpBrowserView.detach();
    }

    public void detachForIdleTimeout(int minutes) {
        terminalSessionPane.detachForIdleTimeout(minutes);
        sftpBrowserView.detach();
    }

    public void applyFont(String family, int size) {
        terminalSessionPane.applyFont(family, size);
    }

    public void applyTerminalSettings(TerminalTheme theme, CursorStyle cursorStyle, boolean cursorBlink) {
        terminalSessionPane.applyTerminalSettings(theme != null ? theme.resolveEffectiveTheme(ThemeManager.isDarkMode()) : null, cursorStyle, cursorBlink);
    }

    public void setOnReconnectRequested(Runnable runnable) {
        terminalSessionPane.setOnReconnectRequested(runnable);
    }

    public void setServerStatusEnabled(boolean enabled) {
        terminalSessionPane.setServerStatusEnabled(enabled);
    }

    @Override
    public void close() {
        terminalSessionPane.close();
        sftpBrowserView.detach();
    }
}
