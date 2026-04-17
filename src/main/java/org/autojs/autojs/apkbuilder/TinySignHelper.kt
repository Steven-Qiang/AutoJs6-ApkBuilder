package org.autojs.autojs.apkbuilder

import java.io.OutputStream

/**
 * TinySign 的包装类，支持从 ZipApkBuilder 直接构建未签名的 APK
 */
object TinySignHelper {
    
    /**
     * 从 ZipApkBuilder 构建未签名的 APK
     */
    @JvmStatic
    fun signFromZipBuilder(zipBuilder: ZipApkBuilder, outputStream: OutputStream) {
        // 直接从 ZipApkBuilder 构建到输出流
        // 注意：这里不包含签名，只是构建 ZIP
        zipBuilder.buildToOutputStream(outputStream)
    }
}
