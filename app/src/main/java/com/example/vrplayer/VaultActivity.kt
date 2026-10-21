package com.example.vrplayer

import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.text.InputFilter
import android.text.InputType
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContract
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import java.net.URLConnection

/** Выбор нескольких видео с правом удаления оригинала. */
class PickVideos : ActivityResultContract<Unit, List<Uri>>() {
    override fun createIntent(context: Context, input: Unit) = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
        addCategory(Intent.CATEGORY_OPENABLE)
        type = "video/*"
        putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
    }

    override fun parseResult(resultCode: Int, intent: Intent?): List<Uri> {
        if (resultCode != Activity.RESULT_OK || intent == null) return emptyList()
        val list = mutableListOf<Uri>()
        intent.data?.let { list.add(it) }
        intent.clipData?.let { for (i in 0 until it.itemCount) list.add(it.getItemAt(i).uri) }
        return list.distinct()
    }
}

class VaultActivity : AppCompatActivity() {

    private lateinit var root: LinearLayout
    private var status: TextView? = null
    private var picking = false

    private val adder = registerForActivityResult(PickVideos()) { uris ->
        picking = false
        if (uris.isNotEmpty()) importAll(uris)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)   // без скриншотов и превью в «недавних»
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF101010.toInt())
            setPadding(dp(20), dp(40), dp(20), dp(20))
        }
        setContentView(root)
        if (Vault.hasPin(this)) showLogin() else showSetup(false)
    }

    // Уходим из приложения -> сейф закрывается и снова просит PIN
    override fun onStop() {
        super.onStop()
        if (!picking && !isFinishing && !isChangingConfigurations) finish()
    }

    // ---------------- экраны ----------------

    private fun showSetup(change: Boolean) {
        root.removeAllViews()
        root.addView(label(if (change) "Новый PIN-код" else "Создайте PIN-код (4–8 цифр)", 20f))
        val a = pinField("PIN")
        val b = pinField("Повторите PIN")
        val msg = label("", 14f, 0xFFFF6B6B.toInt())
        root.addView(a); root.addView(b); root.addView(msg)
        root.addView(button("Сохранить") {
            val x = a.text.toString(); val y = b.text.toString()
            when {
                x.length !in 4..8 -> msg.text = "PIN должен быть из 4–8 цифр"
                x != y -> msg.text = "PIN-коды не совпадают"
                else -> { Vault.setPin(this, x); showList() }
            }
        })
        if (change) root.addView(button("Отмена") { showList() })
        root.addView(label("Если забудете PIN или удалите приложение, видео из сейфа восстановить нельзя.", 12f, 0xFFAAAAAA.toInt()))
    }

    private fun showLogin() {
        root.removeAllViews()
        root.addView(label("🔒 Приватные видео", 22f))
        val f = pinField("Введите PIN")
        val msg = label("", 14f, 0xFFFF6B6B.toInt())
        root.addView(f); root.addView(msg)
        root.addView(button("Открыть") {
            when (Vault.check(this, f.text.toString())) {
                0 -> showList()
                1 -> msg.text = "Неверный PIN"
                else -> msg.text = "Слишком много попыток. Подождите " + (Vault.lockLeftMs(this) / 1000 + 1) + " сек."
            }
            f.text.clear()
        })
        root.addView(button("Назад") { finish() })
    }

    private fun showList() {
        root.removeAllViews()
        root.addView(label("🔒 Приватные видео", 22f))
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        bar.addView(button("Добавить видео") { picking = true; adder.launch(Unit) })
        bar.addView(button("Сменить PIN") { showSetup(true) })
        bar.addView(button("Закрыть") { finish() })
        root.addView(bar)
        status = label("", 13f, 0xFF8AB4F8.toInt())
        root.addView(status)

        val files = Vault.list(this)
        if (files.isEmpty()) {
            root.addView(label("Пока пусто. Нажмите «Добавить видео»: файл переместится сюда и пропадёт из галереи и проводника.", 15f))
            return
        }
        root.addView(label("Нажмите на видео, чтобы смотреть. Долгое нажатие: вынуть или удалить.", 12f, 0xFFAAAAAA.toInt()))
        val lv = ListView(this)
        lv.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1,
            files.map { it.name + "  (" + (it.length() / 1048576) + " МБ)" })
        lv.setOnItemClickListener { _, _, pos, _ ->
            setResult(RESULT_OK, Intent().putExtra("path", files[pos].absolutePath))
            finish()
        }
        lv.setOnItemLongClickListener { _, _, pos, _ -> fileMenu(files[pos]); true }
        root.addView(lv, LinearLayout.LayoutParams(-1, 0, 1f))
    }

    private fun fileMenu(f: File) {
        AlertDialog.Builder(this).setTitle(f.name)
            .setItems(arrayOf("Вынуть из сейфа (вернуть в галерею)", "Удалить навсегда")) { _, w ->
                if (w == 0) exportFile(f) else confirmDelete(f)
            }.show()
    }

    private fun confirmDelete(f: File) {
        AlertDialog.Builder(this).setTitle("Удалить «${f.name}» навсегда?")
            .setPositiveButton("Удалить") { _, _ -> f.delete(); showList() }
            .setNegativeButton("Отмена", null).show()
    }

    // ---------------- импорт и экспорт ----------------

    private fun importAll(uris: List<Uri>) {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        Thread {
            var ok = 0
            val notDeleted = mutableListOf<String>()
            var error: String? = null
            uris.forEachIndexed { i, uri ->
                val name = displayName(uri)
                val total = sizeOf(uri)
                val dest = uniqueFile(name)
                try {
                    contentResolver.openInputStream(uri)!!.use { inp ->
                        dest.outputStream().use { out ->
                            val buf = ByteArray(1 shl 20)
                            var done = 0L
                            var lastUi = 0L
                            while (true) {
                                val r = inp.read(buf)
                                if (r < 0) break
                                out.write(buf, 0, r)
                                done += r
                                val now = System.currentTimeMillis()
                                if (now - lastUi > 300) {
                                    lastUi = now
                                    val pct = if (total > 0) (done * 100 / total).toInt() else 0
                                    runOnUiThread { status?.text = "Копирую ${i + 1}/${uris.size}: $name — $pct%" }
                                }
                            }
                        }
                    }
                    ok++
                    val deleted = try { DocumentsContract.deleteDocument(contentResolver, uri) } catch (e: Exception) { false }
                    if (!deleted) notDeleted.add(name)
                } catch (e: Exception) {
                    dest.delete()
                    error = "Не удалось добавить «$name» (возможно, не хватило места)."
                }
            }
            runOnUiThread {
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                if (isFinishing || isDestroyed) return@runOnUiThread
                showList()
                val msg = StringBuilder("Добавлено в сейф: $ok.")
                if (notDeleted.isNotEmpty())
                    msg.append("\n\nОригиналы не удалось удалить автоматически: ").append(notDeleted.joinToString(", "))
                        .append(".\nУдалите их вручную (в «Файлах» или галерее), иначе они останутся видны.")
                if (error != null) msg.append("\n\n").append(error)
                AlertDialog.Builder(this).setMessage(msg.toString()).setPositiveButton("OK", null).show()
            }
        }.start()
    }

    private fun exportFile(f: File) {
        if (Build.VERSION.SDK_INT < 29) {
            Toast.makeText(this, "Вынуть из сейфа можно только на Android 10+", Toast.LENGTH_LONG).show()
            return
        }
        status?.text = "Возвращаю ${f.name}…"
        Thread {
            var ok = false
            try {
                val cv = ContentValues().apply {
                    put(MediaStore.Video.Media.DISPLAY_NAME, f.name)
                    put(MediaStore.Video.Media.MIME_TYPE, URLConnection.guessContentTypeFromName(f.name) ?: "video/mp4")
                    put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/VRPlayer")
                    put(MediaStore.Video.Media.IS_PENDING, 1)
                }
                val uri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, cv)!!
                contentResolver.openOutputStream(uri)!!.use { out -> f.inputStream().use { it.copyTo(out, 1 shl 20) } }
                cv.clear()
                cv.put(MediaStore.Video.Media.IS_PENDING, 0)
                contentResolver.update(uri, cv, null, null)
                f.delete()
                ok = true
            } catch (e: Exception) { /* ok остаётся false */ }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                Toast.makeText(this,
                    if (ok) "Готово: Movies/VRPlayer" else "Не удалось вынуть видео", Toast.LENGTH_LONG).show()
                showList()
            }
        }.start()
    }

    private fun displayName(uri: Uri): String {
        var n: String? = null
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) n = it.getString(0)
        }
        return (n ?: "video_${System.currentTimeMillis()}.mp4").replace(Regex("[\\\\/:*?\"<>|]"), "_")
    }

    private fun sizeOf(uri: Uri): Long {
        var s = -1L
        contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use {
            if (it.moveToFirst() && !it.isNull(0)) s = it.getLong(0)
        }
        return s
    }

    private fun uniqueFile(name: String): File {
        val d = Vault.dir(this)
        var f = File(d, name)
        val base = name.substringBeforeLast('.', name)
        val ext = name.substringAfterLast('.', "")
        var i = 1
        while (f.exists()) {
            f = File(d, if (ext.isEmpty()) "${base}_$i" else "${base}_$i.$ext")
            i++
        }
        return f
    }

    // ---------------- мелкие помощники ----------------

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun label(t: String, sp: Float, color: Int = Color.WHITE) = TextView(this).apply {
        text = t; textSize = sp; setTextColor(color); setPadding(0, dp(8), 0, dp(8))
    }

    private fun pinField(h: String) = EditText(this).apply {
        hint = h
        setHintTextColor(0xFF888888.toInt())
        setTextColor(Color.WHITE)
        inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        filters = arrayOf(InputFilter.LengthFilter(8))
    }

    private fun button(t: String, a: () -> Unit) = Button(this).apply {
        text = t; isAllCaps = false; setOnClickListener { a() }
    }
}
