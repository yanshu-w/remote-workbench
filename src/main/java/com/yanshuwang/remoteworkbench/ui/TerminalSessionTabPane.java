package com.yanshuwang.remoteworkbench.ui;

import com.yanshuwang.remoteworkbench.connection.ConnectionProfile;
import com.yanshuwang.remoteworkbench.monitor.SystemProbeService;
import com.yanshuwang.remoteworkbench.ssh.SshConnectionService;
import com.yanshuwang.remoteworkbench.ui.terminal.CursorStyle;
import com.yanshuwang.remoteworkbench.ui.terminal.TerminalTheme;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextInputDialog;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public final class TerminalSessionTabPane extends BorderPane implements AutoCloseable {
    private final HBox tabHeaderBar = new HBox(8);
    private final HBox tabList = new HBox(4);
    private final Button addTabBtn = new Button("+");
    private final MiniDashboardView miniDashboard = new MiniDashboardView();
    private final StackPane contentContainer = new StackPane();

    private final List<TerminalTabItem> tabs = new ArrayList<>();
    private TerminalTabItem activeTab;

    private ConnectionProfile currentProfile;
    private SshConnectionService sshService;
    private final SystemProbeService systemProbeService = new SystemProbeService();

    private String currentFontFamily = "Menlo";
    private int currentFontSize = 14;
    private TerminalTheme currentTheme = TerminalTheme.DEFAULT_DARK;
    private CursorStyle currentCursorStyle = CursorStyle.BLOCK;
    private boolean currentCursorBlink = true;
    private Runnable onReconnectRequested;

    public TerminalSessionTabPane() {
        getStyleClass().add("terminal-session-tabs-root");

        // Top tab header bar
        tabHeaderBar.setAlignment(Pos.CENTER_LEFT);
        tabHeaderBar.setPadding(new Insets(6, 12, 6, 12));
        tabHeaderBar.getStyleClass().add("terminal-session-header");

        tabList.setAlignment(Pos.CENTER_LEFT);

        addTabBtn.getStyleClass().add("terminal-add-tab-btn");
        addTabBtn.setTooltip(new Tooltip("新建终端标签页 (Cmd/Ctrl+T)"));
        addTabBtn.setOnAction(e -> createNewTab(null));

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        tabHeaderBar.getChildren().addAll(tabList, addTabBtn, spacer, miniDashboard);
        setTop(tabHeaderBar);
        setCenter(contentContainer);

        // Global keyboard shortcut filters for Cmd+T and Cmd+W
        addEventFilter(KeyEvent.KEY_PRESSED, this::handleShortcuts);

        // Initialize with default first tab
        createNewTab("终端 1");
    }

    private Set<Integer> collectUsedTabNumbers() {
        Set<Integer> used = new HashSet<>();
        for (TerminalTabItem tab : tabs) {
            String t = tab.getTitle();
            if (t != null && t.startsWith("终端 ")) {
                try {
                    int num = Integer.parseInt(t.substring(3).trim());
                    used.add(num);
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return used;
    }

    private String generateNextTabTitle() {
        Set<Integer> used = collectUsedTabNumbers();
        int next = 1;
        while (used.contains(next)) {
            next++;
        }
        return "终端 " + next;
    }

    public TerminalTabItem createNewTab(String title) {
        String tabTitle = (title != null && !title.isBlank()) ? title : generateNextTabTitle();
        String channelId = "ch-" + UUID.randomUUID().toString().substring(0, 8);

        TerminalView terminalView = new TerminalView(channelId, tabTitle);
        terminalView.applyFont(currentFontFamily, currentFontSize);
        terminalView.applyTerminalSettings(currentTheme, currentCursorStyle, currentCursorBlink);
        if (onReconnectRequested != null) {
            terminalView.setOnReconnectRequested(onReconnectRequested);
        }

        TerminalTabItem tabItem = new TerminalTabItem(channelId, tabTitle, terminalView);
        tabs.add(tabItem);
        tabList.getChildren().add(tabItem.tabButton);
        contentContainer.getChildren().add(terminalView);

        selectTab(tabItem);

        if (currentProfile != null && sshService != null && sshService.isConnected(currentProfile)) {
            terminalView.attachConnection(currentProfile, sshService, channelId);
        }

        return tabItem;
    }

    private static final javafx.css.PseudoClass SELECTED_PSEUDO = javafx.css.PseudoClass.getPseudoClass("selected");

    public void selectTab(TerminalTabItem tabItem) {
        if (tabItem == null || !tabs.contains(tabItem)) {
            return;
        }

        activeTab = tabItem;

        for (TerminalTabItem item : tabs) {
            boolean isSelected = (item == tabItem);
            item.tabButton.pseudoClassStateChanged(SELECTED_PSEUDO, isSelected);
            item.terminalView.setVisible(isSelected);
            item.terminalView.setManaged(isSelected);
        }

        tabItem.terminalView.toFront();
        Platform.runLater(() -> tabItem.terminalView.setInputEnabled(true));
    }

    public void closeTab(TerminalTabItem tabItem) {
        if (tabItem == null) {
            return;
        }

        if (tabs.size() <= 1) {
            // Keep at least one tab, just reset it
            tabItem.terminalView.clear();
            return;
        }

        int index = tabs.indexOf(tabItem);
        tabItem.terminalView.detach();
        tabItem.terminalView.close();
        tabs.remove(tabItem);
        tabList.getChildren().remove(tabItem.tabButton);
        contentContainer.getChildren().remove(tabItem.terminalView);

        if (activeTab == tabItem) {
            int nextIndex = Math.min(index, tabs.size() - 1);
            if (nextIndex >= 0) {
                selectTab(tabs.get(nextIndex));
            }
        }
    }

    private void handleShortcuts(KeyEvent event) {
        boolean isCmd = event.isShortcutDown();
        if (isCmd && event.getCode() == KeyCode.T) {
            createNewTab(null);
            event.consume();
        } else if (isCmd && event.getCode() == KeyCode.W) {
            if (activeTab != null && tabs.size() > 1) {
                closeTab(activeTab);
                event.consume();
            }
        }
    }

    public void attachConnection(ConnectionProfile profile, SshConnectionService service) {
        this.currentProfile = profile;
        this.sshService = service;

        // Attach each open tab to its dedicated channel over this SSH connection
        for (TerminalTabItem tab : tabs) {
            tab.terminalView.attachConnection(profile, service, tab.channelId);
        }

        // Start real-time health probe
        systemProbeService.start(profile, service, miniDashboard::updateMetrics);
    }

    public void detach() {
        this.currentProfile = null;
        for (TerminalTabItem tab : tabs) {
            tab.terminalView.detach();
        }
        systemProbeService.stop();
        miniDashboard.resetToStandby();
    }

    public void detachForIdleTimeout(int minutes) {
        this.currentProfile = null;
        for (TerminalTabItem tab : tabs) {
            tab.terminalView.detachForIdleTimeout(minutes);
        }
        systemProbeService.stop();
        miniDashboard.resetToStandby();
    }

    public void applyFont(String family, int size) {
        this.currentFontFamily = family;
        this.currentFontSize = size;
        for (TerminalTabItem tab : tabs) {
            tab.terminalView.applyFont(family, size);
        }
    }

    public void applyTerminalSettings(TerminalTheme theme, CursorStyle cursorStyle, boolean cursorBlink) {
        this.currentTheme = (theme != null) ? theme : TerminalTheme.DEFAULT_DARK;
        this.currentCursorStyle = (cursorStyle != null) ? cursorStyle : CursorStyle.BLOCK;
        this.currentCursorBlink = cursorBlink;
        for (TerminalTabItem tab : tabs) {
            tab.terminalView.applyTerminalSettings(this.currentTheme, this.currentCursorStyle, this.currentCursorBlink);
        }
    }

    public void setOnReconnectRequested(Runnable runnable) {
        this.onReconnectRequested = runnable;
        for (TerminalTabItem tab : tabs) {
            tab.terminalView.setOnReconnectRequested(runnable);
        }
    }

    public void clear() {
        if (activeTab != null) {
            activeTab.terminalView.clear();
        }
    }

    public void setInputEnabled(boolean enabled) {
        if (activeTab != null) {
            activeTab.terminalView.setInputEnabled(enabled);
        }
    }

    public void appendOutput(String text) {
        if (activeTab != null) {
            activeTab.terminalView.appendOutput(text);
        }
    }

    public TerminalView getActiveTerminalView() {
        return activeTab != null ? activeTab.terminalView : null;
    }

    @Override
    public void close() {
        detach();
        for (TerminalTabItem tab : tabs) {
            tab.terminalView.close();
        }
        systemProbeService.close();
    }

    public final class TerminalTabItem {
        private final String channelId;
        private String title;
        private final TerminalView terminalView;
        private final HBox tabButton = new HBox(6);
        private final Label titleLabel = new Label();
        private final Button closeButton = new Button("×");

        public TerminalTabItem(String channelId, String title, TerminalView terminalView) {
            this.channelId = channelId;
            this.title = title;
            this.terminalView = terminalView;

            titleLabel.setText(title);
            titleLabel.getStyleClass().add("terminal-tab-title");

            closeButton.getStyleClass().add("terminal-tab-close-btn");
            closeButton.setOnAction(e -> {
                e.consume();
                closeTab(this);
            });

            tabButton.setAlignment(Pos.CENTER_LEFT);
            tabButton.getStyleClass().add("terminal-session-tab");
            tabButton.getChildren().addAll(titleLabel, closeButton);

            tabButton.setOnMouseClicked(e -> {
                if (e.getClickCount() == 2) {
                    showRenameDialog();
                } else {
                    selectTab(this);
                }
            });
        }

        private void showRenameDialog() {
            TextInputDialog dialog = new TextInputDialog(this.title);
            dialog.setTitle("重命名终端标签页");
            dialog.setHeaderText("修改当前标签页名称");
            dialog.setContentText("名称：");

            if (getScene() != null && getScene().getWindow() != null) {
                dialog.initOwner(getScene().getWindow());
            }
            String css = getClass().getResource("/com/yanshuwang/remoteworkbench/application.css").toExternalForm();
            dialog.getDialogPane().getStylesheets().add(css);

            Node okBtn = dialog.getDialogPane().lookupButton(javafx.scene.control.ButtonType.OK);
            if (okBtn != null) {
                okBtn.getStyleClass().add("dialog-primary-button");
            }
            Node cancelBtn = dialog.getDialogPane().lookupButton(javafx.scene.control.ButtonType.CANCEL);
            if (cancelBtn != null) {
                cancelBtn.getStyleClass().add("dialog-secondary-button");
            }

            dialog.showAndWait().ifPresent(newName -> {
                if (newName != null && !newName.isBlank()) {
                    this.title = newName.trim();
                    this.titleLabel.setText(this.title);
                    this.terminalView.setTabTitle(this.title);
                }
            });
        }

        public String getChannelId() {
            return channelId;
        }

        public String getTitle() {
            return title;
        }

        public TerminalView getTerminalView() {
            return terminalView;
        }
    }
}
