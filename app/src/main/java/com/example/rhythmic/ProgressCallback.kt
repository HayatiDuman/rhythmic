package com.example.rhythmic

import android.os.Handler

import com.chaquo.python.PyObject

class ProgressCallback(
    private val onProgress: (Int) -> Unit
) {
    @Suppress("unused") // Python burayı çağıracak
    fun __call__(percent: Int) {
        onProgress(percent)
    }
}

