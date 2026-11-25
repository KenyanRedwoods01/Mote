package com.mote.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * LobeHub (`@lobehub/icons`) inspired AI model & provider profile avatars rendered natively
 * in Jetpack Compose Canvas for instant, crisp, resolution-independent rendering.
 */
enum class LobeModelBrand(val label: String) {
    GEMINI("Gemini"),
    GEMMA("Gemma"),
    QWEN("Qwen"),
    LLAMA("Meta Llama"),
    DEEPSEEK("DeepSeek"),
    MISTRAL("Mistral"),
    HUGGINGFACE("Hugging Face"),
    MOTE("Mote");

    companion object {
        fun fromModelId(modelId: String): LobeModelBrand {
            val lower = modelId.lowercase()
            return when {
                lower.contains("gemma") -> GEMMA
                lower.contains("gemini") -> GEMINI
                lower.contains("qwen") -> QWEN
                lower.contains("llama") || lower.contains("meta") -> LLAMA
                lower.contains("deepseek") -> DEEPSEEK
                lower.contains("mistral") || lower.contains("ministral") -> MISTRAL
                lower.contains("smollm") || lower.contains("hugging") -> HUGGINGFACE
                else -> MOTE
            }
        }
    }
}

@Composable
fun LobeModelAvatar(
    modelId: String,
    modifier: Modifier = Modifier,
    size: Dp = 36.dp,
    circular: Boolean = false
) {
    LobeModelAvatar(
        brand = LobeModelBrand.fromModelId(modelId),
        modifier = modifier,
        size = size,
        circular = circular
    )
}

@Composable
fun LobeModelAvatar(
    brand: LobeModelBrand,
    modifier: Modifier = Modifier,
    size: Dp = 36.dp,
    circular: Boolean = false
) {
    val shape = if (circular) CircleShape else RoundedCornerShape(size * 0.28f)
    val bgBrush = when (brand) {
        LobeModelBrand.GEMINI -> Brush.linearGradient(listOf(Color(0xFF1B2B65), Color(0xFF0F172A)))
        LobeModelBrand.GEMMA -> Brush.linearGradient(listOf(Color(0xFF0F2942), Color(0xFF1E3A8A)))
        LobeModelBrand.QWEN -> Brush.linearGradient(listOf(Color(0xFF4F46E5), Color(0xFF7C3AED)))
        LobeModelBrand.LLAMA -> Brush.linearGradient(listOf(Color(0xFF0668E1), Color(0xFF0082FB)))
        LobeModelBrand.DEEPSEEK -> Brush.linearGradient(listOf(Color(0xFF1D4ED8), Color(0xFF4D6BFE)))
        LobeModelBrand.MISTRAL -> Brush.linearGradient(listOf(Color(0xFF18181B), Color(0xFF27272A)))
        LobeModelBrand.HUGGINGFACE -> Brush.linearGradient(listOf(Color(0xFFFFD21E), Color(0xFFFF9D00)))
        LobeModelBrand.MOTE -> Brush.linearGradient(listOf(MoteBrandNavy, Color(0xFF1E293B)))
    }

    Box(
        modifier = modifier
            .size(size)
            .clip(shape)
            .background(bgBrush)
            .border(0.75.dp, Color(0x22FFFFFF), shape)
            .semantics { contentDescription = "${brand.label} model icon" },
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.size(size * 0.66f)) {
            when (brand) {
                LobeModelBrand.GEMINI -> drawGeminiSpark()
                LobeModelBrand.GEMMA -> drawGemmaFacet()
                LobeModelBrand.QWEN -> drawQwenStar()
                LobeModelBrand.LLAMA -> drawMetaInfinity()
                LobeModelBrand.DEEPSEEK -> drawDeepSeekWave()
                LobeModelBrand.MISTRAL -> drawMistralBars()
                LobeModelBrand.HUGGINGFACE -> drawHuggingFaceBadge()
                LobeModelBrand.MOTE -> drawMoteCore()
            }
        }
    }
}

private fun DrawScope.drawGeminiSpark() {
    val cx = size.width / 2f
    val cy = size.height / 2f
    val r = size.minDimension * 0.48f
    val path = Path().apply {
        moveTo(cx, cy - r)
        quadraticTo(cx, cy, cx + r, cy)
        quadraticTo(cx, cy, cx, cy + r)
        quadraticTo(cx, cy, cx - r, cy)
        quadraticTo(cx, cy, cx, cy - r)
        close()
    }
    drawPath(
        path = path,
        brush = Brush.linearGradient(
            colors = listOf(Color(0xFF4285F4), Color(0xFF9B72CB), Color(0xFFD96570)),
            start = Offset(0f, 0f),
            end = Offset(size.width, size.height)
        )
    )
}

private fun DrawScope.drawGemmaFacet() {
    val cx = size.width / 2f
    val cy = size.height / 2f
    val r = size.minDimension * 0.45f
    val outer = Path().apply {
        moveTo(cx, cy - r)
        lineTo(cx + r * 0.82f, cy)
        lineTo(cx, cy + r)
        lineTo(cx - r * 0.82f, cy)
        close()
    }
    drawPath(
        path = outer,
        brush = Brush.linearGradient(
            colors = listOf(Color(0xFF60A5FA), Color(0xFF38BDF8), Color(0xFF34D399))
        )
    )
    val inner = Path().apply {
        moveTo(cx, cy - r * 0.48f)
        lineTo(cx + r * 0.40f, cy)
        lineTo(cx, cy + r * 0.48f)
        lineTo(cx - r * 0.40f, cy)
        close()
    }
    drawPath(path = inner, color = Color(0xCCFFFFFF))
}

