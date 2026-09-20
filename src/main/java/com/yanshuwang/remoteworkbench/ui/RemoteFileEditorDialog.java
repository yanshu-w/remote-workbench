package com.yanshuwang.remoteworkbench.ui;

import com.yanshuwang.remoteworkbench.connection.ConnectionProfile;
import com.yanshuwang.remoteworkbench.sftp.RemoteFileItem;
import com.yanshuwang.remoteworkbench.sftp.SftpService;
import com.yanshuwang.remoteworkbench.ui.theme.ThemeManager;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.TextArea;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

public final class RemoteFileEditorDialog extends Dialog<Void> {
    private final ConnectionProfile profile;
    private final RemoteFileItem item;
    private final SftpService sftpService;

    private final TextArea editorArea = new TextArea();
    private final Label statusLabel = new Label("正在加载...");
    private final Button saveButton = new Button("保存 (Cmd+S)");
    private final StackPane loadingOverlay = new StackPane();

    private boolean isModified = false;
    private boolean isSaving = false;

    public RemoteFileEditorDialog(Window owner, ConnectionProfile profile, RemoteFileItem item, SftpService sftpService) {
        this.profile = profile;
        this.item = item;
        this.sftpService = sftpService;

        setTitle("在线文本编辑 - " + item.name());
        ThemeManager.applyDialogTheme(this, owner);
        getDialogPane().getStyleClass().add("remote-editor-root");

        ButtonType closeButtonType = new ButtonType("关闭", ButtonBar.ButtonData.CANCEL_CLOSE);
        getDialogPane().getButtonTypes().add(closeButtonType);
        // Hide default dialog button bar since we have a dedicated editor top bar
        Node defaultCloseBtn = getDialogPane().lookupButton(closeButtonType);
        if (defaultCloseBtn != null) {
            defaultCloseBtn.setVisible(false);
            defaultCloseBtn.setManaged(false);
        }

        BorderPane root = new BorderPane();
        root.setPrefSize(840, 580);
        root.setTop(createHeaderBar(closeButtonType));
        root.setCenter(createEditorArea());

        getDialogPane().setContent(root);

        // Window close confirmation if modified
        setOnCloseRequest(event -> {
            if (isModified) {
                Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
                confirm.setTitle("未保存修改");
                confirm.setHeaderText("文件尚未保存");
                confirm.setContentText("文件 " + item.name() + " 已经修改，确认放弃修改并退出吗？");
                ThemeManager.applyDialogTheme(confirm, getOwner());
                var res = confirm.showAndWait();
                if (res.isEmpty() || res.get() != ButtonType.OK) {
                    event.consume();
                }
            }
        });

        loadRemoteFileContent();
    }

    private HBox createHeaderBar(ButtonType closeButtonType) {
        Label fileIcon = new Label("📄");
        fileIcon.setStyle("-fx-font-size: 16px;");

        Label fileNameLabel = new Label(item.name());
        fileNameLabel.setStyle("-fx-font-weight: bold; -fx-text-fill: #e8e9ed; -fx-font-size: 13px;");

        Label filePathLabel = new Label(item.path() + " (" + item.formattedSize() + ")");
        filePathLabel.setStyle("-fx-text-fill: #7d828e; -fx-font-size: 11px;");

        VBox titleBox = new VBox(2, fileNameLabel, filePathLabel);

        statusLabel.getStyleClass().add("remote-editor-status");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        saveButton.getStyleClass().add("dialog-primary-button");
        saveButton.setOnAction(e -> saveFile());

        Button closeBtn = new Button("关闭");
        closeBtn.getStyleClass().add("dialog-secondary-button");
        closeBtn.setOnAction(e -> {
            Node btn = getDialogPane().lookupButton(closeButtonType);
            if (btn instanceof Button b) {
                b.fire();
            } else {
                close();
            }
        });

        HBox header = new HBox(10, fileIcon, titleBox, statusLabel, spacer, saveButton, closeBtn);
        header.setAlignment(Pos.CENTER_LEFT);
        header.getStyleClass().add("remote-editor-header");
        return header;
    }

