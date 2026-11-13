package com.example.myapp

import android.content.Context
import java.io.File

class F03AnalysisController(private val context: Context) {
    var busy: Boolean = false
    fun analyze(file: File, warehouseId: Int, callback: (Any) -> Unit) {
        callback(object {})
    }
    fun liveController(): F03LiveController {
        return F03LiveController()
    }
    fun cancel() {}
}

class F03LiveController {
    fun reanalyze(file: File, result: Any, warehouseId: Int, callback: (Any) -> Unit) {
        callback(object {})
    }
}
