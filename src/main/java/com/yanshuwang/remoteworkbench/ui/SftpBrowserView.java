package com.yanshuwang.remoteworkbench.ui;

import com.yanshuwang.remoteworkbench.connection.ConnectionProfile;
import com.yanshuwang.remoteworkbench.sftp.RemoteFileItem;
import com.yanshuwang.remoteworkbench.sftp.SftpService;
import com.yanshuwang.remoteworkbench.ui.theme.ThemeManager;
import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.input.Dragboard;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.stage.FileChooser;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

public final class SftpBrowserView extends BorderPane {
    private final ObservableList<RemoteFileItem> fileItems = FXCollections.observableArrayList();
    private final TableView<RemoteFileItem> tableView = new TableView<>(fileItems);
    private final TextField pathField = new TextField("/");
    private final Button upButton = new Button("↑ 上级");
    private final Button refreshButton = new Button("刷新");
    private final Button newFolderButton = new Button("+ 新建文件夹");
    private final Button uploadButton = new Button("上传文件");
    private final Label itemCountLabel = new Label("暂无文件");
    private final Label statusLabel = new Label("未连接");
    private final ProgressBar progressBar = new ProgressBar(0);

    private ConnectionProfile currentProfile;
    private SftpService sftpService;
    private String currentPath = "/";
    private String defaultHomePath = "/";

    public SftpBrowserView() {
        getStyleClass().add("sftp-browser");
        setTop(createToolBar());
        setCenter(createTableView());
        setBottom(createStatusBar());
        setControlsEnabled(false);
    }

    public String getCurrentPath() {
        return currentPath;
    }

    private HBox createToolBar() {
        upButton.getStyleClass().add("secondary-button");
        upButton.setOnAction(event -> navigateUp());

        pathField.setPromptText("远程目录路径，如 /var/www 或 /root");
        pathField.setOnAction(event -> navigateTo(pathField.getText()));
        HBox.setHgrow(pathField, Priority.ALWAYS);

        Button goButton = new Button("前往");
        goButton.getStyleClass().add("secondary-button");
        goButton.setOnAction(event -> navigateTo(pathField.getText()));

        refreshButton.getStyleClass().add("secondary-button");
        refreshButton.setOnAction(event -> reloadCurrentDirectory());

        newFolderButton.getStyleClass().add("secondary-button");
        newFolderButton.setOnAction(event -> promptNewFolder());

        uploadButton.getStyleClass().add("primary-button");
        uploadButton.setOnAction(event -> promptUploadFile());

        HBox toolBar = new HBox(8, upButton, pathField, goButton, refreshButton, newFolderButton, uploadButton);
        toolBar.setAlignment(Pos.CENTER_LEFT);
        toolBar.setPadding(new Insets(10, 12, 10, 12));
        toolBar.getStyleClass().add("sftp-toolbar");
        return toolBar;
    }

