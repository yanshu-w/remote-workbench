package com.yanshuwang.remoteworkbench.ui;

import com.yanshuwang.remoteworkbench.connection.ConnectionProfile;
import com.yanshuwang.remoteworkbench.ssh.SshConnectionService;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.PasswordField;
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.input.Clipboard;
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
import javafx.scene.control.SplitPane;
import javafx.geometry.Orientation;
import javafx.scene.text.Font;

import java.util.ArrayList;
import java.util.List;
import java.util.prefs.Preferences;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;

public final class MainView extends BorderPane implements AutoCloseable {
    private static final Pattern ANSI_ESCAPE = Pattern.compile("\\u001B(?:\\[[0-?]*[ -/]*[@-~]|\\][^\\u0007]*(?:\\u0007|\\u001B\\\\))");
    private static final List<String> COMMON_TERMINAL_FONTS = List.of(
            "PingFang SC",
            "PingFang TC",
            "SF Mono",
            "Menlo",
            "Monaco"
    );
    private static final Preferences PREFERENCES = Preferences.userNodeForPackage(MainView.class);

    private final ObservableList<ConnectionProfile> connectionProfiles = FXCollections.observableArrayList();
    private final ListView<ConnectionProfile> connectionList = new ListView<>(connectionProfiles);
    private final SshConnectionService sshConnectionService = new SshConnectionService();
    private final Label activeConnectionLabel = new Label("未连接");
    private final Label statusLabel = new Label("就绪");
    private final Label connectionStatusLabel = new Label("SSH：未连接");
    private final Button disconnectButton = new Button("断开连接");
    private final TextArea terminalOutput = new TextArea();

    private String terminalFontFamily = PREFERENCES.get("terminal.font.family", "PingFang SC");
    private int terminalFontSize = Math.max(10, Math.min(32, PREFERENCES.getInt("terminal.font.size", 14)));
    private ConnectionProfile selectedProfile;
    private boolean terminalInputEnabled;

    public MainView() {
        getStyleClass().add("app-root");
        setTop(createTopBar());
        setLeft(createConnectionSidebar());
        setCenter(createWorkspace());
        setBottom(createStatusBar());

        connectionList.getSelectionModel().selectedItemProperty().addListener((observable, previous, selected) -> {
            selectedProfile = selected;
            updateSelectionState();
        });
    }

    private Node createTopBar() {
        Label brand = new Label("远程工作台");
        brand.getStyleClass().add("brand-label");

        Label subtitle = new Label("SSH / SFTP 工具");
        subtitle.getStyleClass().add("subtitle-label");

        HBox brandBox = new HBox(10, brand, subtitle);
        brandBox.setAlignment(Pos.CENTER_LEFT);

        Button newConnectionButton = new Button("新建连接");
        newConnectionButton.getStyleClass().add("primary-button");
        newConnectionButton.setOnAction(event -> showNewConnectionDialog());

        disconnectButton.getStyleClass().add("secondary-button");
        disconnectButton.setDisable(true);
        disconnectButton.setOnAction(event -> disconnectSelectedConnection());

        Button settingsButton = new Button("设置");
        settingsButton.getStyleClass().add("secondary-button");
        settingsButton.setTooltip(new Tooltip("打开应用设置"));
        settingsButton.setOnAction(event -> showSettingsDialog());

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox topBar = new HBox(14, brandBox, spacer, newConnectionButton, disconnectButton, settingsButton);
        topBar.setAlignment(Pos.CENTER_LEFT);
        topBar.getStyleClass().add("top-bar");
        return topBar;
    }

