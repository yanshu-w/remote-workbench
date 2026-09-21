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
import javafx.geometry.Bounds;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Point2D;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBase;
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
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.Menu;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.text.TextAlignment;
import javafx.stage.FileChooser;
import com.yanshuwang.remoteworkbench.config.CommandSnippet;
import com.yanshuwang.remoteworkbench.config.CommandSnippetService;
import com.yanshuwang.remoteworkbench.config.SnippetExecutor;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
    private final Button findButton = new Button("查找");
    private final Pane searchOverlayPane = new Pane();
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

    private final MenuButton snippetsMenu = new MenuButton("⚡ 常用命令");
    private final Runnable snippetChangeListener = () -> Platform.runLater(this::rebuildSnippetsMenu);

    private final StringBuilder currentInputLine = new StringBuilder();
    private final ObservableList<String> commandHistory = FXCollections.observableArrayList();
    private final FilteredList<String> filteredCommandHistory = new FilteredList<>(commandHistory, p -> true);

    private boolean rightSidebarExpanded = false;
    private VBox historyPanel;
    private Button historyTabButton;
    private Label historyCountBadge;
    private ListView<String> historyListView;
    private TextField historySearchField;

    private final MenuButton encodingMenu = new MenuButton("编码: UTF-8");
    private final Button openInSftpBtn = new Button("在 SFTP 打开");
    private java.nio.charset.Charset currentCharset = StandardCharsets.UTF_8;

    private String currentWorkingDirectory = "~";
    private java.util.function.Consumer<String> onWorkingDirectoryChanged;
    private java.util.function.Consumer<String> onOpenInSftpRequested;
    private java.util.function.Consumer<String> onTerminalSizeChanged;

    public TerminalView() {
        this("default", "终端");
    }

    public TerminalView(String channelId, String tabTitle) {
        this.channelId = channelId != null ? channelId : "default";
        this.tabTitle = tabTitle != null ? tabTitle : "终端";
        this.canvas = new TerminalCanvas(buffer, fontFamily, fontSize);

        CommandSnippetService.addListener(snippetChangeListener);

        getStyleClass().add("terminal-view-root");
        setTop(createTerminalBar());
        setCenter(createCanvasContainer());
        setRight(createRightSidebar());

        setupKeyboardHandlers();
        setupContextMenu();

        canvas.setOnUrlClicked(this::openUrlInBrowser);
        canvas.setOnPathClicked(path -> {
            if (onOpenInSftpRequested != null && path != null && !path.isBlank()) {
                onOpenInSftpRequested.accept(path.trim());
            }
        });

        parser.setWorkingDirectoryListener(dir -> Platform.runLater(() -> updateWorkingDirectory(dir)));

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

    private void setupActionButton(ButtonBase btn, double minWidth) {
        btn.getStyleClass().add("terminal-action-btn");
        btn.setPrefHeight(26);
        btn.setMinHeight(26);
        btn.setMaxHeight(26);
        if (minWidth > 0) {
            btn.setMinWidth(minWidth);
        }
    }

    private HBox createTerminalBar() {
        iconLabel.setText("⚡ " + tabTitle);
        iconLabel.getStyleClass().add("terminal-title");

        sizeLabel.getStyleClass().add("terminal-size-badge");
        connectionLabel.getStyleClass().add("status-muted");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        setupActionButton(findButton, 48);
        findButton.setTooltip(new Tooltip("查找终端文本 (Cmd/Ctrl+F)"));
        findButton.setOnAction(e -> toggleSearchBar());

        Button copyButton = new Button("复制");
        setupActionButton(copyButton, 48);
        copyButton.setTooltip(new Tooltip("有选中文字时复制选中内容，否则复制全部文本"));
        copyButton.setOnAction(e -> {
            if (canvas.hasSelection()) {
                copySelectedText();
            } else {
                copyAllTerminalText();
            }
        });

        Button pasteButton = new Button("粘贴");
        setupActionButton(pasteButton, 48);
        pasteButton.setTooltip(new Tooltip("粘贴剪贴板内容 (Cmd/Ctrl+V)"));
        pasteButton.setOnAction(e -> pasteClipboardText());

        MenuButton shellMenu = new MenuButton("Shell: 默认");
        setupActionButton(shellMenu, -1);
        shellMenu.setTooltip(new Tooltip("当前 Shell 环境 (默认) - 点击切换"));

        MenuItem toDefault = new MenuItem("恢复默认 Shell");
        toDefault.setOnAction(e -> {
            sendInput("exec $SHELL -l\r");
            shellMenu.setText("Shell: 默认");
        });
        MenuItem toZsh = new MenuItem("切换到 Zsh");
        toZsh.setOnAction(e -> {
            switchToZsh();
            shellMenu.setText("Shell: Zsh");
        });
        MenuItem toBash = new MenuItem("切换到 Bash");
        toBash.setOnAction(e -> {
            switchToBash();
            shellMenu.setText("Shell: Bash");
        });
        MenuItem toSh = new MenuItem("切换到 Sh");
        toSh.setOnAction(e -> {
            sendInput("exec sh -l\r");
            shellMenu.setText("Shell: Sh");
        });

        MenuItem installZshDebian = new MenuItem("远端安装 Zsh (Debian/Ubuntu)");
        installZshDebian.setOnAction(e -> sendInput("sudo apt update && sudo apt install -y zsh\r"));
        MenuItem installZshCentos = new MenuItem("远端安装 Zsh (CentOS/RHEL)");
        installZshCentos.setOnAction(e -> sendInput("sudo yum install -y zsh\r"));

        shellMenu.getItems().addAll(toDefault, toZsh, toBash, toSh, new SeparatorMenuItem(), installZshDebian, installZshCentos);

        setupActionButton(snippetsMenu, -1);
        snippetsMenu.setTooltip(new Tooltip("常用运维诊断与系统状态快捷命令"));
        rebuildSnippetsMenu();

        Button exportButton = new Button("导出日志");
        setupActionButton(exportButton, -1);
        exportButton.setTooltip(new Tooltip("将当前终端历史记录导出为文本日志文件"));
        exportButton.setOnAction(e -> exportSessionLog());

        Button clearButton = new Button("清屏");
        setupActionButton(clearButton, 48);
        clearButton.setTooltip(new Tooltip("清屏 (执行 clear 命令)"));
        clearButton.setOnAction(e -> clear());

        setupActionButton(encodingMenu, -1);
        encodingMenu.setTooltip(new Tooltip("当前终端字符集编码 - 点击切换 (UTF-8 / GBK)"));
        MenuItem utf8Item = new MenuItem("UTF-8 (推荐)");
        utf8Item.setOnAction(e -> applyCharset(StandardCharsets.UTF_8, "编码: UTF-8"));
        MenuItem gbkItem = new MenuItem("GBK (简体中文)");
        gbkItem.setOnAction(e -> {
            try {
                applyCharset(java.nio.charset.Charset.forName("GBK"), "编码: GBK");
            } catch (Exception ex) {
                applyCharset(java.nio.charset.Charset.forName("GB18030"), "编码: GB18030");
            }
        });
        MenuItem gb18030Item = new MenuItem("GB18030");
        gb18030Item.setOnAction(e -> applyCharset(java.nio.charset.Charset.forName("GB18030"), "编码: GB18030"));
        MenuItem big5Item = new MenuItem("Big5 (繁体中文)");
        big5Item.setOnAction(e -> applyCharset(java.nio.charset.Charset.forName("Big5"), "编码: Big5"));
        MenuItem isoItem = new MenuItem("ISO-8859-1 (Latin-1)");
        isoItem.setOnAction(e -> applyCharset(StandardCharsets.ISO_8859_1, "编码: ISO-8859-1"));
        encodingMenu.getItems().addAll(utf8Item, gbkItem, gb18030Item, big5Item, isoItem);

        setupActionButton(openInSftpBtn, -1);
        openInSftpBtn.setTooltip(new Tooltip("在 SFTP 文件管理中打开当前目录 (" + currentWorkingDirectory + ")"));
        openInSftpBtn.setOnAction(e -> {
            if (onOpenInSftpRequested != null) {
                onOpenInSftpRequested.accept(currentWorkingDirectory);
            }
        });

        HBox bar = new HBox(8, iconLabel, connectionLabel, spacer, findButton, copyButton, pasteButton, encodingMenu, openInSftpBtn, shellMenu, snippetsMenu, exportButton, clearButton);
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

        searchBar = createSearchBar();
        searchOverlayPane.setPickOnBounds(false);
        searchOverlayPane.getChildren().add(searchBar);
        searchOverlayPane.widthProperty().addListener((obs, oldW, newW) -> {
            if (searchBar != null && searchBar.isVisible()) {
                updateSearchBarPosition();
            }
        });
        searchOverlayPane.sceneProperty().addListener((obs, oldS, newS) -> {
            if (newS != null && searchBar != null && searchBar.isVisible()) {
                Platform.runLater(this::updateSearchBarPosition);
            }
        });
        findButton.boundsInParentProperty().addListener((obs, oldB, newB) -> {
            if (searchBar != null && searchBar.isVisible()) {
                updateSearchBarPosition();
            }
        });

        HBox reconnectWidget = createReconnectBanner();
        StackPane.setAlignment(reconnectWidget, Pos.TOP_CENTER);
        StackPane.setMargin(reconnectWidget, new Insets(10, 0, 0, 0));

        return new StackPane(mainCanvasPane, searchOverlayPane, reconnectWidget);
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
        icon.getStyleClass().add("terminal-search-icon");

        searchField.setPromptText("查找终端文本...");
        searchField.getStyleClass().add("terminal-search-input");
        searchField.setPrefWidth(160);

        searchField.textProperty().addListener((obs, oldVal, newVal) -> performSearch(newVal));
        searchField.setOnKeyPressed(e -> {
            boolean isFindKey = (e.isMetaDown() && e.getCode() == KeyCode.F)
                    || (e.isControlDown() && e.getCode() == KeyCode.F);
            if (isFindKey) {
                toggleSearchBar();
                e.consume();
            } else if (e.getCode() == KeyCode.ENTER) {
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

        matchCountLabel.getStyleClass().add("terminal-search-count");
        matchCountLabel.setMinWidth(46);
        matchCountLabel.setAlignment(Pos.CENTER);

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
        closeBtn.setTooltip(new Tooltip("关闭 (ESC)"));
        closeBtn.setOnAction(e -> hideSearchBar());

        searchBar.getChildren().addAll(icon, searchField, matchCountLabel, prevBtn, nextBtn, closeBtn);
        return searchBar;
    }

    private void updateSearchBarPosition() {
        if (searchBar == null || !searchBar.isVisible() || findButton.getScene() == null || searchOverlayPane.getScene() == null) {
            return;
        }

        Bounds btnSceneBounds = findButton.localToScene(findButton.getBoundsInLocal());
        if (btnSceneBounds == null) {
            return;
        }

        Point2D btnOverlayPos = searchOverlayPane.sceneToLocal(btnSceneBounds.getMinX(), btnSceneBounds.getMaxY());
        if (btnOverlayPos == null) {
            return;
        }

        searchBar.applyCss();
        searchBar.autosize();
        double barWidth = searchBar.prefWidth(-1);
        if (barWidth <= 0) {
            barWidth = 330;
        }
        double barHeight = searchBar.prefHeight(-1);
        if (barHeight <= 0) {
            barHeight = 36;
        }
        searchBar.resize(barWidth, barHeight);

        // Center horizontally under findButton
        double btnCenterX = btnOverlayPos.getX() + btnSceneBounds.getWidth() / 2.0;
        double targetX = btnCenterX - (barWidth / 2.0);

        // Keep within searchOverlayPane bounds
        double overlayWidth = searchOverlayPane.getWidth();
        if (overlayWidth > 0) {
            double maxX = overlayWidth - barWidth - 14;
            if (targetX > maxX) {
                targetX = maxX;
            }
            if (targetX < 14) {
                targetX = 14;
            }
        }

        searchBar.setLayoutX(Math.round(targetX));
        searchBar.setLayoutY(6);
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

    public void toggleSearchBar() {
        if (searchBar != null && searchBar.isVisible()) {
            hideSearchBar();
        } else {
            showSearchBar();
        }
    }

    public void showSearchBar() {
        if (searchBar == null) {
            return;
        }
        searchBar.setVisible(true);
        searchBar.setManaged(true);
        if (!findButton.getStyleClass().contains("active")) {
            findButton.getStyleClass().add("active");
        }
        updateSearchBarPosition();
        Platform.runLater(() -> {
            updateSearchBarPosition();
            searchField.requestFocus();
            searchField.selectAll();
            if (!searchField.getText().isBlank()) {
                performSearch(searchField.getText());
            }
        });
    }

    public void hideSearchBar() {
        if (searchBar == null) {
            return;
        }
        searchBar.setVisible(false);
        searchBar.setManaged(false);
        findButton.getStyleClass().remove("active");
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
            if (onTerminalSizeChanged != null) {
                onTerminalSizeChanged.accept(cols + " × " + rows);
            }

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
                    if (currentCharset != null && currentCharset != StandardCharsets.UTF_8) {
                        sshService.setTerminalCharset(profile, this.channelId, currentCharset);
                    }
                    if (onTerminalSizeChanged != null) {
                        onTerminalSizeChanged.accept(getTerminalSize());
                    }
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

    private void openUrlInBrowser(String url) {
        if (url == null || url.isBlank()) {
            return;
        }
        String cleanUrl = url.trim();
        try {
            if (java.awt.Desktop.isDesktopSupported() && java.awt.Desktop.getDesktop().isSupported(java.awt.Desktop.Action.BROWSE)) {
                java.awt.Desktop.getDesktop().browse(new java.net.URI(cleanUrl));
                return;
            }
        } catch (Exception ignored) {
        }
        try {
            String os = System.getProperty("os.name", "").toLowerCase();
            if (os.contains("mac")) {
                new ProcessBuilder("open", cleanUrl).start();
            } else if (os.contains("win")) {
                new ProcessBuilder("rundll32", "url.dll,FileProtocolHandler", cleanUrl).start();
            } else {
                new ProcessBuilder("xdg-open", cleanUrl).start();
            }
        } catch (Exception ex) {
            System.err.println("[TerminalView] 打开链接失败: " + ex.getMessage());
        }
    }

    private void setupKeyboardHandlers() {
        addEventFilter(KeyEvent.KEY_PRESSED, this::handleKeyPressed);
        addEventFilter(KeyEvent.KEY_TYPED, this::handleKeyTyped);
        addEventFilter(KeyEvent.KEY_RELEASED, event -> {
            if (!event.isShortcutDown() && !event.isMetaDown() && !event.isControlDown()) {
                canvas.clearHoveredLink();
            }
        });
    }

    private void handleKeyPressed(KeyEvent event) {
        if (!terminalInputEnabled) {
            return;
        }

        // Toggle history sidebar: Cmd+Shift+H or Ctrl+Shift+H
        if (event.isShortcutDown() && event.isShiftDown() && event.getCode() == KeyCode.H) {
            toggleRightSidebar();
            event.consume();
            return;
        }

        // 0. Find handling: Cmd+F on Mac or Ctrl+F on Win/Linux
        boolean isMacCmdF = event.isMetaDown() && event.getCode() == KeyCode.F;
        boolean isCtrlF = event.isControlDown() && event.getCode() == KeyCode.F;
        if (isMacCmdF || isCtrlF) {
            toggleSearchBar();
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

        // Track command input for current session
        if (event.getCode() == KeyCode.ENTER) {
            String typed = currentInputLine.toString().trim();
            currentInputLine.setLength(0);
            if (!typed.isEmpty()) {
                recordSessionCommand(typed);
            } else {
                String lineFromBuffer = extractCommandLineFromBuffer();
                if (lineFromBuffer != null && !lineFromBuffer.isBlank()) {
                    recordSessionCommand(lineFromBuffer);
                }
            }
        } else if (event.getCode() == KeyCode.BACK_SPACE) {
            if (currentInputLine.length() > 0) {
                currentInputLine.deleteCharAt(currentInputLine.length() - 1);
            }
        } else if ((event.getCode() == KeyCode.C || event.getCode() == KeyCode.U) && event.isControlDown()) {
            currentInputLine.setLength(0);
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
            currentInputLine.append(character);
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

    public void sendInput(String text) {
        canvas.resetCursorBlink();
        if (buffer.getScrollOffset() > 0) {
            buffer.setScrollOffset(0);
            updateScrollBar();
            canvas.requestRender();
        }

        // Record programmatic multi-char commands containing \r (e.g. from snippets)
        if (text != null && text.contains("\r") && text.length() > 1) {
            for (String part : text.split("[\r\n]+")) {
                String c = part.trim();
                if (!c.isEmpty() && !c.startsWith("\u001B")) {
                    recordSessionCommand(c);
                }
            }
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
            String normalized = text.replace("\r\n", "\r").replace("\n", "\r");
            if (!normalized.contains("\r")) {
                currentInputLine.append(normalized);
            }
            sendInput(normalized);
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

    private void rebuildSnippetsMenu() {
        if (snippetsMenu == null) {
            return;
        }
        snippetsMenu.getItems().clear();

        MenuItem manageItem = new MenuItem("⚙ 管理常用命令...");
        manageItem.setOnAction(e -> showSnippetManagerDialog());
        snippetsMenu.getItems().addAll(manageItem, new SeparatorMenuItem());

        List<CommandSnippet> snippets = CommandSnippetService.loadSnippets();
        Map<String, List<CommandSnippet>> grouped = new LinkedHashMap<>();
        for (CommandSnippet s : snippets) {
            grouped.computeIfAbsent(s.category(), k -> new ArrayList<>()).add(s);
        }

        for (Map.Entry<String, List<CommandSnippet>> entry : grouped.entrySet()) {
            Menu catMenu = new Menu(entry.getKey());
            for (CommandSnippet snippet : entry.getValue()) {
                MenuItem item = new MenuItem(snippet.name());
                item.setOnAction(e -> SnippetExecutor.executeSnippet(
                        getScene() != null ? getScene().getWindow() : null,
                        snippet,
                        this::sendInput
                ));
                catMenu.getItems().add(item);
            }
            snippetsMenu.getItems().add(catMenu);
        }
    }

    private void showSnippetManagerDialog() {
        CommandSnippetDialog dialog = new CommandSnippetDialog(snippet ->
                SnippetExecutor.executeSnippet(
                        getScene() != null ? getScene().getWindow() : null,
                        snippet,
                        this::sendInput
                )
        );
        ThemeManager.applyDialogTheme(dialog, getScene() != null ? getScene().getWindow() : null);
        dialog.showAndWait();
    }

    public void close() {
        CommandSnippetService.removeListener(snippetChangeListener);
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

    private Node createRightSidebar() {
        HBox container = new HBox();
        container.getStyleClass().add("terminal-right-sidebar-container");

        // 1. History Drawer Panel (initially hidden)
        historyPanel = createHistoryPanel();
        historyPanel.setVisible(false);
        historyPanel.setManaged(false);

        // 2. Slim Sidebar Strip (Width 36px) with only one button: "历史命令"
        VBox sidebarStrip = new VBox(8);
        sidebarStrip.getStyleClass().add("terminal-sidebar-strip");
        sidebarStrip.setAlignment(Pos.TOP_CENTER);
        sidebarStrip.setPrefWidth(36);
        sidebarStrip.setMinWidth(36);
        sidebarStrip.setMaxWidth(36);

        historyTabButton = new Button();
        historyTabButton.getStyleClass().add("terminal-sidebar-tab-btn");
        historyTabButton.setTooltip(new Tooltip("历史命令 (点击展开/折叠)"));

        Label tabIcon = new Label("🕒");
        tabIcon.getStyleClass().add("terminal-sidebar-tab-icon");

        Label tabText = new Label("历\n史\n命\n令");
        tabText.getStyleClass().add("terminal-sidebar-tab-text");
        tabText.setAlignment(Pos.CENTER);

        VBox tabContent = new VBox(2, tabIcon, tabText);
        tabContent.setAlignment(Pos.CENTER);
        historyTabButton.setGraphic(tabContent);
        historyTabButton.setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
        historyTabButton.setOnAction(e -> toggleRightSidebar());

        sidebarStrip.getChildren().add(historyTabButton);

        container.getChildren().addAll(historyPanel, sidebarStrip);
        return container;
    }

    private void toggleRightSidebar() {
        rightSidebarExpanded = !rightSidebarExpanded;
        historyPanel.setVisible(rightSidebarExpanded);
        historyPanel.setManaged(rightSidebarExpanded);
        if (rightSidebarExpanded) {
            if (!historyTabButton.getStyleClass().contains("active")) {
                historyTabButton.getStyleClass().add("active");
            }
            if (historySearchField != null) {
                historySearchField.requestFocus();
            }
        } else {
            historyTabButton.getStyleClass().remove("active");
            if (canvasPane != null) {
                canvasPane.requestFocus();
            }
        }
    }

    private VBox createHistoryPanel() {
        VBox panel = new VBox();
        panel.getStyleClass().add("terminal-history-panel");
        panel.setPrefWidth(260);
        panel.setMinWidth(200);
        panel.setMaxWidth(360);

        // Header
        Label titleLabel = new Label("历史命令");
        titleLabel.getStyleClass().add("terminal-history-title");

        historyCountBadge = new Label("0");
        historyCountBadge.getStyleClass().add("history-count-badge");

        HBox titleBox = new HBox(6, titleLabel, historyCountBadge);
        titleBox.setAlignment(Pos.CENTER_LEFT);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Button clearBtn = new Button("清空");
        clearBtn.getStyleClass().add("terminal-history-action-btn");
        clearBtn.setTooltip(new Tooltip("清空当前会话的历史命令"));
        clearBtn.setOnAction(e -> {
            commandHistory.clear();
            updateHistoryCount();
        });

        Button collapseBtn = new Button("▶");
        collapseBtn.getStyleClass().add("terminal-history-action-btn");
        collapseBtn.setTooltip(new Tooltip("收起历史命令侧边栏"));
        collapseBtn.setOnAction(e -> toggleRightSidebar());

        HBox header = new HBox(8, titleBox, spacer, clearBtn, collapseBtn);
        header.setAlignment(Pos.CENTER_LEFT);
        header.getStyleClass().add("terminal-history-header");

        // Search Bar
        historySearchField = new TextField();
        historySearchField.setPromptText("搜索当前会话命令...");
        historySearchField.getStyleClass().add("sidebar-search-input");
        historySearchField.textProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal == null || newVal.isBlank()) {
                filteredCommandHistory.setPredicate(cmd -> true);
            } else {
                String q = newVal.trim().toLowerCase();
                filteredCommandHistory.setPredicate(cmd -> cmd.toLowerCase().contains(q));
            }
        });

        HBox searchContainer = new HBox(historySearchField);
        searchContainer.setPadding(new Insets(6, 8, 6, 8));
        HBox.setHgrow(historySearchField, Priority.ALWAYS);

        // List View
        historyListView = createHistoryListView();
        VBox.setVgrow(historyListView, Priority.ALWAYS);

        panel.getChildren().addAll(header, searchContainer, historyListView);
        return panel;
    }

    private ListView<String> createHistoryListView() {
        ListView<String> listView = new ListView<>(filteredCommandHistory);
        listView.getStyleClass().add("terminal-history-list");

        Label emptyLabel = new Label("当前会话暂无执行历史\n输入命令回车后自动记录");
        emptyLabel.getStyleClass().add("status-muted");
        emptyLabel.setAlignment(Pos.CENTER);
        emptyLabel.setTextAlignment(TextAlignment.CENTER);
        listView.setPlaceholder(emptyLabel);

        listView.setCellFactory(lv -> new ListCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setGraphic(null);
                } else {
                    int index = getIndex() + 1;
                    Label idxLabel = new Label(String.valueOf(index));
                    idxLabel.getStyleClass().add("history-item-idx");
                    idxLabel.setPrefWidth(24);

                    Label cmdLabel = new Label(item);
                    cmdLabel.getStyleClass().add("history-item-text");
                    cmdLabel.setMaxWidth(Double.MAX_VALUE);
                    HBox.setHgrow(cmdLabel, Priority.ALWAYS);

                    HBox row = new HBox(6, idxLabel, cmdLabel);
                    row.setAlignment(Pos.CENTER_LEFT);
                    setGraphic(row);
                    setTooltip(new Tooltip(item));
                }
            }
        });

        // Double click to execute command
        listView.setOnMouseClicked(event -> {
            if (event.getClickCount() == 2) {
                String selected = listView.getSelectionModel().getSelectedItem();
                if (selected != null) {
                    sendInput(selected + "\r");
                    if (canvasPane != null) {
                        canvasPane.requestFocus();
                    }
                }
            }
        });

        // Enter key to execute command
        listView.setOnKeyPressed(event -> {
            if (event.getCode() == KeyCode.ENTER) {
                String selected = listView.getSelectionModel().getSelectedItem();
                if (selected != null) {
                    sendInput(selected + "\r");
                    if (canvasPane != null) {
                        canvasPane.requestFocus();
                    }
                    event.consume();
                }
            }
        });

        // Context menu
        ContextMenu contextMenu = new ContextMenu();
        MenuItem runItem = new MenuItem("⚡ 执行命令");
        runItem.setOnAction(e -> {
            String selected = listView.getSelectionModel().getSelectedItem();
            if (selected != null) {
                sendInput(selected + "\r");
                if (canvasPane != null) {
                    canvasPane.requestFocus();
                }
            }
        });

        MenuItem insertItem = new MenuItem("📋 填入终端 (不回车)");
        insertItem.setOnAction(e -> {
            String selected = listView.getSelectionModel().getSelectedItem();
            if (selected != null) {
                sendInput(selected);
                if (canvasPane != null) {
                    canvasPane.requestFocus();
                }
            }
        });

        MenuItem copyItem = new MenuItem("📄 复制命令");
        copyItem.setOnAction(e -> {
            String selected = listView.getSelectionModel().getSelectedItem();
            if (selected != null) {
                ClipboardContent content = new ClipboardContent();
                content.putString(selected);
                Clipboard.getSystemClipboard().setContent(content);
            }
        });

        MenuItem deleteItem = new MenuItem("🗑 从历史中移除");
        deleteItem.setOnAction(e -> {
            String selected = listView.getSelectionModel().getSelectedItem();
            if (selected != null) {
                commandHistory.remove(selected);
                updateHistoryCount();
            }
        });

        contextMenu.getItems().addAll(runItem, insertItem, copyItem, new SeparatorMenuItem(), deleteItem);
        listView.setContextMenu(contextMenu);

        return listView;
    }

    public void recordSessionCommand(String cmd) {
        if (cmd == null) {
            return;
        }
        String trimmed = cmd.trim();
        if (trimmed.isEmpty()) {
            return;
        }

        if (trimmed.equals("cd") || trimmed.equals("cd ~")) {
            Platform.runLater(() -> updateWorkingDirectory("~"));
        } else if (trimmed.startsWith("cd ")) {
            String target = trimmed.substring(3).trim();
            if ((target.startsWith("\"") && target.endsWith("\"")) || (target.startsWith("'") && target.endsWith("'"))) {
                target = target.substring(1, target.length() - 1);
            }
            if (target.startsWith("/") || target.startsWith("~")) {
                String finalTarget = target;
                Platform.runLater(() -> updateWorkingDirectory(finalTarget));
            }
        }

        Platform.runLater(() -> {
            if (!commandHistory.isEmpty() && commandHistory.get(commandHistory.size() - 1).equals(trimmed)) {
                return;
            }
            commandHistory.add(trimmed);
            updateHistoryCount();
            if (historyListView != null) {
                historyListView.scrollTo(commandHistory.size() - 1);
            }
        });
    }

    private void updateHistoryCount() {
        if (historyCountBadge != null) {
            historyCountBadge.setText(String.valueOf(commandHistory.size()));
        }
    }

    public String getCurrentWorkingDirectory() {
        return currentWorkingDirectory;
    }

    public void updateWorkingDirectory(String dir) {
        if (dir == null || dir.isBlank()) {
            return;
        }
        this.currentWorkingDirectory = dir.trim();
        openInSftpBtn.setTooltip(new Tooltip("在 SFTP 文件管理中打开当前目录 (" + currentWorkingDirectory + ")"));
        if (onWorkingDirectoryChanged != null) {
            onWorkingDirectoryChanged.accept(currentWorkingDirectory);
        }
    }

    public void setOnWorkingDirectoryChanged(java.util.function.Consumer<String> listener) {
        this.onWorkingDirectoryChanged = listener;
        if (listener != null && currentWorkingDirectory != null) {
            listener.accept(currentWorkingDirectory);
        }
    }

    public void setOnOpenInSftpRequested(java.util.function.Consumer<String> listener) {
        this.onOpenInSftpRequested = listener;
    }

    public String getTerminalSize() {
        return buffer.getCols() + " × " + buffer.getRows();
    }

    public void setOnTerminalSizeChanged(java.util.function.Consumer<String> listener) {
        this.onTerminalSizeChanged = listener;
        if (listener != null) {
            listener.accept(getTerminalSize());
        }
    }

    public void applyCharset(java.nio.charset.Charset charset, String label) {
        if (charset == null) {
            return;
        }
        this.currentCharset = charset;
        if (label != null) {
            this.encodingMenu.setText(label);
        }
        if (currentProfile != null && sshService != null) {
            sshService.setTerminalCharset(currentProfile, channelId, charset);
        }
    }

    private String extractCommandLineFromBuffer() {
        int r = buffer.getCursorRow();
        String line = buffer.getGridLineText(r);
        if (line == null || line.isBlank()) {
            return null;
        }
        int promptIdx = -1;
        for (String delim : new String[]{"$ ", "# ", "% ", "> "}) {
            int idx = line.lastIndexOf(delim);
            if (idx > promptIdx) {
                promptIdx = idx + delim.length();
            }
        }
        if (promptIdx >= 0 && promptIdx < line.length()) {
            String cmd = line.substring(promptIdx).trim();
            if (!cmd.isEmpty()) {
                return cmd;
            }
        }
        return null;
    }
}