    private TableView<RemoteFileItem> createTableView() {
        TableColumn<RemoteFileItem, String> typeCol = new TableColumn<>("类型");
        typeCol.setPrefWidth(90);
        typeCol.setCellValueFactory(cell -> new SimpleStringProperty(cell.getValue().typeDisplayName()));

        TableColumn<RemoteFileItem, String> nameCol = new TableColumn<>("名称");
        nameCol.setPrefWidth(320);
        nameCol.setCellValueFactory(cell -> new SimpleStringProperty(cell.getValue().name()));

        TableColumn<RemoteFileItem, String> sizeCol = new TableColumn<>("大小");
        sizeCol.setPrefWidth(110);
        sizeCol.setCellValueFactory(cell -> new SimpleStringProperty(cell.getValue().formattedSize()));

        TableColumn<RemoteFileItem, String> permCol = new TableColumn<>("权限");
        permCol.setPrefWidth(120);
        permCol.setCellValueFactory(cell -> new SimpleStringProperty(cell.getValue().permissions()));

        TableColumn<RemoteFileItem, String> timeCol = new TableColumn<>("修改时间");
        timeCol.setPrefWidth(180);
        timeCol.setCellValueFactory(cell -> new SimpleStringProperty(cell.getValue().formattedModifyTime()));

        tableView.getColumns().addAll(typeCol, nameCol, sizeCol, permCol, timeCol);
        tableView.setPlaceholder(new Label("当前目录为空或未连接"));
        tableView.getStyleClass().add("sftp-table");

        tableView.setOnDragOver(event -> {
            if (currentProfile != null && sftpService != null && event.getDragboard().hasFiles()) {
                event.acceptTransferModes(TransferMode.COPY);
                if (!tableView.getStyleClass().contains("drag-over")) {
                    tableView.getStyleClass().add("drag-over");
                }
                event.consume();
            }
        });

        tableView.setOnDragExited(event -> {
            tableView.getStyleClass().remove("drag-over");
            event.consume();
        });

        tableView.setOnDragDropped(event -> {
            Dragboard dragboard = event.getDragboard();
            boolean success = false;
            if (currentProfile != null && sftpService != null && dragboard.hasFiles()) {
                List<File> files = dragboard.getFiles();
                if (files != null && !files.isEmpty()) {
                    success = true;
                    uploadBatch(files, currentPath);
                }
            }
            tableView.getStyleClass().remove("drag-over");
            event.setDropCompleted(success);
            event.consume();
        });

        tableView.setRowFactory(tv -> {
            TableRow<RemoteFileItem> row = new TableRow<>();
            row.setOnMouseClicked(event -> {
                if (event.getClickCount() == 2 && !row.isEmpty()) {
                    RemoteFileItem item = row.getItem();
                    if (item.isDirectory()) {
                        navigateTo(item.path());
                    } else {
                        openFileEditor(item);
                    }
                }
            });

            row.setOnDragOver(event -> {
                if (currentProfile != null && sftpService != null && event.getDragboard().hasFiles()) {
                    RemoteFileItem item = row.getItem();
                    if (item != null && item.isDirectory()) {
                        event.acceptTransferModes(TransferMode.COPY);
                        if (!row.getStyleClass().contains("row-drag-over")) {
                            row.getStyleClass().add("row-drag-over");
                        }
                        event.consume();
                    }
                }
            });

            row.setOnDragExited(event -> {
                row.getStyleClass().remove("row-drag-over");
                event.consume();
            });

            row.setOnDragDropped(event -> {
                Dragboard dragboard = event.getDragboard();
                RemoteFileItem item = row.getItem();
                if (currentProfile != null && sftpService != null && dragboard.hasFiles() && item != null && item.isDirectory()) {
                    List<File> files = dragboard.getFiles();
                    if (files != null && !files.isEmpty()) {
                        row.getStyleClass().remove("row-drag-over");
                        event.setDropCompleted(true);
                        event.consume();
                        uploadBatch(files, item.path());
                        return;
                    }
                }
                row.getStyleClass().remove("row-drag-over");
            });

            row.setContextMenu(createContextMenu(row));
            return row;
        });

        return tableView;
    }

    private ContextMenu createContextMenu(TableRow<RemoteFileItem> row) {
        ContextMenu menu = new ContextMenu();

        MenuItem openItem = new MenuItem("打开 / 进入");
        openItem.setOnAction(e -> {
            RemoteFileItem item = row.getItem();
            if (item != null && item.isDirectory()) {
                navigateTo(item.path());
            } else if (item != null) {
                openFileEditor(item);
            }
        });

        MenuItem editItem = new MenuItem("在线编辑 (Edit)");
        editItem.setOnAction(e -> {
            RemoteFileItem item = row.getItem();
            if (item != null && !item.isDirectory()) {
                openFileEditor(item);
            }
        });

        MenuItem renameItem = new MenuItem("重命名 (Rename)...");
        renameItem.setOnAction(e -> {
            RemoteFileItem item = row.getItem();
            if (item != null) {
                promptRename(item);
            }
        });

        MenuItem chmodItem = new MenuItem("修改权限 (Chmod)...");
        chmodItem.setOnAction(e -> {
            RemoteFileItem item = row.getItem();
            if (item != null) {
                promptChmod(item);
            }
        });

        MenuItem downloadItem = new MenuItem("下载到本地...");
        downloadItem.setOnAction(e -> {
            RemoteFileItem item = row.getItem();
            if (item != null) {
                promptDownload(item);
            }
        });

        MenuItem deleteItem = new MenuItem("删除");
        deleteItem.setOnAction(e -> {
            RemoteFileItem item = row.getItem();
            if (item != null) {
                promptDelete(item);
            }
        });

        MenuItem refreshItem = new MenuItem("刷新");
        refreshItem.setOnAction(e -> reloadCurrentDirectory());

        MenuItem newDirItem = new MenuItem("新建文件夹");
        newDirItem.setOnAction(e -> promptNewFolder());

        menu.setOnShowing(e -> {
            boolean hasItem = !row.isEmpty() && row.getItem() != null;
            RemoteFileItem item = row.getItem();
            openItem.setVisible(hasItem);
            editItem.setVisible(hasItem && item != null && !item.isDirectory());
            renameItem.setVisible(hasItem);
            chmodItem.setVisible(hasItem);
            downloadItem.setVisible(hasItem && item != null && !item.isDirectory());
            deleteItem.setVisible(hasItem);
        });

        menu.getItems().addAll(
                openItem,
                editItem,
                renameItem,
                chmodItem,
                downloadItem,
                deleteItem,
                new SeparatorMenuItem(),
                newDirItem,
                refreshItem
        );
        return menu;
    }

