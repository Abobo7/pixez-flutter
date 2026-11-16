package com.perol.pixez

import android.Manifest
import android.annotation.TargetApi
import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.DocumentsContract
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.lifecycleScope
import com.perol.pixez.kitkat.KitKatWindowInsetsBridge
import com.perol.pixez.plugin.CustomTab
import com.perol.pixez.plugin.DeepLinkPlugin
import com.perol.pixez.plugin.JsEvalPlugin
import com.perol.pixez.plugin.OpenSettinger
import com.perol.pixez.plugin.Safer
import com.perol.pixez.plugin.SecurePlugin
import com.perol.pixez.plugin.SupporterPlugin
import com.perol.pixez.plugin.Weiss
import com.perol.pixez.plugin.exist
import com.perol.pixez.plugin.save
import com.perol.pixez.plugin.writeBytesOrDelete
import com.waynejo.androidndkgif.GifEncoder
import io.flutter.Log
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Collections

class MainActivity : FlutterActivity() {
    private val CHANNEL = "com.perol.dev/save"
    private val ENCODE_CHANNEL = "samples.flutter.dev/battery"
    private val APP_WIDGET_CHANNEL = "com.perol.dev/app_widget"
    private var saveMode = 0
    private val OPEN_DOCUMENT_TREE_CODE = 190
    private val PICK_IMAGE_FILE = 2
    private val REQ_STORAGE_PERM_CODE = 7788
    private var pendingResult: MethodChannel.Result? = null
    private var pendingPickResult: MethodChannel.Result? = null
    private var pendingPermResult: MethodChannel.Result? = null
    private var helplessPath: String? = null
    private val SHARED_PREFERENCES_NAME = "FlutterSharedPreferences"
    private lateinit var sharedPreferences: SharedPreferences

