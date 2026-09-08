package com.tbzmike.trueramusage

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import com.tbzmike.trueramusage.ui.MemoryViewModel
import com.tbzmike.trueramusage.ui.TrueRamApp

class MainActivity : ComponentActivity() {
    private val memoryViewModel: MemoryViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            TrueRamApp(memoryViewModel)
        }
    }

    override fun onStart() {
        super.onStart()
        memoryViewModel.setMonitoringActive(true)
    }

    override fun onStop() {
        memoryViewModel.setMonitoringActive(false)
        super.onStop()
    }
}