    private HBox createStatusBar() {
        itemCountLabel.getStyleClass().add("status-muted");
        statusLabel.getStyleClass().add("status-muted");

        progressBar.setVisible(false);
        progressBar.setPrefWidth(120);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox statusBar = new HBox(12, itemCountLabel, spacer, progressBar, statusLabel);
        statusBar.setAlignment(Pos.CENTER_LEFT);
        statusBar.setPadding(new Insets(6, 12, 6, 12));
        statusBar.getStyleClass().add("sftp-status-bar");
        return statusBar;
    }

    public void attachConnection(ConnectionProfile profile, SftpService service) {
        this.currentProfile = profile;
        this.sftpService = service;
        setControlsEnabled(true);
        statusLabel.setText("正在定位主目录...");

        sftpService.resolveDefaultDirectory(profile).whenComplete((homePath, error) -> {
            Platform.runLater(() -> {
                if (error != null) {
                    defaultHomePath = "/";
                    navigateTo("/");
                } else {
                    defaultHomePath = (homePath != null && !homePath.isBlank()) ? homePath : "/";
                    navigateTo(defaultHomePath);
                }
            });
        });
    }

    public void detach() {
        this.currentProfile = null;
        this.sftpService = null;
        fileItems.clear();
        pathField.setText("/");
        currentPath = "/";
        defaultHomePath = "/";
        itemCountLabel.setText("暂无文件");
        statusLabel.setText("未连接");
        setControlsEnabled(false);
    }

    private String resolveHomePath(String path) {
        if (path == null || path.isBlank()) {
            return "/";
        }
        String trimmed = path.trim();
        if (trimmed.equals("~")) {
            return (defaultHomePath != null && !defaultHomePath.isBlank()) ? defaultHomePath : "/";
        } else if (trimmed.startsWith("~/")) {
            String base = (defaultHomePath != null && !defaultHomePath.isBlank()) ? defaultHomePath : "";
            if (base.endsWith("/")) {
                base = base.substring(0, base.length() - 1);
            }
            return base + trimmed.substring(1);
        }
        return trimmed;
    }

    public void navigateTo(String path) {
        if (currentProfile == null || sftpService == null) {
            return;
        }

        String targetPath = SftpService.normalizeRemotePath(resolveHomePath(path));
        statusLabel.setText("正在加载目录...");
        progressBar.setProgress(-1);
        progressBar.setVisible(true);

        sftpService.listDirectory(currentProfile, targetPath).whenComplete((items, error) -> {
            Platform.runLater(() -> {
                progressBar.setVisible(false);
                if (error != null) {
                    statusLabel.setText("无法访问该目录");
                    showError("打开目录失败", "无法读取远程路径：" + targetPath, error.getMessage());
                    pathField.setText(currentPath);
                } else {
                    currentPath = targetPath;
                    pathField.setText(currentPath);
                    fileItems.setAll(items);
                    updateItemStats(items);
                    statusLabel.setText("就绪");
                }
            });
        });
    }

