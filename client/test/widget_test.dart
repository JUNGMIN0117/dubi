import 'package:ddubi/app/app.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  testWidgets('Home에 Mock 추천 정보가 표시된다', (tester) async {
    tester.view.physicalSize = const Size(390, 844);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);

    await tester.pumpWidget(const ProviderScope(child: DdubiApp()));

    expect(find.text('뚜비'), findsOneWidget);
    expect(find.text('오전 10:00 SSAFY 프로젝트 회의'), findsOneWidget);
    expect(find.text('09:52'), findsOneWidget);
    expect(find.text('09:05'), findsOneWidget);
    expect(find.text('+8분'), findsOneWidget);
    expect(find.text('지하철 일부 지연'), findsOneWidget);

    final routeButton = find.widgetWithText(FilledButton, '추천 경로 보기');
    await tester.ensureVisible(routeButton);
    await tester.tap(routeButton);
    await tester.pumpAndSettle();

    expect(find.text('추천 ID: mock-recommendation-1'), findsOneWidget);
  });
}
