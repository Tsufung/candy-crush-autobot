package com.candyrush.autobot

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
        private const val REQUEST_OVERLAY_PERMISSION = 2002
        private const val REQUEST_MEDIA_PROJECTION = 2003
    }

    private lateinit var tvOverlayStatus: TextView
    private lateinit var tvAccessibilityStatus: TextView
    private lateinit var tvCaptureStatus: TextView
    private lateinit var btnGrantOverlay: Button
    private lateinit var btnGrantAccessibility: Button
    private lateinit var btnStartCapture: Button
    private lateinit var btnStartFloating: Button
    private lateinit var btnStopFloating: Button

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
        btnStartCapture = findViewById(R.id.btnStartCapture)
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

        btnStartCapture.setOnClickListener {
            if (ScreenCaptureService.isRunning()) {
                Toast.makeText(this, "Chụp màn hình MediaProjection đã đang chạy!", Toast.LENGTH_SHORT).show()
            } else {
                val mpManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                startActivityForResult(mpManager.createScreenCaptureIntent(), REQUEST_MEDIA_PROJECTION)
            }
        }

        btnStartFloating.setOnClickListener {
            checkAndLaunchBot()
        }

        btnStopFloating.setOnClickListener {
            stopBotCompletely()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_MEDIA_PROJECTION && resultCode == RESULT_OK && data != null) {
            val captureIntent = Intent(this, ScreenCaptureService::class.java).apply {
                putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, resultCode)
                putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, data)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(captureIntent)
            } else {
                startService(captureIntent)
            }
            Toast.makeText(this, "✅ Đã bật Chụp Màn Hình HD thành công!", Toast.LENGTH_SHORT).show()
            updatePermissionStates()
        }
    }

    private fun updatePermissionStates() {
        val hasOverlay = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(this)
        } else true

        val hasAccessibility = CandyAccessibilityService.isReady()
        val isCaptureRunning = ScreenCaptureService.isRunning()
        val isBotRunning = FloatingHomeService.isServiceRunning

        tvOverlayStatus.text = if (hasOverlay) "✅ Đã cấp quyền Nút Home ảo" else "❌ Chưa cấp quyền Nút Home ảo"
        tvAccessibilityStatus.text = if (hasAccessibility) "✅ Đã bật Dịch vụ Trợ Năng" else "❌ Chưa bật Dịch vụ Trợ Năng"
        tvCaptureStatus.text = if (isCaptureRunning) "✅ Đã bật Chụp Màn Hình HD" else "⚪ Chưa bật Chụp Màn Hình HD (Tự dùng Trợ năng ngầm)"

        btnGrantOverlay.isEnabled = !hasOverlay
        btnGrantAccessibility.isEnabled = !hasAccessibility
        btnStartCapture.isEnabled = !isCaptureRunning
        btnStopFloating.isEnabled = isBotRunning
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

        launchFloatingService()
    }

    private fun launchFloatingService() {
        val floatIntent = Intent(this, FloatingHomeService::class.java)
        startService(floatIntent)

        Toast.makeText(this, "🎉 Nút Home ảo đã xuất hiện! Hãy mở Candy Crush Saga và chạm nút để chơi!", Toast.LENGTH_LONG).show()

        val startMain = Intent(Intent.ACTION_MAIN)
        startMain.addCategory(Intent.CATEGORY_HOME)
        startMain.flags = Intent.FLAG_ACTIVITY_NEW_TASK
        startActivity(startMain)
    }

    private fun stopBotCompletely() {
        val stopFloat = Intent(this, FloatingHomeService::class.java).apply {
            action = FloatingHomeService.ACTION_STOP_FLOATING
        }
        startService(stopFloat)

        // Tắt luôn ScreenCapture nếu đang chạy
        val stopCapture = Intent(this, ScreenCaptureService::class.java)
        stopService(stopCapture)

        Toast.makeText(this, "🛑 Đã tắt bot và đóng Nút Home ảo hoàn toàn!", Toast.LENGTH_SHORT).show()
        updatePermissionStates()
    }
}