    public void navigateToAndSelect(String path) {
        if (currentProfile == null || sftpService == null || path == null || path.isBlank()) {
            return;
        }

        String targetPath = SftpService.normalizeRemotePath(resolveHomePath(path));
        statusLabel.setText("正在定位路径...");
        progressBar.setProgress(-1);
        progressBar.setVisible(true);

        // 1. Try listing as a directory
        sftpService.listDirectory(currentProfile, targetPath).whenComplete((items, error) -> {
            Platform.runLater(() -> {
                if (error == null) {
                    progressBar.setVisible(false);
                    currentPath = targetPath;
                    pathField.setText(currentPath);
                    fileItems.setAll(items);
                    updateItemStats(items);
                    statusLabel.setText("就绪");
                } else {
                    // 2. If listing fails, target might be a file; list its parent and select the file!
                    String parent = SftpService.getParentDirectory(targetPath);
                    String fileName = SftpService.getFileName(targetPath);

                    sftpService.listDirectory(currentProfile, parent).whenComplete((parentItems, parentErr) -> {
                        Platform.runLater(() -> {
                            progressBar.setVisible(false);
                            if (parentErr == null) {
                                currentPath = parent;
                                pathField.setText(currentPath);
                                fileItems.setAll(parentItems);
                                updateItemStats(parentItems);
                                statusLabel.setText("已定位文件：" + fileName);

                                for (RemoteFileItem item : parentItems) {
                                    if (item.name().equals(fileName)) {
                                        tableView.getSelectionModel().select(item);
                                        tableView.scrollTo(item);
                                        break;
                                    }
                                }
                            } else {
                                statusLabel.setText("无法访问路径");
                                showError("打开路径失败", "无法读取远程路径：" + targetPath, error.getMessage());
                                pathField.setText(currentPath);
                            }
                        });
                    });
                }
            });
        });
    }

    private void navigateUp() {
        if ("/".equals(currentPath)) {
            return;
        }
        String parent = SftpService.getParentDirectory(currentPath);
        navigateTo(parent);
    }

    private void reloadCurrentDirectory() {
        navigateTo(currentPath);
    }

    private void updateItemStats(List<RemoteFileItem> items) {
        long dirCount = items.stream().filter(RemoteFileItem::isDirectory).count();
        long fileCount = items.size() - dirCount;
        itemCountLabel.setText(String.format("共 %d 项 (%d 个文件夹, %d 个文件)", items.size(), dirCount, fileCount));
    }

    private void promptNewFolder() {
        if (currentProfile == null || sftpService == null) {
            return;
        }

        TextInputDialog dialog = new TextInputDialog("新文件夹");
        dialog.setTitle("新建文件夹");
        dialog.setHeaderText("新建远程文件夹");
        dialog.setContentText("请输入文件夹名称：");
        applyDialogTheme(dialog);

        Optional<String> result = dialog.showAndWait();
        result.ifPresent(name -> {
            String cleanName = name.trim();
            if (cleanName.isEmpty() || cleanName.contains("/")) {
                showError("名称无效", "文件夹名称不能为空且不能包含斜杠 '/'", "");
                return;
            }

            String newFolderPath = SftpService.joinPath(currentPath, cleanName);
            statusLabel.setText("正在创建文件夹...");
            sftpService.createDirectory(currentProfile, newFolderPath).whenComplete((unused, error) -> {
                Platform.runLater(() -> {
                    if (error != null) {
                        showError("创建失败", "无法创建文件夹：" + newFolderPath, error.getMessage());
                    } else {
                        reloadCurrentDirectory();
                    }
                });
            });
        });
    }

    private void openFileEditor(RemoteFileItem item) {
        if (currentProfile == null || sftpService == null || item.isDirectory()) {
            return;
        }

        RemoteFileEditorDialog dialog = new RemoteFileEditorDialog(
                getScene() != null ? getScene().getWindow() : null,
                currentProfile,
                item,
                sftpService
        );
        dialog.showAndWait();
        reloadCurrentDirectory();
    }

