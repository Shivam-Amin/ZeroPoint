import 'package:flutter/material.dart';

class Home extends StatefulWidget {
  final String deviceName;
  const Home({super.key, required this.deviceName});

  @override
  State<Home> createState() => _HomeState();
}

class _HomeState extends State<Home> {
  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        backgroundColor: const Color.fromARGB(48, 26, 26, 26),
        titleSpacing: 0,
        title: Text(
          widget.deviceName,
          style: TextStyle(
            fontSize: 18,
          ),
        )
      ),
    );
  }
}