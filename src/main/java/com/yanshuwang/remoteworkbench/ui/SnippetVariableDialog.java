package com.yanshuwang.remoteworkbench.ui;

import com.yanshuwang.remoteworkbench.config.CommandSnippet;
import com.yanshuwang.remoteworkbench.config.SnippetExecutor;
import com.yanshuwang.remoteworkbench.ui.theme.ThemeManager;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Interactive dialog that prompts the user to fill in placeholders (${var}) before executing a command snippet.
 */
public final class SnippetVariableDialog extends Dialog<String> {

    private final CommandSnippet snippet;
    private final Map<String, TextField> fieldMap = new LinkedHashMap<>();
    private final TextField previewField = new TextField();

    public SnippetVariableDialog(CommandSnippet snippet) {
        this.snippet = snippet;

        setTitle("常用命令变量填充");
        setHeaderText("参数填充与执行确认");
        ThemeManager.applyDialogTheme(this, null);

        VBox contentBox = new VBox(14);
        contentBox.setPrefWidth(540);
        contentBox.setPadding(new Insets(10, 16, 14, 16));

        // 1. Snippet info
        Label nameLabel = new Label("命令名称：" + snippet.name() + " (" + snippet.category() + ")");
        nameLabel.getStyleClass().add("status-bold");

        Label templateLabel = new Label(snippet.command());
        templateLabel.getStyleClass().addAll("dialog-form-hint", "snippet-template-code");
        templateLabel.setWrapText(true);

        VBox infoBox = new VBox(4, nameLabel, templateLabel);
        infoBox.getStyleClass().add("snippet-template-container");
        infoBox.setPadding(new Insets(8, 12, 8, 12));

        // 2. Variable form grid
        List<SnippetExecutor.VariableDefinition> variables = SnippetExecutor.extractVariables(snippet.command());
        GridPane grid = new GridPane();
        grid.setHgap(12);
        grid.setVgap(10);

        TextField firstField = null;
        int row = 0;
        for (SnippetExecutor.VariableDefinition varDef : variables) {
            Label varLabel = new Label("${" + varDef.name() + "}");
            varLabel.getStyleClass().add("dialog-form-label");
            varLabel.setMinWidth(110);

            TextField inputField = new TextField(varDef.defaultValue());
            inputField.setPromptText("输入 " + varDef.name() + " 的值");
            inputField.getStyleClass().add("dialog-form-input");
            GridPane.setHgrow(inputField, Priority.ALWAYS);

            inputField.textProperty().addListener((obs, oldV, newV) -> updatePreview());
            inputField.setOnKeyPressed(e -> {
                if (e.getCode() == KeyCode.ENTER) {
                    setResult(previewField.getText());
                    close();
                }
            });

            fieldMap.put(varDef.name(), inputField);
            grid.add(varLabel, 0, row);
            grid.add(inputField, 1, row);

            if (firstField == null) {
                firstField = inputField;
            }
            row++;
        }

        // 3. Live Preview
        Label previewTitle = new Label("最终执行命令预览：");
        previewTitle.getStyleClass().add("section-label");

        previewField.setEditable(false);
        previewField.getStyleClass().addAll("dialog-form-input", "snippet-preview-input");
        previewField.setFocusTraversable(false);

        VBox previewBox = new VBox(6, previewTitle, previewField);

        contentBox.getChildren().addAll(infoBox, grid, previewBox);
        getDialogPane().setContent(contentBox);

        // 4. Buttons
        ButtonType executeButtonType = new ButtonType("执行 (Enter)", ButtonBar.ButtonData.OK_DONE);
        ButtonType cancelButtonType = new ButtonType("取消", ButtonBar.ButtonData.CANCEL_CLOSE);
        getDialogPane().getButtonTypes().addAll(executeButtonType, cancelButtonType);

        Node execNode = getDialogPane().lookupButton(executeButtonType);
        if (execNode != null) {
            execNode.getStyleClass().add("dialog-primary-button");
        }
        Node cancelNode = getDialogPane().lookupButton(cancelButtonType);
        if (cancelNode != null) {
            cancelNode.getStyleClass().add("dialog-secondary-button");
        }

        setResultConverter(buttonType -> {
            if (buttonType == executeButtonType) {
                return previewField.getText();
            }
            return null;
        });

        updatePreview();

        final TextField focusTarget = firstField;
        Platform.runLater(() -> {
            if (focusTarget != null) {
                focusTarget.requestFocus();
                focusTarget.selectAll();
            }
        });
    }

    private void updatePreview() {
        Map<String, String> values = new LinkedHashMap<>();
        for (Map.Entry<String, TextField> entry : fieldMap.entrySet()) {
            values.put(entry.getKey(), entry.getValue().getText());
        }
        String assembled = SnippetExecutor.substituteVariables(snippet.command(), values);
        previewField.setText(assembled);
    }
}
