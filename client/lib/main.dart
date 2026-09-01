import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import 'app.dart';
import 'core/maps/kakao_map_config.dart';

Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();
  await KakaoMapConfig.initialize();
  runApp(const ProviderScope(child: DdubiApp()));
}
