import 'package:material_ui/material_ui.dart';
import '../data/repository.dart';
import '../domain/models.dart';
import 'editors.dart';
import 'theme.dart';
import 'pairing.dart';

class JournalPage extends StatefulWidget {
  final DoranRepository repository;
  final bool history;
  final int refreshRevision;
  const JournalPage({
    super.key,
    required this.repository,
    this.history = false,
    this.refreshRevision = 0,
  });
  @override
  State<JournalPage> createState() => _JournalPageState();
}

class _JournalPageState extends State<JournalPage> {
  String date = kstDate();
  DoranDay? day;
  List<DoranRecord> records = [];
  String? cursor, error;
  bool loading = true, moreBusy = false, offline = false;
  int generation = 0;
  @override
  void initState() {
    super.initState();
    _load();
  }

  @override
  void didUpdateWidget(JournalPage old) {
    super.didUpdateWidget(old);
    if (old.history != widget.history) {
      _load();
    } else if (old.refreshRevision != widget.refreshRevision) {
      _load(preserveContent: true);
    }
  }

  Future<void> _load({bool preserveContent = false}) async {
    final version = ++generation;
    final previousCount = records.length;
    setState(() {
      loading = !preserveContent;
      error = null;
    });
    try {
      final page = await widget.repository.fetchRecordPage(
        date: widget.history ? null : date,
      );
      final refreshed = page.value.items.toList();
      var nextCursor = page.value.nextCursor;
      var fromCache = page.offline;
      // A background refresh retains the already loaded history depth, rather
      // than collapsing a long list back to its first page under the reader.
      while (preserveContent &&
          refreshed.length < previousCount &&
          nextCursor != null) {
        if (!mounted || version != generation) return;
        final next = await widget.repository.fetchRecordPage(
          date: widget.history ? null : date,
          cursor: nextCursor,
        );
        final ids = refreshed.map((item) => item.id).toSet();
        refreshed.addAll(
          next.value.items.where((item) => !ids.contains(item.id)),
        );
        if (next.value.nextCursor == nextCursor) break;
        nextCursor = next.value.nextCursor;
        fromCache = fromCache || next.offline;
      }
      final diary = widget.history
          ? null
          : await widget.repository.fetchDay(date);
      if (!mounted || version != generation) return;
      setState(() {
        records = refreshed;
        cursor = nextCursor;
        day = diary?.value;
        offline = fromCache || (diary?.offline ?? false);
      });
    } catch (e) {
      if (mounted && version == generation) {
        setState(() {
          error = friendlyError(e);
          if (e is ApiException && (e.status == 401 || e.status == 403)) {
            records = [];
            day = null;
            cursor = null;
          }
        });
      }
    } finally {
      if (mounted && version == generation) setState(() => loading = false);
    }
  }

  Future<void> _more() async {
    if (cursor == null || moreBusy) return;
    final version = generation;
    setState(() => moreBusy = true);
    try {
      final page = await widget.repository.fetchRecordPage(
        date: widget.history ? null : date,
        cursor: cursor,
      );
      if (mounted && generation == version) {
        setState(() {
          final ids = records.map((r) => r.id).toSet();
          records.addAll(page.value.items.where((r) => !ids.contains(r.id)));
          cursor = page.value.nextCursor;
          offline = offline || page.offline;
        });
      }
    } catch (e) {
      if (mounted) setState(() => error = friendlyError(e));
    } finally {
      if (mounted) setState(() => moreBusy = false);
    }
  }

  Future<void> _openDay({bool compare = false}) async {
    if (day == null) return;
    await Navigator.of(context).push(
      MaterialPageRoute<bool>(
        builder: (_) => DayEditor(
          repository: widget.repository,
          day: day!,
          compare: compare,
        ),
      ),
    );
    if (mounted) _load();
  }

