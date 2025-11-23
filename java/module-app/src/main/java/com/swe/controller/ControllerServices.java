package com.swe.controller;

import functionlibrary.CloudFunctionLibrary;
import com.swe.core.Context;
import com.swe.canvas.CanvasManager;

public class ControllerServices {
    private static ControllerServices instance;

    public NetworkingInterface networking;
    public CloudFunctionLibrary cloud;
    public Context context;
    public CanvasManager canvasManager;

    private ControllerServices() {
        context = Context.getInstance();
    }

    public static ControllerServices getInstance() {
        if (instance == null)
            instance = new ControllerServices();
        return instance;
    }
}




