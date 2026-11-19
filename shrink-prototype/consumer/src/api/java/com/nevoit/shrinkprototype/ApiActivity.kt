package com.nevoit.shrinkprototype

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent

class ApiActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { ApiExample() }
    }
}
