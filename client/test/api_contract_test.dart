import 'package:ddubi/core/constants/api_constants.dart';
import 'package:ddubi/core/constants/api_paths.dart';
import 'package:ddubi/core/network/api_error_response.dart';
import 'package:ddubi/domain/auth.dart';
import 'package:ddubi/domain/models.dart';
import 'package:ddubi/services/device_token_service.dart';
import 'package:ddubi/services/api_auth_repository.dart';
import 'package:ddubi/services/auth_api.dart';
import 'package:ddubi/services/app_api.dart';
import 'package:ddubi/services/place_api.dart';
import 'package:dio/dio.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  test('리소스 ID를 API 경로에 안전하게 조합한다', () {
    expect(ApiPaths.schedule('schedule-1'), '/schedules/schedule-1');
    expect(ApiPaths.login, '/auth/local/login');
    expect(ApiPaths.emailVerification, '/auth/email/verification');
    expect(
      ApiPaths.emailVerificationConfirm,
      '/auth/email/verification/confirm',
    );
    expect(ApiPaths.refresh, '/auth/refresh');
    expect(ApiPaths.logout, '/auth/logout');
    expect(ApiPaths.oauthTokenLogin, '/auth/oauth/token');
    expect(ApiPaths.oauthIdTokenLogin, '/auth/oauth/id-token');
    expect(ApiPaths.oauthLink, '/auth/oauth/link');
    expect(ApiPaths.placeSearch, '/places/search');
    expect(ApiPaths.home, '/home');
    expect(ApiPaths.notificationsReadAll, '/notifications/read-all');
    expect(ApiPaths.notificationRead('7'), '/notifications/7/read');
    expect(ApiPaths.scheduleRepeats, '/schedule-repeats');
    expect(ApiPaths.scheduleRepeat('3'), '/schedule-repeats/3');
    expect(ApiPaths.scheduleArrival('12'), '/schedules/12/arrival');
    expect(ApiPaths.history, '/history');
    expect(ApiPaths.reports, '/reports');
    expect(ApiPaths.report('4'), '/reports/4');
    expect(ApiPaths.scheduleConflicts, '/schedules/conflicts');
    expect(ApiPaths.routeSearch, '/routes/search');
    expect(
      ApiPaths.latestRecommendation('schedule-1'),
      '/schedules/schedule-1/recommendations/latest',
    );
    expect(ApiPaths.routeMap('route-1'), '/routes/route-1/map');
  });

  test('공통 오류 응답에서 코드와 필드 오류를 읽는다', () {
    final response = ApiErrorResponse.fromJson({
      'success': false,
      'error': {
        'code': 'INVALID_REQUEST',
        'message': '입력값을 확인해주세요.',
        'fieldErrors': [
          {
            'field': 'email',
            'rejectedValue': 'abc@',
            'reason': '올바른 이메일 형식이 아닙니다.',
          },
        ],
      },
      'timestamp': '2026-08-31T12:00:00+09:00',
      'requestId': 'request-1',
    });

    expect(response.code, 'INVALID_REQUEST');
    expect(response.message, '입력값을 확인해주세요.');
    expect(response.fieldErrors.single.field, 'email');
    expect(response.fieldErrors.single.reason, '올바른 이메일 형식이 아닙니다.');
    expect(response.requestId, 'request-1');
  });

  test('토큰 재발급 응답에 없는 기존 사용자와 디바이스 정보를 유지한다', () {
    final session = AuthSession(
      accessToken: 'old-access',
      refreshToken: 'old-refresh',
      tokenType: 'Bearer',
      deviceId: 'device-1',
      user: AuthUser(
        id: 7,
        provider: 'LOCAL',
        email: 'member@ddubi.app',
        nickname: '뚜비',
      ),
    );

    final refreshed = session.withRefreshedTokens({
      'accessToken': 'new-access',
      'refreshToken': 'new-refresh',
      'tokenType': 'Bearer',
    });

    expect(refreshed.accessToken, 'new-access');
    expect(refreshed.refreshToken, 'new-refresh');
    expect(refreshed.deviceId, 'device-1');
    expect(refreshed.user.id, 7);
    expect(refreshed.user.nickname, '뚜비');
  });

  test('baseUrl의 API 버전 경로를 요청 URI에 유지한다', () {
    final options = RequestOptions(
      baseUrl: ApiConstants.baseUrl,
      path: ApiPaths.refresh,
    );

    expect(options.uri.path, '/api/v1/auth/refresh');
  });

  test('FCM data를 알림 상세 또는 일정 경로로 변환한다', () {
    expect(
      notificationLocationFromData({
        'notificationId': '27',
        'scheduleId': '12',
      }),
      '/notifications/27',
    );
    expect(notificationLocationFromData({'scheduleId': '12'}), '/schedules/12');
    expect(notificationLocationFromData(const {}), '/notifications');
  });

  test('소셜 로그인 계정 충돌 응답을 연동 확인 흐름으로 변환한다', () async {
    final dio = Dio(BaseOptions(baseUrl: 'https://example.test/api/v1'))
      ..interceptors.add(
        InterceptorsWrapper(
          onRequest: (options, handler) => handler.reject(
            DioException(
              requestOptions: options,
              response: Response<Map<String, dynamic>>(
                requestOptions: options,
                statusCode: 409,
                data: {
                  'success': false,
                  'error': {'code': 'ACCOUNT_LINK_REQUIRED'},
                  'data': {
                    'linkToken': 'one-time-link-token',
                    'provider': 'KAKAO',
                    'email': 'm***@example.com',
                  },
                },
              ),
            ),
          ),
        ),
      );
    final store = AuthSessionStore();
    final repository = ApiAuthRepository(
      api: AuthApi(dio),
      sessionStore: store,
      deviceTokenService: DeviceTokenService(dio, store),
    );

    await expectLater(
      repository.loginWithOAuthToken(
        provider: 'KAKAO',
        accessToken: 'provider-token',
      ),
      throwsA(
        isA<AccountLinkRequiredException>()
            .having(
              (error) => error.linkToken,
              'linkToken',
              'one-time-link-token',
            )
            .having((error) => error.provider, 'provider', 'KAKAO')
            .having((error) => error.email, 'email', 'm***@example.com'),
      ),
    );
  });

  test('일정 조회 응답에서 좌표와 updatedAt을 잃지 않는다', () {
    final schedule = Schedule.fromJson({
      'id': 12,
      'title': '프로젝트 회의',
      'origin': {'name': '강남역', 'lat': 37.4979, 'lng': 127.0276},
      'destination': {'name': '잠실야구장', 'lat': 37.5121, 'lng': 127.0719},
      'arriveBy': '2026-09-10T19:00:00',
      'importance': 'HIGH',
      'considerLastTrip': true,
      'updatedAt': '2026-09-03T09:14:22.123456',
    });

    expect(schedule.id, '12');
    expect(schedule.destination.lat, 37.5121);
    expect(schedule.importance, ScheduleImportance.high);
    expect(schedule.considerLastTrip, isTrue);
    expect(schedule.updatedAt, DateTime(2026, 9, 3, 9, 14, 22, 123, 456));
  });

  test('교통수단 3지선다를 사용자 API transportPrefs로 변환한다', () {
    expect(TransportPreferenceChoice.balanced.apiTransportPrefs, isEmpty);
    expect(TransportPreferenceChoice.subwayFirst.apiTransportPrefs, [
      {'mode': 'SUBWAY', 'preference': 'PREFER'},
    ]);
    expect(TransportPreferenceChoice.busFirst.apiTransportPrefs, [
      {'mode': 'BUS', 'preference': 'PREFER'},
    ]);
  });

  test('반복 일정 요청을 백엔드 rrule과 장소 좌표 계약으로 변환한다', () {
    final request = RepeatScheduleRule(
      id: 'local-id',
      title: '평일 출근',
      origin: const PlaceLocation(name: '집', lat: 37.5, lng: 127.0),
      destination: const PlaceLocation(name: '회사', lat: 37.51, lng: 127.03),
      time: '09:00',
      startDate: DateTime(2026, 9, 7),
      weekdays: const {1, 2, 3, 4, 5},
    ).toCreateRequestJson();

    expect(request['rrule'], 'FREQ=WEEKLY;BYDAY=MO,TU,WE,TH,FR');
    expect(request['startDate'], '2026-09-07');
    expect((request['origin'] as Map)['lat'], 37.5);
    expect((request['destination'] as Map)['name'], '회사');
  });

  test('반복 일정 응답을 수정 가능한 규칙으로 복원한다', () {
    final rule = RepeatScheduleRule.fromJson({
      'id': 3,
      'title': '평일 출근',
      'rrule': 'FREQ=WEEKLY;BYDAY=MO,WE,FR',
      'localTime': '09:00:00',
      'startDate': '2026-09-07',
      'endDate': null,
      'origin': {'name': '집', 'lat': 37.5, 'lng': 127.0},
      'destination': {'name': '회사', 'lat': 37.51, 'lng': 127.03},
      'importance': 'HIGH',
      'considerLastTrip': true,
      'active': true,
    });

    expect(rule.id, '3');
    expect(rule.time, '09:00');
    expect(rule.weekdays, {1, 3, 5});
    expect(rule.untilDate, isNull);
    expect(rule.considerLastTrip, isTrue);
    expect(rule.toUpdateRequestJson()['rrule'], 'FREQ=WEEKLY;BYDAY=MO,WE,FR');
  });

  test('저장 장소와 이동 기록 응답의 숫자·날짜 타입을 복원한다', () {
    final place = SavedPlace.fromJson({
      'id': 9,
      'name': '회사',
      'address': '서울 영등포구',
      'lat': 37.5,
      'lng': 126.9,
    });
    final history = TravelHistoryItem.fromJson({
      'reportDetailId': 7,
      'scheduleId': 12,
      'title': '출근',
      'arriveBy': '2026-09-09T09:00:00',
      'actualArrival': null,
      'result': null,
    });

    expect(place.id, '9');
    expect(place.address, '서울 영등포구');
    expect(history.scheduleId, '12');
    expect(history.arriveBy, DateTime(2026, 9, 9, 9));
    expect(history.actualArrival, isNull);
  });

  test('주간 리포트에서 빠듯 도착 건수를 분리한다', () {
    final report = WeeklyReport.fromJson({
      'id': 4,
      'periodStart': '2026-09-07',
      'periodEnd': '2026-09-13',
      'totalSchedules': 10,
      'onTimeEstimated': 6,
      'delayedEstimated': 1,
    });

    expect(report.tightEstimated, 3);
  });

  test('미연결 API가 백엔드 계약 경로와 요청 본문을 사용한다', () async {
    final requests = <RequestOptions>[];
    final dio = Dio(BaseOptions(baseUrl: 'https://example.test/api/v1'))
      ..interceptors.add(
        InterceptorsWrapper(
          onRequest: (options, handler) {
            requests.add(options);
            final data = switch (options.path) {
              '/places' => {
                'content': [
                  {
                    'id': 1,
                    'name': '집',
                    'address': '서울',
                    'lat': 37.5,
                    'lng': 127.0,
                  },
                ],
              },
              '/schedule-repeats' => {'content': <Object>[]},
              '/schedules/12/arrival' => {
                'scheduleId': 12,
                'status': 'DONE',
                'reportDetailId': 7,
                'actualArrival': null,
                'result': null,
              },
              _ => <String, Object?>{},
            };
            handler.resolve(
              Response<Map<String, dynamic>>(
                requestOptions: options,
                statusCode: 200,
                data: {'success': true, 'data': data},
              ),
            );
          },
        ),
      );

    expect((await PlaceApi(dio).list()).single.name, '집');
    await RepeatScheduleApi(dio).repeatSchedules(activeOnly: true);
    await ArrivalApi(dio).record('12');

    expect(requests.map((request) => request.path), [
      '/places',
      '/schedule-repeats',
      '/schedules/12/arrival',
    ]);
    expect(requests[1].queryParameters['activeOnly'], isTrue);
    expect(requests[2].data, {'actualArrival': null, 'locationConsent': false});
  });
}