    private void promptRename(RemoteFileItem item) {
        if (currentProfile == null || sftpService == null) {
            return;
        }

        TextInputDialog dialog = new TextInputDialog(item.name());
        dialog.setTitle("重命名");
        dialog.setHeaderText("重命名远程文件 / 目录");
        dialog.setContentText("请输入新名称：");
        applyDialogTheme(dialog);

        Optional<String> result = dialog.showAndWait();
        result.ifPresent(name -> {
            String cleanName = name.trim();
            if (cleanName.isEmpty() || cleanName.contains("/")) {
                showError("名称无效", "新名称不能为空且不能包含斜杠 '/'", "");
                return;
            }
            if (cleanName.equals(item.name())) {
                return;
            }

            String newPath = SftpService.joinPath(SftpService.getParentDirectory(item.path()), cleanName);
            statusLabel.setText("正在重命名...");
            sftpService.rename(currentProfile, item.path(), newPath).whenComplete((unused, error) -> {
                Platform.runLater(() -> {
                    if (error != null) {
                        showError("重命名失败", "无法重命名 " + item.name(), error.getMessage());
                    } else {
                        reloadCurrentDirectory();
                    }
                });
            });
        });
    }

    private void promptChmod(RemoteFileItem item) {
        if (currentProfile == null || sftpService == null) {
            return;
        }

        TextInputDialog dialog = new TextInputDialog(guessOctalPermissions(item.permissions()));
        dialog.setTitle("修改权限");
        dialog.setHeaderText("修改远程权限 (POSIX / Octal)");
        dialog.setContentText("请输入三位八进制权限（例如 755、644、600）：");
        applyDialogTheme(dialog);

        Optional<String> result = dialog.showAndWait();
        result.ifPresent(val -> {
            try {
                int octal = Integer.parseInt(val.trim(), 8);
                statusLabel.setText("正在修改权限...");
                sftpService.changePermissions(currentProfile, item.path(), octal).whenComplete((unused, error) -> {
                    Platform.runLater(() -> {
                        if (error != null) {
                            showError("修改权限失败", "无法修改 " + item.name() + " 的权限", error.getMessage());
                        } else {
                            reloadCurrentDirectory();
                        }
                    });
                });
            } catch (NumberFormatException exception) {
                showError("格式错误", "请输入有效的八进制权限数字（如 755、644）", "");
            }
        });
    }

    private static String guessOctalPermissions(String permStr) {
        if (permStr == null || permStr.length() < 9) {
            return "644";
        }
        String s = (permStr.startsWith("d") || permStr.startsWith("-") || permStr.startsWith("l"))
                ? permStr.substring(1)
                : permStr;
        if (s.length() < 9) {
            return "644";
        }
        int u = (s.charAt(0) == 'r' ? 4 : 0) + (s.charAt(1) == 'w' ? 2 : 0) + (s.charAt(2) == 'x' ? 1 : 0);
        int g = (s.charAt(3) == 'r' ? 4 : 0) + (s.charAt(4) == 'w' ? 2 : 0) + (s.charAt(5) == 'x' ? 1 : 0);
        int o = (s.charAt(6) == 'r' ? 4 : 0) + (s.charAt(7) == 'w' ? 2 : 0) + (s.charAt(8) == 'x' ? 1 : 0);
        return "" + u + g + o;
    }

    private void applyDialogTheme(Dialog<?> dialog) {
        ThemeManager.applyDialogTheme(dialog, getScene() != null ? getScene().getWindow() : null);
        Node okBtn = dialog.getDialogPane().lookupButton(ButtonType.OK);
        if (okBtn != null) {
            okBtn.getStyleClass().add("dialog-primary-button");
        }
        Node cancelBtn = dialog.getDialogPane().lookupButton(ButtonType.CANCEL);
        if (cancelBtn != null) {
            cancelBtn.getStyleClass().add("dialog-secondary-button");
        }
    }

