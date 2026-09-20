package com.yanshuwang.remoteworkbench.ui;

import com.yanshuwang.remoteworkbench.config.ConfigStorageService;
import com.yanshuwang.remoteworkbench.connection.ConnectionProfile;
import com.yanshuwang.remoteworkbench.sftp.SftpService;
import com.yanshuwang.remoteworkbench.ssh.SshConnectionService;
import com.yanshuwang.remoteworkbench.ui.terminal.CursorStyle;
import com.yanshuwang.remoteworkbench.ui.terminal.TerminalTheme;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.Side;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.MenuItem;
import javafx.scene.control.PasswordField;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.shape.Circle;
import javafx.stage.FileChooser;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.prefs.Preferences;

public final class MainView extends BorderPane implements AutoCloseable {
    private static final List<String> COMMON_TERMINAL_FONTS = List.of(
            "Menlo",
            "SF Mono",
            "Monaco",
            "Consolas",
            "Courier New"
    );
    private static final Preferences PREFERENCES = Preferences.userNodeForPackage(MainView.class);

    private final ObservableList<ConnectionProfile> connectionProfiles = FXCollections.observableArrayList();
    private final ListView<ConnectionProfile> connectionList = new ListView<>(connectionProfiles);

    private final SshConnectionService sshConnectionService = new SshConnectionService();
    private final SftpService sftpService = new SftpService(sshConnectionService);

    private final Label activeConnectionLabel = new Label("未连接");
    private final Label statusLabel = new Label("就绪");
    private final Label connectionStatusLabel = new Label("SSH：未连接");
    private final Button connectButton = new Button("连接");
    private final Button disconnectButton = new Button("断开连接");

    private final Map<String, ProfileWorkspace> profileWorkspaces = new ConcurrentHashMap<>();
    private final Set<String> connectingProfileIds = ConcurrentHashMap.newKeySet();
    private final StackPane workspaceContainer = new StackPane();
    private StackPane welcomeOverlay;

    private String terminalFontFamily = resolveMonospaceFont();
    private int terminalFontSize = Math.max(10, Math.min(32, PREFERENCES.getInt("terminal.font.size", 14)));
    private TerminalTheme terminalTheme = resolveTerminalTheme();
    private CursorStyle terminalCursorStyle = resolveCursorStyle();
    private boolean terminalCursorBlink = PREFERENCES.getBoolean("terminal.cursor.blink", true);
    private ConnectionProfile selectedProfile;

    private boolean sidebarCollapsed = PREFERENCES.getBoolean("sidebar.collapsed", false);
    private final StackPane sidebarContainer = new StackPane();
    private VBox fullSidebar;
    private VBox slimSidebar;
    private final VBox slimServerListBox = new VBox(8);

    private static String resolveMonospaceFont() {
        String saved = PREFERENCES.get("terminal.font.family", "Menlo");
        if (saved == null || saved.isBlank() || saved.contains("PingFang") || saved.contains("Song") || saved.contains("Hei")) {
            saved = "Menlo";
            PREFERENCES.put("terminal.font.family", saved);
        }
        return saved;
    }

    private static TerminalTheme resolveTerminalTheme() {
        String id = PREFERENCES.get("terminal.theme", TerminalTheme.DEFAULT_DARK.id());
        return TerminalTheme.getTheme(id);
    }

    private static CursorStyle resolveCursorStyle() {
        String name = PREFERENCES.get("terminal.cursor.style", CursorStyle.BLOCK.name());
        return CursorStyle.fromString(name);
    }

