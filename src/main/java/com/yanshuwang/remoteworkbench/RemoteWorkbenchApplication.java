package com.yanshuwang.remoteworkbench;

import com.yanshuwang.remoteworkbench.ui.MainView;
import javafx.application.Application;
import javafx.scene.Scene;
import javafx.stage.Stage;

public final class RemoteWorkbenchApplication extends Application {
    private static final double INITIAL_WIDTH = 1280;
    private static final double INITIAL_HEIGHT = 800;

    @Override
    public void start(Stage stage) {
        MainView mainView = new MainView();
        Scene scene = new Scene(mainView, INITIAL_WIDTH, INITIAL_HEIGHT);
        scene.getStylesheets().add(
                getClass().getResource("/com/yanshuwang/remoteworkbench/application.css").toExternalForm()
        );

        stage.setTitle("Remote Workbench");
        stage.setMinWidth(960);
        stage.setMinHeight(620);
        stage.setScene(scene);
        stage.show();
    }

    public static void main(String[] args) {
        launch(args);
    }
}
