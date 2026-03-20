package com.rokusoudo.hitokazu.ui.screens

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.rokusoudo.hitokazu.viewmodel.GameViewModel

@Composable
fun QrScannerScreen(
    viewModel: GameViewModel,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    var nickname by remember { mutableStateOf("") }
    var scannedRoomId by remember { mutableStateOf("") }
    var hasPermission by remember { mutableStateOf(false) }

    val scanLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        result.contents?.let { rawValue ->
            // QRコードにはルームIDが直接入っている
            scannedRoomId = rawValue.trim().uppercase()
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasPermission = granted
        if (granted) {
            scanLauncher.launch(
                ScanOptions().apply {
                    setPrompt("QRコードをスキャンしてください")
                    setBeepEnabled(false)
                    setOrientationLocked(true)
                }
            )
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
    ) {
        Text(text = "QRコードで参加", fontSize = 24.sp)

        if (scannedRoomId.isEmpty()) {
            Button(
                onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                modifier = Modifier.fillMaxWidth().height(56.dp),
            ) {
                Text("カメラを起動してスキャン", fontSize = 16.sp)
            }
        } else {
            Text(
                text = "ルームID: $scannedRoomId",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            TextButton(onClick = {
                scannedRoomId = ""
            }) { Text("スキャンし直す") }
        }

        OutlinedTextField(
            value = nickname,
            onValueChange = { nickname = it },
            label = { Text("ニックネーム") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        Button(
            onClick = {
                viewModel.joinRoom(scannedRoomId, nickname.trim())
            },
            enabled = scannedRoomId.isNotEmpty() && nickname.isNotBlank(),
            modifier = Modifier.fillMaxWidth().height(56.dp),
        ) {
            Text("参加する", fontSize = 16.sp)
        }

        OutlinedButton(
            onClick = onBack,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("戻る")
        }
    }
}
