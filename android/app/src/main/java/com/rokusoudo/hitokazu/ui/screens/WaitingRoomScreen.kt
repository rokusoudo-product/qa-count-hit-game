package com.rokusoudo.hitokazu.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rokusoudo.hitokazu.data.model.GamePhase
import com.rokusoudo.hitokazu.viewmodel.GameViewModel

@Composable
fun WaitingRoomScreen(
    viewModel: GameViewModel,
    onGameStarted: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsState()

    // ゲーム開始通知→回答画面へ
    LaunchedEffect(uiState.phase) {
        if (uiState.phase == GamePhase.ANSWERING) {
            onGameStarted()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(modifier = Modifier.height(24.dp))

        Text(text = "待合室", fontSize = 28.sp, fontWeight = FontWeight.Bold)

        Text(
            text = "ルームID: ${uiState.roomId}",
            fontSize = 20.sp,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = 8.dp),
        )

        // 接続状態（Firebase自動管理）
        Text(
            text = "Firebase接続中",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = 4.dp, bottom = 24.dp),
        )

        Text(
            text = "参加者 (${uiState.players.size}人)",
            fontSize = 18.sp,
            fontWeight = FontWeight.Medium,
        )

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(top = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(uiState.players) { player ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = player.nickname,
                            fontSize = 16.sp,
                            modifier = Modifier.weight(1f),
                        )
                        if (player.isHost) {
                            Surface(
                                color = MaterialTheme.colorScheme.primaryContainer,
                                shape = MaterialTheme.shapes.small,
                            ) {
                                Text(
                                    text = "ホスト",
                                    fontSize = 12.sp,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                )
                            }
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        if (!uiState.isHost) {
            Text(
                text = "ホストがゲームを開始するまでお待ちください",
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                modifier = Modifier.padding(bottom = 24.dp),
            )
        }
    }
}
