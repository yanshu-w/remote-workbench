package com.yanshuwang.remoteworkbench.ui;

import com.yanshuwang.remoteworkbench.connection.ConnectionProfile;
import com.yanshuwang.remoteworkbench.ssh.SshConnectionService;
import com.yanshuwang.remoteworkbench.ui.terminal.AnsiParser;
import com.yanshuwang.remoteworkbench.ui.terminal.CursorStyle;
import com.yanshuwang.remoteworkbench.ui.terminal.TerminalBuffer;
import com.yanshuwang.remoteworkbench.ui.terminal.TerminalCanvas;
import com.yanshuwang.remoteworkbench.ui.terminal.TerminalTheme;
import com.yanshuwang.remoteworkbench.ui.theme.ThemeManager;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ScrollBar;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.stage.FileChooser;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletionException;

public final class TerminalView extends BorderPane {
    private static final int INITIAL_COLS = 100;
    private static final int INITIAL_ROWS = 32;

    private final TerminalBuffer buffer = new TerminalBuffer(INITIAL_COLS, INITIAL_ROWS);
    private final AnsiParser parser = new AnsiParser(buffer);
    private final TerminalCanvas canvas;

    private final Label iconLabel = new Label("⚡ 终端");
    private final Label sizeLabel = new Label("100 x 32");
    private final Label connectionLabel = new Label("终端未连接");

    private final ScrollBar terminalScrollBar = new ScrollBar();
    private boolean isUpdatingScrollBar = false;

    private Pane canvasPane;
    private HBox searchBar;
    private final TextField searchField = new TextField();
    private final Label matchCountLabel = new Label("0/0");
    private final List<SearchMatch> currentMatches = new ArrayList<>();
    private int currentMatchIndex = -1;

    private final HBox reconnectBanner = new HBox(12);
    private final Label reconnectMsg = new Label("⚠️ 终端会话已断开");
    private final Button reconnectBtn = new Button("重新连接");
    private final Button closeReconnectBannerBtn = new Button("✕");
    private Runnable onReconnectRequested;
    private volatile boolean idleTimeoutDisconnected = false;

    private SshConnectionService sshService;
    private ConnectionProfile currentProfile;
    private String channelId = "default";
    private String tabTitle = "终端";
    private boolean terminalInputEnabled;

    private String fontFamily = "Menlo";
    private int fontSize = 14;

    public TerminalView() {
        this("default", "终端");
    }

    public TerminalView(String channelId, String tabTitle) {
        this.channelId = channelId != null ? channelId : "default";
        this.tabTitle = tabTitle != null ? tabTitle : "终端";
        this.canvas = new TerminalCanvas(buffer, fontFamily, fontSize);

        getStyleClass().add("terminal-view-root");
        setTop(createTerminalBar());
        setCenter(createCanvasContainer());

        setupKeyboardHandlers();
        setupContextMenu();

        parser.parse("\033[90m[提示] 尚未建立 SSH 终端会话。请双击左侧连接或点击“连接”开始。\033[0m\r\n");
        canvas.requestRender();
    }

    public String getChannelId() {
        return channelId;
    }

    public void setChannelId(String channelId) {
        this.channelId = channelId != null ? channelId : "default";
    }

    public String getTabTitle() {
        return tabTitle;
    }

    public void setTabTitle(String tabTitle) {
        this.tabTitle = tabTitle != null ? tabTitle : "终端";
        if (iconLabel != null) {
            iconLabel.setText("⚡ " + this.tabTitle);
        }
    }

