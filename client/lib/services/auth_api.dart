import 'package:dio/dio.dart';

class AuthApi {
  const AuthApi(this._dio);

  final Dio _dio;

  Future<Response<Map<String, dynamic>>> login({
    required String email,
    required String password,
  }) {
    return _dio.post<Map<String, dynamic>>(
      '/auth/login',
      data: {'email': email, 'password': password},
    );
  }
}
