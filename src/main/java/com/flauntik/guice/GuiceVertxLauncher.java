package com.flauntik.guice;

import io.vertx.core.Launcher;

public class GuiceVertxLauncher extends Launcher {

    public static void main(String[] args) {
        new GuiceVertxLauncher().dispatch(args);
    }
}


