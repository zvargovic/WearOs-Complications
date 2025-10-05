package com.example.complicationprovider.tiles

object TilePngCache {
    @Volatile var bytes: ByteArray? = null
    @Volatile var version: String = "init"

    fun clear() {
        bytes = null
        version = "init"
    }
}