package com.example.smbtv

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class BrowserActivity : ComponentActivity() {

    companion object {
        const val EXTRA_SOURCE = "source"
    }

    private lateinit var source: SmbSource
    private lateinit var store: Store
    private lateinit var crumbs: TextView
    private lateinit var folderTitle: TextView
    private lateinit var count: TextView
    private lateinit var loading: ProgressBar
    private lateinit var empty: TextView
    private lateinit var list: RecyclerView
    private val adapter = RowAdapter(onClick = ::onItemClick)

    /** Current directory, relative to the share root, "/"-separated. */
    private var path = ""
    private var entries: List<SmbEntry> = emptyList()
    private var loaded = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_browser)

        store = Store(this)
        val id = intent.getStringExtra(EXTRA_SOURCE)
        source = store.sources().firstOrNull { it.id == id } ?: run {
            finish()
            return
        }

        crumbs = findViewById(R.id.crumbs)
        folderTitle = findViewById(R.id.folderTitle)
        count = findViewById(R.id.count)
        loading = findViewById(R.id.loading)
        empty = findViewById(R.id.empty)
        list = findViewById(R.id.list)
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter

        // Back goes up one folder (keeping focus on the folder we came from); from the root it leaves.
        onBackPressedDispatcher.addCallback(this) {
            if (path.isEmpty()) finish() else load(path.substringBeforeLast('/', ""), focusName = path.substringAfterLast('/'))
        }

        load("")
    }

    override fun onResume() {
        super.onResume()
        // Refresh resume markers after returning from the player, without hitting the network.
        if (loaded) {
            val focused = list.focusedChild?.let { list.getChildAdapterPosition(it) } ?: 0
            render()
            list.focusRow(focused)
        }
    }

    private fun load(dir: String, focusName: String? = null) {
        loading.visibility = View.VISIBLE
        lifecycleScope.launch {
            runCatching { withContext(Dispatchers.IO) { Smb.list(source, dir) } }
                .onSuccess { all ->
                    path = dir
                    entries = all
                        .filter { it.isDir || it.isVideo }
                        .sortedWith(compareByDescending<SmbEntry> { it.isDir }.thenBy { it.name.lowercase() })
                    loaded = true
                    render()
                    list.focusRow(entries.indexOfFirst { it.name == focusName }.coerceAtLeast(0))
                }
                .onFailure {
                    Toast.makeText(this@BrowserActivity, "Could not open folder: ${it.message}", Toast.LENGTH_LONG).show()
                }
            loading.visibility = View.GONE
        }
    }

    private fun render() {
        folderTitle.text = if (path.isEmpty()) source.name else path.substringAfterLast('/')
        crumbs.text = if (path.isEmpty()) {
            "\\\\${source.host}\\${source.share}"
        } else {
            (listOf(source.name) + path.split('/').dropLast(1)).joinToString("  /  ")
        }

        val folders = entries.count { it.isDir }
        val videos = entries.size - folders
        count.text = listOfNotNull(
            plural(folders, "folder").takeIf { folders > 0 },
            plural(videos, "video").takeIf { videos > 0 },
        ).joinToString("  ·  ")
        empty.visibility = if (entries.isEmpty()) View.VISIBLE else View.GONE

        adapter.submit(entries.map { entry ->
            if (entry.isDir) {
                Row(entry.name, R.drawable.ic_folder, chevron = true)
            } else {
                val resumeMs = store.position(smbUri(source.id, join(path, entry.name)).toString())
                Row(
                    title = entry.name.substringBeforeLast('.'),
                    icon = R.drawable.ic_movie,
                    subtitle = "${entry.name.substringAfterLast('.').uppercase()}  ·  ${formatSize(entry.size)}",
                    meta = if (resumeMs > 0) "Resume ${formatClock(resumeMs)}" else null,
                )
            }
        })
    }

    private fun onItemClick(index: Int) {
        val entry = entries.getOrNull(index) ?: return
        if (entry.isDir) {
            load(join(path, entry.name))
            return
        }
        // Queue all videos in this folder so playback continues to the next episode.
        val videos = entries.filter { !it.isDir }
        startActivity(
            Intent(this, PlayerActivity::class.java)
                .putExtra(PlayerActivity.EXTRA_SOURCE, source.id)
                .putStringArrayListExtra(
                    PlayerActivity.EXTRA_PATHS,
                    ArrayList(videos.map { join(path, it.name) })
                )
                .putExtra(PlayerActivity.EXTRA_INDEX, videos.indexOf(entry))
        )
    }

    private fun join(dir: String, name: String) = if (dir.isEmpty()) name else "$dir/$name"

    private fun plural(n: Int, word: String) = if (n == 1) "1 $word" else "$n ${word}s"
}