    private void promptDelete(RemoteFileItem item) {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.setTitle("确认删除");
        alert.setHeaderText("确定要删除该项目吗？");
        alert.setContentText("路径：" + item.path() + "\n类型：" + item.typeDisplayName() + "\n警告：此操作无法撤销！");
        ButtonType deleteButton = new ButtonType("删除", ButtonBar.ButtonData.OK_DONE);
        ButtonType cancelButton = new ButtonType("取消", ButtonBar.ButtonData.CANCEL_CLOSE);
        alert.getButtonTypes().setAll(deleteButton, cancelButton);
        applyDialogTheme(alert);
        Node deleteBtn = alert.getDialogPane().lookupButton(deleteButton);
        if (deleteBtn != null) {
            deleteBtn.getStyleClass().add("dialog-primary-button");
        }

        alert.showAndWait().ifPresent(selected -> {
            if (selected == deleteButton) {
                statusLabel.setText("正在删除...");
                sftpService.delete(currentProfile, item.path(), item.isDirectory()).whenComplete((unused, error) -> {
                    Platform.runLater(() -> {
                        if (error != null) {
                            showError("删除失败", "无法删除 " + item.name(), error.getMessage());
                        } else {
                            reloadCurrentDirectory();
                        }
                    });
                });
            }
        });
    }

    private void promptDownload(RemoteFileItem item) {
        if (item.isDirectory()) {
            navigateTo(item.path());
            return;
        }

        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("选择下载保存位置");
        fileChooser.setInitialFileName(item.name());
        if (getScene() != null && getScene().getWindow() != null) {
            File dest = fileChooser.showSaveDialog(getScene().getWindow());
            if (dest != null) {
                startDownload(item, dest);
            }
        }
    }

    private void startDownload(RemoteFileItem item, File destination) {
        statusLabel.setText("正在下载 " + item.name() + "...");
        progressBar.setProgress(-1);
        progressBar.setVisible(true);

        sftpService.downloadFile(currentProfile, item.path(), destination.toPath(), totalRead -> {
            if (item.size() > 0) {
                double progress = (double) totalRead / item.size();
                Platform.runLater(() -> progressBar.setProgress(progress));
            }
        }).whenComplete((unused, error) -> {
            Platform.runLater(() -> {
                progressBar.setVisible(false);
                if (error != null) {
                    showError("下载失败", "文件下载中断：" + item.name(), error.getMessage());
                    statusLabel.setText("下载失败");
                } else {
                    statusLabel.setText("下载完成：" + item.name());
                }
            });
        });
    }

    private void promptUploadFile() {
        if (currentProfile == null || sftpService == null) {
            return;
        }

        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("选择要上传的本地文件（可多选）");
        if (getScene() != null && getScene().getWindow() != null) {
            List<File> selectedFiles = fileChooser.showOpenMultipleDialog(getScene().getWindow());
            if (selectedFiles != null && !selectedFiles.isEmpty()) {
                uploadBatch(selectedFiles, currentPath);
            }
        }
    }