  Future<void> _deleteDay() async {
    final ok = await showDialog<bool>(
      context: context,
      builder: (c) => AlertDialog(
        title: const Text('이 날짜의 기록을 모두 삭제할까요?'),
        content: Text(
          '$date의 대화 원문, 개별 기록, AI 일기와 직접 수정한 일기, 여기서 만든 개인화 정보가 삭제됩니다. 되돌릴 수 없어요.',
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(c, false),
            child: const Text('취소'),
          ),
          TextButton(
            onPressed: () => Navigator.pop(c, true),
            child: const Text('모두 삭제'),
          ),
        ],
      ),
    );
    if (ok != true || day == null) return;
    try {
      await widget.repository.deleteDay(day!);
      if (mounted) _load();
    } catch (e) {
      if (mounted) setState(() => error = friendlyError(e));
    }
  }

  @override
  Widget build(BuildContext context) => RefreshIndicator(
    onRefresh: _load,
    child: ListView(
      physics: const AlwaysScrollableScrollPhysics(),
      padding: const EdgeInsets.fromLTRB(24, 16, 24, 32),
      children: [
        Row(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Expanded(
              child: Text(
                widget.history
                    ? '나의 기록'
                    : date == kstDate()
                    ? '오늘의 이야기'
                    : '그날의 이야기',
                style: Theme.of(context).textTheme.displaySmall,
              ),
            ),
            IconButton(
              tooltip: '새로고침',
              onPressed: loading ? null : _load,
              icon: const Icon(Icons.refresh),
            ),
          ],
        ),
        const SizedBox(height: 8),
        Text(
          widget.history ? '차 안에서 시작한 이야기들을 모았어요' : '나눈 이야기를 천천히 돌아보세요',
          style: TextStyle(
            color: Theme.of(context).colorScheme.onSurfaceVariant,
          ),
        ),
        const SizedBox(height: 24),
        if (!widget.history)
          Align(
            alignment: Alignment.centerLeft,
            child: TextButton.icon(
              onPressed: () async {
                final picked = await showDatePicker(
                  context: context,
                  initialDate: DateTime.parse(date),
                  firstDate: DateTime(2000),
                  lastDate: DateTime.now().add(const Duration(days: 366)),
                );
                if (picked != null && mounted) {
                  date =
                      '${picked.year}-${picked.month.toString().padLeft(2, '0')}-${picked.day.toString().padLeft(2, '0')}';
                  _load();
                }
              },
              icon: const Icon(Icons.calendar_today_outlined, size: 18),
              label: Text(date),
            ),
          ),
        if (offline)
          const Notice(
            '오프라인 · 이 기기에 저장된 기록입니다. 변경 사항은 연결 후 저장해 주세요.',
            icon: Icons.cloud_off_outlined,
          ),
        if (error != null) Notice(error!, icon: Icons.error_outline),
        if (loading)
          const Padding(
            padding: EdgeInsets.all(48),
            child: Center(child: CircularProgressIndicator()),
          )
        else ...[
          if (!widget.history && day != null && day!.status != 'empty') ...[
            Container(
              padding: const EdgeInsets.all(24),
              decoration: BoxDecoration(
                color: Colors.white,
                border: Border.all(
                  color: Theme.of(context).colorScheme.outlineVariant,
                ),
                borderRadius: BorderRadius.circular(16),
              ),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Row(
                    children: [
                      Expanded(
                        child: Text(
                          '하루 일기',
                          style: Theme.of(context).textTheme.titleLarge,
                        ),
                      ),
                      if (day!.isManual)
                        const Text('직접 다듬은 글', style: TextStyle(fontSize: 12)),
                    ],
                  ),
                  const SizedBox(height: 20),
                  Text(
                    day!.displayText.isEmpty
                        ? '아직 작성된 내용이 없어요'
                        : day!.displayText,
                    style: Theme.of(context).textTheme.bodyLarge,
                  ),
                  const SizedBox(height: 20),
                  if (day!.status == 'pending')
                    const Notice('새 기록을 반영하고 있어요. 기존 일기는 계속 읽을 수 있어요.'),
                  if (day!.status == 'failed')
                    const Notice('일기 갱신이 지연되고 있어요. 개별 기록은 안전하게 보관되어 있습니다.'),
                  Wrap(
                    spacing: 8,
                    runSpacing: 8,
                    children: [
                      OutlinedButton.icon(
                        onPressed: () => _openDay(),
                        icon: const Icon(Icons.edit_outlined, size: 18),
                        label: const Text('다듬기'),
                      ),
                      if (day!.hasNewGenerated)
                        FilledButton(
                          onPressed: () => _openDay(compare: true),
                          child: const Text('새 AI 일기 비교'),
                        ),
                    ],
                  ),
                ],
              ),
            ),
            const SizedBox(height: 32),
          ],
          if (records.isEmpty && error == null)
            Padding(
              padding: const EdgeInsets.symmetric(vertical: 32),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  const Icon(Icons.forum_outlined, size: 36),
                  const SizedBox(height: 24),
                  Text(
                    '아직 나눈 이야기가 없어요',
                    style: Theme.of(context).textTheme.headlineSmall,
                  ),
                  const SizedBox(height: 12),
                  const Text('차량에서 도란을 열고 대화를 마치면\n이곳에 기록과 하루 일기가 도착해요.'),
                  const SizedBox(height: 24),
                  OutlinedButton(
                    onPressed: () async {
                      await Navigator.of(context).push(
                        MaterialPageRoute<void>(
                          builder: (_) =>
                              PairingPage(repository: widget.repository),
                        ),
                      );
                      if (mounted) _load();
                    },
                    child: const Text('차량 연결하기'),
                  ),
                ],
              ),
            ),
          if (records.isNotEmpty) ...[
            Text(
              widget.history ? '모든 개별 기록' : '개별 기록 ${records.length}개',
              style: Theme.of(context).textTheme.titleLarge,
            ),
            const SizedBox(height: 16),
            ...records.map((r) => _record(r)),
            if (cursor != null)
              OutlinedButton(
                onPressed: moreBusy ? null : _more,
                child: Text(moreBusy ? '불러오는 중' : '기록 더 불러오기'),
              ),
          ],
          if (!widget.history && records.isNotEmpty && day != null)
            Padding(
              padding: const EdgeInsets.only(top: 24),
              child: TextButton(
                onPressed: offline ? null : _deleteDay,
                child: const Text('이 날짜의 모든 기록 삭제'),
              ),
            ),
        ],
      ],
    ),
  );
  Widget _record(DoranRecord record) => Padding(
    padding: const EdgeInsets.only(bottom: 12),
    child: Material(
      color: Colors.white,
      borderRadius: BorderRadius.circular(12),
      child: InkWell(
        borderRadius: BorderRadius.circular(12),
        onTap: () async {
          await Navigator.of(context).push(
            MaterialPageRoute<bool>(
              builder: (_) =>
                  RecordEditor(repository: widget.repository, record: record),
            ),
          );
          if (mounted) _load();
        },
        child: Padding(
          padding: const EdgeInsets.all(20),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Row(
                children: [
                  Expanded(
                    child: Text(
                      '${record.date} · ${record.createdAt.toUtc().add(const Duration(hours: 9)).hour.toString().padLeft(2, '0')}:${record.createdAt.minute.toString().padLeft(2, '0')}',
                      style: TextStyle(
                        color: Theme.of(context).colorScheme.onSurfaceVariant,
                        fontSize: 13,
                      ),
                    ),
                  ),
                  const Icon(Icons.chevron_right, size: 20),
                ],
              ),
              const SizedBox(height: 12),
              Text(
                record.text.isEmpty ? '대화 내용을 정리하고 있어요' : record.text,
                maxLines: 3,
                overflow: TextOverflow.ellipsis,
              ),
              if (record.excluded || record.status != 'ready')
                Padding(
                  padding: const EdgeInsets.only(top: 12),
                  child: Text(
                    record.excluded
                        ? '개인화에서 제외'
                        : record.status == 'failed'
                        ? 'AI 정리 다시 시도 가능'
                        : 'AI가 정리하는 중',
                    style: TextStyle(
                      fontSize: 12,
                      color: Theme.of(context).colorScheme.primary,
                    ),
                  ),
                ),
            ],
          ),
        ),
      ),
    ),
  );
}
