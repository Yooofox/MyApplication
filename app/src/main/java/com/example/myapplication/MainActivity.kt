package com.example.myapplication

import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.widget.Button
import android.widget.ImageView
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.canhub.cropper.CropImageContract
import com.canhub.cropper.CropImageContractOptions
import com.canhub.cropper.CropImageOptions
import com.canhub.cropper.CropImageView
import java.io.OutputStream

class MainActivity : AppCompatActivity() {

    private lateinit var ivResult: ImageView
    private lateinit var tvImageInfo: TextView

    // 图层数据存储
    private var layerA_Cropped: Bitmap? = null
    private var layerA_Background: Bitmap? = null
    private var layerB_Direct: Bitmap? = null
    private var layer_StaticBackground: Bitmap? = null
    private var compositeBitmap: Bitmap? = null

    // 选择状态
    private var isA_BackgroundActive = false
    private var hasLayerB = false
    private var hasStaticBackground = false
    private var isScaleBToA = true

    // 复用 Paint
    private val clearPaint = Paint().apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
        isAntiAlias = true
    }
    private val filterPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

    // 1. 图层 A 选择 (裁切模式)
    private val pickImageA = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let { startAdvancedCropper(it) }
    }

    private val cropImageA = registerForActivityResult(CropImageContract()) { result ->
        if (result.isSuccessful) {
            val uriOriginal = result.originalUri
            val cropRect = result.cropRect
            if (uriOriginal != null && cropRect != null) {
                val original = loadBitmapFromUri(uriOriginal)
                original?.let { 
                    releaseLayerA()
                    layerA_Cropped = extractFullSizeCroppedLayer(it, cropRect)
                    layerA_Background = extractBackgroundLayer(it, cropRect)
                    updateCompositeView()
                    showToast(getString(R.string.toast_layer_a_ready))
                }
            }
        }
    }

    // 2. 静态背景图选择 (绝对底层)
    private val pickStaticBackground = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let {
            val bitmap = loadBitmapFromUri(it)
            if (bitmap != null) {
                layer_StaticBackground?.recycle()
                layer_StaticBackground = bitmap
                hasStaticBackground = true
                updateCompositeView()
                showToast("静态背景已设置")
            }
        }
    }

    // 3. 图层 B 选择 (直接加载)
    private val pickImageB = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let { 
            val bitmap = loadBitmapFromUri(it)
            if (bitmap != null) {
                layerB_Direct?.recycle()
                layerB_Direct = bitmap
                hasLayerB = true
                updateCompositeView()
                showToast(getString(R.string.toast_layer_b_ready))
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initViews()
        setupEdgeToEdge()
    }

    private fun initViews() {
        ivResult = findViewById(R.id.ivResult)
        tvImageInfo = findViewById(R.id.tvImageInfo)
        val btnToggleScale = findViewById<Button>(R.id.btnToggleScaleMode)
        
        findViewById<Button>(R.id.btnSelectImage1).setOnClickListener { pickImageA.launch("image/*") }
        findViewById<Button>(R.id.btnSetBackground).setOnClickListener { pickStaticBackground.launch("image/*") }
        findViewById<Button>(R.id.btnSelectImage2).setOnClickListener { pickImageB.launch("image/*") }
        
        findViewById<Button>(R.id.btnLayerA_Cropped).setOnClickListener { 
            isA_BackgroundActive = false
            updateCompositeView()
        }
        findViewById<Button>(R.id.btnLayerA_Background).setOnClickListener { 
            isA_BackgroundActive = true
            updateCompositeView()
        }
        
        btnToggleScale.setOnClickListener {
            isScaleBToA = !isScaleBToA
            btnToggleScale.text = if (isScaleBToA) getString(R.string.scale_mode_b_to_a) else getString(R.string.scale_mode_a_to_b)
            updateCompositeView()
        }

        findViewById<Button>(R.id.btnSave).setOnClickListener {
            saveCurrentComposite()
        }
    }

    private fun setupEdgeToEdge() {
        val mainLayout = findViewById<ScrollView>(R.id.main_layout)
        ViewCompat.setOnApplyWindowInsetsListener(mainLayout) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
    }

    /**
     * 三层合成引擎：静态背景 -> 图层 B -> 图层 A
     */
    private fun updateCompositeView() {
        val layerA = if (isA_BackgroundActive) layerA_Background else layerA_Cropped
        val layerB = layerB_Direct
        val bgLayer = layer_StaticBackground

        if (layerA == null && layerB == null && bgLayer == null) return

        // 确定基准尺寸
        val targetWidth: Int
        val targetHeight: Int
        
        if (isScaleBToA) {
            targetWidth = layerA?.width ?: layerB?.width ?: bgLayer?.width ?: 1
            targetHeight = layerA?.height ?: layerB?.height ?: bgLayer?.height ?: 1
        } else {
            targetWidth = bgLayer?.width ?: layerB?.width ?: layerA?.width ?: 1
            targetHeight = bgLayer?.height ?: layerB?.height ?: layerA?.height ?: 1
        }

        // 画布复用
        if (compositeBitmap == null || compositeBitmap?.width != targetWidth || compositeBitmap?.height != targetHeight) {
            compositeBitmap?.recycle()
            compositeBitmap = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
        }

        val canvas = Canvas(compositeBitmap!!)
        canvas.drawColor(Color.WHITE)

        val destRect = RectF(0f, 0f, targetWidth.toFloat(), targetHeight.toFloat())

        // 1. 绘制绝对底层：静态背景
        bgLayer?.let { canvas.drawBitmap(it, null, destRect, filterPaint) }

        // 2. 绘制中间层与顶层 (根据 A/B 适配逻辑)
        if (isScaleBToA) {
            // B 适配 A
            layerB?.let { canvas.drawBitmap(it, null, destRect, filterPaint) }
            layerA?.let { canvas.drawBitmap(it, 0f, 0f, filterPaint) }
        } else {
            // A 适配 B (此处 A/B 均适配基准尺寸，逻辑保持一致)
            layerB?.let { canvas.drawBitmap(it, null, destRect, filterPaint) }
            layerA?.let { canvas.drawBitmap(it, null, destRect, filterPaint) }
        }

        ivResult.setImageBitmap(compositeBitmap)
        updateImageInfo(compositeBitmap!!)
    }

    private fun updateImageInfo(bitmap: Bitmap) {
        val width = bitmap.width
        val height = bitmap.height
        val byteCount = bitmap.allocationByteCount.toDouble()
        val mbSize = byteCount / (1024 * 1024)
        val format = bitmap.config?.name ?: "ARGB_8888"

        tvImageInfo.text = getString(R.string.image_info_template, width, height, mbSize, format)
    }

    private fun startAdvancedCropper(uri: Uri) {
        val options = CropImageOptions(
            guidelines = CropImageView.Guidelines.ON,
            activityTitle = getString(R.string.activity_title_crop_a),
            toolbarColor = Color.parseColor("#6200EE"),
            activityMenuIconColor = Color.WHITE,
            outputCompressFormat = Bitmap.CompressFormat.PNG
        )
        cropImageA.launch(CropImageContractOptions(uri = uri, cropImageOptions = options))
    }

    private fun extractFullSizeCroppedLayer(source: Bitmap, cropRect: Rect): Bitmap {
        val fullBitmap = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(fullBitmap)
        canvas.drawBitmap(source, cropRect, cropRect, filterPaint)
        return fullBitmap
    }

    private fun extractBackgroundLayer(source: Bitmap, cropRect: Rect): Bitmap {
        val bgBitmap = source.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(bgBitmap)
        canvas.drawRect(cropRect, clearPaint)
        return bgBitmap
    }

    private fun saveCurrentComposite() {
        val bitmap = compositeBitmap ?: return
        val filename = "Composite_${System.currentTimeMillis()}.png"
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/MyApplication")
            }
        }

        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
        uri?.let { 
            try {
                val outputStream: OutputStream? = contentResolver.openOutputStream(it)
                outputStream?.use { stream ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
                    showToast(getString(R.string.toast_save_success))
                }
            } catch (e: Exception) {
                e.printStackTrace()
                showToast(getString(R.string.toast_save_error, e.message ?: "Unknown"))
            }
        }
    }

    private fun loadBitmapFromUri(uri: Uri): Bitmap? {
        return try {
            val source = ImageDecoder.createSource(contentResolver, uri)
            ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                decoder.isMutableRequired = true
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun releaseLayerA() {
        layerA_Cropped?.recycle()
        layerA_Background?.recycle()
        layerA_Cropped = null
        layerA_Background = null
    }

    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        super.onDestroy()
        releaseLayerA()
        layerB_Direct?.recycle()
        layer_StaticBackground?.recycle()
        compositeBitmap?.recycle()
    }
}