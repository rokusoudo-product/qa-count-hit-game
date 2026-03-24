package com.rokusoudo.hitokazu.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rokusoudo.hitokazu.data.model.GamePhase
import com.rokusoudo.hitokazu.ui.components.ConnectionBanner
import com.rokusoudo.hitokazu.ui.components.CountdownTimer
import com.rokusoudo.hitokazu.viewmodel.GameViewModel

@Composable
fun AnsweringScreen(
    viewModel: GameViewModel,
    onPredicting: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsState()
    val question = uiState.currentQuestion

    // 全員回答完了→予測フェーズへ
    LaunchedEffect(uiState.phase) {
        if (uiState.phase == GamePhase.PREDICTING) onPredicting()
    }

    Column(modifier = Modifier.fillMaxSize()) {
        ConnectionBanner(
            isDisconnected = false,
            onReconnect = { viewModel.reconnect() },
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // ラウンド表示
            Text(
                text = "第${uiState.currentRound}問 / 全${uiState.totalRounds}問",
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
            )

            Spacer(modifier = Modifier.height(8.dp))

            question?.let {
                CountdownTimer(
                    totalSeconds = it.answerSeconds,
                    onTimeout = { /* タイムアウトはサーバー側で処理 */ },
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Spacer(modifier = Modifier.height(32.dp))

            // 質問テキスト
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                ),
            ) {
                Text(
                    text = question?.text ?: "読み込み中...",
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                )
            }

            Spacer(modifier = Modifier.weight(1f))

            // 回答ボタン
            val answered = uiState.selectedAnswer.isNotEmpty()
            question?.options?.forEach { option ->
                val isSelected = uiState.selectedAnswer == option
                Button(
                    onClick = { viewModel.submitAnswer(option) },
                    enabled = !answered,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isSelected)
                            MaterialTheme.colorScheme.secondary
                        else
                            MaterialTheme.colorScheme.primary,
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(64.dp)
                        .padding(vertical = 4.dp),
                ) {
                    Text(
                        text = option,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }

            if (answered) {
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "「${uiState.selectedAnswer}」で回答済み\n全員の回答を待っています...",
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                )
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}
