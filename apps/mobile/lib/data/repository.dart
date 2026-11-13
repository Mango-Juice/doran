import 'dart:async';
import 'dart:convert';
import 'dart:io';
import 'package:http/http.dart' as http;
import 'package:uuid/uuid.dart';
import '../domain/models.dart';

abstract interface class CacheStore {
  Future<String?> read(String key);
  Future<void> write(String key, String value);
  Future<void> remove(String key);
  Future<void> clearPrefix(String prefix);
}

class ApiException implements Exception {
  final int status;
  final String code, message;
  const ApiException(this.status, this.code, this.message);
  @override
  String toString() => message;
}

class DoranRepository {
  final http.Client client;
  final Future<String?> Function() token;
  final String Function() userId;
  final CacheStore cache;
  final String baseUrl, publishableKey;
  final String ownerId;
  DoranRepository({
    required this.client,
    required this.token,
    required this.userId,
    required this.cache,
    required this.baseUrl,
    required this.publishableKey,
    String? ownerId,
  }) : ownerId = ownerId ?? userId();
  String get prefix => 'doran:$ownerId:';
  void _ensureOwner() {
    if (ownerId.isEmpty) {
      throw const ApiException(401, 'unauthenticated', '다시 로그인해 주세요');
    }
    if (userId() != ownerId) {
      throw const ApiException(401, 'account_changed', '계정이 변경되었습니다');
    }
  }

  String get requestId => const Uuid().v4();
  Future<Map<String, dynamic>> request(
    String method,
    String path, [
    Map<String, dynamic>? body,
  ]) async {
    _ensureOwner();
    final originalOwner = ownerId;
    final access = await token();
    if (userId() != originalOwner) {
      throw const ApiException(401, 'account_changed', '계정이 변경되었습니다');
    }
    if (access == null || userId().isEmpty) {
      throw const ApiException(401, 'unauthenticated', '다시 로그인해 주세요');
    }
    final req = http.Request(method, Uri.parse('$baseUrl$path'));
    req.headers.addAll({
      'Authorization': 'Bearer $access',
      'apikey': publishableKey,
      'Content-Type': 'application/json',
    });
    if (body != null) req.body = jsonEncode(body);
    final response = await http.Response.fromStream(
      await client.send(req).timeout(const Duration(seconds: 20)),
    ).timeout(const Duration(seconds: 20));
    if (userId() != originalOwner) {
      throw const ApiException(401, 'account_changed', '계정이 변경되었습니다');
    }
    Map<String, dynamic> data;
    try {
      data = jsonDecode(response.body) as Map<String, dynamic>;
    } catch (_) {
      throw ApiException(
        response.statusCode,
        'invalid_response',
        '서버 응답을 확인하지 못했어요. 잠시 후 다시 시도해 주세요',
      );
    }
    if (response.statusCode >= 400) {
      final e = data['error'] as Map<String, dynamic>? ?? {};
      throw ApiException(
        response.statusCode,
        e['code'] as String? ?? 'server_error',
        e['message'] as String? ?? '요청을 처리하지 못했어요',
      );
    }
    return data;
  }

  Future<CachedValue<Map<String, dynamic>>> cached(String path) async {
    _ensureOwner();
    final owner = ownerId;
    if (owner.isEmpty) {
      throw const ApiException(401, 'unauthenticated', '다시 로그인해 주세요');
    }
    final key = '$prefix$path';
    try {
      final data = await request('GET', path);
      if (userId() != owner) {
        throw const ApiException(401, 'account_changed', '계정이 변경되었습니다');
      }
      await cache.write(key, jsonEncode(data));
      if (userId() != owner) {
        await cache.remove(key);
        throw const ApiException(401, 'account_changed', '계정이 변경되었습니다');
      }
      return CachedValue(data);
    } on ApiException {
      rethrow;
    } on Object catch (e) {
      if (e is! SocketException &&
          e is! http.ClientException &&
          e is! TimeoutException) {
        rethrow;
      }
      if (userId() != owner) rethrow;
      final saved = await cache.read(key);
      if (userId() != owner) {
        throw const ApiException(401, 'account_changed', '계정이 변경되었습니다');
      }
      if (saved == null) rethrow;
      return CachedValue(
        jsonDecode(saved) as Map<String, dynamic>,
        offline: true,
      );
    }
  }

  Future<CachedValue<RecordPage>> fetchRecordPage({
    String? date,
    String? cursor,
  }) async {
    final q = {'limit': '50', 'date': ?date, 'cursor': ?cursor};
    final r = await cached('/records?${Uri(queryParameters: q).query}');
    return CachedValue(
      RecordPage(
        (r.value['items'] as List)
            .map((j) => DoranRecord.fromJson(j as Map<String, dynamic>))
            .toList(),
        r.value['next_cursor'] as String?,
      ),
      offline: r.offline,
    );
  }

