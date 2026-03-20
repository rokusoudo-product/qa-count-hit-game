package com.rokusoudo.hitokazu.ui.components

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rokusoudo.hitokazu.data.websocket.WsState

@Composable
fun ConnectionBanner(
    wsState: WsState,
    onReconnect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isError = wsState is WsState.Error || wsState is WsState.Disconnected

    AnimatedVisibility(
        visible = isError,
        enter = slideInVertically() + fadeIn(),
        exit = slideOutVertically() + fadeOut(),
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.errorContainer)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "接続が切れました",
                color = MaterialTheme.colorScheme.onErrorContainer,
                fontSize = 14.sp,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onReconnect) {
                Text(
                    text = "再接続",
                    color = MaterialTheme.colorScheme.error,
                    fontSize = 14.sp,
                )
            }
        }
    }
}
