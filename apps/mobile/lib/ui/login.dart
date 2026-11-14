import 'package:material_ui/material_ui.dart';

import 'privacy.dart';
import 'theme.dart';

class LoginPage extends StatefulWidget {
  /// Reports whether the system browser opened, not whether sign-in completed.
  final Future<bool> Function() onLogin;
  final String? authError;
  const LoginPage({super.key, required this.onLogin, this.authError});
  @override
  State<LoginPage> createState() => _LoginPageState();
}

class _LoginPageState extends State<LoginPage> {
  bool busy = false;
  String? error;

  Future<void> _login() async {
    if (busy) return;
    setState(() {
      busy = true;
      error = null;
    });
    bool launched;
    try {
      launched = await widget.onLogin();
    } catch (_) {
      launched = false;
    }
    if (!mounted) return;
    setState(() {
      busy = false;
      error = launched ? null : '로그인을 시작하지 못했어요. 연결을 확인하고 다시 시도해 주세요.';
    });
  }

  @override
  Widget build(BuildContext context) => Scaffold(
    body: SafeArea(
      child: Center(
        child: SingleChildScrollView(
          padding: const EdgeInsets.symmetric(horizontal: 24, vertical: 32),
          child: ConstrainedBox(
            constraints: const BoxConstraints(maxWidth: 520),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Row(
                  children: [
                    const DoranMark(size: 44),
                    const SizedBox(width: 12),
                    Text('도란', style: Theme.of(context).textTheme.titleLarge),
                  ],
                ),
                const SizedBox(height: 48),
                Text(
                  '말로 남긴 하루,\n읽으며 이어가기',
                  style: Theme.of(context).textTheme.displaySmall?.copyWith(
                    fontSize: 36,
                    height: 1.3,
                    fontWeight: FontWeight.w500,
                  ),
                ),
                const SizedBox(height: 24),
                Text(
                  '차에서는 이야기하고\n휴대폰에서는 읽고 다듬어요',
                  style: Theme.of(context).textTheme.bodyLarge?.copyWith(
                    color: Theme.of(context).colorScheme.onSurfaceVariant,
                  ),
                ),
                const SizedBox(height: 32),
                Container(
                  padding: const EdgeInsets.symmetric(vertical: 24),
                  decoration: BoxDecoration(
                    border: Border(
                      top: BorderSide(
                        color: Theme.of(context).colorScheme.outlineVariant,
                      ),
                      bottom: BorderSide(
                        color: Theme.of(context).colorScheme.outlineVariant,
                      ),
                    ),
                  ),
                  child: Row(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Icon(
                        Icons.mic_none_outlined,
                        color: Theme.of(context).colorScheme.primary,
                      ),
                      const SizedBox(width: 16),
                      Expanded(
                        child: Text(
                          '나눈 대화는 하나의 기록으로,\n모인 기록은 하루 일기로 남아요',
                          style: Theme.of(context).textTheme.bodyMedium,
                        ),
                      ),
                    ],
                  ),
                ),
                const SizedBox(height: 32),
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
                        fontFamilyFallback: ['SUIT'],
                        fontSize: 14,
                        height: 20 / 14,
                        fontWeight: FontWeight.w500,
                      ),
                    ),
                    onPressed: busy ? null : _login,
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
                  '로그인 후 차량을 연결할 수 있어요. Android와 iPhone에서 같은 기록을 이어봅니다.',
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
