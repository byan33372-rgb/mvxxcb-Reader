import 'reader_error.dart';

/// 统一结果类型（对应 Kotlin 版 AppResult）。
sealed class AppResult<T> {
  const AppResult();
}

class Success<T> extends AppResult<T> {
  final T value;
  const Success(this.value);
}

class Failure<T> extends AppResult<T> {
  final ReaderError error;
  const Failure(this.error);
}

extension AppResultX<T> on AppResult<T> {
  T? get valueOrNull => this is Success<T> ? (this as Success<T>).value : null;
  ReaderError? get errorOrNull => this is Failure<T> ? (this as Failure<T>).error : null;
  bool get isSuccess => this is Success<T>;
}