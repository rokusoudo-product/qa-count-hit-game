package com.rokusoudo.hitokazu.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rokusoudo.hitokazu.data.model.PlayerScore
import com.rokusoudo.hitokazu.viewmodel.GameViewModel

@Composable
fun FinishedScreen(
    viewModel: GameViewModel,
    onRestart: () -> Unit,
    onHome: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsState()
    val winner = uiState.scores.firstOrNull()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "ゲーム終了！",
            fontSize = 32.sp,
            fontWeight = FontWeight.Bold,
        )

        Text(
            text = "お疲れさまでした",
            fontSize = 16.sp,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
            modifier = Modifier.padding(top = 4.dp, bottom = 24.dp),
        )

        // 優勝者
        winner?.let {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                ),
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(text = "🏆 優勝", fontSize = 18.sp)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = it.nickname.ifEmpty { it.playerId } + if (it.playerId == uiState.playerId) "（あなた！）" else "",
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                    )
                    Text(
                        text = "${it.totalScore}点",
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = "最終ランキング",
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
        )

        Spacer(modifier = Modifier.height(8.dp))

        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            itemsIndexed(uiState.scores) { index, score ->
                FinalRankingRow(rank = index + 1, score = score, myPlayerId = uiState.playerId)
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        if (uiState.isHost) {
            Button(
                onClick = onRestart,
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) {
                Text("もう一度遊ぶ", fontSize = 16.sp)
            }
            Spacer(modifier = Modifier.height(8.dp))
        }

        OutlinedButton(
            onClick = onHome,
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) {
            Text("トップに戻る", fontSize = 16.sp)
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}

@Composable
private fun FinalRankingRow(rank: Int, score: PlayerScore, myPlayerId: String) {
    val isMe = score.playerId == myPlayerId
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (isMe)
                MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surface,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = when (rank) {
                    1 -> "🥇"
                    2 -> "🥈"
                    3 -> "🥉"
                    else -> "${rank}位"
                },
                fontSize = 20.sp,
                modifier = Modifier.width(40.dp),
            )
            Text(
                text = score.nickname.ifEmpty { score.playerId } + if (isMe) "（あなた）" else "",
                fontSize = 15.sp,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "${score.totalScore}点",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}
