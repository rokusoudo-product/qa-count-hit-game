package com.rokusoudo.hitokazu

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.rokusoudo.hitokazu.data.model.GamePhase
import com.rokusoudo.hitokazu.ui.screens.*
import com.rokusoudo.hitokazu.ui.theme.HitokazuTheme
import com.rokusoudo.hitokazu.viewmodel.GameViewModel

object Routes {
    const val HOME = "home"
    const val QR_DISPLAY = "qr_display"
    const val QR_SCANNER = "qr_scanner"
    const val WAITING_ROOM = "waiting_room"
    const val ANSWERING = "answering"
    const val PREDICTING = "predicting"
    const val RESULT = "result"
    const val FINISHED = "finished"
    const val HOST_LEFT = "host_left"
}

class MainActivity : ComponentActivity() {

    private var gameViewModel: GameViewModel? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            HitokazuTheme {
                val navController = rememberNavController()
                val vm: GameViewModel = viewModel()
                gameViewModel = vm

                // 起動時のディープリンクを処理
                handleInviteIntent(intent, vm)

                // ホスト離脱によるルーム終了は、参加者がどの画面にいても割り込むため、
                // 各画面個別のLaunchedEffectではなくここで一括して監視する（Issue #15）。
                val uiState by vm.uiState.collectAsState()
                LaunchedEffect(uiState.phase) {
                    if (uiState.phase == GamePhase.HOST_LEFT) {
                        navController.navigate(Routes.HOST_LEFT) {
                            popUpTo(Routes.HOME)
                        }
                    }
                }

                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    NavHost(
                        navController = navController,
                        startDestination = Routes.HOME,
                        modifier = Modifier.padding(innerPadding),
                    ) {
                        composable(Routes.HOME) {
                            HomeScreen(
                                viewModel = vm,
                                onNavigateToQr = { navController.navigate(Routes.QR_DISPLAY) },
                                onNavigateToWaiting = { navController.navigate(Routes.WAITING_ROOM) },
                                onNavigateToScanner = { navController.navigate(Routes.QR_SCANNER) },
                            )
                        }
                        composable(Routes.QR_DISPLAY) {
                            QrDisplayScreen(
                                viewModel = vm,
                                onGameStarted = {
                                    navController.navigate(Routes.ANSWERING) {
                                        popUpTo(Routes.HOME)
                                    }
                                },
                                onLeaveRoom = {
                                    navController.navigate(Routes.HOME) {
                                        popUpTo(Routes.HOME) { inclusive = true }
                                    }
                                },
                            )
                        }
                        composable(Routes.QR_SCANNER) {
                            QrScannerScreen(
                                viewModel = vm,
                                onBack = { navController.popBackStack() },
                            )
                        }
                        composable(Routes.WAITING_ROOM) {
                            WaitingRoomScreen(
                                viewModel = vm,
                                onGameStarted = {
                                    navController.navigate(Routes.ANSWERING) {
                                        popUpTo(Routes.HOME)
                                    }
                                },
                                onLeaveRoom = {
                                    navController.navigate(Routes.HOME) {
                                        popUpTo(Routes.HOME) { inclusive = true }
                                    }
                                },
                            )
                        }
                        composable(Routes.ANSWERING) {
                            AnsweringScreen(
                                viewModel = vm,
                                onPredicting = { navController.navigate(Routes.PREDICTING) },
                                onLeaveRoom = {
                                    navController.navigate(Routes.HOME) {
                                        popUpTo(Routes.HOME) { inclusive = true }
                                    }
                                },
                            )
                        }
                        composable(Routes.PREDICTING) {
                            PredictingScreen(
                                viewModel = vm,
                                onResult = { navController.navigate(Routes.RESULT) },
                                onLeaveRoom = {
                                    navController.navigate(Routes.HOME) {
                                        popUpTo(Routes.HOME) { inclusive = true }
                                    }
                                },
                            )
                        }
                        composable(Routes.RESULT) {
                            ResultScreen(
                                viewModel = vm,
                                onNextRound = {
                                    navController.navigate(Routes.ANSWERING) {
                                        popUpTo(Routes.ANSWERING) { inclusive = true }
                                    }
                                },
                                onFinished = {
                                    navController.navigate(Routes.FINISHED) {
                                        popUpTo(Routes.HOME)
                                    }
                                },
                            )
                        }
                        composable(Routes.HOST_LEFT) {
                            HostLeftScreen(
                                onLeaveRoom = {
                                    vm.resetGame()
                                    navController.navigate(Routes.HOME) {
                                        popUpTo(Routes.HOME) { inclusive = true }
                                    }
                                },
                            )
                        }
                        composable(Routes.FINISHED) {
                            val finishedUiState by vm.uiState.collectAsState()
                            FinishedScreen(
                                viewModel = vm,
                                onRestart = {
                                    // restartGame() は roomId/observer を維持したまま status を
                                    // WAITING に戻す。実際の画面遷移は onWaitingRoom（下記）で行う。
                                    vm.restartGame()
                                },
                                onHome = {
                                    // 「トップに戻る」は従来どおりルームから離脱してホーム画面へ戻す。
                                    // onRestart（再戦）とは明確に別の挙動（Issue #17）。
                                    vm.resetGame()
                                    navController.navigate(Routes.HOME) {
                                        popUpTo(Routes.HOME) { inclusive = true }
                                    }
                                },
                                onWaitingRoom = {
                                    // ホストは QR表示/開始画面へ、参加者は待合室へ。
                                    // どちらも既存のANSWERING検知でゲーム開始に追従する。
                                    val destination = if (finishedUiState.isHost) Routes.QR_DISPLAY else Routes.WAITING_ROOM
                                    navController.navigate(destination) {
                                        popUpTo(Routes.HOME)
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    // アプリが既に起動中に招待URLをタップした場合
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        gameViewModel?.let { handleInviteIntent(intent, it) }
    }

    // 招待URL（https://hitokazu.rokusoudo.com/join/{roomId}）からroomIdを抽出
    private fun handleInviteIntent(intent: Intent, vm: GameViewModel) {
        if (intent.action != Intent.ACTION_VIEW) return
        val uri = intent.data ?: return
        val pathSegments = uri.pathSegments
        // パスが /join/{roomId} の形式を期待
        if (pathSegments.size >= 2 && pathSegments[0] == "join") {
            val roomId = pathSegments[1].uppercase()
            if (roomId.isNotBlank()) {
                vm.setPendingJoinRoomId(roomId)
            }
        }
    }
}