    private fun normalizeSaveMode(rawMode: Int): Int {
        // ACTION_OPEN_DOCUMENT_TREE / DocumentsContract tree URIs require API 21+
        return if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP && rawMode == 1) 0 else rawMode
    }

    override fun shouldHandleDeeplinking(): Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            WindowCompat.setDecorFitsSystemWindows(window, false)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            splashScreen.setOnExitAnimationListener { splashScreenView -> splashScreenView.remove() }
        }
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            KitKatWindowInsetsBridge.install(this, flutterEngine)
        }
    }

    override fun onResume() {
        super.onResume()
        KitKatWindowInsetsBridge.onResume()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            KitKatWindowInsetsBridge.onResume()
        }
    }

    private val savingPools = Collections.synchronizedList(arrayListOf<String>())
    private val safFolderLock = Any()

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        flutterEngine.plugins.add(DeepLinkPlugin())
        sharedPreferences =
            this.getSharedPreferences(SHARED_PREFERENCES_NAME, Context.MODE_PRIVATE)
        helplessPath = sharedPreferences.getString("flutter.store_path", null)
        saveMode = normalizeSaveMode(sharedPreferences.getLong("flutter.save_mode", 0).toInt())
        OpenSettinger.bindChannel(flutterEngine, this)
        Weiss.bindChannel(flutterEngine)
        CustomTab.bindChannel(this, flutterEngine)
        Safer.bindChannel(this, flutterEngine)
        JsEvalPlugin(this).bindChannel(flutterEngine)
        SecurePlugin(this).bindChannel(flutterEngine)
        SupporterPlugin().bindChannel(this, flutterEngine)
        MethodChannel(
            flutterEngine.dartExecutor.binaryMessenger,
            CHANNEL
        ).setMethodCallHandler { call, result ->
            when (call.method) {
                "requestPermission" -> {
                    when {
                        Build.VERSION.SDK_INT >= 33 -> result.success(true)
                        Build.VERSION.SDK_INT < Build.VERSION_CODES.M -> result.success(true)
                        else -> {
                            val granted = ContextCompat.checkSelfPermission(
                                this,
                                Manifest.permission.WRITE_EXTERNAL_STORAGE
                            ) == PackageManager.PERMISSION_GRANTED
                            if (granted) {
                                result.success(true)
                            } else {
                                pendingPermResult = result
                                ActivityCompat.requestPermissions(
                                    this,
                                    arrayOf(
                                        Manifest.permission.WRITE_EXTERNAL_STORAGE,
                                        Manifest.permission.READ_EXTERNAL_STORAGE
                                    ),
                                    REQ_STORAGE_PERM_CODE
                                )
                            }
                        }
                    }
                }

                "permissionStatus" -> {
                    when {
                        Build.VERSION.SDK_INT >= 33 -> {
                            if (BuildConfig.IS_GOOGLEPLAY) {
                                result.success(true)
                                return@setMethodCallHandler
                            }
                            val checkSelfPermission = ContextCompat.checkSelfPermission(
                                this,
                                Manifest.permission.READ_MEDIA_IMAGES
                            )
                            result.success(checkSelfPermission == PackageManager.PERMISSION_GRANTED)
                        }
                        Build.VERSION.SDK_INT < Build.VERSION_CODES.M -> {
                            result.success(true)
                        }
                        else -> {
                            val checkSelfPermission = ContextCompat.checkSelfPermission(
                                this,
                                Manifest.permission.WRITE_EXTERNAL_STORAGE
                            )
                            result.success(checkSelfPermission == PackageManager.PERMISSION_GRANTED)
                        }
                    }
                }

                "save" -> {
                    val data = call.argument<ByteArray>("data")
                    val name = call.argument<String>("name")
                    if (data == null || name == null) {
                        result.error("INVALID_ARGS", "data and name are required", null)
                        return@setMethodCallHandler
                    }
                    val clearOld = call.argument<Boolean>("clear_old") ?: false
                    saveMode = normalizeSaveMode(call.argument<Int>("save_mode") ?: 0)
                    if (savingPools.contains(name)) return@setMethodCallHandler
                    savingPools.add(name)
                    lifecycleScope.launch {
                        try {
                            var saved = false
                            when (saveMode) {
                                0 -> {
                                    val path = withContext(Dispatchers.IO) { save(data, name) }
                                    if (path != null) {
                                        saved = true
                                        scanMediaFile(path)
                                    }
                                }

                                2 -> {
                                    if (helplessPath.isNullOrEmpty()) {
                                        helplessPath = sharedPreferences.getString("flutter.store_path", null)
                                        if (helplessPath.isNullOrEmpty()) {
                                            helplessPath = File(
                                                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                                                "pixez"
                                            ).absolutePath
                                        }
                                    }
                                    val file = File("$helplessPath/$name")
                                    withContext(Dispatchers.IO) {
                                        file.parentFile?.let { if (!it.exists()) it.mkdirs() }
                                        if (!file.exists()) file.createNewFile()
                                        file.writeBytesOrDelete(data)
                                        if (clearOld && name.contains("_p0")) {
                                            val oldFile = File("$helplessPath", name.replace("_p0", ""))
                                            if (oldFile.exists()) oldFile.delete()
                                        }
                                    }
                                    scanMediaFile(file.path)
                                    saved = true
                                }

                                1 -> {
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                                        withContext(Dispatchers.IO) {
                                            saved = SafTreeHelper.saveBytes(this@MainActivity, safFolderLock, name, clearOld, data) {
                                                runOnUiThread { choiceFolder() }
                                            }
                                        }
                                    }
                                }
                            }
                            result.success(saved)
                        } catch (e: Throwable) {
                            Log.d("Save", "${e.message}")
                            result.success(false)
                        } finally {
                            savingPools.remove(name)
                        }
                    }
                }

                "saveFromPath" -> {
                    val sourcePath = call.argument<String>("source_path")
                    val name = call.argument<String>("name")
                    if (sourcePath == null || name == null) {
                        result.error("INVALID_ARGS", "source_path and name are required", null)
                        return@setMethodCallHandler
                    }
                    val clearOld = call.argument<Boolean>("clear_old") ?: false
                    saveMode = normalizeSaveMode(call.argument<Int>("save_mode") ?: 0)
                    if (savingPools.contains(name)) return@setMethodCallHandler
                    savingPools.add(name)
                    lifecycleScope.launch {
                        try {
                            val sourceFile = File(sourcePath)
                            var saved = false
                            when (saveMode) {
                                0 -> {
                                    val data = withContext(Dispatchers.IO) { sourceFile.readBytes() }
                                    val path = save(data, name)
                                    if (path != null) {
                                        saved = true
                                        scanMediaFile(path)
                                    }
                                }

                                2 -> {
                                    if (helplessPath.isNullOrEmpty()) {
                                        helplessPath = File(
                                            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                                            "pixez"
                                        ).absolutePath
                                    }
                                    val target = File("$helplessPath/$name")
                                    withContext(Dispatchers.IO) {
                                        target.parentFile?.let { if (!it.exists()) it.mkdirs() }
                                        try {
                                            sourceFile.copyTo(target, overwrite = true)
                                        } catch (e: Throwable) {
                                            target.delete()
                                            throw e
                                        }
                                    }
                                    saved = true
                                    scanMediaFile(target.path)
                                }

                                1 -> {
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                                        withContext(Dispatchers.IO) {
                                            val data = sourceFile.readBytes()
                                            saved = SafTreeHelper.saveBytes(this@MainActivity, safFolderLock, name, clearOld, data) {
                                                runOnUiThread { choiceFolder() }
                                            }
                                        }
                                    }
                                }
                            }
                            sourceFile.delete()
                            result.success(saved)
                        } catch (e: Throwable) {
                            Log.d("SaveFromPath", "${e.message}")
                            result.success(false)
                        } finally {
                            savingPools.remove(name)
                        }
                    }
                }

                "get_path" -> {
                    saveMode = normalizeSaveMode(call.argument<Int>("save_mode") ?: 0)
                    lifecycleScope.launch {
                        val path = withContext(Dispatchers.IO) { getPath() }
                        result.success(path)
                    }
                }

                "exist" -> {
                    val name = call.argument<String>("name")!!
                    saveMode = normalizeSaveMode(call.argument<Int>("save_mode") ?: 0)
                    lifecycleScope.launch {
                        val exists = withContext(Dispatchers.IO) {
                            runCatching { isFileExist(name) }.getOrDefault(false)
                        }
                        result.success(exists)
                    }
                }

                "choice_folder" -> {
                    saveMode = normalizeSaveMode(call.argument<Int>("save_mode") ?: 0)
                    pendingPickResult = result
                    choiceFolder()
                }
            }
        }
        MethodChannel(
            flutterEngine.dartExecutor.binaryMessenger,
            ENCODE_CHANNEL
        ).setMethodCallHandler { call, result ->
            if (call.method == "getBatteryLevel") {
                val path = call.argument<String>("path")!!
                val delay = call.argument<Int>("delay")!!
                val delayArray = call.argument<List<Int>>("delay_array")!!
                lifecycleScope.launch {
                    val gifPath = withContext(Dispatchers.IO) {
                        encodeGif(path, delay, delayArray)
                    }
                    if (gifPath != null) {
                        result.success(gifPath)
                    } else {
                        result.error("ENCODE_FAILED", "GIF encoding failed", null)
                    }
                }
            }
        }
        MethodChannel(
            flutterEngine.dartExecutor.binaryMessenger,
            APP_WIDGET_CHANNEL
        ).setMethodCallHandler { call, result ->
            when (call.method) {
                "setRecommendType" -> {
                    val type = call.argument<String>("type") ?: "recom"
                    sharedPreferences.edit().putString("flutter.widget_illust_type", type).apply()
                    refreshAppWidgets()
                    result.success(null)
                }

                else -> result.notImplemented()
            }
        }
    }

    private fun scanMediaFile(path: String) {
        MediaScannerConnection.scanFile(
            this,
            arrayOf(path),
            arrayOf(MimeTypeMap.getSingleton().getMimeTypeFromExtension(File(path).extension))
        ) { _, _ -> }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_STORAGE_PERM_CODE) {
            val ok = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
            pendingPermResult?.success(ok)
            pendingPermResult = null
        }
    }

    private fun refreshAppWidgets() {
        val appWidgetManager = AppWidgetManager.getInstance(this)
        listOf(SquareAppWidget::class.java, IllustCardAppWidget::class.java).forEach { widgetClass ->
            val componentName = ComponentName(this, widgetClass)
            val widgetIds = appWidgetManager.getAppWidgetIds(componentName)
            if (widgetIds.isNotEmpty()) {
                sendBroadcast(Intent(AppWidgetManager.ACTION_APPWIDGET_UPDATE).apply {
                    component = componentName
                    putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, widgetIds)
                })
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        Safer.bindResult(this, requestCode, resultCode, data)
        when (requestCode) {
            PICK_IMAGE_FILE -> if (resultCode == Activity.RESULT_OK) {
                data?.data?.also { uri ->
                    applicationContext.contentResolver.openInputStream(uri)?.use {
                        pendingPickResult?.success(it.readBytes())
                        pendingPickResult = null
                    }
                }
            } else {
                pendingResult?.success(null)
                pendingResult = null
            }

            OPEN_DOCUMENT_TREE_CODE -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    SafTreeHelper.handleTreeResult(
                        this,
                        resultCode,
                        data,
                        onRetry = { choiceFolder(needHint = false) },
                        onFinish = { ok ->
                            pendingPickResult?.success(ok)
                            pendingPickResult = null
                        }
                    )
                } else {
                    pendingPickResult?.success(true)
                    pendingPickResult = null
                }
            }
        }
    }

    private fun getPath(): String? {
        if (saveMode == 0) return "Pictures/PixEz"
        if (saveMode == 2) {
            helplessPath = sharedPreferences.getString("flutter.store_path", "")
            return helplessPath
        }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            SafTreeHelper.getPersistedPath(this)
        } else {
            "Pictures/PixEz"
        }
    }

    private fun encodeGif(path: String, delay: Int, delayArray: List<Int>): String? {
        val file = File(path)
        val tempFile = File.createTempFile("encode_", ".gif", applicationContext.cacheDir)
        try {
            val listFiles = file.listFiles()
            if (listFiles.isNullOrEmpty()) throw RuntimeException("unzip files not found")
            val arrayFile = mutableListOf<File>()
            for (i in listFiles) {
                val ext = i.extension.lowercase()
                if (ext == "jpg" || ext == "jpeg" || ext == "png") arrayFile.add(i)
            }
            arrayFile.sortWith { o1, o2 -> o1.name.compareTo(o2.name) }
            val bitmap: Bitmap = BitmapFactory.decodeFile(arrayFile.first().path)
            val encoder = GifEncoder()
            encoder.init(
                bitmap.width,
                bitmap.height,
                tempFile.path,
                GifEncoder.EncodingType.ENCODING_TYPE_STABLE_HIGH_MEMORY
            )
            for (i in arrayFile.indices) {
                val trueDelay = if (i < delayArray.size) delayArray[i] else delay
                if (i != 0) {
                    val bmp = BitmapFactory.decodeFile(arrayFile[i].path)
                    encoder.encodeFrame(bmp, trueDelay)
                    bmp.recycle()
                } else {
                    encoder.encodeFrame(bitmap, trueDelay)
                }
            }
            encoder.close()
            bitmap.recycle()
            return tempFile.absolutePath
        } catch (e: Throwable) {
            e.printStackTrace()
            tempFile.delete()
            return null
        }
    }

    private fun choiceFolder(needHint: Boolean = true) {
        if (saveMode == 2 || saveMode == 0 || Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            pendingPickResult?.success(true)
            pendingPickResult = null
            return
        }
        SafTreeHelper.launchDocumentTree(this, OPEN_DOCUMENT_TREE_CODE, needHint)
    }

    private fun isFileExist(name: String): Boolean {
        return when (saveMode) {
            0 -> exist(name)
            2 -> File("$helplessPath/$name").exists()
            else -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    SafTreeHelper.isFileExist(this, name)
                } else {
                    exist(name)
                }
            }
        }
    }
}

