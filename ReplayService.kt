package com.example.replay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.MediaStore
import android.view.Surface
import android.widget.Toast
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.effect.FrameDropEffect
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.concurrent.thread
import kotlin.math.roundToInt

// ---------------------------------------------------------------------------
// Sosyal medya hazır ayarları. fps = 0 -> dışa aktarma yok (sadece orijinal kalır).
// fps 120 -> kare hızı korunur, sadece 1080p'ye ve bit hızına göre sıkıştırılır.
// Değerleri buradan değiştirebilirsin.
// ---------------------------------------------------------------------------
class Preset(val name: String, val fps: Int, val mbps: Int, val vertical: Boolean)

val PRESETS = listOf(
    Preset("Yok (sadece orijinal)", 0, 0, false),
    Preset("TikTok – dikey 1080x1920, 60 fps", 60, 10, true),
    Preset("TikTok – yatay 1080p, 120 fps koru (deneysel)", 120, 16, false),
    Preset("Instagram Reels – dikey, 60 fps", 60, 12, true),
    Preset("YouTube Shorts – dikey, 60 fps", 60, 12, true),
    Preset("YouTube – yatay 1080p, 60 fps", 60, 16, false),
    Preset("Güvenli 30 fps – dikey", 30, 8, true),
    Preset("Güvenli 30 fps – yatay", 30, 8, false),
)

class ReplayService : Service() {

    class Sample(val data: ByteArray, val pts: Long, val flags: Int)

    private class VideoChoice(val codec: MediaCodec, val fps: Int, val w: Int, val h: Int)

    private val main = Handler(Looper.getMainLooper())
    private val lock = Any()
    private val gops = ArrayDeque<MutableList<Sample>>()   // video: anahtar kare gruplari
    private val audioQ = ArrayDeque<Sample>()              // ses: AAC kareleri

    @Volatile private var running = false
    @Volatile private var videoFormat: MediaFormat? = null
    @Volatile private var audioFormat: MediaFormat? = null

    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var codec: MediaCodec? = null
    private var surface: Surface? = null
    private var aenc: MediaCodec? = null
    private var recs: List<AudioRecord> = emptyList()
    private var worker: Thread? = null
    private var audioWorker: Thread? = null

    private var windowUs = 30_000_000L
    private var presetIdx = 0
    private var recW = 0
    private var recH = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopAll()
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val i = intent ?: return START_NOT_STICKY
        when (i.action) {
            ACTION_START -> if (!running) {
                try {
                    start(i)
                } catch (e: Exception) {
                    toast("Başlatma hatası: ${e.message}")
                    stopAll()
                    stopSelf()
                }
            }
            ACTION_SAVE -> save()
            ACTION_STOP -> {
                stopAll()
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    // ------------------------------------------------------------------ START
    @Suppress("DEPRECATION")
    private fun start(intent: Intent) {
        windowUs = intent.getIntExtra(EXTRA_SECONDS, 30) * 1_000_000L
        val mbps = intent.getIntExtra(EXTRA_BITRATE, 16)
        val wantFps = intent.getIntExtra(EXTRA_FPS, 120)
        val wantLong = intent.getIntExtra(EXTRA_LONG, 1920)
        val force = intent.getBooleanExtra(EXTRA_FORCE, false)
        val portrait = intent.getBooleanExtra(EXTRA_PORTRAIT, false)
        val sys = intent.getBooleanExtra(EXTRA_SYS, false)
        val mic = intent.getBooleanExtra(EXTRA_MIC, false)
        presetIdx = intent.getIntExtra(EXTRA_PRESET, 0)

        notifyForeground(mic)

        val data = intent.getParcelableExtra<Intent>(EXTRA_DATA)
        if (data == null) {
            toast("Ekran kaydı izni alınamadı")
            stopAll()
            stopSelf()
            return
        }
        val mpm = getSystemService(MediaProjectionManager::class.java)
        val p = mpm.getMediaProjection(intent.getIntExtra(EXTRA_CODE, 0), data)
        projection = p
        p.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                stopAll()
                stopSelf()
            }
        }, main)

