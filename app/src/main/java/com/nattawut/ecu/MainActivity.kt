package com.nattawut.ecu

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.*
import java.io.BufferedReader
import java.io.InputStreamReader
import java.security.MessageDigest
import java.util.UUID
import kotlin.concurrent.thread
import kotlin.math.max

class MainActivity : Activity() {
    private val sppUuid = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    private var socket: BluetoothSocket? = null
    private var readerThread: Thread? = null
    private var logging = false
    private val logRows = mutableListOf<String>()
    private val samples = mutableListOf<Float>()
    private val histories = mutableMapOf<String, MutableList<Float>>()
    private var receivedPackets = 0L
    private var lastPacketAt = 0L
    private val mainHandler = Handler(Looper.getMainLooper())
    private var telemetryTimedOut = true
    private val telemetry = linkedMapOf(
        "RPM" to "0", "TPS" to "0", "ECT" to "0", "IAT" to "0",
        "BAT" to "0", "O2" to "0", "AFR" to "0", "SPEED" to "0",
        "INJ" to "0", "IGN" to "0", "MAP" to "0", "STFT" to "0", "LTFT" to "0"
    )
    private var latestRawFrame = "ยังไม่ได้รับ RAW DATA จาก ECU"
    private var rawDataView: TextView? = null
    private val units = mapOf("RPM" to "rpm", "TPS" to "%", "ECT" to "°C", "IAT" to "°C",
        "BAT" to "V", "O2" to "V", "AFR" to "AFR", "SPEED" to "km/h",
        "INJ" to "ms", "IGN" to "deg", "MAP" to "kPa", "STFT" to "%", "LTFT" to "%")
    private lateinit var root: LinearLayout
    private lateinit var body: LinearLayout
    private lateinit var status: TextView
    private lateinit var connectBtn: Button
    private lateinit var graph: LiveGraph
    private var activePage = "DASHBOARD"
    private var selectedRomName = "ยังไม่ได้เลือกไฟล์ BIN"
    private var selectedRomSize = 0
    private var selectedRomSha256 = "-"
    private var selectedRomValid = false
    private var remapHistory = mutableListOf<String>()

