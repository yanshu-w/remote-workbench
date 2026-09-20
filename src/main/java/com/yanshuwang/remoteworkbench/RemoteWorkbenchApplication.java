package com.yanshuwang.remoteworkbench;

import com.yanshuwang.remoteworkbench.ui.MainView;
import javafx.application.Application;
import javafx.scene.Scene;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.stage.Stage;

public final class RemoteWorkbenchApplication extends Application {
    private static final double INITIAL_WIDTH = 1280;
    private static final double INITIAL_HEIGHT = 800;
    private MainView mainView;

    @Override
    public void start(Stage stage) {
        com.yanshuwang.remoteworkbench.ui.theme.ThemeManager.init();
        mainView = new MainView();
        Scene scene = new Scene(mainView, INITIAL_WIDTH, INITIAL_HEIGHT);
        scene.getStylesheets().add(
                getClass().getResource("/com/yanshuwang/remoteworkbench/application.css").toExternalForm()
        );
        scene.getAccelerators().put(
                new KeyCodeCombination(KeyCode.COMMA, KeyCombination.SHORTCUT_DOWN),
                mainView::showSettingsDialog
        );

        stage.setTitle("远程工作台");
        stage.setMinWidth(960);
        stage.setMinHeight(620);
        stage.setScene(scene);
        stage.show();
    }

    @Override
    public void stop() {
        if (mainView != null) {
            mainView.close();
        }
    }

    public static void main(String[] args) {
        launch(args);
    }
}