    private HBox createTerminalBar() {
        iconLabel.setText("⚡ " + tabTitle);
        iconLabel.getStyleClass().add("terminal-title");

        sizeLabel.getStyleClass().add("terminal-size-badge");
        connectionLabel.getStyleClass().add("status-muted");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Button findButton = new Button("查找");
        findButton.getStyleClass().add("terminal-action-btn");
        findButton.setTooltip(new Tooltip("查找终端文本 (Cmd/Ctrl+F)"));
        findButton.setOnAction(e -> showSearchBar());

        Button copyButton = new Button("复制");
        copyButton.getStyleClass().add("terminal-action-btn");
        copyButton.setTooltip(new Tooltip("有选中文字时复制选中内容，否则复制全部文本"));
        copyButton.setOnAction(e -> {
            if (canvas.hasSelection()) {
                copySelectedText();
            } else {
                copyAllTerminalText();
            }
        });

        Button pasteButton = new Button("粘贴");
        pasteButton.getStyleClass().add("terminal-action-btn");
        pasteButton.setTooltip(new Tooltip("粘贴剪贴板内容 (Cmd/Ctrl+V)"));
        pasteButton.setOnAction(e -> pasteClipboardText());

        MenuButton shellMenu = new MenuButton("Shell");
        shellMenu.getStyleClass().add("terminal-action-btn");
        MenuItem toZsh = new MenuItem("切换到 Zsh");
        toZsh.setOnAction(e -> switchToZsh());
        MenuItem toBash = new MenuItem("切换到 Bash");
        toBash.setOnAction(e -> switchToBash());
        MenuItem toSh = new MenuItem("切换到 Sh");
        toSh.setOnAction(e -> sendInput("exec sh -l\r"));

        MenuItem installZshDebian = new MenuItem("远端安装 Zsh (Debian/Ubuntu)");
        installZshDebian.setOnAction(e -> sendInput("sudo apt update && sudo apt install -y zsh\r"));
        MenuItem installZshCentos = new MenuItem("远端安装 Zsh (CentOS/RHEL)");
        installZshCentos.setOnAction(e -> sendInput("sudo yum install -y zsh\r"));

        shellMenu.getItems().addAll(toZsh, toBash, toSh, new SeparatorMenuItem(), installZshDebian, installZshCentos);

        MenuButton snippetsMenu = new MenuButton("⚡ 常用命令");
        snippetsMenu.getStyleClass().add("terminal-action-btn");

        MenuItem osInfo = new MenuItem("系统信息 (uname -a && cat /etc/os-release)");
        osInfo.setOnAction(e -> sendInput("uname -a && cat /etc/os-release\r"));
        MenuItem memInfo = new MenuItem("内存占用 (free -h)");
        memInfo.setOnAction(e -> sendInput("free -h\r"));
        MenuItem diskInfo = new MenuItem("磁盘占用 (df -h)");
        diskInfo.setOnAction(e -> sendInput("df -h\r"));
        MenuItem uptimeInfo = new MenuItem("系统负载与运行时间 (uptime)");
        uptimeInfo.setOnAction(e -> sendInput("uptime\r"));

        MenuItem topProcs = new MenuItem("高内存消耗进程 (ps aux --sort=-%mem | head -10)");
        topProcs.setOnAction(e -> sendInput("ps aux --sort=-%mem | head -n 10\r"));
        MenuItem logsInfo = new MenuItem("最新系统日志 (journalctl -n 50 --no-pager)");
        logsInfo.setOnAction(e -> sendInput("journalctl -n 50 --no-pager\r"));

        MenuItem ipInfo = new MenuItem("网络接口与 IP (ip addr || ifconfig)");
        ipInfo.setOnAction(e -> sendInput("ip addr || ifconfig\r"));
        MenuItem portInfo = new MenuItem("监听端口与服务 (ss -tulnp || netstat -tulnp)");
        portInfo.setOnAction(e -> sendInput("ss -tulnp || netstat -tulnp\r"));

        MenuItem dockerPs = new MenuItem("运行中的 Docker 容器 (docker ps)");
        dockerPs.setOnAction(e -> sendInput("docker ps\r"));

        snippetsMenu.getItems().addAll(
                osInfo, memInfo, diskInfo, uptimeInfo,
                new SeparatorMenuItem(),
                topProcs, logsInfo,
                new SeparatorMenuItem(),
                ipInfo, portInfo,
                new SeparatorMenuItem(),
                dockerPs
        );

        Button exportButton = new Button("导出日志");
        exportButton.getStyleClass().add("terminal-action-btn");
        exportButton.setTooltip(new Tooltip("将当前终端历史记录导出为文本日志文件"));
        exportButton.setOnAction(e -> exportSessionLog());

        Button clearButton = new Button("清屏");
        clearButton.getStyleClass().add("terminal-action-btn");
        clearButton.setTooltip(new Tooltip("清屏 (执行 clear 命令)"));
        clearButton.setOnAction(e -> clear());

        HBox bar = new HBox(10, iconLabel, sizeLabel, connectionLabel, spacer, findButton, copyButton, pasteButton, exportButton, shellMenu, snippetsMenu, clearButton);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setPadding(new Insets(6, 12, 6, 12));
        bar.getStyleClass().add("terminal-top-bar");
        return bar;
    }

