import 'package:doran_mobile/domain/models.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  group('KST diary dates', () {
    test('uses Seoul midnight rather than the host calendar day', () {
      expect(kstDate(DateTime.parse('2026-11-13T14:59:59Z')), '2026-11-13');
      expect(kstDate(DateTime.parse('2026-11-13T15:00:00Z')), '2026-11-14');
    });
    test('handles year boundary and explicit source offsets', () {
      expect(kstDate(DateTime.parse('2026-12-31T15:00:00Z')), '2027-01-01');
      expect(
        kstDate(DateTime.parse('2026-11-14T00:01:00+09:00')),
        '2026-11-14',
      );
    });
  });
  group('human authored diary preservation', () {
    test('new AI generation does not replace an edited diary', () {
      final day = DoranDay.fromJson({
        'date': '2026-11-14',
        'generated_text': 'AI가 새로 정리한 일기',
        'manual_text': '내가 고른 표현',
        'version': 8,
        'has_new_generated': true,
        'status': 'ready',
      });
      expect(day.displayText, '내가 고른 표현');
      expect(day.isManual, isTrue);
      expect(day.hasNewGenerated, isTrue);
    });
    test('a deliberately empty manual diary is not filled by AI', () {
      final day = DoranDay.fromJson({
        'date': '2026-11-14',
        'generated_text': '자동으로 되살리면 안 되는 문장',
        'manual_text': '',
        'version': 3,
        'status': 'ready',
      });
      expect(day.displayText, isEmpty);
      expect(day.isManual, isTrue);
    });
    test('a day with no saved manual version uses its AI version', () {
      final day = DoranDay.fromJson({
        'date': '2026-11-14',
        'generated_text': '대화에서 정리한 내용',
        'manual_text': null,
        'version': 2,
      });
      expect(day.displayText, '대화에서 정리한 내용');
      expect(day.isManual, isFalse);
    });
    test('an empty date remains empty with initial version zero', () {
      final day = DoranDay.fromJson({'date': '2026-11-14'});
      expect(day.displayText, isEmpty);
      expect(day.status, 'empty');
      expect(day.version, 0);
      expect(day.hasNewGenerated, isFalse);
    });
  });
  test('pending records preserve the source fallback and exclusion state', () {
    final record = DoranRecord.fromJson({
      'id': 'record-1',
      'session_id': 'session-1',
      'record_date': '2026-11-14',
      'text': '발표를 마쳐서 마음이 놓였다고 이야기했다',
      'version': 4,
      'excluded_from_personalization': true,
      'status': 'pending',
      'created_at': '2026-11-14T01:30:00Z',
      'future_optional_field': 42,
    });
    expect(record.text, contains('발표를 마쳐서'));
    expect(record.excluded, isTrue);
    expect(record.status, 'pending');
    expect(record.createdAt.toUtc(), DateTime.utc(2026, 11, 14, 1, 30));
  });
  test('missing preferences cannot grant cloud or vehicle consent', () {
    final settings = DoranSettings.fromJson({});
    expect(settings.consent, isFalse);
    expect(settings.vehicleContext, isFalse);
    expect(settings.destinationName, isFalse);
    expect(settings.suggestions, 'off');
  });
  test('destination names require all three explicit consents', () {
    for (final ai in [false, true]) {
      for (final vehicle in [false, true]) {
        for (final destination in [false, true]) {
          final settings = DoranSettings.fromJson({
            'consent_accepted': ai,
            'vehicle_context_enabled': vehicle,
            'consent_destination_name': destination,
          });
          expect(settings.destinationName, ai && vehicle && destination);
        }
      }
    }
    final legacy = DoranSettings.fromJson({
      'consent_accepted': true,
      'vehicle_context_enabled': true,
    });
    expect(legacy.destinationName, isFalse);
  });
}
