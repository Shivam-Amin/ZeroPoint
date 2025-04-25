// File: lib/main.dart (modified for desktop platforms)

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:mdns_connect_app/pages/home.dart';
import 'dart:async';
import 'dart:io';
import 'services/desktop_mdns_discovery.dart'; // Import the desktop discovery service

void main() {
  runApp(const MyApp());
}

class MyApp extends StatelessWidget {
  const MyApp({Key? key}) : super(key: key);

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'mDNS Connect App',
      theme: ThemeData(primarySwatch: Colors.blue),
      home: const DeviceDiscoveryScreen(),
    );
  }
}

class DeviceDiscoveryScreen extends StatefulWidget {
  const DeviceDiscoveryScreen({Key? key}) : super(key: key);

  @override
  State<DeviceDiscoveryScreen> createState() => _DeviceDiscoveryScreenState();
}

class _DeviceDiscoveryScreenState extends State<DeviceDiscoveryScreen> {
  static const platform = MethodChannel('com.example.mdns_connect_app/mdns');

  final List<Map<String, dynamic>> _discoveredDevices = [];
  bool _isDiscovering = false;
  String _statusMessage = 'Starting service...';
  Timer? _discoveryTimer;
  final int _discoveryDuration = 10; // seconds to run discovery
  
  // Desktop mDNS discovery service
  DesktopMDNSDiscovery? _desktopDiscovery;

  @override
  void initState() {
    super.initState();
    
    if (Platform.isAndroid || Platform.isIOS) {
      _setupMethodCallHandler();
      // Start broadcasting and initial discovery automatically for mobile
      WidgetsBinding.instance.addPostFrameCallback((_) {
        _startBroadcastAndDiscovery();
      });
    } else {
      // For desktop platforms, only set up discovery
      _setupDesktopDiscovery();
    }
  }

  void _setupDesktopDiscovery() {
    _desktopDiscovery = DesktopMDNSDiscovery();
    
    // Initialize the desktop discovery service
    _desktopDiscovery!.initialize().then((_) {
      setState(() {
        _statusMessage = 'Ready to discover devices';
      });
      
      // Listen for device updates
      _desktopDiscovery!.devicesStream.listen((devices) {
        setState(() {
          _discoveredDevices.clear();
          for (var device in devices) {
            _discoveredDevices.add(device.toJson());
          }
        });
      });
    }).catchError((error) {
      setState(() {
        _statusMessage = 'Failed to initialize mDNS: $error';
      });
    });
  }

  void _setupMethodCallHandler() {
    platform.setMethodCallHandler((call) async {
      switch (call.method) {
        case 'onDevicesDiscovered':
          final List<dynamic> devices = call.arguments;
          setState(() {
            _discoveredDevices.clear();
            for (var device in devices) {
              _discoveredDevices.add(Map<String, dynamic>.from(device));
            }
          });
          break;
      }
    });
  }

  Future<void> _startBroadcastAndDiscovery() async {
    setState(() {
      _statusMessage = 'Starting service...';
    });

    try {
      if (Platform.isAndroid || Platform.isIOS) {
        // Mobile platforms: use the platform channel
        // First start the broadcasting service
        await platform.invokeMethod('startBroadcast');
        // Then start discovery
        _startDiscovery();
      }
    } on PlatformException catch (e) {
      setState(() {
        _statusMessage = 'Failed to start service: ${e.message}';
      });
    }
  }

  Future<void> _startDiscovery() async {
    // Cancel any existing timer
    _discoveryTimer?.cancel();

    setState(() {
      _statusMessage = 'Discovering devices...';
      _isDiscovering = true;
    });

    try {
      if (Platform.isAndroid || Platform.isIOS) {
        // Mobile platforms: use the platform channel
        await platform.invokeMethod('startDiscovery');
      } else {
        // Desktop platforms: use the Flutter mDNS discovery
        await _desktopDiscovery?.startDiscovery(duration: _discoveryDuration);
      }

      // Set timer to stop discovery after specified duration
      _discoveryTimer = Timer(Duration(seconds: _discoveryDuration), () {
        _stopDiscovery();
      });
    } catch (e) {
      setState(() {
        _statusMessage = 'Failed to start discovery: $e';
        _isDiscovering = false;
      });
    }
  }

  Future<void> _stopDiscovery() async {
    _discoveryTimer?.cancel();

    try {
      if (Platform.isAndroid || Platform.isIOS) {
        // Mobile platforms: use the platform channel
        await platform.invokeMethod('stopDiscovery');
      } else {
        // Desktop platforms: use the Flutter mDNS discovery
        _desktopDiscovery?.stopDiscovery();
      }
      
      setState(() {
        _statusMessage = 'Discovery completed (${_discoveredDevices.length} devices found)';
        _isDiscovering = false;
      });
    } catch (e) {
      setState(() {
        _statusMessage = 'Failed to stop discovery: $e';
        _isDiscovering = false;
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        backgroundColor: const Color.fromARGB(48, 26, 26, 26),
        title: const Text('ZeroPoint')),
      body: Column(
        children: [
          Padding(
            padding: const EdgeInsets.all(16.0),
            child: Row(
              mainAxisAlignment: MainAxisAlignment.spaceBetween,
              children: [
                Expanded(
                  child: Text(
                    _statusMessage,
                    style: const TextStyle(fontWeight: FontWeight.bold),
                  ),
                ),
                _isDiscovering
                    ? ElevatedButton.icon(
                      onPressed: null,
                      icon: const SizedBox(
                        width: 20,
                        height: 20,
                        child: CircularProgressIndicator(
                          strokeWidth: 2,
                          color: Colors.white,
                        ),
                      ),
                      label: const Text('Scanning...'),
                    )
                    : ElevatedButton.icon(
                      onPressed: _startDiscovery,
                      icon: const Icon(Icons.refresh),
                      label: const Text('Discover Devices'),
                    ),
              ],
            ),
          ),
          Expanded(
            child:
                _discoveredDevices.isEmpty
                    ? const Center(child: Text('No devices discovered yet'))
                    : ListView.builder(
                      itemCount: _discoveredDevices.length,
                      itemBuilder: (context, index) {
                        final device = _discoveredDevices[index];
                        return ListTile(
                          title: Text(device['name'].toString()),
                          subtitle: Text(
                            'IP: ${device['address']} | Port: ${device['port']}',
                          ),
                          trailing: const Icon(Icons.devices),
                          onTap: () {
                            // We'll implement connection functionality in the next step
                            if (Platform.isAndroid || Platform.isIOS) {
                              final String deviceName = device['name']?.toString() ?? 'Unknown Device';
                              Navigator.push(
                                context,
                                MaterialPageRoute(builder: (context) => Home(deviceName: deviceName)),
                              );
                            } else {
                              ScaffoldMessenger.of(context).showSnackBar(
                              SnackBar(
                                content: Text(
                                  'Connecting to ${device['name']} will be implemented next',
                                ),
                              ),
                            );
                            }
                          },
                        );
                      },
                    ),
          ),
        ],
      ),
    );
  }

  @override
  void dispose() {
    _discoveryTimer?.cancel();
    if (Platform.isAndroid || Platform.isIOS) {
      _stopDiscovery();
    } else {
      _desktopDiscovery?.dispose();
    }
    super.dispose();
  }
}