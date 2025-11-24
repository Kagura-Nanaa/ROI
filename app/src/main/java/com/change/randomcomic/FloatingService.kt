package com.change.randomcomic

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import com.google.android.material.color.MaterialColors
import java.io.File
import java.net.URLDecoder
import kotlin.math.abs

class FloatingService : Service() {

    companion object {
        var isStarted = false
    }

    private lateinit var windowManager: WindowManager
    private lateinit var floatingView: ImageView
    private lateinit var bubbleView: TextView

    private lateinit var params: WindowManager.LayoutParams
    private lateinit var bubbleParams: WindowManager.LayoutParams

    private val handler = Handler(Looper.getMainLooper())
    private val hideBubbleRunnable = Runnable { hideBubble() }

    private val DEFAULT_FLOATING_SIZE_PX = 160

    // Keys
    private val PREFS_NAME = "ComicPrefs"
    private val KEY_LAST_PATH = "last_path"
    private val KEY_IMG_PKG = "img_pkg"
    private val KEY_VID_PKG = "vid_pkg"
    private val KEY_EXCLUDED_PREFIX = "excluded_"
    private val KEY_FLOAT_SIZE = "float_size"
    private val KEY_FLOAT_X = "float_x"
    private val KEY_FLOAT_Y = "float_y"
    private val KEY_COMIC_MODE_PREFIX = "comic_mode_"
    private val KEY_COOLDOWN_ENABLED = "cooldown_enabled"
    private val KEY_COOLDOWN_MINUTES = "cooldown_minutes"
    private val KEY_LAST_OPEN_PREFIX = "last_open_"

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onCreate() {
        super.onCreate()
        isStarted = true
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val floatSize = prefs.getInt(KEY_FLOAT_SIZE, DEFAULT_FLOATING_SIZE_PX)
        val initialX = prefs.getInt(KEY_FLOAT_X, 0)
        val initialY = prefs.getInt(KEY_FLOAT_Y, 200)

        // 1. 初始化悬浮按钮
        initFloatingView(floatSize, initialX, initialY)

        // 2. 初始化气泡
        initBubbleView()

        setupTouchListener(floatSize)
    }

    private fun initFloatingView(size: Int, x: Int, y: Int) {
        floatingView = ImageView(this).apply {
            setImageResource(R.drawable.arrow_clockwise_fill)
            setColorFilter(Color.BLACK)
            setBackgroundResource(R.drawable.fab_background)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(size / 5, size / 5, size / 5, size / 5)
        }

        val layoutFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            WindowManager.LayoutParams.TYPE_PHONE
        }

        params = WindowManager.LayoutParams(
            size, size,
            layoutFlag,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )

        params.gravity = Gravity.TOP or Gravity.START
        params.x = x
        params.y = y