    private StackPane createEditorArea() {
        editorArea.getStyleClass().add("remote-editor-textarea");
        editorArea.setWrapText(false);

        // Track user edits
        editorArea.textProperty().addListener((obs, oldText, newText) -> {
            if (!isModified && oldText != null) {
                isModified = true;
                updateStatusBadge();
            }
        });

        // Cmd+S (Mac) or Ctrl+S (Win/Linux)
        editorArea.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            boolean isSaveKey = (event.isShortcutDown() || (event.isMetaDown() && !event.isControlDown()))
                    && event.getCode() == KeyCode.S;
            if (isSaveKey) {
                saveFile();
                event.consume();
            }
        });

        ProgressIndicator spinner = new ProgressIndicator();
        spinner.setMaxSize(36, 36);
        Label loadingText = new Label("正在加载远程文件内容...");
        loadingText.setStyle("-fx-text-fill: #9ca1ac; -fx-font-size: 12px;");
        VBox loadingBox = new VBox(10, spinner, loadingText);
        loadingBox.setAlignment(Pos.CENTER);

        loadingOverlay.getChildren().add(loadingBox);
        loadingOverlay.setStyle("-fx-background-color: rgba(24, 26, 31, 0.85);");

        return new StackPane(editorArea, loadingOverlay);
    }

    private void updateStatusBadge() {
        if (isModified) {
            statusLabel.setText("● 已修改 (未保存)");
            statusLabel.getStyleClass().removeAll("saved");
            if (!statusLabel.getStyleClass().contains("modified")) {
                statusLabel.getStyleClass().add("modified");
            }
        } else {
            statusLabel.setText("✓ 已保存");
            statusLabel.getStyleClass().removeAll("modified");
            if (!statusLabel.getStyleClass().contains("saved")) {
                statusLabel.getStyleClass().add("saved");
            }
        }
    }

    private void loadRemoteFileContent() {
        sftpService.readTextFile(profile, item.path()).whenComplete((content, error) ->
                Platform.runLater(() -> {
                    loadingOverlay.setVisible(false);
                    if (error != null) {
                        statusLabel.setText("❌ 读取失败");
                        editorArea.setText("// 读取远程文件失败: " + error.getMessage());
                        editorArea.setEditable(false);
                        saveButton.setDisable(true);
                    } else {
                        editorArea.setText(content);
                        isModified = false;
                        statusLabel.setText("✓ 已同步至最新");
                        statusLabel.getStyleClass().removeAll("modified", "saved");
                    }
                })
        );
    }

    private void saveFile() {
        if (isSaving || !isModified) {
            return;
        }

        isSaving = true;
        saveButton.setDisable(true);
        statusLabel.setText("正在保存至服务器...");
        statusLabel.getStyleClass().removeAll("modified", "saved");

        String content = editorArea.getText();
        sftpService.writeTextFile(profile, item.path(), content).whenComplete((unused, error) ->
                Platform.runLater(() -> {
                    isSaving = false;
                    saveButton.setDisable(false);
                    if (error != null) {
                        statusLabel.setText("❌ 保存失败：" + error.getMessage());
                        statusLabel.getStyleClass().removeAll("saved");
                        if (!statusLabel.getStyleClass().contains("modified")) {
                            statusLabel.getStyleClass().add("modified");
                        }
                        Alert alert = new Alert(Alert.AlertType.ERROR);
                        alert.setTitle("保存失败");
                        alert.setHeaderText("无法将文件写入服务器");
                        alert.setContentText(error.getMessage());
                        ThemeManager.applyDialogTheme(alert, getOwner());
                        alert.showAndWait();
                    } else {
                        isModified = false;
                        updateStatusBadge();
                    }
                })
        );
    }
}