  Future<CachedValue<List<DoranRecord>>> fetchRecords({
    String? date,
    String? cursor,
  }) async {
    final page = await fetchRecordPage(date: date, cursor: cursor);
    return CachedValue(page.value.items, offline: page.offline);
  }

  Future<CachedValue<DoranDay>> fetchDay(String date) async {
    final r = await cached('/days/$date');
    return CachedValue(DoranDay.fromJson(r.value), offline: r.offline);
  }

  Future<CachedValue<DoranSettings>> fetchSettings() async {
    final r = await cached('/settings');
    return CachedValue(DoranSettings.fromJson(r.value), offline: r.offline);
  }

  Future<void> invalidate() async {
    _ensureOwner();
    await cache.clearPrefix('$prefix/');
    _ensureOwner();
  }

  Future<DoranRecord> updateRecord(
    DoranRecord record, {
    String? text,
    String? recordDate,
    bool? excluded,
  }) async {
    final r = await request('PATCH', '/records/${record.id}', {
      'request_id': requestId,
      'version': record.version,
      'text': ?text,
      'record_date': ?recordDate,
      'excluded_from_personalization': ?excluded,
    });
    await invalidate();
    return DoranRecord.fromJson(r);
  }

  Future<void> deleteRecord(
    DoranRecord record, {
    bool deleteManualDiary = false,
  }) async {
    await request('DELETE', '/records/${record.id}', {
      'request_id': requestId,
      'version': record.version,
      'delete_manual_diary': deleteManualDiary,
    });
    await invalidate();
    await cache.remove('${prefix}draft:record:${record.id}');
  }

  Future<DoranDay> saveDay(DoranDay day, String text) async {
    final r = await request('PATCH', '/days/${day.date}', {
      'request_id': requestId,
      'version': day.version,
      'manual_text': text,
    });
    await invalidate();
    return DoranDay.fromJson(r);
  }

  Future<DoranDay> adoptDay(DoranDay day) async {
    final r = await request('POST', '/days/${day.date}/adopt', {
      'request_id': requestId,
      'version': day.version,
    });
    await invalidate();
    return DoranDay.fromJson(r);
  }

  Future<void> deleteDay(DoranDay day) async {
    await request('DELETE', '/days/${day.date}', {
      'request_id': requestId,
      'version': day.version,
    });
    await invalidate();
    await cache.clearPrefix('${prefix}draft:');
  }

  Future<DoranSettings> updateSettings(
    DoranSettings settings,
    Map<String, dynamic> changes,
  ) async {
    final r = await request('PATCH', '/settings', {
      'request_id': requestId,
      'version': settings.version,
      ...changes,
    });
    await invalidate();
    return DoranSettings.fromJson(r);
  }

  Future<Map<String, dynamic>> claimPairing(String code) => request(
    'POST',
    '/pairings/claim',
    {'request_id': requestId, 'code': code},
  );
  Future<Map<String, dynamic>> pairing(String id) =>
      request('GET', '/pairings/$id');
  Future<List<Map<String, dynamic>>> devices() async =>
      ((await request('GET', '/devices'))['items'] as List)
          .cast<Map<String, dynamic>>();
  Future<void> revokeDevice(String id) async {
    await request('DELETE', '/devices/$id');
  }

  Future<List<Map<String, dynamic>>> memories() async =>
      ((await request('GET', '/memories'))['items'] as List)
          .cast<Map<String, dynamic>>();
  Future<void> forgetMemory(String id) async {
    await request('DELETE', '/memories/$id', {'request_id': requestId});
  }

  Future<void> retryRecord(String id) async {
    await request('POST', '/records/$id/retry', {'request_id': requestId});
    await invalidate();
  }

  Future<void> deleteAccount() async {
    await request('DELETE', '/account', {'confirmation': 'DELETE'});
    await cache.clearPrefix(prefix);
  }

  Future<void> saveDraft(String id, String text) async {
    _ensureOwner();
    final key = '${prefix}draft:$id';
    await cache.write(key, text);
    if (userId() != ownerId) {
      await cache.remove(key);
      throw const ApiException(401, 'account_changed', '계정이 변경되었습니다');
    }
  }

  Future<String?> loadDraft(String id) async {
    _ensureOwner();
    final value = await cache.read('${prefix}draft:$id');
    _ensureOwner();
    return value;
  }

  Future<void> clearDraft(String id) async {
    _ensureOwner();
    await cache.remove('${prefix}draft:$id');
    _ensureOwner();
  }

  Future<void> clearUserCache() => cache.clearPrefix(prefix);
}
