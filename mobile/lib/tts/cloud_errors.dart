import 'dart:io';

/// Problems an online voice service reports.
class CloudTtsException implements IOException {
  CloudTtsException(this.message);
  final String message;
  @override
  String toString() => message;
}

class AuthException extends CloudTtsException {
  AuthException(String service, int code) : super('$service rejected the key (HTTP $code)');
}

class ThrottledException extends CloudTtsException {
  ThrottledException(this.retryAfterSeconds, {required this.quota}) : super(quota ? 'Free quota used up' : 'Too many requests');
  final int? retryAfterSeconds;
  final bool quota;
}

class RejectedTextException extends CloudTtsException {
  RejectedTextException(int code) : super('The text was rejected (HTTP $code)');
}
