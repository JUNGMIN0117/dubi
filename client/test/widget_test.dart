import 'package:ddubi/app.dart';
import 'package:ddubi/domain/auth.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  test('Mock 회원가입 계정으로 즉시 로그인할 수 있다', () async {
    final repository = MockAuthRepository();
    await repository.signUp(email: 'new@ddubi.app', password: 'new-password');
    await expectLater(
      repository.login(email: 'new@ddubi.app', password: 'new-password'),
      completes,
    );
    await expectLater(
      repository.signUp(email: 'new@ddubi.app', password: 'again'),
      throwsA(isA<AuthException>()),
    );
  });

  testWidgets('로그인 화면과 핵심 진입 동작을 표시한다', (tester) async {
    await tester.pumpWidget(const DdubiApp());
    expect(find.text('뚜비'), findsOneWidget);
    expect(find.textContaining('카카오로 시작하기'), findsOneWidget);
    expect(find.textContaining('이메일로 시작하기'), findsOneWidget);
    await tester.tap(find.textContaining('이메일로 시작하기'));
    await tester.pumpAndSettle();
    expect(find.text('이메일로 로그인'), findsOneWidget);
    expect(find.text('이메일을 잊으셨나요?'), findsOneWidget);
    expect(find.text('비밀번호를 잊으셨나요?'), findsOneWidget);
    await tester.enterText(
      find.byType(TextFormField).at(0),
      'member@ddubi.app',
    );
    await tester.enterText(find.byType(TextFormField).at(1), 'password123');
    await tester.tap(find.text('로그인'));
    await tester.pumpAndSettle(const Duration(seconds: 1));
    expect(find.text('다음 중요 일정'), findsOneWidget);
    expect(find.text('추천 출발 시간'), findsOneWidget);
  });

  testWidgets('이메일 로그인은 잘못된 입력을 한국어로 안내한다', (tester) async {
    await tester.pumpWidget(const DdubiApp());
    await tester.tap(find.textContaining('이메일로 시작하기'));
    await tester.pumpAndSettle();
    await tester.enterText(find.byType(TextFormField).first, 'abc@');
    await tester.tap(find.text('로그인'));
    await tester.pump();
    expect(find.text('올바른 이메일 형식으로 입력해주세요.'), findsOneWidget);
    expect(find.text('비밀번호를 입력해주세요.'), findsOneWidget);
  });

  testWidgets('카카오 로그인은 공급자 인증 화면으로 이동한다', (tester) async {
    await tester.pumpWidget(const DdubiApp());
    await tester.tap(find.textContaining('카카오로 시작하기'));
    await tester.pumpAndSettle();
    expect(find.text('카카오 로그인'), findsOneWidget);
    expect(find.text('개발용 카카오 인증 완료'), findsOneWidget);
  });
}
