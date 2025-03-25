// File: android/app/src/main/java/com/example/mdns_connect_app/MainActivity.java

package com.example.mdns_connect_app;

import androidx.annotation.NonNull;

import java.util.List;
import java.util.Map;

import io.flutter.embedding.android.FlutterActivity;
import io.flutter.embedding.engine.FlutterEngine;
import io.flutter.plugin.common.MethodChannel;

public class MainActivity extends FlutterActivity implements MDNSService.DeviceDiscoveryListener {
    private static final String CHANNEL = "com.example.mdns_connect_app/mdns";
    private MDNSService mdnsService;
    private MethodChannel channel;

    @Override
    public void configureFlutterEngine(@NonNull FlutterEngine flutterEngine) {
        super.configureFlutterEngine(flutterEngine);

        channel = new MethodChannel(flutterEngine.getDartExecutor().getBinaryMessenger(), CHANNEL);
        mdnsService = new MDNSService(this);

        channel.setMethodCallHandler((call, result) -> {
            switch (call.method) {
                case "startBroadcast":
                    mdnsService.startBroadcast();
                    result.success(true);
                    break;
                case "startDiscovery":
                    mdnsService.startDiscovery();
                    result.success(true);
                    break;
                case "stopDiscovery":
                    mdnsService.stopDiscovery();
                    result.success(true);
                    break;
                case "stopService":
                    mdnsService.stopService();
                    result.success(true);
                    break;
                default:
                    result.notImplemented();
                    break;
            }
        });
    }

    @Override
    public void onDeviceDiscovered(List<Map<String, Object>> devices) {
        runOnUiThread(() -> {
            if (channel != null) {
                channel.invokeMethod("onDevicesDiscovered", devices);
            }
        });
    }

    @Override
    protected void onDestroy() {
        if (mdnsService != null) {
            mdnsService.stopService();
        }
        super.onDestroy();
    }
}