import 'package:flutter/material.dart';
import 'core/theme.dart';
import 'data/mock_repository.dart';
import 'presentation/screens.dart';

class DdubiApp extends StatelessWidget {
  const DdubiApp({super.key});
  @override
  Widget build(BuildContext context) => MaterialApp(
    title: '뚜비',
    debugShowCheckedModeBanner: false,
    theme: DdubiTheme.light,
    home: LoginScreen(repository: MockDdubiRepository()),
  );
}
