package com.example.myapp

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.bumptech.glide.Glide
import com.example.myapp.overlay.OverlayActivity
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Photos are private app files, separated by warehouse; no server upload. */
class WarehousePhotos : AppCompatActivity() {
    private var warehouse = 1
    private var page = "select"
    private var capture: ImageCapture? = null
    private var provider: ProcessCameraProvider? = null
    private var cameraGeneration = 0
    private lateinit var content: LinearLayout
    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { allowed ->
        if (allowed) camera() else {
            Toast.makeText(this, "카메라 권한을 허용하면 촬영할 수 있어요.", Toast.LENGTH_LONG).show()
            gallery()
        }
    }
    private fun folder() = File(filesDir, "warehouse_photos/$warehouse").apply { mkdirs() }
    private fun photos() = folder().listFiles()?.filter { it.extension == "jpg" && it.length() > 0 }
        ?.sortedByDescending { it.lastModified() } ?: emptyList()
    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        warehouse = savedInstanceState?.getInt("warehouse", 1) ?: warehousePrefs.getInt("selected", 1)
        // Added warehouse3 sample
        val thirdPhoto = File(filesDir, "warehouse_photos/3/warehouse3.jpg")
        if (!thirdPhoto.exists()) {
            thirdPhoto.parentFile?.mkdirs()
            resources.openRawResource(R.drawable.warehouse3).use { input ->
                thirdPhoto.outputStream().use { output -> input.copyTo(output) }
            }
        }
        if (warehousePrefs.getInt("count", 3) < 3) {
            warehousePrefs.edit().putInt("count", 3).apply()
        }

