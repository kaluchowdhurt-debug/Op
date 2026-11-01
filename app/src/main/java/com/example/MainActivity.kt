package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.data.local.AssistantDatabase
import com.example.data.repository.AssistantRepository
import com.example.ui.screens.NovaBotAppScreen
import com.example.ui.theme.MyApplicationTheme
import com.example.util.configureAppCheck
import com.example.viewmodel.AssistantViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // STEP 1: Configure Firebase App Check with launch intent BEFORE UI, ViewModels, or Firebase calls
        configureAppCheck(this, intent)

        // STEP 2: Enable Edge-to-Edge layout
        enableEdgeToEdge()

        val database = AssistantDatabase.getInstance(applicationContext)
        val repository = AssistantRepository(database.assistantDao())

        setContent {
            MyApplicationTheme {
                val assistantViewModel: AssistantViewModel = viewModel(
                    factory = AssistantViewModel.Factory(repository)
                )
                NovaBotAppScreen(viewModel = assistantViewModel)
            }
        }
    }
}