        try {
            windowManager.addView(floatingView, params)
        } catch (e: Exception) {
            e.printStackTrace()
            stopSelf()
        }
    }

    private fun initBubbleView() {
        bubbleView = TextView(this).apply {
            text = ""
            setTextColor(Color.WHITE)
            textSize = 14f
            setBackgroundResource(R.drawable.bg_bubble)
            visibility = View.GONE
            maxWidth = 600
        }

        val layoutFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            WindowManager.LayoutParams.TYPE_PHONE
        }

        bubbleParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutFlag,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        )

        bubbleParams.gravity = Gravity.TOP or Gravity.START

        try {
            windowManager.addView(bubbleView, bubbleParams)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun showBubble(text: String) {
        handler.removeCallbacks(hideBubbleRunnable)

        handler.post {
            bubbleView.text = text
            bubbleView.visibility = View.VISIBLE

            val screenWidth = windowManager.defaultDisplay.width
            val iconWidth = params.width
            val iconHeight = params.height
            val iconX = params.x
            val iconY = params.y

            val isIconOnLeft = iconX < (screenWidth / 2)
            val bubbleY = iconY + (iconHeight / 4)

            if (isIconOnLeft) {
                bubbleParams.gravity = Gravity.TOP or Gravity.START
                bubbleParams.x = iconX + iconWidth + 20
                bubbleParams.y = bubbleY
            } else {
                bubbleParams.gravity = Gravity.TOP or Gravity.END
                val marginFromRight = screenWidth - iconX
                bubbleParams.x = marginFromRight + 20
                bubbleParams.y = bubbleY
            }

            try {
                windowManager.updateViewLayout(bubbleView, bubbleParams)
            } catch (e: Exception) {
                e.printStackTrace()
            }

            handler.postDelayed(hideBubbleRunnable, 3000)
        }
    }

    private fun hideBubble() {
        if (bubbleView.visibility == View.VISIBLE) {
            bubbleView.visibility = View.GONE
        }
    }

    private fun setupTouchListener(floatSize: Int) {
        floatingView.setOnTouchListener(object : View.OnTouchListener {
            private var initialX = 0
            private var initialY = 0
            private var initialTouchX = 0f
            private var initialTouchY = 0f
            private var isDrag = false

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = params.x
                        initialY = params.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        isDrag = false
                        hideBubble()
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        if (!isDrag) {
                            performRandomOpen()
                        } else {
                            val screenWidth = windowManager.defaultDisplay.width
                            val halfScreen = screenWidth / 2
                            val currentX = params.x + (v.width / 2)

                            params.x = if (currentX <= halfScreen) 0 else screenWidth - floatSize

                            windowManager.updateViewLayout(floatingView, params)
                            isDrag = false

                            val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                            prefs.edit()
                                .putInt(KEY_FLOAT_X, params.x)
                                .putInt(KEY_FLOAT_Y, params.y)
                                .apply()
                        }
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = (event.rawX - initialTouchX).toInt()
                        val dy = (event.rawY - initialTouchY).toInt()

                        if (abs(dx) > 10 || abs(dy) > 10) {
                            isDrag = true
                            params.x = initialX + dx
                            params.y = initialY + dy
                            windowManager.updateViewLayout(floatingView, params)
                        }
                        return true
                    }
                }
                return false
            }
        })
    }

    // --- Helper Functions for Cooldown ---
    private fun isFolderOnCooldown(folderPath: String, prefs: android.content.SharedPreferences): Boolean {
        val isCooldownEnabled = prefs.getBoolean(KEY_COOLDOWN_ENABLED, false)
        if (!isCooldownEnabled) return false
        val lastTime = prefs.getLong(KEY_LAST_OPEN_PREFIX + folderPath, 0L)
        if (lastTime == 0L) return false
        val cooldownMinutes = prefs.getInt(KEY_COOLDOWN_MINUTES, 60)
        val currentTime = System.currentTimeMillis()
        val cooldownMillis = cooldownMinutes * 60 * 1000L
        return (currentTime - lastTime) < cooldownMillis
    }

    private fun saveFolderOpenTime(folderPath: String, prefs: android.content.SharedPreferences) {
        val isCooldownEnabled = prefs.getBoolean(KEY_COOLDOWN_ENABLED, false)
        if (!isCooldownEnabled) return
        prefs.edit().putLong(KEY_LAST_OPEN_PREFIX + folderPath, System.currentTimeMillis()).apply()
    }

    // --- Core Logic ---
    private fun performRandomOpen() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val path = prefs.getString(KEY_LAST_PATH, null)
        if (path == null) {
            showBubble("请先在主应用中选择一个文件夹")
            return
        }
        if (path.startsWith("content://")) {
            performRandomOpenSaf(Uri.parse(path), prefs)
        } else {
            performRandomOpenFile(path, prefs)
        }
    }

    private fun performRandomOpenFile(pathStr: String, prefs: android.content.SharedPreferences) {
        val rootDir = File(pathStr)
        if (!rootDir.exists() || !rootDir.isDirectory) return
        val excludedSet = prefs.getStringSet(KEY_EXCLUDED_PREFIX + pathStr, emptySet()) ?: emptySet()
        val isComicMode = prefs.getBoolean(KEY_COMIC_MODE_PREFIX + pathStr, false)

        val subDirs = rootDir.listFiles { file ->
            file.isDirectory && !excludedSet.contains(file.name)
        }

        if (subDirs == null || subDirs.isEmpty()) {
            findAndOpenFile(rootDir, prefs, isComicMode)
            return
        }

        val validSubDirs = subDirs.filter { !isFolderOnCooldown(it.absolutePath, prefs) }

        var toastPrefix: String? = null
        val targetDir = if (validSubDirs.isNotEmpty()) {
            validSubDirs.random()
        } else {
            toastPrefix = "所有文件夹都在冷却中"
            subDirs.random()
        }

        saveFolderOpenTime(targetDir.absolutePath, prefs)
        findAndOpenFile(targetDir, prefs, isComicMode, toastPrefix)
    }

    private fun findAndOpenFile(dir: File, prefs: android.content.SharedPreferences, isComicMode: Boolean, toastPrefix: String? = null) {
        val files = dir.listFiles { file ->
            val n = file.name.lowercase()
            file.isFile && (n.endsWith(".jpg") || n.endsWith(".png") || n.endsWith(".mp4") || n.endsWith(".mkv"))
        }
        if (files == null || files.isEmpty()) {
            showBubble("无文件")
            return
        }

        val randomFile = if (isComicMode) {
            files.sortedBy { it.name }.first()
        } else {
            files.random()
        }

        val isVid = randomFile.name.lowercase().let { it.endsWith(".mp4") || it.endsWith(".mkv") }

        val msg = if (toastPrefix != null) "$toastPrefix\n正在打开: ${dir.name}" else "正在打开: ${dir.name}"
        showBubble(msg)

        openExternalApp(Uri.fromFile(randomFile), isVid, prefs)
    }

    private fun performRandomOpenSaf(treeUri: Uri, prefs: android.content.SharedPreferences) {
        val rootDir = DocumentFile.fromTreeUri(this, treeUri) ?: return
        val pathKey = convertUriToFilePath(treeUri)
        val excludedSet = prefs.getStringSet(KEY_EXCLUDED_PREFIX + pathKey, emptySet()) ?: emptySet()
        val isComicMode = prefs.getBoolean(KEY_COMIC_MODE_PREFIX + pathKey, false)

        val subDirs = rootDir.listFiles().filter {
            it.isDirectory && it.name != null && !excludedSet.contains(it.name)
        }

        if (subDirs.isEmpty()) {
            findAndOpenSaf(rootDir, prefs, isComicMode)
            return
        }

        val validSubDirs = subDirs.filter {
            val subPath = pathKey + "/" + it.name
            !isFolderOnCooldown(subPath, prefs)
        }

        var toastPrefix: String? = null
        val targetDir = if (validSubDirs.isNotEmpty()) {
            validSubDirs.random()
        } else {
            toastPrefix = "所有文件夹都在冷却中"
            subDirs.random()
        }

        val targetPath = pathKey + "/" + targetDir.name
        saveFolderOpenTime(targetPath, prefs)

        findAndOpenSaf(targetDir, prefs, isComicMode, toastPrefix)
    }

    private fun findAndOpenSaf(dir: DocumentFile, prefs: android.content.SharedPreferences, isComicMode: Boolean, toastPrefix: String? = null) {
        val files = dir.listFiles().filter {
            val n = it.name?.lowercase() ?: ""
            it.isFile && (n.endsWith(".jpg") || n.endsWith(".png") || n.endsWith(".mp4"))
        }
        if (files.isEmpty()) {
            showBubble("无文件")
            return
        }

        val randomFile = if (isComicMode) {
            files.sortedBy { it.name }.first()
        } else {
            files.random()
        }

        val isVid = randomFile.name?.lowercase()?.endsWith(".mp4") == true

        val folderName = dir.name ?: "未知文件夹"
        val msg = if (toastPrefix != null) "$toastPrefix\n正在打开: $folderName" else "正在打开: $folderName"
        showBubble(msg)

        openExternalApp(randomFile.uri, isVid, prefs)
    }

    private fun openExternalApp(uri: Uri, isVideo: Boolean, prefs: android.content.SharedPreferences) {
        val imgPkg = prefs.getString(KEY_IMG_PKG, "com.rookiestudio.perfectviewer")
        val vidPkg = prefs.getString(KEY_VID_PKG, "com.mxtech.videoplayer.ad")
        val pkg = if (isVideo) vidPkg else imgPkg
        val type = if (isVideo) "video/*" else "image/*"
        try {
            val intent = Intent(Intent.ACTION_VIEW)
            intent.setDataAndType(uri, type)
            if (!pkg.isNullOrEmpty()) intent.setPackage(pkg)
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
        } catch (e: Exception) {
            showBubble("启动失败，请检查包名")
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

    override fun onDestroy() {
        super.onDestroy()
        isStarted = false
        if (::floatingView.isInitialized) windowManager.removeView(floatingView)
        if (::bubbleView.isInitialized) windowManager.removeView(bubbleView)
    }
}