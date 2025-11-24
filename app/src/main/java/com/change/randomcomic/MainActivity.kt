package com.change.randomcomic

import android.Manifest
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.StrictMode
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.color.DynamicColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import java.io.File
import java.net.URLDecoder
import java.util.Locale
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {

    private lateinit var tvLog: TextView
    private lateinit var chipGroupPaths: ChipGroup

    // Keys
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
    private val KEY_DEFAULT_FLOAT_ENABLED = "default_float_enabled"
    private val KEY_FLOAT_SIZE = "float_size"
    private val KEY_COMIC_MODE_PREFIX = "comic_mode_"
    private val KEY_IS_FIRST_RUN = "is_first_run_v2"

    // Settings - Defaults
    private var targetImgPackage = "com.rookiestudio.perfectviewer"
    private var targetVidPackage = "com.mxtech.videoplayer.ad"
    private var isCooldownEnabled = false
    private var cooldownMinutes = 60
    private var isDefaultFloatEnabled = false
    private var floatSize = 160

    // State
    private var currentSelectedPath: String? = null
    private var pathBeingModified: String? = null

    // 数据类：用于存储应用信息
    data class AppInfo(
        val name: String,
        val packageName: String,
        val icon: Drawable
    )

    private val selectFolderLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri == null) {
            pathBeingModified = null
            return@registerForActivityResult
        }
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
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

        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val isFirstRun = prefs.getBoolean(KEY_IS_FIRST_RUN, true)
        if (isFirstRun) {
            showWelcomeGuide()
        } else {
            checkPermissionsAndFloat()
        }

        val lastPath = prefs.getString(KEY_LAST_PATH, null)
        refreshPathChips(autoSelectPath = lastPath)
    }

    private fun checkPermissionsAndFloat() {
        // 使用 FloatingService.isStarted 静态变量检查服务状态
        if (isDefaultFloatEnabled && Settings.canDrawOverlays(this) && !FloatingService.isStarted) {
            val serviceIntent = Intent(this, FloatingService::class.java).apply {
                putExtra(KEY_FLOAT_SIZE, floatSize)
            }
            startService(serviceIntent)
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

    // 首次运行引导页
    private fun showWelcomeGuide() {
        val context = this
        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 50, 50, 50)
        }

        val tvWelcome = TextView(context).apply {
            text = "欢迎使用 ROI 随机阅读器！\n\n首次使用，请设置您偏好的打开方式。\n\n如果未选择，默认使用：\n图片: Perfect Viewer\n视频: MX Player"
            textSize = 16f
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = 40 }
        }

        val btnSelectImgApp = Button(context, null, 0, com.google.android.material.R.style.Widget_Material3_Button_OutlinedButton).apply {
            text = "选择图片阅读器"
            setOnClickListener {
                showAppPickerDialog("选择图片阅读器") { app ->
                    targetImgPackage = app.packageName
                    text = "图片: ${app.name}"
                }
            }
        }

        val btnSelectVidApp = Button(context, null, 0, com.google.android.material.R.style.Widget_Material3_Button_OutlinedButton).apply {
            text = "选择视频播放器"
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = 20 }
            setOnClickListener {
                showAppPickerDialog("选择视频播放器") { app ->
                    targetVidPackage = app.packageName
                    text = "视频: ${app.name}"
                }
            }
        }

        layout.addView(tvWelcome)
        layout.addView(btnSelectImgApp)
        layout.addView(btnSelectVidApp)

        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle("初次设置")
            .setView(layout)
            .setCancelable(false)
            .setPositiveButton("开始使用", null)
            .show()

        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                .putString(KEY_IMG_PKG, targetImgPackage)
                .putString(KEY_VID_PKG, targetVidPackage)
                .putBoolean(KEY_IS_FIRST_RUN, false)
                .apply()

            dialog.dismiss()
            Toast.makeText(context, "设置完成！请点击右上角 + 号添加文件夹。", Toast.LENGTH_LONG).show()
            checkPermissionsAndFloat()
        }
    }

    // 获取手机内所有可启动的应用 (Launcher Apps)
    private fun getAllLauncherApps(): List<AppInfo> {
        val intent = Intent(Intent.ACTION_MAIN, null)
        intent.addCategory(Intent.CATEGORY_LAUNCHER)

        val resolveInfos = packageManager.queryIntentActivities(intent, 0)
        val apps = mutableListOf<AppInfo>()

        for (info in resolveInfos) {
            val pkgName = info.activityInfo.packageName
            val appName = info.loadLabel(packageManager).toString()
            val icon = info.loadIcon(packageManager)
            apps.add(AppInfo(appName, pkgName, icon))
        }
        return apps.distinctBy { it.packageName }.sortedBy { it.name.lowercase(Locale.getDefault()) }
    }

    // 带有搜索功能的应用选择器
    private fun showAppPickerDialog(title: String, onSelected: (AppInfo) -> Unit) {
        Toast.makeText(this, "正在读取应用列表...", Toast.LENGTH_SHORT).show()

        Thread {
            val allApps = getAllLauncherApps()

            runOnUiThread {
                val context = this
                val container = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(0, 20, 0, 0)
                }

                val tilSearch = TextInputLayout(context).apply {
                    boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
                    setBoxCornerRadii(20f, 20f, 20f, 20f)
                    hint = "搜索应用"
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        marginStart = 50
                        marginEnd = 50
                        bottomMargin = 20
                    }
                }
                val etSearch = TextInputEditText(tilSearch.context).apply {
                    inputType = InputType.TYPE_CLASS_TEXT
                }
                tilSearch.addView(etSearch)
                container.addView(tilSearch)

                val recyclerView = RecyclerView(this).apply {
                    layoutManager = LinearLayoutManager(this@MainActivity)
                    setPadding(0, 0, 0, 20)
                }
                container.addView(recyclerView)

                var dialog: AlertDialog? = null

                val adapter = AppListAdapter(allApps) { app ->
                    dialog?.dismiss()
                    onSelected(app)
                }
                recyclerView.adapter = adapter

                etSearch.addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                        val query = s.toString().lowercase(Locale.getDefault())
                        val filtered = if (query.isEmpty()) {
                            allApps
                        } else {
                            allApps.filter {
                                it.name.lowercase(Locale.getDefault()).contains(query) ||
                                        it.packageName.lowercase(Locale.getDefault()).contains(query)
                            }
                        }
                        adapter.updateList(filtered)
                    }
                    override fun afterTextChanged(s: Editable?) {}
                })

                dialog = MaterialAlertDialogBuilder(this)
                    .setTitle(title)
                    .setView(container)
                    .setNegativeButton("取消", null)
                    .show()
            }
        }.start()
    }

    inner class AppListAdapter(
        private var apps: List<AppInfo>,
        private val onClick: (AppInfo) -> Unit
    ) : RecyclerView.Adapter<AppListAdapter.ViewHolder>() {

        fun updateList(newApps: List<AppInfo>) {
            apps = newApps
            notifyDataSetChanged()
        }

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val icon: ImageView = view.findViewById(R.id.ivAppIcon)
            val name: TextView = view.findViewById(R.id.tvAppName)
            val pkg: TextView = view.findViewById(R.id.tvAppPackage)
            val radio: RadioButton = view.findViewById(R.id.rbSelected)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_app_info, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val app = apps[position]
            holder.icon.setImageDrawable(app.icon)
            holder.name.text = app.name
            holder.pkg.text = app.packageName

            val isCurrentDefault = (app.packageName == targetImgPackage) || (app.packageName == targetVidPackage)
            holder.radio.isChecked = isCurrentDefault
            holder.radio.visibility = if (isCurrentDefault) View.VISIBLE else View.INVISIBLE

            holder.itemView.setOnClickListener {
                onClick(app)
            }
        }

        override fun getItemCount() = apps.size
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
        isDefaultFloatEnabled = prefs.getBoolean(KEY_DEFAULT_FLOAT_ENABLED, false)
        floatSize = prefs.getInt(KEY_FLOAT_SIZE, 160)
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

    private fun setComicModeEnabled(path: String, enabled: Boolean) {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_COMIC_MODE_PREFIX + path, enabled).apply()
    }

    private fun isComicModeEnabled(path: String): Boolean {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_COMIC_MODE_PREFIX + path, false)
    }

    private fun removeComicModeSetting(path: String) {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().remove(KEY_COMIC_MODE_PREFIX + path).apply()
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
        val oldComicMode = isComicModeEnabled(oldPath)

        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val historySet = prefs.getStringSet(KEY_HISTORY_PATHS, mutableSetOf())?.toMutableSet() ?: mutableSetOf()

        if (historySet.contains(oldPath)) {
            historySet.remove(oldPath)
            removePathAlias(oldPath)
            removeExcludedFolders(oldPath)
            removeComicModeSetting(oldPath)
        }

        historySet.add(newPath)
        prefs.edit().putStringSet(KEY_HISTORY_PATHS, historySet).apply()

        if (!oldAlias.isNullOrEmpty()) {
            savePathAlias(newPath, oldAlias)
        }
        if (oldExcluded.isNotEmpty()) {
            saveExcludedFolders(newPath, oldExcluded)
        }
        setComicModeEnabled(newPath, oldComicMode)

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
        removeComicModeSetting(path)

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
        val context = this
        val dialogLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 20, 0, 20)
        }

        var dialog: AlertDialog? = null

        fun createOptionItem(text: String, onClick: () -> Unit): TextView {
            return TextView(context).apply {
                this.text = text
                textSize = 16f
                setPadding(70, 40, 70, 40)
                val outValue = TypedValue()
                context.theme.resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)
                setBackgroundResource(outValue.resourceId)

                setOnClickListener {
                    onClick()
                    dialog?.dismiss()
                }
            }
        }

        dialogLayout.addView(createOptionItem("重命名") { showRenameDialog(path, isNew = false) })
        dialogLayout.addView(createOptionItem("更改路径") { pathBeingModified = path; selectFolderLauncher.launch(null) })
        dialogLayout.addView(createOptionItem("排除子文件夹") { showExcludeDialog(path) })

        val comicLayout = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, 0)
        }

        val isComicMode = isComicModeEnabled(path)
        val statusText = if (isComicMode) "开启" else "关闭"

        val comicTextView = TextView(context).apply {
            text = "漫画模式: $statusText"
            textSize = 16f
            setPadding(70, 40, 20, 40)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)

            val outValue = TypedValue()
            context.theme.resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)
            setBackgroundResource(outValue.resourceId)

            setOnClickListener {
                setComicModeEnabled(path, !isComicMode)
                val newStatus = if (!isComicMode) "开启" else "关闭"
                Toast.makeText(context, "漫画模式已$newStatus", Toast.LENGTH_SHORT).show()
                dialog?.dismiss()
            }
        }

        val helpBtn = ImageButton(context).apply {
            setImageResource(android.R.drawable.ic_menu_info_details)
            val outValue = TypedValue()
            context.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, outValue, true)
            setBackgroundResource(outValue.resourceId)
            setPadding(30, 30, 70, 30)
            setColorFilter(android.graphics.Color.GRAY)

            setOnClickListener {
                showComicModeHelpDialog()
            }
        }

        comicLayout.addView(comicTextView)
        comicLayout.addView(helpBtn)
        dialogLayout.addView(comicLayout)

        dialogLayout.addView(createOptionItem("移除") { showDeleteDialog(path) })

        dialog = MaterialAlertDialogBuilder(context)
            .setTitle("管理: $currentName")
            .setView(dialogLayout)
            .show()
    }

    private fun showComicModeHelpDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle("什么是漫画模式？")
            .setMessage("开启此模式后，App 在选中某个子文件夹时，将不再随机打开文件，而是始终打开该文件夹中排序第一的文件。\n\n这非常适合阅读漫画或观看连续剧，确保每次都能从第一页或第一集开始。")
            .setPositiveButton("知道了", null)
            .show()
    }

    // 【补全】添加回 getAllSubFolders
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
        val scrollView = ScrollView(context)
        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 40, 50, 10)
        }
        scrollView.addView(layout)

        val tvFloatSize = TextView(context).apply {
            text = "悬浮窗大小: ${floatSize}px"
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = 20 }
        }
        val sliderFloatSize = Slider(context).apply {
            valueFrom = 100f
            valueTo = 300f
            stepSize = 10f
            value = floatSize.toFloat()
            addOnChangeListener { _, value, _ ->
                tvFloatSize.text = "悬浮窗大小: ${value.roundToInt()}px"
            }
        }
        val tvRestartHint = TextView(context).apply {
            text = "(重启软件后生效)"
            textSize = 12f
            alpha = 0.6f
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { gravity = Gravity.END }
        }
        val switchDefaultFloat = MaterialSwitch(context).apply {
            text = "App 启动时自动开启悬浮窗"
            isChecked = isDefaultFloatEnabled
            setPadding(10, 20, 10, 20)
        }
        val isRunning = FloatingService.isStarted
        val btnFloating = Button(context, null, 0, com.google.android.material.R.style.Widget_Material3_Button_TextButton).apply {
            text = if (isRunning) "关闭 桌面悬浮窗" else "开启 桌面悬浮窗"
            setOnClickListener {
                val currentSize = sliderFloatSize.value.roundToInt()
                val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                prefs.edit().putInt(KEY_FLOAT_SIZE, currentSize).apply()
                if (!Settings.canDrawOverlays(context)) {
                    Toast.makeText(context, "请先授予悬浮窗权限", Toast.LENGTH_LONG).show()
                    startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                } else {
                    val serviceIntent = Intent(context, FloatingService::class.java).apply { putExtra(KEY_FLOAT_SIZE, currentSize) }
                    if (FloatingService.isStarted) {
                        stopService(serviceIntent)
                        text = "开启 桌面悬浮窗"
                        Toast.makeText(context, "悬浮窗已关闭", Toast.LENGTH_SHORT).show()
                    } else {
                        stopService(serviceIntent)
                        startService(serviceIntent)
                        text = "关闭 桌面悬浮窗"
                        Toast.makeText(context, "悬浮窗已开启", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

        // 包名设置
        fun createAppSelector(label: String, currentPkg: String, mimeType: String, onPkgChanged: (String) -> Unit): TextInputLayout {
            val til = TextInputLayout(context).apply {
                boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
                setBoxCornerRadii(20f, 20f, 20f, 20f)
                hint = label
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = 20 }
            }
            val et = TextInputEditText(til.context).apply {
                setText(currentPkg)
                inputType = InputType.TYPE_NULL
                isFocusable = false
                setOnClickListener {
                    showAppPickerDialog("选择应用") { app ->
                        setText(app.packageName)
                        onPkgChanged(app.packageName)
                    }
                }
            }
            til.addView(et)
            return til
        }
        val selectorImg = createAppSelector("图片 App 包名", targetImgPackage, "image/*") { pkg -> targetImgPackage = pkg }
        val selectorVid = createAppSelector("视频 App 包名", targetVidPackage, "video/*") { pkg -> targetVidPackage = pkg }

        val switchCooldown = MaterialSwitch(context).apply {
            text = "避免重复选中近期看过的文件夹"
            isChecked = isCooldownEnabled
            setPadding(10, 20, 10, 20)
        }
        fun createNumberInput(hint: String, defaultText: String): Pair<TextInputLayout, TextInputEditText> {
            val til = TextInputLayout(context).apply {
                boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
                setBoxCornerRadii(20f, 20f, 20f, 20f)
                this.hint = hint
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = 20; bottomMargin = 20 }
            }
            val et = TextInputEditText(til.context).apply { setText(defaultText); inputType = InputType.TYPE_CLASS_NUMBER }
            til.addView(et)
            return Pair(til, et)
        }
        val (tilCooldown, etCooldown) = createNumberInput("冷却时间 (分钟)", cooldownMinutes.toString())
        tilCooldown.visibility = if (isCooldownEnabled) View.VISIBLE else View.GONE
        switchCooldown.setOnCheckedChangeListener { _, isChecked ->
            tilCooldown.visibility = if (isChecked) View.VISIBLE else View.GONE
        }

        val btnAbout = Button(context, null, 0, com.google.android.material.R.style.Widget_Material3_Button_TonalButton).apply {
            text = "关于 ROI"
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = 30 }
            setOnClickListener { showAboutDialog() }
        }

        layout.addView(TextView(context).apply { text = "悬浮窗设置" })
        layout.addView(tvFloatSize)
        layout.addView(sliderFloatSize)
        layout.addView(tvRestartHint)
        layout.addView(switchDefaultFloat)
        layout.addView(btnFloating)
        layout.addView(TextView(context).apply { text = "\n应用包名设置 (点击选择)" })
        layout.addView(selectorImg)
        layout.addView(selectorVid)
        layout.addView(TextView(context).apply { text = "\n随机策略设置" })
        layout.addView(switchCooldown)
        layout.addView(tilCooldown)
        layout.addView(btnAbout)

        MaterialAlertDialogBuilder(context)
            .setTitle("设置")
            .setView(scrollView)
            .setPositiveButton("保存") { _, _ ->
                val newCooldownEnabled = switchCooldown.isChecked
                val newCooldownMinutes = etCooldown.text.toString().toIntOrNull() ?: 60
                val newDefaultFloatEnabled = switchDefaultFloat.isChecked
                val newFloatSize = sliderFloatSize.value.roundToInt()

                isCooldownEnabled = newCooldownEnabled
                cooldownMinutes = newCooldownMinutes
                isDefaultFloatEnabled = newDefaultFloatEnabled
                floatSize = newFloatSize

                getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                    .putString(KEY_IMG_PKG, targetImgPackage)
                    .putString(KEY_VID_PKG, targetVidPackage)
                    .putBoolean(KEY_COOLDOWN_ENABLED, newCooldownEnabled)
                    .putInt(KEY_COOLDOWN_MINUTES, newCooldownMinutes)
                    .putBoolean(KEY_DEFAULT_FLOAT_ENABLED, newDefaultFloatEnabled)
                    .putInt(KEY_FLOAT_SIZE, newFloatSize)
                    .apply()

                Toast.makeText(context, "设置已保存", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showAboutDialog() {
        val context = this
        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(50, 50, 50, 50)
        }

        val ivIcon = ImageView(context).apply {
            setImageResource(R.mipmap.ic_launcher_round)
            layoutParams = LinearLayout.LayoutParams(180, 180).apply { bottomMargin = 24 }
        }

        val tvName = TextView(context).apply {
            text = "ROI"
            textSize = 24f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            val typedValue = TypedValue()
            theme.resolveAttribute(com.google.android.material.R.attr.colorOnSurface, typedValue, true)
            setTextColor(typedValue.data)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = 8 }
        }

        val versionName = try {
            packageManager.getPackageInfo(packageName, 0).versionName
        } catch (e: Exception) { "Unknown" }
        val tvVersion = TextView(context).apply {
            text = "Version $versionName"
            textSize = 14f
            alpha = 0.6f
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = 32 }
        }

        val tvGithub = TextView(context).apply {
            textSize = 14f
            movementMethod = android.text.method.LinkMovementMethod.getInstance()

            val htmlText = "项目地址：<a href=\"https://github.com/Kagura-Nanaa/ROI\">GitHub</a>"

            text = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                android.text.Html.fromHtml(htmlText, android.text.Html.FROM_HTML_MODE_COMPACT)
            } else {
                @Suppress("DEPRECATION")
                android.text.Html.fromHtml(htmlText)
            }

            val typedValue = TypedValue()
            context.theme.resolveAttribute(com.google.android.material.R.attr.colorPrimary, typedValue, true)
            setLinkTextColor(typedValue.data)

            val textTypedValue = TypedValue()
            context.theme.resolveAttribute(com.google.android.material.R.attr.colorOnSurface, textTypedValue, true)
            setTextColor(textTypedValue.data)

            gravity = Gravity.CENTER
        }

        layout.addView(ivIcon)
        layout.addView(tvName)
        layout.addView(tvVersion)
        layout.addView(tvGithub)

        MaterialAlertDialogBuilder(context)
            .setView(layout)
            .setPositiveButton("关闭", null)
            .show()
    }

    private fun isServiceRunning(serviceClass: Class<*>): Boolean {
        val manager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
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
        val isComicMode = isComicModeEnabled(pathStr)
        val validSubDirs = rootDir.listFiles { file ->
            file.isDirectory && !excluded.contains(file.name)
        }?.toList() ?: emptyList()
        if (validSubDirs.isEmpty()) {
            log("没有可用的子文件夹，尝试搜索当前目录...")
            findAndOpenFile(rootDir, isComicMode)
            return
        }
        val readySubDirs = validSubDirs.filter { !isFolderOnCooldown(it.absolutePath) }
        // Toast 前缀
        var toastPrefix: String? = null
        val targetDir = if (readySubDirs.isNotEmpty()) {
            readySubDirs.random()
        } else {
            log("注意：所有子文件夹都在冷却中，已随机选择一个。")
            toastPrefix = "所有文件夹都在冷却中"
            validSubDirs.random()
        }
        saveFolderOpenTime(targetDir.absolutePath)
        findAndOpenFile(targetDir, isComicMode, toastPrefix)
    }

    private fun findAndOpenFile(dir: File, isComicMode: Boolean, toastPrefix: String? = null) {
        val files = dir.listFiles { file ->
            val n = file.name.lowercase()
            file.isFile && (isImage(n) || isVideo(n))
        }
        if (files == null || files.isEmpty()) {
            log("[${dir.name}] 无可读文件")
            return
        }
        val randomFile = if (isComicMode) {
            files.sortedBy { it.name }.first()
        } else {
            files.random()
        }
        val uri = Uri.fromFile(randomFile)
        log("打开：${dir.name}/${randomFile.name}")

        val msg = if (toastPrefix != null) "$toastPrefix，正在打开: ${dir.name}" else "正在打开: ${dir.name}"
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

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
        val isComicMode = isComicModeEnabled(pathKey)
        val validSubDirs = rootDir.listFiles().filter {
            it.isDirectory && it.name != null && !excluded.contains(it.name)
        }
        if (validSubDirs.isEmpty()) {
            findAndOpenSaf(rootDir, isComicMode)
            return
        }
        val readySubDirs = validSubDirs.filter {
            val subPath = pathKey + "/" + it.name
            !isFolderOnCooldown(subPath)
        }

        var toastPrefix: String? = null
        val targetDir = if (readySubDirs.isNotEmpty()) {
            readySubDirs.random()
        } else {
            log("注意：所有子文件夹都在冷却中，已随机选择一个。")
            toastPrefix = "所有文件夹都在冷却中"
            validSubDirs.random()
        }
        val targetPath = pathKey + "/" + targetDir.name
        saveFolderOpenTime(targetPath)
        findAndOpenSaf(targetDir, isComicMode, toastPrefix)
    }

    private fun findAndOpenSaf(dir: DocumentFile, isComicMode: Boolean, toastPrefix: String? = null) {
        val files = dir.listFiles().filter { file ->
            val n = file.name?.lowercase() ?: ""
            file.isFile && (isImage(n) || isVideo(n))
        }
        if (files.isEmpty()) {
            log("[${dir.name}] 无文件")
            return
        }
        val randomFile = if (isComicMode) {
            files.sortedBy { it.name }.first()
        } else {
            files.random()
        }
        log("打开：${dir.name}/${randomFile.name}")
        val folderName = dir.name ?: "未知文件夹"
        val msg = if (toastPrefix != null) "$toastPrefix，正在打开: $folderName" else "正在打开: $folderName"
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

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