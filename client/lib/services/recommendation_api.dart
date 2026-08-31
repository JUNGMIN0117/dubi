import 'package:ddubi/models/recommendation.dart';
import 'package:dio/dio.dart';

class RecommendationApi {
  const RecommendationApi(this._dio);

  final Dio _dio;

  Future<Recommendation> getRecommendation(String id) async {
    final response = await _dio.get<Map<String, dynamic>>(
      '/recommendations/$id',
    );
    return Recommendation.fromJson(response.data!);
  }
}
