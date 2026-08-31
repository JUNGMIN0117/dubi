import 'package:ddubi/app/router.dart';
import 'package:ddubi/app/theme.dart';
import 'package:flutter/material.dart';

class DdubiApp extends StatelessWidget {
  const DdubiApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp.router(
      title: '뚜비',
      debugShowCheckedModeBanner: false,
      theme: buildAppTheme(),
      routerConfig: appRouter,
    );
  }
}
