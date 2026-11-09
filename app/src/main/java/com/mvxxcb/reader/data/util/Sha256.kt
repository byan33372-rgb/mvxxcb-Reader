package com.mvxxcb.reader.data.util

import java.security.MessageDigest

/** SHA-256 十六进制工具（contentHash / 标题指纹共用）。 */
object Sha256 {

    fun hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    fun hexOf(text: String): String = hex(text.toByteArray(Charsets.UTF_8))
}
