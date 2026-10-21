package dev.brahmkshatriya.echo.extension

internal fun flog(msg: String) = println("FilenExt: $msg")

internal fun flog(msg: String, e: Throwable) {
    println("FilenExt: $msg -> ${e::class.simpleName}: ${e.message}")
    e.printStackTrace()
}