    private void uploadBatch(List<File> files, String targetRemoteDir) {
        if (currentProfile == null || sftpService == null || files == null || files.isEmpty()) {
            return;
        }

        List<File> filesToUpload = new ArrayList<>(files);
        if (targetRemoteDir != null && targetRemoteDir.equals(currentPath)) {
            Set<String> existingNames = fileItems.stream()
                    .map(RemoteFileItem::name)
                    .collect(Collectors.toSet());

            List<File> conflictFiles = files.stream()
                    .filter(f -> existingNames.contains(f.getName()))
                    .toList();

            if (!conflictFiles.isEmpty()) {
                Alert conflictAlert = new Alert(Alert.AlertType.CONFIRMATION);
                conflictAlert.setTitle("文件冲突提示");
                conflictAlert.setHeaderText("目标目录已存在同名文件");

                String content;
                if (conflictFiles.size() == 1) {
                    content = "远程目录已存在同名文件「" + conflictFiles.get(0).getName() + "」。\n是否覆盖该文件？";
                } else {
                    String names = conflictFiles.stream().limit(3).map(File::getName).collect(Collectors.joining("、"));
                    if (conflictFiles.size() > 3) {
                        names += " 等 " + conflictFiles.size() + " 个项目";
                    }
                    content = "发现 " + conflictFiles.size() + " 个同名冲突文件（" + names + "）。\n是否覆盖这些文件？";
                }
                conflictAlert.setContentText(content);

                ButtonType overwriteBtn = new ButtonType("覆盖", ButtonBar.ButtonData.OK_DONE);
                ButtonType cancelBtn = new ButtonType("取消上传", ButtonBar.ButtonData.CANCEL_CLOSE);

                if (files.size() > conflictFiles.size()) {
                    ButtonType skipBtn = new ButtonType("跳过冲突", ButtonBar.ButtonData.OTHER);
                    conflictAlert.getButtonTypes().setAll(overwriteBtn, skipBtn, cancelBtn);
                    applyDialogTheme(conflictAlert);

                    Node overNode = conflictAlert.getDialogPane().lookupButton(overwriteBtn);
                    if (overNode != null) overNode.getStyleClass().add("dialog-primary-button");
                    Node skipNode = conflictAlert.getDialogPane().lookupButton(skipBtn);
                    if (skipNode != null) skipNode.getStyleClass().add("dialog-secondary-button");
                    Node cancelNode = conflictAlert.getDialogPane().lookupButton(cancelBtn);
                    if (cancelNode != null) cancelNode.getStyleClass().add("dialog-secondary-button");

                    Optional<ButtonType> result = conflictAlert.showAndWait();
                    if (result.isEmpty() || result.get() == cancelBtn) {
                        statusLabel.setText("已取消上传");
                        return;
                    }
                    if (result.get() == skipBtn) {
                        Set<String> conflictNames = conflictFiles.stream().map(File::getName).collect(Collectors.toSet());
                        filesToUpload.removeIf(f -> conflictNames.contains(f.getName()));
                        if (filesToUpload.isEmpty()) {
                            statusLabel.setText("已跳过所有冲突文件");
                            return;
                        }
                    }
                } else {
                    conflictAlert.getButtonTypes().setAll(overwriteBtn, cancelBtn);
                    applyDialogTheme(conflictAlert);

                    Node overNode = conflictAlert.getDialogPane().lookupButton(overwriteBtn);
                    if (overNode != null) overNode.getStyleClass().add("dialog-primary-button");
                    Node cancelNode = conflictAlert.getDialogPane().lookupButton(cancelBtn);
                    if (cancelNode != null) cancelNode.getStyleClass().add("dialog-secondary-button");

                    Optional<ButtonType> result = conflictAlert.showAndWait();
                    if (result.isEmpty() || result.get() == cancelBtn) {
                        statusLabel.setText("已取消上传");
                        return;
                    }
                }
            }
        }

        progressBar.setProgress(-1);
        progressBar.setVisible(true);
        statusLabel.setText("准备上传 " + filesToUpload.size() + " 个项目...");

        CompletableFuture.runAsync(() -> {
            int total = filesToUpload.size();
            for (int i = 0; i < total; i++) {
                File file = filesToUpload.get(i);
                int index = i + 1;
                Platform.runLater(() -> statusLabel.setText(String.format("正在上传 (%d/%d): %s...", index, total, file.getName())));
                try {
                    long fileSize = file.length();
                    sftpService.uploadPath(
                            currentProfile,
                            file.toPath(),
                            targetRemoteDir,
                            currentFileName -> Platform.runLater(() ->
                                    statusLabel.setText(String.format("(%d/%d) 正在上传: %s", index, total, currentFileName))),
                            totalWritten -> {
                                if (fileSize > 0) {
                                    double progress = (double) totalWritten / fileSize;
                                    Platform.runLater(() -> progressBar.setProgress(progress));
                                }
                            }
                    ).join();
                } catch (Exception exception) {
                    Platform.runLater(() -> showError("上传中断", "项目上传失败：" + file.getName(), exception.getMessage()));
                    break;
                }
            }

            Platform.runLater(() -> {
                progressBar.setVisible(false);
                statusLabel.setText("上传完成，共 " + total + " 个项目");
                reloadCurrentDirectory();
            });
        });
    }

    private void setControlsEnabled(boolean enabled) {
        upButton.setDisable(!enabled);
        pathField.setDisable(!enabled);
        refreshButton.setDisable(!enabled);
        newFolderButton.setDisable(!enabled);
        uploadButton.setDisable(!enabled);
        tableView.setDisable(!enabled);
    }

    private void showError(String title, String header, String detail) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle(title);
        alert.setHeaderText(header);
        alert.setContentText(detail == null || detail.isBlank() ? "未知错误" : detail);
        applyDialogTheme(alert);
        alert.showAndWait();
    }
}
