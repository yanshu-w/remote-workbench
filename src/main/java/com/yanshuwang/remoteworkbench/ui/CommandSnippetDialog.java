package com.yanshuwang.remoteworkbench.ui;

import com.yanshuwang.remoteworkbench.config.CommandSnippet;
import com.yanshuwang.remoteworkbench.config.CommandSnippetService;
import com.yanshuwang.remoteworkbench.ui.theme.ThemeManager;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Modern modal dialog for managing custom and preset terminal command snippets.
 */
public final class CommandSnippetDialog extends Dialog<Void> {
    private final ObservableList<CommandSnippet> snippetList = FXCollections.observableArrayList();
    private final FilteredList<CommandSnippet> filteredSnippets = new FilteredList<>(snippetList, p -> true);
    private final TableView<CommandSnippet> tableView = new TableView<>(filteredSnippets);
    private final Consumer<CommandSnippet> onExecuteSnippet;

    public CommandSnippetDialog(Consumer<CommandSnippet> onExecuteSnippet) {
        this.onExecuteSnippet = onExecuteSnippet;

        setTitle("常用命令管理");
        setHeaderText("管理终端常用命令与快捷脚本");
        ThemeManager.applyDialogTheme(this, null);

        // Main layout
        BorderPane root = new BorderPane();
        root.setPrefSize(680, 440);
        root.setPadding(new Insets(10, 14, 10, 14));

        // Top search bar
        TextField searchField = new TextField();
        searchField.setPromptText("搜索命令 (名称/执行命令/分类)...");
        searchField.getStyleClass().add("sidebar-search-input");
        searchField.textProperty().addListener((obs, oldVal, query) -> {
            if (query == null || query.isBlank()) {
                filteredSnippets.setPredicate(p -> true);
            } else {
                String q = query.trim().toLowerCase();
                filteredSnippets.setPredicate(p ->
                        (p.name() != null && p.name().toLowerCase().contains(q))
                                || (p.command() != null && p.command().toLowerCase().contains(q))
                                || (p.category() != null && p.category().toLowerCase().contains(q))
                );
            }
        });

        HBox topBar = new HBox(10, new Label("搜索："), searchField);
        topBar.setAlignment(Pos.CENTER_LEFT);
        topBar.setPadding(new Insets(0, 0, 10, 0));
        HBox.setHgrow(searchField, Priority.ALWAYS);
        root.setTop(topBar);

        // Center TableView
        TableColumn<CommandSnippet, String> categoryCol = new TableColumn<>("分类");
        categoryCol.setCellValueFactory(cell -> new SimpleStringProperty(cell.getValue().category()));
        categoryCol.setPrefWidth(100);

        TableColumn<CommandSnippet, String> nameCol = new TableColumn<>("命令名称");
        nameCol.setCellValueFactory(cell -> new SimpleStringProperty(cell.getValue().name()));
        nameCol.setPrefWidth(180);

        TableColumn<CommandSnippet, String> commandCol = new TableColumn<>("执行命令");
        commandCol.setCellValueFactory(cell -> new SimpleStringProperty(cell.getValue().command()));
        commandCol.setPrefWidth(360);

        tableView.getColumns().addAll(categoryCol, nameCol, commandCol);
        tableView.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        tableView.getStyleClass().add("sftp-table");
        tableView.setPlaceholder(new Label("暂无常用命令"));

        // Double click to execute or edit
        tableView.setRowFactory(tv -> {
            TableRow<CommandSnippet> row = new TableRow<>();
            row.setOnMouseClicked(event -> {
                if (event.getClickCount() == 2 && !row.isEmpty()) {
                    CommandSnippet snippet = row.getItem();
                    if (onExecuteSnippet != null) {
                        close();
                        onExecuteSnippet.accept(snippet);
                    } else {
                        editSnippet(snippet);
                    }
                }
            });
            return row;
        });

        root.setCenter(tableView);

        // Bottom action toolbar
        Button addBtn = new Button("+ 添加命令");
        addBtn.getStyleClass().add("dialog-primary-button");
        addBtn.setOnAction(e -> addSnippet());

        Button editBtn = new Button("编辑");
        editBtn.getStyleClass().add("dialog-secondary-button");
        editBtn.disableProperty().bind(tableView.getSelectionModel().selectedItemProperty().isNull());
        editBtn.setOnAction(e -> {
            CommandSnippet selected = tableView.getSelectionModel().getSelectedItem();
            if (selected != null) {
                editSnippet(selected);
            }
        });

        Button deleteBtn = new Button("删除");
        deleteBtn.getStyleClass().add("dialog-secondary-button");
        deleteBtn.disableProperty().bind(tableView.getSelectionModel().selectedItemProperty().isNull());
        deleteBtn.setOnAction(e -> {
            CommandSnippet selected = tableView.getSelectionModel().getSelectedItem();
            if (selected != null) {
                deleteSnippet(selected);
            }
        });

        Button resetBtn = new Button("恢复默认");
        resetBtn.getStyleClass().add("dialog-secondary-button");
        resetBtn.setOnAction(e -> resetDefaultSnippets());

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox bottomBar = new HBox(8, addBtn, editBtn, deleteBtn, resetBtn, spacer);
        bottomBar.setAlignment(Pos.CENTER_LEFT);
        bottomBar.setPadding(new Insets(10, 0, 0, 0));

        if (onExecuteSnippet != null) {
            Button executeBtn = new Button("⚡ 执行选中命令");
            executeBtn.getStyleClass().add("dialog-primary-button");
            executeBtn.disableProperty().bind(tableView.getSelectionModel().selectedItemProperty().isNull());
            executeBtn.setOnAction(e -> {
                CommandSnippet selected = tableView.getSelectionModel().getSelectedItem();
                if (selected != null) {
                    close();
                    onExecuteSnippet.accept(selected);
                }
            });
            bottomBar.getChildren().add(executeBtn);
        }

        root.setBottom(bottomBar);
        getDialogPane().setContent(root);

        // Dialog buttons
        ButtonType closeButtonType = new ButtonType("完成", ButtonBar.ButtonData.OK_DONE);
        getDialogPane().getButtonTypes().add(closeButtonType);

        Node doneNode = getDialogPane().lookupButton(closeButtonType);
        if (doneNode != null) {
            doneNode.getStyleClass().add("dialog-primary-button");
        }

        // Load snippets
        loadSnippetsData();
    }