    private val bg = Color.rgb(8, 12, 21)
    private val panel = Color.rgb(20, 29, 44)
    private val cyan = Color.rgb(43, 220, 235)
    private val white = Color.WHITE
    private val muted = Color.rgb(155, 170, 190)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = bg
        window.navigationBarColor = Color.BLACK
        buildShell()
        requestBtPermission()
        showPage("DASHBOARD")
        startTelemetryWatchdog()
    }

    private fun startTelemetryWatchdog() {
        mainHandler.post(object : Runnable {
            override fun run() {
                val stale = lastPacketAt == 0L ||
                    System.currentTimeMillis() - lastPacketAt > 1500L
                if (stale && !telemetryTimedOut) {
                    telemetryTimedOut = true
                    synchronized(telemetry) { telemetry.keys.forEach { telemetry[it] = "0" } }
                    if (activePage == "DASHBOARD") updateDashboardValues()
                    if (socket != null) {
                        status.text = "● CONNECTED • NO TELEMETRY"
                        status.setTextColor(Color.YELLOW)
                    }
                }
                mainHandler.postDelayed(this, 300L)
            }
        })
    }

    private fun buildShell() {
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            // Android 15 edge-to-edge: keep the content below the system status bar.
            setPadding(dp(12), 0, dp(12), dp(28))
            setBackgroundColor(bg)
        }
        val header = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.setOnApplyWindowInsetsListener { view, insets ->
            header.setPadding(0, insets.systemWindowInsetTop + dp(8), 0, 0)
            view.setPadding(dp(12), 0, dp(12), dp(28))
            insets
        }
        header.addView(label("NATTWUT SMARTGAUGE", 23f, cyan, true))
        header.addView(label("HONDA PGM-FI  •  BLUETOOTH CLASSIC SPP", 10f, muted, true))
        status = label("● DISCONNECTED", 12f, Color.rgb(255, 100, 110), true)
        status.setPadding(0, 10, 0, 8)
        header.addView(status)
        root.addView(header)
        val controls = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        connectBtn = Button(this).apply { text = "CONNECT"; setOnClickListener { if (socket == null) showPairedDevices() else disconnect() } }
        controls.addView(connectBtn, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(controls)
        val navScroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        val nav = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf("DASHBOARD", "GRAPH", "LOGGER", "DTC", "ECU", "MAP", "REMAP", "CONTROL", "SETTINGS").forEach { page ->
            val b = Button(this).apply {
                text = page
                textSize = 10f
                setOnClickListener { showPage(page) }
            }
            nav.addView(b)
        }
        navScroll.addView(nav)
        root.addView(navScroll)
        val scroll = ScrollView(this)
        body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 10, 0, 8) }
        scroll.addView(body)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(label("READ-ONLY ECU TELEMETRY  •  ECU WRITE / REMAP DISABLED", 9f, muted, false).apply {
            gravity = Gravity.CENTER; setPadding(0, 8, 0, 4)
        })
        setContentView(root)
    }

    private fun label(text: String, size: Float, color: Int, bold: Boolean): TextView =
        TextView(this).apply {
            this.text = text; textSize = size; setTextColor(color)
            if (bold) typeface = Typeface.DEFAULT_BOLD
        }

    private fun showPage(page: String) {
        activePage = page
        body.removeAllViews()
        when (page) {
            "DASHBOARD" -> dashboard()
            "GRAPH" -> graphPage()
            "LOGGER" -> loggerPage()
            "DTC" -> dtcPage()
            "ECU" -> ecuPage()
            "MAP" -> mapPage()
            "REMAP" -> remapPage()
            "CONTROL" -> controlPage()
            "SETTINGS" -> settingsPage()
        }
    }

    // Convert dp to physical pixels. LayoutParams dimensions are pixels, not dp.
    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density + 0.5f).toInt()

    private fun card(key: String): LinearLayout {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(8), dp(12), dp(8), dp(5))
            setBackgroundColor(panel)
            minimumHeight = dp(116)
        }

        // Keep the title in its own top row so it cannot collide with the value.
        val title = label(key, 13f, cyan, true).apply {
            gravity = Gravity.CENTER
            includeFontPadding = false
        }
        box.addView(title, LinearLayout.LayoutParams(-1, dp(25)))

        // The value receives the remaining card height and is vertically centered.
        val value = label(telemetry[key] ?: "0", 28f, white, true).apply {
            tag = "value:$key"
            gravity = Gravity.CENTER
            maxLines = 1
            includeFontPadding = false
            isSingleLine = true
        }
        box.addView(value, LinearLayout.LayoutParams(-1, 0, 1f))

        val unit = label(units[key] ?: "", 10f, muted, false).apply {
            gravity = Gravity.CENTER
            includeFontPadding = false
        }
        box.addView(unit, LinearLayout.LayoutParams(-1, dp(17)))
        return box
    }

    private fun rawDataPanel(): LinearLayout {
        val panelBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(10, 9, 10, 9)
            setBackgroundColor(panel)
        }
        panelBox.addView(label("RAW ECU DATA", 13f, cyan, true))
        val raw = label(latestRawFrame, 10f, white, false).apply {
            tag = "latest_raw_data"
            setTextIsSelectable(true)
            maxLines = 5
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        rawDataView = raw
        panelBox.addView(raw, LinearLayout.LayoutParams(-1, -2))
        return panelBox
    }

    private fun dashboard() {
        body.addView(label("LIVE ENGINE DATA", 16f, white, true).apply {
            setPadding(dp(2), dp(2), dp(2), dp(4))
            includeFontPadding = false
        })

        val keys = listOf(
            "RPM", "TPS", "ECT",
            "IAT", "BAT", "AFR",
            "MAP", "SPEED", "INJ",
            "IGN", "O2", "STFT",
            "LTFT"
        )
        val cardHeight = dp(116)
        keys.chunked(3).forEach { group ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.TOP
            }
            group.forEach { key ->
                row.addView(card(key), LinearLayout.LayoutParams(0, cardHeight, 1f).apply {
                    setMargins(dp(3), dp(4), dp(3), dp(4))
                })
            }
            repeat(3 - group.size) {
                row.addView(View(this), LinearLayout.LayoutParams(0, cardHeight, 1f).apply {
                    setMargins(dp(3), dp(4), dp(3), dp(4))
                })
            }
            body.addView(row, LinearLayout.LayoutParams(-1, cardHeight + dp(8)))
        }

        body.addView(
            rawDataPanel(),
            LinearLayout.LayoutParams(-1, -2).apply {
                setMargins(dp(3), dp(8), dp(3), dp(6))
            }
        )
        body.addView(label(
            "ค่าที่ ECU ไม่ส่งจะแสดง 0 เป็นค่าเริ่มต้น; RAW จะแสดงเมื่อได้รับเฟรมจริง",
            10f, muted, false
        ).apply {
            setPadding(dp(2), dp(2), dp(2), dp(8))
        })
    }

    private fun graphPage() {
        body.addView(label("REAL-TIME GRAPH", 16f, white, true))
        val picker = Spinner(this)
        val options = arrayOf("RPM", "TPS", "ECT", "BAT", "SPEED")
        picker.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, options)
        body.addView(picker)
        graph = LiveGraph(this)
        body.addView(graph, LinearLayout.LayoutParams(-1, 260))
        picker.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                graph.metric = options[position]; graph.invalidate()
            }
        }
    }

    private fun loggerPage() {
        body.addView(label("DATA LOGGER", 16f, white, true))
        body.addView(label("บันทึกทั้ง RAW ECU DATA และ LIVE ENGINE DATA ลง CSV ไฟล์เดียว", 12f, muted, false))
        body.addView(rawDataPanel(), LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, 6, 0, 8) })
        val start = Button(this).apply { text = if (logging) "STOP LOGGING" else "START LOGGING" }
        val export = Button(this).apply { text = "EXPORT CSV" }
        start.setOnClickListener { logging = !logging; start.text = if (logging) "STOP LOGGING" else "START LOGGING"; toast(if (logging) "เริ่มบันทึกแล้ว" else "หยุดบันทึกแล้ว") }
        export.setOnClickListener {
            if (logRows.isEmpty()) toast("ยังไม่มีข้อมูลสำหรับส่งออก") else {
                val csv = buildString {
                    append("record_type,time_ms,raw_table,raw_length,raw_hex,")
                    append(telemetry.keys.joinToString(",")).append('\n')
                    logRows.forEach { append(it).append('\n') }
                }
                val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE); type = "text/csv"; putExtra(Intent.EXTRA_TITLE, "SmartGauge_Log.csv")
                }
                pendingCsv = csv
                startActivityForResult(intent, 77)
            }
        }
        body.addView(start); body.addView(export)
        val count = label("จำนวนรายการที่บันทึก: ${synchronized(logRows) { logRows.size }}", 13f, cyan, true)
        count.tag = "logger_count"
        body.addView(count)
    }

    private fun updateLoggerCount() {
        val count = body.findViewWithTag<TextView>("logger_count")
        count?.text = "จำนวนรายการที่บันทึก: ${synchronized(logRows) { logRows.size }}"
    }

    private fun dtcPage() {
        body.addView(label("ECU DIAGNOSTIC TROUBLE CODES", 16f, white, true))
        body.addView(label("เฟิร์มแวร์ที่แนบมายังไม่มีคำสั่ง DTC ผ่าน Bluetooth SPP", 12f, muted, false))
        body.addView(label("DTC READ / CLEAR ถูกพักไว้จนกว่าจะเพิ่มและทดสอบคำสั่งควบคุมฝั่ง ESP32 เพื่อป้องกันการส่งคำสั่งที่ไม่รองรับ", 12f, Color.YELLOW, false))
        val read = Button(this).apply { text = "READ DTC — NOT AVAILABLE"; isEnabled = false }
        val clear = Button(this).apply { text = "CLEAR DTC — LOCKED"; isEnabled = false }
        body.addView(read); body.addView(clear)
    }

    private fun ecuPage() {
        body.addView(label("ECU INFORMATION", 16f, white, true))
        listOf("Bluetooth: Classic SPP / RFCOMM", "Device name: Nattawut ECU", "Telemetry: CSV v1", "Update interval: 250 ms",
            "ECU protocol: Honda Keihin K-Line", "ECU communication: Read-only",
            "ECU write / flash / remap: Disabled").forEach { body.addView(label("• $it", 13f, muted, false).apply { setPadding(0, 8, 0, 8) }) }
    }

    private fun mapPage() {
        body.addView(label("MAP FILE MANAGER", 16f, white, true))
        body.addView(label("คู่มือกำหนดการจัดการไฟล์ MAP บนตัว SmartGauge แต่เฟิร์มแวร์ SPP ที่แนบมายังไม่มี API สำหรับอ่าน/บันทึกไฟล์ผ่านมือถือ", 12f, muted, false))
        body.addView(label("ไฟล์ MAP 1–5 และการซิงก์กับ SD จะเปิดใช้หลังยืนยันรูปแบบไฟล์และเพิ่มคำสั่ง ESP32", 12f, Color.YELLOW, false))
        val locked = Button(this).apply { text = "MAP SYNC — NOT AVAILABLE"; isEnabled = false }
        body.addView(locked)
    }

    private fun remapPage() {
        body.addView(label("ECU REMAP • ROM MANAGER", 17f, white, true))
        body.addView(label("HONDA WAVE 110i • PROFILE 38770-K58-T81", 12f, cyan, true))
        body.addView(label("โปรไฟล์อ้างอิงจากตารางที่ผู้ใช้ให้: ROM 64 KB (65,536 bytes), Start Address 0x8000, Checksum Address 0x0000", 12f, muted, false))

        val profile = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setBackgroundColor(panel)
        }
        listOf("ECU: 38770-K58-T81", "ROM SIZE: 64 KB", "START ADDRESS: 0x8000", "CHECKSUM ADDRESS: 0x0000").forEach {
            profile.addView(label(it, 12f, white, true).apply { setPadding(0, dp(3), 0, dp(3)) })
        }
        body.addView(profile, LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, dp(8), 0, dp(8)) })

        val fileInfo = label("ไฟล์: $selectedRomName\nขนาด: ${if (selectedRomSize > 0) "$selectedRomSize bytes" else "-"}\nSHA-256: $selectedRomSha256\nผลตรวจ: ${if (selectedRomValid) "ขนาดตรงกับโปรไฟล์ 64 KB" else "ยังไม่ผ่านการตรวจสอบ"}", 11f, if (selectedRomValid) cyan else muted, false)
        fileInfo.tag = "remap_file_info"
        body.addView(fileInfo)

        val choose = Button(this).apply {
            text = "เลือกไฟล์ BIN จากมือถือ"
            setOnClickListener {
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "application/octet-stream"
                    putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/octet-stream", "application/bin", "application/x-binary", "*/*"))
                }
                startActivityForResult(intent, 78)
            }
        }
        body.addView(choose)

        val backup = Button(this).apply {
            text = "ส่งออกข้อมูลไฟล์ที่เลือก / รายงานตรวจสอบ"
            isEnabled = selectedRomSize > 0
            setOnClickListener { exportRomReport() }
        }
        body.addView(backup)

        val write = Button(this).apply {
            text = "WRITE ECU — LOCKED (รอยืนยันโปรโตคอล)"
            isEnabled = false
        }
        body.addView(write)
        body.addView(label("การเขียน ROM จริงยังปิดไว้: แอปเวอร์ชันนี้ยังไม่มีคำสั่ง Bootloader/Security Access/Erase/Program/Verify ที่ยืนยันแล้ว จึงไม่ส่งข้อมูล BIN ไปยัง ECU เพื่อป้องกันกล่องเสีย", 11f, Color.YELLOW, false).apply { setPadding(0, dp(8), 0, dp(8)) })

        body.addView(label("REMAP HISTORY (บันทึกจากแอปนี้)", 14f, white, true).apply { setPadding(0, dp(10), 0, dp(5)) })
        val historyText = if (remapHistory.isEmpty()) "ยังไม่มีประวัติการเขียนจากแอปนี้\nจำนวนครั้งที่เคยรีแมพก่อนติดตั้งแอป: ยังไม่ทราบ" else remapHistory.takeLast(10).reversed().joinToString("\n")
        body.addView(label(historyText, 11f, muted, false))
    }

    private fun exportRomReport() {
        val report = "NATTAWUT SMARTGAUGE ROM REPORT\nProfile=38770-K58-T81\nExpectedSize=65536\nStartAddress=0x8000\nChecksumAddress=0x0000\nFile=$selectedRomName\nSize=$selectedRomSize\nSHA256=$selectedRomSha256\nSizeValid=$selectedRomValid\nECU_WRITE=LOCKED\n"
        pendingCsv = report
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE); type = "text/plain"; putExtra(Intent.EXTRA_TITLE, "ROM_Report.txt")
        }
        startActivityForResult(intent, 79)
    }

    private fun controlPage() {
        body.addView(label("SMARTGAUGE REMOTE CONTROL", 16f, white, true))
        body.addView(label("โครงหน้ารีโมตเตรียมไว้แล้ว แต่ยังไม่ส่งคำสั่งไปยัง ESP32 เพราะโค้ดปัจจุบันรองรับเฉพาะ telemetry CSV v1", 12f, muted, false))
        listOf("เปลี่ยนหน้าเกจ", "ปรับความสว่าง", "เลือกสีธีม", "สั่งสแกน DTC", "จัดการไฟล์ MAP").forEach {
            val b = Button(this).apply { text = "$it — WAITING FIRMWARE API"; isEnabled = false }
            body.addView(b)
        }
    }

    private fun settingsPage() {
        body.addView(label("APP SETTINGS", 16f, white, true))
        body.addView(label("Bluetooth device: Nattawut ECU", 13f, muted, false))
        body.addView(label("Protocol: NATTWUT,1 CSV", 13f, muted, false))
        body.addView(label("Update rate: 4 Hz (250 ms)", 13f, muted, false))
        body.addView(label("ค่าที่ยังไม่ยืนยันจะไม่ถูกเติมด้วยข้อมูลจำลอง", 12f, cyan, true))
        val disconnect = Button(this).apply { text = "DISCONNECT BLUETOOTH"; setOnClickListener { disconnect() } }
        body.addView(disconnect)
    }

    private fun requestBtPermission() {
    if (Build.VERSION.SDK_INT >= 31) {
        val permissions = mutableListOf<String>()

        if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        }

        if (checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
        }

        if (permissions.isNotEmpty()) {
            requestPermissions(permissions.toTypedArray(), 41)
        }
    }
}

    override fun onRequestPermissionsResult(
    requestCode: Int,
    permissions: Array<out String>,
    grantResults: IntArray
) {
    super.onRequestPermissionsResult(requestCode, permissions, grantResults)

    if (requestCode == 41 && Build.VERSION.SDK_INT >= 31) {
        val connectGranted =
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

        val scanGranted =
            checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED

        if (!connectGranted || !scanGranted) {
            toast("กรุณาอนุญาต Nearby devices / Bluetooth")
        } else {
            toast("อนุญาต Bluetooth แล้ว กรุณากด CONNECT อีกครั้ง")
        }
    }
}

    private fun showPairedDevices() {
        if (Build.VERSION.SDK_INT >= 31 &&
    (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED ||
     checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED)
) {
            requestBtPermission(); return
        }
        val adapter = BluetoothAdapter.getDefaultAdapter()
        if (adapter == null) { toast("โทรศัพท์ไม่รองรับ Bluetooth Classic"); return }
        if (!adapter.isEnabled) { toast("กรุณาเปิด Bluetooth ก่อน"); return }
        val devices = adapter.bondedDevices?.toList().orEmpty()
        if (devices.isEmpty()) { toast("จับคู่ ESP32 ชื่อ Nattawut ECU ใน Settings > Bluetooth ก่อน"); return }
        val names = devices.map { "${it.name ?: "Unknown"} (${it.address})" }.toTypedArray()
        AlertDialog.Builder(this).setTitle("เลือกอุปกรณ์ SmartGauge").setItems(names) { _, which -> connect(devices[which]) }
            .setNegativeButton("ยกเลิก", null).show()
    }
private fun connect(device: BluetoothDevice) {
    if (Build.VERSION.SDK_INT >= 31 &&
    (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED ||
     checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED)
) {
    requestBtPermission()
    return
}

    connectBtn.isEnabled = false
    status.text = "● CONNECTING..."
    status.setTextColor(Color.YELLOW)

    thread {
        var newSocket: BluetoothSocket? = null

        try {

            val connectedSocket =
                device.createRfcommSocketToServiceRecord(sppUuid)

            newSocket = connectedSocket
            connectedSocket.connect()

            socket = connectedSocket
            connectedSocket.outputStream.write("PING\nSTATUS\n".toByteArray(Charsets.UTF_8))
            connectedSocket.outputStream.flush()

            runOnUiThread {
                status.text = "● CONNECTED • WAITING DATA"
                status.setTextColor(Color.GREEN)
                connectBtn.text = "DISCONNECT"
                connectBtn.isEnabled = true
            }

            readLoop(connectedSocket)

        } catch (e: Exception) {
            try {
                newSocket?.close()
            } catch (_: Exception) {
            }

            socket = null

            runOnUiThread {
                status.text = "● CONNECT FAILED: ${e.message}"
                status.setTextColor(Color.RED)
                connectBtn.text = "CONNECT"
                connectBtn.isEnabled = true
            }
        }
    }
}


    
private fun readLoop(s: BluetoothSocket) {
    readerThread = thread {
        try {
            runOnUiThread {
                if (socket === s) {
                    status.text = "● CONNECTED • WAITING DATA"
                    status.setTextColor(Color.YELLOW)
                }
            }

            val reader = BufferedReader(
                InputStreamReader(s.inputStream, Charsets.UTF_8)
            )

            while (!Thread.currentThread().isInterrupted) {
                val line = reader.readLine() ?: break

                if (line.isNotBlank()) {
                    parsePacket(line)
                }
            }

        } catch (e: Exception) {
            runOnUiThread {
                if (socket === s) {
                    status.text = "● READ ERROR: ${e.message}"
                    status.setTextColor(Color.RED)
                }
            }
            return@thread
        }

        runOnUiThread {
            if (socket === s) {
                socket = null
                status.text = "● DISCONNECTED"
                status.setTextColor(Color.RED)
                connectBtn.text = "CONNECT"
                connectBtn.isEnabled = true
            }
        }
    }
 }

    

    
private fun parsePacket(line: String) {

    val cleanLine = line.trim().removePrefix("\uFEFF")
    if (cleanLine.isEmpty()) return

    // Preserve every complete line received over Bluetooth while logging.
    if (logging) {
        val stamp = System.currentTimeMillis().toString()
        val escaped = cleanLine.replace("\\", "\\\\").replace("\"", "\"\"")
        val emptyTelemetry = telemetry.keys.joinToString(",") { "" }
        synchronized(logRows) {
            logRows.add(listOf("BT_RAW", stamp, "", "", "\"$escaped\"").joinToString(",") + "," + emptyTelemetry)
        }
    }
    latestRawFrame = cleanLine
    val p = cleanLine.split(',').map { it.trim() }

    if (p.firstOrNull() == "NATTWUT_ACK" || p.firstOrNull() == "NATTWUT_STATUS") {
        runOnUiThread {
            rawDataView?.text = latestRawFrame
            updateLoggerCount()
            if (p.firstOrNull() == "NATTWUT_STATUS") {
                status.text = "● LINK OK • ${p.drop(2).joinToString(" • ")}"
                status.setTextColor(Color.GREEN)
            }
        }
        return
    }

    // RAW frame sent separately by ESP32; keep it in the same session CSV.
    if (p.size >= 6 && p[0] == "NATTWUT_RAW" && p[1] == "1") {
        val timeMs = p[2].toLongOrNull() ?: System.currentTimeMillis()
        val table = p[3]
        val length = p[4]
        val hex = p.drop(5).joinToString(" ")
        latestRawFrame = "t=$timeMs ms | table=$table | len=$length\n$hex"
        if (logging) synchronized(logRows) {
            val emptyTelemetry = telemetry.keys.joinToString(",") { "" }
            logRows.add(listOf("RAW", timeMs.toString(), table, length, ("\"" + hex + "\"")).joinToString(",") + "," + emptyTelemetry)
        }
        runOnUiThread {
            rawDataView?.text = latestRawFrame
            updateLoggerCount()
        }
        return
    }

    // รับข้อมูลตั้งแต่ 15 ช่องขึ้นไป
    if (p.size < 15 || p[0] != "NATTWUT" || p[1] != "1") {
        runOnUiThread {
            status.text = "● INVALID PACKET: ${cleanLine.take(55)}"
            status.setTextColor(Color.RED)
        }
        return
    }

    val keys = listOf(
        "RPM", "TPS", "ECT", "IAT", "BAT",
        "O2", "AFR", "SPEED", "INJ", "IGN",
        "MAP", "STFT", "LTFT"
    )

    val row = mutableMapOf<String, String>()

    keys.forEachIndexed { i, key ->
        row[key] = if (p[i + 2] == "NA") "0" else p[i + 2]
    }

    synchronized(telemetry) {
        row.forEach { (k, v) -> telemetry[k] = v }
    }

    synchronized(this) {
        receivedPackets++
        lastPacketAt = System.currentTimeMillis()
        telemetryTimedOut = false
    }

    synchronized(histories) {
        keys.forEach { key ->
            val value = row[key]?.toFloatOrNull() ?: return@forEach
            val list = histories.getOrPut(key) { mutableListOf() }
            list.add(value)
            while (list.size > 120) list.removeAt(0)
        }
    }

    val rpm = row["RPM"]?.toFloatOrNull()
    if (rpm != null) {
        synchronized(samples) {
            samples.add(rpm)
            while (samples.size > 100) samples.removeAt(0)
        }
    }

    if (logging) {
        val stamp = System.currentTimeMillis().toString()
        synchronized(logRows) {
            logRows.add(
                listOf("TELEMETRY", stamp, "", "", "").joinToString(",") + "," +
                keys.joinToString(",") { key -> if (p[keys.indexOf(key) + 2] == "NA") "NA" else (row[key] ?: "0") }
            )
        }
        runOnUiThread { updateLoggerCount() }
    }

    runOnUiThread {
        status.text = "● DATA RECEIVED • #$receivedPackets"
        status.setTextColor(Color.GREEN)

        if (activePage == "DASHBOARD") {
            updateDashboardValues()
        } else if (activePage == "GRAPH") {
            graph.invalidate()
        }
    }
}

    private fun updateDashboardValues() {
        fun walk(v: View) {
            if (v is TextView && v.tag is String && (v.tag as String).startsWith("value:")) {
                val key = (v.tag as String).substringAfter("value:")
                v.text = telemetry[key] ?: "0"
            }
            if (v is android.view.ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(body)
    }

    private var pendingCsv: String? = null
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if ((requestCode == 77 || requestCode == 79) && resultCode == RESULT_OK && data?.data != null) {
            try {
                contentResolver.openOutputStream(data.data!!)?.bufferedWriter()?.use { it.write(pendingCsv ?: "") }
                toast("ส่งออกไฟล์สำเร็จ")
            } catch (e: Exception) { toast("บันทึกไฟล์ไม่สำเร็จ: ${e.message}") }
        }
        if (requestCode == 78 && resultCode == RESULT_OK && data?.data != null) {
            try {
                val uri = data.data!!
                val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: ByteArray(0)
                selectedRomName = queryDisplayName(uri) ?: "selected.bin"
                selectedRomSize = bytes.size
                selectedRomSha256 = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
                selectedRomValid = bytes.size == 65536
                if (activePage == "REMAP") showPage("REMAP")
                toast(if (selectedRomValid) "ตรวจพบ BIN ขนาด 64 KB ตรงกับโปรไฟล์" else "เลือกไฟล์แล้ว แต่ขนาดไม่ตรงกับโปรไฟล์ 64 KB")
            } catch (e: Exception) { toast("อ่านไฟล์ BIN ไม่สำเร็จ: ${e.message}") }
        }
    }

    private fun queryDisplayName(uri: android.net.Uri): String? {
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) return cursor.getString(index)
        }
        return null
    }

    private fun disconnect() {
        try { readerThread?.interrupt(); socket?.close() } catch (_: Exception) { }
        socket = null
        lastPacketAt = 0L
        telemetryTimedOut = true
        synchronized(telemetry) { telemetry.keys.forEach { telemetry[it] = "0" } }
        if (activePage == "DASHBOARD") updateDashboardValues()
        status.text = "● DISCONNECTED"; status.setTextColor(Color.RED)
        connectBtn.text = "CONNECT"; connectBtn.isEnabled = true
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
    override fun onDestroy() { disconnect(); super.onDestroy() }

    inner class LiveGraph(context: android.content.Context) : View(context) {
        var metric = "RPM"
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            canvas.drawColor(panel)
            paint.color = muted; paint.strokeWidth = 1f; paint.style = Paint.Style.STROKE
            for (i in 1..4) canvas.drawLine(20f, height * i / 5f, width - 12f, height * i / 5f, paint)
            val vals = synchronized(histories) { histories[metric]?.toList().orEmpty() }
            if (vals.size > 1) {
                val maxV = max(1000f, vals.maxOrNull() ?: 1000f)
                paint.color = cyan; paint.strokeWidth = 3f
                for (i in 1 until vals.size) {
                    val x1 = 20f + (width - 40f) * (i - 1) / (vals.size - 1)
                    val x2 = 20f + (width - 40f) * i / (vals.size - 1)
                    val y1 = height - 15f - (height - 30f) * vals[i - 1] / maxV
                    val y2 = height - 15f - (height - 30f) * vals[i] / maxV
                    canvas.drawLine(x1, y1, x2, y2, paint)
                }
            } else {
                paint.style = Paint.Style.FILL; paint.color = muted; paint.textSize = 13f
                canvas.drawText("Waiting for $metric telemetry…", 24f, height / 2f, paint)
            }
        }
    }
}