    private Node createConnectionSidebar() {
        Label title = new Label("连接");
        title.getStyleClass().add("section-label");

        Button addButton = new Button("+  添加连接");
        addButton.getStyleClass().add("sidebar-action");
        addButton.setMaxWidth(Double.MAX_VALUE);
        addButton.setOnAction(event -> showNewConnectionDialog());

        connectionList.getStyleClass().add("connection-list");
        connectionList.setPlaceholder(new Label("暂无连接"));
        connectionList.setCellFactory(list -> new ListCell<>() {
            @Override
            protected void updateItem(ConnectionProfile profile, boolean empty) {
                super.updateItem(profile, empty);

                if (empty || profile == null) {
                    setText(null);
                    setGraphic(null);
                    return;
                }

                Label name = new Label(profile.name());
                name.getStyleClass().add("connection-name");
                Label address = new Label(profile.username() + "@" + profile.host() + ":" + profile.port());
                address.getStyleClass().add("connection-address");

                VBox content = new VBox(3, name, address);
                setText(null);
                setGraphic(content);
            }
        });

        VBox sidebar = new VBox(14, title, addButton, connectionList);
        sidebar.setPadding(new Insets(20, 14, 16, 14));
        sidebar.setPrefWidth(260);
        VBox.setVgrow(connectionList, Priority.ALWAYS);
        sidebar.getStyleClass().add("sidebar");
        return sidebar;
    }

    private Node createWorkspace() {
        Label title = new Label("远程工作区");
        title.getStyleClass().add("workspace-title");

        Label description = new Label("选择一个连接以浏览远程文件、打开终端并管理传输任务。");
        description.setWrapText(true);
        description.getStyleClass().add("workspace-description");

        Button createButton = new Button("创建第一个连接");
        createButton.getStyleClass().add("primary-button");
        createButton.setOnAction(event -> showNewConnectionDialog());

        VBox welcome = new VBox(14, title, description, createButton);
        welcome.setAlignment(Pos.CENTER);
        welcome.setMaxWidth(480);
        welcome.getStyleClass().add("welcome-card");

        StackPane overview = new StackPane(welcome);
        overview.getStyleClass().add("workspace-overview");

        terminalOutput.setEditable(false);
        terminalOutput.setWrapText(false);
        terminalOutput.setText("尚未建立 SSH 连接。\n连接成功后，远程 Shell 会在这里显示提示符。\n");
        terminalOutput.getStyleClass().add("terminal-output");
        applyTerminalFont();
        terminalOutput.addEventFilter(KeyEvent.KEY_PRESSED, this::handleTerminalKeyPressed);
        terminalOutput.addEventFilter(KeyEvent.KEY_TYPED, this::handleTerminalKeyTyped);
        terminalOutput.setOnMousePressed(event -> Platform.runLater(this::moveTerminalCaretToEnd));

        VBox terminalContent = new VBox(terminalOutput);
        terminalContent.setPadding(new Insets(10));
        terminalContent.getStyleClass().add("terminal-panel");
        VBox.setVgrow(terminalOutput, Priority.ALWAYS);

        terminalContent.setMinHeight(180);
        terminalContent.setMinWidth(360);

        SplitPane workspace = new SplitPane(terminalContent, overview);
        workspace.setOrientation(Orientation.HORIZONTAL);
        workspace.setDividerPositions(0.35);
        workspace.getStyleClass().addAll("workspace-split", "workspace-split-horizontal");
        return workspace;
    }

    public void showSettingsDialog() {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("设置");
        dialog.setHeaderText("终端外观");

        ButtonType saveButton = new ButtonType("保存", ButtonBar.ButtonData.OK_DONE);
        ButtonType cancelButton = new ButtonType("取消", ButtonBar.ButtonData.CANCEL_CLOSE);
        dialog.getDialogPane().getButtonTypes().addAll(saveButton, cancelButton);

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

        GridPane form = new GridPane();
        form.setHgap(12);
        form.setVgap(12);
        form.setPadding(new Insets(10, 0, 4, 0));

        ColumnConstraints labelColumn = new ColumnConstraints();
        labelColumn.setMinWidth(90);
        ColumnConstraints inputColumn = new ColumnConstraints();
        inputColumn.setHgrow(Priority.ALWAYS);
        form.getColumnConstraints().addAll(labelColumn, inputColumn);
        form.add(new Label("终端字体"), 0, 0);
        form.add(fontSelector, 1, 0);
        form.add(new Label("字号"), 0, 1);
        form.add(sizeSpinner, 1, 1);

        dialog.getDialogPane().setContent(form);
        dialog.getDialogPane().setMinWidth(420);
        dialog.setResultConverter(button -> button);

        dialog.showAndWait().ifPresent(button -> {
            if (button != saveButton) {
                return;
            }

            terminalFontFamily = fontSelector.getValue();
            terminalFontSize = parseFontSize(sizeSpinner);
            PREFERENCES.put("terminal.font.family", terminalFontFamily);
            PREFERENCES.putInt("terminal.font.size", terminalFontSize);
            applyTerminalFont();
        });
    }