        val sample = File(filesDir, "warehouse_photos/1/warehouse_sample.jpg")
        if (!sample.exists()) {
            sample.parentFile?.mkdirs()
            resources.openRawResource(R.drawable.warehouse).use { input -> sample.outputStream().use { input.copyTo(it) } }
        }
        if (savedInstanceState?.getString("page") == "gallery") gallery() else select()
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt("warehouse", warehouse)
        outState.putString("page", if (page == "select") "select" else "gallery")
        super.onSaveInstanceState(outState)
    }
    private fun layout(title: String, scroll: Boolean = true) {
        cameraGeneration++; provider?.unbindAll(); capture = null
        val shell = LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL; setBackgroundColor(Color.rgb(248,250,252))
        }
        val header = LinearLayout(this).apply {
            orientation=LinearLayout.HORIZONTAL; gravity=android.view.Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.WHITE); setPadding(dp(8),0,dp(16),0)
        }
        if (page != "select") header.addView(TextView(this).apply {
            text="←"; textSize=24f; includeFontPadding=false; setTextColor(Color.rgb(15,23,42))
            gravity=android.view.Gravity.CENTER; contentDescription="뒤로가기"
            setOnClickListener { onBackPressed() }
        },LinearLayout.LayoutParams(dp(48),dp(48)))
        if (page == "select") header.addView(ImageView(this).apply {
            setImageResource(android.R.drawable.star_big_on)
            setPadding(dp(12),dp(12),dp(12),dp(12))
            contentDescription="홈으로"
            setOnClickListener {
                startActivity(Intent(this@WarehousePhotos, HomeDashboard::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                })
                finish()
            }
        },LinearLayout.LayoutParams(dp(48),dp(48)))
        header.addView(TextView(this).apply {
            text=title; textSize=18f; includeFontPadding=false; setTextColor(Color.rgb(15,23,42))
            typeface=android.graphics.Typeface.DEFAULT_BOLD
        },LinearLayout.LayoutParams(0,-2,1f))
        shell.addView(header,LinearLayout.LayoutParams(-1,dp(56)))
        shell.addView(View(this).apply { setBackgroundColor(Color.rgb(226,232,240)) },LinearLayout.LayoutParams(-1,dp(1)))
        content=LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL; setPadding(dp(16),dp(18),dp(16),dp(24))
        }
        if(scroll) shell.addView(ScrollView(this).apply { isFillViewport=true; addView(content) },LinearLayout.LayoutParams(-1,0,1f))
        else shell.addView(content,LinearLayout.LayoutParams(-1,0,1f))
        setContentView(shell)
    }
    private fun button(label: String, action: () -> Unit): Button = Button(this).apply {
        text = label; setOnClickListener { action() }; content.addView(this, LinearLayout.LayoutParams(-1,-2))
    }
    private val warehousePrefs by lazy { getSharedPreferences("warehouse_catalog", MODE_PRIVATE) }
    private fun warehouseName() = warehousePrefs.getString("name_$warehouse", "창고 $warehouse") ?: "창고 $warehouse"
    private fun rounded(color: Int) = android.graphics.drawable.GradientDrawable().apply {
        setColor(color); cornerRadius = dp(16).toFloat(); setStroke(dp(1),Color.rgb(221,230,239))
    }
    private fun label(value: String, size: Float, color: Int = Color.rgb(26,43,62)) = TextView(this).apply {
        text = value; textSize = size; setTextColor(color)
        setPadding(dp(4),dp(8),dp(4),dp(8))
    }
    private fun chooseWarehouse(anchor: View) {
        val menu = PopupMenu(this, anchor)
        val count = warehousePrefs.getInt("count", 3)
        for (id in 1..count) menu.menu.add(0,id,id,warehousePrefs.getString("name_$id", "창고 $id"))
        menu.menu.add(0,0,count+1,"＋ 창고 추가")
        menu.setOnMenuItemClickListener { item ->
            if (item.itemId != 0) {
                warehouse = item.itemId
                warehousePrefs.edit().putInt("selected",warehouse).apply()
                select()
            } else {
                val input = EditText(this).apply { hint = "창고 이름"; setSingleLine(true) }
                val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle("새 창고 만들기").setView(input)
                    .setNegativeButton("취소",null).setPositiveButton("추가",null).create()
                dialog.setOnShowListener {
                    dialog.getButton(-1).setOnClickListener {
                        val name = input.text.toString().trim()
                        if (name.isBlank()) { input.error = "이름을 입력해주세요"; return@setOnClickListener }
                        if ((1..count).any { warehousePrefs.getString("name_$it", "창고 $it") == name }) {
                            input.error = "이미 있는 창고 이름이에요"; return@setOnClickListener
                        }
                        warehouse = count+1
                        warehousePrefs.edit().putInt("count",warehouse).putString("name_$warehouse",name)
                            .putInt("selected",warehouse).apply()
                        folder(); dialog.dismiss(); select()
                    }
                }
                dialog.show()
            }
            true
        }
        menu.show()
    }
    private fun renameWarehouse() {
        val input = EditText(this).apply {
            inputType=android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setText(warehouseName()); setSingleLine(true); selectAll()
            imeOptions=android.view.inputmethod.EditorInfo.IME_ACTION_DONE
        }
        val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("창고 이름 수정").setView(input)
            .setNegativeButton("취소",null).setPositiveButton("저장",null).create()
        dialog.setOnShowListener {
            dialog.getButton(-1).setOnClickListener {
                val name = input.text.toString().trim()
                if (name.isBlank()) { input.error="이름을 입력해주세요"; return@setOnClickListener }
                if ((1..warehousePrefs.getInt("count",3)).any {
                    it != warehouse && warehousePrefs.getString("name_$it","창고 $it") == name
                }) { input.error="이미 있는 창고 이름이에요"; return@setOnClickListener }
                warehousePrefs.edit().putString("name_$warehouse",name).apply()
                dialog.dismiss(); select()
            }
        }
        dialog.show()
        input.requestFocus()
        dialog.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
    }
    private fun select() {
        page = "select"; layout("AI 재고실사")
        content.addView(label("창고별 촬영 기록을 한곳에서 관리하세요",14f,Color.rgb(104,120,139)))
        val chooser = label(warehouseName() + "    ▾",21f).apply {
            background = rounded(Color.WHITE)
            setPadding(dp(20),dp(18),dp(20),dp(18))
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setOnClickListener { chooseWarehouse(this) }
        }
        content.addView(chooser,LinearLayout.LayoutParams(-1,-2).apply { topMargin=dp(16); bottomMargin=dp(4) })
        content.addView(label("창고 이름 수정",15f,Color.rgb(13,148,136)).apply {
            gravity=android.view.Gravity.END
            setPadding(dp(8),dp(12),dp(8),dp(16))
            setOnClickListener { renameWarehouse() }
        })
        val row = LinearLayout(this)
        fun tile(title: String, subtitle: String, action: () -> Unit): LinearLayout {
            return LinearLayout(this).apply {
                orientation=LinearLayout.VERTICAL
                background=rounded(Color.WHITE)
                setPadding(dp(16),dp(20),dp(16),dp(20))
                elevation=0f
                gravity=android.view.Gravity.CENTER
                minimumHeight=dp(160)
                addView(label(title,24f).apply {
                    gravity=android.view.Gravity.CENTER
                    typeface=android.graphics.Typeface.DEFAULT_BOLD
                })
                addView(label(subtitle,13f,Color.rgb(104,120,139)).apply { gravity=android.view.Gravity.CENTER })
                setOnClickListener { action() }
            }
        }
        row.addView(tile("촬영하기","새 사진 기록") { camera() },LinearLayout.LayoutParams(0,-2,1f).apply { rightMargin=dp(8) })
        row.addView(tile("사진 목록","저장된 사진 " + photos().size + "장") { gallery() },LinearLayout.LayoutParams(0,-2,1f).apply { leftMargin=dp(8) })
        content.addView(row)
        content.addView(label("선택한 창고에 사진이 저장됩니다",13f,Color.rgb(104,120,139)))
    }
    private fun gallery() {
        page = "gallery"; layout(warehouseName() + " · 사진 목록")
        button("＋ 새 사진 촬영") { camera() }
        button("창고 선택으로") { select() }
        val files = photos()
        content.addView(label("전체 " + files.size + "장",15f))
        if (files.isEmpty()) content.addView(label("아직 사진이 없어요. 첫 사진을 촬영해보세요.",16f))
        files.chunked(2).forEach { pair ->
            val row = LinearLayout(this)
            pair.forEach { file ->
                val card = LinearLayout(this).apply {
                    orientation=LinearLayout.VERTICAL; background=rounded(Color.WHITE)
                    clipToOutline=true; setOnClickListener { detail(file) }
                }
                val photo = ImageView(this).apply {
                    scaleType=ImageView.ScaleType.CENTER_CROP
                    contentDescription=warehouseName() + " 저장 사진"
                }
                card.addView(photo,LinearLayout.LayoutParams(-1,dp(150)))
                Glide.with(this).load(file).into(photo)
                card.addView(label(if(file.name=="warehouse_sample.jpg") "기본 사진 · 위치 검수" else
                    SimpleDateFormat("MM.dd HH:mm",Locale.KOREA).format(Date(file.lastModified())),13f))
                card.addView(label(com.example.myapp.overlay.InspectionCase(warehouse, file.name).status(this),13f,Color.rgb(13,148,136)))
                row.addView(card,LinearLayout.LayoutParams(0,-2,1f).apply { setMargins(dp(4),dp(8),dp(4),dp(8)) })
            }
            if(pair.size==1) row.addView(View(this),LinearLayout.LayoutParams(0,1,1f))
            content.addView(row)
        }
    }
    private fun detail(file: File) {
        val inspection = com.example.myapp.overlay.InspectionCase(warehouse, file.name)
        startActivity(inspection.attach(Intent(this, OverlayActivity::class.java)))
    }
    override fun onResume() {
        super.onResume()
        if (page == "gallery") gallery()
    }
    private fun confirmPhotoWarehouse(file: File) {
        // This is a capture confirmation, not an AI mismatch verdict.
        gallery()
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("창고 위치 확인")
            .setMessage("선택한 창고 ‘${warehouseName()}’가 맞는지 다시 확인해주세요.\n다른 창고에서 촬영했다면 위치를 다시 선택해주세요.")
            .setCancelable(false)
            .setNegativeButton("무시") { _, _ -> gallery() }
            .setPositiveButton("위치 재선택") { _, _ -> reselectPhotoWarehouse(file) }
            .show()
    }
    private fun reselectPhotoWarehouse(file: File) {
        val ids = (1..warehousePrefs.getInt("count",3)).toList()
        val names = ids.map { warehousePrefs.getString("name_$it","창고 $it") ?: "창고 $it" }.toTypedArray()
        var selected = ids.indexOf(warehouse).coerceAtLeast(0)
        val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("사진을 저장할 창고")
            .setSingleChoiceItems(names,selected) { _, index -> selected=index }
            .setNegativeButton("취소") { _, _ -> confirmPhotoWarehouse(file) }
            .setPositiveButton("선택한 창고로 저장",null)
            .setCancelable(false).create()
        dialog.setOnShowListener {
            dialog.getButton(-1).setOnClickListener {
                val id=ids[selected]
                if(id==warehouse) { dialog.dismiss(); gallery(); return@setOnClickListener }
                val targetFolder=File(filesDir,"warehouse_photos/$id")
                val target=File(targetFolder,file.name)
                // Internal storage is on the same filesystem. Rename avoids partial copies.
                if((targetFolder.isDirectory || targetFolder.mkdirs()) && !target.exists() && file.renameTo(target)) {
                    warehouse=id
                    warehousePrefs.edit().putInt("selected",id).apply()
                    dialog.dismiss(); gallery()
                    Toast.makeText(this,"사진의 창고 위치를 변경했어요.",Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this,"이동하지 못했어요. 원래 창고에 사진을 보관했습니다.",Toast.LENGTH_LONG).show()
                }
            }
        }
        dialog.show()
    }
    private fun camera() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            permission.launch(Manifest.permission.CAMERA); return
        }
        page = "camera"; layout(warehouseName() + " · 카메라", false)
        val previewView = PreviewView(this)
        content.addView(previewView, LinearLayout.LayoutParams(-1,0,1f))
        val shutter = button("촬영하고 저장") { }
        shutter.isEnabled = false
        if (warehouse == 1) button("기존 창고 사진으로 검수") { detail(File(folder(),"warehouse_sample.jpg")) }
        button("저장 사진 목록") { gallery() }
        val generation = cameraGeneration
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            if (isDestroyed || page != "camera" || generation != cameraGeneration) return@addListener
            try {
                val cp = future.get(); provider = cp
                val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
                val shot = ImageCapture.Builder().setTargetRotation(previewView.display.rotation).build()
                cp.unbindAll()
                cp.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, shot)
                capture = shot; shutter.isEnabled = true
                shutter.setOnClickListener {
                    shutter.isEnabled = false
                    val file = File(folder(), "photo_${java.util.UUID.randomUUID()}.jpg")
                    shot.takePicture(ImageCapture.OutputFileOptions.Builder(file).build(), ContextCompat.getMainExecutor(this),
                        object : ImageCapture.OnImageSavedCallback {
                            override fun onImageSaved(result: ImageCapture.OutputFileResults) {
                                if (!isDestroyed && generation == cameraGeneration) confirmPhotoWarehouse(file)
                            }
                            override fun onError(error: ImageCaptureException) {
                                file.delete()
                                if (!isDestroyed && generation == cameraGeneration) {
                                    shutter.isEnabled = true
                                    Toast.makeText(this@WarehousePhotos,"사진 저장 실패. 다시 촬영해주세요.",Toast.LENGTH_LONG).show()
                                }
                            }
                        })
                }
            } catch (e: Exception) {
                Toast.makeText(this,"카메라를 열 수 없어요. 저장 사진을 확인하거나 다시 시도해주세요.",Toast.LENGTH_LONG).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }
    @Deprecated("Legacy back handling")
    override fun onBackPressed() {
        when(page) { "select" -> super.onBackPressed(); "detail", "camera" -> gallery(); else -> select() }
    }
    override fun onDestroy() { cameraGeneration++; provider?.unbindAll(); super.onDestroy() }
}