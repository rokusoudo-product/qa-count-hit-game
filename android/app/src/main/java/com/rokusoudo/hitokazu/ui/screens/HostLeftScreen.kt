package com.rokusoudo.hitokazu.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ホストが離脱（アプリ強制終了・通信断・バックグラウンド遷移など）したことを検知し、
// ルームが HOST_LEFT へ遷移した際に残った参加者に表示する画面（Issue #15）。
@Composable
fun HostLeftScreen(
    onLeaveRoom: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(text = "⚠️", fontSize = 48.sp)

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "ルームを終了しました",
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
        )

        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text = "ホストが離脱したためルームを終了しました",
            fontSize = 16.sp,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
        )

        Spacer(modifier = Modifier.height(32.dp))

        Button(
            onClick = onLeaveRoom,
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) {
            Text("トップに戻る", fontSize = 16.sp)
        }
    }
}