    private Node createCanvasContainer() {
        canvasPane = new Pane(canvas);
        canvasPane.getStyleClass().add("terminal-canvas-container");

        canvasPane.widthProperty().addListener((obs, oldW, newW) -> updateTerminalGeometry(newW.doubleValue(), canvasPane.getHeight()));
        canvasPane.heightProperty().addListener((obs, oldH, newH) -> updateTerminalGeometry(canvasPane.getWidth(), newH.doubleValue()));

        canvasPane.setOnMouseClicked(event -> {
            canvasPane.requestFocus();
            canvas.setTerminalFocused(true);
        });

        canvasPane.focusedProperty().addListener((obs, wasFocused, isFocused) -> {
            canvas.setTerminalFocused(isFocused);
        });

        terminalScrollBar.setOrientation(Orientation.VERTICAL);
        terminalScrollBar.getStyleClass().add("terminal-scrollbar");
        terminalScrollBar.setVisible(false);
        terminalScrollBar.setManaged(false);
        terminalScrollBar.setPrefWidth(12);

        terminalScrollBar.valueProperty().addListener((obs, oldVal, newVal) -> {
            if (isUpdatingScrollBar) {
                return;
            }
            int offset = (int) Math.round(terminalScrollBar.getMax() - newVal.doubleValue());
            buffer.setScrollOffset(offset);
            canvas.requestRender();
        });

        canvas.setScrollListener(this::updateScrollBar);

        BorderPane mainCanvasPane = new BorderPane();
        mainCanvasPane.setCenter(canvasPane);
        mainCanvasPane.setRight(terminalScrollBar);

        HBox searchWidget = createSearchBar();
        StackPane.setAlignment(searchWidget, Pos.TOP_RIGHT);
        StackPane.setMargin(searchWidget, new Insets(10, 24, 0, 0));

        HBox reconnectWidget = createReconnectBanner();
        StackPane.setAlignment(reconnectWidget, Pos.TOP_CENTER);
        StackPane.setMargin(reconnectWidget, new Insets(10, 0, 0, 0));

        return new StackPane(mainCanvasPane, searchWidget, reconnectWidget);
    }

    private HBox createReconnectBanner() {
        reconnectBanner.setAlignment(Pos.CENTER);
        reconnectBanner.setSpacing(12);
        reconnectBanner.setPadding(new Insets(6, 16, 6, 16));
        reconnectBanner.getStyleClass().add("terminal-reconnect-banner");
        reconnectBanner.setVisible(false);
        reconnectBanner.setManaged(false);

        reconnectMsg.getStyleClass().add("terminal-reconnect-msg");

        reconnectBtn.getStyleClass().add("terminal-reconnect-btn");
        reconnectBtn.setOnAction(e -> reconnect());

        closeReconnectBannerBtn.getStyleClass().add("search-nav-btn");
        closeReconnectBannerBtn.setOnAction(e -> hideReconnectBanner());

        reconnectBanner.getChildren().addAll(reconnectMsg, reconnectBtn, closeReconnectBannerBtn);
        return reconnectBanner;
    }

    public void showReconnectBanner() {
        showReconnectBanner("⚠️ 终端会话已断开");
    }

    public void showReconnectBanner(String message) {
        Platform.runLater(() -> {
            if (message != null && !message.isBlank()) {
                reconnectMsg.setText(message);
            } else {
                reconnectMsg.setText("⚠️ 终端会话已断开");
            }
            reconnectBanner.setVisible(true);
            reconnectBanner.setManaged(true);
            reconnectBanner.toFront();
        });
    }

    public void hideReconnectBanner() {
        Platform.runLater(() -> {
            reconnectBanner.setVisible(false);
            reconnectBanner.setManaged(false);
        });
    }

    public void setOnReconnectRequested(Runnable runnable) {
        this.onReconnectRequested = runnable;
    }

    public void reconnect() {
        hideReconnectBanner();
        idleTimeoutDisconnected = false;
        if (onReconnectRequested != null) {
            onReconnectRequested.run();
        } else if (currentProfile != null && sshService != null && sshService.isConnected(currentProfile)) {
            attachConnection(currentProfile, sshService, channelId);
        }
    }

    private void updateScrollBar() {
        int max = buffer.getScrollbackSize();
        if (max <= 0) {
            terminalScrollBar.setVisible(false);
            terminalScrollBar.setManaged(false);
            return;
        }

        terminalScrollBar.setVisible(true);
        terminalScrollBar.setManaged(true);
        terminalScrollBar.setMin(0);
        terminalScrollBar.setMax(max);
        terminalScrollBar.setVisibleAmount(Math.max(1, buffer.getRows()));

        isUpdatingScrollBar = true;
        try {
            terminalScrollBar.setValue(max - buffer.getScrollOffset());
        } finally {
            isUpdatingScrollBar = false;
        }
    }

