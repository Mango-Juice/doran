import 'package:material_ui/material_ui.dart';
import '../data/repository.dart';
import '../domain/models.dart';
import 'theme.dart';

class RecordEditor extends StatefulWidget {
  final DoranRepository repository;
  final DoranRecord record;
  const RecordEditor({
    super.key,
    required this.repository,
    required this.record,
  });
  @override
  State<RecordEditor> createState() => _RecordEditorState();
}

class _RecordEditorState extends State<RecordEditor> {
  late DoranRecord record = widget.record;
  late final TextEditingController text = TextEditingController(
    text: record.text,
  );
  late String date = record.date;
  late bool excluded = record.excluded;
  bool busy = false, restored = false, userEdited = false;
  String? error;
  String get draftId => 'record:${record.id}';
  @override
  void initState() {
    super.initState();
    _restore();
  }

  Future<void> _restore() async {
    try {
      final saved = await widget.repository.loadDraft(draftId);
      if (mounted && !userEdited && saved != null && saved != record.text) {
        text.text = saved;
        setState(() => restored = true);
      }
    } catch (e) {
      if (mounted) setState(() => error = friendlyError(e));
    }
  }

  Future<void> _persistDraft(String value) async {
    try {
      await widget.repository.saveDraft(draftId, value);
    } catch (e) {
      if (mounted) setState(() => error = friendlyError(e));
    }
  }

  @override
  void dispose() {
    text.dispose();
    super.dispose();
  }

  Future<void> _save() async {
    setState(() {
      busy = true;
      error = null;
    });
    try {
      await widget.repository.saveDraft(draftId, text.text);
      await widget.repository.updateRecord(
        record,
        text: text.text,
        recordDate: date != widget.record.date ? date : null,
        excluded: excluded != widget.record.excluded ? excluded : null,
      );
      await widget.repository.clearDraft(draftId);
      if (mounted) Navigator.pop(context, true);
    } on ApiException catch (e) {
      if (e.status == 409) {
        await _conflict();
      } else {
        if (mounted) setState(() => error = e.message);
      }
    } catch (e) {
      if (mounted) setState(() => error = friendlyError(e));
    } finally {
      if (mounted) setState(() => busy = false);
    }
  }

