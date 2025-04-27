// File: android/app/src/main/java/com/example/mdns_connect_app/MDNSService.java

package com.example.mdns_connect_app;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class MDNSService {
    private static final String TAG = "MDNSService";
    private static final String SERVICE_TYPE = "_mdnsconnect._tcp";  // Note: NSD uses _tcp instead of _udp.local.
    private static final int SERVICE_PORT = 55555;

    private NsdManager nsdManager;
    private NsdManager.RegistrationListener registrationListener;
    private NsdManager.DiscoveryListener discoveryListener;
    private NsdManager.ResolveListener resolveListener;
    private DeviceDiscoveryListener deviceDiscoveryListener;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private String serviceName;
    private final List<Map<String, Object>> discoveredDevices = new ArrayList<>();
    private boolean isDiscovering = false;
    private boolean isRegistered = false;
    private final Context context;

    public interface DeviceDiscoveryListener {
        void onDeviceDiscovered(List<Map<String, Object>> devices);
    }

    public MDNSService(Context context, DeviceDiscoveryListener listener) {
        this.context = context;
        this.deviceDiscoveryListener = listener;
        this.nsdManager = (NsdManager) context.getSystemService(Context.NSD_SERVICE);
    }

    public void startBroadcast() {
        if (isRegistered) {
            Log.d(TAG, "Broadcasting already started");
            return;
        }

        // Create the NsdServiceInfo object
        NsdServiceInfo serviceInfo = new NsdServiceInfo();
        
        // The name is how other devices will see your device
        serviceName = android.os.Build.MODEL + "-" + System.currentTimeMillis();
        serviceInfo.setServiceName(serviceName);
        serviceInfo.setServiceType(SERVICE_TYPE);
        serviceInfo.setPort(SERVICE_PORT);

        // Add any additional service attributes
        serviceInfo.setAttribute("deviceName", android.os.Build.MODEL);

        // Create registration listener
        registrationListener = new NsdManager.RegistrationListener() {
            @Override
            public void onServiceRegistered(NsdServiceInfo serviceInfo) {
                // Save the service name because Android may have changed it
                serviceName = serviceInfo.getServiceName();
                isRegistered = true;
                Log.d(TAG, "Service registered: " + serviceName);
            }

            @Override
            public void onRegistrationFailed(NsdServiceInfo serviceInfo, int errorCode) {
                isRegistered = false;
                Log.e(TAG, "Registration failed: " + errorCode);
            }

            @Override
            public void onServiceUnregistered(NsdServiceInfo serviceInfo) {
                isRegistered = false;
                Log.d(TAG, "Service unregistered: " + serviceName);
            }

            @Override
            public void onUnregistrationFailed(NsdServiceInfo serviceInfo, int errorCode) {
                Log.e(TAG, "Unregistration failed: " + errorCode);
            }
        };

        // Register the service
        nsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, registrationListener);
        Log.d(TAG, "Attempting to register service: " + serviceName);
    }

    public void startDiscovery() {
        if (isDiscovering) {
            Log.d(TAG, "Discovery already in progress");
            return;
        }

        // Clear previous discoveries
        synchronized (discoveredDevices) {
            discoveredDevices.clear();
            notifyDevicesUpdated();
        }

        // Initialize discovery listener
        discoveryListener = new NsdManager.DiscoveryListener() {
            @Override
            public void onDiscoveryStarted(String serviceType) {
                isDiscovering = true;
                Log.d(TAG, "Discovery started: " + serviceType);
            }

            @Override
            public void onServiceFound(NsdServiceInfo serviceInfo) {
                // Filter out our own service
                if (serviceName != null && serviceInfo.getServiceName().equals(serviceName)) {
                    Log.d(TAG, "Found our own service, ignoring: " + serviceInfo.getServiceName());
                    return;
                }

                Log.d(TAG, "Service found: " + serviceInfo.getServiceName());
                
                // Create a resolver for this service
                resolveService(serviceInfo);
            }

            @Override
            public void onServiceLost(NsdServiceInfo serviceInfo) {
                Log.d(TAG, "Service lost: " + serviceInfo.getServiceName());
                
                synchronized (discoveredDevices) {
                    discoveredDevices.removeIf(device -> 
                            device.get("name").equals(serviceInfo.getServiceName()));
                    notifyDevicesUpdated();
                }
            }

            @Override
            public void onDiscoveryStopped(String serviceType) {
                isDiscovering = false;
                Log.d(TAG, "Discovery stopped: " + serviceType);
            }

            @Override
            public void onStartDiscoveryFailed(String serviceType, int errorCode) {
                isDiscovering = false;
                Log.e(TAG, "Start discovery failed: " + errorCode);
            }

            @Override
            public void onStopDiscoveryFailed(String serviceType, int errorCode) {
                Log.e(TAG, "Stop discovery failed: " + errorCode);
            }
        };

        // Start discovery
        nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener);
    }

    private void resolveService(NsdServiceInfo serviceInfo) {
        resolveListener = new NsdManager.ResolveListener() {
            @Override
            public void onResolveFailed(NsdServiceInfo serviceInfo, int errorCode) {
                Log.e(TAG, "Resolve failed for " + serviceInfo.getServiceName() + ": " + errorCode);
                
                // Retry resolution after a delay if it's a timeout issue
                if (errorCode == NsdManager.FAILURE_ALREADY_ACTIVE) {
                    mainHandler.postDelayed(() -> resolveService(serviceInfo), 1000);
                }
            }

            @Override
            public void onServiceResolved(NsdServiceInfo resolvedService) {
                Log.d(TAG, "Resolved service: " + resolvedService.getServiceName() + 
                          " at " + resolvedService.getHost().getHostAddress() + 
                          ":" + resolvedService.getPort());
                
                // Create device info map
                Map<String, Object> deviceInfo = new HashMap<>();
                deviceInfo.put("name", resolvedService.getServiceName());
                deviceInfo.put("address", resolvedService.getHost().getHostAddress());
                deviceInfo.put("port", resolvedService.getPort());
                
                // Add device attributes if any
                Map<String, byte[]> attributes = resolvedService.getAttributes();
                if (attributes != null) {
                    for (String key : attributes.keySet()) {
                        byte[] value = attributes.get(key);
                        if (value != null) {
                            deviceInfo.put(key, new String(value));
                        }
                    }
                }
                
                synchronized (discoveredDevices) {
                    // Update if already exists, add if not
                    boolean exists = false;
                    for (int i = 0; i < discoveredDevices.size(); i++) {
                        if (discoveredDevices.get(i).get("name").equals(resolvedService.getServiceName())) {
                            discoveredDevices.set(i, deviceInfo);
                            exists = true;
                            break;
                        }
                    }

                    if (!exists) {
                        discoveredDevices.add(deviceInfo);
                    }
                    
                    notifyDevicesUpdated();
                }
            }
        };

        // Resolve the service
        try {
            nsdManager.resolveService(serviceInfo, resolveListener);
        } catch (Exception e) {
            Log.e(TAG, "Error resolving service", e);
        }
    }

    private void notifyDevicesUpdated() {
        if (deviceDiscoveryListener != null) {
            mainHandler.post(() -> {
                deviceDiscoveryListener.onDeviceDiscovered(new ArrayList<>(discoveredDevices));
            });
        }
    }

    public void stopDiscovery() {
        if (isDiscovering && nsdManager != null && discoveryListener != null) {
            try {
                nsdManager.stopServiceDiscovery(discoveryListener);
                isDiscovering = false;
                Log.d(TAG, "Stopped discovery");
            } catch (Exception e) {
                Log.e(TAG, "Error stopping discovery", e);
            }
            discoveryListener = null;
        }
    }

    public void stopBroadcast() {
        if (isRegistered && nsdManager != null && registrationListener != null) {
            try {
                nsdManager.unregisterService(registrationListener);
                isRegistered = false;
                Log.d(TAG, "Service unregistered");
            } catch (Exception e) {
                Log.e(TAG, "Error unregistering service", e);
            }
            registrationListener = null;
        }
    }

    public void stopService() {
        stopDiscovery();
        stopBroadcast();
    }
}