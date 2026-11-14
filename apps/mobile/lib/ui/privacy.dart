import 'package:material_ui/material_ui.dart';

/// The app's data-use notice. No claim of provider zero data retention is made.
class PrivacyPage extends StatelessWidget {
  const PrivacyPage({super.key});

  @override
  Widget build(BuildContext context) => Scaffold(
    appBar: AppBar(title: const Text('기록과 개인정보')),
    body: Center(
      child: ConstrainedBox(
        constraints: const BoxConstraints(maxWidth: 680),
        child: ListView(
          padding: const EdgeInsets.fromLTRB(24, 16, 24, 40),
          children: const [
            _PrivacySection(
              title: '대화는 어떻게 기록되나요',
              body:
                  '차량의 Pleos 음성 인식 기능으로 말을 글로 바꿉니다. 도란은 음성 원본 파일을 보관하거나 외부 AI에 전송하지 않습니다. 인식된 대화 텍스트와 정리된 기록은 연결된 개인 계정에 저장합니다.',
            ),
            _PrivacySection(
              title: 'AI와 클라우드 처리',
              body:
                  '대화 응답과 일기 정리를 위해 인식된 텍스트, 현재 대화에 필요한 과거 기록과 허용된 차량 맥락을 OpenAI에 전달합니다. 계정 인증과 기록 저장에는 Supabase를 사용합니다. 외부 사업자의 처리·보관 정책이 적용되며, 도란의 삭제가 외부 사업자의 모든 보관 자료를 즉시 지운다는 뜻은 아닙니다.',
            ),
            _PrivacySection(
              title: '차량 정보는 선택이에요',
              body:
                  '차량 정보 활용을 켠 경우에만 지원되는 목적지 도착 여부, 남은 시간, 이동 시간·거리, 좌석 상태 등을 대화에 참고합니다. 연속 이동 경로나 원시 좌표는 도란의 클라우드 API에 전송하지 않습니다. 차량 정보 활용을 끄더라도 대화와 기록 기능은 사용할 수 있습니다.',
            ),
            _PrivacySection(
              title: '개인화에서 제외하거나 삭제하기',
              body:
                  '특정 기록을 개인화에서 제외하면 기록 자체는 남아 있지만 이후 질문을 위한 검색에서 제외됩니다. 기록을 삭제하면 연결된 대화 원문·검색 정보·파생 기억을 제거하고 해당 날짜의 AI 일기를 다시 정리합니다. 직접 수정한 하루 일기도 함께 삭제할지는 삭제 화면에서 선택합니다. 개인화 정보만 삭제한 경우에는 원본 기록이 남아 있으므로, 다시 참고하지 않으려면 해당 기록도 개인화에서 제외해 주세요.',
            ),
            _PrivacySection(
              title: '보관과 기기 연결',
              body:
                  '기록은 사용자가 삭제하거나 계정을 삭제할 때까지 계정에 보관합니다. 모바일에는 다시 읽기와 임시 글 보관을 위한 암호화된 사본이 저장될 수 있습니다. 로그아웃하면 해당 계정의 기기 내 기록과 임시 글을 지웁니다. 차량 연결은 설정에서 해제할 수 있으며, 해제한 차량의 새 요청은 허용하지 않습니다.',
            ),
            _PrivacySection(
              title: '내가 정한 범위에서 사용하기',
              body:
                  '대화·기록 처리 동의를 철회하면 새 대화 처리가 중단됩니다. 기존 기록의 삭제는 별도 기능으로 제공합니다. 계정 삭제는 모든 기록과 일기, 파생 기억, 차량 연결을 삭제하는 작업이며 되돌릴 수 없습니다. 오프라인에서 보이는 내용은 마지막으로 저장된 사본일 수 있습니다.',
            ),
          ],
        ),
      ),
    ),
  );
}

class _PrivacySection extends StatelessWidget {
  const _PrivacySection({required this.title, required this.body});
  final String title;
  final String body;

  @override
  Widget build(BuildContext context) => Padding(
    padding: const EdgeInsets.only(bottom: 32),
    child: Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text(title, style: Theme.of(context).textTheme.titleLarge),
        const SizedBox(height: 16),
        Text(
          body,
          style: Theme.of(context).textTheme.bodyMedium?.copyWith(height: 1.8),
        ),
      ],
    ),
  );
}
