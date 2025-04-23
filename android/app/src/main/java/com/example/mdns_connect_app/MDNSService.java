// File: android/app/src/main/java/com/example/mdns_connect_app/MDNSService.java

package com.example.mdns_connect_app;

import android.util.Log;

import java.io.IOException;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.jmdns.JmDNS;
import javax.jmdns.ServiceEvent;
import javax.jmdns.ServiceInfo;
import javax.jmdns.ServiceListener;

public class MDNSService {
    private static final String TAG = "MDNSService";
    private static final String SERVICE_TYPE = "_mdnsconnect._udp.local.";
    private static final int SERVICE_PORT = 55555;

    private JmDNS jmDNS;
    private ServiceInfo serviceInfo;
    private ServiceListener serviceListener;
    private DeviceDiscoveryListener discoveryListener;

    private final List<Map<String, Object>> discoveredDevices = new ArrayList<>();
    private boolean isBroadcasting = false;

    public interface DeviceDiscoveryListener {
        void onDeviceDiscovered(List<Map<String, Object>> devices);
    }

    public MDNSService(DeviceDiscoveryListener listener) {
        this.discoveryListener = listener;
    }

    public void startBroadcast() {
        if (isBroadcasting) {
            Log.d(TAG, "Broadcasting already started");
            return;
        }

        new Thread(() -> {
            try {
                // Get the local IP address
                InetAddress localAddress = getLocalIpAddress();
                if (localAddress == null) {
                    Log.e(TAG, "Could not get local IP address");
                    return;
                }

                // Create JmDNS instance
                jmDNS = JmDNS.create(localAddress);

                // Register a service
                Map<String, String> properties = new HashMap<>();
                properties.put("deviceName", android.os.Build.MODEL);

                serviceInfo = ServiceInfo.create(
                        SERVICE_TYPE,
                        android.os.Build.MODEL + "-" + System.currentTimeMillis(),
                        SERVICE_PORT,
                        0,
                        0,
                        properties
                );

                jmDNS.registerService(serviceInfo);
                isBroadcasting = true;
                Log.d(TAG, "Service registered: " + serviceInfo.getName() + " on " + localAddress.getHostAddress());
            } catch (IOException e) {
                Log.e(TAG, "Error registering mDNS service", e);
            }
        }).start();
    }

    public void startDiscovery() {
        if (jmDNS == null) {
            Log.e(TAG, "JmDNS not initialized. Start broadcasting first.");
            return;
        }

        // Clear previous discoveries
        synchronized (discoveredDevices) {
            discoveredDevices.clear();
            if (discoveryListener != null) {
                discoveryListener.onDeviceDiscovered(new ArrayList<>(discoveredDevices));
            }
        }

        // Remove previous listener if exists
        if (serviceListener != null) {
            jmDNS.removeServiceListener(SERVICE_TYPE, serviceListener);
        }

        // Create and add new listener
        serviceListener = new ServiceListener() {
            @Override
            public void serviceAdded(ServiceEvent event) {
                Log.d(TAG, "Service added: " + event.getName());
                // Request more information about the service
                jmDNS.requestServiceInfo(event.getType(), event.getName());
            }

            @Override
            public void serviceRemoved(ServiceEvent event) {
                Log.d(TAG, "Service removed: " + event.getName());
                synchronized (discoveredDevices) {
                    discoveredDevices.removeIf(device ->
                            device.get("name").equals(event.getName()));

                    if (discoveryListener != null) {
                        discoveryListener.onDeviceDiscovered(new ArrayList<>(discoveredDevices));
                    }
                }
            }

            @Override
            public void serviceResolved(ServiceEvent event) {
                ServiceInfo info = event.getInfo();
                // Skip if no addresses are available
                if (info.getInet4Addresses() == null || info.getInet4Addresses().length == 0) {
                    return;
                }

                String address = info.getInet4Addresses()[0].getHostAddress();
                String name = info.getName();

                Log.d(TAG, "Service resolved: " + name + " at " + address);

                Map<String, Object> deviceInfo = new HashMap<>();
                deviceInfo.put("name", name);
                deviceInfo.put("address", address);
                deviceInfo.put("port", info.getPort());

                synchronized (discoveredDevices) {
                    // Update if already exists, add if not
                    boolean exists = false;
                    for (int i = 0; i < discoveredDevices.size(); i++) {
                        if (discoveredDevices.get(i).get("name").equals(name)) {
                            discoveredDevices.set(i, deviceInfo);
                            exists = true;
                            break;
                        }
                    }

                    if (!exists) {
                        discoveredDevices.add(deviceInfo);
                    }

                    if (discoveryListener != null) {
                        discoveryListener.onDeviceDiscovered(new ArrayList<>(discoveredDevices));
                    }
                }
            }
        };

        jmDNS.addServiceListener(SERVICE_TYPE, serviceListener);
        Log.d(TAG, "Started discovery for service type: " + SERVICE_TYPE);
    }

    public void stopDiscovery() {
        if (jmDNS != null && serviceListener != null) {
            jmDNS.removeServiceListener(SERVICE_TYPE, serviceListener);
            serviceListener = null;
            Log.d(TAG, "Stopped discovery");
        }
    }

    public void stopService() {
        if (jmDNS != null) {
            try {
                stopDiscovery();

                if (serviceInfo != null) {
                    jmDNS.unregisterService(serviceInfo);
                    serviceInfo = null;
                }

                jmDNS.close();
                jmDNS = null;
                isBroadcasting = false;

                Log.d(TAG, "mDNS service stopped");
            } catch (IOException e) {
                Log.e(TAG, "Error stopping mDNS service", e);
            }
        }
    }

    private InetAddress getLocalIpAddress() {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface networkInterface = interfaces.nextElement();
                if (networkInterface.isLoopback() || !networkInterface.isUp()) {
                    continue;
                }

                Enumeration<InetAddress> addresses = networkInterface.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress address = addresses.nextElement();
                    if (!address.isLoopbackAddress() && address.getHostAddress().contains(".")) {
                        return address;
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error getting local IP address", e);
        }
        return null;
    }
}