package com.wifi.toolbox;

import android.net.wifi.WifiConfiguration;
import android.os.Bundle;
import com.wifi.toolbox.IToolboxCallback;

interface IToolboxService {
    int getUid();
    void pressPowerKey();
    void setWifiEnabled(boolean enabled);
    void forgetNetwork(int netId);
    void setMediaVolumeMax();
    int connectToWifi(String ssid, String password);
    void enableNetwork(int netId);
    void disconnectWifi();
    boolean startWifiScan(boolean allowUseCommand);
    List<Bundle> getSavedWifiList();
    int executeCommand(String command, IToolboxCallback callback);
    int getNetIdBySsid(String ssid);
    List<Bundle> getWifiScanResults();
    void stopCommand(int taskId);
}