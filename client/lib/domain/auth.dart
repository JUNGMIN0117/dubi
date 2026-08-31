enum AuthErrorCode { invalidCredentials, emailAlreadyExists, serverError }

class AuthException implements Exception {
  const AuthException(this.code);
  final AuthErrorCode code;
}

abstract interface class AuthRepository {
  Future<void> login({required String email, required String password});
  Future<void> signUp({required String email, required String password});
}

class MockAuthRepository implements AuthRepository {
  final Map<String, String> _credentials = {'member@ddubi.app': 'password123'};
  @override
  Future<void> login({required String email, required String password}) async {
    await Future<void>.delayed(const Duration(milliseconds: 350));
    if (_credentials[email.toLowerCase()] == password) return;
    throw const AuthException(AuthErrorCode.invalidCredentials);
  }

  @override
  Future<void> signUp({required String email, required String password}) async {
    await Future<void>.delayed(const Duration(milliseconds: 450));
    if (_credentials.containsKey(email.toLowerCase())) {
      throw const AuthException(AuthErrorCode.emailAlreadyExists);
    }
    _credentials[email.toLowerCase()] = password;
  }
}

String? validateEmail(String? value) {
  final email = value?.trim() ?? '';
  if (email.isEmpty) return '이메일을 입력해주세요.';
  if (!RegExp(r'^[^@\s]+@[^@\s]+\.[^@\s]+$').hasMatch(email)) {
    return '올바른 이메일 형식으로 입력해주세요.';
  }
  return null;
}

String? validatePassword(String? value) =>
    (value ?? '').isEmpty ? '비밀번호를 입력해주세요.' : null;