    private HBox createSearchBar() {
        searchBar = new HBox(8);
        searchBar.setAlignment(Pos.CENTER_LEFT);
        searchBar.getStyleClass().add("terminal-search-bar");
        searchBar.setVisible(false);
        searchBar.setManaged(false);

        Label icon = new Label("🔍");
        icon.setStyle("-fx-text-fill: #8f949f; -fx-font-size: 11px;");

        searchField.setPromptText("查找终端文本...");
        searchField.getStyleClass().add("terminal-search-input");
        searchField.setPrefWidth(160);

        searchField.textProperty().addListener((obs, oldVal, newVal) -> performSearch(newVal));
        searchField.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ENTER) {
                if (e.isShiftDown()) {
                    navigateMatch(-1);
                } else {
                    navigateMatch(1);
                }
                e.consume();
            } else if (e.getCode() == KeyCode.ESCAPE) {
                hideSearchBar();
                e.consume();
            }
        });

        matchCountLabel.setStyle("-fx-text-fill: #8f949f; -fx-font-size: 11px;");

        Button prevBtn = new Button("↑");
        prevBtn.getStyleClass().add("search-nav-btn");
        prevBtn.setTooltip(new Tooltip("上一个匹配 (Shift+Enter)"));
        prevBtn.setOnAction(e -> navigateMatch(-1));

        Button nextBtn = new Button("↓");
        nextBtn.getStyleClass().add("search-nav-btn");
        nextBtn.setTooltip(new Tooltip("下一个匹配 (Enter)"));
        nextBtn.setOnAction(e -> navigateMatch(1));

        Button closeBtn = new Button("✕");
        closeBtn.getStyleClass().add("search-nav-btn");
        closeBtn.setOnAction(e -> hideSearchBar());

        searchBar.getChildren().addAll(icon, searchField, matchCountLabel, prevBtn, nextBtn, closeBtn);
        return searchBar;
    }

    private void performSearch(String query) {
        currentMatches.clear();
        currentMatchIndex = -1;

        if (query == null || query.isBlank()) {
            matchCountLabel.setText("0/0");
            canvas.clearSelection();
            return;
        }

        String lowerQuery = query.toLowerCase();
        List<String> allLines = buffer.getAllLines();
        for (int lineIdx = 0; lineIdx < allLines.size(); lineIdx++) {
            String line = allLines.get(lineIdx).toLowerCase();
            int col = 0;
            while ((col = line.indexOf(lowerQuery, col)) != -1) {
                currentMatches.add(new SearchMatch(lineIdx, col, query.length()));
                col += Math.max(1, query.length());
            }
        }

        if (currentMatches.isEmpty()) {
            matchCountLabel.setText("无结果");
            canvas.clearSelection();
        } else {
            matchCountLabel.setText("1/" + currentMatches.size());
            currentMatchIndex = 0;
            showMatch(currentMatches.get(0));
        }
    }

    private void navigateMatch(int direction) {
        if (currentMatches.isEmpty()) {
            return;
        }
        currentMatchIndex = (currentMatchIndex + direction + currentMatches.size()) % currentMatches.size();
        matchCountLabel.setText((currentMatchIndex + 1) + "/" + currentMatches.size());
        showMatch(currentMatches.get(currentMatchIndex));
    }

    private void showMatch(SearchMatch match) {
        List<String> allLines = buffer.getAllLines();
        int totalLines = allLines.size();
        int rows = buffer.getRows();

        int targetOffset = totalLines - match.lineIndex - rows / 2;
        buffer.setScrollOffset(targetOffset);
        updateScrollBar();

        int startVisibleLine = totalLines - rows - buffer.getScrollOffset();
        int visibleRow = match.lineIndex - startVisibleLine;
        visibleRow = Math.max(0, Math.min(rows - 1, visibleRow));

        canvas.setSelection(visibleRow, match.colIndex, visibleRow, match.colIndex + match.length);
        canvas.requestRender();
    }

    public void showSearchBar() {
        searchBar.setVisible(true);
        searchBar.setManaged(true);
        searchField.requestFocus();
        searchField.selectAll();
        if (!searchField.getText().isBlank()) {
            performSearch(searchField.getText());
        }
    }

    public void hideSearchBar() {
        searchBar.setVisible(false);
        searchBar.setManaged(false);
        canvas.clearSelection();
        if (canvasPane != null) {
            canvasPane.requestFocus();
        }
        canvas.setTerminalFocused(true);
    }

    private record SearchMatch(int lineIndex, int colIndex, int length) {}

    private void updateTerminalGeometry(double width, double height) {
        if (width <= 40 || height <= 40) {
            return;
        }

        double charW = canvas.getCharWidth();
        double charH = canvas.getCharHeight();

        int cols = Math.max(30, (int) (width / charW));
        int rows = Math.max(8, (int) (height / charH));

        canvas.setWidth(width);
        canvas.setHeight(height);

        if (cols != buffer.getCols() || rows != buffer.getRows()) {
            buffer.resize(cols, rows);
            sizeLabel.setText(cols + " x " + rows);

            if (currentProfile != null && sshService != null) {
                sshService.resizeTerminal(currentProfile, channelId, cols, rows, (int) width, (int) height);
            }
            canvas.requestRender();
        }
    }

    public void attachConnection(ConnectionProfile profile, SshConnectionService service) {
        attachConnection(profile, service, this.channelId);
    }

    public void attachConnection(ConnectionProfile profile, SshConnectionService service, String channelId) {
        this.currentProfile = profile;
        this.sshService = service;
        this.channelId = (channelId != null && !channelId.isBlank()) ? channelId : "default";
        this.terminalInputEnabled = false;
        this.idleTimeoutDisconnected = false;

        buffer.clearScreen(2);
        buffer.setCursor(0, 0);
        connectionLabel.setText("正在连接远程终端...");
        canvas.requestRender();

        service.openTerminal(profile, this.channelId, this::receiveTerminalOutput, () -> {
            Platform.runLater(() -> {
                if (idleTimeoutDisconnected) {
                    return;
                }
                setInputEnabled(false);
                connectionLabel.setText("会话已退出");
                parser.parse("\r\n\033[90m[SSH 终端会话已退出]\033[0m\r\n");
                canvas.requestRender();
                showReconnectBanner();
            });
        }).whenComplete((unused, error) -> {
            Platform.runLater(() -> {
                if (error != null) {
                    connectionLabel.setText("终端打开失败");
                    parser.parse("\r\n[打开远程终端失败：" + error.getMessage() + "]\r\n");
                    canvas.requestRender();
                    setInputEnabled(false);
                    showReconnectBanner();
                } else {
                    hideReconnectBanner();
                    connectionLabel.setText("SSH 终端在线");
                    setInputEnabled(true);
                    // Initial resize sync
                    sshService.resizeTerminal(
                            profile,
                            this.channelId,
                            buffer.getCols(),
                            buffer.getRows(),
                            (int) canvas.getWidth(),
                            (int) canvas.getHeight()
                    );

                    if ("zsh".equalsIgnoreCase(profile.initialShell())) {
                        sshService.execute(profile, "command -v zsh").whenComplete((path, checkErr) -> {
                            Platform.runLater(() -> {
                                if (checkErr != null || path == null || path.isBlank()) {
                                    parser.parse("\r\n\033[1;33m[远程提示] 配置的首选 Shell 为 zsh，但目标主机未安装 (zsh: command not found)。\033[0m\r\n"
                                            + "已自动保持当前默认 Shell。如需使用 zsh，可执行安装：\r\n"
                                            + "  sudo apt update && sudo apt install -y zsh (Ubuntu/Debian) 或 sudo yum install -y zsh (CentOS)\r\n\r\n");
                                    canvas.requestRender();
                                    connectionLabel.setText("SSH 终端在线 (默认Shell, zsh未安装)");
                                } else {
                                    String zshBin = path.trim().lines().findFirst().orElse("zsh").trim();
                                    sendInput("exec " + zshBin + " -l\r");
                                    connectionLabel.setText("SSH 终端在线 (Zsh)");
                                }
                            });
                        });
                    } else if ("bash".equalsIgnoreCase(profile.initialShell())) {
                        sshService.execute(profile, "command -v bash").whenComplete((path, checkErr) -> {
                            Platform.runLater(() -> {
                                if (checkErr == null && path != null && !path.isBlank()) {
                                    String bashBin = path.trim().lines().findFirst().orElse("bash").trim();
                                    sendInput("exec " + bashBin + " -l\r");
                                }
                            });
                        });
                    }
                }
            });
        });
    }

    public void switchToZsh() {
        if (currentProfile == null || sshService == null || !sshService.isTerminalOpen(currentProfile, channelId)) {
            return;
        }

        connectionLabel.setText("正在检查远端 zsh...");
        sshService.execute(currentProfile, "command -v zsh").whenComplete((path, error) -> {
            Platform.runLater(() -> {
                if (error != null || path == null || path.isBlank()) {
                    connectionLabel.setText("远端未安装 zsh");
                    parser.parse("\r\n\033[1;33m[远程提示] 目标服务器未检测到 zsh (zsh: command not found)。\033[0m\r\n"
                            + "\033[36m已保留当前 Shell 会话。如需在远端使用 zsh，可执行以下命令安装：\033[0m\r\n"
                            + "  • Debian / Ubuntu:  sudo apt update && sudo apt install -y zsh\r\n"
                            + "  • CentOS / RHEL:    sudo yum install -y zsh\r\n"
                            + "  • Alpine Linux:     apk add zsh\r\n\r\n");
                    canvas.requestRender();
                } else {
                    String zshBin = path.trim().lines().findFirst().orElse("zsh").trim();
                    sendInput("exec " + zshBin + " -l\r");
                    connectionLabel.setText("已切换至 Zsh (" + zshBin + ")");
                }
            });
        });
    }

    public void switchToBash() {
        if (currentProfile == null || sshService == null || !sshService.isTerminalOpen(currentProfile, channelId)) {
            return;
        }

        connectionLabel.setText("正在检查远端 bash...");
        sshService.execute(currentProfile, "command -v bash").whenComplete((path, error) -> {
            Platform.runLater(() -> {
                if (error != null || path == null || path.isBlank()) {
                    connectionLabel.setText("远端未安装 bash");
                    parser.parse("\r\n\033[1;33m[远程提示] 目标服务器未检测到 bash (bash: command not found)。\033[0m\r\n\r\n");
                    canvas.requestRender();
                } else {
                    String bashBin = path.trim().lines().findFirst().orElse("bash").trim();
                    sendInput("exec " + bashBin + " -l\r");
                    connectionLabel.setText("已切换至 Bash (" + bashBin + ")");
                }
            });
        });
    }

    public void detach() {
        hideReconnectBanner();
        if (currentProfile != null && sshService != null && channelId != null) {
            sshService.closeTerminal(currentProfile, channelId);
        }
        this.currentProfile = null;
        setInputEnabled(false);
        connectionLabel.setText("终端已断开");
        parser.parse("\r\n[连接已断开]\r\n");
        canvas.requestRender();
    }

    public void detachForIdleTimeout(int minutes) {
        idleTimeoutDisconnected = true;
        if (currentProfile != null && sshService != null && channelId != null) {
            sshService.closeTerminal(currentProfile, channelId);
        }
        this.currentProfile = null;
        setInputEnabled(false);
        connectionLabel.setText("已闲置断开");
        parser.parse("\r\n\033[1;33m[安全防护] 本地已连续 " + minutes + " 分钟未输入任何命令，连接已主动断开以确保安全。\033[0m\r\n");
        canvas.requestRender();
        showReconnectBanner("⚠️ 闲置超时安全断开 (" + minutes + "分钟无操作)");
    }

    public void applyFont(String family, int size) {
        this.fontFamily = family;
        this.fontSize = size;
        canvas.setTerminalFont(family, size);
        updateTerminalGeometry(canvas.getWidth(), canvas.getHeight());
    }

    public void setInputEnabled(boolean enabled) {
        this.terminalInputEnabled = enabled;
        if (enabled) {
            canvas.getParent().requestFocus();
            canvas.setTerminalFocused(true);
        }
    }

    public void appendOutput(String text) {
        receiveTerminalOutput(text);
    }

    public void clear() {
        if (currentProfile != null && sshService != null && sshService.isTerminalOpen(currentProfile, channelId)) {
            sendInput("clear\r");
        } else {
            buffer.clearScreen(2);
            buffer.setCursor(0, 0);
            canvas.requestRender();
        }
        if (canvasPane != null) {
            canvasPane.requestFocus();
            canvas.setTerminalFocused(true);
        }
    }

    private void receiveTerminalOutput(String text) {
        parser.parse(text);
        updateScrollBar();
        canvas.requestRender();
    }

    private void setupKeyboardHandlers() {
        addEventFilter(KeyEvent.KEY_PRESSED, this::handleKeyPressed);
        addEventFilter(KeyEvent.KEY_TYPED, this::handleKeyTyped);
    }

    private void handleKeyPressed(KeyEvent event) {
        if (!terminalInputEnabled) {
            return;
        }

        // 0. Find handling: Cmd+F on Mac or Ctrl+F on Win/Linux
        boolean isMacCmdF = event.isMetaDown() && event.getCode() == KeyCode.F;
        boolean isCtrlF = event.isControlDown() && event.getCode() == KeyCode.F;
        if (isMacCmdF || isCtrlF) {
            showSearchBar();
            event.consume();
            return;
        }

        // 1. Copy Selected Text handling
        // macOS: Cmd+C (event.isMetaDown() && KeyCode.C)
        // Windows/Linux: Ctrl+C with active selection, or Ctrl+Shift+C
        boolean isMacCmdC = event.isMetaDown() && event.getCode() == KeyCode.C;
        boolean isCtrlShiftC = event.isControlDown() && event.isShiftDown() && event.getCode() == KeyCode.C;
        boolean isCtrlCWithSelection = event.isControlDown() && event.getCode() == KeyCode.C && canvas.hasSelection();

        if (isMacCmdC || isCtrlShiftC || isCtrlCWithSelection) {
            if (canvas.hasSelection()) {
                copySelectedText();
            } else if (isCtrlShiftC) {
                copyAllTerminalText();
            }
            event.consume();
            return;
        }

        // 2. Select All: Cmd+A on Mac
        if (event.isMetaDown() && event.getCode() == KeyCode.A) {
            canvas.selectAll();
            event.consume();
            return;
        }

        // 3. Paste handling: Cmd+V on Mac or Ctrl+V on Windows/Linux or Shift+Insert
        boolean isPaste = (event.isShortcutDown() && event.getCode() == KeyCode.V)
                || (event.isShiftDown() && event.getCode() == KeyCode.INSERT);
        if (isPaste) {
            pasteClipboardText();
            event.consume();
            return;
        }

        // 4. Normal terminal keys
        String sequence = switch (event.getCode()) {
            case ENTER -> "\r";
            case BACK_SPACE -> "\u007F";
            case TAB -> "\t";
            case ESCAPE -> "\u001B";
            case UP -> "\u001B[A";
            case DOWN -> "\u001B[B";
            case RIGHT -> "\u001B[C";
            case LEFT -> "\u001B[D";
            case HOME -> "\u001B[H";
            case END -> "\u001B[F";
            case INSERT -> "\u001B[2~";
            case DELETE -> "\u001B[3~";
            case PAGE_UP -> "\u001B[5~";
            case PAGE_DOWN -> "\u001B[6~";
            case F1 -> "\u001BOP";
            case F2 -> "\u001BOQ";
            case F3 -> "\u001BOR";
            case F4 -> "\u001BOS";
            case F5 -> "\u001B[15~";
            case F6 -> "\u001B[17~";
            case F7 -> "\u001B[18~";
            case F8 -> "\u001B[19~";
            case F9 -> "\u001B[20~";
            case F10 -> "\u001B[21~";
            case F11 -> "\u001B[23~";
            case F12 -> "\u001B[24~";
            default -> controlSequence(event);
        };

        if (sequence != null) {
            sendInput(sequence);
            event.consume();
        }
    }

    private void handleKeyTyped(KeyEvent event) {
        if (!terminalInputEnabled) {
            return;
        }

        String character = event.getCharacter();
        if (character != null
                && !character.isEmpty()
                && character.charAt(0) >= 0x20
                && character.charAt(0) != 0x7F
                && !event.isControlDown()
                && !event.isAltDown()
                && !event.isMetaDown()) {
            sendInput(character);
            event.consume();
        }
    }

    private String controlSequence(KeyEvent event) {
        if (!event.isControlDown()) {
            return null;
        }

        return switch (event.getCode()) {
            case A -> "\u0001";
            case B -> "\u0002";
            case C -> "\u0003"; // SIGINT
            case D -> "\u0004"; // EOF / logout
            case E -> "\u0005";
            case F -> "\u0006";
            case G -> "\u0007";
            case H -> "\u0008";
            case K -> "\u000B";
            case L -> "\u000C"; // Clear screen
            case N -> "\u000E";
            case P -> "\u0010";
            case R -> "\u0012";
            case U -> "\u0015";
            case W -> "\u0017";
            case Z -> "\u001A"; // SIGTSTP
            default -> null;
        };
    }

    private void sendInput(String text) {
        canvas.resetCursorBlink();
        if (buffer.getScrollOffset() > 0) {
            buffer.setScrollOffset(0);
            updateScrollBar();
            canvas.requestRender();
        }

        if (currentProfile == null || sshService == null || !sshService.isTerminalOpen(currentProfile, channelId)) {
            return;
        }

        try {
            sshService.sendTerminalInput(currentProfile, channelId, text);
        } catch (CompletionException exception) {
            setInputEnabled(false);
            connectionLabel.setText("连接已断开");
            showReconnectBanner();
        }
    }

    public boolean copySelectedText() {
        if (canvas.hasSelection()) {
            String text = canvas.getSelectedText();
            if (text != null && !text.isEmpty()) {
                ClipboardContent content = new ClipboardContent();
                content.putString(text);
                Clipboard.getSystemClipboard().setContent(content);
                return true;
            }
        }
        return false;
    }

    private void copyAllTerminalText() {
        String allText = buffer.getAllText();
        ClipboardContent content = new ClipboardContent();
        content.putString(allText);
        Clipboard.getSystemClipboard().setContent(content);
    }

    private void pasteClipboardText() {
        String text = Clipboard.getSystemClipboard().getString();
        if (text != null && !text.isEmpty()) {
            sendInput(text.replace("\r\n", "\r").replace("\n", "\r"));
        }
    }

    public void exportSessionLog() {
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("导出终端会话日志");
        String hostPart = (currentProfile != null) ? currentProfile.host() : "session";
        String datePart = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        fileChooser.setInitialFileName("terminal-" + hostPart + "-" + datePart + ".log");
        fileChooser.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter("日志文件 (*.log)", "*.log"),
                new FileChooser.ExtensionFilter("文本文件 (*.txt)", "*.txt"),
                new FileChooser.ExtensionFilter("所有文件 (*.*)", "*.*")
        );

        javafx.stage.Window window = getScene() != null ? getScene().getWindow() : null;
        File file = fileChooser.showSaveDialog(window);
        if (file == null) {
            return;
        }

        try {
            String allText = buffer.getAllText();
            StringBuilder header = new StringBuilder();
            header.append("================================================================================\n");
            header.append("远程工作台终端会话记录 (Remote Workbench Terminal Session Log)\n");
            if (currentProfile != null) {
                header.append("服务器名称: ").append(currentProfile.name()).append("\n");
                header.append("连接地址:   ").append(currentProfile.username()).append("@").append(currentProfile.host()).append(":").append(currentProfile.port()).append("\n");
            }
            header.append("标签页:     ").append(tabTitle).append(" (Channel: ").append(channelId).append(")\n");
            header.append("导出时间:   ").append(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))).append("\n");
            header.append("================================================================================\n\n");

            Files.writeString(file.toPath(), header.toString() + allText, StandardCharsets.UTF_8);

            Alert alert = new Alert(Alert.AlertType.INFORMATION);
            alert.setTitle("导出成功");
            alert.setHeaderText("终端会话日志导出成功");
            alert.setContentText("日志文件已成功保存至：\n" + file.getAbsolutePath());
            ThemeManager.applyDialogTheme(alert, window);
            alert.showAndWait();
        } catch (Exception ex) {
            Alert alert = new Alert(Alert.AlertType.ERROR);
            alert.setTitle("导出失败");
            alert.setHeaderText("保存日志文件时发生错误");
            alert.setContentText(ex.getMessage());
            ThemeManager.applyDialogTheme(alert, window);
            alert.showAndWait();
        }
    }

    public void applyTerminalSettings(TerminalTheme theme, CursorStyle cursorStyle, boolean cursorBlink) {
        canvas.setTheme(theme != null ? theme : TerminalTheme.AUTO);
        if (cursorStyle != null) {
            canvas.setCursorStyle(cursorStyle);
        }
        canvas.setCursorBlink(cursorBlink);
    }

    public void close() {
        detach();
        canvas.dispose();
    }

    private void setupContextMenu() {
        ContextMenu menu = new ContextMenu();

        MenuItem copySelectionItem = new MenuItem("复制选中内容 (Cmd/Ctrl+C)");
        copySelectionItem.setOnAction(e -> copySelectedText());

        MenuItem copyAllItem = new MenuItem("复制全部文本");
        copyAllItem.setOnAction(e -> copyAllTerminalText());

        MenuItem selectAllItem = new MenuItem("全选 (Cmd/Ctrl+A)");
        selectAllItem.setOnAction(e -> canvas.selectAll());

        MenuItem pasteItem = new MenuItem("粘贴 (Cmd/Ctrl+V)");
        pasteItem.setOnAction(e -> pasteClipboardText());

        MenuItem exportLogItem = new MenuItem("导出终端会话日志...");
        exportLogItem.setOnAction(e -> exportSessionLog());

        MenuItem clearItem = new MenuItem("清屏");
        clearItem.setOnAction(e -> clear());

        menu.getItems().addAll(
                copySelectionItem,
                copyAllItem,
                selectAllItem,
                new SeparatorMenuItem(),
                pasteItem,
                new SeparatorMenuItem(),
                exportLogItem,
                new SeparatorMenuItem(),
                clearItem
        );

        setOnContextMenuRequested(event -> {
            boolean hasSel = canvas.hasSelection();
            copySelectionItem.setDisable(!hasSel);
            pasteItem.setDisable(!Clipboard.getSystemClipboard().hasString());
            menu.show(this, event.getScreenX(), event.getScreenY());
        });
    }
}
