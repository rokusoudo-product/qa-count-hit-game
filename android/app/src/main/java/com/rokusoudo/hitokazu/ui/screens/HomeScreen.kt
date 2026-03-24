package com.rokusoudo.hitokazu.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rokusoudo.hitokazu.viewmodel.GameViewModel

@Composable
fun HomeScreen(
    viewModel: GameViewModel,
    onNavigateToQr: () -> Unit,
    onNavigateToWaiting: () -> Unit,
    onNavigateToScanner: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val versionName = remember {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    }
    var hostName by remember { mutableStateOf("") }
    var showCreateDialog by remember { mutableStateOf(false) }
    var showJoinDialog by remember { mutableStateOf(false) }
    var joinRoomId by remember { mutableStateOf("") }
    var joinNickname by remember { mutableStateOf("") }

    // ルーム作成成功→QR画面へ
    LaunchedEffect(uiState.roomId, uiState.isHost) {
        if (uiState.roomId.isNotEmpty() && uiState.isHost) {
            onNavigateToQr()
        }
    }
    // 参加成功→待合室へ
    LaunchedEffect(uiState.roomId) {
        if (uiState.roomId.isNotEmpty() && !uiState.isHost) {
            onNavigateToWaiting()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "人数当てゲーム",
            fontSize = 32.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
        Text(
            text = "みんなの回答を予測しよう！",
            fontSize = 16.sp,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
            modifier = Modifier.padding(top = 8.dp, bottom = 48.dp),
        )

        Button(
            onClick = { showCreateDialog = true },
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
        ) {
            Text("ルームを作る", fontSize = 18.sp)
        }

        Spacer(modifier = Modifier.height(16.dp))

        OutlinedButton(
            onClick = { onNavigateToScanner() },
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
        ) {
            Text("QRコードで参加", fontSize = 18.sp)
        }

        Spacer(modifier = Modifier.height(8.dp))

        TextButton(onClick = { showJoinDialog = true }) {
            Text("ルームIDで参加")
        }
    }

    // バージョン表示
    Text(
        text = "v$versionName",
        fontSize = 11.sp,
        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.4f),
        modifier = Modifier
            .align(Alignment.BottomEnd)
            .padding(12.dp),
    )
    } // Box end

    // エラーSnackbar
    uiState.errorMessage?.let { msg ->
        AlertDialog(
            onDismissRequest = { viewModel.clearError() },
            title = { Text("エラー") },
            text = { Text(msg) },
            confirmButton = {
                TextButton(onClick = { viewModel.clearError() }) { Text("OK") }
            }
        )
    }

    // ルーム作成ダイアログ
    if (showCreateDialog) {
        AlertDialog(
            onDismissRequest = { showCreateDialog = false },
            title = { Text("ルームを作る") },
            text = {
                OutlinedTextField(
                    value = hostName,
                    onValueChange = { hostName = it },
                    label = { Text("あなたの名前") },
                    singleLine = true,
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (hostName.isNotBlank()) {
                            viewModel.createRoom(hostName.trim())
                            showCreateDialog = false
                        }
                    },
                    enabled = hostName.isNotBlank(),
                ) { Text("作成") }
            },
            dismissButton = {
                TextButton(onClick = { showCreateDialog = false }) { Text("キャンセル") }
            }
        )
    }

    // ルームID入力ダイアログ
    if (showJoinDialog) {
        AlertDialog(
            onDismissRequest = { showJoinDialog = false },
            title = { Text("ルームに参加") },
            text = {
                Column {
                    OutlinedTextField(
                        value = joinRoomId,
                        onValueChange = { joinRoomId = it.uppercase() },
                        label = { Text("ルームID") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = joinNickname,
                        onValueChange = { joinNickname = it },
                        label = { Text("ニックネーム") },
                        singleLine = true,
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (joinRoomId.isNotBlank() && joinNickname.isNotBlank()) {
                            viewModel.joinRoom(joinRoomId.trim(), joinNickname.trim())
                            showJoinDialog = false
                        }
                    },
                    enabled = joinRoomId.isNotBlank() && joinNickname.isNotBlank(),
                ) { Text("参加") }
            },
            dismissButton = {
                TextButton(onClick = { showJoinDialog = false }) { Text("キャンセル") }
            }
        )
    }
}
