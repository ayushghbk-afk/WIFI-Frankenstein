package com.spectrefi.app.ui.wpsgenerator

data class WpsGeneratorResult(
    val ssid: String,
    val bssid: String,
    val pins: List<WPSPin>
)
