package com.messenger.crisix.ui.components

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.messenger.crisix.R
import kotlinx.coroutines.delay
import kotlin.math.abs

data class AiTip(
    val emoji: String,
    val titleRes: Int,
    val bodyRes: Int,
)

private val ALL_TIPS = listOf(
    AiTip("\uD83E\uDD16", R.string.ai_tip_feature_01_title, R.string.ai_tip_feature_01_body),
    AiTip("\uD83C\uDFA4", R.string.ai_tip_feature_02_title, R.string.ai_tip_feature_02_body),
    AiTip("\uD83C\uDF10", R.string.ai_tip_feature_03_title, R.string.ai_tip_feature_03_body),
    AiTip("\uD83E\uDDE0", R.string.ai_tip_feature_04_title, R.string.ai_tip_feature_04_body),
    AiTip("\u23F0", R.string.ai_tip_feature_05_title, R.string.ai_tip_feature_05_body),
    AiTip("\uD83D\uDD12", R.string.ai_tip_privacy_01_title, R.string.ai_tip_privacy_01_body),
    AiTip("\uD83D\uDCF1", R.string.ai_tip_privacy_02_title, R.string.ai_tip_privacy_02_body),
    AiTip("\u26A1", R.string.ai_tip_perf_01_title, R.string.ai_tip_perf_01_body),
    AiTip("\uD83D\uDCE6", R.string.ai_tip_perf_02_title, R.string.ai_tip_perf_02_body),
    AiTip("\uD83C\uDF0D", R.string.ai_tip_fun_01_title, R.string.ai_tip_fun_01_body),
    AiTip("\u2728", R.string.ai_tip_fun_02_title, R.string.ai_tip_fun_02_body),
    AiTip("\uD83D\uDCDA", R.string.ai_tip_fun_03_title, R.string.ai_tip_fun_03_body),
)

@Composable
fun AiTipCarousel(
    modifier: Modifier = Modifier,
    intervalMs: Long = 8000L,
    tips: List<AiTip> = remember { ALL_TIPS.shuffled() },
) {
    val pagerState = rememberPagerState(pageCount = { tips.size })

    var entered by remember { mutableStateOf(false) }
    val entryOffset by animateDpAsState(
        targetValue = if (entered) 0.dp else 80.dp,
        animationSpec = spring(
            dampingRatio = 0.7f,
            stiffness = 100f,
        ),
        label = "entry_offset",
    )
    val entryAlpha by animateFloatAsState(
        targetValue = if (entered) 1f else 0f,
        animationSpec = spring(
            dampingRatio = 0.7f,
            stiffness = 100f,
        ),
        label = "entry_alpha",
    )

    LaunchedEffect(Unit) {
        entered = true
        while (true) {
            delay(intervalMs)
            val next = (pagerState.currentPage + 1) % tips.size
            pagerState.animateScrollToPage(
                page = next,
                animationSpec = tween(
                    durationMillis = 1800,
                    easing = androidx.compose.animation.core.FastOutSlowInEasing,
                ),
            )
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .graphicsLayer(alpha = entryAlpha)
            .offset(x = entryOffset),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxWidth(),
            pageSpacing = 16.dp,
            beyondViewportPageCount = 1,
        ) { page ->
            val pageOffset = (pagerState.currentPage - page) + pagerState.currentPageOffsetFraction
            TipPage(
                tip = tips[page],
                pageOffset = pageOffset,
            )
        }

        Spacer(modifier = Modifier.height(14.dp))

        PageIndicator(
            count = tips.size,
            current = pagerState.currentPage,
            currentPageOffset = pagerState.currentPageOffsetFraction,
        )
    }
}

@Composable
private fun TipPage(
    tip: AiTip,
    pageOffset: Float,
    modifier: Modifier = Modifier,
) {
    val absOffset = abs(pageOffset)
    val alpha by animateFloatAsState(
        targetValue = (1f - absOffset * 0.5f).coerceAtLeast(0.4f),
        animationSpec = spring(dampingRatio = 0.7f, stiffness = 200f),
        label = "page_alpha",
    )

    Column(
        modifier = modifier
            .fillMaxWidth()
            .graphicsLayer(alpha = alpha)
            .padding(horizontal = 24.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(80.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = tip.emoji,
                fontSize = 42.sp,
            )
        }

        Spacer(modifier = Modifier.height(20.dp))

        Text(
            text = stringResource(tip.titleRes),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            lineHeight = 34.sp,
        )

        Spacer(modifier = Modifier.height(10.dp))

        Text(
            text = stringResource(tip.bodyRes),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            lineHeight = 26.sp,
        )
    }
}

@Composable
private fun PageIndicator(
    count: Int,
    current: Int,
    currentPageOffset: Float,
    dotSize: Dp = 7.dp,
    activeDotSize: Dp = 28.dp,
    spacing: Dp = 7.dp,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(spacing),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Spacer(modifier = Modifier.weight(1f))
        for (i in 0 until count) {
            val isActive = i == current
            val smoothProgress = when {
                i == current -> 1f - abs(currentPageOffset)
                i == current + 1 -> abs(currentPageOffset).coerceAtMost(1f)
                i == current - 1 -> abs(currentPageOffset).coerceAtMost(1f)
                else -> 0f
            }.coerceIn(0f, 1f)

            val targetWidth = if (isActive) activeDotSize else dotSize
            val animatedWidth by animateDpAsState(
                targetValue = dotSize + (targetWidth - dotSize) * smoothProgress,
                animationSpec = spring(dampingRatio = 0.6f, stiffness = 300f),
                label = "dot_width",
            )
            val alpha = if (isActive) 1f else 0.2f + smoothProgress * 0.4f

            Box(
                modifier = Modifier
                    .size(
                        width = animatedWidth,
                        height = dotSize,
                    )
                    .clip(RoundedCornerShape(4.dp))
                    .background(
                        if (smoothProgress > 0.5f) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha.coerceIn(0f, 1f))
                    ),
            )
        }
        Spacer(modifier = Modifier.weight(1f))
    }
}
