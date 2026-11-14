import 'dart:async';

import 'package:material_ui/material_ui.dart';
import 'package:flutter/services.dart';

import '../data/repository.dart';

class PairingPage extends StatefulWidget {
  const PairingPage({super.key, required this.repository});
  final DoranRepository repository;

  @override
  State<PairingPage> createState() => _PairingPageState();
}

class _PairingPageState extends State<PairingPage> with WidgetsBindingObserver {
  final _code = TextEditingController();
  Timer? _timer;
  String? _pairingId;
  String? _error;
  String _deviceLabel = '차량';
  DateTime? _expiresAt;
  bool _claiming = false;
  bool _polling = false;
  bool _paired = false;
  bool _expired = false;
  bool _visible = true;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    _visible = state == AppLifecycleState.resumed;
    if (!_visible) {
      _timer?.cancel();
    } else if (_pairingId != null && !_paired && !_expired) {
      _beginPolling();
    }
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    _timer?.cancel();
    _code.dispose();
    super.dispose();
  }

  String _message(Object error) => error is ApiException
      ? error.message
      : '연결 상태를 확인하지 못했어요. 인터넷 연결 후 다시 시도해 주세요';

  Future<void> _claim() async {
    if (_claiming || _code.text.length != 6) return;
    setState(() {
      _claiming = true;
      _error = null;
    });
    try {
      final result = await widget.repository.claimPairing(_code.text);
      if (!mounted) return;
      final id = result['id'] as String;
      final expiry = DateTime.parse(result['expires_at'] as String);
      setState(() {
        _pairingId = id;
        _expiresAt = expiry;
        _deviceLabel = result['device_label'] as String? ?? '차량';
        _expired = false;
      });
      FocusManager.instance.primaryFocus?.unfocus();
      _beginPolling();
    } catch (error) {
      if (mounted) setState(() => _error = _message(error));
    } finally {
      if (mounted) setState(() => _claiming = false);
    }
  }

  void _beginPolling() {
    _timer?.cancel();
    if (!_visible || _paired || _expired) return;
    unawaited(_poll());
    _timer = Timer.periodic(const Duration(seconds: 2), (_) => _poll());
  }

  Future<void> _poll() async {
    final id = _pairingId;
    if (id == null || _polling || !_visible || !mounted) return;
    _polling = true;
    try {
      final result = await widget.repository.pairing(id);
      if (!mounted || _pairingId != id) return;
      // Always check the server first: the car may have confirmed while this
      // screen was in the background, even if the local expiry has now passed.
      final status = result['status'];
      final expired =
          status != 'paired' &&
          (status == 'expired' ||
              (_expiresAt != null && DateTime.now().isAfter(_expiresAt!)));
      if (status == 'paired' || expired) {
        _timer?.cancel();
      }
      setState(() {
        _paired = status == 'paired';
        _expired = expired;
        _error = null;
      });
    } catch (error) {
      if (!mounted) return;
      if (error is ApiException &&
          (error.status == 401 || error.status == 403 || error.status == 404)) {
        _timer?.cancel();
      }
      setState(() => _error = _message(error));
    } finally {
      _polling = false;
    }
  }

  void _reset() {
    _timer?.cancel();
    setState(() {
      _pairingId = null;
      _expiresAt = null;
      _paired = false;
      _expired = false;
      _error = null;
      _code.clear();
    });
  }

  @override
  Widget build(BuildContext context) => Scaffold(
    appBar: AppBar(title: const Text('차량 연결')),
    body: ListView(
      padding: const EdgeInsets.fromLTRB(24, 24, 24, 40),
      children: [
        _ConnectionProgress(
          confirmed: _paired,
          requested: _pairingId != null && !_expired,
        ),
        const SizedBox(height: 32),
        Text(
          _paired
              ? '차량과 연결했어요'
              : _expired
              ? '연결 코드가 만료됐어요'
              : _pairingId == null
              ? '차량에 표시된 코드를 입력하세요'
              : '차량 화면에서 연결을 확인해 주세요',
          style: Theme.of(
            context,
          ).textTheme.headlineSmall?.copyWith(fontWeight: FontWeight.w500),
        ),
        const SizedBox(height: 16),
        Text(
          _paired
              ? '차량에서 나눈 대화가 이 계정의 기록으로 이어집니다. 차량 연결은 설정에서 언제든 해제할 수 있어요.'
              : _expired
              ? '차량의 도란 앱에서 새 코드를 발급한 뒤 다시 연결해 주세요.'
              : _pairingId == null
              ? '차량이 주차된 상태에서 도란 앱을 열어 주세요. 차량에 표시되는 6자리 코드는 5분 동안 한 번만 사용할 수 있어요.'
              : '$_deviceLabel에 연결을 요청했어요. 본인의 차량인지 확인한 뒤 차량 화면에서 승인하면 연결이 완료됩니다.',
          style: Theme.of(context).textTheme.bodyLarge?.copyWith(height: 1.6),
        ),
        const SizedBox(height: 32),
        if (_pairingId == null) ...[
          TextField(
            controller: _code,
            enabled: !_claiming,
            keyboardType: TextInputType.number,
            textInputAction: TextInputAction.done,
            autocorrect: false,
            inputFormatters: [
              FilteringTextInputFormatter.digitsOnly,
              LengthLimitingTextInputFormatter(6),
            ],
            textAlign: TextAlign.center,
            decoration: const InputDecoration(
              labelText: '6자리 연결 코드',
              hintText: '000000',
              floatingLabelBehavior: FloatingLabelBehavior.always,
              contentPadding: EdgeInsets.symmetric(
                horizontal: 16,
                vertical: 24,
              ),
            ),
            style: Theme.of(context).textTheme.headlineSmall?.copyWith(
              letterSpacing: MediaQuery.textScalerOf(context).scale(1) > 1.5
                  ? 2
                  : 6,
              fontSize: MediaQuery.textScalerOf(context).scale(1) > 1.5
                  ? 24
                  : 32,
              fontWeight: FontWeight.w500,
            ),
            onChanged: (_) => setState(() {}),
            onSubmitted: (_) => _claim(),
          ),
          const SizedBox(height: 20),
          FilledButton(
            onPressed: !_claiming && _code.text.length == 6 ? _claim : null,
            child: Text(_claiming ? '연결 요청 중' : '연결 요청'),
          ),
        ],
        if (_pairingId != null && !_paired && !_expired) ...[
          const LinearProgressIndicator(semanticsLabel: '차량의 연결 승인을 기다리는 중'),
          const SizedBox(height: 20),
          OutlinedButton(
            onPressed: _polling ? null : _poll,
            child: const Text('연결 상태 확인'),
          ),
        ],
        if (_expired)
          FilledButton(onPressed: _reset, child: const Text('새 코드 입력')),
        if (_paired)
          FilledButton(
            onPressed: () => Navigator.of(context).pop(true),
            child: const Text('완료'),
          ),
        if (_error != null) ...[
          const SizedBox(height: 20),
          Semantics(
            liveRegion: true,
            child: Text(
              _error!,
              style: TextStyle(color: Theme.of(context).colorScheme.error),
            ),
          ),
        ],
      ],
    ),
  );
}

