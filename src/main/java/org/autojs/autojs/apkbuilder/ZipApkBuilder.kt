package org.autojs.autojs.apkbuilder

import org.autojs.autojs.apkbuilder.util.StreamUtils
import java.io.*
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * 直接操作 ZIP 的 APK 构建器，避免解压到磁盘导致的 Windows 大小写冲突问题
 * 
 * 核心思路：
 * 1. 保持原 ZIP 文件不解压
 * 2. 需要修改的文件在内存中处理
 * 3. 最终构建时，从原 ZIP 复制未修改的文件，写入修改的文件
 */
class ZipApkBuilder(
    private val templateApkFile: File,
    private val workspacePath: String
) {
    // 存储需要修改或新增的文件 (ZIP 路径 -> 文件内容)
    private val modifiedEntries = mutableMapOf<String, ByteArray>()
    
    // 存储需要删除的文件路径
    private val deletedEntries = mutableSetOf<String>()
    
    private var mCancelSignal: AtomicBoolean? = null
    
    fun setCancelSignal(cancelSignal: AtomicBoolean?) = also {
        mCancelSignal = cancelSignal
    }
    
    private fun ensureNotCancelled() {
        if (mCancelSignal?.get() == true) throw CancellationException("Build aborted")
        if (Thread.currentThread().isInterrupted) throw CancellationException("Build aborted")
    }
    
    /**
     * 添加或替换文件
     */
    fun putEntry(zipPath: String, data: ByteArray) {
        modifiedEntries[zipPath] = data
        deletedEntries.remove(zipPath)
    }
    
    /**
     * 添加或替换文件
     */
    fun putEntry(zipPath: String, file: File) {
        putEntry(zipPath, file.readBytes())
    }
    
    /**
     * 删除文件
     */
    fun removeEntry(zipPath: String) {
        deletedEntries.add(zipPath)
        modifiedEntries.remove(zipPath)
    }
    
    /**
     * 读取 ZIP 中的文件
     */
    fun getEntry(zipPath: String): ByteArray? {
        // 先检查是否在修改列表中
        modifiedEntries[zipPath]?.let { return it }
        
        // 从原始 ZIP 中读取
        ZipInputStream(BufferedInputStream(FileInputStream(templateApkFile), StreamUtils.DEFAULT_BUFFER_SIZE)).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                ensureNotCancelled()
                if (entry.name == zipPath && !entry.isDirectory) {
                    return zis.readBytes()
                }
                entry = zis.nextEntry
            }
        }
        return null
    }
    
    /**
     * 检查文件是否存在
     */
    fun hasEntry(zipPath: String): Boolean {
        if (deletedEntries.contains(zipPath)) return false
        if (modifiedEntries.containsKey(zipPath)) return true
        
        ZipInputStream(BufferedInputStream(FileInputStream(templateApkFile), StreamUtils.DEFAULT_BUFFER_SIZE)).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                ensureNotCancelled()
                if (entry.name == zipPath) return true
                entry = zis.nextEntry
            }
        }
        return false
    }
    
    /**
     * 构建新的 APK 到指定的输出流
     * 这个方法用于 TinySign.sign() 调用
     */
    fun buildToOutputStream(outputStream: OutputStream) {
        val processedEntries = mutableSetOf<String>()
        
        ZipOutputStream(BufferedOutputStream(outputStream, 256 * 1024)).use { zos ->
            // 1. 复制原 ZIP 中未被删除和修改的文件
            ZipInputStream(BufferedInputStream(FileInputStream(templateApkFile), StreamUtils.DEFAULT_BUFFER_SIZE)).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    ensureNotCancelled()
                    val name = entry.name
                    
                    // 跳过旧的签名文件，但保留 META-INF/services/
                    if (name.startsWith("META-INF/")) {
                        if (name.startsWith("META-INF/services/")) {
                            // 保留 services 目录，Rhino 需要它
                            val newEntry = ZipEntry(name)
                            newEntry.time = entry.time
                            zos.putNextEntry(newEntry)
                            if (!entry.isDirectory) {
                                val buffer = ByteArray(StreamUtils.DEFAULT_BUFFER_SIZE)
                                var len: Int
                                while (zis.read(buffer).also { len = it } > 0) {
                                    ensureNotCancelled()
                                    zos.write(buffer, 0, len)
                                }
                            }
                            zos.closeEntry()
                            processedEntries.add(name)
                        }
                        entry = zis.nextEntry
                        continue
                    }
                    
                    if (!deletedEntries.contains(name) && !modifiedEntries.containsKey(name)) {
                        // 直接复制
                        val newEntry = ZipEntry(name)
                        newEntry.time = entry.time
                        
                        zos.putNextEntry(newEntry)
                        if (!entry.isDirectory) {
                            val buffer = ByteArray(StreamUtils.DEFAULT_BUFFER_SIZE)
                            var len: Int
                            while (zis.read(buffer).also { len = it } > 0) {
                                ensureNotCancelled()
                                zos.write(buffer, 0, len)
                            }
                        }
                        zos.closeEntry()
                        processedEntries.add(name)
                    }
                    
                    entry = zis.nextEntry
                }
            }
            
            // 2. 写入修改和新增的文件
            modifiedEntries.forEach { (name, data) ->
                ensureNotCancelled()
                if (!processedEntries.contains(name)) {
                    val newEntry = ZipEntry(name)
                    zos.putNextEntry(newEntry)
                    zos.write(data)
                    zos.closeEntry()
                }
            }
        }
    }
    
    /**
     * 构建新的 APK 到文件
     */
    fun build(outputFile: File) {
        outputFile.parentFile?.mkdirs()
        BufferedOutputStream(FileOutputStream(outputFile), 256 * 1024).use { fos ->
            buildToOutputStream(fos)
            fos.flush()
        }
    }
    
    /**
     * 列出所有条目
     */
    fun listEntries(): List<String> {
        val entries = mutableSetOf<String>()
        
        // 从原 ZIP 读取
        ZipInputStream(BufferedInputStream(FileInputStream(templateApkFile), StreamUtils.DEFAULT_BUFFER_SIZE)).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                ensureNotCancelled()
                if (!deletedEntries.contains(entry.name)) {
                    entries.add(entry.name)
                }
                entry = zis.nextEntry
            }
        }
        
        // 添加修改的条目
        entries.addAll(modifiedEntries.keys)
        
        return entries.sorted()
    }
    
    /**
     * 解压到工作目录（仅用于需要文件系统访问的场景，如 resources.arsc 处理）
     * 只解压指定的文件
     */
    fun extractSpecificFiles(filesToExtract: List<String>) {
        val workspaceDir = File(workspacePath)
        workspaceDir.mkdirs()
        
        ZipInputStream(BufferedInputStream(FileInputStream(templateApkFile), StreamUtils.DEFAULT_BUFFER_SIZE)).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                ensureNotCancelled()
                val name = entry.name
                
                if (filesToExtract.contains(name) && !entry.isDirectory) {
                    val file = File(workspaceDir, name)
                    file.parentFile?.mkdirs()
                    
                    BufferedOutputStream(FileOutputStream(file), StreamUtils.DEFAULT_BUFFER_SIZE).use { bos ->
                        val buffer = ByteArray(StreamUtils.DEFAULT_BUFFER_SIZE)
                        var len: Int
                        while (zis.read(buffer).also { len = it } > 0) {
                            ensureNotCancelled()
                            bos.write(buffer, 0, len)
                        }
                    }
                }
                
                entry = zis.nextEntry
            }
        }
    }
}
