import 'dart:convert';
import 'dart:io';

import 'package:ddubi/domain/schedule_evaluation.dart';
import 'package:ddubi/presentation/schedule_evaluation_card.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

Future<ScheduleEvaluationV2> _fixture(String name) async {
  final raw = await File('test/fixtures/schedule_evaluation_$name.json')
      .readAsString();
  return ScheduleEvaluationV2.fromJson(jsonDecode(raw) as Map<String, dynamic>);
}

Widget _summary(ScheduleEvaluationV2 evaluation) => MaterialApp(
  home: Scaffold(body: ScheduleEvaluationSummary(evaluation: evaluation)),
);

class _FakeEvaluationRepository implements ScheduleEvaluationRepository {
  _FakeEvaluationRepository(this.responses, {this.acceptFailure});

  final List<ScheduleEvaluationV2> responses;
  final ScheduleEvaluationFailure? acceptFailure;
  var latestCalls = 0;
  var acceptCalls = 0;

  @override
  Future<ScheduleEvaluationV2> latestEvaluation(String scheduleId) async {
    final index = latestCalls.clamp(0, responses.length - 1);
    latestCalls++;
    return responses[index];
  }

  @override
  Future<void> acceptEvaluation(String id, String evaluationId) async {
    acceptCalls++;
    if (acceptFailure != null) throw acceptFailure!;
  }

  @override
  Future<void> confirmDeparture(String id) async {}

  @override
  Future<void> refreshEvaluation(String id) async {}
}

class _FailOnceEvaluationRepository implements ScheduleEvaluationRepository {
  _FailOnceEvaluationRepository(this.success);

  final ScheduleEvaluationV2 success;
  var latestCalls = 0;

  @override
  Future<ScheduleEvaluationV2> latestEvaluation(String scheduleId) async {
    latestCalls++;
    if (latestCalls == 1) {
      throw const ScheduleEvaluationFailure(
        kind: EvaluationFailureKind.network,
        message: 'network',
      );
    }
    return success;
  }

  @override
  Future<void> acceptEvaluation(String id, String evaluationId) async {}

  @override
  Future<void> confirmDeparture(String id) async {}

  @override
  Future<void> refreshEvaluation(String id) async {}
}

void main() {
  late Map<String, ScheduleEvaluationV2> fixtures;

  setUpAll(() async {
    fixtures = {
      for (final name in [
        'keep',
        'advance',
        'margin',
        'late',
        'data',
        'search',
      ])
        name: await _fixture(name),
    };
  });

  testWidgets('결정별 문구를 서로 다른 의미로 표시한다', (tester) async {
    final cases = <String, String>{
      'keep': '현재 출발 계획을 유지해도 일정 전에 도착할 것으로 예상됩니다.',
      'advance': '제안 시각에 출발하면 일정 10분 전 도착이 예상됩니다.',
      'margin': '10분 여유 확보는 어렵지만 일정 전 도착 가능한 경로입니다.',
      'late': '현재 확인 가능한 경로로는 일정 시작 이후 도착이 예상됩니다.',
      'data': '최신 도착 예측에 필요한 정보가 부족합니다.',
      'search': '제한 시간 안에 탐색을 완료하지 못했습니다. 확인된 대안만 표시합니다.',
    };

    for (final entry in cases.entries) {
      await tester.pumpWidget(_summary(fixtures[entry.key]!));
      expect(find.text(entry.value), findsOneWidget, reason: entry.key);
    }
  });

  testWidgets('도보 역할과 품질 coverage를 사용자에게 구분해 표시한다', (tester) async {
    await tester.pumpWidget(_summary(fixtures['keep']!));

    expect(find.textContaining('접근·이탈 도보 경로 반영'), findsOneWidget);
    expect(find.textContaining('접근 도보 · 집 → 정류장'), findsOneWidget);
    expect(find.textContaining('환승 도보 · 환승역 → 승강장'), findsOneWidget);
    expect(find.textContaining('이탈 도보 · 도착역 → 회사'), findsOneWidget);
  });

  testWidgets('PREVIEW 결과는 표시하되 제안 적용을 비활성화한다', (tester) async {
    final preview = fixtures['data']!;
    final repository = _FakeEvaluationRepository([preview]);

    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: ScheduleEvaluationCard(
            repository: repository,
            scheduleId: preview.scheduleId,
          ),
        ),
      ),
    );
    await tester.pumpAndSettle();

    expect(find.text('검증용'), findsOneWidget);
    final button = tester.widget<FilledButton>(
      find.byKey(const Key('acceptEvaluationButton')),
    );
    expect(button.onPressed, isNull);
    await tester.pumpWidget(const SizedBox.shrink());
  });

  testWidgets('409 수락 충돌 후 최신 평가를 다시 조회하고 자동 수락하지 않는다', (tester) async {
    final first = fixtures['keep']!;
    final latest = fixtures['advance']!;
    final repository = _FakeEvaluationRepository(
      [first, latest],
      acceptFailure: const ScheduleEvaluationFailure(
        kind: EvaluationFailureKind.staleEvaluation,
        message: 'stale',
      ),
    );

    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: ScheduleEvaluationCard(
            repository: repository,
            scheduleId: 'schedule-1',
          ),
        ),
      ),
    );
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key('acceptEvaluationButton')));
    await tester.pumpAndSettle();

    expect(repository.acceptCalls, 1);
    expect(repository.latestCalls, 2);
    expect(find.text('추천이 변경됐습니다. 최신 결과를 다시 확인해 주세요.'), findsOneWidget);
    expect(find.textContaining('제안 시각에 출발하면'), findsOneWidget);
    await tester.pumpWidget(const SizedBox.shrink());
  });

  testWidgets('네트워크 실패 후 첫 재조회는 30초 backoff를 적용한다', (tester) async {
    final repository = _FailOnceEvaluationRepository(fixtures['keep']!);
    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: ScheduleEvaluationCard(
            repository: repository,
            scheduleId: 'schedule-1',
          ),
        ),
      ),
    );
    await tester.pump();

    expect(repository.latestCalls, 1);
    await tester.pump(const Duration(seconds: 29));
    expect(repository.latestCalls, 1);
    await tester.pump(const Duration(seconds: 1));
    await tester.pump();
    expect(repository.latestCalls, 2);
    expect(find.text('평가가 완료됐습니다.'), findsOneWidget);
    await tester.pumpWidget(const SizedBox.shrink());
  });
}
