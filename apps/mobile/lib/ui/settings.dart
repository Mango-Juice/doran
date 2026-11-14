import 'package:material_ui/material_ui.dart';

import '../data/repository.dart';
import '../domain/models.dart';
import 'pairing.dart';
import 'privacy.dart';

class SettingsPage extends StatefulWidget {
  const SettingsPage({
    super.key,
    required this.repository,
    required this.onSignOut,
    required this.onDeleteAccount,
  });
  final DoranRepository repository;
  final Future<void> Function() onSignOut;
  final Future<void> Function() onDeleteAccount;

  @override
  State<SettingsPage> createState() => _SettingsPageState();
}

class _SettingsPageState extends State<SettingsPage> {
  DoranSettings? _settings;
  List<Map<String, dynamic>> _devices = [];
  List<Map<String, dynamic>> _memories = [];
  bool _loading = true;
  bool _busy = false;
  bool _offline = false;
  String? _error;
  String? _detailError;
  int _loadGeneration = 0;

  @override
  void initState() {
    super.initState();
    _load();
  }

  String _message(Object error) =>
      error is ApiException ? error.message : '인터넷 연결을 확인한 뒤 다시 시도해 주세요';

  Future<void> _load() async {
    final generation = ++_loadGeneration;
    if (mounted) {
      setState(() {
        _loading = true;
        _error = null;
        _detailError = null;
      });
    }
    try {
      final settings = await widget.repository.fetchSettings();
      if (!mounted || generation != _loadGeneration) return;
      setState(() {
        _settings = settings.value;
        _offline = settings.offline;
      });
      if (!settings.offline) {
        try {
          final details = await Future.wait([
            widget.repository.devices(),
            widget.repository.memories(),
          ]);
          if (!mounted || generation != _loadGeneration) return;
          setState(() {
            _devices = details[0];
            _memories = details[1];
          });
        } catch (error) {
          if (mounted && generation == _loadGeneration) {
            setState(() {
              _detailError = _message(error);
              if (error is ApiException &&
                  (error.status == 401 || error.status == 403)) {
                _devices = [];
                _memories = [];
              }
            });
          }
        }
      }
    } catch (error) {
      if (mounted && generation == _loadGeneration) {
        setState(() {
          _error = _message(error);
          if (error is ApiException &&
              (error.status == 401 || error.status == 403)) {
            _settings = null;
            _devices = [];
            _memories = [];
          }
        });
      }
    } finally {
      if (mounted && generation == _loadGeneration) {
        setState(() => _loading = false);
      }
    }
  }

  Future<bool> _confirm(
    String title,
    String body, {
    String action = '확인',
    bool destructive = false,
  }) async {
    return await showDialog<bool>(
          context: context,
          builder: (dialogContext) => AlertDialog(
            title: Text(title),
            content: SingleChildScrollView(child: Text(body)),
            actions: [
              TextButton(
                onPressed: () => Navigator.pop(dialogContext, false),
                child: const Text('취소'),
              ),
              TextButton(
                onPressed: () => Navigator.pop(dialogContext, true),
                style: destructive
                    ? TextButton.styleFrom(
                        foregroundColor: Theme.of(context).colorScheme.error,
                      )
                    : null,
                child: Text(action),
              ),
            ],
          ),
        ) ??
        false;
  }

