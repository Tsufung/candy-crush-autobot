package com.candyrush.autobot

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.candyrush.autobot.service.CandyAccessibilityService
import com.candyrush.autobot.service.FloatingHomeService
import com.candyrush.autobot.service.ScreenCaptureService

class MainActivity : AppCompatActivity() {

    companion object {
        private const val REQUEST_MEDIA_PROJECTION = 2001
        private const val REQUEST_OVERLAY_PERMISSION = 2002
    }

    private lateinit var tvOverlayStatus: TextView
    private lateinit var tvAccessibilityStatus: TextView
    private lateinit var tvCaptureStatus: TextView
    private lateinit var btnGrantOverlay: Button
    private lateinit var btnGrantAccessibility: Button
    private lateinit var btnStartFloating: Button
    private lateinit var btnStopFloating: Button

    private var screenCaptureIntentData: Intent? = null
    private var screenCaptureResultCode: Int = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initViews()
        updatePermissionStates()
    }

    override fun onResume() {
        super.onResume()
        updatePermissionStates()
    }

    private fun initViews() {
        tvOverlayStatus = findViewById(R.id.tvOverlayStatus)
        tvAccessibilityStatus = findViewById(R.id.tvAccessibilityStatus)
        tvCaptureStatus = findViewById(R.id.tvCaptureStatus)
        btnGrantOverlay = findViewById(R.id.btnGrantOverlay)
        btnGrantAccessibility = findViewById(R.id.btnGrantAccessibility)
        btnStartFloating = findViewById(R.id.btnStartFloating)
        btnStopFloating = findViewById(R.id.btnStopFloating)

        btnGrantOverlay.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                startActivityForResult(intent, REQUEST_OVERLAY_PERMISSION)
            } else {
                Toast.makeText(this, "Quyền Nút Home Ảo đã được cấp!", Toast.LENGTH_SHORT).show()
            }
        }

        btnGrantAccessibility.setOnClickListener {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            startActivity(intent)
            Toast.makeText(this, "Tìm 'Candy Crush AutoBot' và Bật lên nhé!", Toast.LENGTH_LONG).show()
        }

        btnStartFloating.setOnClickListener {
            checkAndLaunchBot()
        }

        btnStopFloating.setOnClickListener {
            stopBotCompletely()
        }
    }

    private fun updatePermissionStates() {
        val hasOverlay = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(this)
        } else true

        val hasAccessibility = CandyAccessibilityService.isReady()
        val hasCapture = screenCaptureIntentData != null
        val isBotRunning = FloatingHomeService.isServiceRunning

        tvOverlayStatus.text = if (hasOverlay) "✅ Đã cấp quyền Nút Home ảo" else "❌ Chưa cấp quyền Nút Home ảo"
        tvAccessibilityStatus.text = if (hasAccessibility) "✅ Đã bật Dịch vụ Trợ Năng" else "❌ Chưa bật Dịch vụ Trợ Năng"
        tvCaptureStatus.text = if (isBotRunning) "🟢 Bot đang hiển thị trên màn hình" else if (hasCapture) "✅ Đã sẵn sàng chụp màn hình" else "⚪ Chưa bật"

        btnGrantOverlay.isEnabled = !hasOverlay
        btnGrantAccessibility.isEnabled = !hasAccessibility
        btnStopFloating.isEnabled = isBotRunning || hasCapture
    }

    private fun checkAndLaunchBot() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Vui lòng cấp quyền Hiển thị trên ứng dụng khác trước!", Toast.LENGTH_SHORT).show()
            return
        }

        if (!CandyAccessibilityService.isReady()) {
            Toast.makeText(this, "Vui lòng BẬT Dịch vụ Trợ năng trong Cài đặt!", Toast.LENGTH_SHORT).show()
            return
        }

        if (screenCaptureIntentData == null) {
            val mpManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            startActivityForResult(mpManager.createScreenCaptureIntent(), REQUEST_MEDIA_PROJECTION)
        } else {
            launchServices()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_MEDIA_PROJECTION) {
            if (resultCode == Activity.RESULT_OK && data != null) {
                screenCaptureResultCode = resultCode
                screenCaptureIntentData = data
                updatePermissionStates()
                launchServices()
            } else {
                Toast.makeText(this, "Bạn cần đồng ý chụp màn hình để bot nhận diện kẹo!", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun launchServices() {
        // Khởi chạy Screen Capture Service
        val capIntent = Intent(this, ScreenCaptureService::class.java).apply {
            putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, screenCaptureResultCode)
            putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, screenCaptureIntentData)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(capIntent)
        } else {
            startService(capIntent)
        }

        // Khởi chạy Floating Home Button Service
        val floatIntent = Intent(this, FloatingHomeService::class.java)
        startService(floatIntent)

        Toast.makeText(this, "🎉 Nút Home ảo đã xuất hiện! Hãy mở Candy Crush Saga và chạm nút để chơi!", Toast.LENGTH_LONG).show()

        // Ẩn app về Home để người dùng vào game
        val startMain = Intent(Intent.ACTION_MAIN)
        startMain.addCategory(Intent.CATEGORY_HOME)
        startMain.flags = Intent.FLAG_ACTIVITY_NEW_TASK
        startActivity(startMain)
    }

    private fun stopBotCompletely() {
        // Gửi lệnh tắt Floating Home Service
        val stopFloat = Intent(this, FloatingHomeService::class.java).apply {
            action = FloatingHomeService.ACTION_STOP_FLOATING
        }
        startService(stopFloat)

        // Dừng ScreenCaptureService
        val stopCap = Intent(this, ScreenCaptureService::class.java)
        stopService(stopCap)

        screenCaptureIntentData = null
        screenCaptureResultCode = 0

        Toast.makeText(this, "🛑 Đã tắt bot và đóng Nút Home ảo hoàn toàn!", Toast.LENGTH_SHORT).show()
        updatePermissionStates()
    }
}
