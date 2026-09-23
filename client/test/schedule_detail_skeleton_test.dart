import 'dart:async';

import 'package:ddubi/data/mock_repository.dart';
import 'package:ddubi/domain/auth.dart';
import 'package:ddubi/domain/models.dart';
import 'package:ddubi/presentation/screens.dart';
import 'package:ddubi/services/app_api.dart';
import 'package:ddubi/services/recommendation_api.dart';
import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

/// 일정 최초 조회만 늦추어 빈 화면 대신 카드 형태 로딩이 나오는지 확인한다.
class _SlowScheduleRepository extends MockDdubiRepository {
  final Completer<Schedule?> requested = Completer<Schedule?>();

  @override
  Future<Schedule?> scheduleById(String id) => requested.future;
}

class _SlowRefreshRecommendationApi extends RecommendationApi {
  _SlowRefreshRecommendationApi() : super(Dio());

  final Completer<RecommendationOverview?> refreshed =
      Completer<RecommendationOverview?>();
  int calls = 0;

  @override
  Future<RecommendationOverview?> getLatestOverview(String scheduleId) {
    calls++;
    return calls == 1 ? Future.value(null) : refreshed.future;
  }

  @override
  Future<List<SavedRouteSummary>> getSavedRoutes(String scheduleId) async =>
      const [];
}

void main() {
  testWidgets('최초 조회 중에는 애니메이션 스켈레톤, 완료 후에는 실제 카드를 보여준다', (tester) async {
    final repository = _SlowScheduleRepository();
    final schedule = MockDdubiRepository.scheduleData.first;
    await tester.pumpWidget(
      MaterialApp(
        home: ScheduleDetailScreen(
          repository: repository,
          routeRepository: repository,
          scheduleId: schedule.id,
          arrivalApi: ArrivalApi(Dio()),
          userApi: UserApi(Dio(), AuthSessionStore()),
        ),
      ),
    );

    expect(find.byKey(const Key('scheduleDetailSkeleton')), findsOneWidget);
    expect(find.byType(CircularProgressIndicator), findsNothing);
    repository.requested.complete(schedule);
    await tester.pumpAndSettle();
    expect(find.byKey(const Key('scheduleDetailSkeleton')), findsNothing);
    expect(find.byKey(const Key('scheduleRouteOverview')), findsOneWidget);
  });

  testWidgets('새로 계산하는 동안 이미 보이던 상세 정보는 스켈레톤으로 바꾸지 않는다', (tester) async {
    final repository = MockDdubiRepository();
    final api = _SlowRefreshRecommendationApi();
    final schedule = MockDdubiRepository.scheduleData.first;
    await tester.pumpWidget(
      MaterialApp(
        home: ScheduleDetailScreen(
          repository: repository,
          routeRepository: repository,
          recommendationApi: api,
          scheduleId: schedule.id,
          arrivalApi: ArrivalApi(Dio()),
          userApi: UserApi(Dio(), AuthSessionStore()),
        ),
      ),
    );
    await tester.pumpAndSettle();
    expect(find.byKey(const Key('scheduleRouteOverview')), findsOneWidget);
    expect(api.calls, 1);

    final reload = find.byKey(const Key('scheduleDetailReload'));
    await tester.scrollUntilVisible(reload, 220);
    await tester.tap(reload);
    await tester.pump();
    expect(api.calls, 2);
    expect(find.byKey(const Key('scheduleRouteOverview')), findsOneWidget);
    expect(find.byKey(const Key('scheduleDetailSkeleton')), findsNothing);

    api.refreshed.complete(null);
    await tester.pumpAndSettle();
  });
}
