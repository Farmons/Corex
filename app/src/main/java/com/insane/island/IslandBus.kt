package com.insane.island

// Связь голосового сервиса с островом (подсветка при команде)
object IslandBus {
    @Volatile var onCommand: (() -> Unit)? = null
}
