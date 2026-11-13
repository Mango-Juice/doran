package app.doran.car.ui

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val Ink = Color(0xFF181B1A)
val Raised = Color(0xFF222725)
val Paper = Color(0xFFF2F0EA)
val Quiet = Color(0xFFAAAFA9)
val Warm = Color(0xFFD9A87E)

@Composable
fun DoranTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Warm, onPrimary = Ink, background = Ink, onBackground = Paper,
            surface = Raised, onSurface = Paper, onSurfaceVariant = Quiet, outline = Color(0xFF414741), error = Color(0xFFF1AAA1)
        ),
        typography = Typography(
            displaySmall = TextStyle(
                fontFamily = FontFamily.SansSerif,
                fontWeight = FontWeight.Medium,
                fontSize = 42.sp,
                lineHeight = 56.sp
            ),
            headlineMedium = TextStyle(
                fontFamily = FontFamily.SansSerif,
                fontWeight = FontWeight.Medium,
                fontSize = 32.sp,
                lineHeight = 44.sp
            ),
            titleLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 26.sp, lineHeight = 36.sp),
            bodyLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 22.sp, lineHeight = 34.sp),
            bodyMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 18.sp, lineHeight = 28.sp),
            labelLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 22.sp, lineHeight = 30.sp)
        ),
        content = content
    )
}
