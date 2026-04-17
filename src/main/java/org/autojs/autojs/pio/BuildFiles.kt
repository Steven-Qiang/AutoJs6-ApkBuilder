package org.autojs.autojs.pio

import java.io.Closeable
import java.io.File
import java.io.File.separator
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.Charset

object BuildFiles {

    const val DEFAULT_BUFFER_SIZE = 8192

    @JvmStatic
    fun create(path: String): Boolean {
        val f = File(path)
        return when {
            path.endsWith(separator) -> f.mkdir()
            else -> runCatching { f.createNewFile() }.isSuccess
        }
    }

    @JvmStatic
    fun createIfNotExists(path: String): Boolean {
        ensureDir(path)
        val file = File(path)
        if (!file.exists()) {
            try {
                return file.createNewFile()
            } catch (e: IOException) {
                e.printStackTrace()
            }
        }
        return false
    }

    @JvmStatic
    fun createWithDirs(path: String) = createIfNotExists(path)

    @JvmStatic
    fun exists(path: String?) = path?.let { File(it).exists() } ?: false

    @JvmStatic
    fun ensureDir(path: String): Boolean {
        val i = path.lastIndexOf(separator)
        return if (i >= 0) {
            val folder = path.take(i)
            val file = File(folder)
            file.exists() || file.mkdirs()
        } else false
    }

    @JvmStatic
    fun read(path: String?) = path?.let { read(File(it)) } ?: ""

    @JvmStatic
    fun read(file: File?): String {
        return try {
            read(FileInputStream(file))
        } catch (e: FileNotFoundException) {
            throw UncheckedIOException(e)
        }
    }

    @JvmStatic
    fun read(inputStream: InputStream): String {
        return try {
            val bytes = ByteArray(inputStream.available())
            inputStream.read(bytes)
            String(bytes, Charsets.UTF_8)
        } catch (e: IOException) {
            throw UncheckedIOException(e)
        } finally {
            closeSilently(inputStream)
        }
    }

    private fun isValidUtf8(bytes: ByteArray): Boolean {
        return try {
            val decoder = Charsets.UTF_8.newDecoder()
            decoder.decode(java.nio.ByteBuffer.wrap(bytes))
            true
        } catch (e: Exception) {
            false
        }
    }

    fun readBytes(stream: InputStream): ByteArray {
        return try {
            ByteArray(stream.available()).also { stream.read(it) }
        } catch (e: IOException) {
            throw UncheckedIOException(e)
        }
    }

    @JvmStatic
    fun copyStream(stream: InputStream, path: String): Boolean {
        if (!ensureDir(path)) return false
        val file = File(path)
        return try {
            if (!file.exists() && !file.createNewFile()) return false
            val fos = FileOutputStream(file)
            true.also { write(stream, fos) }
        } catch (e: IOException) {
            false.also { e.printStackTrace() }
        } catch (e: UncheckedIOException) {
            false.also { e.printStackTrace() }
        }
    }

    @JvmStatic
    @JvmOverloads
    fun write(stream: InputStream, os: OutputStream, close: Boolean = true) {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        try {
            while (stream.available() > 0) {
                val n = stream.read(buffer)
                if (n > 0) {
                    os.write(buffer, 0, n)
                }
            }
            if (close) {
                stream.close()
                os.close()
            }
        } catch (e: IOException) {
            throw UncheckedIOException(e)
        }
    }

    @JvmStatic
    fun write(path: String?, text: String) = write(path?.let { File(it) }, text)

    @JvmStatic
    fun write(file: File?, text: String?) {
        try {
            text?.let { write(FileOutputStream(file), it) }
        } catch (e: FileNotFoundException) {
            throw UncheckedIOException(e)
        }
    }

    @JvmStatic
    fun write(fileOutputStream: OutputStream, text: String) {
        try {
            fileOutputStream.write(text.toByteArray(Charsets.UTF_8))
        } catch (e: IOException) {
            throw UncheckedIOException(e)
        } finally {
            closeSilently(fileOutputStream)
        }
    }

    @JvmStatic
    fun append(path: String, text: String) {
        create(path)
        try {
            write(FileOutputStream(path, true), text)
        } catch (e: FileNotFoundException) {
            throw UncheckedIOException(e)
        }
    }

    fun writeBytes(outputStream: OutputStream, bytes: ByteArray?) {
        try {
            outputStream.write(bytes)
            outputStream.close()
        } catch (e: IOException) {
            throw UncheckedIOException(e)
        }
    }

    @JvmStatic
    fun appendBytes(path: String, bytes: ByteArray?) {
        create(path)
        try {
            writeBytes(FileOutputStream(path, true), bytes)
        } catch (e: FileNotFoundException) {
            throw UncheckedIOException(e)
        }
    }

    @JvmStatic
    fun writeBytes(path: String?, bytes: ByteArray?) {
        try {
            writeBytes(FileOutputStream(path), bytes)
        } catch (e: FileNotFoundException) {
            throw UncheckedIOException(e)
        }
    }

    @JvmStatic
    fun copy(pathFrom: String?, pathTo: String): Boolean {
        return try {
            copyStream(FileInputStream(pathFrom), pathTo)
        } catch (e: FileNotFoundException) {
            e.printStackTrace()
            false
        }
    }

    @JvmStatic
    fun renameWithoutExtension(path: String, newName: String): Boolean {
        val file = File(path)
        val newFile = File(file.parent, newName + "." + getExtension(file.name))
        return file.renameTo(newFile)
    }

    @JvmStatic
    fun rename(path: String, newName: String): Boolean {
        val f = File(path)
        return f.renameTo(File(f.parent, newName))
    }

    @JvmStatic
    fun move(path: String, newPath: String): Boolean {
        val f = File(path)
        return f.renameTo(File(newPath))
    }

    @JvmStatic
    fun getExtension(fileName: String): String {
        val dotIndex = fileName.lastIndexOf('.')
        return if (dotIndex == -1 || dotIndex == fileName.length - 1) "" else fileName.substring(dotIndex + 1)
    }

    @JvmStatic
    fun getName(filePath: String): String = File(filePath.replace('\\', '/')).name

    @JvmStatic
    fun getNameWithoutExtension(filePath: String): String {
        val fileName = getName(filePath)
        var b = fileName.lastIndexOf('.')
        if (b < 0) b = fileName.length
        return fileName.take(b)
    }

    @JvmStatic
    fun deleteRecursively(file: File): Boolean {
        if (file.isDirectory) {
            val children = file.listFiles()
            if (children != null) {
                for (child in children) {
                    if (!deleteRecursively(child)) {
                        return false
                    }
                }
            }
        }
        return file.delete()
    }

    @JvmStatic
    fun remove(path: String?): Boolean {
        return path?.let { File(it).delete() } ?: false
    }

    @JvmStatic
    fun removeDir(path: String?): Boolean {
        return path?.let { deleteRecursively(File(it)) } ?: false
    }

    @JvmStatic
    fun listDir(path: String?): Array<String> {
        val list = path?.let { File(it).list() }
        return list ?: emptyArray()
    }

    @JvmStatic
    fun isFile(path: String?) = path?.let { File(it).isFile } ?: false

    @JvmStatic
    fun isDir(path: String?) = path?.let { File(it).isDirectory } ?: false

    @JvmStatic
    fun join(base: String?, vararg paths: String?): String {
        base ?: return ""
        var file = File(base)
        for (path in paths) {
            file = path?.let { File(file, it) } ?: file
        }
        return file.path
    }

    @JvmStatic
    fun readBytes(path: String?): ByteArray {
        return try {
            readBytes(FileInputStream(path))
        } catch (e: FileNotFoundException) {
            throw UncheckedIOException(e)
        }
    }

    @JvmStatic
    fun closeSilently(closeable: Closeable?) {
        runCatching { closeable?.close() }
    }
}

class UncheckedIOException(cause: IOException) : RuntimeException(cause)