    private void loadSnippetsData() {
        snippetList.setAll(CommandSnippetService.loadSnippets());
        if (!snippetList.isEmpty()) {
            tableView.getSelectionModel().select(0);
        }
    }

    private void addSnippet() {
        showEditDialog(null).ifPresent(newSnippet -> {
            snippetList.add(newSnippet);
            saveChanges();
            tableView.getSelectionModel().select(newSnippet);
            tableView.scrollTo(newSnippet);
        });
    }

    private void editSnippet(CommandSnippet existing) {
        showEditDialog(existing).ifPresent(updated -> {
            int index = snippetList.indexOf(existing);
            if (index >= 0) {
                snippetList.set(index, updated);
            } else {
                snippetList.add(updated);
            }
            saveChanges();
            tableView.getSelectionModel().select(updated);
        });
    }

    private void deleteSnippet(CommandSnippet snippet) {
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle("删除确认");
        confirm.setHeaderText("确认删除常用命令？");
        confirm.setContentText("确定要删除「" + snippet.name() + "」(" + snippet.command() + ") 吗？");
        Window owner = (getDialogPane().getScene() != null) ? getDialogPane().getScene().getWindow() : null;
        ThemeManager.applyDialogTheme(confirm, owner);

        confirm.showAndWait().ifPresent(btn -> {
            if (btn == ButtonType.OK) {
                snippetList.remove(snippet);
                saveChanges();
            }
        });
    }

