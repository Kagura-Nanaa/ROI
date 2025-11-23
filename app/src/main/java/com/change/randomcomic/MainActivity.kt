package com.change.randomcomic

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.StrictMode
import android.provider.Settings
import android.text.InputType
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.color.DynamicColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import java.io.File
import java.net.URLDecoder

class MainActivity : AppCompatActivity() {

    private lateinit var tvLog: TextView
    private lateinit var chipGroupPaths: ChipGroup

    // SharedPreferences Keys
    private val PREFS_NAME = "ComicPrefs"
    private val KEY_LAST_PATH = "last_path"
    private val KEY_IMG_PKG = "img_pkg"
    private val KEY_VID_PKG = "vid_pkg"
    private val KEY_HISTORY_PATHS = "history_paths"
    private val KEY_ALIAS_PREFIX = "alias_"
    private val KEY_EXCLUDED_PREFIX = "excluded_"
    private val KEY_COOLDOWN_ENABLED = "cooldown_enabled"
    private val KEY_COOLDOWN_MINUTES = "cooldown_minutes"
    private val KEY_LAST_OPEN_PREFIX = "last_open_"
    // [新增] 默认开启悬浮窗
    private val KEY_DEFAULT_FLOAT_ENABLED = "default_float_enabled"

    // Default Settings
    private var targetImgPackage = "com.rookiestudio.perfectviewer"
    private var targetVidPackage = "com.mxtech.videoplayer.ad"
    private var isCooldownEnabled = false
    private var cooldownMinutes = 60
    private var isDefaultFloatEnabled = false

    // State
    private var currentSelectedPath: String? = null
    private var pathBeingModified: String? = null

