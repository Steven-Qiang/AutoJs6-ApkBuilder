package org.autojs.autojs.util

import java.security.MessageDigest
import java.security.NoSuchAlgorithmException

object MD5Utils {

    private const val TAG = "MD5Utils"

    @JvmStatic
    fun md5(string: String): String {
        return try {
            val md = MessageDigest.getInstance("MD5")
            md.update(string.toByteArray())
            bytesToHex(md.digest())
        } catch (e: NoSuchAlgorithmException) {
            throw RuntimeException(e)
        }
    }

    @JvmStatic
    fun md5(bytes: ByteArray): String {
        return try {
            val md = MessageDigest.getInstance("MD5")
            md.update(bytes)
            bytesToHex(md.digest())
        } catch (e: NoSuchAlgorithmException) {
            throw RuntimeException(e)
        }
    }

    private fun bytesToHex(bytes: ByteArray): String {
        val sb = StringBuilder()
        for (b in bytes) {
            sb.append(String.format("%02x", b.toInt() and 0xff))
        }
        return sb.toString()
    }
}
