package com.rovexquant.app.data.repository

/** Lifecycle-controlled stream port; transport implementations stay in data.remote. */
interface MarketStream {
    fun start(instrumentKeys: List<String>)
    fun stop()
}