    private void applyTerminalFont() {
        String safeFontFamily = terminalFontFamily.replace("\"", "");
        terminalOutput.setStyle(
                "-fx-font-family: \"" + safeFontFamily + "\"; -fx-font-size: " + terminalFontSize + "px;"
        );
    }

    private static int parseFontSize(Spinner<Integer> sizeSpinner) {
        try {
            int size = Integer.parseInt(sizeSpinner.getEditor().getText().trim());
            return Math.max(10, Math.min(32, size));
        } catch (NumberFormatException exception) {
            return 14;
        }
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

    private void showNewConnectionDialog() {
        Dialog<ConnectionRequest> dialog = new Dialog<>();
        dialog.setTitle("新建 SSH 连接");
        dialog.setHeaderText("添加服务器连接配置");

        ButtonType connectButtonType = new ButtonType("连接", ButtonBar.ButtonData.OK_DONE);
        ButtonType cancelButtonType = new ButtonType("取消", ButtonBar.ButtonData.CANCEL_CLOSE);
        dialog.getDialogPane().getButtonTypes().addAll(connectButtonType, cancelButtonType);

        TextField nameField = new TextField();
        nameField.setPromptText("例如：生产服务器");

        TextField hostField = new TextField();
        hostField.setPromptText("例如：example.com");

        Spinner<Integer> portSpinner = new Spinner<>();
        portSpinner.setValueFactory(new SpinnerValueFactory.IntegerSpinnerValueFactory(1, 65535, 22));
        portSpinner.setEditable(true);

        TextField usernameField = new TextField();
        usernameField.setPromptText("例如：root");

        PasswordField passwordField = new PasswordField();
        passwordField.setPromptText("输入 SSH 密码，不会保存");

        GridPane form = new GridPane();
        form.setHgap(12);
        form.setVgap(12);
        form.setPadding(new Insets(10, 0, 4, 0));

        ColumnConstraints labelColumn = new ColumnConstraints();
        labelColumn.setMinWidth(90);
        ColumnConstraints inputColumn = new ColumnConstraints();
        inputColumn.setHgrow(Priority.ALWAYS);
        form.getColumnConstraints().addAll(labelColumn, inputColumn);

        form.add(new Label("名称"), 0, 0);
        form.add(nameField, 1, 0);
        form.add(new Label("主机"), 0, 1);
        form.add(hostField, 1, 1);
        form.add(new Label("端口"), 0, 2);
        form.add(portSpinner, 1, 2);
        form.add(new Label("用户名"), 0, 3);
        form.add(usernameField, 1, 3);
        form.add(new Label("密码"), 0, 4);
        form.add(passwordField, 1, 4);

        dialog.getDialogPane().setContent(form);
        dialog.getDialogPane().setMinWidth(460);

        Node connectButton = dialog.getDialogPane().lookupButton(connectButtonType);
        connectButton.addEventFilter(javafx.event.ActionEvent.ACTION, event -> {
            if (nameField.getText().isBlank()
                    || hostField.getText().isBlank()
                    || usernameField.getText().isBlank()
                    || passwordField.getText().isBlank()) {
                statusLabel.setText("名称、主机、用户名和密码不能为空");
                event.consume();
            }
        });

        dialog.setResultConverter(button -> {
            if (button != connectButtonType) {
                return null;
            }

            ConnectionProfile profile = new ConnectionProfile(
                    nameField.getText(),
                    hostField.getText(),
                    portSpinner.getValue(),
                    usernameField.getText()
            );
            return new ConnectionRequest(profile, passwordField.getText());
        });

        dialog.showAndWait().ifPresent(request -> {
            connectionProfiles.add(request.profile());
            connectionList.getSelectionModel().select(request.profile());
            connectProfile(request.profile(), request.password());
        });
    }

    private void connectProfile(ConnectionProfile profile, String password) {
        selectedProfile = profile;
        terminalInputEnabled = false;
        terminalOutput.clear();
        updateSelectionState();
        connectionStatusLabel.setText("SSH：连接中");
        statusLabel.setText("正在连接...");

        sshConnectionService.connect(profile, password, this::confirmServerKey).whenComplete((ignored, error) ->
                Platform.runLater(() -> {
                    if (error != null) {
                        showConnectionFailure(error);
                        return;
                    }

                    statusLabel.setText("正在打开远程终端...");
                    sshConnectionService.openTerminal(profile, this::receiveTerminalOutput).whenComplete((unused, terminalError) ->
                            Platform.runLater(() -> {
                                if (terminalError != null) {
                                    showConnectionFailure(terminalError);
                                    return;
                                }

                                connectionStatusLabel.setText("SSH：已连接");
                                statusLabel.setText("SSH 连接成功");
                                setTerminalInputEnabled(true);
                                terminalOutput.requestFocus();
                                moveTerminalCaretToEnd();
                            })
                    );
                })
        );
    }

    private void showConnectionFailure(Throwable error) {
        connectionStatusLabel.setText("SSH：未连接");
        statusLabel.setText("SSH 连接失败");
        disconnectButton.setDisable(true);
        setTerminalInputEnabled(false);

        Throwable cause = unwrap(error);
        String detail = cause.getMessage();
        terminalOutput.clear();
        appendTerminalOutput("SSH 连接失败\n\n");
        appendTerminalOutput("原因：" + (detail == null || detail.isBlank() ? "未返回具体错误信息。" : detail) + "\n\n");
        appendTerminalOutput("提示：首次连接请确认服务器指纹；如果是认证失败，请检查用户名和密码。\n");
    }

    private boolean confirmServerKey(String host, int port, String fingerprint) {
        CompletableFuture<Boolean> decision = new CompletableFuture<>();

        Platform.runLater(() -> {
            Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
            ButtonType trustButton = new ButtonType("信任并继续", ButtonBar.ButtonData.OK_DONE);
            ButtonType cancelButton = new ButtonType("取消", ButtonBar.ButtonData.CANCEL_CLOSE);

            alert.setTitle("确认服务器指纹");
            alert.setHeaderText("首次连接此服务器");
            alert.setContentText(
                    "主机：" + host + ":" + port + "\n"
                            + "指纹：" + fingerprint + "\n\n"
                            + "请确认该指纹来自可信服务器。确认后会写入 ~/.ssh/known_hosts。"
            );
            alert.getButtonTypes().setAll(trustButton, cancelButton);
            if (getScene() != null && getScene().getWindow() != null) {
                alert.initOwner(getScene().getWindow());
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

    private void handleTerminalKeyPressed(KeyEvent event) {
        if (!terminalInputEnabled) {
            event.consume();
            return;
        }

        if (event.isShortcutDown() && event.getCode() == KeyCode.C) {
            return;
        }

        if (event.isShortcutDown() && event.getCode() == KeyCode.V) {
            String pastedText = Clipboard.getSystemClipboard().getString();
            if (pastedText != null && !pastedText.isEmpty()) {
                sendTerminalInput(pastedText);
            }
            event.consume();
            return;
        }

        String sequence = switch (event.getCode()) {
            case ENTER -> "\r";
            case BACK_SPACE -> "\u007F";
            case TAB -> "\t";
            case UP -> "\u001B[A";
            case DOWN -> "\u001B[B";
            case RIGHT -> "\u001B[C";
            case LEFT -> "\u001B[D";
            case HOME -> "\u001B[H";
            case END -> "\u001B[F";
            case DELETE -> "\u001B[3~";
            case PAGE_UP -> "\u001B[5~";
            case PAGE_DOWN -> "\u001B[6~";
            default -> controlSequence(event);
        };

        if (sequence != null) {
            sendTerminalInput(sequence);
            event.consume();
        }
    }

    private void handleTerminalKeyTyped(KeyEvent event) {
        if (!terminalInputEnabled) {
            event.consume();
            return;
        }

        String text = event.getCharacter();
        if (text != null
                && !text.isEmpty()
                && text.charAt(0) >= 0x20
                && !event.isControlDown()
                && !event.isAltDown()
                && !event.isMetaDown()) {
            sendTerminalInput(text);
        }
        event.consume();
    }

    private String controlSequence(KeyEvent event) {
        if (!event.isControlDown() || event.isShortcutDown()) {
            return null;
        }

        return switch (event.getCode()) {
            case A -> "\u0001";
            case C -> "\u0003";
            case D -> "\u0004";
            case E -> "\u0005";
            case K -> "\u000B";
            case L -> "\u000C";
            case U -> "\u0015";
            case Z -> "\u001A";
            default -> null;
        };
    }

    private void sendTerminalInput(String input) {
        if (selectedProfile == null || !sshConnectionService.isTerminalOpen(selectedProfile)) {
            return;
        }

        try {
            sshConnectionService.sendTerminalInput(selectedProfile, input);
        } catch (CompletionException exception) {
            setTerminalInputEnabled(false);
            statusLabel.setText("终端连接已断开");
        }
    }

    private void receiveTerminalOutput(String text) {
        Platform.runLater(() -> appendTerminalOutput(text));
    }

    private void disconnectSelectedConnection() {
        if (selectedProfile == null) {
            return;
        }

        sshConnectionService.disconnect(selectedProfile);
        connectionStatusLabel.setText("SSH：未连接");
        statusLabel.setText("连接已断开");
        setTerminalInputEnabled(false);
        appendTerminalOutput("\n\n连接已断开。\n");
    }

    private void updateSelectionState() {
        if (selectedProfile == null) {
            activeConnectionLabel.setText("未连接");
            connectionStatusLabel.setText("SSH：未连接");
            statusLabel.setText("就绪");
            setTerminalInputEnabled(false);
            disconnectButton.setDisable(true);
            return;
        }

        activeConnectionLabel.setText(selectedProfile.username() + "@" + selectedProfile.host());
        boolean connected = sshConnectionService.isConnected(selectedProfile);
        boolean terminalOpen = sshConnectionService.isTerminalOpen(selectedProfile);
        connectionStatusLabel.setText(connected ? "SSH：已连接" : "SSH：未连接");
        statusLabel.setText("已选择连接配置");
        setTerminalInputEnabled(terminalOpen);
        disconnectButton.setDisable(!connected);
    }

    private void setTerminalInputEnabled(boolean enabled) {
        terminalInputEnabled = enabled;
        terminalOutput.setEditable(enabled);
        if (enabled) {
            terminalOutput.requestFocus();
            moveTerminalCaretToEnd();
        }
    }

    private void appendTerminalOutput(String text) {
        String cleanText = cleanTerminalText(text);
        terminalOutput.appendText(cleanText);
        moveTerminalCaretToEnd();
    }

    private void moveTerminalCaretToEnd() {
        terminalOutput.positionCaret(terminalOutput.getLength());
    }

    private static String cleanTerminalText(String text) {
        return ANSI_ESCAPE.matcher(text.replace("\r\n", "\n").replace('\r', '\n')).replaceAll("");
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
        sshConnectionService.close();
    }

    private record ConnectionRequest(ConnectionProfile profile, String password) {
    }
}