  Future<void> _conflict() async {
    try {
      final newest = DoranRecord.fromJson(
        await widget.repository.request('GET', '/records/${record.id}'),
      );
      if (!mounted) return;
      final replace = await showDialog<bool>(
        context: context,
        builder: (c) => AlertDialog(
          title: const Text('다른 곳에서 기록이 바뀌었어요'),
          content: SingleChildScrollView(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              mainAxisSize: MainAxisSize.min,
              children: [
                const Text('최신 기록을 확인한 뒤 선택해 주세요. 작성 중인 글은 이 기기에 남아 있어요.'),
                const SizedBox(height: 16),
                const Text(
                  '서버의 최신 기록',
                  style: TextStyle(fontWeight: FontWeight.w600),
                ),
                const SizedBox(height: 8),
                Text(newest.text),
              ],
            ),
          ),
          actions: [
            TextButton(
              onPressed: () => Navigator.pop(c, false),
              child: const Text('계속 수정'),
            ),
            TextButton(
              onPressed: () => Navigator.pop(c, true),
              child: const Text('내 글로 덮어쓰기'),
            ),
          ],
        ),
      );
      if (replace == true) {
        record = newest;
        await widget.repository.updateRecord(
          record,
          text: text.text,
          recordDate: date != widget.record.date ? date : null,
          excluded: excluded != widget.record.excluded ? excluded : null,
        );
        await widget.repository.clearDraft(draftId);
        if (mounted) Navigator.pop(context, true);
      }
    } catch (e) {
      if (mounted) setState(() => error = friendlyError(e));
    }
  }

  Future<void> _delete() async {
    bool deleteManual = false;
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (c) => StatefulBuilder(
        builder: (c, set) => AlertDialog(
          title: const Text('이 기록을 삭제할까요?'),
          content: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              const Text('대화 원문과 이 기록에서 만든 개인화 정보가 함께 삭제되고 AI 하루 일기가 갱신됩니다.'),
              CheckboxListTile(
                contentPadding: EdgeInsets.zero,
                title: const Text('이 날짜에 직접 수정한 하루 일기도 삭제'),
                value: deleteManual,
                onChanged: (v) => set(() => deleteManual = v ?? false),
              ),
            ],
          ),
          actions: [
            TextButton(
              onPressed: () => Navigator.pop(c, false),
              child: const Text('취소'),
            ),
            TextButton(
              onPressed: () => Navigator.pop(c, true),
              child: const Text('삭제'),
            ),
          ],
        ),
      ),
    );
    if (confirmed != true || !mounted) return;
    setState(() => busy = true);
    try {
      await widget.repository.deleteRecord(
        record,
        deleteManualDiary: deleteManual,
      );
      if (mounted) Navigator.pop(context, true);
    } catch (e) {
      if (mounted) setState(() => error = friendlyError(e));
    } finally {
      if (mounted) setState(() => busy = false);
    }
  }

  @override
  Widget build(BuildContext context) => Scaffold(
    appBar: AppBar(
      title: const Text('개별 기록'),
      actions: [
        IconButton(
          tooltip: '기록 삭제',
          onPressed: busy ? null : _delete,
          icon: const Icon(Icons.delete_outline),
        ),
      ],
    ),
    body: SafeArea(
      child: Column(
        children: [
          Expanded(
            child: ListView(
              padding: const EdgeInsets.all(24),
              children: [
                if (restored) const Notice('저장하지 못한 글을 불러왔어요. 확인한 뒤 저장해 주세요.'),
                if (error != null) Notice(error!, icon: Icons.error_outline),
                if (record.status != 'ready')
                  Notice(
                    record.status == 'failed'
                        ? 'AI 정리를 마치지 못했어요. 대화 내용은 보관되어 있습니다.'
                        : '대화는 저장되었고 AI가 기록을 정리하고 있어요.',
                  ),
                OutlinedButton.icon(
                  icon: const Icon(Icons.calendar_today_outlined),
                  label: Text(date),
                  onPressed: busy
                      ? null
                      : () async {
                          final result = await showDatePicker(
                            context: context,
                            initialDate: DateTime.parse(date),
                            firstDate: DateTime(2000),
                            lastDate: DateTime.now().add(
                              const Duration(days: 366),
                            ),
                          );
                          if (result != null && mounted) {
                            setState(
                              () => date =
                                  '${result.year}-${result.month.toString().padLeft(2, '0')}-${result.day.toString().padLeft(2, '0')}',
                            );
                          }
                        },
                ),
                const SizedBox(height: 20),
                TextField(
                  controller: text,
                  enabled: !busy,
                  minLines: 7,
                  maxLines: null,
                  maxLength: 12000,
                  decoration: const InputDecoration(
                    labelText: '기록 내용',
                    hintText: '기억하고 싶은 말로 다듬어 보세요',
                    alignLabelWithHint: true,
                  ),
                  onChanged: (v) {
                    userEdited = true;
                    _persistDraft(v);
                  },
                ),
                const SizedBox(height: 16),
                SwitchListTile.adaptive(
                  contentPadding: EdgeInsets.zero,
                  title: const Text('개인화에서 제외'),
                  subtitle: const Text('일기로는 보관하고, 이후 대화의 참고 자료로 사용하지 않아요'),
                  value: excluded,
                  onChanged: busy ? null : (v) => setState(() => excluded = v),
                ),

                if (record.status == 'failed')
                  TextButton(
                    onPressed: busy
                        ? null
                        : () async {
                            try {
                              await widget.repository.retryRecord(record.id);
                              if (context.mounted) Navigator.pop(context, true);
                            } catch (e) {
                              if (mounted) {
                                setState(() => error = friendlyError(e));
                              }
                            }
                          },
                    child: const Text('AI 정리 다시 시도'),
                  ),
              ],
            ),
          ),
          EditorActionBar(
            label: busy ? '저장 중' : '변경 사항 저장',
            onPressed: busy ? null : _save,
          ),
        ],
      ),
    ),
  );
}

class DayEditor extends StatefulWidget {
  final DoranRepository repository;
  final DoranDay day;
  final bool compare;
  const DayEditor({
    super.key,
    required this.repository,
    required this.day,
    this.compare = false,
  });
  @override
  State<DayEditor> createState() => _DayEditorState();
}

class _DayEditorState extends State<DayEditor> {
  late DoranDay day = widget.day;
  late final TextEditingController text = TextEditingController(
    text: day.displayText,
  );
  bool busy = false, restored = false, userEdited = false;
  String? error;
  String get draftId => 'day:${day.date}';
  @override
  void initState() {
    super.initState();
    _restore();
  }

  Future<void> _restore() async {
    try {
      final saved = await widget.repository.loadDraft(draftId);
      if (mounted && !userEdited && saved != null && saved != day.displayText) {
        text.text = saved;
        setState(() => restored = true);
      }
    } catch (e) {
      if (mounted) setState(() => error = friendlyError(e));
    }
  }