private fun DrawScope.drawQwenStar() {
    val cx = size.width / 2f
    val cy = size.height / 2f
    val outerR = size.minDimension * 0.46f
    val innerR = size.minDimension * 0.22f
    val path = Path()
    for (i in 0 until 12) {
        val angle = (i * 30.0 - 90.0) * PI / 180.0
        val rad = if (i % 2 == 0) outerR else innerR
        val x = cx + (rad * cos(angle)).toFloat()
        val y = cy + (rad * sin(angle)).toFloat()
        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    path.close()
    drawPath(path = path, color = Color.White)
    drawCircle(color = Color(0xFF6366F1), radius = size.minDimension * 0.13f, center = Offset(cx, cy))
}

private fun DrawScope.drawMetaInfinity() {
    val w = size.width
    val h = size.height
    val stroke = Stroke(width = w * 0.14f, cap = StrokeCap.Round, join = StrokeJoin.Round)
    val path = Path().apply {
        moveTo(w * 0.25f, h * 0.5f)
        cubicTo(w * 0.25f, h * 0.18f, w * 0.46f, h * 0.24f, w * 0.50f, h * 0.50f)
        cubicTo(w * 0.54f, h * 0.76f, w * 0.75f, h * 0.82f, w * 0.75f, h * 0.50f)
        cubicTo(w * 0.75f, h * 0.18f, w * 0.54f, h * 0.24f, w * 0.50f, h * 0.50f)
        cubicTo(w * 0.46f, h * 0.76f, w * 0.25f, h * 0.82f, w * 0.25f, h * 0.50f)
    }
    drawPath(path = path, color = Color.White, style = stroke)
}

private fun DrawScope.drawDeepSeekWave() {
    val w = size.width
    val h = size.height
    val body = Path().apply {
        moveTo(w * 0.14f, h * 0.60f)
        cubicTo(w * 0.18f, h * 0.22f, w * 0.62f, h * 0.18f, w * 0.84f, h * 0.46f)
        cubicTo(w * 0.90f, h * 0.55f, w * 0.86f, h * 0.78f, w * 0.58f, h * 0.80f)
        cubicTo(w * 0.34f, h * 0.82f, w * 0.12f, h * 0.72f, w * 0.14f, h * 0.60f)
        close()
    }
    drawPath(path = body, color = Color.White)
    drawCircle(color = Color(0xFF1D4ED8), radius = w * 0.065f, center = Offset(w * 0.66f, h * 0.48f))
}

private fun DrawScope.drawMistralBars() {
    val w = size.width
    val h = size.height
    val colors = listOf(
        Color(0xFFFFD800),
        Color(0xFFFFAF00),
        Color(0xFFFF8205),
        Color(0xFFFA500F),
        Color(0xFFE10500)
    )
    val rowH = h * 0.15f
    val gap = h * 0.04f
    val startY = h * 0.08f
    // Stylized 5-band Mistral "M" silhouette
    val spans = listOf(
        listOf(0.08f to 0.30f, 0.70f to 0.92f),
        listOf(0.08f to 0.42f, 0.58f to 0.92f),
        listOf(0.08f to 0.92f),
        listOf(0.08f to 0.30f, 0.42f to 0.58f, 0.70f to 0.92f),
        listOf(0.08f to 0.30f, 0.70f to 0.92f)
    )
    spans.forEachIndexed { rowIdx, segs ->
        val top = startY + rowIdx * (rowH + gap)
        segs.forEach { (x0, x1) ->
            drawRoundRect(
                color = colors[rowIdx],
                topLeft = Offset(w * x0, top),
                size = Size(w * (x1 - x0), rowH),
                cornerRadius = CornerRadius(2f, 2f)
            )
        }
    }
}

private fun DrawScope.drawHuggingFaceBadge() {
    val cx = size.width / 2f
    val cy = size.height / 2f
    val r = size.minDimension * 0.42f
    drawCircle(color = Color(0xFF1F2937), radius = r * 0.13f, center = Offset(cx - r * 0.38f, cy - r * 0.18f))
    drawCircle(color = Color(0xFF1F2937), radius = r * 0.13f, center = Offset(cx + r * 0.38f, cy - r * 0.18f))
    val smile = Path().apply {
        moveTo(cx - r * 0.42f, cy + r * 0.18f)
        quadraticTo(cx, cy + r * 0.62f, cx + r * 0.42f, cy + r * 0.18f)
    }
    drawPath(
        path = smile,
        color = Color(0xFF1F2937),
        style = Stroke(width = size.minDimension * 0.11f, cap = StrokeCap.Round)
    )
}

private fun DrawScope.drawMoteCore() {
    val cx = size.width / 2f
    val cy = size.height / 2f
    drawCircle(
        brush = Brush.radialGradient(listOf(LocalNanoEmerald, CloudFlashCobalt)),
        radius = size.minDimension * 0.36f,
        center = Offset(cx, cy)
    )
    drawCircle(
        color = Color.White,
        radius = size.minDimension * 0.13f,
        center = Offset(cx, cy)
    )
}
