// File: lib/services/desktop_mdns_discovery.dart

import 'dart:async';
import 'dart:io';
import 'package:flutter/foundation.dart';
import 'package:multicast_dns/multicast_dns.dart';

class DesktopMDNSDiscovery {
  // Use the same service type as defined in your Android service
  // static const String _serviceType = '_mdnsconnect._udp.local';
  static const String _serviceType = '_mdnsconnect._tcp';
  
  final MDnsClient _mdnsClient = MDnsClient();
  final List<DeviceInfo> _discoveredDevices = [];
  bool _isInitialized = false;
  Timer? _discoveryTimer;
  
  // Stream controller for broadcasting device updates
  final StreamController<List<DeviceInfo>> _devicesController = 
      StreamController<List<DeviceInfo>>.broadcast();
  
  Stream<List<DeviceInfo>> get devicesStream => _devicesController.stream;
  List<DeviceInfo> get discoveredDevices => List.unmodifiable(_discoveredDevices);
  
  Future<void> initialize() async {
    if (_isInitialized) return;
    
    try {
      await _mdnsClient.start();
      _isInitialized = true;
      debugPrint('MDNS Client initialized successfully');
    } catch (e) {
      debugPrint('Failed to initialize MDNS Client: $e');
      rethrow;
    }
  }
  
  Future<void> startDiscovery({int duration = 10}) async {
    if (!_isInitialized) {
      await initialize();
    }
    
    // Clear previous discoveries
    _discoveredDevices.clear();
    _notifyListeners();
    
    debugPrint('Starting mDNS discovery for $_serviceType');
    
    try {
      // Cancel any existing timer
      _discoveryTimer?.cancel();
      
      // Query for service instances
      await for (final PtrResourceRecord ptr in _mdnsClient.lookup<PtrResourceRecord>(
        ResourceRecordQuery.serverPointer(_serviceType),
      )) {
        // Request service info
        await for (final SrvResourceRecord srv in _mdnsClient.lookup<SrvResourceRecord>(
          ResourceRecordQuery.service(ptr.domainName),
        )) {
          // Get IP addresses
          await for (final IPAddressResourceRecord ip in _mdnsClient.lookup<IPAddressResourceRecord>(
            ResourceRecordQuery.addressIPv4(srv.target),
          )) {
            // Get any TXT record for additional info
            // Map<String, String> txtData = {};
            // await for (final TxtResourceRecord txt in _mdnsClient.lookup<TxtResourceRecord>(
            //   ResourceRecordQuery.text(ptr.domainName),
            // )) {
            //   txtData = txt.text as Map<String, String>;
            //   break; // Just get the first one
            // }
            
            final device = DeviceInfo(
              name: ptr.domainName,
              address: ip.address.address,
              port: srv.port,
              // properties: txtData,
            );
            
            // Add device if not already in the list
            if (!_discoveredDevices.any((d) => d.name == device.name)) {
              _discoveredDevices.add(device);
              _notifyListeners();
            }
            
            break; // Just get the first IP
          }
        }
      }
      
      // Set timer to stop discovery after specified duration
      _discoveryTimer = Timer(Duration(seconds: duration), () {
        debugPrint('Discovery completed (${_discoveredDevices.length} devices found)');
      });
      
    } catch (e) {
      debugPrint('Error during mDNS discovery: $e');
      rethrow;
    }
  }
  
  void stopDiscovery() {
    _discoveryTimer?.cancel();
  }
  
  void dispose() {
    stopDiscovery();
    _mdnsClient.stop();
    _devicesController.close();
    _isInitialized = false;
  }
  
  void _notifyListeners() {
    if (!_devicesController.isClosed) {
      _devicesController.add(List.unmodifiable(_discoveredDevices));
    }
  }
}

class DeviceInfo {
  final String name;
  final String address;
  final int port;
  final Map<String, String> properties;
  
  DeviceInfo({
    required this.name,
    required this.address,
    required this.port,
    this.properties = const {},
  });
  
  @override
  String toString() {
    return 'DeviceInfo{name: $name, address: $address, port: $port, properties: $properties}';
  }
  
  // For easy conversion to the format used in your existing code
  Map<String, dynamic> toJson() {
    return {
      'name': name,
      'address': address,
      'port': port,
      ...properties,
    };
  }
}