    private val selectFolderLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri == null) {
            pathBeingModified = null
            return@registerForActivityResult
        }

        try {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        } catch (e: Exception) { e.printStackTrace() }

        val newPath = convertUriToFilePath(uri)

        val oldPath = pathBeingModified
        if (oldPath != null) {
            replacePathInHistory(oldPath, newPath)
            pathBeingModified = null
        } else {
            if (isPathInHistory(newPath)) {
                Toast.makeText(this, "该路径已存在", Toast.LENGTH_SHORT).show()
                refreshPathChips(autoSelectPath = newPath)
            } else {
                showRenameDialog(newPath, isNew = true)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DynamicColors.applyToActivityIfAvailable(this)
        setContentView(R.layout.activity_main)

        val builder = StrictMode.VmPolicy.Builder()
        StrictMode.setVmPolicy(builder.build())
        builder.detectFileUriExposure()

        initViews()
        loadPreferences()

        val lastPath = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_LAST_PATH, null)
        refreshPathChips(autoSelectPath = lastPath)

        // [新增] 如果设置了默认开启且已授予权限，则启动悬浮窗
        if (isDefaultFloatEnabled && Settings.canDrawOverlays(this) && !isServiceRunning(FloatingService::class.java)) {
            startService(Intent(this, FloatingService::class.java))
        }
    }

    private fun initViews() {
        tvLog = findViewById(R.id.tvLog)
        chipGroupPaths = findViewById(R.id.chipGroupPaths)
        val btnSettings: ImageButton = findViewById(R.id.btnSettings)
        val btnOpen: Button = findViewById(R.id.btnRandomOpen)

        btnSettings.setOnClickListener {
            showSettingsDialog()
        }

        btnOpen.setOnClickListener {
            triggerRandomSelection()
        }
    }

    private fun triggerRandomSelection() {
        val path = currentSelectedPath
        if (path == null) {
            log("请先选择或添加一个文件夹 (点击 + 号)")
            if (chipGroupPaths.childCount <= 1) {
                pathBeingModified = null
                selectFolderLauncher.launch(null)
            }
            return
        }

        if (path.startsWith("content://")) {
            performRandomOpenSaf(Uri.parse(path))
        } else {
            if (checkAndRequestPermissions()) {
                performRandomOpenFile(path)
            }
        }
    }

    private fun loadPreferences() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        targetImgPackage = prefs.getString(KEY_IMG_PKG, targetImgPackage) ?: targetImgPackage
        targetVidPackage = prefs.getString(KEY_VID_PKG, targetVidPackage) ?: targetVidPackage
        isCooldownEnabled = prefs.getBoolean(KEY_COOLDOWN_ENABLED, false)
        cooldownMinutes = prefs.getInt(KEY_COOLDOWN_MINUTES, 60)
        // [新增] 加载默认开启设置
        isDefaultFloatEnabled = prefs.getBoolean(KEY_DEFAULT_FLOAT_ENABLED, false)
    }

    // --- Chip Logic ---
    private fun refreshPathChips(autoSelectPath: String? = null) {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val historySet = prefs.getStringSet(KEY_HISTORY_PATHS, mutableSetOf()) ?: mutableSetOf()
        val sortedList = historySet.sorted()

        chipGroupPaths.removeAllViews()

        for (path in sortedList) {
            val chip = Chip(this)
            chip.isCheckable = true
            chip.isClickable = true

            val alias = getPathAlias(path)
            chip.text = if (!alias.isNullOrEmpty()) alias else (if (path.contains("/")) path.substringAfterLast("/") else path)
            chip.tag = path

            chip.setOnCheckedChangeListener { _, isChecked ->
                if (isChecked) {
                    currentSelectedPath = path
                    saveLastPath(path)
                    log("已选中: ${chip.text}")
                } else {
                    if (currentSelectedPath == path) {
                        currentSelectedPath = null
                    }
                }
            }

            chip.setOnLongClickListener {
                showChipOptionsDialog(path, chip.text.toString())
                true
            }

            chipGroupPaths.addView(chip)

            if (path == autoSelectPath) {
                chip.isChecked = true
                currentSelectedPath = path
            }
        }

        val plusChip = Chip(this)
        plusChip.text = "+"
        plusChip.textAlignment = TextView.TEXT_ALIGNMENT_CENTER
        plusChip.isCheckable = false
        plusChip.setOnClickListener {
            pathBeingModified = null
            selectFolderLauncher.launch(null)
        }
        chipGroupPaths.addView(plusChip)
    }

    // --- Storage Helpers ---
    private fun savePathAlias(path: String, alias: String) {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_ALIAS_PREFIX + path, alias).apply()
    }

    private fun getPathAlias(path: String): String? {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_ALIAS_PREFIX + path, null)
    }

    private fun removePathAlias(path: String) {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().remove(KEY_ALIAS_PREFIX + path).apply()
    }

    private fun getExcludedFolders(parentPath: String): Set<String> {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getStringSet(KEY_EXCLUDED_PREFIX + parentPath, emptySet()) ?: emptySet()
    }

    private fun saveExcludedFolders(parentPath: String, excludedSet: Set<String>) {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putStringSet(KEY_EXCLUDED_PREFIX + parentPath, excludedSet).apply()
    }

    private fun removeExcludedFolders(parentPath: String) {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().remove(KEY_EXCLUDED_PREFIX + parentPath).apply()
    }

    private fun saveFolderOpenTime(folderPath: String) {
        if (!isCooldownEnabled) return
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putLong(KEY_LAST_OPEN_PREFIX + folderPath, System.currentTimeMillis()).apply()
    }

    private fun isFolderOnCooldown(folderPath: String): Boolean {
        if (!isCooldownEnabled) return false

        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val lastTime = prefs.getLong(KEY_LAST_OPEN_PREFIX + folderPath, 0L)
        if (lastTime == 0L) return false

        val currentTime = System.currentTimeMillis()
        val cooldownMillis = cooldownMinutes * 60 * 1000L

        return (currentTime - lastTime) < cooldownMillis
    }

    private fun isPathInHistory(path: String): Boolean {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val historySet = prefs.getStringSet(KEY_HISTORY_PATHS, emptySet())
        return historySet?.contains(path) == true
    }

    private fun addPathToHistory(path: String) {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val historySet = prefs.getStringSet(KEY_HISTORY_PATHS, mutableSetOf())?.toMutableSet() ?: mutableSetOf()
        historySet.add(path)
        prefs.edit().putStringSet(KEY_HISTORY_PATHS, historySet).apply()
    }

    private fun replacePathInHistory(oldPath: String, newPath: String) {
        val oldAlias = getPathAlias(oldPath)
        val oldExcluded = getExcludedFolders(oldPath)

        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val historySet = prefs.getStringSet(KEY_HISTORY_PATHS, mutableSetOf())?.toMutableSet() ?: mutableSetOf()

        if (historySet.contains(oldPath)) {
            historySet.remove(oldPath)
            removePathAlias(oldPath)
            removeExcludedFolders(oldPath)
        }

        historySet.add(newPath)
        prefs.edit().putStringSet(KEY_HISTORY_PATHS, historySet).apply()

        if (!oldAlias.isNullOrEmpty()) {
            savePathAlias(newPath, oldAlias)
        }
        if (oldExcluded.isNotEmpty()) {
            saveExcludedFolders(newPath, oldExcluded)
        }

        if (currentSelectedPath == oldPath) {
            currentSelectedPath = newPath
            saveLastPath(newPath)
        }

        refreshPathChips(autoSelectPath = currentSelectedPath)
        Toast.makeText(this, "路径已更新", Toast.LENGTH_SHORT).show()
    }

    private fun removePathFromHistory(path: String) {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val historySet = prefs.getStringSet(KEY_HISTORY_PATHS, mutableSetOf())?.toMutableSet() ?: return
        historySet.remove(path)
        prefs.edit().putStringSet(KEY_HISTORY_PATHS, historySet).apply()

        removePathAlias(path)
        removeExcludedFolders(path)

        if (currentSelectedPath == path) {
            currentSelectedPath = null
        }
        refreshPathChips(autoSelectPath = currentSelectedPath)
    }

    private fun saveLastPath(path: String) {
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_LAST_PATH, path).apply()
    }

    // --- Dialogs ---

    private fun showChipOptionsDialog(path: String, currentName: String) {
        val options = arrayOf("重命名", "更改路径", "排除子文件夹", "移除")
        MaterialAlertDialogBuilder(this)
            .setTitle("管理: $currentName")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showRenameDialog(path, isNew = false)
                    1 -> {
                        pathBeingModified = path
                        selectFolderLauncher.launch(null)
                    }
                    2 -> showExcludeDialog(path)
                    3 -> showDeleteDialog(path)
                }
            }
            .show()
    }

    private fun showExcludeDialog(path: String) {
        val allSubFolders = getAllSubFolders(path)
        if (allSubFolders.isEmpty()) {
            Toast.makeText(this, "该目录下没有子文件夹", Toast.LENGTH_SHORT).show()
            return
        }

        val excludedSet = getExcludedFolders(path)

        val checkedItems = BooleanArray(allSubFolders.size) { i ->
            excludedSet.contains(allSubFolders[i])
        }

        MaterialAlertDialogBuilder(this)
            .setTitle("选择要排除的文件夹")
            .setMultiChoiceItems(allSubFolders, checkedItems) { _, which, isChecked ->
                checkedItems[which] = isChecked
            }
            .setPositiveButton("保存") { _, _ ->
                val newExcludedSet = mutableSetOf<String>()
                for (i in allSubFolders.indices) {
                    if (checkedItems[i]) {
                        newExcludedSet.add(allSubFolders[i])
                    }
                }
                saveExcludedFolders(path, newExcludedSet)
                Toast.makeText(this, "已更新排除列表", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun getAllSubFolders(pathStr: String): Array<String> {
        val list = mutableListOf<String>()
        try {
            if (pathStr.startsWith("content://")) {
                val root = DocumentFile.fromTreeUri(this, Uri.parse(pathStr))
                root?.listFiles()?.forEach {
                    if (it.isDirectory && it.name != null) list.add(it.name!!)
                }
            } else {
                val root = File(pathStr)
                root.listFiles()?.forEach {
                    if (it.isDirectory) list.add(it.name)
                }
            }
        } catch (e: Exception) { e.printStackTrace() }
        return list.sorted().toTypedArray()
    }

    private fun showRenameDialog(path: String, isNew: Boolean) {
        val context = this
        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 40, 50, 10)
        }

        val currentAlias = getPathAlias(path)
        val folderName = if (path.contains("/")) path.substringAfterLast("/") else path
        val defaultText = if (isNew) folderName else (currentAlias ?: folderName)

        val til = TextInputLayout(context).apply {
            boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
            setBoxCornerRadii(20f, 20f, 20f, 20f)
            hint = "按钮名称"
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        val et = TextInputEditText(til.context).apply { setText(defaultText) }
        til.addView(et)
        layout.addView(til)

        MaterialAlertDialogBuilder(context)
            .setTitle(if (isNew) "添加文件夹" else "重命名")
            .setView(layout)
            .setPositiveButton("确定") { _, _ ->
                val newName = et.text.toString().trim()
                val finalName = if (newName.isEmpty()) folderName else newName

                savePathAlias(path, finalName)
                if (isNew) {
                    addPathToHistory(path)
                }

                refreshPathChips(autoSelectPath = path)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showDeleteDialog(path: String) {
        MaterialAlertDialogBuilder(this)
            .setTitle("移除路径")
            .setMessage("确定要移除这个路径吗？\n\n$path")
            .setPositiveButton("移除") { _, _ ->
                removePathFromHistory(path)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showSettingsDialog() {
        val context = this
        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 40, 50, 10)
        }

        fun createInput(hint: String, defaultText: String, isNumber: Boolean = false): Pair<TextInputLayout, TextInputEditText> {
            val til = TextInputLayout(context).apply {
                boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
                setBoxCornerRadii(20f, 20f, 20f, 20f)
                this.hint = hint
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = 20; bottomMargin = 20 }
            }
            val et = TextInputEditText(til.context).apply {
                setText(defaultText)
                if (isNumber) inputType = InputType.TYPE_CLASS_NUMBER
            }
            til.addView(et)
            return Pair(til, et)
        }

        // 1. 默认开启悬浮窗开关
        val switchDefaultFloat = MaterialSwitch(context).apply {
            text = "App 启动时自动开启悬浮窗"
            isChecked = isDefaultFloatEnabled
            setPadding(10, 20, 10, 20)
        }

        // 包名
        val (tilImg, etImg) = createInput("图片 App 包名", targetImgPackage)
        val (tilVid, etVid) = createInput("视频 App 包名", targetVidPackage)

        // 冷却
        val switchCooldown = MaterialSwitch(context).apply {
            text = "避免重复选中近期看过的文件夹"
            isChecked = isCooldownEnabled
            setPadding(10, 20, 10, 20)
        }
        val (tilCooldown, etCooldown) = createInput("冷却时间 (分钟)", cooldownMinutes.toString(), isNumber = true)
        tilCooldown.visibility = if (isCooldownEnabled) android.view.View.VISIBLE else android.view.View.GONE
        switchCooldown.setOnCheckedChangeListener { _, isChecked ->
            tilCooldown.visibility = if (isChecked) android.view.View.VISIBLE else android.view.View.GONE
        }

        // 悬浮窗开启按钮 - 【修复点】使用 Material Button 风格
        val btnFloating = Button(context, null, 0, com.google.android.material.R.style.Widget_Material3_Button_TextButton).apply {
            text = "开启/关闭 桌面悬浮窗"
            setOnClickListener {
                if (!Settings.canDrawOverlays(context)) {
                    Toast.makeText(context, "请先授予悬浮窗权限", Toast.LENGTH_LONG).show()
                    val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
                    startActivity(intent)
                } else {
                    val serviceIntent = Intent(context, FloatingService::class.java)
                    if (isServiceRunning(FloatingService::class.java)) {
                        stopService(serviceIntent)
                        Toast.makeText(context, "悬浮窗已关闭", Toast.LENGTH_SHORT).show()
                    } else {
                        startService(serviceIntent)
                        Toast.makeText(context, "悬浮窗已开启", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

        layout.addView(TextView(context).apply { text = "悬浮窗设置" })
        layout.addView(switchDefaultFloat)
        layout.addView(btnFloating)
        layout.addView(TextView(context).apply { text = "\n应用包名设置" })
        layout.addView(tilImg)
        layout.addView(tilVid)
        layout.addView(TextView(context).apply { text = "\n随机策略设置" })
        layout.addView(switchCooldown)
        layout.addView(tilCooldown)

        MaterialAlertDialogBuilder(context)
            .setTitle("设置")
            .setView(layout)
            .setPositiveButton("保存") { _, _ ->
                targetImgPackage = etImg.text.toString().trim()
                targetVidPackage = etVid.text.toString().trim()

                val newCooldownEnabled = switchCooldown.isChecked
                val newCooldownMinutes = etCooldown.text.toString().toIntOrNull() ?: 60
                val newDefaultFloatEnabled = switchDefaultFloat.isChecked

                isCooldownEnabled = newCooldownEnabled
                cooldownMinutes = newCooldownMinutes
                isDefaultFloatEnabled = newDefaultFloatEnabled

                getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                    .putString(KEY_IMG_PKG, targetImgPackage)
                    .putString(KEY_VID_PKG, targetVidPackage)
                    .putBoolean(KEY_COOLDOWN_ENABLED, newCooldownEnabled)
                    .putInt(KEY_COOLDOWN_MINUTES, newCooldownMinutes)
                    .putBoolean(KEY_DEFAULT_FLOAT_ENABLED, newDefaultFloatEnabled)
                    .apply()

                Toast.makeText(context, "设置已保存", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun isServiceRunning(serviceClass: Class<*>): Boolean {
        val manager = getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        for (service in manager.getRunningServices(Int.MAX_VALUE)) {
            if (serviceClass.name == service.service.className) {
                return true
            }
        }
        return false
    }


    // --- File Logic ---
    private fun isImage(name: String) = name.endsWith(".jpg") || name.endsWith(".png") || name.endsWith(".jpeg") || name.endsWith(".webp") || name.endsWith(".bmp")
    private fun isVideo(name: String) = name.endsWith(".mp4") || name.endsWith(".mkv") || name.endsWith(".avi") || name.endsWith(".mov") || name.endsWith(".flv")

    private fun performRandomOpenFile(pathStr: String) {
        val rootDir = File(pathStr)
        if (!rootDir.exists() || !rootDir.isDirectory) {
            log("路径无效：$pathStr")
            return
        }

        val excluded = getExcludedFolders(pathStr)

        val validSubDirs = rootDir.listFiles { file ->
            file.isDirectory && !excluded.contains(file.name)
        }?.toList() ?: emptyList()

        if (validSubDirs.isEmpty()) {
            log("没有可用的子文件夹，尝试搜索当前目录...")
            findAndOpenFile(rootDir)
            return
        }

        val readySubDirs = validSubDirs.filter { !isFolderOnCooldown(it.absolutePath) }

        val targetDir = if (readySubDirs.isNotEmpty()) {
            readySubDirs.random()
        } else {
            log("注意：所有子文件夹都在冷却中，已随机选择一个。")
            Toast.makeText(this, "所有文件夹都在冷却中，随机选择", Toast.LENGTH_SHORT).show()
            validSubDirs.random()
        }

        saveFolderOpenTime(targetDir.absolutePath)

        findAndOpenFile(targetDir)
    }

    private fun findAndOpenFile(dir: File) {
        val files = dir.listFiles { file ->
            val n = file.name.lowercase()
            file.isFile && (isImage(n) || isVideo(n))
        }
        if (files == null || files.isEmpty()) {
            log("[${dir.name}] 无可读文件")
            return
        }
        val randomFile = files.random()
        val uri = Uri.fromFile(randomFile)

        log("打开：${dir.name}/${randomFile.name}")
        openExternalApp(uri, if(isVideo(randomFile.name.lowercase())) "video/*" else "image/*", if(isVideo(randomFile.name.lowercase())) targetVidPackage else targetImgPackage)
    }

    private fun performRandomOpenSaf(treeUri: Uri) {
        val rootDir = DocumentFile.fromTreeUri(this, treeUri)
        if (rootDir == null || !rootDir.isDirectory) {
            log("无法读取目录")
            return
        }

        val pathKey = convertUriToFilePath(treeUri)
        val excluded = getExcludedFolders(pathKey)

        val validSubDirs = rootDir.listFiles().filter {
            it.isDirectory && it.name != null && !excluded.contains(it.name)
        }

        if (validSubDirs.isEmpty()) {
            findAndOpenSaf(rootDir)
            return
        }

        val readySubDirs = validSubDirs.filter {
            val subPath = pathKey + "/" + it.name
            !isFolderOnCooldown(subPath)
        }

        val targetDir = if (readySubDirs.isNotEmpty()) {
            readySubDirs.random()
        } else {
            log("注意：所有子文件夹都在冷却中，已随机选择一个。")
            Toast.makeText(this, "所有文件夹都在冷却中，随机选择", Toast.LENGTH_SHORT).show()
            validSubDirs.random()
        }

        val targetPath = pathKey + "/" + targetDir.name
        saveFolderOpenTime(targetPath)

        findAndOpenSaf(targetDir)
    }

    private fun findAndOpenSaf(dir: DocumentFile) {
        val files = dir.listFiles().filter { file ->
            val n = file.name?.lowercase() ?: ""
            file.isFile && (isImage(n) || isVideo(n))
        }
        if (files.isEmpty()) {
            log("[${dir.name}] 无文件")
            return
        }
        val randomFile = files.random()
        log("打开：${dir.name}/${randomFile.name}")
        openExternalApp(randomFile.uri, if(isVideo(randomFile.name ?: "")) "video/*" else "image/*", if(isVideo(randomFile.name ?: "")) targetVidPackage else targetImgPackage)
    }

    private fun openExternalApp(uri: Uri, type: String, pkg: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW)
            intent.setDataAndType(uri, type)
            if (pkg.isNotEmpty()) intent.setPackage(pkg)
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
        } catch (e: Exception) {
            e.printStackTrace()
            try {
                val fallbackIntent = Intent(Intent.ACTION_VIEW)
                fallbackIntent.setDataAndType(uri, type)
                fallbackIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(fallbackIntent)
                log("未找到指定App ($pkg)，已调用系统默认方式。")
            } catch (ex: Exception) {
                log("启动失败：未找到可打开此文件的App。\n包名：$pkg")
            }
        }
    }

    private fun convertUriToFilePath(uri: Uri): String {
        try {
            val path = uri.path ?: return uri.toString()
            if (path.contains("primary:")) {
                val splitIndex = path.indexOf("primary:") + "primary:".length
                val relativePath = path.substring(splitIndex)
                val decodedPath = URLDecoder.decode(relativePath, "UTF-8")
                return Environment.getExternalStorageDirectory().absolutePath + "/" + decodedPath
            }
        } catch (e: Exception) { e.printStackTrace() }
        return uri.toString()
    }

    private fun checkAndRequestPermissions(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                Toast.makeText(this, "需授予所有文件管理权限", Toast.LENGTH_LONG).show()
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                intent.data = Uri.parse("package:$packageName")
                startActivity(intent)
                return false
            }
            return true
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE),
                    100
                )
                return false
            }
            return true
        }
    }

    private fun log(msg: String) {
        tvLog.text = msg
    }
}