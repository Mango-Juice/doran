import 'package:material_ui/material_ui.dart';
import '../data/repository.dart';

abstract final class DoranColors {
  static const fog = Color(0xfff5f7f8);
  static const paper = Color(0xffffffff);
  static const ink = Color(0xff20292f);
  static const secondary = Color(0xff65737b);
  static const accent = Color(0xff315c78);
  static const sky = Color(0xff9cc7de);
  static const line = Color(0xffdce3e7);
}

ThemeData doranTheme() {
  final colors =
      ColorScheme.fromSeed(
        seedColor: DoranColors.accent,
        brightness: Brightness.light,
      ).copyWith(
        primary: DoranColors.accent,
        onPrimary: Colors.white,
        primaryContainer: const Color(0xffe4eff5),
        onPrimaryContainer: DoranColors.accent,
        surface: DoranColors.paper,
        onSurface: DoranColors.ink,
        onSurfaceVariant: DoranColors.secondary,
        surfaceContainerHighest: const Color(0xffeaf0f3),
        outline: const Color(0xff83939d),
        outlineVariant: DoranColors.line,
      );
  return ThemeData(
    useMaterial3: true,
    fontFamily: 'SUIT',
    colorScheme: colors,
    scaffoldBackgroundColor: DoranColors.fog,
    appBarTheme: const AppBarTheme(
      backgroundColor: DoranColors.fog,
      foregroundColor: DoranColors.ink,
      centerTitle: false,
      scrolledUnderElevation: 0,
      titleTextStyle: TextStyle(
        fontFamily: 'SUIT',
        fontSize: 18,
        fontWeight: FontWeight.w600,
        color: DoranColors.ink,
      ),
    ),
    textTheme: const TextTheme(
      displaySmall: TextStyle(
        fontSize: 32,
        height: 1.25,
        fontWeight: FontWeight.w500,
      ),
      headlineSmall: TextStyle(
        fontSize: 24,
        height: 1.35,
        fontWeight: FontWeight.w500,
      ),
      titleLarge: TextStyle(
        fontSize: 20,
        height: 1.4,
        fontWeight: FontWeight.w600,
      ),
      titleMedium: TextStyle(
        fontSize: 16,
        height: 1.5,
        fontWeight: FontWeight.w600,
      ),
      bodyLarge: TextStyle(fontSize: 17, height: 1.75),
      bodyMedium: TextStyle(fontSize: 15, height: 1.6),
      bodySmall: TextStyle(fontSize: 13, height: 1.5),
      labelLarge: TextStyle(fontSize: 15, fontWeight: FontWeight.w600),
    ),
    filledButtonTheme: FilledButtonThemeData(
      style: FilledButton.styleFrom(
        minimumSize: const Size(48, 52),
        padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 12),
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(12)),
      ),
    ),
    outlinedButtonTheme: OutlinedButtonThemeData(
      style: OutlinedButton.styleFrom(
        minimumSize: const Size(48, 52),
        padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 12),
        side: const BorderSide(color: DoranColors.line),
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(12)),
      ),
    ),
    inputDecorationTheme: InputDecorationTheme(
      filled: true,
      fillColor: Colors.white,
      border: OutlineInputBorder(
        borderRadius: BorderRadius.circular(12),
        borderSide: const BorderSide(color: DoranColors.line),
      ),
      enabledBorder: OutlineInputBorder(
        borderRadius: BorderRadius.circular(12),
        borderSide: const BorderSide(color: DoranColors.line),
      ),
      focusedBorder: OutlineInputBorder(
        borderRadius: BorderRadius.circular(12),
        borderSide: const BorderSide(color: DoranColors.accent, width: 2),
      ),
      contentPadding: const EdgeInsets.all(20),
    ),
    navigationBarTheme: NavigationBarThemeData(
      height: 72,
      backgroundColor: DoranColors.paper,
      indicatorColor: const Color(0xffe4eff5),
      labelTextStyle: WidgetStateProperty.all(
        const TextStyle(
          fontFamily: 'SUIT',
          fontSize: 12,
          fontWeight: FontWeight.w600,
        ),
      ),
    ),
    dividerTheme: const DividerThemeData(color: DoranColors.line, space: 32),
  );
}

/// Two spoken phrases becoming the three lines of a written record.
class DoranMark extends StatelessWidget {
  const DoranMark({super.key, this.size = 32});
  final double size;
  @override
  Widget build(BuildContext context) => ExcludeSemantics(
    child: SizedBox(
      width: size,
      height: size,
      child: CustomPaint(painter: _DoranMarkPainter()),
    ),
  );
}

class _DoranMarkPainter extends CustomPainter {
  @override
  void paint(Canvas canvas, Size size) {
    final ink = Paint()
      ..color = DoranColors.accent
      ..style = PaintingStyle.stroke
      ..strokeWidth = size.width / 16
      ..strokeCap = StrokeCap.round;
    final w = size.width;
    canvas.drawRRect(
      RRect.fromRectAndRadius(
        Rect.fromLTWH(w * .08, w * .08, w * .5, w * .35),
        Radius.circular(w * .1),
      ),
      ink,
    );
    canvas.drawLine(Offset(w * .2, w * .43), Offset(w * .16, w * .55), ink);
    canvas.drawLine(Offset(w * .43, w * .6), Offset(w * .92, w * .6), ink);
    canvas.drawLine(Offset(w * .43, w * .76), Offset(w * .92, w * .76), ink);
    canvas.drawLine(Offset(w * .43, w * .92), Offset(w * .76, w * .92), ink);
  }

  @override
  bool shouldRepaint(covariant CustomPainter oldDelegate) => false;
}

class Notice extends StatelessWidget {
  final String text;
  final IconData icon;
  const Notice(this.text, {super.key, this.icon = Icons.info_outline});
  @override
  Widget build(BuildContext context) => Semantics(
    liveRegion: true,
    child: Container(
      margin: const EdgeInsets.only(bottom: 16),
      padding: const EdgeInsets.all(16),
      decoration: BoxDecoration(
        color: const Color(0xffeaf0f3),
        borderRadius: BorderRadius.circular(12),
      ),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Icon(icon, size: 20, color: DoranColors.accent),
          const SizedBox(width: 12),
          Expanded(
            child: Text(
              text,
              style: const TextStyle(fontSize: 14, height: 1.6),
            ),
          ),
        ],
      ),
    ),
  );
}

class EditorActionBar extends StatelessWidget {
  const EditorActionBar({
    super.key,
    required this.label,
    required this.onPressed,
  });
  final String label;
  final VoidCallback? onPressed;
  @override
  Widget build(BuildContext context) => DecoratedBox(
    decoration: const BoxDecoration(
      color: DoranColors.paper,
      border: Border(top: BorderSide(color: DoranColors.line)),
    ),
    child: SafeArea(
      top: false,
      child: Padding(
        padding: const EdgeInsets.fromLTRB(24, 12, 24, 12),
        child: SizedBox(
          width: double.infinity,
          child: FilledButton(onPressed: onPressed, child: Text(label)),
        ),
      ),
    ),
  );
}

String friendlyError(Object error) {
  if (error is ApiException) return error.message;
  return '연결을 확인한 뒤 다시 시도해 주세요. 작성 중인 글은 이 기기에 보관됩니다.';
}
