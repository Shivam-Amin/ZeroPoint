// File: lib/main.dart

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'dart:async';

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

  @override
  void initState() {
    super.initState();
    _setupMethodCallHandler();
    // Start broadcasting and initial discovery automatically
    WidgetsBinding.instance.addPostFrameCallback((_) {
      _startBroadcastAndDiscovery();
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
      // First start the broadcasting service
      await platform.invokeMethod('startBroadcast');

      // Then start discovery
      _startDiscovery();
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
      await platform.invokeMethod('startDiscovery');

      // Set timer to stop discovery after specified duration
      _discoveryTimer = Timer(Duration(seconds: _discoveryDuration), () {
        _stopDiscovery();
      });
    } on PlatformException catch (e) {
      setState(() {
        _statusMessage = 'Failed to start discovery: ${e.message}';
        _isDiscovering = false;
      });
    }
  }

  Future<void> _stopDiscovery() async {
    _discoveryTimer?.cancel();

    try {
      await platform.invokeMethod('stopDiscovery');
      setState(() {
        _statusMessage =
            'Discovery completed (${_discoveredDevices.length} devices found)';
        _isDiscovering = false;
      });
    } on PlatformException catch (e) {
      setState(() {
        _statusMessage = 'Failed to stop discovery: ${e.message}';
        _isDiscovering = false;
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('mDNS Device Discovery')),
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
                            ScaffoldMessenger.of(context).showSnackBar(
                              SnackBar(
                                content: Text(
                                  'Connecting to ${device['name']} will be implemented next',
                                ),
                              ),
                            );
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
    _stopDiscovery();
    super.dispose();
  }
}
