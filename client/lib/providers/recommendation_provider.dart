import 'package:ddubi/core/network/dio_provider.dart';
import 'package:ddubi/models/recommendation.dart';
import 'package:ddubi/models/schedule.dart';
import 'package:ddubi/services/recommendation_api.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

final recommendationApiProvider = Provider<RecommendationApi>((ref) {
  return RecommendationApi(ref.watch(dioProvider));
});

// 백엔드 연동 전까지 UI가 사용하는 임시 데이터입니다.
final homeRecommendationProvider = Provider<Recommendation>((ref) {
  return const Recommendation(
    id: 'mock-recommendation-1',
    schedule: Schedule(
      id: 'mock-schedule-1',
      time: '오전 10:00',
      title: 'SSAFY 프로젝트 회의',
    ),
    estimatedArrivalTime: '09:52',
    recommendedDepartureTime: '09:05',
    delayDifference: '+8분',
    currentStatus: '지하철 일부 지연',
  );
});
