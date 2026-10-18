package com.shixiaoshi0417.lunexsu.magica

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Type compatibility only; never registered in the demo manifest and never starts services. */
class BootCompletedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) = Unit
}
