package com.wifi.toolbox;

interface IToolboxCallback {
    void onOutput(String line);
    void onFinished(String allOutput, int exitCode);
}