@TargetApi(Build.VERSION_CODES.LOLLIPOP)
private object SafTreeHelper {
    private fun splicingUrl(parentUri: String, fileName: String) =
        if (parentUri.endsWith(":")) parentUri + fileName else "$parentUri/$fileName"

    fun launchDocumentTree(activity: Activity, reqCode: Int, needHint: Boolean) {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
        }
        if (needHint) {
            Toast.makeText(
                activity,
                activity.getString(R.string.choose_a_suitable_image_storage_directory),
                Toast.LENGTH_SHORT
            ).show()
        }
        activity.startActivityForResult(intent, reqCode)
    }

    fun handleTreeResult(
        activity: Activity,
        resultCode: Int,
        data: Intent?,
        onRetry: () -> Unit,
        onFinish: (Boolean) -> Unit
    ) {
        if (resultCode == Activity.RESULT_OK) {
            data?.data?.also { uri ->
                if (uri.toString().lowercase().contains("download")) {
                    Toast.makeText(
                        activity.applicationContext,
                        activity.getString(R.string.do_not_choice_download_folder_message),
                        Toast.LENGTH_LONG
                    ).show()
                    onRetry()
                    return
                }
                val contentResolver = activity.applicationContext.contentResolver
                val takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                contentResolver.takePersistableUriPermission(uri, takeFlags)
                for (i in contentResolver.persistedUriPermissions) {
                    if (i.isReadPermission && i.isWritePermission && i.uri != uri) {
                        contentResolver.releasePersistableUriPermission(i.uri, takeFlags)
                    }
                }
                onFinish(true)
            }
        } else {
            Toast.makeText(
                activity.applicationContext,
                activity.getString(R.string.failure_to_obtain_authorization_may_cause_some_functions_to_fail_or_crash),
                Toast.LENGTH_SHORT
            ).show()
            onFinish(false)
        }
    }

    fun getPersistedPath(context: Context): String? {
        val list = context.contentResolver.persistedUriPermissions
            .takeWhile { it.isReadPermission && it.isWritePermission }
        return list.firstOrNull()?.uri?.toString()
    }

    fun isFileExist(context: Context, name: String): Boolean {
        val perm = context.contentResolver.persistedUriPermissions
            .takeWhile { it.isReadPermission && it.isWritePermission }
            .firstOrNull() ?: return false
        val treeDocument = DocumentFile.fromTreeUri(context, perm.uri) ?: return false
        val treeId = DocumentsContract.getTreeDocumentId(treeDocument.uri)
        if (name.contains("/")) {
            val names = name.split("/")
            if (names.size >= 2) {
                val folderName = names.first()
                val fName = names.last()
                val dirId = splicingUrl(treeId, folderName)
                val dirUri = DocumentsContract.buildDocumentUriUsingTree(treeDocument.uri, dirId)
                val dirDocument = DocumentFile.fromSingleUri(context, dirUri)
                return if (dirDocument == null || !dirDocument.exists()) {
                    false
                } else if (dirDocument.isFile) {
                    dirDocument.delete()
                    false
                } else {
                    val fileId = splicingUrl(dirId, fName)
                    val fileUri = DocumentsContract.buildDocumentUriUsingTree(treeDocument.uri, fileId)
                    val targetFile = DocumentFile.fromSingleUri(context, fileUri)
                    targetFile != null && targetFile.exists()
                }
            }
            return false
        }
        val fileId = splicingUrl(treeId, name)
        val fileUri = DocumentsContract.buildDocumentUriUsingTree(treeDocument.uri, fileId)
        val targetFile = DocumentFile.fromSingleUri(context, fileUri)
        return targetFile != null && targetFile.exists()
    }

    fun saveBytes(
        context: Context,
        safFolderLock: Any,
        fileName: String,
        clearOld: Boolean,
        data: ByteArray,
        onNeedFolder: () -> Unit
    ): Boolean {
        val uri = writeFileUri(context, safFolderLock, fileName, clearOld, onNeedFolder) ?: return false
        try {
            context.contentResolver.openOutputStream(uri, "w")?.use { it.write(data) }
            return true
        } catch (e: Throwable) {
            runCatching { DocumentsContract.deleteDocument(context.contentResolver, uri) }
            throw e
        }
    }

    private fun writeFileUri(
        context: Context,
        safFolderLock: Any,
        fileName: String,
        clearOld: Boolean,
        onNeedFolder: () -> Unit
    ): Uri? {
        val mimeType = when {
            fileName.endsWith("jpg", ignoreCase = true) || fileName.endsWith("jpeg", ignoreCase = true) -> "image/jpg"
            fileName.endsWith("png", ignoreCase = true) -> "image/png"
            else -> "image/gif"
        }
        val permissions = context.contentResolver.persistedUriPermissions
            .takeWhile { it.isReadPermission && it.isWritePermission }
        if (permissions.isEmpty()) {
            onNeedFolder()
            return null
        }
        val treeDocument = DocumentFile.fromTreeUri(context, permissions.first().uri) ?: return null
        val treeId = DocumentsContract.getTreeDocumentId(treeDocument.uri)
        if (fileName.contains("/")) {
            val names = fileName.split("/")
            if (names.size >= 2) {
                return try {
                    var folderDocument: DocumentFile? = treeDocument
                    val fName = names.last()
                    val list = names.subList(0, names.size - 1)
                    synchronized(safFolderLock) {
                        for (part in list) {
                            folderDocument = folderDocument!!.findFile(part)
                                ?: folderDocument!!.createDirectory(part)!!
                        }
                    }
                    folderDocument?.createFile(mimeType, fName)?.uri
                } catch (_: Throwable) {
                    null
                }
            }
        }
        if (clearOld && fileName.contains("_p0")) {
            treeDocument.findFile(fileName.replace("_p0", ""))?.delete()
        }
        val fileId = splicingUrl(treeId, fileName)
        val fileUri = DocumentsContract.buildDocumentUriUsingTree(treeDocument.uri, fileId)
        val targetFile = DocumentFile.fromSingleUri(context, fileUri)
        if (targetFile != null && targetFile.exists()) targetFile.delete()
        return treeDocument.createFile(mimeType, fileName)?.uri
    }
}