    public MainView() {
        getStyleClass().add("app-root");

        List<ConnectionProfile> loaded = ConfigStorageService.loadConnections();
        if (loaded != null && !loaded.isEmpty()) {
            connectionProfiles.addAll(loaded);
        }

        int savedIdleMinutes = PREFERENCES.getInt("ssh.idle.timeout.minutes", 10);
        sshConnectionService.setIdleTimeoutMinutes(savedIdleMinutes);
        sshConnectionService.setOnIdleTimeout(profileId -> Platform.runLater(() -> handleIdleTimeoutDisconnect(profileId)));

        setTop(createTopBar());
        initSidebars();
        setLeft(sidebarContainer);
        applySidebarState();
        setCenter(createWorkspace());
        setBottom(createStatusBar());

        addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (event.isShortcutDown() && event.getCode() == KeyCode.B) {
                toggleSidebar();
                event.consume();
            }
        });

        connectionList.getSelectionModel().selectedItemProperty().addListener((observable, previous, selected) -> {
            selectedProfile = selected;
            updateSelectionState();
            showWorkspaceFor(selected);
        });

        if (!connectionProfiles.isEmpty()) {
            connectionList.getSelectionModel().select(0);
        }
    }

    private Node createTopBar() {
        Button toggleSidebarTopBtn = new Button("◧");
        toggleSidebarTopBtn.getStyleClass().add("top-bar-icon-btn");
        toggleSidebarTopBtn.setTooltip(new Tooltip("展开/收起连接侧边栏 (Cmd+B)"));
        toggleSidebarTopBtn.setOnAction(event -> toggleSidebar());

        Label brand = new Label("远程工作台");
        brand.getStyleClass().add("brand-label");

        Label subtitle = new Label("SSH / SFTP 工具");
        subtitle.getStyleClass().add("subtitle-label");

        HBox brandBox = new HBox(10, toggleSidebarTopBtn, brand, subtitle);
        brandBox.setAlignment(Pos.CENTER_LEFT);

        Button newConnectionButton = new Button("新建连接");
        newConnectionButton.getStyleClass().add("primary-button");
        newConnectionButton.setOnAction(event -> showConnectionDialog(null));

        connectButton.getStyleClass().add("primary-button");
        connectButton.setTooltip(new Tooltip("连接至当前选中的服务器"));
        connectButton.setOnAction(event -> {
            if (selectedProfile != null) {
                promptConnectProfile(selectedProfile);
            }
        });

        disconnectButton.getStyleClass().add("secondary-button");
        disconnectButton.setDisable(true);
        disconnectButton.setVisible(false);
        disconnectButton.setManaged(false);
        disconnectButton.setTooltip(new Tooltip("断开当前服务器连接"));
        disconnectButton.setOnAction(event -> disconnectSelectedConnection());

        Button settingsButton = new Button("设置");
        settingsButton.getStyleClass().add("secondary-button");
        settingsButton.setTooltip(new Tooltip("打开应用设置"));
        settingsButton.setOnAction(event -> showSettingsDialog());

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox topBar = new HBox(14, brandBox, spacer, newConnectionButton, connectButton, disconnectButton, settingsButton);
        topBar.setAlignment(Pos.CENTER_LEFT);
        topBar.getStyleClass().add("top-bar");
        return topBar;
    }

    private void initSidebars() {
        // --- Full Sidebar ---
        Label title = new Label("连接");
        title.getStyleClass().add("section-label");

        Button collapseBtn = new Button("◀");
        collapseBtn.getStyleClass().add("sidebar-toggle-btn");
        collapseBtn.setTooltip(new Tooltip("收起侧边栏 (Cmd+B)"));
        collapseBtn.setOnAction(e -> toggleSidebar());

        Region titleSpacer = new Region();
        HBox.setHgrow(titleSpacer, Priority.ALWAYS);
        HBox titleBar = new HBox(8, title, titleSpacer, collapseBtn);
        titleBar.setAlignment(Pos.CENTER_LEFT);

        Button addButton = new Button("+  添加连接");
        addButton.getStyleClass().add("sidebar-action");
        addButton.setMaxWidth(Double.MAX_VALUE);
        addButton.setOnAction(event -> showConnectionDialog(null));

        connectionList.getStyleClass().add("connection-list");
        connectionList.setPlaceholder(new Label("暂无连接"));
        connectionList.setCellFactory(list -> new ListCell<>() {
            @Override
            protected void updateItem(ConnectionProfile profile, boolean empty) {
                super.updateItem(profile, empty);

                if (empty || profile == null) {
                    setText(null);
                    setGraphic(null);
                    setContextMenu(null);
                    return;
                }

                Label name = new Label(profile.name());
                name.getStyleClass().add("connection-name");
                String authBadge = profile.isKeyAuth() ? " [Key]" : "";
                Label address = new Label(profile.username() + "@" + profile.host() + ":" + profile.port() + authBadge);
                address.getStyleClass().add("connection-address");

                Circle statusDot = new Circle(4);
                Label statusText = new Label();

                boolean isConn = sshConnectionService.isConnected(profile);
                boolean isIng = connectingProfileIds.contains(profile.id());

                if (isConn) {
                    statusDot.setFill(javafx.scene.paint.Color.web("#34d399"));
                    statusText.setText("在线");
                    statusText.setStyle("-fx-text-fill: #34d399; -fx-font-size: 11px;");
                } else if (isIng) {
                    statusDot.setFill(javafx.scene.paint.Color.web("#fbbf24"));
                    statusText.setText("连接中...");
                    statusText.setStyle("-fx-text-fill: #fbbf24; -fx-font-size: 11px;");
                } else {
                    statusDot.setFill(javafx.scene.paint.Color.web("#555865"));
                    statusText.setText("未连接");
                    statusText.setStyle("-fx-text-fill: #808694; -fx-font-size: 11px;");
                }

                HBox statusBadge = new HBox(4, statusDot, statusText);
                statusBadge.setAlignment(Pos.CENTER_RIGHT);

                ContextMenu menu = createProfileContextMenu(profile);

                Button moreBtn = new Button("⋯");
                moreBtn.getStyleClass().add("connection-more-btn");
                moreBtn.setTooltip(new Tooltip("更多操作"));
                moreBtn.setOnAction(e -> {
                    connectionList.getSelectionModel().select(profile);
                    menu.show(moreBtn, Side.BOTTOM, 0, 0);
                    e.consume();
                });

                VBox rightBox = new VBox(2, statusBadge, moreBtn);
                rightBox.setAlignment(Pos.TOP_RIGHT);

                VBox leftBox = new VBox(3, name, address);
                HBox.setHgrow(leftBox, Priority.ALWAYS);

                HBox row = new HBox(8, leftBox, rightBox);
                row.setAlignment(Pos.CENTER_LEFT);
                setText(null);
                setGraphic(row);
                setContextMenu(menu);
            }
        });

        connectionList.setOnMouseClicked(event -> {
            if (event.getClickCount() == 2 && selectedProfile != null) {
                if (!sshConnectionService.isConnected(selectedProfile)) {
                    promptConnectProfile(selectedProfile);
                }
            }
        });

        fullSidebar = new VBox(14, titleBar, addButton, connectionList);
        fullSidebar.setPadding(new Insets(16, 14, 16, 14));
        fullSidebar.setPrefWidth(260);
        fullSidebar.setMinWidth(260);
        fullSidebar.setMaxWidth(260);
        VBox.setVgrow(connectionList, Priority.ALWAYS);
        fullSidebar.getStyleClass().add("sidebar");

        // --- Slim Sidebar ---
        Button expandBtn = new Button("▶");
        expandBtn.getStyleClass().add("sidebar-toggle-btn");
        expandBtn.setTooltip(new Tooltip("展开侧边栏 (Cmd+B)"));
        expandBtn.setOnAction(e -> toggleSidebar());

        Button slimAddBtn = new Button("+");
        slimAddBtn.getStyleClass().add("sidebar-slim-add-btn");
        slimAddBtn.setTooltip(new Tooltip("添加连接"));
        slimAddBtn.setOnAction(e -> showConnectionDialog(null));

        slimServerListBox.setAlignment(Pos.TOP_CENTER);
        ScrollPane slimScroll = new ScrollPane(slimServerListBox);
        slimScroll.setFitToWidth(true);
        slimScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        slimScroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        slimScroll.getStyleClass().add("slim-sidebar-scroll");
        VBox.setVgrow(slimScroll, Priority.ALWAYS);

        slimSidebar = new VBox(10, expandBtn, slimAddBtn, slimScroll);
        slimSidebar.setPadding(new Insets(16, 6, 16, 6));
        slimSidebar.setPrefWidth(52);
        slimSidebar.setMinWidth(52);
        slimSidebar.setMaxWidth(52);
        slimSidebar.setAlignment(Pos.TOP_CENTER);
        slimSidebar.getStyleClass().add("sidebar");
    }

    private ContextMenu createProfileContextMenu(ConnectionProfile profile) {
        ContextMenu menu = new ContextMenu();
        boolean isConn = sshConnectionService.isConnected(profile);

        if (isConn) {
            MenuItem disconnectItem = new MenuItem("断开连接");
            disconnectItem.setOnAction(e -> disconnectProfile(profile));

            MenuItem newTabItem = new MenuItem("新建终端标签页");
            newTabItem.setOnAction(e -> {
                ProfileWorkspace ws = getOrCreateWorkspace(profile);
                ws.getTerminalSessionPane().createNewTab(null);
                if (selectedProfile != profile) {
                    connectionList.getSelectionModel().select(profile);
                }
            });

            MenuItem editItem = new MenuItem("编辑配置...");
            editItem.setOnAction(e -> showConnectionDialog(profile));

            MenuItem dupItem = new MenuItem("复制配置 (Duplicate)");
            dupItem.setOnAction(e -> duplicateProfile(profile));

            MenuItem deleteItem = new MenuItem("删除连接");
            deleteItem.setOnAction(e -> deleteProfile(profile));

            menu.getItems().addAll(disconnectItem, newTabItem, new SeparatorMenuItem(), editItem, dupItem, new SeparatorMenuItem(), deleteItem);
        } else {
            MenuItem connectItem = new MenuItem("连接此服务器");
            connectItem.setOnAction(e -> promptConnectProfile(profile));

            MenuItem editItem = new MenuItem("编辑配置...");
            editItem.setOnAction(e -> showConnectionDialog(profile));

            MenuItem dupItem = new MenuItem("复制配置 (Duplicate)");
            dupItem.setOnAction(e -> duplicateProfile(profile));

            MenuItem deleteItem = new MenuItem("删除连接");
            deleteItem.setOnAction(e -> deleteProfile(profile));

            menu.getItems().addAll(connectItem, editItem, dupItem, new SeparatorMenuItem(), deleteItem);
        }
        return menu;
    }

    private void refreshSlimSidebar() {
        slimServerListBox.getChildren().clear();
        for (ConnectionProfile profile : connectionProfiles) {
            Button itemBtn = new Button();
            itemBtn.getStyleClass().add("slim-server-btn");
            boolean isSelected = (selectedProfile != null && profile.id().equals(selectedProfile.id()));
            if (isSelected) {
                itemBtn.getStyleClass().add("selected");
            }

            boolean isConn = sshConnectionService.isConnected(profile);
            boolean isIng = connectingProfileIds.contains(profile.id());

            Circle dot = new Circle(3.5);
            if (isConn) {
                dot.setFill(javafx.scene.paint.Color.web("#34d399"));
            } else if (isIng) {
                dot.setFill(javafx.scene.paint.Color.web("#fbbf24"));
            } else {
                dot.setFill(javafx.scene.paint.Color.web("#555865"));
            }

            String initial = profile.name().isBlank() ? "S" : profile.name().substring(0, 1).toUpperCase();
            Label initialLabel = new Label(initial);
            initialLabel.setStyle("-fx-font-size: 13px; -fx-font-weight: bold; -fx-text-fill: #e8e9ed;");

            StackPane badgeStack = new StackPane();
            badgeStack.getChildren().addAll(initialLabel, dot);
            StackPane.setAlignment(dot, Pos.BOTTOM_RIGHT);

            itemBtn.setGraphic(badgeStack);
            String statusDesc = isConn ? "在线" : (isIng ? "连接中..." : "未连接");
            Tooltip.install(itemBtn, new Tooltip(profile.name() + " (" + profile.host() + ":" + profile.port() + ")\n状态：" + statusDesc));

            itemBtn.setOnAction(e -> {
                connectionList.getSelectionModel().select(profile);
            });

            itemBtn.setOnMouseClicked(e -> {
                if (e.getClickCount() == 2) {
                    if (!sshConnectionService.isConnected(profile)) {
                        promptConnectProfile(profile);
                    }
                }
            });

            ContextMenu slimMenu = createProfileContextMenu(profile);
            itemBtn.setContextMenu(slimMenu);

            slimServerListBox.getChildren().add(itemBtn);
        }
    }

    private void toggleSidebar() {
        sidebarCollapsed = !sidebarCollapsed;
        PREFERENCES.putBoolean("sidebar.collapsed", sidebarCollapsed);
        applySidebarState();
    }

    private void applySidebarState() {
        if (sidebarCollapsed) {
            refreshSlimSidebar();
            sidebarContainer.getChildren().setAll(slimSidebar);
        } else {
            sidebarContainer.getChildren().setAll(fullSidebar);
        }
    }

    private Node createWorkspace() {
        Label title = new Label("远程工作区");
        title.getStyleClass().add("workspace-title");

        Label description = new Label("创建或选择一个连接，即可使用远程终端执行命令，或使用 SFTP 浏览、上传和下载文件。");
        description.setWrapText(true);
        description.getStyleClass().add("workspace-description");

        Button createButton = new Button("创建第一个连接");
        createButton.getStyleClass().add("primary-button");
        createButton.setOnAction(event -> showConnectionDialog(null));

        VBox welcomeCard = new VBox(14, title, description, createButton);
        welcomeCard.setAlignment(Pos.CENTER);
        welcomeCard.setMaxWidth(480);
        welcomeCard.getStyleClass().add("welcome-card");

        welcomeOverlay = new StackPane(welcomeCard);
        welcomeOverlay.getStyleClass().add("workspace-overview");

        StackPane workspaceRoot = new StackPane(workspaceContainer, welcomeOverlay);
        welcomeOverlay.setVisible(connectionProfiles.isEmpty());
        return workspaceRoot;
    }

    private void showWorkspaceFor(ConnectionProfile profile) {
        if (profile == null) {
            workspaceContainer.getChildren().clear();
            welcomeOverlay.setVisible(connectionProfiles.isEmpty());
            return;
        }
        ProfileWorkspace ws = getOrCreateWorkspace(profile);
        workspaceContainer.getChildren().setAll(ws.getRoot());
        welcomeOverlay.setVisible(false);
    }

    private ProfileWorkspace getOrCreateWorkspace(ConnectionProfile profile) {
        return profileWorkspaces.computeIfAbsent(profile.id(), id -> {
            ProfileWorkspace ws = new ProfileWorkspace(
                    profile,
                    terminalFontFamily,
                    terminalFontSize,
                    terminalTheme,
                    terminalCursorStyle,
                    terminalCursorBlink
            );
            ws.setOnReconnectRequested(() -> promptConnectProfile(profile));
            return ws;
        });
    }

    private void applyDialogTheme(Dialog<?> dialog) {
        if (dialog == null || dialog.getDialogPane() == null) {
            return;
        }
        if (getScene() != null && getScene().getWindow() != null) {
            dialog.initOwner(getScene().getWindow());
        }
        String css = getClass().getResource("/com/yanshuwang/remoteworkbench/application.css").toExternalForm();
        if (!dialog.getDialogPane().getStylesheets().contains(css)) {
            dialog.getDialogPane().getStylesheets().add(css);
        }
    }

    public void showSettingsDialog() {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("设置");
        dialog.setHeaderText("终端外观与偏好设置");
        applyDialogTheme(dialog);

        Label icon = new Label("⚙");
        icon.setStyle("-fx-font-size: 24px; -fx-text-fill: #4c82e8;");
        dialog.setGraphic(icon);

        ButtonType saveButton = new ButtonType("保存设置", ButtonBar.ButtonData.OK_DONE);
        ButtonType cancelButton = new ButtonType("取消", ButtonBar.ButtonData.CANCEL_CLOSE);
        dialog.getDialogPane().getButtonTypes().addAll(saveButton, cancelButton);

        Node saveBtn = dialog.getDialogPane().lookupButton(saveButton);
        if (saveBtn != null) {
            saveBtn.getStyleClass().add("dialog-primary-button");
        }
        Node cancelBtn = dialog.getDialogPane().lookupButton(cancelButton);
        if (cancelBtn != null) {
            cancelBtn.getStyleClass().add("dialog-secondary-button");
        }

        List<String> availableFonts = new ArrayList<>();
        List<String> installedFonts = Font.getFamilies();
        for (String font : COMMON_TERMINAL_FONTS) {
            if (installedFonts.contains(font)) {
                availableFonts.add(font);
            }
        }
        if (!availableFonts.contains(terminalFontFamily)) {
            availableFonts.add(0, terminalFontFamily);
        }

        ComboBox<String> fontSelector = new ComboBox<>();
        fontSelector.getItems().setAll(availableFonts);
        fontSelector.setValue(terminalFontFamily);
        fontSelector.setMaxWidth(Double.MAX_VALUE);

        Spinner<Integer> sizeSpinner = new Spinner<>(10, 32, terminalFontSize);
        sizeSpinner.setEditable(true);
        sizeSpinner.setMaxWidth(Double.MAX_VALUE);

        ComboBox<TerminalTheme> themeSelector = new ComboBox<>();
        themeSelector.getItems().setAll(TerminalTheme.getAllThemes());
        themeSelector.setValue(terminalTheme);
        themeSelector.setMaxWidth(Double.MAX_VALUE);
        themeSelector.setCellFactory(lv -> new ListCell<>() {
            @Override
            protected void updateItem(TerminalTheme item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : item.displayName());
            }
        });
        themeSelector.setButtonCell(new ListCell<>() {
            @Override
            protected void updateItem(TerminalTheme item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : item.displayName());
            }
        });

        ComboBox<CursorStyle> cursorStyleSelector = new ComboBox<>();
        cursorStyleSelector.getItems().setAll(CursorStyle.values());
        cursorStyleSelector.setValue(terminalCursorStyle);
        cursorStyleSelector.setMaxWidth(Double.MAX_VALUE);
        cursorStyleSelector.setCellFactory(lv -> new ListCell<>() {
            @Override
            protected void updateItem(CursorStyle item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : item.getDisplayName());
            }
        });
        cursorStyleSelector.setButtonCell(new ListCell<>() {
            @Override
            protected void updateItem(CursorStyle item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : item.getDisplayName());
            }
        });

        CheckBox cursorBlinkCheck = new CheckBox("启用光标呼吸闪烁效果");
        cursorBlinkCheck.getStyleClass().add("dialog-form-hint");
        cursorBlinkCheck.setSelected(terminalCursorBlink);

        Label previewTitle = new Label("效果预览：");
        previewTitle.getStyleClass().add("dialog-form-hint");

        Label previewLabel = new Label("user@workbench:~$ ls -lh\ndrwxr-xr-x 4 user staff 128B Sep 20 13:20 project\n-rw-r--r-- 1 user staff 2.4K Sep 20 13:20 log.txt\n0123456789 ABCDEF");
        previewLabel.getStyleClass().add("dialog-preview-box");
        previewLabel.setMaxWidth(Double.MAX_VALUE);

        Runnable updatePreview = () -> {
            String font = fontSelector.getValue();
            int sz = parseFontSize(sizeSpinner);
            TerminalTheme th = themeSelector.getValue();
            if (font != null) {
                previewLabel.setFont(Font.font(font, FontWeight.NORMAL, sz));
            }
            if (th != null) {
                String bgHex = String.format("#%02x%02x%02x",
                        (int) (th.background().getRed() * 255),
                        (int) (th.background().getGreen() * 255),
                        (int) (th.background().getBlue() * 255));
                String fgHex = String.format("#%02x%02x%02x",
                        (int) (th.foreground().getRed() * 255),
                        (int) (th.foreground().getGreen() * 255),
                        (int) (th.foreground().getBlue() * 255));
                previewLabel.setStyle("-fx-background-color: " + bgHex + "; -fx-text-fill: " + fgHex
                        + "; -fx-padding: 10px; -fx-background-radius: 6px; -fx-border-color: #3e424f; -fx-border-radius: 6px;");
            }
        };
        fontSelector.valueProperty().addListener((o, old, val) -> updatePreview.run());
        sizeSpinner.valueProperty().addListener((o, old, val) -> updatePreview.run());
        sizeSpinner.getEditor().textProperty().addListener((o, old, val) -> updatePreview.run());
        themeSelector.valueProperty().addListener((o, old, val) -> updatePreview.run());
        updatePreview.run();

        GridPane form = new GridPane();
        form.setHgap(14);
        form.setVgap(12);
        form.setPadding(new Insets(12, 0, 4, 0));

        ColumnConstraints labelColumn = new ColumnConstraints();
        labelColumn.setMinWidth(90);
        ColumnConstraints inputColumn = new ColumnConstraints();
        inputColumn.setHgrow(Priority.ALWAYS);
        form.getColumnConstraints().addAll(labelColumn, inputColumn);

        int r = 0;
        Label fontLabel = new Label("终端字体");
        fontLabel.getStyleClass().add("dialog-form-label");
        form.add(fontLabel, 0, r);
        form.add(fontSelector, 1, r++);

        Label sizeLabel = new Label("字体大小");
        sizeLabel.getStyleClass().add("dialog-form-label");
        form.add(sizeLabel, 0, r);
        form.add(sizeSpinner, 1, r++);

        Label themeLabel = new Label("配色主题");
        themeLabel.getStyleClass().add("dialog-form-label");
        form.add(themeLabel, 0, r);
        form.add(themeSelector, 1, r++);

        Label cursorLabel = new Label("光标样式");
        cursorLabel.getStyleClass().add("dialog-form-label");
        form.add(cursorLabel, 0, r);
        form.add(cursorStyleSelector, 1, r++);

        Label blinkLabel = new Label("光标动效");
        blinkLabel.getStyleClass().add("dialog-form-label");
        form.add(blinkLabel, 0, r);
        form.add(cursorBlinkCheck, 1, r++);

        int currentIdleTimeout = PREFERENCES.getInt("ssh.idle.timeout.minutes", 10);
        Spinner<Integer> idleTimeoutSpinner = new Spinner<>(0, 120, currentIdleTimeout);
        idleTimeoutSpinner.setEditable(true);
        idleTimeoutSpinner.setMaxWidth(110);

        Label idleLabel = new Label("闲置超时断开");
        idleLabel.getStyleClass().add("dialog-form-label");
        form.add(idleLabel, 0, r);
        Label idleHint = new Label("分钟 (0 为禁用自动断开，默认 10 分钟)");
        idleHint.getStyleClass().add("dialog-form-hint");
        HBox idleBox = new HBox(8, idleTimeoutSpinner, idleHint);
        idleBox.setAlignment(Pos.CENTER_LEFT);
        form.add(idleBox, 1, r++);

        form.add(previewTitle, 0, r);
        form.add(previewLabel, 1, r++);

        Button exportBtn = new Button("导出连接配置备份 (JSON)...");
        exportBtn.getStyleClass().add("dialog-secondary-button");
        exportBtn.setOnAction(e -> exportProfilesBackup(dialog.getDialogPane().getScene().getWindow()));

        Button importBtn = new Button("导入连接配置备份 (JSON)...");
        importBtn.getStyleClass().add("dialog-secondary-button");
        importBtn.setOnAction(e -> importProfilesBackup(dialog.getDialogPane().getScene().getWindow()));

        HBox backupBox = new HBox(10, exportBtn, importBtn);
        Label backupLabel = new Label("数据备份");
        backupLabel.getStyleClass().add("dialog-form-label");
        form.add(backupLabel, 0, r);
        form.add(backupBox, 1, r++);

        dialog.getDialogPane().setContent(form);
        dialog.getDialogPane().setMinWidth(500);
        dialog.setResultConverter(button -> button);

        dialog.showAndWait().ifPresent(button -> {
            if (button != saveButton) {
                return;
            }

            terminalFontFamily = fontSelector.getValue();
            terminalFontSize = parseFontSize(sizeSpinner);
            terminalTheme = themeSelector.getValue() != null ? themeSelector.getValue() : TerminalTheme.DEFAULT_DARK;
            terminalCursorStyle = cursorStyleSelector.getValue() != null ? cursorStyleSelector.getValue() : CursorStyle.BLOCK;
            terminalCursorBlink = cursorBlinkCheck.isSelected();

            int idleMinutes = parseSpinnerValue(idleTimeoutSpinner, 10, 0, 120);

            PREFERENCES.put("terminal.font.family", terminalFontFamily);
            PREFERENCES.putInt("terminal.font.size", terminalFontSize);
            PREFERENCES.put("terminal.theme", terminalTheme.id());
            PREFERENCES.put("terminal.cursor.style", terminalCursorStyle.name());
            PREFERENCES.putBoolean("terminal.cursor.blink", terminalCursorBlink);
            PREFERENCES.putInt("ssh.idle.timeout.minutes", idleMinutes);

            sshConnectionService.setIdleTimeoutMinutes(idleMinutes);

            for (ProfileWorkspace w : profileWorkspaces.values()) {
                w.applyFont(terminalFontFamily, terminalFontSize);
                w.applyTerminalSettings(terminalTheme, terminalCursorStyle, terminalCursorBlink);
            }
        });
    }

    private static int parseSpinnerValue(Spinner<Integer> spinner, int fallback, int min, int max) {
        try {
            int val = Integer.parseInt(spinner.getEditor().getText().trim());
            return Math.max(min, Math.min(max, val));
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }

    private static int parseFontSize(Spinner<Integer> sizeSpinner) {
        return parseSpinnerValue(sizeSpinner, 14, 10, 32);
    }

    private Node createStatusBar() {
        connectionStatusLabel.getStyleClass().add("status-muted");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox statusBar = new HBox(16, activeConnectionLabel, spacer, statusLabel, connectionStatusLabel);
        statusBar.setAlignment(Pos.CENTER_LEFT);
        statusBar.getStyleClass().add("status-bar");
        return statusBar;
    }

    public void showConnectionDialog(ConnectionProfile existing) {
        Dialog<ConnectionResult> dialog = new Dialog<>();
        dialog.setTitle(existing == null ? "新建 SSH 连接" : "编辑 SSH 连接");
        dialog.setHeaderText(existing == null ? "配置服务器连接参数" : "修改「" + existing.name() + "」的连接参数");
        applyDialogTheme(dialog);

        Label icon = new Label("⚡");
        icon.setStyle("-fx-font-size: 24px; -fx-text-fill: #4c82e8;");
        dialog.setGraphic(icon);

        ButtonType connectButtonType = new ButtonType(existing == null ? "连接" : "保存并连接", ButtonBar.ButtonData.OK_DONE);
        ButtonType saveOnlyButtonType = new ButtonType("仅保存", ButtonBar.ButtonData.OTHER);
        ButtonType cancelButtonType = new ButtonType("取消", ButtonBar.ButtonData.CANCEL_CLOSE);
        dialog.getDialogPane().getButtonTypes().addAll(connectButtonType, saveOnlyButtonType, cancelButtonType);

        Node connectBtn = dialog.getDialogPane().lookupButton(connectButtonType);
        if (connectBtn != null) {
            connectBtn.getStyleClass().add("dialog-primary-button");
        }
        Node saveOnlyBtn = dialog.getDialogPane().lookupButton(saveOnlyButtonType);
        if (saveOnlyBtn != null) {
            saveOnlyBtn.getStyleClass().add("dialog-secondary-button");
        }
        Node cancelBtn = dialog.getDialogPane().lookupButton(cancelButtonType);
        if (cancelBtn != null) {
            cancelBtn.getStyleClass().add("dialog-secondary-button");
        }

        TextField nameField = new TextField(existing != null ? existing.name() : "");
        nameField.setPromptText("例如：生产服务器 / 测试集群");

        TextField hostField = new TextField(existing != null ? existing.host() : "");
        hostField.setPromptText("例如：192.168.1.100 或 server.example.com");

        Spinner<Integer> portSpinner = new Spinner<>();
        portSpinner.setValueFactory(new SpinnerValueFactory.IntegerSpinnerValueFactory(1, 65535, existing != null ? existing.port() : 22));
        portSpinner.setEditable(true);

        TextField usernameField = new TextField(existing != null ? existing.username() : "");
        usernameField.setPromptText("例如：root 或 ubuntu");

        ComboBox<String> authTypeSelector = new ComboBox<>();
        authTypeSelector.getItems().addAll("密码认证 (Password)", "私钥认证 (Private Key)");
        authTypeSelector.setValue((existing != null && existing.isKeyAuth()) ? "私钥认证 (Private Key)" : "密码认证 (Password)");
        authTypeSelector.setMaxWidth(Double.MAX_VALUE);

        PasswordField passwordField = new PasswordField();
        passwordField.setPromptText("输入 SSH 登录密码");
        if (existing != null && existing.rememberPassword() && !existing.isKeyAuth()) {
            passwordField.setText(existing.password());
        }

        TextField keyPathField = new TextField(existing != null ? existing.privateKeyPath() : "");
        keyPathField.setPromptText("私钥路径，例如 ~/.ssh/id_rsa 或 id_ed25519");
        HBox.setHgrow(keyPathField, Priority.ALWAYS);

        Button browseKeyButton = new Button("浏览...");
        browseKeyButton.getStyleClass().add("dialog-secondary-button");
        browseKeyButton.setOnAction(e -> {
            FileChooser chooser = new FileChooser();
            chooser.setTitle("选择 SSH 私钥文件");
            File defaultDir = new File(System.getProperty("user.home"), ".ssh");
            if (defaultDir.exists() && defaultDir.isDirectory()) {
                chooser.setInitialDirectory(defaultDir);
            }
            File keyFile = chooser.showOpenDialog(dialog.getDialogPane().getScene().getWindow());
            if (keyFile != null) {
                keyPathField.setText(keyFile.getAbsolutePath());
            }
        });

        HBox keyBox = new HBox(8, keyPathField, browseKeyButton);
        keyBox.setAlignment(Pos.CENTER_LEFT);

        PasswordField passphraseField = new PasswordField();
        passphraseField.setPromptText("私钥保护口令 (Passphrase，无密码可留空)");
        if (existing != null && existing.rememberPassword() && existing.isKeyAuth()) {
            passphraseField.setText(existing.passphrase());
        }

        CheckBox rememberPasswordCheck = new CheckBox("记住认证密码 / 私钥口令 (保存在本地存储)");
        rememberPasswordCheck.getStyleClass().add("dialog-form-hint");
        rememberPasswordCheck.setSelected(existing != null ? existing.rememberPassword() : true);

        ComboBox<String> shellSelector = new ComboBox<>();
        shellSelector.getItems().addAll("默认 Shell", "zsh (/bin/zsh)", "bash (/bin/bash)", "sh (/bin/sh)");
        if (existing != null && "zsh".equalsIgnoreCase(existing.initialShell())) {
            shellSelector.setValue("zsh (/bin/zsh)");
        } else if (existing != null && "bash".equalsIgnoreCase(existing.initialShell())) {
            shellSelector.setValue("bash (/bin/bash)");
        } else if (existing != null && "sh".equalsIgnoreCase(existing.initialShell())) {
            shellSelector.setValue("sh (/bin/sh)");
        } else {
            shellSelector.setValue("默认 Shell");
        }
        shellSelector.setMaxWidth(Double.MAX_VALUE);

        Label errorBanner = new Label();
        errorBanner.getStyleClass().add("dialog-error-banner");
        errorBanner.setMaxWidth(Double.MAX_VALUE);
        errorBanner.setWrapText(true);
        errorBanner.setVisible(false);
        errorBanner.setManaged(false);

        Runnable clearError = () -> {
            if (errorBanner.isVisible()) {
                errorBanner.setVisible(false);
                errorBanner.setManaged(false);
            }
        };
        nameField.textProperty().addListener((o, old, v) -> clearError.run());
        hostField.textProperty().addListener((o, old, v) -> clearError.run());
        usernameField.textProperty().addListener((o, old, v) -> clearError.run());
        passwordField.textProperty().addListener((o, old, v) -> clearError.run());
        keyPathField.textProperty().addListener((o, old, v) -> clearError.run());

        GridPane form = new GridPane();
        form.setHgap(14);
        form.setVgap(12);
        form.setPadding(new Insets(12, 0, 4, 0));

        ColumnConstraints labelColumn = new ColumnConstraints();
        labelColumn.setMinWidth(90);
        ColumnConstraints inputColumn = new ColumnConstraints();
        inputColumn.setHgrow(Priority.ALWAYS);
        form.getColumnConstraints().addAll(labelColumn, inputColumn);

        int r = 0;
        Label nameLabel = new Label("连接名称");
        nameLabel.getStyleClass().add("dialog-form-label");
        form.add(nameLabel, 0, r);
        form.add(nameField, 1, r++);

        Label hostLabel = new Label("主机地址");
        hostLabel.getStyleClass().add("dialog-form-label");
        form.add(hostLabel, 0, r);
        form.add(hostField, 1, r++);

        Label portLabel = new Label("SSH 端口");
        portLabel.getStyleClass().add("dialog-form-label");
        form.add(portLabel, 0, r);
        form.add(portSpinner, 1, r++);

        Label userLabel = new Label("用户名");
        userLabel.getStyleClass().add("dialog-form-label");
        form.add(userLabel, 0, r);
        form.add(usernameField, 1, r++);

        Label authLabel = new Label("认证方式");
        authLabel.getStyleClass().add("dialog-form-label");
        form.add(authLabel, 0, r);
        form.add(authTypeSelector, 1, r++);

        Label pwdLabel = new Label("认证密码");
        pwdLabel.getStyleClass().add("dialog-form-label");
        form.add(pwdLabel, 0, r);
        form.add(passwordField, 1, r++);

        Label keyLabel = new Label("私钥文件");
        keyLabel.getStyleClass().add("dialog-form-label");
        form.add(keyLabel, 0, r);
        form.add(keyBox, 1, r++);

        Label passLabel = new Label("私钥口令");
        passLabel.getStyleClass().add("dialog-form-label");
        form.add(passLabel, 0, r);
        form.add(passphraseField, 1, r++);

        Label emptyLabel = new Label("");
        form.add(emptyLabel, 0, r);
        form.add(rememberPasswordCheck, 1, r++);

        Label shellLabel = new Label("登录 Shell");
        shellLabel.getStyleClass().add("dialog-form-label");
        form.add(shellLabel, 0, r);
        form.add(shellSelector, 1, r++);

        form.add(errorBanner, 0, r, 2, 1);

        Runnable updateAuthFields = () -> {
            boolean isKey = authTypeSelector.getValue() != null && authTypeSelector.getValue().contains("私钥");
            pwdLabel.setVisible(!isKey);
            pwdLabel.setManaged(!isKey);
            passwordField.setVisible(!isKey);
            passwordField.setManaged(!isKey);

            keyLabel.setVisible(isKey);
            keyLabel.setManaged(isKey);
            keyBox.setVisible(isKey);
            keyBox.setManaged(isKey);
            passLabel.setVisible(isKey);
            passLabel.setManaged(isKey);
            passphraseField.setVisible(isKey);
            passphraseField.setManaged(isKey);
        };
        authTypeSelector.valueProperty().addListener((obs, old, val) -> updateAuthFields.run());
        updateAuthFields.run();

        dialog.getDialogPane().setContent(form);
        dialog.getDialogPane().setMinWidth(520);

        javafx.event.EventHandler<javafx.event.ActionEvent> validateHandler = event -> {
            boolean isKey = authTypeSelector.getValue() != null && authTypeSelector.getValue().contains("私钥");
            if (nameField.getText().isBlank() || hostField.getText().isBlank() || usernameField.getText().isBlank()) {
                errorBanner.setText("⚠️ 连接名称、主机地址和用户名不能为空");
                errorBanner.setVisible(true);
                errorBanner.setManaged(true);
                event.consume();
                return;
            }
            if (isKey) {
                String kPath = keyPathField.getText().trim();
                if (kPath.isBlank()) {
                    errorBanner.setText("⚠️ 私钥认证模式下，必须指定私钥文件路径");
                    errorBanner.setVisible(true);
                    errorBanner.setManaged(true);
                    event.consume();
                    return;
                }
                File kFile = new File(kPath);
                if (!kFile.exists()) {
                    errorBanner.setText("⚠️ 指定的私钥文件不存在：" + kPath);
                    errorBanner.setVisible(true);
                    errorBanner.setManaged(true);
                    event.consume();
                }
            }
        };

        if (connectBtn != null) {
            connectBtn.addEventFilter(javafx.event.ActionEvent.ACTION, validateHandler);
        }
        if (saveOnlyBtn != null) {
            saveOnlyBtn.addEventFilter(javafx.event.ActionEvent.ACTION, validateHandler);
        }

        dialog.setResultConverter(button -> {
            if (button != connectButtonType && button != saveOnlyButtonType) {
                return null;
            }

            boolean isKey = authTypeSelector.getValue() != null && authTypeSelector.getValue().contains("私钥");
            String authType = isKey ? ConnectionProfile.AUTH_KEY : ConnectionProfile.AUTH_PASSWORD;

            String shellVal = shellSelector.getValue();
            String shell = "default";
            if (shellVal != null && shellVal.contains("zsh")) {
                shell = "zsh";
            } else if (shellVal != null && shellVal.contains("bash")) {
                shell = "bash";
            } else if (shellVal != null && shellVal.contains("sh")) {
                shell = "sh";
            }

            boolean remember = rememberPasswordCheck.isSelected();
            String pwdToSave = remember ? passwordField.getText() : "";
            String passToSave = remember ? passphraseField.getText() : "";

            String id = existing != null ? existing.id() : UUID.randomUUID().toString();
            ConnectionProfile profile = new ConnectionProfile(
                    id,
                    nameField.getText().trim(),
                    hostField.getText().trim(),
                    portSpinner.getValue(),
                    usernameField.getText().trim(),
                    authType,
                    pwdToSave,
                    keyPathField.getText().trim(),
                    passToSave,
                    remember,
                    shell
            );

            String liveSecret = isKey ? passphraseField.getText() : passwordField.getText();
            boolean shouldConnect = (button == connectButtonType);
            return new ConnectionResult(profile, liveSecret, shouldConnect);
        });

        dialog.showAndWait().ifPresent(result -> {
            ConnectionProfile profile = result.profile();
            if (existing != null) {
                int index = connectionProfiles.indexOf(existing);
                if (index >= 0) {
                    connectionProfiles.set(index, profile);
                } else {
                    connectionProfiles.add(profile);
                }
            } else {
                connectionProfiles.add(profile);
            }

            ConfigStorageService.saveConnections(connectionProfiles);
            connectionList.getSelectionModel().select(profile);
            welcomeOverlay.setVisible(false);

            if (result.shouldConnect()) {
                connectProfile(profile, result.liveSecret());
            }
        });
    }

    private void promptConnectProfile(ConnectionProfile profile) {
        if (profile == null) {
            return;
        }

        if (profile.isKeyAuth()) {
            connectProfile(profile, profile.passphrase());
        } else if (profile.rememberPassword() && profile.password() != null && !profile.password().isBlank()) {
            connectProfile(profile, profile.password());
        } else {
            Dialog<String> pwdDialog = new Dialog<>();
            pwdDialog.setTitle("SSH 认证");
            pwdDialog.setHeaderText("连接至 " + profile.name() + " (" + profile.host() + ")");
            applyDialogTheme(pwdDialog);

            PasswordField pwdInput = new PasswordField();
            pwdInput.setPromptText("请输入 SSH 密码");
            pwdInput.setPrefWidth(260);

            VBox box = new VBox(10, new Label("用户：" + profile.username() + "@" + profile.host()), pwdInput);
            box.setPadding(new Insets(10, 0, 4, 0));
            pwdDialog.getDialogPane().setContent(box);

            ButtonType okBtn = new ButtonType("连接", ButtonBar.ButtonData.OK_DONE);
            ButtonType cancelBtn = new ButtonType("取消", ButtonBar.ButtonData.CANCEL_CLOSE);
            pwdDialog.getDialogPane().getButtonTypes().addAll(okBtn, cancelBtn);

            Node okNode = pwdDialog.getDialogPane().lookupButton(okBtn);
            if (okNode != null) {
                okNode.getStyleClass().add("dialog-primary-button");
            }
            Node cancelNode = pwdDialog.getDialogPane().lookupButton(cancelBtn);
            if (cancelNode != null) {
                cancelNode.getStyleClass().add("dialog-secondary-button");
            }

            pwdInput.setOnAction(e -> {
                if (okNode instanceof Button b) {
                    b.fire();
                }
            });

            pwdDialog.setResultConverter(btn -> btn == okBtn ? pwdInput.getText() : null);
            pwdDialog.showAndWait().ifPresent(pwd -> {
                if (pwd != null && !pwd.isBlank()) {
                    connectProfile(profile, pwd);
                }
            });
        }
    }

    private void duplicateProfile(ConnectionProfile profile) {
        if (profile == null) {
            return;
        }
        ConnectionProfile copy = new ConnectionProfile(
                UUID.randomUUID().toString(),
                profile.name() + " (副本)",
                profile.host(),
                profile.port(),
                profile.username(),
                profile.authType(),
                profile.password(),
                profile.privateKeyPath(),
                profile.passphrase(),
                profile.rememberPassword(),
                profile.initialShell()
        );
        connectionProfiles.add(copy);
        ConfigStorageService.saveConnections(connectionProfiles);
        connectionList.getSelectionModel().select(copy);
        welcomeOverlay.setVisible(false);
    }

    private void deleteProfile(ConnectionProfile profile) {
        if (profile == null) {
            return;
        }
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle("删除连接");
        confirm.setHeaderText("确认删除连接配置？");
        confirm.setContentText("确定要删除「" + profile.name() + "」(" + profile.host() + ") 吗？此操作无法撤销。");
        applyDialogTheme(confirm);

        confirm.showAndWait().ifPresent(btn -> {
            if (btn == ButtonType.OK) {
                disconnectProfile(profile);
                ProfileWorkspace ws = profileWorkspaces.remove(profile.id());
                if (ws != null) {
                    ws.close();
                }
                boolean wasSelected = (selectedProfile == profile);
                connectionProfiles.remove(profile);
                ConfigStorageService.saveConnections(connectionProfiles);
                welcomeOverlay.setVisible(connectionProfiles.isEmpty());

                if (wasSelected) {
                    if (!connectionProfiles.isEmpty()) {
                        connectionList.getSelectionModel().select(0);
                    } else {
                        selectedProfile = null;
                        showWorkspaceFor(null);
                        updateSelectionState();
                    }
                } else if (sidebarCollapsed) {
                    refreshSlimSidebar();
                }
            }
        });
    }

    private void exportProfilesBackup(javafx.stage.Window owner) {
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("导出连接配置备份");
        fileChooser.setInitialFileName("remote-workbench-connections.json");
        fileChooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("JSON 文件 (*.json)", "*.json"));
        File file = fileChooser.showSaveDialog(owner);
        if (file != null) {
            try {
                ConfigStorageService.exportConnections(file.toPath(), connectionProfiles);
                Alert alert = new Alert(Alert.AlertType.INFORMATION);
                alert.setTitle("导出成功");
                alert.setHeaderText("配置备份已导出");
                alert.setContentText("已成功将 " + connectionProfiles.size() + " 个连接配置导出至：\n" + file.getAbsolutePath());
                applyDialogTheme(alert);
                alert.showAndWait();
            } catch (Exception ex) {
                Alert alert = new Alert(Alert.AlertType.ERROR);
                alert.setTitle("导出失败");
                alert.setHeaderText("导出配置备份时发生错误");
                alert.setContentText(ex.getMessage());
                applyDialogTheme(alert);
                alert.showAndWait();
            }
        }
    }

    private void importProfilesBackup(javafx.stage.Window owner) {
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("选择要导入的连接配置备份");
        fileChooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("JSON 文件 (*.json)", "*.json"));
        File file = fileChooser.showOpenDialog(owner);
        if (file != null) {
            try {
                List<ConnectionProfile> imported = ConfigStorageService.importConnections(file.toPath());
                if (imported == null || imported.isEmpty()) {
                    Alert alert = new Alert(Alert.AlertType.WARNING);
                    alert.setTitle("导入提示");
                    alert.setHeaderText("文件中未找到有效的连接配置");
                    applyDialogTheme(alert);
                    alert.showAndWait();
                    return;
                }

                int addedCount = 0;
                for (ConnectionProfile item : imported) {
                    boolean exists = connectionProfiles.stream().anyMatch(p -> p.id().equals(item.id()));
                    if (!exists) {
                        connectionProfiles.add(item);
                        addedCount++;
                    }
                }
                ConfigStorageService.saveConnections(connectionProfiles);
                welcomeOverlay.setVisible(connectionProfiles.isEmpty());
                if (sidebarCollapsed && addedCount > 0) {
                    refreshSlimSidebar();
                }

                Alert alert = new Alert(Alert.AlertType.INFORMATION);
                alert.setTitle("导入成功");
                alert.setHeaderText("配置备份导入完成");
                alert.setContentText("成功从文件导入 " + addedCount + " 个新增连接配置（已存在配置已自动跳过）。");
                applyDialogTheme(alert);
                alert.showAndWait();
            } catch (Exception ex) {
                Alert alert = new Alert(Alert.AlertType.ERROR);
                alert.setTitle("导入失败");
                alert.setHeaderText("导入配置备份时发生错误");
                alert.setContentText(ex.getMessage());
                applyDialogTheme(alert);
                alert.showAndWait();
            }
        }
    }

    private record ConnectionResult(ConnectionProfile profile, String liveSecret, boolean shouldConnect) {}

    private void connectProfile(ConnectionProfile profile, String password) {
        if (profile == null) {
            return;
        }

        selectedProfile = profile;
        ProfileWorkspace workspace = getOrCreateWorkspace(profile);
        showWorkspaceFor(profile);

        connectingProfileIds.add(profile.id());
        connectionList.refresh();
        updateSelectionState();

        sshConnectionService.connect(profile, password, this::confirmServerKey).whenComplete((ignored, error) ->
                Platform.runLater(() -> {
                    connectingProfileIds.remove(profile.id());
                    connectionList.refresh();
                    updateSelectionState();

                    if (error != null) {
                        showConnectionFailure(profile, workspace, error);
                        return;
                    }

                    workspace.attach(sshConnectionService, sftpService);
                })
        );
    }

    private void showConnectionFailure(ConnectionProfile profile, ProfileWorkspace workspace, Throwable error) {
        Throwable cause = unwrap(error);
        String detail = cause.getMessage();
        workspace.getTerminalSessionPane().clear();
        workspace.getTerminalSessionPane().appendOutput("SSH 连接失败 [" + profile.name() + "]\n\n");
        workspace.getTerminalSessionPane().appendOutput("原因：" + (detail == null || detail.isBlank() ? "未返回具体错误信息。" : detail) + "\n\n");
        workspace.getTerminalSessionPane().appendOutput("提示：首次连接请确认服务器指纹；如果是认证失败，请检查用户名和密码。\n");
        workspace.getTerminalSessionPane().setInputEnabled(false);
        updateSelectionState();
    }

    private boolean confirmServerKey(String host, int port, String fingerprint) {
        CompletableFuture<Boolean> decision = new CompletableFuture<>();

        Platform.runLater(() -> {
            Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
            applyDialogTheme(alert);

            ButtonType trustButton = new ButtonType("信任并继续", ButtonBar.ButtonData.OK_DONE);
            ButtonType cancelButton = new ButtonType("取消", ButtonBar.ButtonData.CANCEL_CLOSE);

            alert.setTitle("确认服务器指纹");
            alert.setHeaderText("首次连接此服务器");
            alert.setContentText(
                    "主机：" + host + ":" + port + "\n"
                            + "指纹：" + fingerprint + "\n\n"
                            + "请确认该指纹来自可信服务器。确认后将记录至已知主机列表，后续连接将自动信任。"
            );
            alert.getButtonTypes().setAll(trustButton, cancelButton);

            Node trustBtn = alert.getDialogPane().lookupButton(trustButton);
            if (trustBtn != null) {
                trustBtn.getStyleClass().add("dialog-primary-button");
            }
            Node cancelBtn = alert.getDialogPane().lookupButton(cancelButton);
            if (cancelBtn != null) {
                cancelBtn.getStyleClass().add("dialog-secondary-button");
            }

            alert.showAndWait().ifPresentOrElse(
                    selectedButton -> decision.complete(selectedButton == trustButton),
                    () -> decision.complete(false)
            );
        });

        try {
            return decision.get(60, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return false;
        } catch (ExecutionException | TimeoutException exception) {
            return false;
        }
    }

    private void disconnectSelectedConnection() {
        if (selectedProfile != null) {
            disconnectProfile(selectedProfile);
        }
    }

    private void disconnectProfile(ConnectionProfile profile) {
        if (profile == null) {
            return;
        }

        sshConnectionService.disconnect(profile);
        ProfileWorkspace workspace = profileWorkspaces.get(profile.id());
        if (workspace != null) {
            workspace.detach();
        }
        connectingProfileIds.remove(profile.id());
        connectionList.refresh();
        updateSelectionState();
    }

    private void handleIdleTimeoutDisconnect(String profileId) {
        if (profileId == null) {
            return;
        }
        int timeoutMinutes = PREFERENCES.getInt("ssh.idle.timeout.minutes", 10);
        ProfileWorkspace workspace = profileWorkspaces.get(profileId);
        if (workspace != null) {
            workspace.detachForIdleTimeout(timeoutMinutes);
        }
        connectingProfileIds.remove(profileId);
        connectionList.refresh();
        updateSelectionState();
        if (selectedProfile != null && profileId.equals(selectedProfile.id())) {
            statusLabel.setText("连接「" + selectedProfile.name() + "」因本地闲置超过 " + timeoutMinutes + " 分钟已主动断开");
        }
    }

    private void updateSelectionState() {
        if (selectedProfile == null) {
            activeConnectionLabel.setText("未连接");
            connectionStatusLabel.setText("SSH：未连接");
            statusLabel.setText("就绪");
            connectButton.setVisible(true);
            connectButton.setManaged(true);
            connectButton.setDisable(true);
            disconnectButton.setVisible(false);
            disconnectButton.setManaged(false);
            return;
        }

        boolean connected = sshConnectionService.isConnected(selectedProfile);
        boolean connecting = connectingProfileIds.contains(selectedProfile.id());

        activeConnectionLabel.setText(selectedProfile.username() + "@" + selectedProfile.host() + ":" + selectedProfile.port());

        if (connected) {
            connectionStatusLabel.setText("SSH：已连接 🟢");
            statusLabel.setText("已连接至「" + selectedProfile.name() + "」");
            connectButton.setVisible(false);
            connectButton.setManaged(false);
            disconnectButton.setVisible(true);
            disconnectButton.setManaged(true);
            disconnectButton.setDisable(false);
        } else if (connecting) {
            connectionStatusLabel.setText("SSH：连接中 🟡");
            statusLabel.setText("正在连接「" + selectedProfile.name() + "」...");
            connectButton.setVisible(true);
            connectButton.setManaged(true);
            connectButton.setDisable(true);
            disconnectButton.setVisible(false);
            disconnectButton.setManaged(false);
        } else {
            connectionStatusLabel.setText("SSH：未连接");
            statusLabel.setText("已选择「" + selectedProfile.name() + "」");
            connectButton.setVisible(true);
            connectButton.setManaged(true);
            connectButton.setDisable(false);
            disconnectButton.setVisible(false);
            disconnectButton.setManaged(false);
        }

        if (sidebarCollapsed) {
            refreshSlimSidebar();
        }
    }

    private static Throwable unwrap(Throwable error) {
        Throwable current = error;
        while ((current instanceof CompletionException || current instanceof ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    @Override
    public void close() {
        profileWorkspaces.values().forEach(ProfileWorkspace::close);
        profileWorkspaces.clear();
        sshConnectionService.close();
    }
}
