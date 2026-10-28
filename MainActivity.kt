package com.example.replay

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private var seconds = 30
    private val rates = intArrayOf(8, 16, 24, 32)        // Mbps
    private val fpsOpts = intArrayOf(30, 60, 90, 120)
    private val longOpts = intArrayOf(1920, 1280)

    private lateinit var bitrateSp: Spinner
    private lateinit var fpsSp: Spinner
    private lateinit var resSp: Spinner
    private lateinit var presetSp: Spinner
    private lateinit var cbSys: CheckBox
    private lateinit var cbMic: CheckBox
    private lateinit var cbPortrait: CheckBox
    private lateinit var cbForce: CheckBox

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val perms = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) perms.add(Manifest.permission.POST_NOTIFICATIONS)
        requestPermissions(perms.toTypedArray(), 1)

        val pad = (20 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(pad, pad, pad, pad)

        val group = RadioGroup(this)
        val r30 = RadioButton(this)
        r30.text = "Son 30 saniye"
        r30.id = 30
        val r60 = RadioButton(this)
        r60.text = "Son 60 saniye"
        r60.id = 60
        group.addView(r30)
        group.addView(r60)
        group.check(30)
        group.setOnCheckedChangeListener { _, id -> seconds = id }

        fpsSp = makeSpinner(fpsOpts.map { "$it fps" }, 3)
        resSp = makeSpinner(listOf("1080p (1920)", "720p (1280)"), 0)
        bitrateSp = makeSpinner(rates.map { "$it Mbps" }, 1)
        presetSp = makeSpinner(PRESETS.map { it.name }, 0)

        cbSys = CheckBox(this)
        cbSys.text = "Sistem sesini kaydet"
        cbMic = CheckBox(this)
        cbMic.text = "Mikrofonu kaydet"
        cbPortrait = CheckBox(this)
        cbPortrait.text = "Dikey kayıt (işaretsizse yatay, PUBG için)"
        cbForce = CheckBox(this)
        cbForce.text = "120 fps'i zorla (telefon desteklediğini bildirmese de dene)"

        val start = Button(this)
        start.text = "Başlat"
        start.setOnClickListener { onStartClicked() }

        root.addView(group)
        root.addView(label("Kare hızı"))
        root.addView(fpsSp)
        root.addView(label("Çözünürlük"))
        root.addView(resSp)
        root.addView(label("Video bit hızı"))
        root.addView(bitrateSp)
        root.addView(label("Sosyal medya ayarı (Kaydet'ten sonra ayrıca dışa aktarır)"))
        root.addView(presetSp)
        root.addView(cbSys)
        root.addView(cbMic)
        root.addView(cbPortrait)
        root.addView(cbForce)
        root.addView(start)
        root.addView(label("İzin penceresi çıkınca kaydı başlat de. Kaydı bildirimdeki Kaydet düğmesiyle kaydedersin."))

        val scroll = ScrollView(this)
        scroll.addView(root)
        setContentView(scroll)
    }

    private fun label(text: String): TextView {
        val t = TextView(this)
        t.text = text
        t.setPadding(0, (12 * resources.displayMetrics.density).toInt(), 0, 0)
        return t
    }

    private fun makeSpinner(items: List<String>, selected: Int): Spinner {
        val s = Spinner(this)
        s.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, items)
        s.setSelection(selected)
        return s
    }

    private fun toast(m: String) {
        Toast.makeText(this, m, Toast.LENGTH_LONG).show()
    }

    private fun onStartClicked() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            toast("Bildirim izni gerekli, Kaydet düğmesi bildirimde.")
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2)
            return
        }
        val wantAudio = cbSys.isChecked || cbMic.isChecked
        if (wantAudio &&
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED
        ) {
            toast("Ses izni gerekli")
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 3)
            return
        }
        val mpm = getSystemService(MediaProjectionManager::class.java)
        val intent = if (Build.VERSION.SDK_INT >= 34) {
            // Tüm ekranı kaydet (tek uygulama seçeneği çıkmasın)
            mpm.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
        } else {
            mpm.createScreenCaptureIntent()
        }
        startActivityForResult(intent, 100)
    }

    @Suppress("DEPRECATION")
    @Deprecated("basit tutmak için")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 100) return
        if (resultCode != RESULT_OK || data == null) {
            toast("Ekran kaydı izni verilmedi")
            return
        }
        val svc = Intent(this, ReplayService::class.java)
        svc.action = ReplayService.ACTION_START
        svc.putExtra(ReplayService.EXTRA_SECONDS, seconds)
        svc.putExtra(ReplayService.EXTRA_BITRATE, rates[bitrateSp.selectedItemPosition])
        svc.putExtra(ReplayService.EXTRA_FPS, fpsOpts[fpsSp.selectedItemPosition])
        svc.putExtra(ReplayService.EXTRA_LONG, longOpts[resSp.selectedItemPosition])
        svc.putExtra(ReplayService.EXTRA_PRESET, presetSp.selectedItemPosition)
        svc.putExtra(ReplayService.EXTRA_FORCE, cbForce.isChecked)
        svc.putExtra(ReplayService.EXTRA_PORTRAIT, cbPortrait.isChecked)
        svc.putExtra(ReplayService.EXTRA_SYS, cbSys.isChecked)
        svc.putExtra(ReplayService.EXTRA_MIC, cbMic.isChecked)
        svc.putExtra(ReplayService.EXTRA_CODE, resultCode)
        svc.putExtra(ReplayService.EXTRA_DATA, data)
        startForegroundService(svc)
        moveTaskToBack(true)
    }
}
