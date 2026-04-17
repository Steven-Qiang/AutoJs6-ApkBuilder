package org.autojs.autojs.apkbuilder

import java.io.*
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.*

/**
 * 优化的 ZIP 构建器 - 使用 Java 标准库但优化性能
 */
class FastZipBuilder(private val templateApk: File) {
    
    private val modifiedEntries = ConcurrentHashMap<String, ByteArray>()
    private val deletedEntries = ConcurrentHashMap<String, Boolean>()
    
    fun putEntry(path: String, data: ByteArray) {
        modifiedEntries[path] = data
        deletedEntries.remove(path)
    }
    
    fun removeEntry(path: String) {
        deletedEntries[path] = true
        modifiedEntries.remove(path)
    }
    
    fun getEntry(path: String): ByteArray? {
        return modifiedEntries[path]
    }
    
    fun hasEntry(path: String): Boolean {
        return modifiedEntries.containsKey(path)
    }
    
    fun listEntries(): Set<String> {
        return modifiedEntries.keys.toSet()
    }
    
    /**
     * 构建最终的 APK - 优化版本
     */
    fun buildTo(outputFile: File, progressCallback: ((String) -> Unit)? = null) {
        outputFile.parentFile?.mkdirs()
        
        val buffer = ByteArray(64 * 1024) // 64KB buffer
        
        ZipOutputStream(BufferedOutputStream(FileOutputStream(outputFile), 256 * 1024)).use { zos ->
            zos.setLevel(Deflater.BEST_SPEED) // 使用快速压缩
            
            val addedEntries = mutableSetOf<String>()
            
            // 1. 从 template.apk 复制未修改的文件
            progressCallback?.invoke("Copying from template")
            ZipFile(templateApk).use { zipFile ->
                val entries = zipFile.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    val name = entry.name
                    
                    // 跳过旧签名
                    if (name.startsWith("META-INF/") && !name.startsWith("META-INF/services/")) {
                        continue
                    }
                    
                    // 跳过已修改或删除的文件
                    if (modifiedEntries.containsKey(name) || deletedEntries.containsKey(name)) {
                        continue
                    }
                    
                    // 复制原文件
                    val newEntry = ZipEntry(name)
                    newEntry.time = entry.time
                    
                    // 对于 STORED 方法，需要设置 size 和 crc
                    if (entry.method == ZipEntry.STORED) {
                        newEntry.method = ZipEntry.STORED
                        newEntry.size = entry.size
                        newEntry.compressedSize = entry.compressedSize
                        newEntry.crc = entry.crc
                    }
                    
                    zos.putNextEntry(newEntry)
                    if (!entry.isDirectory) {
                        zipFile.getInputStream(entry).use { input ->
                            var len: Int
                            while (input.read(buffer).also { len = it } > 0) {
                                zos.write(buffer, 0, len)
                            }
                        }
                    }
                    zos.closeEntry()
                    addedEntries.add(name)
                }
            }
            
            // 2. 写入修改的文件
            progressCallback?.invoke("Writing modified files")
            modifiedEntries.forEach { (name, data) ->
                if (!addedEntries.contains(name)) {
                    val entry = ZipEntry(name)
                    entry.method = ZipEntry.DEFLATED
                    zos.putNextEntry(entry)
                    zos.write(data)
                    zos.closeEntry()
                }
            }
            
            zos.flush()
        }
        
        progressCallback?.invoke("Build completed")
    }
}
