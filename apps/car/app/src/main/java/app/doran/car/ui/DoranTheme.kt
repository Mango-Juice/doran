package app.doran.car.ui

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import app.doran.car.R

val Ink = Color(0xFF20292F)
val Raised = Color(0xFF2B373F)
val Paper = Color(0xFFF5F7F8)
val Quiet = Color(0xFFB7C3CA)
val Accent = Color(0xFF9CC7DE)

@OptIn(ExperimentalTextApi::class)
private val DoranTypeface = FontFamily(
    Font(R.font.suit_variable, FontWeight.Normal, variationSettings = FontVariation.Settings(FontVariation.weight(400))),
    Font(R.font.suit_variable, FontWeight.Medium, variationSettings = FontVariation.Settings(FontVariation.weight(500))),
    Font(R.font.suit_variable, FontWeight.SemiBold, variationSettings = FontVariation.Settings(FontVariation.weight(600)))
)

@Composable
fun DoranTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Accent, onPrimary = Ink, background = Ink, onBackground = Paper,
            surface = Raised, onSurface = Paper, onSurfaceVariant = Quiet, outline = Color(0xFF62717B), error = Color(0xFFF1AAA1)
        ),
        typography = Typography(
            displaySmall = TextStyle(
                fontFamily = DoranTypeface,
                fontWeight = FontWeight.Medium,
                fontSize = 40.sp,
                lineHeight = 48.sp
            ),
            headlineMedium = TextStyle(
                fontFamily = DoranTypeface,
                fontWeight = FontWeight.Medium,
                fontSize = 32.sp,
                lineHeight = 44.sp
            ),
            titleLarge = TextStyle(fontFamily = DoranTypeface, fontWeight = FontWeight.SemiBold, fontSize = 24.sp, lineHeight = 32.sp),
            bodyLarge = TextStyle(fontFamily = DoranTypeface, fontSize = 22.sp, lineHeight = 32.sp),
            bodyMedium = TextStyle(fontFamily = DoranTypeface, fontSize = 18.sp, lineHeight = 28.sp),
            labelLarge = TextStyle(fontFamily = DoranTypeface, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 30.sp)
        ),
        content = content
    )
}
