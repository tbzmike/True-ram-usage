package com.tbzmike.trueramusage.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tbzmike.trueramusage.data.MemoryRepository
import com.tbzmike.trueramusage.data.MemorySnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MemoryViewModel(
    private val repository: MemoryRepository = MemoryRepository()
) : ViewModel() {
    var snapshot by mutableStateOf<MemorySnapshot?>(null)
        private set

    var errorMessage by mutableStateOf<String?>(null)
        private set

    init {
        viewModelScope.launch {
            while (isActive) {
                refresh()
                delay(2_000)
            }
        }
    }

    fun refreshNow() {
        viewModelScope.launch { refresh() }
    }

    private suspend fun refresh() {
        runCatching {
            withContext(Dispatchers.IO) { repository.readSnapshot() }
        }.onSuccess {
            snapshot = it
            errorMessage = null
        }.onFailure {
            errorMessage = it.message ?: "Unable to read memory information"
        }
    }
}
