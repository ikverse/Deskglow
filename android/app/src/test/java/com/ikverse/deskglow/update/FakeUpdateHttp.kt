package com.ikverse.deskglow.update

import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream

/** Answers each request with the next queued status and body, and remembers what was asked. */
class FakeUpdateHttp : UpdateHttp {
    class Asked(val url: String, val accept: String?)

    private val answers = ArrayDeque<() -> Pair<Int, InputStream>>()
    val asked = ArrayList<Asked>()

    fun answer(code: Int, body: ByteArray) {
        answers += { code to ByteArrayInputStream(body) }
    }

    fun answer(code: Int, body: String) = answer(code, body.toByteArray())

    /** A body that delivers [good] bytes and then fails, like a connection that drops. */
    fun breakAfter(body: ByteArray, good: Int) {
        answers += {
            200 to object : InputStream() {
                var at = 0
                override fun read(): Int {
                    if (at >= good) throw IOException("connection reset")
                    return body[at++].toInt() and 0xff
                }
            }
        }
    }

    fun offline() {
        answers += { throw IOException("no route") }
    }

    override fun <T> get(url: String, accept: String?, read: (code: Int, body: InputStream) -> T): T {
        asked += Asked(url, accept)
        val (code, body) = answers.removeFirst()()
        return body.use { read(code, it) }
    }
}
