import 'package:ddubi/domain/models.dart';
import 'package:ddubi/services/api_schedule_repository.dart';
import 'package:ddubi/services/recommendation_api.dart';
import 'package:ddubi/services/schedule_api.dart';
import 'package:dio/dio.dart';
import 'package:flutter_test/flutter_test.dart';

const _evaluationData = <String, dynamic>{
  'schemaVersion': 'schedule-evaluation-v2',
  'evaluationId': 'eval-1',
  'scheduleId': 'schedule-1',
  'state': 'READY',
  'decision': 'KEEP_DEPARTURE',
  'proposedDepartAt': '2026-09-18T08:10:00+09:00',
  'estimatedArrivalAt': '2026-09-18T08:42:00+09:00',
  'quality': {
    'preview': false,
    'productionEligible': true,
    'realtimeApplied': true,
    'walkingApiApplied': true,
    'walkingApiCoverage': 'BOTH',
    'costCoverage': 'COMPLETE',
    'missingInputs': <Object>[],
    'fallbackReasons': <Object>[],
  },
  'validUntil': '2099-09-18T00:00:00Z',
};

RouteSearchCondition _condition() => RouteSearchCondition(
  origin: const PlaceLocation(name: '집', lat: 37.5, lng: 127.0),
  destination: const PlaceLocation(name: '회사', lat: 37.51, lng: 127.03),
  plannedDepartAt: DateTime(2026, 9, 18, 8, 10),
  desiredArrivalAt: DateTime(2026, 9, 18, 9),
);

void main() {
  test('/routes/evaluate에 P/D와 좌표를 보내고 서버 시각을 그대로 사용한다', () async {
    late RequestOptions captured;
    final dio = Dio(BaseOptions(baseUrl: 'https://example.test/api/v1'))
      ..interceptors.add(
        InterceptorsWrapper(
          onRequest: (options, handler) {
            captured = options;
            handler.resolve(
              Response<Map<String, dynamic>>(
                requestOptions: options,
                statusCode: 200,
                data: {'success': true, 'data': _evaluationData},
              ),
            );
          },
        ),
      );

    final result = await RecommendationApi(dio).evaluateOrSearch(_condition());
    final body = captured.data as Map<String, dynamic>;

    expect(captured.path, '/routes/evaluate');
    expect(body.keys, {'origin', 'destination', 'plannedDepartAt', 'arriveBy'});
    expect(body['plannedDepartAt'], contains('2026-09-18T08:10:00'));
    expect(body['arriveBy'], contains('2026-09-18T09:00:00'));
    expect((body['origin'] as Map)['longitude'], 127.0);
    expect(result.searchFallback, isFalse);
    expect(
      result.evaluation?.proposedDepartAt,
      DateTime.utc(2026, 9, 17, 23, 10),
    );
  });

  test('evaluate 미지원 상태에서만 search를 단순 후보 fallback으로 사용한다', () async {
    final paths = <String>[];
    final dio = Dio(BaseOptions(baseUrl: 'https://example.test/api/v1'))
      ..interceptors.add(
        InterceptorsWrapper(
          onRequest: (options, handler) {
            paths.add(options.path);
            if (options.path == '/routes/evaluate') {
              handler.reject(
                DioException(
                  requestOptions: options,
                  response: Response<void>(
                    requestOptions: options,
                    statusCode: 404,
                  ),
                  type: DioExceptionType.badResponse,
                ),
              );
              return;
            }
            handler.resolve(
              Response<Map<String, dynamic>>(
                requestOptions: options,
                statusCode: 200,
                data: {
                  'success': true,
                  'data': {
                    'candidates': [
                      {
                        'type': 'BUS',
                        'totalDurationSeconds': 1800,
                        'totalDistanceMeters': 10000,
                        'transfers': 0,
                        'fare': {'minimumWon': 1500},
                      },
                    ],
                  },
                },
              ),
            );
          },
        ),
      );

    final result = await RecommendationApi(dio).evaluateOrSearch(_condition());

    expect(paths, ['/routes/evaluate', '/routes/search']);
    expect(result.searchFallback, isTrue);
    expect(result.candidates.single.kind, RouteKind.candidate);
    expect(result.candidates.single.departure, '평가 필요');
    expect(result.candidates.single.arrival, '평가 필요');
  });

  test('500 평가 실패는 search 성공처럼 숨기지 않는다', () async {
    final paths = <String>[];
    final dio = Dio(BaseOptions(baseUrl: 'https://example.test/api/v1'))
      ..interceptors.add(
        InterceptorsWrapper(
          onRequest: (options, handler) {
            paths.add(options.path);
            handler.reject(
              DioException(
                requestOptions: options,
                response: Response<void>(
                  requestOptions: options,
                  statusCode: 500,
                ),
                type: DioExceptionType.badResponse,
              ),
            );
          },
        ),
      );

    await expectLater(
      RecommendationApi(dio).evaluateOrSearch(_condition()),
      throwsA(isA<DioException>()),
    );
    expect(paths, ['/routes/evaluate']);
  });

  test('평가 수락 body에는 evaluationId만 포함한다', () async {
    late RequestOptions captured;
    final dio = Dio(BaseOptions(baseUrl: 'https://example.test/api/v1'))
      ..interceptors.add(
        InterceptorsWrapper(
          onRequest: (options, handler) {
            captured = options;
            handler.resolve(
              Response<Map<String, dynamic>>(
                requestOptions: options,
                statusCode: 200,
                data: {'success': true, 'data': <String, dynamic>{}},
              ),
            );
          },
        ),
      );

    await ApiScheduleRepository(ScheduleApi(dio))
        .acceptEvaluation('schedule-1', 'eval-1');

    expect(captured.path, '/schedules/schedule-1/recommendations/accept');
    expect(captured.data, {'evaluationId': 'eval-1'});
  });
}
