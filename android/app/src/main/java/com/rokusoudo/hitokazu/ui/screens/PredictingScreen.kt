package com.rokusoudo.hitokazu.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rokusoudo.hitokazu.data.model.GamePhase
import com.rokusoudo.hitokazu.ui.components.ConnectionBanner
import com.rokusoudo.hitokazu.ui.components.CountdownTimer
import com.rokusoudo.hitokazu.viewmodel.GameViewModel

@Composable
fun PredictingScreen(
    viewModel: GameViewModel,
    onResult: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsState()
    val question = uiState.currentQuestion
    val totalPlayers = uiState.players.size

    var selectedOption by remember { mutableStateOf(question?.options?.firstOrNull() ?: "") }
    var predictedCountStr by remember { mutableStateOf("") }
    var submitted by remember { mutableStateOf(false) }

    LaunchedEffect(uiState.phase) {
        if (uiState.phase == GamePhase.RESULT || uiState.phase == GamePhase.FINISHED) onResult()
    }

    Column(modifier = Modifier.fillMaxSize()) {
        ConnectionBanner(
            wsState = uiState.wsState,
            onReconnect = { viewModel.reconnect() },
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "第${uiState.currentRound}問 / 全${uiState.totalRounds}問",
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
            )

            Spacer(modifier = Modifier.height(8.dp))

            question?.let {
                CountdownTimer(
                    totalSeconds = it.predictSeconds,
                    onTimeout = { },
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = "何人が選んだ？",
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
            )

            Spacer(modifier = Modifier.height(16.dp))

            // 選択肢タブ
            question?.options?.let { options ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    options.forEach { option ->
                        FilterChip(
                            selected = selectedOption == option,
                            onClick = { if (!submitted) selectedOption = option },
                            label = { Text(option) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = "「$selectedOption」を選んだ人数は？",
                fontSize = 18.sp,
                textAlign = TextAlign.Center,
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "（全${totalPlayers}人参加中）",
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
            )

            Spacer(modifier = Modifier.height(16.dp))

            // 人数入力
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                IconButton(
                    onClick = {
                        val v = predictedCountStr.toIntOrNull() ?: 0
                        if (v > 0) predictedCountStr = (v - 1).toString()
                    },
                    enabled = !submitted,
                ) {
                    Text("－", fontSize = 24.sp)
                }

                OutlinedTextField(
                    value = predictedCountStr,
                    onValueChange = { input ->
                        val v = input.filter { it.isDigit() }
                        val n = v.toIntOrNull()
                        if (n == null || n <= totalPlayers) predictedCountStr = v
                    },
                    label = { Text("人数") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    enabled = !submitted,
                    modifier = Modifier.width(120.dp),
                )

                IconButton(
                    onClick = {
                        val v = predictedCountStr.toIntOrNull() ?: 0
                        if (v < totalPlayers) predictedCountStr = (v + 1).toString()
                    },
                    enabled = !submitted,
                ) {
                    Text("＋", fontSize = 24.sp)
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            if (!submitted) {
                Button(
                    onClick = {
                        val count = predictedCountStr.toIntOrNull()
                        if (count != null && selectedOption.isNotEmpty()) {
                            viewModel.submitPrediction(selectedOption, count)
                            submitted = true
                        }
                    },
                    enabled = predictedCountStr.isNotBlank() && selectedOption.isNotEmpty(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                ) {
                    Text("予測を確定する", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                }
            } else {
                Text(
                    text = "「$selectedOption が ${predictedCountStr}人」で予測済み\n結果を待っています...",
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                )
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}
