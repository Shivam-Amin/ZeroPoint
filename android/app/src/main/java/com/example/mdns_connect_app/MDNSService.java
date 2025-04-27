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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class MDNSService {
    private static final String TAG = "MDNSService";
    private static final String SERVICE_TYPE = "_mdnsconnect._tcp";
    private static final int SERVICE_PORT = 55555;

    private NsdManager nsdManager;
    private NsdManager.RegistrationListener registrationListener;
    private NsdManager.DiscoveryListener discoveryListener;
    private DeviceDiscoveryListener deviceDiscoveryListener;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private String serviceName;
    private String ownDeviceIp;
    private final Map<String, Map<String, Object>> discoveredDevicesByIp = new HashMap<>();
    private final Set<String> resolveInProgress = new HashSet<>();
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
        
        // Create a more stable service name by removing the timestamp
        serviceName = android.os.Build.MODEL + "-device";
        serviceInfo.setServiceName(serviceName);
        serviceInfo.setServiceType(SERVICE_TYPE);
        serviceInfo.setPort(SERVICE_PORT);

        // Add any additional service attributes
        serviceInfo.setAttribute("deviceName", android.os.Build.MODEL);
        serviceInfo.setAttribute("deviceId", getDeviceUniqueId());

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

    // Generate a unique ID for this device that's persistent across app restarts
    private String getDeviceUniqueId() {
        // Use Android ID or some other unique identifier that doesn't change
        return android.provider.Settings.Secure.getString(
            context.getContentResolver(), 
            android.provider.Settings.Secure.ANDROID_ID
        );
    }

    public void startDiscovery() {
        if (isDiscovering) {
            Log.d(TAG, "Discovery already in progress");
            return;
        }

        // Clear previous discoveries
        synchronized (discoveredDevicesByIp) {
            discoveredDevicesByIp.clear();
            resolveInProgress.clear();
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
                String foundName = serviceInfo.getServiceName();
                
                // Skip our own service if we recognize it
                if (serviceName != null && foundName.equals(serviceName)) {
                    Log.d(TAG, "Found our own service, ignoring: " + foundName);
                    return;
                }
                
                Log.d(TAG, "Service found: " + foundName);

                // Avoid resolving the same service multiple times simultaneously
                synchronized (resolveInProgress) {
                    if (resolveInProgress.contains(foundName)) {
                        Log.d(TAG, "Already resolving service: " + foundName);
                        return;
                    }
                    resolveInProgress.add(foundName);
                }
                
                // Create a resolver for this service
                resolveService(serviceInfo);
            }

            @Override
            public void onServiceLost(NsdServiceInfo serviceInfo) {
                String lostName = serviceInfo.getServiceName();
                Log.d(TAG, "Service lost: " + lostName);
                
                // We don't remove devices by name anymore since we're tracking by IP
                // We'll let devices age out if they don't reappear in future discoveries
                
                synchronized (resolveInProgress) {
                    resolveInProgress.remove(lostName);
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
        final String serviceToResolve = serviceInfo.getServiceName();
        
        NsdManager.ResolveListener resolveListener = new NsdManager.ResolveListener() {
            @Override
            public void onResolveFailed(NsdServiceInfo serviceInfo, int errorCode) {
                Log.e(TAG, "Resolve failed for " + serviceToResolve + ": " + errorCode);
                
                synchronized (resolveInProgress) {
                    resolveInProgress.remove(serviceToResolve);
                }
                
                // Retry resolution after a delay if it's a timeout issue
                if (errorCode == NsdManager.FAILURE_ALREADY_ACTIVE) {
                    mainHandler.postDelayed(() -> {
                        synchronized (resolveInProgress) {
                            if (!resolveInProgress.contains(serviceToResolve)) {
                                resolveInProgress.add(serviceToResolve);
                                resolveService(serviceInfo);
                            }
                        }
                    }, 1000);
                }
            }

            @Override
            public void onServiceResolved(NsdServiceInfo resolvedService) {
                String resolvedName = resolvedService.getServiceName();
                String hostAddress = resolvedService.getHost().getHostAddress();
                int port = resolvedService.getPort();
                
                Log.d(TAG, "Resolved service: " + resolvedName +
                      " at " + hostAddress + ":" + port);
                
                synchronized (resolveInProgress) {
                    resolveInProgress.remove(serviceToResolve);
                }
                
                // Save our own IP address if this is our service
                if (serviceName != null && resolvedName.equals(serviceName)) {
                    ownDeviceIp = hostAddress;
                    Log.d(TAG, "Identified own device IP: /////////////" + ownDeviceIp);
                    return;
                }
                
                // Skip our own service by checking IP
                if (ownDeviceIp != null && hostAddress.equals(ownDeviceIp)) {
                    Log.d(TAG, "Skipping our own service based on IP: " + hostAddress);
                    return;
                }
                
                // Read device attributes
                Map<String, String> attributeMap = new HashMap<>();
                Map<String, byte[]> attributes = resolvedService.getAttributes();
                if (attributes != null) {
                    for (String key : attributes.keySet()) {
                        byte[] value = attributes.get(key);
                        if (value != null) {
                            attributeMap.put(key, new String(value));
                        }
                    }
                }
                
                // Try to get a device ID from attributes
                String deviceId = attributeMap.get("deviceId");
                String deviceName = attributeMap.get("deviceName");
                if (deviceName == null) {
                    deviceName = resolvedName;
                }
                
                // Create or update device info map
                Map<String, Object> deviceInfo = new HashMap<>();
                deviceInfo.put("name", deviceName);
                deviceInfo.put("address", hostAddress);
                deviceInfo.put("port", port);
                deviceInfo.putAll(attributeMap);
                
                // Use IP as key if no device ID available
                String deviceKey = deviceId != null ? deviceId : hostAddress;
                
                synchronized (discoveredDevicesByIp) {
                    // Store or update the device by its IP
                    discoveredDevicesByIp.put(deviceKey, deviceInfo);
                    notifyDevicesUpdated();
                }
            }
        };

        // Resolve the service
        try {
            nsdManager.resolveService(serviceInfo, resolveListener);
        } catch (Exception e) {
            Log.e(TAG, "Error resolving service: " + e.getMessage());
            synchronized (resolveInProgress) {
                resolveInProgress.remove(serviceToResolve);
            }
        }
    }

    private void notifyDevicesUpdated() {
        if (deviceDiscoveryListener != null) {
            List<Map<String, Object>> devicesList;
            synchronized (discoveredDevicesByIp) {
                devicesList = new ArrayList<>(discoveredDevicesByIp.values());
            }
            
            mainHandler.post(() -> {
                deviceDiscoveryListener.onDeviceDiscovered(devicesList);
            });
        }
    }

    public void stopDiscovery() {
        if (isDiscovering && nsdManager != null && discoveryListener != null) {
            try {
                nsdManager.stopServiceDiscovery(discoveryListener);
                isDiscovering = false;
                
                synchronized (resolveInProgress) {
                    resolveInProgress.clear();
                }
                
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