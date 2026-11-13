import 'package:material_ui/material_ui.dart';
import '../data/repository.dart';

ThemeData doranTheme() {
  final colors =
      ColorScheme.fromSeed(
        seedColor: const Color(0xff945838),
        brightness: Brightness.light,
      ).copyWith(
        primary: const Color(0xff945838),
        onPrimary: Colors.white,
        surface: const Color(0xfff8f7f3),
        onSurface: const Color(0xff252b28),
        outlineVariant: const Color(0xffdeded5),
      );
  return ThemeData(
    useMaterial3: true,
    colorScheme: colors,
    scaffoldBackgroundColor: colors.surface,
    appBarTheme: AppBarTheme(
      backgroundColor: colors.surface,
      foregroundColor: colors.onSurface,
      centerTitle: false,
      scrolledUnderElevation: 0,
    ),
    textTheme: const TextTheme(
      displaySmall: TextStyle(
        fontSize: 32,
        height: 1.25,
        fontWeight: FontWeight.w600,
      ),
      headlineSmall: TextStyle(
        fontSize: 24,
        height: 1.4,
        fontWeight: FontWeight.w600,
      ),
      titleLarge: TextStyle(
        fontSize: 20,
        height: 1.4,
        fontWeight: FontWeight.w600,
      ),
      bodyLarge: TextStyle(fontSize: 17, height: 1.75),
      bodyMedium: TextStyle(fontSize: 15, height: 1.6),
      labelLarge: TextStyle(fontSize: 15, fontWeight: FontWeight.w600),
    ),
    filledButtonTheme: FilledButtonThemeData(
      style: FilledButton.styleFrom(
        minimumSize: const Size(48, 52),
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(12)),
      ),
    ),
    outlinedButtonTheme: OutlinedButtonThemeData(
      style: OutlinedButton.styleFrom(
        minimumSize: const Size(48, 52),
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(12)),
      ),
    ),
    inputDecorationTheme: InputDecorationTheme(
      filled: true,
      fillColor: Colors.white,
      border: OutlineInputBorder(borderRadius: BorderRadius.circular(12)),
      contentPadding: const EdgeInsets.all(16),
    ),
    navigationBarTheme: NavigationBarThemeData(
      backgroundColor: colors.surface,
      indicatorColor: const Color(0xffeaded4),
    ),
    dividerTheme: DividerThemeData(color: colors.outlineVariant, space: 32),
  );
}

class Notice extends StatelessWidget {
  final String text;
  final IconData icon;
  const Notice(this.text, {super.key, this.icon = Icons.info_outline});
  @override
  Widget build(BuildContext context) => Container(
    margin: const EdgeInsets.only(bottom: 16),
    padding: const EdgeInsets.all(16),
    decoration: BoxDecoration(
      color: Theme.of(context).colorScheme.surfaceContainerHighest,
      borderRadius: BorderRadius.circular(12),
    ),
    child: Row(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Icon(icon, size: 20),
        const SizedBox(width: 12),
        Expanded(child: Text(text)),
      ],
    ),
  );
}

String friendlyError(Object error) {
  if (error is ApiException) return error.message;
  return '연결을 확인한 뒤 다시 시도해 주세요. 작성 중인 글은 이 기기에 보관됩니다.';
}
