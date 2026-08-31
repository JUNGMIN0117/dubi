import 'package:ddubi/models/schedule.dart';
import 'package:dio/dio.dart';

class ScheduleApi {
  const ScheduleApi(this._dio);

  final Dio _dio;

  Future<List<Schedule>> getSchedules() async {
    final response = await _dio.get<List<dynamic>>('/schedules');
    return (response.data ?? const <dynamic>[])
        .map((item) => Schedule.fromJson(item as Map<String, dynamic>))
        .toList();
  }
}