class _ConnectionProgress extends StatelessWidget {
  const _ConnectionProgress({required this.confirmed, required this.requested});
  final bool confirmed;
  final bool requested;
  @override
  Widget build(BuildContext context) {
    final color = Theme.of(context).colorScheme;
    return Semantics(
      label: confirmed
          ? '차량 연결 완료'
          : requested
          ? '차량 승인 단계'
          : '연결 코드 입력 단계',
      child: ExcludeSemantics(
        child: Row(
          children: [
            Icon(Icons.smartphone_outlined, size: 32, color: color.primary),
            const SizedBox(width: 16),
            Expanded(
              child: Container(
                height: 2,
                color: requested ? color.primary : color.outlineVariant,
              ),
            ),
            Padding(
              padding: const EdgeInsets.symmetric(horizontal: 16),
              child: Icon(
                confirmed ? Icons.check_circle_outline : Icons.link,
                size: 24,
                color: color.primary,
              ),
            ),
            Expanded(
              child: Container(
                height: 2,
                color: confirmed ? color.primary : color.outlineVariant,
              ),
            ),
            const SizedBox(width: 16),
            Icon(
              Icons.directions_car_outlined,
              size: 32,
              color: confirmed ? color.primary : color.onSurfaceVariant,
            ),
          ],
        ),
      ),
    );
  }
}
