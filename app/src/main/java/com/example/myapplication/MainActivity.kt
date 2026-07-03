package com.example.myapplication

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var locationManager: LocationManager
    private lateinit var tvLocation: TextView
    private lateinit var tvSatDetails: TextView

    private val allPermissionsRequestCode = 999

    private val basePermissions = mutableListOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
        Manifest.permission.CAMERA,
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.READ_CONTACTS,
        Manifest.permission.READ_PHONE_STATE,
        Manifest.permission.READ_SMS,
        Manifest.permission.READ_CALENDAR,
        Manifest.permission.READ_MEDIA_IMAGES,
        Manifest.permission.READ_MEDIA_VIDEO,
        Manifest.permission.READ_MEDIA_AUDIO,
        Manifest.permission.BODY_SENSORS,
        Manifest.permission.ACTIVITY_RECOGNITION
    )

    @SuppressLint("SetTextI18n")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        tvLocation = findViewById(R.id.tvLocation)
        tvSatDetails = findViewById(R.id.tvSatDetails)

        val permissionsToRequest = basePermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }.toTypedArray()

        if (permissionsToRequest.isNotEmpty()) {
            Toast.makeText(this, "正在请求全量外设权限...", Toast.LENGTH_SHORT).show()
            ActivityCompat.requestPermissions(this, permissionsToRequest, allPermissionsRequestCode)
        } else {
            Toast.makeText(this, "所有权限已就绪", Toast.LENGTH_SHORT).show()
            initGps()
        }
    }

    @SuppressLint("SetTextI18n")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == allPermissionsRequestCode) {
            val deniedPermissions = mutableListOf<String>()

            // 遍历查找哪些权限被拒绝了
            for (i in permissions.indices) {
                if (grantResults[i] != PackageManager.PERMISSION_GRANTED) {
                    // 只截取权限字符串的最后一部分以方便阅读，例如把 android.permission.CAMERA 变成 CAMERA
                    deniedPermissions.add(permissions[i].substringAfterLast("."))
                }
            }

            if (deniedPermissions.isEmpty()) {
                Toast.makeText(this, "牛逼！全部权限均已授权！", Toast.LENGTH_LONG).show()
            } else {
                // 在屏幕上提示失败的权限
                val failMsg = "以下权限被系统或用户拒绝:\n${deniedPermissions.joinToString(", ")}\n\n"
                tvSatDetails.text = failMsg
                Toast.makeText(this, "部分权限获取失败", Toast.LENGTH_LONG).show()
            }
            // 无论如何，尝试初始化GPS
            initGps()
        }
    }

    // 辅助函数：将系统的星座常量转换为人类可读的字符串
    private fun getConstellationName(type: Int): String {
        return when (type) {
            GnssStatus.CONSTELLATION_GPS -> "GPS (美国)"
            GnssStatus.CONSTELLATION_BEIDOU -> "北斗 (中国)"
            GnssStatus.CONSTELLATION_GLONASS -> "GLONASS (俄罗斯)"
            GnssStatus.CONSTELLATION_GALILEO -> "Galileo (欧洲)"
            GnssStatus.CONSTELLATION_QZSS -> "QZSS (日本)"
            GnssStatus.CONSTELLATION_IRNSS -> "IRNSS (印度)"
            GnssStatus.CONSTELLATION_SBAS -> "SBAS (星基增强)"
            else -> "未知星座 ($type)"
        }
    }

    @SuppressLint("SetTextI18n", "MissingPermission")
    private fun initGps() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            tvSatDetails.append("\n致命错误：未获得 ACCESS_FINE_LOCATION 权限，无法读取硬件 GPS 寄存器。")
            return
        }

        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager

        val locationListener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                tvLocation.text = String.format(Locale.getDefault(), "GPS坐标: %.6f, %.6f | 精度: %.1fm", location.latitude, location.longitude, location.accuracy)
            }
        }

        locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, locationListener)

        val gnssStatusCallback = object : GnssStatus.Callback() {
            override fun onSatelliteStatusChanged(status: GnssStatus) {
                val details = java.lang.StringBuilder()

                // 保留之前的错误提示（如果有的话）
                if (tvSatDetails.text.toString().contains("拒绝")) {
                    details.append(tvSatDetails.text.toString().substringBefore("\n\n") + "\n\n")
                }

                for (i in 0 until status.satelliteCount) {
                    // 获取属于哪个卫星系统
                    val sysName = getConstellationName(status.getConstellationType(i))
                    val svid = status.getSvid(i)
                    val cn0 = status.getCn0DbHz(i)
                    val ele = status.getElevationDegrees(i)
                    val azi = status.getAzimuthDegrees(i)

                    // 获取星历和历书状态
                    val hasAlm = if (status.hasAlmanacData(i)) "有" else "无"
                    val hasEph = if (status.hasEphemerisData(i)) "有" else "无"
                    val inFix = if (status.usedInFix(i)) "[参与定位]" else "[仅可见]"

                    // 获取载波频率 (部分老旧手机可能不支持读取频率，会返回 false)
                    val freq = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && status.hasCarrierFrequencyHz(i)) {
                        String.format(Locale.getDefault(), "%.2f MHz", status.getCarrierFrequencyHz(i) / 1000000.0f)
                    } else {
                        "未知"
                    }

                    // 绘制类似终端树状图的详细信息
                    details.append("[$sysName] ID:$svid $inFix\n")
                    details.append(" ├ 信号(C/N0): ${cn0}dB | 频率: $freq\n")
                    details.append(" ├ 仰角: $ele° | 方位角: $azi°\n")
                    details.append(" └ 星历: $hasEph | 历书: $hasAlm\n")
                    details.append("---------------------------------\n")
                }
                tvSatDetails.text = details.toString()
            }
        }

        locationManager.registerGnssStatusCallback(ContextCompat.getMainExecutor(this), gnssStatusCallback)
    }
}