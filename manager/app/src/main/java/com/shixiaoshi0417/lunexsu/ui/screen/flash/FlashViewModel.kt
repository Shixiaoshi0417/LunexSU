package com.shixiaoshi0417.lunexsu.ui.screen.flash

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class FlashViewModel : ViewModel() {
    internal val operation = FlashOperation(viewModelScope)

    fun start(request: FlashIt, restoredAttempt: Boolean) {
        operation.start(restoredAttempt) { stdout, stderr ->
            withContext(Dispatchers.IO) { flashIt(request, stdout, stderr) }
        }
    }
}