        val dm = resources.displayMetrics
        val longSide = maxOf(dm.widthPixels, dm.heightPixels)
        val shortSide = minOf(dm.widthPixels, dm.heightPixels)
        // PUBG yatay oynandığı için varsayılan kayıt yatay.
        val lw = if (portrait) shortSide else longSide
        val lh = if (portrait) longSide else shortSide

        val choice = createVideoEncoder(lw, lh, wantFps, wantLong, mbps, force)
        if (choice == null) {
            toast("Video encoder başlatılamadı")
            stopAll()
            stopSelf()
            return
        }
        val enc = choice.codec
        val surf = enc.createInputSurface()
        surface = surf
        enc.start()
        codec = enc
        recW = choice.w
        recH = choice.h
        display = p.createVirtualDisplay(
            "replay", choice.w, choice.h, dm.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, surf, null, null
        )
        running = true
        worker = thread(name = "video-drain") { drainVideo(enc) }

        val audioInfo = startAudio(p, sys, mic)
        toast("Kayıt: ${choice.w}x${choice.h} @ ${choice.fps} fps, $mbps Mbps, $audioInfo")
    }

    // Seçilen fps'i dener; desteklenmezse önce çözünürlüğü, sonra fps'i düşürür.
    private fun createVideoEncoder(
        lw: Int, lh: Int, wantFps: Int, wantLong: Int, mbps: Int, force: Boolean
    ): VideoChoice? {
        val mime = MediaFormat.MIMETYPE_VIDEO_AVC
        val sizes = listOf(wantLong, 1280, 960).filter { it <= wantLong }.distinct()
        val rates = listOf(120, 90, 60, 30).filter { it <= wantFps }
        for (fps in rates) {
            for (target in sizes) {
                val sc = minOf(1f, target.toFloat() / maxOf(lw, lh))
                val tw = (lw * sc).toInt() / 16 * 16
                val th = (lh * sc).toInt() / 16 * 16
                var c: MediaCodec? = null
                try {
                    c = MediaCodec.createEncoderByType(mime)
                    val cc = c.codecInfo.getCapabilitiesForType(mime)
                    val vc = cc.videoCapabilities
                    if (!force && !vc.areSizeAndRateSupported(tw, th, fps.toDouble())) {
                        c.release()
                        c = null
                        continue
                    }
                    val bitrate = vc.bitrateRange.clamp(mbps * 1_000_000)
                    val cbr = cc.encoderCapabilities
                        .isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
                    val f = MediaFormat.createVideoFormat(mime, tw, th)
                    f.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                        MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                    f.setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                    f.setInteger(MediaFormat.KEY_BITRATE_MODE,
                        if (cbr) MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR
                        else MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
                    f.setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                    f.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                    f.setInteger(MediaFormat.KEY_PRIORITY, 0)
                    f.setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 100_000L)
                    c.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                    return VideoChoice(c, fps, tw, th)
                } catch (e: Exception) {
                    c?.release()
                }
            }
        }
        return null
    }

    // ------------------------------------------------------------------ SES
    private fun startAudio(p: MediaProjection, sys: Boolean, mic: Boolean): String {
        if (!sys && !mic) return "ses yok"
        val list = mutableListOf<AudioRecord>()
        if (sys) makeRecord(true, p)?.let { list.add(it) }
        if (mic) makeRecord(false, p)?.let { list.add(it) }
        if (list.isEmpty()) {
            toast("Ses başlatılamadı (izin?)")
            return "ses yok"
        }
        return try {
            val af = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, RATE, 1)
            af.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            af.setInteger(MediaFormat.KEY_BIT_RATE, 128_000)
            af.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
            val ae = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            ae.configure(af, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            ae.start()
            aenc = ae
            recs = list
            list.forEach { it.startRecording() }
            audioWorker = thread(name = "audio") { audioLoop(ae, list) }
            listOfNotNull(if (sys) "sistem" else null, if (mic) "mikrofon" else null)
                .joinToString("+")
        } catch (e: Exception) {
            toast("Ses hatası: ${e.message}")
            "ses yok"
        }
    }

    private fun makeRecord(sys: Boolean, p: MediaProjection): AudioRecord? {
        return try {
            val fmt = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(RATE)
                .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                .build()
            val minBuf = AudioRecord.getMinBufferSize(
                RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val b = AudioRecord.Builder()
                .setAudioFormat(fmt)
                .setBufferSizeInBytes(minBuf * 4)
            if (sys) {
                val cfg = AudioPlaybackCaptureConfiguration.Builder(p)
                    .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                    .addMatchingUsage(AudioAttributes.USAGE_GAME)
                    .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                    .build()
                b.setAudioPlaybackCaptureConfig(cfg)
            } else {
                b.setAudioSource(MediaRecorder.AudioSource.MIC)
            }
            val r = b.build()
            if (r.state == AudioRecord.STATE_INITIALIZED) r else { r.release(); null }
        } catch (e: Exception) {
            null
        }
    }

    // Mikrofon ve/veya sistem sesini okur, toplar, AAC'ye çevirir.
    private fun audioLoop(enc: MediaCodec, sources: List<AudioRecord>) {
        val n = 1024
        val bufs = Array(sources.size) { ShortArray(n) }
        val mix = ShortArray(n)
        val info = MediaCodec.BufferInfo()
        var startUs = -1L
        var frames = 0L
        try {
            while (running) {
                var ok = true
                for (k in sources.indices) {
                    var got = 0
                    while (got < n && running) {
                        val x = sources[k].read(bufs[k], got, n - got, AudioRecord.READ_BLOCKING)
                        if (x < 0) { ok = false; break }
                        got += x
                    }
                    if (!ok) break
                }
                if (!ok || !running) break
                // Video ile aynı saat (CLOCK_MONOTONIC) kullanılır.
                if (startUs < 0) startUs = System.nanoTime() / 1000 - n * 1_000_000L / RATE
                for (j in 0 until n) {
                    var s = 0
                    for (arr in bufs) s += arr[j].toInt()
                    mix[j] = s.coerceIn(-32768, 32767).toShort()
                }
                val pts = startUs + frames * 1_000_000L / RATE
                frames += n
                val ii = enc.dequeueInputBuffer(5_000)
                if (ii >= 0) {
                    val ib = enc.getInputBuffer(ii)!!
                    ib.clear()
                    ib.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(mix)
                    enc.queueInputBuffer(ii, 0, n * 2, pts, 0)
                }
                drainAudio(enc, info)
            }
        } catch (ignored: Exception) {
        }
    }

    private fun drainAudio(enc: MediaCodec, info: MediaCodec.BufferInfo) {
        while (true) {
            val i = enc.dequeueOutputBuffer(info, 0)
            if (i == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                audioFormat = enc.outputFormat
            } else if (i >= 0) {
                val buf = enc.getOutputBuffer(i)!!
                val isConfig = (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                if (info.size > 0 && !isConfig) {
                    buf.position(info.offset)
                    buf.limit(info.offset + info.size)
                    val bytes = ByteArray(info.size)
                    buf.get(bytes)
                    val pts = info.presentationTimeUs
                    synchronized(lock) {
                        audioQ.addLast(Sample(bytes, pts, MediaCodec.BUFFER_FLAG_KEY_FRAME))
                        while (audioQ.first().pts < pts - windowUs - 3_000_000L) audioQ.removeFirst()
                    }
                }
                enc.releaseOutputBuffer(i, false)
            } else {
                break
            }
        }
    }

    // ------------------------------------------------------------------ VIDEO
    private fun drainVideo(c: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        try {
            while (running) {
                val i = c.dequeueOutputBuffer(info, 10_000)
                if (i == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    videoFormat = c.outputFormat
                } else if (i >= 0) {
                    val buf = c.getOutputBuffer(i)!!
                    val isConfig = (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                    if (info.size > 0 && !isConfig) {
                        buf.position(info.offset)
                        buf.limit(info.offset + info.size)
                        val bytes = ByteArray(info.size)
                        buf.get(bytes)
                        addVideo(Sample(bytes, info.presentationTimeUs, info.flags))
                    }
                    c.releaseOutputBuffer(i, false)
                }
            }
        } catch (ignored: Exception) {
        }
    }

    // Kareleri anahtar kare gruplarında (GOP) tutar; pencere dolunca en eski grubu atar.
    private fun addVideo(s: Sample) {
        synchronized(lock) {
            val key = (s.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0
            if (key) {
                gops.addLast(mutableListOf())
            } else if (gops.isEmpty()) {
                return
            }
            gops.last().add(s)
            while (gops.size > 1 && s.pts - gops[1][0].pts >= windowUs) gops.removeFirst()
        }
    }

    // ------------------------------------------------------------------ KAYDET
    private fun save() {
        val vf = videoFormat
        val af = audioFormat
        val (snap, aud) = synchronized(lock) {
            val v = gops.flatten()
            val t = if (v.isEmpty()) 0L else v[0].pts
            val a = if (af != null) audioQ.filter { it.pts >= t } else emptyList<Sample>()
            Pair(v, a)
        }
        if (snap.isEmpty() || vf == null) {
            toast("Henüz kayıt yok")
            return
        }
        val spanS = (snap.last().pts - snap[0].pts) / 1_000_000.0
        val avgFps = if (spanS > 0) snap.size / spanS else 0.0
        val info = "${spanS.roundToInt()} sn, ~${avgFps.roundToInt()} fps"
        val stamp = System.currentTimeMillis()

        thread(name = "save") {
            val tmp = File(cacheDir, "clip_$stamp.mp4")
            try {
                val mux = MediaMuxer(tmp.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                val vt = mux.addTrack(vf)
                val at = if (af != null && aud.isNotEmpty()) mux.addTrack(af) else -1
                mux.start()
                val t0 = snap[0].pts
                val bi = MediaCodec.BufferInfo()
                val aList = if (at >= 0) aud else emptyList<Sample>()
                var vi = 0
                var ai = 0
                while (vi < snap.size || ai < aList.size) {
                    val useV = ai >= aList.size || (vi < snap.size && snap[vi].pts <= aList[ai].pts)
                    val s = if (useV) snap[vi++] else aList[ai++]
                    bi.set(0, s.data.size, s.pts - t0, s.flags)
                    mux.writeSampleData(if (useV) vt else at, ByteBuffer.wrap(s.data), bi)
                }
                mux.stop()
                mux.release()
                storeFile(tmp, "replay_$stamp.mp4")
                toast("Kaydedildi ($info): Movies/Replay")
                val pr = PRESETS.getOrNull(presetIdx)
                if (pr != null && pr.fps > 0) export(tmp, pr, stamp) else tmp.delete()
            } catch (e: Exception) {
                toast("Kaydetme hatası: ${e.message}")
                tmp.delete()
            }
        }
    }

    private fun storeFile(f: File, name: String, sub: String = "") {
        val cv = ContentValues()
        cv.put(MediaStore.Video.Media.DISPLAY_NAME, name)
        cv.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
        cv.put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/Replay" + sub)
        val uri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, cv)!!
        contentResolver.openOutputStream(uri)!!.use { o ->
            f.inputStream().use { it.copyTo(o) }
        }
    }

    // Orijinal klibi hedef fps'e düşürür (120->60 / 120->30 tam katlar), H.264 olarak sıkıştırır.
    private fun export(src: File, p: Preset, stamp: Long) {
        toast("Dışa aktarılıyor: ${p.name}")
        val out = File(cacheDir, "export_$stamp.mp4")
        out.delete()
        main.post {
            try {
                val fx = mutableListOf<Effect>()
                if (p.fps < 120) {
                    fx.add(FrameDropEffect.createDefaultFrameDropEffect(p.fps.toFloat()))
                }
                if (p.vertical) {
                    fx.add(Presentation.createForWidthAndHeight(
                        1080, 1920, Presentation.LAYOUT_SCALE_TO_FIT))
                } else if (recH > 1080) {
                    fx.add(Presentation.createForHeight(1080))
                }
                val encFactory = DefaultEncoderFactory.Builder(this)
                    .setRequestedVideoEncoderSettings(
                        VideoEncoderSettings.Builder().setBitrate(p.mbps * 1_000_000).build())
                    .build()
                val t = Transformer.Builder(this)
                    .setVideoMimeType(MimeTypes.VIDEO_H264)
                    .setEncoderFactory(encFactory)
                    .addListener(object : Transformer.Listener {
                        override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                            thread(name = "export-save") {
                                try {
                                    storeFile(out, "export_$stamp.mp4", "/Export")
                                    toast("Hazır: Movies/Replay/Export")
                                } catch (e: Exception) {
                                    toast("Export kaydetme hatası: ${e.message}")
                                }
                                out.delete()
                                src.delete()
                            }
                        }

                        override fun onError(
                            composition: Composition,
                            exportResult: ExportResult,
                            exportException: ExportException
                        ) {
                            toast("Dışa aktarma hatası: ${exportException.message}")
                            src.delete()
                        }
                    })
                    .build()
                val item = EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(src)))
                    .setEffects(Effects(emptyList(), fx))
                    .build()
                t.start(item, out.path)
            } catch (e: Exception) {
                toast("Dışa aktarma başlatılamadı: ${e.message}")
                src.delete()
            }
        }
    }

    // ------------------------------------------------------------------ DURDUR
    private fun stopAll() {
        running = false
        worker?.join(500)
        worker = null
        audioWorker?.join(800)
        audioWorker = null
        recs.forEach {
            try { it.stop() } catch (ignored: Exception) { }
            try { it.release() } catch (ignored: Exception) { }
        }
        recs = emptyList()
        try { aenc?.stop() } catch (ignored: Exception) { }
        try { aenc?.release() } catch (ignored: Exception) { }
        aenc = null
        try { codec?.stop() } catch (ignored: Exception) { }
        try { codec?.release() } catch (ignored: Exception) { }
        codec = null
        try { display?.release() } catch (ignored: Exception) { }
        display = null
        try { surface?.release() } catch (ignored: Exception) { }
        surface = null
        val p = projection
        projection = null
        try { p?.stop() } catch (ignored: Exception) { }
        synchronized(lock) {
            gops.clear()
            audioQ.clear()
        }
        videoFormat = null
        audioFormat = null
        try { stopForeground(STOP_FOREGROUND_REMOVE) } catch (ignored: Exception) { }
    }

    private fun notifyForeground(mic: Boolean) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel("replay", "Replay", NotificationManager.IMPORTANCE_LOW))
        fun act(a: String, title: String): Notification.Action {
            val pi = PendingIntent.getService(
                this, a.hashCode(),
                Intent(this, ReplayService::class.java).setAction(a),
                PendingIntent.FLAG_IMMUTABLE)
            val icon = Icon.createWithResource(this, android.R.drawable.ic_menu_save)
            return Notification.Action.Builder(icon, title, pi).build()
        }
        val n = Notification.Builder(this, "replay")
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setContentTitle("Son ${windowUs / 1_000_000} saniye kaydediliyor")
            .addAction(act(ACTION_SAVE, "Kaydet"))
            .addAction(act(ACTION_STOP, "Durdur"))
            .setOngoing(true)
            .build()
        var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        if (mic) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        startForeground(1, n, type)
    }

    private fun toast(m: String) {
        main.post { Toast.makeText(applicationContext, m, Toast.LENGTH_LONG).show() }
    }

    companion object {
        const val RATE = 44100
        const val ACTION_START = "start"
        const val ACTION_SAVE = "save"
        const val ACTION_STOP = "stop"
        const val EXTRA_SECONDS = "seconds"
        const val EXTRA_BITRATE = "bitrate"
        const val EXTRA_FPS = "fps"
        const val EXTRA_LONG = "long"
        const val EXTRA_PRESET = "preset"
        const val EXTRA_FORCE = "force"
        const val EXTRA_PORTRAIT = "portrait"
        const val EXTRA_SYS = "sys"
        const val EXTRA_MIC = "mic"
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
    }
}