  Future<void> _persistDraft(String value) async {
    try {
      await widget.repository.saveDraft(draftId, value);
    } catch (e) {
      if (mounted) setState(() => error = friendlyError(e));
    }
  }

  @override
  void dispose() {
    text.dispose();
    super.dispose();
  }

  Future<void> _save() async {
    setState(() {
      busy = true;
      error = null;
    });
    try {
      await widget.repository.saveDraft(draftId, text.text);
      await widget.repository.saveDay(day, text.text);
      await widget.repository.clearDraft(draftId);
      if (mounted) Navigator.pop(context, true);
    } on ApiException catch (e) {
      if (e.status == 409) {
        if (mounted) {
          setState(
            () => error =
                '일기가 새로 갱신되었어요. 내 글은 기기에 보관했어요. 뒤로 돌아가 최신 일기를 확인한 뒤 다시 열어 주세요.',
          );
        }
      } else {
        if (mounted) setState(() => error = e.message);
      }
    } catch (e) {
      if (mounted) setState(() => error = friendlyError(e));
    } finally {
      if (mounted) setState(() => busy = false);
    }
  }

  Future<void> _adopt() async {
    final ok = await showDialog<bool>(
      context: context,
      builder: (c) => AlertDialog(
        title: const Text('새 AI 일기를 반영할까요?'),
        content: const Text(
          '화면에 보이는 글이 새 AI 일기로 바뀝니다. 직접 수정한 글을 유지하려면 취소해 주세요.',
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(c, false),
            child: const Text('내 글 유지'),
          ),
          TextButton(
            onPressed: () => Navigator.pop(c, true),
            child: const Text('새 일기 반영'),
          ),
        ],
      ),
    );
    if (ok != true || !mounted) return;
    setState(() => busy = true);
    try {
      await widget.repository.adoptDay(day);
      await widget.repository.clearDraft(draftId);
      if (mounted) Navigator.pop(context, true);
    } catch (e) {
      if (mounted) setState(() => error = friendlyError(e));
    } finally {
      if (mounted) setState(() => busy = false);
    }
  }

  @override
  Widget build(BuildContext context) => Scaffold(
    appBar: AppBar(title: Text(widget.compare ? '새 일기 비교' : '하루 일기 다듬기')),
    body: SafeArea(
      child: Column(
        children: [
          Expanded(
            child: ListView(
              padding: const EdgeInsets.all(24),
              children: [
                Text(day.date, style: Theme.of(context).textTheme.titleLarge),
                const SizedBox(height: 16),
                if (error != null) Notice(error!, icon: Icons.error_outline),
                if (restored) const Notice('이 기기에 보관한 작성 중인 글을 불러왔어요'),
                const Notice('직접 수정한 글은 새 기록이 추가되어도 자동으로 덮어쓰지 않아요'),
                TextField(
                  controller: text,
                  enabled: !busy,
                  minLines: widget.compare ? 4 : 7,
                  maxLines: null,
                  maxLength: 20000,
                  decoration: const InputDecoration(
                    labelText: '내 하루 일기',
                    hintText: '내 말투로 오늘을 남겨 보세요',
                    alignLabelWithHint: true,
                  ),
                  onChanged: (v) {
                    userEdited = true;
                    _persistDraft(v);
                  },
                ),

                if (widget.compare && !day.hasGeneratedText)
                  const Notice('비교할 새 일기 내용이 없어요. 직접 수정한 글은 그대로 보관돼요.'),
                if (day.hasGeneratedText &&
                    (widget.compare || day.hasComparableDraft)) ...[
                  const SizedBox(height: 24),
                  Container(
                    padding: const EdgeInsets.all(20),
                    decoration: BoxDecoration(
                      color: const Color(0xffeaf0f3),
                      borderRadius: BorderRadius.circular(16),
                    ),
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text(
                          '새 AI 일기',
                          style: Theme.of(context).textTheme.titleLarge,
                        ),
                        const SizedBox(height: 8),
                        const Text(
                          '새 기록까지 반영한 초안',
                          style: TextStyle(
                            color: DoranColors.secondary,
                            fontSize: 14,
                          ),
                        ),
                        const SizedBox(height: 20),
                        SelectableText(
                          day.generatedText,
                          style: Theme.of(context).textTheme.bodyLarge,
                        ),
                        const SizedBox(height: 24),
                        OutlinedButton(
                          onPressed: busy ? null : _adopt,
                          child: const Text('새 AI 일기 전체 반영'),
                        ),
                      ],
                    ),
                  ),
                ],
              ],
            ),
          ),
          EditorActionBar(
            label: busy ? '저장 중' : '내 글 저장',
            onPressed: busy ? null : _save,
          ),
        ],
      ),
    ),
  );
}
