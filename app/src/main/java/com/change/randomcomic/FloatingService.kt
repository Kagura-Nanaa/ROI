package com.change.randomcomic

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.view.Gravity
import android.view.ViewGroup
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import com.google.android.material.color.MaterialColors
import java.io.File
import java.net.URLDecoder
import kotlin.math.abs

class FloatingService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var floatingView: ImageView
    private lateinit var params: WindowManager.LayoutParams

    private val DEFAULT_FLOATING_SIZE_PX = 160

    // SharedPreferences Keys
    private val PREFS_NAME = "ComicPrefs"
    private val KEY_LAST_PATH = "last_path"
    private val KEY_IMG_PKG = "img_pkg"
    private val KEY_VID_PKG = "vid_pkg"
    private val KEY_EXCLUDED_PREFIX = "excluded_"
    private val KEY_FLOAT_SIZE = "float_size"
    // 【新增】位置记忆 Key
    private val KEY_FLOAT_X = "float_x"
    private val KEY_FLOAT_Y = "float_y"

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        // 读取尺寸
        val floatSize = prefs.getInt(KEY_FLOAT_SIZE, DEFAULT_FLOATING_SIZE_PX)
        // 【新增】读取上次保存的位置，默认为 (0, 200)
        val initialX = prefs.getInt(KEY_FLOAT_X, 0)
        val initialY = prefs.getInt(KEY_FLOAT_Y, 200)

        floatingView = ImageView(this).apply {
            setImageResource(R.drawable.arrow_clockwise_fill)

            setColorFilter(Color.BLACK)
            setBackgroundResource(R.drawable.fab_background)
            setPadding(floatSize / 5, floatSize / 5, floatSize / 5, floatSize / 5)

            layoutParams = ViewGroup.LayoutParams(
                floatSize, floatSize
            )
        }

        val layoutFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            WindowManager.LayoutParams.TYPE_PHONE
        }

        params = WindowManager.LayoutParams(
            floatSize, floatSize,
            layoutFlag,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )

        params.gravity = Gravity.TOP or Gravity.START
        // 【修改】使用读取到的位置
        params.x = initialX
        params.y = initialY

        try {
            windowManager.addView(floatingView, params)
        } catch (e: Exception) {
            e.printStackTrace()
            stopSelf()
            return
        }

        setupTouchListener(floatSize)
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
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        if (!isDrag) {
                            performRandomOpen()
                        } else {
                            // 自动贴边逻辑
                            val screenWidth = windowManager.defaultDisplay.width
                            val halfScreen = screenWidth / 2

                            val currentX = params.x + (v.width / 2)

                            // 计算贴边后的 X 坐标
                            params.x = if (currentX <= halfScreen) {
                                0
                            } else {
                                screenWidth - floatSize
                            }

                            windowManager.updateViewLayout(floatingView, params)
                            isDrag = false

                            // 【新增】拖拽并贴边结束后，保存最新的位置 (X 和 Y)
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

    // --- 核心逻辑 (保持不变) ---

    private fun performRandomOpen() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val path = prefs.getString(KEY_LAST_PATH, null)

        if (path == null) {
            Toast.makeText(this, "请先在主应用中选择一个文件夹", Toast.LENGTH_SHORT).show()
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

        val subDirs = rootDir.listFiles { file ->
            file.isDirectory && !excludedSet.contains(file.name)
        }

        if (subDirs == null || subDirs.isEmpty()) {
            findAndOpenFile(rootDir, prefs)
            return
        }

        findAndOpenFile(subDirs.random(), prefs)
    }

    private fun findAndOpenFile(dir: File, prefs: android.content.SharedPreferences) {
        val files = dir.listFiles { file ->
            val n = file.name.lowercase()
            file.isFile && (n.endsWith(".jpg") || n.endsWith(".png") || n.endsWith(".mp4") || n.endsWith(".mkv"))
        }
        if (files == null || files.isEmpty()) {
            Toast.makeText(this, "无文件", Toast.LENGTH_SHORT).show()
            return
        }
        val randomFile = files.random()
        val isVid = randomFile.name.lowercase().let { it.endsWith(".mp4") || it.endsWith(".mkv") }

        openExternalApp(Uri.fromFile(randomFile), isVid, prefs)
    }

    private fun performRandomOpenSaf(treeUri: Uri, prefs: android.content.SharedPreferences) {
        val rootDir = DocumentFile.fromTreeUri(this, treeUri) ?: return
        val pathKey = convertUriToFilePath(treeUri)
        val excludedSet = prefs.getStringSet(KEY_EXCLUDED_PREFIX + pathKey, emptySet()) ?: emptySet()

        val subDirs = rootDir.listFiles().filter {
            it.isDirectory && it.name != null && !excludedSet.contains(it.name)
        }

        if (subDirs.isEmpty()) {
            findAndOpenSaf(rootDir, prefs)
            return
        }
        findAndOpenSaf(subDirs.random(), prefs)
    }

    private fun findAndOpenSaf(dir: DocumentFile, prefs: android.content.SharedPreferences) {
        val files = dir.listFiles().filter {
            val n = it.name?.lowercase() ?: ""
            it.isFile && (n.endsWith(".jpg") || n.endsWith(".png") || n.endsWith(".mp4"))
        }
        if (files.isEmpty()) {
            Toast.makeText(this, "无文件", Toast.LENGTH_SHORT).show()
            return
        }
        val randomFile = files.random()
        val isVid = randomFile.name?.lowercase()?.endsWith(".mp4") == true
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
            Toast.makeText(this, "启动失败，请检查包名", Toast.LENGTH_SHORT).show()
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
        if (::floatingView.isInitialized) {
            windowManager.removeView(floatingView)
        }
    }
}