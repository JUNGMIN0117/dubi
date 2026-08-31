import 'package:dio/dio.dart';

class RecommendationApi {
  const RecommendationApi(this._dio);

  final Dio _dio;

  Future<Response<Map<String, dynamic>>> getRecommendation(String id) =>
      _dio.get<Map<String, dynamic>>('/recommendations/$id');
}
