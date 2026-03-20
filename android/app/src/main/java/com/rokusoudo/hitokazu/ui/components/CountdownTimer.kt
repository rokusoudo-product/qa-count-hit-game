package com.rokusoudo.hitokazu.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

@Composable
fun CountdownTimer(
    totalSeconds: Int,
    onTimeout: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var remaining by remember(totalSeconds) { mutableIntStateOf(totalSeconds) }

    LaunchedEffect(totalSeconds) {
        while (remaining > 0) {
            delay(1000L)
            remaining--
        }
        onTimeout()
    }

    val progress by animateFloatAsState(
        targetValue = remaining.toFloat() / totalSeconds.toFloat(),
        label = "timer_progress",
    )

    val color = when {
        remaining > totalSeconds * 0.5f -> MaterialTheme.colorScheme.primary
        remaining > totalSeconds * 0.25f -> Color(0xFFFFA000)
        else -> MaterialTheme.colorScheme.error
    }

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp),
            color = color,
        )
        Text(
            text = "${remaining}秒",
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            color = color,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}
