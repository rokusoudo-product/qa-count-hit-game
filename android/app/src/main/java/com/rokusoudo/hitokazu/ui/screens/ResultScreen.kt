package com.rokusoudo.hitokazu.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rokusoudo.hitokazu.data.model.GamePhase
import com.rokusoudo.hitokazu.data.model.PlayerScore
import com.rokusoudo.hitokazu.viewmodel.GameViewModel

@Composable
fun ResultScreen(
    viewModel: GameViewModel,
    onNextRound: () -> Unit,
    onFinished: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsState()

    LaunchedEffect(uiState.phase) {
        when (uiState.phase) {
            GamePhase.ANSWERING -> onNextRound()
            GamePhase.FINISHED -> onFinished()
            else -> {}
        }
    }

    val question = uiState.currentQuestion
    val counts = uiState.answerCounts
    val myScore = uiState.scores.find { it.playerId == uiState.playerId }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "第${uiState.currentRound}問 結果",
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
        )

        Spacer(modifier = Modifier.height(16.dp))

        // 実際の回答集計
        question?.let { q ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                ),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = q.text,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    q.options.forEach { option ->
                        val count = counts[option] ?: 0
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(text = option, fontSize = 16.sp)
                            Text(
                                text = "${count}人",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // 自分のスコア
        myScore?.let { score ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = if (score.roundScore == 100)
                        MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surfaceVariant,
                ),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    if (score.roundScore == 100) Text("🎯 パーフェクト！", fontSize = 18.sp)
                    Text(
                        text = "あなたの予測: ${score.targetOption} が ${score.predictedCount}人",
                        fontSize = 14.sp,
                    )
                    Text(
                        text = "実際: ${score.actualCount}人",
                        fontSize = 14.sp,
                    )
                    Text(
                        text = "+${score.roundScore}点",
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "ランキング",
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
        )

        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            itemsIndexed(uiState.scores) { index, score ->
                RankingRow(rank = index + 1, score = score, myPlayerId = uiState.playerId)
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "次のラウンドに自動で進みます...",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
        )
        Spacer(modifier = Modifier.height(8.dp))
    }
}

@Composable
private fun RankingRow(rank: Int, score: PlayerScore, myPlayerId: String) {
    val isMe = score.playerId == myPlayerId
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isMe)
                MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surface,
        ),
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
                text = "${score.roundScore}点",
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}
