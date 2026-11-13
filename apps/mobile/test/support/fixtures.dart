import 'dart:convert';
import 'package:doran_mobile/data/repository.dart';
import 'package:http/http.dart' as http;

class MemoryCache implements CacheStore {
  final Map<String, String> values = {};
  @override
  Future<String?> read(String key) async => values[key];
  @override
  Future<void> write(String key, String value) async => values[key] = value;
  @override
  Future<void> remove(String key) async => values.remove(key);
  @override
  Future<void> clearPrefix(String prefix) async =>
      values.removeWhere((key, _) => key.startsWith(prefix));
}

Map<String, dynamic> recordJson({String text = '원래 기록', int version = 2}) => {
  'id': 'record-1',
  'session_id': 'session-1',
  'record_date': '2026-11-14',
  'text': text,
  'version': version,
  'excluded_from_personalization': false,
  'status': 'ready',
  'created_at': '2026-11-14T01:00:00Z',
};

Map<String, dynamic> dayJson({String? manualText, int version = 2}) => {
  'date': '2026-11-14',
  'generated_text': '새 AI 초안',
  'manual_text': manualText,
  'version': version,
  'status': 'ready',
  'has_new_generated': manualText != null,
};

http.Response jsonResponse(Object body, [int status = 200]) => http.Response(
  jsonEncode(body),
  status,
  headers: {'content-type': 'application/json; charset=utf-8'},
);
