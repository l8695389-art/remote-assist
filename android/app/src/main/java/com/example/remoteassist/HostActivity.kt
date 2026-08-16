package com.example.remoteassist

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.os.CountDownTimer
import android.os.IBinder
import android.provider.Settings
import android.text.TextUtils
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.example.remoteassist.databinding.ActivityHostBinding
import com.example.remoteassist.service.RemoteControlAccessibilityService
import com.example.remoteassist.service.ScreenCaptureService

class HostActivity : AppCompatActivity(), ScreenCaptureService.StatusListener {

    private lateinit var binding: ActivityHostBinding
    private var service: ScreenCaptureService? = null
    private var bound = false
    private var controllerJoined = false
    private var countDownTimer: CountDownTimer? = null

    private val mediaProjectionManager by lazy {
        getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
    }

    // Launches the mandatory system consent dialog. Capture only begins if resultCode == RESULT_OK.
    private val projectionLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            val intent = Intent(this, ScreenCaptureService::class.java).apply {
                action = ScreenCaptureService.ACTION_START
                putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, result.data)
            }
            startService(intent)
            binding.btnStartShare.isEnabled = false
            binding.btnStopShare.isEnabled = true
        } else {
            binding.tvStatus.text = "Bạn đã từ chối cấp quyền chia sẻ màn hình"
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = (binder as ScreenCaptureService.LocalBinder).service()
            service?.setStatusListener(this@HostActivity)
            bound = true
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            bound = false
            service = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHostBinding.inflate(layoutInflater)
        setContentView(binding.root)

        checkAccessibilityEnabled()

        binding.btnStartShare.setOnClickListener {
            projectionLauncher.launch(mediaProjectionManager.createScreenCaptureIntent())
        }
        binding.btnStopShare.setOnClickListener {
            service?.stopSharingAndSelf()
        }
        binding.btnDisconnect.setOnClickListener {
            service?.stopSharingAndSelf()
            finish()
        }

        ScreenCaptureService.beginPairing(this)
        val intent = Intent(this, ScreenCaptureService::class.java)
        bindService(intent, connection, Context.BIND_AUTO_CREATE)

        binding.tvStatus.text = getString(R.string.status_connecting)
    }

    private fun checkAccessibilityEnabled() {
        if (!isAccessibilityServiceEnabled()) {
            binding.tvAccessibilityWarning.visibility = android.view.View.VISIBLE
            binding.tvAccessibilityWarning.text = getString(R.string.dialog_accessibility_required_msg)
            AlertDialog.Builder(this)
                .setTitle(R.string.dialog_accessibility_required_title)
                .setMessage(R.string.dialog_accessibility_required_msg)
                .setPositiveButton(R.string.dialog_open_settings) { _, _ ->
                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }
                .setNegativeButton(R.string.dialog_cancel, null)
                .show()
        }
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val expected = ComponentName(this, RemoteControlAccessibilityService::class.java).flattenToString()
        val enabledServices = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val splitter = TextUtils.SimpleStringSplitter(':')
        splitter.setString(enabledServices)
        for (component in splitter) {
            if (component.equals(expected, ignoreCase = true)) return true
        }
        return false
    }

    // ---------------- ScreenCaptureService.StatusListener ----------------

    override fun onPairingCode(code: String, expiresInSeconds: Long) {
        runOnUiThread {
            binding.tvPairingCode.text = code
            binding.tvStatus.text = getString(R.string.status_waiting_controller)
            countDownTimer?.cancel()
            countDownTimer = object : CountDownTimer(expiresInSeconds * 1000, 1000) {
                override fun onTick(msLeft: Long) {
                    if (!controllerJoined) {
                        binding.tvCodeExpiry.text = "Mã hết hạn sau ${msLeft / 1000}s"
                    }
                }
                override fun onFinish() {
                    if (!controllerJoined) {
                        binding.tvCodeExpiry.text = "Mã đã hết hạn"
                        binding.tvPairingCode.text = "------"
                    }
                }
            }.start()
        }
    }

    override fun onControllerJoined() {
        runOnUiThread {
            controllerJoined = true
            countDownTimer?.cancel()
            binding.tvCodeExpiry.text = ""
            binding.tvStatus.text = getString(R.string.status_connected)
            binding.btnStartShare.isEnabled = true
        }
    }

    override fun onSharingStarted() {
        runOnUiThread {
            binding.tvStatus.text = getString(R.string.status_sharing_active)
            binding.btnStopShare.isEnabled = true
            binding.btnStartShare.isEnabled = false
        }
    }

    override fun onSharingStopped() {
        runOnUiThread {
            binding.tvStatus.text = getString(R.string.status_idle)
            binding.btnStartShare.isEnabled = controllerJoined
            binding.btnStopShare.isEnabled = false
        }
    }

    override fun onError(message: String) {
        runOnUiThread { binding.tvStatus.text = message }
    }

    override fun onDestroy() {
        countDownTimer?.cancel()
        if (bound) {
            service?.setStatusListener(null)
            unbindService(connection)
            bound = false
        }
        super.onDestroy()
    }
}
