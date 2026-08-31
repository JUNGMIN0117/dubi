import 'package:dio/dio.dart';

class ScheduleApi {
  const ScheduleApi(this._dio);

  final Dio _dio;

  Future<Response<List<dynamic>>> getSchedules() =>
      _dio.get<List<dynamic>>('/schedules');
}
