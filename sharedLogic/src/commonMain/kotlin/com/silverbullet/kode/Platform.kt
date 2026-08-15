package com.silverbullet.kode

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform