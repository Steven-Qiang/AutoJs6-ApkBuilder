package org.autojs.autojs.apkbuilder

import org.autojs.autojs.project.ProjectConfig
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import kotlin.system.exitProcess

object ConsoleProgressCallback : ApkBuilder.ProgressCallback {
    override fun onPrepare(builder: ApkBuilder) = println("[PREPARE] Starting build process...")

    override fun onBuild(builder: ApkBuilder) = println("[BUILD] Building APK...")

    override fun onSign(builder: ApkBuilder) = println("[SIGN] Signing APK...")

    override fun onClean(builder: ApkBuilder) = println("[CLEAN] Cleaning up workspace...")

    override fun onStepProgress(builder: ApkBuilder, title: String, detail: String?) {
        val msg = if (detail != null) "  $title - $detail" else "  $title"
        println(msg)
    }

    override fun onFinished(builder: ApkBuilder) = println("\nBuild completed successfully!")
}

class ApkBuilderCli {

    companion object {
        @JvmStatic
        fun main(args: Array<String>) {
            System.setProperty("file.encoding", "UTF-8")
            System.setProperty("sun.jnu.encoding", "UTF-8")
            try {
                val utf8 = java.nio.charset.Charset.forName("UTF-8")
                System.setOut(java.io.PrintStream(System.out, true, utf8))
                System.setErr(java.io.PrintStream(System.err, true, utf8))
            } catch (e: Exception) {
                e.printStackTrace()
            }

            println("=".repeat(60))
            println("  AutoJs6 APK Builder")
            println("=".repeat(60))
            println()

            try {
                val options = parseArgs(args)

                val autoJsApk = File(options.autoJsApk)
                if (!autoJsApk.exists()) {
                    throw IllegalArgumentException("AutoJs6 APK not found: ${options.autoJsApk}")
                }

                val sourcePath = options.sourcePath
                if (!File(sourcePath).exists()) {
                    throw IllegalArgumentException("Source path not found: $sourcePath")
                }

                val outputApk = File(options.outputApk)
                val buildPath = options.buildPath ?: "./build_workspace"

                println("Configuration:")
                println("  AutoJs6 APK : ${options.autoJsApk}")
                println("  Source      : ${options.sourcePath}")
                println("  Output      : ${options.outputApk}")
                println("  Build Path  : $buildPath")
                options.keystore?.let { println("  Keystore    : $it") }
                options.keystorePassword?.let { println("  Keystore password: ***") }
                options.keyAlias?.let { println("  Key Alias   : $it") }
                options.keyPassword?.let { println("  Key password: ***") }
                println()

                val tempDir = File("./temp").apply { mkdirs() }
                try {
                    println("Extracting from AutoJs6 APK...")

                    val templateApk = File(tempDir, "template.apk")
                    val extractedAssets = File(tempDir, "assets")
                    val extractedLibs = File(tempDir, "lib")

                    extractFromApk(autoJsApk, templateApk, extractedAssets, extractedLibs)

                    println("  ✓ template.apk extracted")
                    println("  ✓ assets extracted")
                    println("  ✓ libs extracted")

                    val configFile = File(sourcePath).let { src ->
                        if (src.isDirectory) File(src, "project.json") else File(src.parentFile, "project.json")
                    }

                    val config = loadConfig(configFile.absolutePath, sourcePath)
                        ?: createDefaultConfig(sourcePath)

                    val builder = ApkBuilder(
                        templateApk = templateApk,
                        outApkFile = outputApk,
                        buildPath = buildPath
                    )

                    builder.setLibrarySourceDirectory(extractedLibs)
                    builder.setAssetSourceDirectory(tempDir)

                    builder
                        .setProgressCallback(ConsoleProgressCallback)
                        .setCancelSignal(AtomicBoolean(false))
                        .prepare()
                        .withConfig(config)
                        .build()

                    options.keystore?.let { builder.setCustomKeystore(File(it)) }
                    options.keystorePassword?.let { builder.setKeystorePassword(it) }
                    options.keyAlias?.let { builder.setKeyAlias(it) }
                    options.keyPassword?.let { builder.setKeyPassword(it) }

                    builder.sign()

                    if (outputApk.exists()) {
                        println("\nAPK generated successfully!")
                        println("   File: ${outputApk.absolutePath}")
                        println("   Size: ${String.format("%.2f", outputApk.length() / 1024.0 / 1024.0)} MB")
                    } else {
                        throw RuntimeException("APK was not created!")
                    }

                    builder
                        .commitProjectConfigIfNeeded()
                        .cleanWorkspace()
                        .finish()

                } finally {
                    tempDir.deleteRecursively()
                }

            } catch (e: Exception) {
                System.err.println("\nBuild failed: ${e.message}")
                e.printStackTrace()
                exitProcess(1)
            }
        }

        private fun extractFromApk(apkFile: File, templateApk: File, assetsDir: File, libsDir: File) {
            ZipInputStream(java.io.BufferedInputStream(java.io.FileInputStream(apkFile))).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    val name = entry.name
                    when {
                        name == "assets/template.apk" -> {
                            templateApk.parentFile?.mkdirs()
                            templateApk.outputStream().use { zis.copyTo(it) }
                        }

                        name.startsWith("assets/") && !name.startsWith("assets/project") -> {
                            val outFile = File(assetsDir, name.removePrefix("assets/"))
                            outFile.parentFile?.mkdirs()
                            if (!entry.isDirectory) {
                                outFile.outputStream().use { zis.copyTo(it) }
                            }
                        }

                        name.startsWith("lib/") -> {
                            val outFile = File(libsDir, name.removePrefix("lib/"))
                            outFile.parentFile?.mkdirs()
                            if (!entry.isDirectory) {
                                outFile.outputStream().use { zis.copyTo(it) }
                            }
                        }
                    }
                    entry = zis.nextEntry
                }
            }
        }

        private data class BuildOptions(
            val autoJsApk: String,
            val sourcePath: String,
            val outputApk: String,
            val buildPath: String?,
            val keystore: String?,
            val keystorePassword: String?,
            val keyAlias: String?,
            val keyPassword: String?
        )

        private fun parseArgs(args: Array<String>): BuildOptions {
            var autoJsApk: String? = null
            var sourcePath: String? = null
            var outputApk = "output.apk"
            var buildPath: String? = null
            var keystore: String? = null
            var keystorePassword: String? = null
            var keyAlias: String? = null
            var keyPassword: String? = null

            var i = 0
            while (i < args.size) {
                when (args[i]) {
                    "--autojs", "-a" -> {
                        i++; autoJsApk =
                            args.getOrElse(i) { throw IllegalArgumentException("Missing value for --autojs") }
                    }

                    "--source", "-s" -> {
                        i++; sourcePath =
                            args.getOrElse(i) { throw IllegalArgumentException("Missing value for --source") }
                    }

                    "--output", "-o" -> {
                        i++; outputApk =
                            args.getOrElse(i) { throw IllegalArgumentException("Missing value for --output") }
                    }

                    "--workspace", "-w" -> {
                        i++; buildPath =
                            args.getOrElse(i) { throw IllegalArgumentException("Missing value for --workspace") }
                    }

                    "--keystore", "-k" -> {
                        i++; keystore =
                            args.getOrElse(i) { throw IllegalArgumentException("Missing value for --keystore") }
                    }

                    "--keystore-password", "-kp" -> {
                        i++; keystorePassword =
                            args.getOrElse(i) { throw IllegalArgumentException("Missing value for --keystore-password") }
                    }

                    "--key-alias", "-ka" -> {
                        i++; keyAlias =
                            args.getOrElse(i) { throw IllegalArgumentException("Missing value for --key-alias") }
                    }

                    "--key-password", "-kpa" -> {
                        i++; keyPassword =
                            args.getOrElse(i) { throw IllegalArgumentException("Missing value for --key-password") }
                    }

                    "--help", "-h" -> printHelpAndExit()
                }
                i++
            }

            if (autoJsApk == null) {
                throw IllegalArgumentException("Missing required argument: --autojs (-a)")
            }
            if (sourcePath == null) {
                throw IllegalArgumentException("Missing required argument: --source (-s)")
            }

            return BuildOptions(autoJsApk, sourcePath, outputApk, buildPath, keystore, keystorePassword, keyAlias, keyPassword)
        }

        private fun printHelpAndExit() {
            println(
                """
AutoJs6 APK Builder CLI - Command line tool for packaging AutoJs6 projects

Usage:
  java -jar apkbuilder-cli.jar [OPTIONS]

Required Options:
  -a, --autojs <path>       Path to AutoJs6 APK file
  -s, --source <path>       Project directory or .js script file

Optional Options:
  -o, --output <path>       Output APK path (default: output.apk)
  -w, --workspace <path>    Build workspace directory (default: ./build_workspace)
  -k, --keystore <path>     Custom keystore path (uses default signature if not specified)
  -kp, --keystore-password <password>
                            Keystore password (default: AutoJs6)
  -ka, --key-alias <alias>  Key alias in keystore (default: AutoJs6)
  -kpa, --key-password <password>
                            Key password (defaults to keystore password if not specified)
  -h, --help                Show this help message and exit

Examples:
  # Basic usage - package single script
  java -jar apkbuilder-cli.jar -a autojs6.apk -s main.js -o myapp.apk

  # Package project directory
  java -jar apkbuilder-cli.jar -a autojs6.apk -s ./my_project -o myapp.apk

  # Use custom workspace
  java -jar apkbuilder-cli.jar -a autojs6.apk -s ./my_project -o myapp.apk -w ./build

  # Use custom keystore
  java -jar apkbuilder-cli.jar -a autojs6.apk -s ./my_project -o myapp.apk \
    -k my-release-key.keystore -kp mypassword -ka myalias -kpa mykeypassword

Project Home: https://github.com/Steven-Qiang/AutoJs6-ApkBuilder
            """.trimIndent()
            )
            exitProcess(0)
        }

        private fun loadConfig(configPath: String, sourcePath: String?): ProjectConfig? {
            val configFile = File(configPath)
            return if (!configFile.exists()) {
                null
            } else {
                ProjectConfig.fromFile(configFile)?.also { config ->
                    sourcePath?.let { config.setSourcePath(it) }
                    val iconPath = config.iconPath
                    if (iconPath != null) {
                        val iconFile = if (iconPath.startsWith("./") || iconPath.startsWith(".\\")) {
                            File(configFile.parentFile, iconPath.removePrefix("./").removePrefix(".\\"))
                        } else {
                            File(iconPath)
                        }
                        if (iconFile.exists()) {
                            println("Setting icon from: ${iconFile.path}")
                            config.setIconGetter(java.util.concurrent.Callable<java.awt.image.BufferedImage> {
                                javax.imageio.ImageIO.read(iconFile)
                            })
                        }
                    }
                }
            }
        }

        private fun createDefaultConfig(sourcePath: String): ProjectConfig {
            val srcFile = File(sourcePath)
            return ProjectConfig().also { config ->
                config.setName(srcFile.nameWithoutExtension)
                config.setPackageName("com.example.${srcFile.nameWithoutExtension.lowercase()}")
                config.setVersionName("1.0.0")
                config.setVersionCode(1)
                config.setMainScriptFileName("main.js")
                config.setSourcePath(sourcePath)
                config.setPermissions(mutableListOf(
                    "android.permission.INTERNET",
                    "android.permission.WAKE_LOCK"
                ))
                config.setSignatureScheme("V1 + V2")
            }
        }
    }
}
