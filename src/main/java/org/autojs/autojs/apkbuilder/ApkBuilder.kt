package org.autojs.autojs.apkbuilder

import com.mcal.apksigner.ApkSigner
import com.reandroid.arsc.chunk.TableBlock
import org.autojs.autojs.engine.encryption.AdvancedEncryptionStandard
import org.autojs.autojs.pio.BuildFiles
import org.autojs.autojs.project.BuildInfo
import org.autojs.autojs.project.LaunchConfig
import org.autojs.autojs.project.ProjectConfig
import org.autojs.autojs.script.EncryptedScriptFileHeader.writeHeader
import org.autojs.autojs.script.JavaScriptFileSource
import org.autojs.autojs.util.FileUtils.TYPE.JAVASCRIPT
import org.autojs.autojs.util.MD5Utils
import pxb.android.StringItem
import pxb.android.axml.AxmlWriter
import org.autojs.autojs.apkbuilder.TinySign
import zhao.arsceditor.ResDecoder.ARSCDecoder
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import javax.imageio.ImageIO

class ApkBuilder(
    private val templateApk: File,
    private val outApkFile: File,
    private val buildPath: String
) {

    private var mProgressCallback: ProgressCallback? = null
    private var mArscPackageName: String? = null
    private var mManifestEditor: ManifestEditor? = null
    private var mInitVector: String? = null
    private var mKey: String? = null
    private var mCancelSignal: AtomicBoolean? = null
    private var mPendingProjectConfigFile: File? = null
    private var mPendingProjectConfigJson: String? = null
    private var mBundledProjectConfigJson: String? = null
    private var mCustomKeystoreFile: File? = null
    private var mKeystorePassword: String? = null
    private var mKeyAlias: String? = null
    private var mKeyPassword: String? = null

    private lateinit var mProjectConfig: ProjectConfig

    private lateinit var mFastZipBuilder: FastZipBuilder

    private var mLibsIncludes = Lib.defaultLibsToInclude.toMutableList()
    private var mAssetsFileIncludes = Lib.defaultAssetFilesToInclude.map(::normalizeAssetPath).toMutableList()
    private var mAssetsDirExcludes = Lib.defaultAssetDirsToExclude.map(::normalizeAssetPath).toMutableList()

    private var mLibrarySourceDir: File? = null
    private var mAssetSourceDir: File? = null

    // 临时目录用于存储需要处理的文件
    private val mTempDir = File(buildPath, "temp")

    private val mManifestFile get() = File(mTempDir, "AndroidManifest.xml")
    private val mResourcesArscFile get() = File(mTempDir, "resources.arsc")

    private fun getTempAssetsRoot(): File = File(mTempDir, "assets")

    fun setLibrarySourceDirectory(libDir: File) = also { mLibrarySourceDir = libDir }

    fun setAssetSourceDirectory(assetDir: File) = also { mAssetSourceDir = assetDir }

    fun setCustomKeystore(keystoreFile: File) = also { mCustomKeystoreFile = keystoreFile }

    fun setKeystorePassword(password: String) = also { mKeystorePassword = password }

    fun setKeyAlias(alias: String) = also { mKeyAlias = alias }

    fun setKeyPassword(password: String) = also { mKeyPassword = password }

    init {
        BuildFiles.ensureDir(outApkFile.path)
    }

    fun setProgressCallback(callback: ProgressCallback?) = also { mProgressCallback = callback }

    fun setCancelSignal(cancelSignal: AtomicBoolean?) = also {
        mCancelSignal = cancelSignal
    }

    private fun ensureNotCancelled() {
        if (mCancelSignal?.get() == true) throw CancellationException("Build aborted")
        if (Thread.currentThread().isInterrupted) throw CancellationException("Build aborted")
    }

    private fun copyStreamWithCancel(input: InputStream, output: OutputStream, bufferSize: Int = 16 * 1024) {
        val buffer = ByteArray(bufferSize)
        var len: Int
        while (input.read(buffer).also { len = it } > 0) {
            ensureNotCancelled()
            output.write(buffer, 0, len)
        }
    }

    @Throws(IOException::class)
    fun prepare() = also {
        ensureNotCancelled()
        notifyStepChanged(ProgressStep.PREPARE)
        notifyStepProgress("Preparing workspace", buildPath)
        File(buildPath).mkdirs()
        mTempDir.mkdirs()
        notifyStepProgress("Loading template APK", templateApk.path)
        mFastZipBuilder = FastZipBuilder(templateApk)

        // 提取 META-INF/services/ 到 buildPath
        notifyStepProgress("Extracting META-INF/services", "for Rhino engine")
        extractMetaInfServices()

        ensureNotCancelled()
        notifyStepProgress("Prepare completed", buildPath)
    }

    @Throws(IOException::class)
    fun withConfig(config: ProjectConfig) = also {
        notifyStepChanged(ProgressStep.BUILD)
        notifyStepProgress("Processing", "Preparing build config")

        config.also { mProjectConfig = it }.run {
            ensureNotCancelled()
            notifyStepProgress("Reading splash resources", mResourcesArscFile.path)
            retrieveSplashThemeResources(launchConfig)

            ensureNotCancelled()
            notifyStepProgress("Configuring manifest", mManifestFile.path)
            prepareManifestConfiguration(this)

            ensureNotCancelled()
            notifyStepProgress("Configuring package name", packageName)
            setArscPackageName(packageName)

            ensureNotCancelled()
            notifyStepProgress("Processing", "Updating project config")
            updateProjectConfig(this)

            ensureNotCancelled()
            notifyStepProgress("Processing", "Preparing assets")
            prepareAssetsByPolicy()

            ensureNotCancelled()
            notifyStepProgress("Preparing native libraries", "")
            prepareLibrariesByConfig(this)

            ensureNotCancelled()
            notifyStepProgress("Preparing assets", "from source")
            prepareAssetsFromSource()

            ensureNotCancelled()
            notifyStepProgress("Processing source", sourcePath)
            setScriptFile(sourcePath)

            notifyStepProgress("Processing", "Applying binary resources")
        }
    }

    @Throws(Exception::class)
    fun build() = also {
        ensureNotCancelled()
        notifyStepProgress("Processing", "Building resources")

        // 提取 AndroidManifest.xml 和 resources.arsc 到临时目录
        val manifestData = extractEntryFromTemplate("AndroidManifest.xml")
            ?: throw IOException("AndroidManifest.xml not found in template")
        mManifestFile.parentFile?.mkdirs()
        mManifestFile.writeBytes(manifestData)

        val arscData = extractEntryFromTemplate("resources.arsc")
            ?: throw IOException("resources.arsc not found in template")
        mResourcesArscFile.parentFile?.mkdirs()
        mResourcesArscFile.writeBytes(arscData)

        val iconGetter: java.util.concurrent.Callable<java.awt.image.BufferedImage>? = mProjectConfig.iconBitmapGetter
        iconGetter?.let { callable ->
            runCatching {
                ensureNotCancelled()
                val tableBlock = TableBlock.load(mResourcesArscFile)
                val packageName = "${TEMPLATE_PACKAGE_NAME}.inrt"
                val packageBlock = tableBlock.getOrCreatePackage(0x7f, packageName).also {
                    tableBlock.currentPackage = it
                }
                val appIcon = packageBlock.getOrCreate("", ICON_RES_DIR, ICON_NAME)
                val appIconPath = appIcon.resValue.decodeValue()
                println("[INFO] Icon path: $appIconPath")

                notifyStepProgress("Writing app icon", appIconPath)
                val image: java.awt.image.BufferedImage = callable.call()
                val baos = ByteArrayOutputStream()
                ImageIO.write(image, "PNG", baos)
                mFastZipBuilder.putEntry(appIconPath, baos.toByteArray())
            }.onFailure { throw RuntimeException(it) }
        }

        mManifestEditor?.let {
            ensureNotCancelled()
            notifyStepProgress("Writing manifest", "AndroidManifest.xml")
            it.commit()
            val baos = ByteArrayOutputStream()
            it.writeTo(baos)
            mFastZipBuilder.putEntry("AndroidManifest.xml", baos.toByteArray())
        }

        mArscPackageName?.let {
            ensureNotCancelled()
            notifyStepProgress("Writing resources.arsc", "resources.arsc")
            buildArsc()
            mFastZipBuilder.putEntry("resources.arsc", mResourcesArscFile.readBytes())
        }
        notifyStepProgress("Processing", "Build completed")
    }

    @Throws(Exception::class)
    fun sign() = also {
        ensureNotCancelled()
        notifyStepChanged(ProgressStep.SIGN)

        outApkFile.parentFile?.let { if (!it.exists()) it.mkdirs() }
        val workspaceDir = File(buildPath)
        val tmpOutputApk = File(outApkFile.parentFile ?: workspaceDir, "${outApkFile.name}.unsigned.tmp")

        if (tmpOutputApk.exists()) tmpOutputApk.delete()

        notifyStepProgress("Creating unsigned APK", tmpOutputApk.path)

        try {
            // 使用 FastZipBuilder 快速构建
            notifyStepProgress("Building with 7zip native", "fast mode")
            buildUnsignedApkWithFastZip(tmpOutputApk)
        } catch (e: Exception) {
            if (tmpOutputApk.exists() && !tmpOutputApk.delete()) println("Failed to delete temp file")
            if (e.hasNoSpaceLeft()) throw IOException(
                "No space left on device while creating unsigned APK: ${tmpOutputApk.path}",
                e
            )
            throw e
        }

        val defaultKeyStoreFile = File(buildPath, "default_key_store.bks")
        ensureNotCancelled()
        defaultKeyStoreFile.parentFile?.let { if (!it.exists()) it.mkdirs() }
        notifyStepProgress("Preparing keystore", defaultKeyStoreFile.path)
        copyDefaultKeystore(defaultKeyStoreFile)

        ensureNotCancelled()
        val signedApkFile = File(outApkFile.parentFile ?: workspaceDir, "${outApkFile.name}.signed.tmp")
        if (signedApkFile.exists()) signedApkFile.delete()

        val signer = ApkSigner(tmpOutputApk, signedApkFile).apply {
            useDefaultSignatureVersion = false
            v1SigningEnabled = "V1" in mProjectConfig.signatureScheme
            v2SigningEnabled = "V2" in mProjectConfig.signatureScheme
            v3SigningEnabled = "V3" in mProjectConfig.signatureScheme
            v4SigningEnabled = "V4" in mProjectConfig.signatureScheme
        }

        val keyStoreFile = mCustomKeystoreFile ?: defaultKeyStoreFile
        val password = mKeystorePassword ?: "AutoJs6"
        val alias = mKeyAlias ?: "AutoJs6"
        val aliasPassword = mKeyPassword ?: password

        if (mCustomKeystoreFile != null) {
            notifyStepProgress("Using custom keystore", keyStoreFile.path)
        } else {
            notifyStepProgress("Using keystore", keyStoreFile.path)
        }

        try {
            if (!signer.signRelease(keyStoreFile, password, alias, aliasPassword)) {
                throw RuntimeException("Failed to re-sign using ApkSigner")
            }
        } catch (e: Exception) {
            if (signedApkFile.exists() && !signedApkFile.delete()) println("Failed to delete temp file after sign failure")
            if (e.hasNoSpaceLeft()) throw IOException(
                "No space left on device while re-signing APK: ${signedApkFile.path}",
                e
            )
            throw e
        } finally {
            if (tmpOutputApk.exists() && !tmpOutputApk.delete()) println("Failed to delete unsigned temp file")
        }

        try {
            ensureNotCancelled()
            notifyStepProgress("Writing signed APK", outApkFile.path)
            if (outApkFile.exists() && !outApkFile.delete()) {
                throw IOException("Failed to delete old apk before replace: ${outApkFile.path}")
            }
            if (!signedApkFile.renameTo(outApkFile)) {
                copyFileWithLargeBuffer(signedApkFile, outApkFile)
            }
        } catch (e: Exception) {
            if (e.hasNoSpaceLeft()) throw IOException(
                "No space left on device while writing signed APK: ${outApkFile.path}",
                e
            )
            throw RuntimeException(e)
        } finally {
            if (signedApkFile.exists() && !signedApkFile.delete()) println("Failed to delete temporary signed apk")
        }
        notifyStepProgress("Sign completed", outApkFile.path)
    }

    fun commitProjectConfigIfNeeded() = also {
        val pendingFile = mPendingProjectConfigFile
        val pendingJson = mPendingProjectConfigJson
        if (pendingFile == null || pendingJson == null) return@also

        ensureNotCancelled()
        notifyStepProgress("Processing", "Sign stage completed - writing project config")
        pendingFile.writeText(pendingJson)
        mPendingProjectConfigFile = null
        mPendingProjectConfigJson = null
    }

    fun cleanWorkspace() = also {
        notifyStepChanged(ProgressStep.CLEAN)
        val workspace = File(buildPath)
        if (!workspace.exists()) return@also
        val totalTargets = countDeleteTargets(workspace).coerceAtLeast(1)
        val deletedTargets = intArrayOf(0)
        notifyStepProgress("Cleaning workspace", workspace.path)
        deleteWithProgress(workspace, totalTargets, deletedTargets)
        notifyStepProgress("Clean completed", workspace.path)
    }

    fun finish() = also { mProgressCallback?.onFinished(this) }

    @Throws(IOException::class)
    fun setArscPackageName(packageName: String?) = also { mArscPackageName = packageName }

    // ========== Asset Path Utilities ==========

    private fun isAssetDirExcluded(assetPath: String): Boolean {
        val normalized = normalizeAssetPath(assetPath)
        return mAssetsDirExcludes.any { excluded ->
            normalized == excluded || normalized.startsWith("$excluded/")
        }
    }

    private fun includeLibraryContributions(lib: Lib) {
        mLibsIncludes += lib.libsToInclude.toSet()
        mAssetsFileIncludes += lib.assetFilesToInclude.map(::normalizeAssetPath).toSet()
        mAssetsDirExcludes -= lib.assetDirsToInclude.map(::normalizeAssetPath).toSet()
    }

    // ========== Private Methods ==========

    private fun prepareManifestConfiguration(config: ProjectConfig) {
        val manifestData = extractEntryFromTemplate("AndroidManifest.xml")
            ?: throw IOException("AndroidManifest.xml not found in template")
        mManifestEditor = ManifestEditorWithAuthorities(ByteArrayInputStream(manifestData))
            .setAppName(config.name ?: "")
            .setVersionName(config.versionName ?: "")
            .setVersionCode(config.versionCode)
            .setPackageName(config.packageName ?: "")
    }

    private fun retrieveSplashThemeResources(launchConfig: LaunchConfig) {
        if (launchConfig.isSplashVisible) return
        try {
            val arscData = extractEntryFromTemplate("resources.arsc")
                ?: throw IOException("resources.arsc not found in template")
            val tempArscFile = File(mTempDir, "resources.arsc.temp")
            tempArscFile.parentFile?.mkdirs()
            tempArscFile.writeBytes(arscData)

            val tableBlock = TableBlock.load(tempArscFile)
            val packageName = "${TEMPLATE_PACKAGE_NAME}.inrt"
            val packageBlock = tableBlock.getOrCreatePackage(0x7f, packageName).also {
                tableBlock.currentPackage = it
            }
            packageBlock.getEntry("", "style", "AppTheme.Splash")?.let { mSplashThemeId = it.resourceId }
            packageBlock.getEntry("", "style", "AppTheme.SevereTransparent")?.let { mNoSplashThemeId = it.resourceId }

            tempArscFile.delete()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private var mSplashThemeId: Int = 0
    private var mNoSplashThemeId: Int = 0

    private fun writeBundledProjectConfigIfNeeded() {
        val json = mBundledProjectConfigJson ?: return
        println("[DEBUG] ========== Writing project.json ==========")
        println(json)
        println("[DEBUG] ===========================================")
        mFastZipBuilder.putEntry("assets/project/${ProjectConfig.CONFIG_FILE_NAME}", json.toByteArray())
    }

    private fun updateProjectConfig(config: ProjectConfig) {
        ensureNotCancelled()

        val projectConfig = run {
            val sourcePath = config.sourcePath
            if (sourcePath != null && BuildFiles.isDir(sourcePath)) {
                // 项目目录打包
                ProjectConfig.fromProjectDir(sourcePath)?.let { sourceProjectConfig ->
                    sourceProjectConfig
                        .setName(config.name)
                        .setPackageName(config.packageName)
                        .setVersionName(config.versionName)
                        .setVersionCode(config.versionCode)
                        .setAbis(ArrayList(config.abis))
                        .setLibs(ArrayList(config.libs))
                        .setPermissions(ArrayList(config.permissions))
                        .setSignatureScheme(config.signatureScheme)
                    sourceProjectConfig.launchConfig = config.launchConfig
                    val nextBuildInfo = BuildInfo.generate(sourceProjectConfig.buildInfo.buildNumber + 1)
                    sourceProjectConfig.buildInfo.setBuildId(nextBuildInfo.buildId)
                    sourceProjectConfig.buildInfo.setBuildNumber(nextBuildInfo.buildNumber)
                    sourceProjectConfig.buildInfo.setBuildTime(nextBuildInfo.buildTime)
                    mPendingProjectConfigFile = File(ProjectConfig.configFileOfDir(sourcePath))
                    mPendingProjectConfigJson = sourceProjectConfig.toJson(true)
                    return@run sourceProjectConfig
                }
            }
            // 单文件打包
            return@run ProjectConfig().also { newProjectConfig ->
                newProjectConfig
                    .setName(config.name)
                    .setPackageName(config.packageName)
                    .setVersionName(config.versionName)
                    .setVersionCode(config.versionCode)
                    .setAbis(ArrayList(config.abis))
                    .setLibs(ArrayList(config.libs))
                    .setPermissions(ArrayList(config.permissions))
                    .setSignatureScheme(config.signatureScheme)
                newProjectConfig.launchConfig = config.launchConfig
                newProjectConfig.setBuildInfo(BuildInfo.generate(newProjectConfig.versionCode.toLong()))
            }
        }

        mKey = MD5Utils.md5(projectConfig.run { packageName + versionName + mainScriptFileName })
        mInitVector = MD5Utils.md5(projectConfig.run { buildInfo.buildId + name }).take(16)
        println("[DEBUG] ========== Encryption Keys ==========")
        println("[DEBUG] packageName: ${projectConfig.packageName}")
        println("[DEBUG] versionName: ${projectConfig.versionName}")
        println("[DEBUG] mainScriptFileName: ${projectConfig.mainScriptFileName}")
        println("[DEBUG] buildInfo.buildId: ${projectConfig.buildInfo.buildId}")
        println("[DEBUG] name: ${projectConfig.name}")
        println("[DEBUG] Key source: ${projectConfig.packageName}${projectConfig.versionName}${projectConfig.mainScriptFileName}")
        println("[DEBUG] IV source: ${projectConfig.buildInfo.buildId}${projectConfig.name}")
        println("[DEBUG] Key (MD5): $mKey")
        println("[DEBUG] IV (MD5.take(16)): $mInitVector")
        println("[DEBUG] =========================================")
        mBundledProjectConfigJson = projectConfig.toJson(true)
        Lib.entries.forEach { entry ->
            if (config.libs.contains(entry.label)) {
                includeLibraryContributions(entry)
            }
        }
    }

    @Throws(IOException::class)
    private fun setScriptFile(path: String?) {
        ensureNotCancelled()
        path?.let {
            when {
                BuildFiles.isDir(it) -> {
                    notifyStepProgress("Copying project directory", it)
                    copyDirToZip(File(it), "assets/project/")
                    // 项目目录打包时也需要写入更新后的 project.json
                    writeBundledProjectConfigIfNeeded()
                }

                else -> {
                    notifyStepProgress("Copying script file", it)
                    replaceFileInZip(File(it), "assets/project/main.js")
                    // 单文件打包时写入生成的 project.json
                    writeBundledProjectConfigIfNeeded()
                }
            }
            notifyStepProgress("Source processing completed", it)
        }
    }

    @Throws(IOException::class)
    private fun copyDirToZip(srcFile: File, relativeDestPath: String) {
        ensureNotCancelled()
        notifyStepProgress("Copying directory", "${srcFile.path} -> $relativeDestPath")
        srcFile.listFiles()?.forEach { srcChildFile ->
            ensureNotCancelled()
            if (srcChildFile.isFile) {
                // 跳过 project.json，因为会在后面统一写入更新后的版本
                if (srcChildFile.name == ProjectConfig.CONFIG_FILE_NAME) {
                    return@forEach
                }
                val zipPath = "$relativeDestPath${srcChildFile.name}"
                if (srcChildFile.name.endsWith(JAVASCRIPT.extensionWithDot)) {
                    encryptToZip(srcChildFile, zipPath)
                } else {
                    mFastZipBuilder.putEntry(zipPath, srcChildFile.readBytes())
                }
            } else {
                if (!mProjectConfig.excludedDirs.map { it.name }.contains(srcChildFile.name)) {
                    copyDirToZip(srcChildFile, "$relativeDestPath${srcChildFile.name}/")
                }
            }
        }
    }

    @Throws(IOException::class)
    private fun encryptToZip(srcFile: File, zipPath: String) {
        ensureNotCancelled()
        notifyStepProgress("Encrypting script", "${srcFile.path} -> $zipPath")
        println("[DEBUG] Encrypting with key=${mKey?.take(16)}... iv=$mInitVector")
        val baos = ByteArrayOutputStream()
        writeHeader(baos, JavaScriptFileSource(srcFile).executionMode.toShort())
        AdvancedEncryptionStandard(mKey!!.toByteArray(), mInitVector!!)
            .encrypt(BuildFiles.readBytes(srcFile.path))
            .let { bytes -> baos.write(bytes) }
        mFastZipBuilder.putEntry(zipPath, baos.toByteArray())
        println("[DEBUG] Encrypted file size: ${baos.size()} bytes")
    }

    @Throws(IOException::class)
    private fun replaceFileInZip(srcFile: File, zipPath: String) = also {
        ensureNotCancelled()
        if (srcFile.name.endsWith(JAVASCRIPT.extensionWithDot)) {
            encryptToZip(srcFile, zipPath)
        } else {
            notifyStepProgress("Replacing file", "${srcFile.path} -> $zipPath")
            mFastZipBuilder.putEntry(zipPath, srcFile.readBytes())
        }
    }

    // ========== Assets Processing ==========

    private fun prepareAssetsByPolicy() {
        val optionalAssetFiles = Lib.entries
            .flatMap { it.assetFilesToInclude }
            .map(::normalizeAssetPath)
            .toSet()

        // 遍历 template.apk 中的 assets 目录
        val entriesToRemove = mutableListOf<String>()
        listEntriesFromTemplate().forEach { entryName ->
            if (!entryName.startsWith("assets/")) return@forEach

            val assetPath = entryName.removePrefix("assets/")
            if (assetPath.isEmpty() || entryName.endsWith("/")) return@forEach

            ensureNotCancelled()

            // 检查是否在排除的目录中
            val normalizedPath = normalizeAssetPath(assetPath)
            if (isAssetDirExcluded(normalizedPath.substringBefore('/'))) {
                notifyStepProgress("Pruning", entryName)
                entriesToRemove.add(entryName)
                return@forEach
            }

            // 检查是否是可选文件且未被包含
            if (optionalAssetFiles.contains(normalizedPath) && !mAssetsFileIncludes.contains(normalizedPath)) {
                notifyStepProgress("Pruning", entryName)
                entriesToRemove.add(entryName)
            }
        }

        entriesToRemove.forEach { mFastZipBuilder.removeEntry(it) }
    }

    private fun prepareAssetsFromSource() {
        ensureNotCancelled()

        // 从 mAssetSourceDir 复制 assets
        val assetSourceRoot = mAssetSourceDir?.let { File(it, "assets") }
        if (assetSourceRoot == null || !assetSourceRoot.exists()) return

        copyAssetsToZip(assetSourceRoot, "")
    }

    private fun copyAssetsToZip(sourceDir: File, relativePath: String) {
        ensureNotCancelled()

        sourceDir.listFiles()?.forEach { file ->
            ensureNotCancelled()
            val currentPath = if (relativePath.isEmpty()) file.name else "$relativePath/${file.name}"

            if (file.isDirectory) {
                if (!isAssetDirExcluded(currentPath)) {
                    notifyStepProgress("Copying assets dir", currentPath)
                    copyAssetsToZip(file, currentPath)
                }
            } else {
                if (!currentPath.contains('/') && !mAssetsFileIncludes.contains(normalizeAssetPath(currentPath))) {
                    return@forEach
                }
                val zipPath = "assets/$currentPath"
                notifyStepProgress("Copying asset", zipPath)
                mFastZipBuilder.putEntry(zipPath, file.readBytes())
            }
        }
    }

    // ========== Library Copying ==========

    private fun prepareLibrariesByConfig(config: ProjectConfig) {
        ensureNotCancelled()

        val potentialAbiAliasList = mapOf(
            "arm64-v8a" to "arm64",
            "armeabi-v7a" to "arm",
        )

        config.abis.forEach { abiCanonicalName ->
            ensureNotCancelled()
            notifyStepProgress("Preparing libraries for abi", abiCanonicalName)
            prepareLibrariesByAbi(abiCanonicalName, abiCanonicalName)
            potentialAbiAliasList[abiCanonicalName]?.let { abiAliasName ->
                prepareLibrariesByAbi(abiAliasName, abiCanonicalName)
            }
        }
    }

    private fun prepareLibrariesByAbi(abiSrcName: String, abiDestName: String) {
        ensureNotCancelled()

        val srcLibDir = mLibrarySourceDir

        if (srcLibDir == null || !srcLibDir.exists()) {
            println("[Warning] Library directory not set or not found. Use setLibrarySourceDirectory() to specify.")
            println("[Hint] Native libraries will not be copied. The resulting APK may not work correctly.")
            return
        }

        mLibsIncludes.distinct().forEach { libName ->
            ensureNotCancelled()
            runCatching {
                val srcFile = File(srcLibDir, "$abiSrcName/$libName")
                if (srcFile.exists()) {
                    val zipPath = "lib/$abiDestName/$libName"
                    notifyStepProgress("Copying library", "${srcFile.path} -> $zipPath")
                    mFastZipBuilder.putEntry(zipPath, srcFile.readBytes())
                }
            }.onFailure { it.printStackTrace() }
        }
    }

    // ========== Signing Helpers ==========

    private fun buildUnsignedApkWithFastZip(outputFile: File) {
        // 先添加 META-INF/services/ 到 FastZipBuilder
        val servicesDir = File(buildPath, "META-INF/services")
        if (servicesDir.exists() && servicesDir.isDirectory) {
            servicesDir.listFiles()?.forEach { file ->
                if (file.isFile) {
                    mFastZipBuilder.putEntry("META-INF/services/${file.name}", file.readBytes())
                }
            }
        }

        // 使用 FastZipBuilder 构建
        mFastZipBuilder.buildTo(outputFile) { progress ->
            notifyStepProgress("Building", progress)
        }
    }

    private fun extractMetaInfServices() {
        val servicesDir = File(buildPath, "META-INF/services")
        servicesDir.mkdirs()

        listEntriesFromTemplate().forEach { entryName ->
            if (entryName.startsWith("META-INF/services/") && !entryName.endsWith("/")) {
                val data = extractEntryFromTemplate(entryName)
                if (data != null) {
                    val outFile = File(buildPath, entryName)
                    outFile.parentFile?.mkdirs()
                    outFile.writeBytes(data)
                    notifyStepProgress("Extracted", entryName)
                }
            }
        }
    }

    // 从 template.apk 提取条目
    private fun extractEntryFromTemplate(entryName: String): ByteArray? {
        return java.util.zip.ZipFile(templateApk).use { zip ->
            val entry = zip.getEntry(entryName) ?: return@use null
            zip.getInputStream(entry).use { it.readBytes() }
        }
    }

    // 列出 template.apk 中的所有条目
    private fun listEntriesFromTemplate(): List<String> {
        return java.util.zip.ZipFile(templateApk).use { zip ->
            zip.entries().toList().map { it.name }
        }
    }

    private fun copyDefaultKeystore(destFile: File) {
        val defaultKeystoreResource = javaClass.getResourceAsStream("/keystore/default_key_store.bks")
            ?: return
        destFile.outputStream().use { output ->
            copyStreamWithCancel(defaultKeystoreResource, output)
            output.flush()
        }
    }

    @Throws(IOException::class)
    private fun buildArsc() {
        val oldArsc = mResourcesArscFile
        val newArsc = File(mTempDir, "resources.arsc.new")
        BufferedInputStream(FileInputStream(oldArsc), 256 * 1024).use { input ->
            BufferedOutputStream(FileOutputStream(newArsc, false), 256 * 1024).use { output ->
                val decoder = ARSCDecoder(input, null, false)
                decoder.CloneArsc(output, mArscPackageName, true)
                output.flush()
            }
        }
        oldArsc.delete()
        if (!newArsc.renameTo(oldArsc)) {
            copyFileWithLargeBuffer(newArsc, oldArsc)
            newArsc.delete()
        }
    }

    private fun copyFileWithLargeBuffer(source: File, target: File, bufferSize: Int = 256 * 1024) {
        FileInputStream(source).use { input ->
            FileOutputStream(target, false).use { output ->
                val buffer = ByteArray(bufferSize)
                var len: Int
                while (input.read(buffer).also { len = it } > 0) {
                    ensureNotCancelled()
                    output.write(buffer, 0, len)
                }
            }
        }
    }

    private fun Throwable.hasNoSpaceLeft(): Boolean {
        var current: Throwable? = this
        while (current != null) {
            val message = current.message.orEmpty()
            if (message.contains("ENOSPC", ignoreCase = true) || message.contains(
                    "No space left on device",
                    ignoreCase = true
                )
            ) {
                return true
            }
            current = current.cause
        }
        return false
    }

    private fun countDeleteTargets(file: File): Int {
        if (!file.exists()) return 0
        return if (file.isDirectory) {
            1 + (file.listFiles()?.sumOf(::countDeleteTargets) ?: 0)
        } else 1
    }

    private fun deleteWithProgress(file: File, totalTargets: Int, deletedTargets: IntArray) {
        ensureNotCancelled()
        if (file.isDirectory) {
            file.listFiles()?.forEach { child -> deleteWithProgress(child, totalTargets, deletedTargets) }
        }
        notifyStepProgress("Deleting", file.path)
        file.delete()
        deletedTargets[0] += 1
    }

    private fun notifyStepChanged(step: ProgressStep) {
        mProgressCallback?.let { callback ->
            when (step) {
                ProgressStep.PREPARE -> callback.onPrepare(this)
                ProgressStep.BUILD -> callback.onBuild(this)
                ProgressStep.SIGN -> callback.onSign(this)
                ProgressStep.CLEAN -> callback.onClean(this)
            }
        }
    }

    private fun notifyStepProgress(title: String, detail: String?) {
        mProgressCallback?.onStepProgress(this, title, detail?.takeIf { it.isNotBlank() })
    }

    enum class ProgressStep { PREPARE, BUILD, SIGN, CLEAN }

    interface ProgressCallback {
        fun onPrepare(builder: ApkBuilder)
        fun onBuild(builder: ApkBuilder)
        fun onSign(builder: ApkBuilder)
        fun onClean(builder: ApkBuilder)
        fun onStepProgress(builder: ApkBuilder, title: String, detail: String?)
        fun onFinished(builder: ApkBuilder)
    }

    // ========== Lib Enum Definition ==========

    @Suppress("SpellCheckingInspection")
    enum class Lib(
        @JvmField val label: String,
        @JvmField val aliases: List<String> = emptyList(),
        @JvmField val enumerable: Boolean = true,
        internal val libsToInclude: List<String> = emptyList(),
        internal val assetFilesToInclude: List<String> = emptyList(),
        internal val assetDirsToInclude: List<String> = emptyList(),
    ) {

        TERMINAL_EMULATOR(
            label = "Terminal Emulator",
            enumerable = false,
            libsToInclude = listOf(
                "libjackpal-androidterm5.so",
                "libjackpal-termexec2.so",
            ),
        ),

        OPENCV(
            label = "OpenCV",
            aliases = listOf("cv"),
            libsToInclude = listOf(
                "libc++_shared.so",
                "libopencv_java4.so",
            ),
        ),

        MLKIT_OCR(
            label = "MLKit OCR",
            aliases = listOf("mlkit", "mlkitocr", "mlkit-ocr", "mlkit_ocr"),
            libsToInclude = listOf(
                "libmlkit_google_ocr_pipeline.so",
            ),
            assetDirsToInclude = listOf(
                "assets/mlkit-google-ocr-models",
            ),
        ),

        RAPID_OCR(
            label = "Rapid OCR",
            aliases = listOf("rapid", "rapidocr", "rapid-ocr", "rapid_ocr"),
            libsToInclude = listOf(
                "libRapidOcr.so",
                "libonnxruntime.so",
            ),
            assetDirsToInclude = listOf(
                "assets/labels",
            ),
        ),

        OPENCC(
            label = "OpenCC",
            aliases = listOf("cc"),
            libsToInclude = listOf(
                "libChineseConverter.so",
            ),
            assetDirsToInclude = listOf(
                "assets/openccdata",
            ),
        ),

        PINYIN(
            label = "Pinyin",
            aliases = listOf("pin"),
            assetFilesToInclude = listOf(
                "assets/dict-chinese-words.db.gzip",
                "assets/dict-chinese-phrases.db.gzip",
                "assets/dict-chinese-chars.db.gzip",
                "assets/prob_emit.txt",
            ),
        ),

        MLKIT_BARCODE(
            label = "MLKit Barcode",
            aliases = listOf("barcode", "mlkit-barcode", "mlkit_barcode"),
            libsToInclude = listOf(
                "libbarhopper_v3.so",
            ),
            assetDirsToInclude = listOf(
                "assets/mlkit_barcode_models",
            ),
        ),

        MEDIA_INFO(
            label = "MediaInfo",
            aliases = listOf("mediainfo", "media-info", "media_info"),
            libsToInclude = listOf(
                "libmediainfo.so",
            ),
        ),

        IMAGE_QUANT(
            label = "Image Quantization",
            aliases = listOf("imagequant", "image-quant", "image-quantization", "image_quant", "image_quantization"),
            libsToInclude = listOf(
                "libpng.so",
                "libpng16d.so",
                "libpngquant_bridge.so",
            ),
        );

        companion object {

            val defaultLibsToInclude = listOf(
                TERMINAL_EMULATOR,
            ).flatMap { it.libsToInclude }

            val defaultAssetFilesToInclude = listOf(
                "init.js", "roboto_medium.ttf",
            ).map(::normalizeAssetPath)

            val defaultAssetDirsToExclude = listOf(
                "doc", "docs", "editor", "indices", "js-beautify", "sample", "stored-locales", "models",
            ).plus(entries.flatMap { it.assetDirsToInclude })
                .map(::normalizeAssetPath)
                .distinct()
        }
    }

    companion object {
        const val ICON_NAME = "ic_launcher"
        const val ICON_RES_DIR = "mipmap"
        const val TEMPLATE_PACKAGE_NAME = "org.autojs.autojs6"
        const val INRT_APP_ID = "org.autojs.autojs6.inrt"

        private fun normalizeAssetPath(path: String): String {
            var out = path.trim().replace('\\', '/')
            while (out.startsWith("/")) {
                out = out.removePrefix("/")
            }
            if (out.startsWith("assets/")) {
                out = out.removePrefix("assets/")
            }
            while (out.endsWith("/")) {
                out = out.removeSuffix("/")
            }
            return out
        }
    }

    private inner class ManifestEditorWithAuthorities(manifestInputStream: java.io.InputStream?) :
        ManifestEditor(manifestInputStream) {

        override fun shouldIgnoreComponentNode(nodeName: String?, componentClassName: String?): Boolean =
            when (componentClassName) {
                "android.app.shortcuts" -> nodeName == "meta-data"
                "org.autojs.autojs.external.open.EditIntentActivity",
                "org.autojs.autojs.external.open.RunIntentActivity",
                "org.autojs.autojs.external.open.ImportIntentActivity",
                "org.autojs.autojs.external.tile.LayoutBoundsTile",
                "org.autojs.autojs.external.tile.LayoutHierarchyTile" -> true

                else -> false
            }

        override fun onAttr(attr: AxmlWriter.Attr) {
            attr.apply {
                if (!mProjectConfig.launchConfig.isSplashVisible && mSplashThemeId != 0 && value == mSplashThemeId) {
                    value = mNoSplashThemeId
                }

                when {
                    name.data == "authorities" -> (value as? StringItem)?.apply {
                        data = data.replace(INRT_APP_ID, mProjectConfig.packageName ?: "")
                    }

                    else -> super.onAttr(this)
                }
            }
        }

        override fun isPermissionRequired(permissionName: String): Boolean {
            return mProjectConfig.permissions.contains(permissionName)
        }
    }
}
