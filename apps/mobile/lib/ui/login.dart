import 'package:material_ui/material_ui.dart';
import 'privacy.dart';

class LoginPage extends StatefulWidget {
  final Future<void> Function() onLogin;
  final String? authError;
  const LoginPage({super.key, required this.onLogin, this.authError});
  @override
  State<LoginPage> createState() => _LoginPageState();
}

class _LoginPageState extends State<LoginPage> {
  bool busy = false;
  String? error;
  @override
  Widget build(BuildContext context) => Scaffold(
    body: SafeArea(
      child: Center(
        child: SingleChildScrollView(
          padding: const EdgeInsets.symmetric(horizontal: 28, vertical: 40),
          child: ConstrainedBox(
            constraints: const BoxConstraints(maxWidth: 520),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text('도란', style: Theme.of(context).textTheme.titleLarge),
                const SizedBox(height: 64),
                Container(
                  width: 56,
                  height: 56,
                  decoration: BoxDecoration(
                    border: Border.all(
                      color: Theme.of(context).colorScheme.primary,
                      width: 2,
                    ),
                    borderRadius: const BorderRadius.only(
                      topLeft: Radius.circular(20),
                      topRight: Radius.circular(20),
                      bottomRight: Radius.circular(20),
                    ),
                  ),
                  child: Icon(
                    Icons.notes_rounded,
                    color: Theme.of(context).colorScheme.primary,
                    size: 28,
                  ),
                ),
                const SizedBox(height: 28),
                Text(
                  '차 안에서 나눈 이야기,\n내 하루의 기록으로',
                  style: Theme.of(context).textTheme.displaySmall,
                ),
                const SizedBox(height: 20),
                const Text(
                  '운전할 때는 편하게 이야기하고,\n여기서는 천천히 읽고 다듬어요.',
                  style: TextStyle(fontSize: 17, height: 1.8),
                ),
                const SizedBox(height: 44),
                SizedBox(
                  width: double.infinity,
                  child: OutlinedButton(
                    style: OutlinedButton.styleFrom(
                      backgroundColor: Colors.white,
                      disabledBackgroundColor: Colors.white,
                      foregroundColor: const Color(0xff1f1f1f),
                      disabledForegroundColor: const Color(0xff5f6368),
                      overlayColor: const Color(0xff1f1f1f),
                      side: const BorderSide(color: Color(0xff747775)),
                      shape: const StadiumBorder(),
                      padding: EdgeInsets.symmetric(
                        horizontal:
                            Theme.of(context).platform == TargetPlatform.iOS
                            ? 16
                            : 12,
                        vertical: 16,
                      ),
                      textStyle: const TextStyle(
                        fontFamily: 'GoogleSans',
                        fontSize: 14,
                        height: 20 / 14,
                        fontWeight: FontWeight.w500,
                      ),
                    ),
                    onPressed: busy
                        ? null
                        : () async {
                            setState(() {
                              busy = true;
                              error = null;
                            });
                            try {
                              await widget.onLogin();
                            } catch (_) {
                              if (mounted) {
                                setState(
                                  () => error =
                                      '로그인을 시작하지 못했어요. 연결을 확인하고 다시 시도해 주세요.',
                                );
                              }
                            } finally {
                              if (mounted) setState(() => busy = false);
                            }
                          },
                    child: Row(
                      children: [
                        Image.asset(
                          'assets/google/g-logo.png',
                          width: 20,
                          height: 20,
                          fit: BoxFit.contain,
                          excludeFromSemantics: true,
                        ),
                        SizedBox(
                          width:
                              Theme.of(context).platform == TargetPlatform.iOS
                              ? 12
                              : 10,
                        ),
                        Expanded(
                          child: Text(
                            busy ? '로그인 여는 중' : 'Google로 계속하기',
                            textAlign: TextAlign.center,
                          ),
                        ),
                        if (busy) ...[
                          const SizedBox(width: 10),
                          const SizedBox(
                            width: 16,
                            height: 16,
                            child: CircularProgressIndicator(
                              strokeWidth: 2,
                              color: Color(0xff1f1f1f),
                            ),
                          ),
                        ],
                      ],
                    ),
                  ),
                ),
                if (error != null || widget.authError != null)
                  Padding(
                    padding: const EdgeInsets.only(top: 16),
                    child: Text(
                      error ?? widget.authError!,
                      style: TextStyle(
                        color: Theme.of(context).colorScheme.error,
                      ),
                    ),
                  ),
                const SizedBox(height: 20),
                const Text(
                  '같은 계정으로 Android와 iPhone에서 기록을 이어볼 수 있어요. 차량은 로그인 후 연결합니다.',
                  style: TextStyle(fontSize: 14, height: 1.6),
                ),
                const SizedBox(height: 12),
                TextButton(
                  onPressed: () => Navigator.of(context).push(
                    MaterialPageRoute<void>(
                      builder: (_) => const PrivacyPage(),
                    ),
                  ),
                  child: const Text('기록과 개인정보 안내'),
                ),
              ],
            ),
          ),
        ),
      ),
    ),
  );
}
