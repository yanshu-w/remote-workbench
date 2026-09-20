package com.yanshuwang.remoteworkbench.ui;

import com.yanshuwang.remoteworkbench.connection.ConnectionProfile;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

public final class MainView extends BorderPane {
    private final ObservableList<ConnectionProfile> connectionProfiles = FXCollections.observableArrayList();
    private final ListView<ConnectionProfile> connectionList = new ListView<>(connectionProfiles);
    private final Label activeConnectionLabel = new Label("No active connection");
    private final Label statusLabel = new Label("Ready");

    public MainView() {
        getStyleClass().add("app-root");
        setTop(createTopBar());
        setLeft(createConnectionSidebar());
        setCenter(createWorkspace());
        setBottom(createStatusBar());

        connectionList.getSelectionModel().selectedItemProperty().addListener((observable, previous, selected) -> {
            if (selected == null) {
                activeConnectionLabel.setText("No active connection");
                statusLabel.setText("Ready");
            } else {
                activeConnectionLabel.setText(selected.username() + "@" + selected.host());
                statusLabel.setText("Connection profile selected");
            }
        });
    }

    private Node createTopBar() {
        Label brand = new Label("REMOTE WORKBENCH");
        brand.getStyleClass().add("brand-label");

        Label subtitle = new Label("SSH / SFTP");
        subtitle.getStyleClass().add("subtitle-label");

        HBox brandBox = new HBox(10, brand, subtitle);
        brandBox.setAlignment(Pos.CENTER_LEFT);

        Button newConnectionButton = new Button("New Connection");
        newConnectionButton.getStyleClass().add("primary-button");
        newConnectionButton.setOnAction(event -> showNewConnectionDialog());

        Button settingsButton = new Button("Settings");
        settingsButton.getStyleClass().add("secondary-button");
        settingsButton.setTooltip(new Tooltip("Application settings"));

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox topBar = new HBox(14, brandBox, spacer, newConnectionButton, settingsButton);
        topBar.setAlignment(Pos.CENTER_LEFT);
        topBar.getStyleClass().add("top-bar");
        return topBar;
    }

    private Node createConnectionSidebar() {
        Label title = new Label("CONNECTIONS");
        title.getStyleClass().add("section-label");

        Button addButton = new Button("+  Add Connection");
        addButton.getStyleClass().add("sidebar-action");
        addButton.setMaxWidth(Double.MAX_VALUE);
        addButton.setOnAction(event -> showNewConnectionDialog());

        connectionList.getStyleClass().add("connection-list");
        connectionList.setPlaceholder(new Label("No connections yet"));
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
        Label title = new Label("Remote workspace");
        title.getStyleClass().add("workspace-title");

        Label description = new Label(
                "Select a connection to browse remote files, open a terminal, and manage transfers."
        );
        description.setWrapText(true);
        description.getStyleClass().add("workspace-description");

        Button createButton = new Button("Create your first connection");
        createButton.getStyleClass().add("primary-button");
        createButton.setOnAction(event -> showNewConnectionDialog());

        VBox welcome = new VBox(14, title, description, createButton);
        welcome.setAlignment(Pos.CENTER);
        welcome.setMaxWidth(480);
        welcome.getStyleClass().add("welcome-card");

        StackPane overview = new StackPane(welcome);
        overview.getStyleClass().add("workspace-overview");

        Tab overviewTab = new Tab("Overview", overview);
        overviewTab.setClosable(false);

        TabPane tabs = new TabPane(overviewTab);
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        tabs.getStyleClass().add("workspace-tabs");
        return tabs;
    }

    private Node createStatusBar() {
        Label connectionStatus = new Label("SSH: disconnected");
        connectionStatus.getStyleClass().add("status-muted");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox statusBar = new HBox(16, activeConnectionLabel, spacer, statusLabel, connectionStatus);
        statusBar.setAlignment(Pos.CENTER_LEFT);
        statusBar.getStyleClass().add("status-bar");
        return statusBar;
    }

    private void showNewConnectionDialog() {
        Dialog<ConnectionProfile> dialog = new Dialog<>();
        dialog.setTitle("New SSH Connection");
        dialog.setHeaderText("Add a server connection profile");

        ButtonType saveButtonType = new ButtonType("Save Profile", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(saveButtonType, ButtonType.CANCEL);

        TextField nameField = new TextField();
        nameField.setPromptText("Production server");

        TextField hostField = new TextField();
        hostField.setPromptText("example.com");

        Spinner<Integer> portSpinner = new Spinner<>();
        portSpinner.setValueFactory(new SpinnerValueFactory.IntegerSpinnerValueFactory(1, 65535, 22));
        portSpinner.setEditable(true);

        TextField usernameField = new TextField();
        usernameField.setPromptText("root");

        GridPane form = new GridPane();
        form.setHgap(12);
        form.setVgap(12);
        form.setPadding(new Insets(10, 0, 4, 0));

        ColumnConstraints labelColumn = new ColumnConstraints();
        labelColumn.setMinWidth(90);
        ColumnConstraints inputColumn = new ColumnConstraints();
        inputColumn.setHgrow(Priority.ALWAYS);
        form.getColumnConstraints().addAll(labelColumn, inputColumn);

        form.add(new Label("Name"), 0, 0);
        form.add(nameField, 1, 0);
        form.add(new Label("Host"), 0, 1);
        form.add(hostField, 1, 1);
        form.add(new Label("Port"), 0, 2);
        form.add(portSpinner, 1, 2);
        form.add(new Label("Username"), 0, 3);
        form.add(usernameField, 1, 3);

        dialog.getDialogPane().setContent(form);
        dialog.getDialogPane().setMinWidth(420);

        Node saveButton = dialog.getDialogPane().lookupButton(saveButtonType);
        saveButton.addEventFilter(javafx.event.ActionEvent.ACTION, event -> {
            if (nameField.getText().isBlank()
                    || hostField.getText().isBlank()
                    || usernameField.getText().isBlank()) {
                statusLabel.setText("Name, host, and username are required");
                event.consume();
            }
        });

        dialog.setResultConverter(button -> {
            if (button != saveButtonType) {
                return null;
            }
            return new ConnectionProfile(
                    nameField.getText(),
                    hostField.getText(),
                    portSpinner.getValue(),
                    usernameField.getText()
            );
        });

        dialog.showAndWait().ifPresent(profile -> {
            connectionProfiles.add(profile);
            connectionList.getSelectionModel().select(profile);
            statusLabel.setText("Profile added. SSH connection is not started yet");
        });
    }
}
