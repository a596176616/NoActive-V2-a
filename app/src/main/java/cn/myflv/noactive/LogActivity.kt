package cn.myflv.noactive

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Menu
import android.view.MenuItem
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import com.topjohnwu.superuser.Shell
import com.topjohnwu.superuser.io.SuFile
import com.topjohnwu.superuser.io.SuFileInputStream
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * 模块日志查看页.
 *
 * 只读展示 /data/system/NoActive/log/ 下的日志文件，
 * 支持 current.log 与 last.log 之间切换、刷新、滚动到底部。
 *
 * 读取通过 root 完成（配置目录归 system 所有，普通进程不可读）。
 */
class LogActivity : AppCompatActivity() {

    private lateinit var logTextView: TextView
    private val handler = Handler(Looper.getMainLooper())

    /** 当前展示的日志文件名：current 或 last */
    private var currentFile = "current.log"

    /** 单次读取上限（行数），避免内存爆炸 */
    private val maxLines = 4000

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_log)

        val toolbar = findViewById<Toolbar>(R.id.log_toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        logTextView = findViewById(R.id.log_text)

        loadLog()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, MENU_REFRESH, 0, getString(R.string.log_refresh))
            .setIcon(R.drawable.ic_setting)
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
        menu.add(0, MENU_TOGGLE, 0, getString(R.string.log_toggle))
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        menu.add(0, MENU_CLEAR, 0, getString(R.string.log_clear))
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            android.R.id.home -> {
                finish()
                return true
            }
            MENU_REFRESH -> {
                loadLog()
                return true
            }
            MENU_TOGGLE -> {
                currentFile = if (currentFile == "current.log") "last.log" else "current.log"
                loadLog()
                return true
            }
            MENU_CLEAR -> {
                clearLog()
                return true
            }
        }
        return super.onOptionsItemSelected(item)
    }

    /**
     * 异步读取日志文件并显示.
     * 优先用 SuFileInputStream 读取（root），失败时回退到 cat 命令.
     */
    private fun loadLog() {
        val path = "${cn.myflv.noactive.core.utils.FreezerConfig.LogDir}/$currentFile"
        title = getString(R.string.log_viewer) + " - " + currentFile

        Thread {
            val content = tryReadLog(path)
            handler.post {
                logTextView.text = content
                if (content.isEmpty()) {
                    Toast.makeText(this, getString(R.string.log_empty), Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    private fun tryReadLog(path: String): String {
        // 优先 SuFileInputStream
        try {
            val file = SuFile(path)
            if (!file.exists()) {
                return ""
            }
            SuFileInputStream.open(file).use { fis ->
                BufferedReader(InputStreamReader(fis)).use { reader ->
                    val sb = StringBuilder()
                    var line = reader.readLine()
                    var count = 0
                    val lines = ArrayDeque<String>()
                    while (line != null) {
                        if (lines.size >= maxLines) {
                            lines.removeFirst()
                        }
                        lines.addLast(line)
                        line = reader.readLine()
                        count++
                    }
                    for (l in lines) {
                        sb.append(l).append('\n')
                    }
                    return sb.toString()
                }
            }
        } catch (e: Throwable) {
            // 回退：用 cat 命令
        }

        return try {
            val result = Shell.cmd("cat '$path'").exec()
            result.out.joinToString("\n")
        } catch (e: Throwable) {
            "Failed to read log: ${e.message}"
        }
    }

    /**
     * 清空 current.log（不是删除文件，避免模块逻辑认为日志初始化失败）.
     */
    private fun clearLog() {
        val path = "${cn.myflv.noactive.core.utils.FreezerConfig.LogDir}/$currentFile"
        Thread {
            try {
                Shell.cmd("echo '' > '$path'").exec()
                handler.post {
                    loadLog()
                    Toast.makeText(this, getString(R.string.log_cleared), Toast.LENGTH_SHORT).show()
                }
            } catch (e: Throwable) {
                handler.post {
                    Toast.makeText(this, "Clear failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    companion object {
        private const val MENU_REFRESH = 1
        private const val MENU_TOGGLE = 2
        private const val MENU_CLEAR = 3
    }
}
