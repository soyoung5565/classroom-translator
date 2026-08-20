package com.classroom.translator.ui

import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.classroom.translator.ui.screens.HistoryScreen
import com.classroom.translator.ui.screens.MainScreen
import com.classroom.translator.ui.screens.SessionDetailScreen
import com.classroom.translator.ui.screens.SettingsScreen
import com.classroom.translator.ui.screens.WholeSessionScreen

@Composable
fun MainNavHost() {
    val navController = rememberNavController()
    val sharedViewModel: TranslatorViewModel = viewModel()

    NavHost(navController = navController, startDestination = "history") {
        composable("history") {
            HistoryScreen(
                onNavigateToRecording = {
                    sharedViewModel.startNewSession()
                    navController.navigate("recording")
                },
                onNavigateToSession = { sessionId ->
                    navController.navigate("session_detail/$sessionId")
                },
                onNavigateToSettings = { navController.navigate("settings") },
                onNavigateToWholeSession = { navController.navigate("whole_session") }
            )
        }
        composable("recording") {
            MainScreen(
                onNavigateToSettings = { navController.navigate("settings") },
                onNavigateToHistory = { navController.navigate("history") },
                viewModel = sharedViewModel
            )
        }
        composable(
            "session_detail/{sessionId}",
            arguments = listOf(navArgument("sessionId") { type = NavType.LongType })
        ) { backStackEntry ->
            val sessionId = backStackEntry.arguments?.getLong("sessionId") ?: 0L
            SessionDetailScreen(
                sessionId = sessionId,
                onBack = { navController.popBackStack() }
            )
        }
        composable("whole_session") {
            WholeSessionScreen(
                onBack = { navController.popBackStack() }
            )
        }
        composable("settings") {
            SettingsScreen(
                onBack = { navController.popBackStack() },
                viewModel = sharedViewModel
            )
        }
    }
}
