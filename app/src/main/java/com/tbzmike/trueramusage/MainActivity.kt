package com.tbzmike.trueramusage

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.tbzmike.trueramusage.ui.AggressiveReclaimOverlay
import com.tbzmike.trueramusage.ui.MemoryViewModel
import com.tbzmike.trueramusage.ui.TrueRamApp
import com.tbzmike.trueramusage.ui.UpdateOverlay
import com.tbzmike.trueramusage.ui.UpdateViewModel
import com.tbzmike.trueramusage.update.UpdatePreferences
import com.tbzmike.trueramusage.update.UpdateScheduler

class MainActivity : ComponentActivity() {
    private val memoryViewModel: MemoryViewModel by viewModels()
    private val updateViewModel: UpdateViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        UpdateScheduler.apply(this, UpdatePreferences(this).automaticUpdatesEnabled)
        setContent {
            Box(Modifier.fillMaxSize()) {
                TrueRamApp(memoryViewModel)
                AggressiveReclaimOverlay(
                    memoryViewModel,
                    Modifier
                        .align(Alignment.BottomStart)
                        .padding(20.dp)
                )
                UpdateOverlay(
                    updateViewModel,
                    Modifier
                        .align(Alignment.BottomEnd)
                        .padding(20.dp)
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        memoryViewModel.setMonitoringActive(true)
        updateViewModel.onForeground()
    }

    override fun onStop() {
        memoryViewModel.setMonitoringActive(false)
        super.onStop()
    }
}
