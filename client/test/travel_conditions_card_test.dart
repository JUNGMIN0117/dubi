import 'package:ddubi/domain/travel_conditions.dart';
import 'package:ddubi/presentation/travel_conditions_card.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

/// 일정 상세의 "오늘의 이동 변수" 칸.
///
/// 예전에는 네 가지를 한 줄로 묶어 "아직 확인되지 않았어요" 라고만 했다. 날씨만 들어와도
/// 문구가 그대로라 무엇이 되고 무엇이 안 되는지 알 수 없었다. 칸을 넷으로 나눈 뒤로는
/// **값이 있는 칸과 없는 칸이 한눈에 갈려야 한다.**
///
/// 여기서 지키는 규칙 하나: 값이 없는 칸을 0 이나 "맑음" 같은 그럴듯한 값으로 채우지 않는다.
class _StubService implements TravelConditionsService {
  _StubService(this._result);

  final TravelConditions _result;
  var calls = 0;

  @override
  Future<TravelConditions> conditionsFor(String scheduleId) async {
    calls++;
    return _result;
  }
}

class _FailingService implements TravelConditionsService {
  @override
  Future<TravelConditions> conditionsFor(String scheduleId) async =>
      throw Exception('서버 없음');
}

Future<void> pumpCard(
  WidgetTester tester, {
  TravelConditionsService? service,
}) async {
  await tester.pumpWidget(
    MaterialApp(
      home: Scaffold(
        body: TravelConditionsCard(scheduleId: 's1', service: service),
      ),
    ),
  );
  await tester.pumpAndSettle();
}

void main() {
  testWidgets('통로가 없으면 네 칸 모두 확인 안 됨으로 보인다', (tester) async {
    await pumpCard(tester);

    expect(find.text('날씨'), findsOneWidget);
    expect(find.text('혼잡도'), findsOneWidget);
    expect(find.text('도로 통제'), findsOneWidget);
    expect(find.text('지하철'), findsOneWidget);
    expect(find.text('확인 안 됨'), findsNWidgets(4));
  });

  testWidgets('값이 없어도 계산에는 영향이 없다고 알린다', (tester) async {
    await pumpCard(tester);

    expect(find.textContaining('계산 결과에는 영향이 없어요'), findsOneWidget);
  });

  testWidgets('값이 있는 칸만 값으로 바뀐다 — 나머지는 채우지 않는다', (tester) async {
    final service = _StubService(
      const TravelConditions(
        weather: TravelConditionItem.value('20.9℃ · 비 안 옴', note: '종로구'),
        roadBlock: TravelConditionItem.value('봉은교 양방향 전면통제'),
      ),
    );

    await pumpCard(tester, service: service);

    expect(service.calls, 1);
    expect(find.text('20.9℃ · 비 안 옴'), findsOneWidget);
    expect(find.text('종로구'), findsOneWidget);
    expect(find.text('봉은교 양방향 전면통제'), findsOneWidget);
    // 혼잡도와 지하철은 값이 없다. 0 이나 "보통" 으로 채우면 안 된다.
    expect(find.text('확인 안 됨'), findsNWidgets(2));
  });

  testWidgets('값이 하나라도 있으면 안내 문구를 빼서 자리를 아낀다', (tester) async {
    await pumpCard(
      tester,
      service: _StubService(
        const TravelConditions(
          weather: TravelConditionItem.value('20.9℃'),
        ),
      ),
    );

    expect(find.textContaining('계산 결과에는 영향이 없어요'), findsNothing);
  });

  testWidgets('관측 시각이 오면 기준 시각을 붙인다', (tester) async {
    await pumpCard(
      tester,
      service: _StubService(
        TravelConditions(
          weather: const TravelConditionItem.value('20.9℃'),
          observedAt: DateTime(2026, 9, 22, 8, 5),
        ),
      ),
    );

    expect(find.text('08:05 기준'), findsOneWidget);
  });

  testWidgets('조회가 실패해도 카드는 남고 오류 화면을 띄우지 않는다', (tester) async {
    await pumpCard(tester, service: _FailingService());

    expect(find.text('오늘의 이동 변수'), findsOneWidget);
    expect(find.text('확인 안 됨'), findsNWidgets(4));
    expect(find.textContaining('불러오지 못했어요'), findsOneWidget);
  });

  testWidgets('긴 통제 문구가 와도 좁은 화면에서 넘치지 않는다', (tester) async {
    // 실제 서버 값이 이만큼 길다. 도로 통제 문구는 공사 안내가 통째로 들어온다.
    tester.view.physicalSize = const Size(360 * 3, 800 * 3);
    tester.view.devicePixelRatio = 3;
    addTearDown(tester.view.reset);

    await pumpCard(
      tester,
      service: _StubService(
        const TravelConditions(
          roadBlock: TravelConditionItem.value(
            '탄천나들목 및 봉은교 구조개선공사 — 양방향 전면통제',
            note: '2027-03-01 해제 예정',
          ),
        ),
      ),
    );

    expect(tester.takeException(), isNull);
  });

  group('서버 응답 읽기', () {
    test('빈 문자열은 값 없음으로 본다', () {
      final parsed = TravelConditions.fromJson({'weather': ''});
      expect(parsed.weather.available, isFalse);
    });

    test('available:false 는 값이 있어도 값 없음이다', () {
      final parsed = TravelConditions.fromJson({
        'weather': {'text': '맑음', 'available': false},
      });
      expect(parsed.weather.available, isFalse);
    });

    test('모르는 모양이 와도 네 칸이 확인 안 됨으로 남는다', () {
      final parsed = TravelConditions.fromJson('이상한 값');
      expect(parsed.hasAny, isFalse);
    });
  });
}