    private void resetDefaultSnippets() {
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle("恢复默认确认");
        confirm.setHeaderText("确认恢复默认常用命令？");
        confirm.setContentText("恢复默认将重置常用命令列表为系统预设集合，已添加的自定义命令将被覆盖。");
        Window owner = (getDialogPane().getScene() != null) ? getDialogPane().getScene().getWindow() : null;
        ThemeManager.applyDialogTheme(confirm, owner);

        confirm.showAndWait().ifPresent(btn -> {
            if (btn == ButtonType.OK) {
                snippetList.setAll(CommandSnippetService.getDefaultSnippets());
                saveChanges();
            }
        });
    }

    private void saveChanges() {
        CommandSnippetService.saveSnippets(new ArrayList<>(snippetList));
    }

    private Optional<CommandSnippet> showEditDialog(CommandSnippet initial) {
        Dialog<CommandSnippet> editDialog = new Dialog<>();
        editDialog.setTitle(initial == null ? "添加常用命令" : "编辑常用命令");
        editDialog.setHeaderText(initial == null ? "新建终端常用快捷命令" : "修改常用命令配置");
        Window owner = (getDialogPane().getScene() != null) ? getDialogPane().getScene().getWindow() : null;
        ThemeManager.applyDialogTheme(editDialog, owner);

        TextField nameField = new TextField(initial != null ? initial.name() : "");
        nameField.setPromptText("例如：重启 Nginx 服务");
        nameField.setPrefWidth(320);

        TextArea cmdArea = new TextArea(initial != null ? initial.command() : "");
        cmdArea.setPromptText("例如：sudo systemctl restart nginx");
        cmdArea.setPrefRowCount(3);
        cmdArea.setWrapText(true);
        cmdArea.setStyle("-fx-font-family: 'Menlo', 'SF Mono', monospace; -fx-font-size: 12px;");

        ComboBox<String> categoryCombo = new ComboBox<>();
        categoryCombo.setEditable(true);
        categoryCombo.getItems().addAll("系统信息", "系统状态", "进程与日志", "网络与端口", "Docker", "自定义");
        categoryCombo.setValue(initial != null ? initial.category() : "自定义");
        categoryCombo.setMaxWidth(Double.MAX_VALUE);

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(12);
        grid.setPadding(new Insets(14, 10, 10, 10));

        grid.add(new Label("分类："), 0, 0);
        grid.add(categoryCombo, 1, 0);
        grid.add(new Label("名称："), 0, 1);
        grid.add(nameField, 1, 1);
        grid.add(new Label("命令内容："), 0, 2);
        grid.add(cmdArea, 1, 2);

        editDialog.getDialogPane().setContent(grid);

        ButtonType saveBtnType = new ButtonType("保存", ButtonBar.ButtonData.OK_DONE);
        ButtonType cancelBtnType = new ButtonType("取消", ButtonBar.ButtonData.CANCEL_CLOSE);
        editDialog.getDialogPane().getButtonTypes().addAll(saveBtnType, cancelBtnType);

        Node saveNode = editDialog.getDialogPane().lookupButton(saveBtnType);
        if (saveNode != null) {
            saveNode.getStyleClass().add("dialog-primary-button");
            saveNode.setDisable(nameField.getText().isBlank() || cmdArea.getText().isBlank());

            // Validate on text change
            Runnable validator = () -> saveNode.setDisable(nameField.getText().isBlank() || cmdArea.getText().isBlank());
            nameField.textProperty().addListener((obs, o, n) -> validator.run());
            cmdArea.textProperty().addListener((obs, o, n) -> validator.run());
        }

        editDialog.setResultConverter(btn -> {
            if (btn == saveBtnType) {
                String cat = categoryCombo.getValue();
                if (cat == null || cat.isBlank()) {
                    cat = "常用命令";
                }
                String id = initial != null ? initial.id() : null;
                return new CommandSnippet(id, nameField.getText(), cmdArea.getText(), cat);
            }
            return null;
        });

        return editDialog.showAndWait();
    }
}
