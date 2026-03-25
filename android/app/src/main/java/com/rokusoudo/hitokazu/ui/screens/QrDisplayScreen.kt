package com.rokusoudo.hitokazu.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.zxing.BarcodeFormat
import com.journeyapps.barcodescanner.BarcodeEncoder
import com.rokusoudo.hitokazu.data.model.GamePhase
import com.rokusoudo.hitokazu.viewmodel.GameViewModel

private const val INVITE_BASE_URL = "https://hitokazu.rokusoudo.com/join/"

@Composable
fun QrDisplayScreen(
    viewModel: GameViewModel,
    onGameStarted: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val inviteUrl = remember(uiState.roomId) { "$INVITE_BASE_URL${uiState.roomId}" }

    // ゲーム開始→回答画面へ
    LaunchedEffect(uiState.phase) {
        if (uiState.phase == GamePhase.ANSWERING) {
            onGameStarted()
        }
    }

    val bitmap = remember(uiState.roomId) {
        runCatching {
            val encoder = BarcodeEncoder()
            // QRコードには招待URLをエンコード（スキャンするだけで参加できる）
            encoder.encodeBitmap(inviteUrl, BarcodeFormat.QR_CODE, 512, 512)
                .asImageBitmap()
        }.getOrNull()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
    ) {
        Text(
            text = "QRコードをスキャンして参加",
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )

        Text(
            text = "ルームID: ${uiState.roomId}",
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
        )

        bitmap?.let {
            Image(
                bitmap = it,
                contentDescription = "QRコード",
                modifier = Modifier.size(240.dp),
            )
        } ?: CircularProgressIndicator()

        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

        // 招待URL表示とシェアボタン
        Text(
            text = "URLで招待（アプリ不要）",
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
        )
        Text(
            text = inviteUrl,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.primary,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 8.dp),
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(
                onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("招待URL", inviteUrl))
                },
            ) {
                Text("URLをコピー", fontSize = 14.sp)
            }
            OutlinedButton(
                onClick = {
                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, "人数当てゲームに招待します！\nこちらのURLから参加できます👇\n$inviteUrl")
                    }
                    context.startActivity(Intent.createChooser(shareIntent, "招待URLをシェア"))
                },
            ) {
                Text("シェア", fontSize = 14.sp)
            }
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

        Text(
            text = "参加者 (${uiState.players.size}人)",
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium,
        )

        uiState.players.forEach { player ->
            Text(
                text = "・${player.nickname}",
                fontSize = 14.sp,
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        Button(
            onClick = { viewModel.startGame() },
            enabled = uiState.players.size >= 1,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
        ) {
            Text(
                text = if (uiState.players.size < 1) "あと${1 - uiState.players.size}人必要"
                       else "ゲームを開始する (${uiState.players.size}人)",
                fontSize = 16.sp,
            )
        }
    }
}
