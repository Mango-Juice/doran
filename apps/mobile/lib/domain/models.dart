String kstDate([DateTime? instant]) {
  final local = (instant ?? DateTime.now()).toUtc().add(
    const Duration(hours: 9),
  );
  return '${local.year}-${local.month.toString().padLeft(2, '0')}-${local.day.toString().padLeft(2, '0')}';
}

class DoranRecord {
  final String id, date, text, status;
  final int version;
  final bool excluded;
  final DateTime createdAt;
  const DoranRecord({
    required this.id,
    required this.date,
    required this.text,
    required this.status,
    required this.version,
    required this.excluded,
    required this.createdAt,
  });
  factory DoranRecord.fromJson(Map<String, dynamic> j) => DoranRecord(
    id: j['id'] as String,
    date: j['record_date'] as String,
    text: j['text'] as String? ?? '',
    status: j['status'] as String? ?? 'pending',
    version: j['version'] as int,
    excluded: j['excluded_from_personalization'] as bool? ?? false,
    createdAt: DateTime.parse(j['created_at'] as String),
  );
}

class DoranDay {
  final String date, generatedText, status;
  final String? manualText;
  final int version;
  final bool hasNewGenerated;
  const DoranDay({
    required this.date,
    required this.generatedText,
    required this.manualText,
    required this.version,
    required this.hasNewGenerated,
    required this.status,
  });
  String get displayText => manualText ?? generatedText;
  bool get isManual => manualText != null;
  factory DoranDay.fromJson(Map<String, dynamic> j) => DoranDay(
    date: j['date'] as String,
    generatedText: j['generated_text'] as String? ?? '',
    manualText: j['manual_text'] as String?,
    version: j['version'] as int? ?? 0,
    hasNewGenerated: j['has_new_generated'] as bool? ?? false,
    status: j['status'] as String? ?? 'empty',
  );
}

class DoranSettings {
  final bool vehicleContext, personalization, consent;
  final String suggestions, style;
  final int version;
  const DoranSettings({
    required this.vehicleContext,
    required this.personalization,
    required this.consent,
    required this.suggestions,
    required this.style,
    required this.version,
  });
  factory DoranSettings.fromJson(Map<String, dynamic> j) => DoranSettings(
    vehicleContext: j['vehicle_context_enabled'] as bool? ?? false,
    personalization: j['personalization_enabled'] as bool? ?? true,
    consent: j['consent_accepted'] as bool? ?? false,
    suggestions: j['conversation_suggestions'] as String? ?? 'off',
    style: j['conversation_style'] as String? ?? 'balanced',
    version: j['version'] as int? ?? 1,
  );
}

class CachedValue<T> {
  final T value;
  final bool offline;
  const CachedValue(this.value, {this.offline = false});
}

class RecordPage {
  final List<DoranRecord> items;
  final String? nextCursor;
  const RecordPage(this.items, this.nextCursor);
}
