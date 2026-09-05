import 'package:flutter/material.dart';

void main() {
  runApp(const UncryApp());
}

class UncryApp extends StatelessWidget {
  const UncryApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      debugShowCheckedModeBanner: false,
      title: 'Uncry',
      theme: ThemeData(
        colorScheme: ColorScheme.fromSeed(seedColor: Colors.black),
        useMaterial3: true,
      ),
      home: const Scaffold(
        body: Center(child: Text('Uncry', style: TextStyle(fontSize: 14))),
      ),
    );
  }
}