  Future<void> _change(Map<String, dynamic> fields) async {
    final settings = _settings;
    if (settings == null || _busy || _offline) return;
    setState(() {
      _busy = true;
      _error = null;
    });
    try {
      final updated = await widget.repository.updateSettings(settings, fields);
      if (mounted) setState(() => _settings = updated);
    } catch (error) {
      if (!mounted) return;
      if (error is ApiException && error.status == 409) {
        await _load();
        if (mounted) {
          setState(() => _error = '다른 기기에서 설정이 바뀌었어요. 최신 설정을 확인한 뒤 다시 선택해 주세요');
        }
      } else {
        setState(() => _error = _message(error));
      }
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Future<void> _consent(bool enabled) async {
    final ok = await _confirm(
      enabled ? '대화와 기록 처리에 동의하시나요?' : '대화와 기록 처리 동의를 철회할까요?',
      enabled
          ? '음성 원본은 저장하지 않습니다. 음성에서 인식된 텍스트와 필요한 기록을 OpenAI에 보내 대화와 일기를 만들고, Supabase에 계정과 기록을 보관합니다. 외부 서비스의 처리·보관 정책이 적용됩니다. 동의는 설정에서 철회할 수 있습니다.'
          : '차량에서 새 대화 처리가 중단됩니다. 기존 기록은 유지되며 기록 화면 또는 계정 삭제에서 별도로 지울 수 있어요.',
      action: enabled ? '동의하고 사용' : '동의 철회',
    );
    if (ok && mounted) await _change({'consent_accepted': enabled});
  }

  Future<void> _task(Future<void> Function() task, {bool reload = true}) async {
    if (_busy) return;
    setState(() {
      _busy = true;
      _error = null;
    });
    try {
      await task();
      if (mounted && reload) await _load();
    } catch (error) {
      if (mounted) setState(() => _error = _message(error));
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Future<void> _pair() async {
    final paired = await Navigator.of(context).push<bool>(
      MaterialPageRoute(
        builder: (_) => PairingPage(repository: widget.repository),
      ),
    );
    if (mounted && paired == true) await _load();
  }

  @override
  Widget build(BuildContext context) {
    final settings = _settings;
    final editable = !_busy && !_offline && !_loading;
    return RefreshIndicator(
      onRefresh: () => _busy ? Future<void>.value() : _load(),
      child: ListView(
        padding: const EdgeInsets.fromLTRB(24, 12, 24, 40),
        physics: const AlwaysScrollableScrollPhysics(),
        children: [
          Text(
            '대화와 기록 설정',
            style: Theme.of(
              context,
            ).textTheme.headlineMedium?.copyWith(fontWeight: FontWeight.w500),
          ),
          const SizedBox(height: 8),
          Text(
            '참고할 정보와 대화 방식을 직접 정해요',
            style: Theme.of(context).textTheme.bodyMedium?.copyWith(
              color: Theme.of(context).colorScheme.onSurfaceVariant,
            ),
          ),
          const SizedBox(height: 24),
          if (_loading)
            const LinearProgressIndicator(semanticsLabel: '설정을 불러오는 중'),
          if (_offline)
            _notice('오프라인에서 마지막 설정을 보고 있어요. 설정 변경은 인터넷에 연결한 뒤 할 수 있습니다.'),
          if (_error != null) _notice(_error!, error: true),
          if (settings == null && !_loading) ...[
            const SizedBox(height: 16),
            OutlinedButton(onPressed: _load, child: const Text('다시 불러오기')),
          ],
          if (settings != null) ...[
            _heading('대화와 개인정보'),
            SwitchListTile.adaptive(
              contentPadding: const EdgeInsets.symmetric(vertical: 4),
              title: const Text('대화와 기록 처리 동의'),
              subtitle: const Text('인식된 텍스트의 AI 처리와 클라우드 저장'),
              value: settings.consent,
              onChanged: editable ? _consent : null,
            ),
            SwitchListTile.adaptive(
              contentPadding: const EdgeInsets.symmetric(vertical: 4),
              title: const Text('차량 정보 활용'),
              subtitle: const Text('도착·이동·좌석 정보를 대화의 단서로 사용'),
              value: settings.vehicleContext,
              onChanged: editable
                  ? (value) => _change({'vehicle_context_enabled': value})
                  : null,
            ),
            SwitchListTile.adaptive(
              contentPadding: const EdgeInsets.symmetric(vertical: 4),
              title: const Text('과거 기록으로 개인화'),
              subtitle: const Text('제외하지 않은 기록에서 관심사와 대화 맥락을 참고'),
              value: settings.personalization,
              onChanged: editable
                  ? (value) => _change({'personalization_enabled': value})
                  : null,
            ),
            TextButton.icon(
              onPressed: () => Navigator.of(context).push(
                MaterialPageRoute<void>(builder: (_) => const PrivacyPage()),
              ),
              icon: const Icon(Icons.privacy_tip_outlined),
              label: const Text('기록과 개인정보 안내'),
            ),
            _heading('대화 방식'),
            _choice(
              label: '대화 제안',
              value: settings.suggestions,
              choices: const {
                'off': '먼저 제안하지 않기',
                'daily': '하루에 한 번',
                'trip': '운행마다 한 번',
              },
              enabled: editable,
              onChanged: (value) =>
                  _change({'conversation_suggestions': value}),
            ),
            const SizedBox(height: 16),
            _choice(
              label: '질문과 경청',
              value: settings.style,
              choices: const {
                'balanced': '자연스럽게 주고받기',
                'listen': '내 이야기를 더 듣기',
                'questions': '질문으로 생각 정리하기',
              },
              enabled: editable,
              onChanged: (value) => _change({'conversation_style': value}),
            ),
          ],
          _heading('연결한 차량'),
          Text(
            '차량의 대화를 이 계정의 기록으로 연결합니다.',
            style: Theme.of(context).textTheme.bodyMedium,
          ),
          const SizedBox(height: 12),
          FilledButton.tonalIcon(
            onPressed: editable ? _pair : null,
            icon: const Icon(Icons.add),
            label: const Text('차량 연결'),
          ),
          if (_detailError != null) _notice(_detailError!, error: true),
          if (!_loading &&
              _devices.isEmpty &&
              _detailError == null &&
              !_offline)
            const Padding(
              padding: EdgeInsets.symmetric(vertical: 16),
              child: Text('아직 연결한 차량이 없어요'),
            ),
          for (final device in _devices.where((item) => item['active'] == true))
            ListTile(
              contentPadding: EdgeInsets.zero,
              leading: const Icon(Icons.directions_car_outlined),
              title: Text(device['label'] as String? ?? '내 차량'),
              trailing: IconButton(
                tooltip: '차량 연결 해제',
                onPressed: editable
                    ? () async {
                        final ok = await _confirm(
                          '차량 연결을 해제할까요?',
                          '이 차량에서 더 이상 내 계정에 접근하거나 새 대화를 저장할 수 없습니다. 기존 기록은 유지됩니다.',
                          action: '연결 해제',
                        );
                        if (ok && mounted) {
                          await _task(
                            () => widget.repository.revokeDevice(
                              device['id'] as String,
                            ),
                          );
                        }
                      }
                    : null,
                icon: const Icon(Icons.link_off),
              ),
            ),
          _heading('도란이 참고하는 기억'),
          const Text(
            '대화에서 알려준 내용을 확인할 수 있어요. 다시 참고하지 않으려면 원본 기록도 개인화에서 제외해 주세요.',
          ),
          const SizedBox(height: 12),
          if (!_loading &&
              _memories.isEmpty &&
              _detailError == null &&
              !_offline)
            const Text('아직 참고할 기억이 없어요'),
          for (final memory in _memories)
            Padding(
              padding: const EdgeInsets.only(bottom: 12),
              child: Row(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Expanded(
                    child: Padding(
                      padding: const EdgeInsets.symmetric(vertical: 12),
                      child: Text(memory['fact'] as String? ?? ''),
                    ),
                  ),
                  IconButton(
                    tooltip: '이 기억 삭제',
                    onPressed: editable
                        ? () async {
                            final ok = await _confirm(
                              '이 기억을 삭제할까요?',
                              '현재의 개인화 정보에서 지웁니다. 원본 기록은 남아 있으므로 이후 다시 참고하지 않으려면 기록 화면에서 개인화 제외도 선택해 주세요.',
                              action: '기억 삭제',
                              destructive: true,
                            );
                            if (ok && mounted) {
                              await _task(
                                () => widget.repository.forgetMemory(
                                  memory['id'] as String,
                                ),
                              );
                            }
                          }
                        : null,
                    icon: const Icon(Icons.delete_outline),
                  ),
                ],
              ),
            ),
          TextButton.icon(
            onPressed: () => showLicensePage(
              context: context,
              applicationName: '도란',
              applicationVersion: '1.0.0',
            ),
            icon: const Icon(Icons.description_outlined),
            label: const Text('오픈소스 라이선스'),
          ),
          _heading('계정'),
          Padding(
            padding: const EdgeInsets.only(bottom: 16),
            child: Text(
              '로그아웃은 기기의 사본만, 계정 삭제는 모든 기록을 지워요',
              style: Theme.of(context).textTheme.bodyMedium?.copyWith(
                color: Theme.of(context).colorScheme.onSurfaceVariant,
              ),
            ),
          ),
          OutlinedButton(
            onPressed: !_busy
                ? () async {
                    final ok = await _confirm(
                      '로그아웃할까요?',
                      '이 기기에 저장된 기록 사본과 아직 저장하지 않은 임시 글을 지웁니다. 계정에 저장된 기록은 다시 로그인하면 볼 수 있어요.',
                      action: '로그아웃',
                    );
                    if (ok && mounted) {
                      await _task(widget.onSignOut, reload: false);
                    }
                  }
                : null,
            child: const Text('로그아웃'),
          ),
          const SizedBox(height: 12),
          TextButton(
            onPressed: editable
                ? () async {
                    final ok = await _confirm(
                      '계정과 모든 기록을 삭제할까요?',
                      '모든 대화 원문, 개별 기록, 직접 수정한 하루 일기, AI 일기, 파생 기억과 차량 연결을 삭제합니다. 이 작업은 되돌릴 수 없습니다.',
                      action: '모두 삭제',
                      destructive: true,
                    );
                    if (ok && mounted) {
                      await _task(widget.onDeleteAccount, reload: false);
                    }
                  }
                : null,
            style: TextButton.styleFrom(
              foregroundColor: Theme.of(context).colorScheme.error,
            ),
            child: const Text('계정과 모든 기록 삭제'),
          ),
        ],
      ),
    );
  }

  Widget _notice(String text, {bool error = false}) => Semantics(
    liveRegion: true,
    child: Container(
      margin: const EdgeInsets.symmetric(vertical: 16),
      padding: const EdgeInsets.all(16),
      decoration: BoxDecoration(
        color: error
            ? Theme.of(context).colorScheme.errorContainer
            : Theme.of(context).colorScheme.surfaceContainerHighest,
        borderRadius: BorderRadius.circular(12),
      ),
      child: Text(
        text,
        style: TextStyle(
          color: error
              ? Theme.of(context).colorScheme.onErrorContainer
              : Theme.of(context).colorScheme.onSurfaceVariant,
        ),
      ),
    ),
  );

  Widget _heading(String title) => Padding(
    padding: const EdgeInsets.only(top: 32, bottom: 12),
    child: Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Divider(height: 1, color: Theme.of(context).colorScheme.outlineVariant),
        const SizedBox(height: 24),
        Text(title, style: Theme.of(context).textTheme.titleLarge),
      ],
    ),
  );

  Widget _choice({
    required String label,
    required String value,
    required Map<String, String> choices,
    required bool enabled,
    required ValueChanged<String> onChanged,
  }) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text(label, style: Theme.of(context).textTheme.labelLarge),
        const SizedBox(height: 8),
        RadioGroup<String>(
          groupValue: choices.containsKey(value) ? value : choices.keys.first,
          onChanged: (next) {
            if (enabled && next != null) onChanged(next);
          },
          child: Column(
            children: choices.entries
                .map(
                  (entry) => Padding(
                    padding: const EdgeInsets.only(bottom: 4),
                    child: Material(
                      color: entry.key == value
                          ? Theme.of(
                              context,
                            ).colorScheme.surfaceContainerHighest
                          : Colors.transparent,
                      borderRadius: BorderRadius.circular(12),
                      child: RadioListTile<String>(
                        contentPadding: const EdgeInsets.symmetric(
                          horizontal: 8,
                        ),
                        title: Text(entry.value),
                        value: entry.key,
                        enabled: enabled,
                      ),
                    ),
                  ),
                )
                .toList(),
          ),
        ),
      ],
    );
  }
}
