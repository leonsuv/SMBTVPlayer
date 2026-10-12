package com.example.smbtv

import android.content.Intent
import android.app.AlertDialog
import android.os.Bundle
import android.view.KeyEvent
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

        /** Folder to start in, relative to the share root; Back from here leaves the browser. */
        const val EXTRA_PATH = "path"
    }

    private lateinit var source: SmbSource
    private lateinit var store: Store
    private lateinit var crumbs: TextView
    private lateinit var folderTitle: TextView
    private lateinit var count: TextView
    private lateinit var loading: ProgressBar
    private lateinit var empty: TextView
    private lateinit var list: RecyclerView
    private lateinit var sortButton: TextView
    private val adapter = RowAdapter(onClick = ::onItemClick, onLongClick = ::toggleFavorite)

    /** Folder the browser was opened at (the share root, or a favorite). */
    private var root = ""

    /** Current directory, relative to the share root, "/"-separated. */
    private var path = ""
    private var entries: List<SmbEntry> = emptyList()
    private lateinit var sort: Sort
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
        sort = store.sort()
        sortButton = findViewById(R.id.sort)
        sortButton.setOnClickListener { showSortDialog() }
        findViewById<View>(R.id.route).setOnClickListener {
            startActivity(
                Intent(this, MapActivity::class.java)
                    .putExtra(MapActivity.EXTRA_SOURCE, source.id)
                    .putExtra(MapActivity.EXTRA_PATH, path)
            )
        }

        // Back goes up one folder (keeping focus on the folder we came from); from the start folder it leaves.
        onBackPressedDispatcher.addCallback(this) {
            if (path == root) finish() else load(path.substringBeforeLast('/', ""), focusName = path.substringAfterLast('/'))
        }

        root = intent.getStringExtra(EXTRA_PATH).orEmpty()
        load(root)
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
                    entries = all.filter { it.isDir || it.isMedia }.sortedWith(sort.comparator())
                    loaded = true
                    render()
                    list.focusRow(entries.indexOfFirst { it.name == focusName }.coerceAtLeast(0))
                }
                .onFailure {
                    Toast.makeText(this@BrowserActivity, "Could not open folder: ${it.message}", Toast.LENGTH_LONG).show()
                    // Nothing to show if the start folder itself is gone (e.g. a renamed favorite folder).
                    if (!loaded) finish()
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
        val videos = entries.count { it.isVideo }
        val photos = entries.count { it.isImage }
        count.text = listOfNotNull(
            plural(folders, "folder").takeIf { folders > 0 },
            plural(videos, "video").takeIf { videos > 0 },
            plural(photos, "photo").takeIf { photos > 0 },
        ).joinToString("  ·  ")
        sortButton.text = sort.label
        empty.visibility = if (entries.isEmpty()) View.VISIBLE else View.GONE

        val favorites = store.favorites().filter { it.sourceId == source.id }.map { it.path }.toSet()
        adapter.submit(entries.map { entry ->
            if (entry.isDir) {
                val meta = if (join(path, entry.name) in favorites) "★ Favorite" else null
                Row(entry.name, R.drawable.ic_folder, meta = meta, chevron = true)
            } else {
                val resumeMs = if (entry.isVideo) store.position(smbUri(source.id, join(path, entry.name)).toString()) else 0L
                // Show the date being sorted by, so the order is easy to follow.
                val date = when (sort.key) {
                    SortKey.NAME -> null
                    SortKey.CREATED -> formatDate(entry.created)
                    SortKey.MODIFIED -> formatDate(entry.modified)
                }
                Row(
                    title = entry.name.substringBeforeLast('.'),
                    icon = if (entry.isVideo) R.drawable.ic_movie else R.drawable.ic_photo,
                    subtitle = listOfNotNull(entry.name.substringAfterLast('.').uppercase(), formatSize(entry.size), date)
                        .joinToString("  ·  "),
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
        // Queue all photos and videos in this folder, in the order shown, so Right / the end of a
        // video moves on to the next item whatever its type.
        val media = entries.filter { it.isMedia }
        startActivity(
            Intent(this, PlayerActivity::class.java)
                .putExtra(PlayerActivity.EXTRA_SOURCE, source.id)
                .putStringArrayListExtra(
                    PlayerActivity.EXTRA_PATHS,
                    ArrayList(media.map { join(path, it.name) })
                )
                .putExtra(PlayerActivity.EXTRA_INDEX, media.indexOf(entry))
        )
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_MENU) {
            if (event.action == KeyEvent.ACTION_UP) showSortDialog()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    private fun showSortDialog() {
        val options = SortKey.entries.flatMap { key -> listOf(Sort(key, false), Sort(key, true)) }
        val labels = options.map { o ->
            val direction = when {
                o.key == SortKey.NAME -> if (o.descending) "Z → A" else "A → Z"
                else -> if (o.descending) "newest first" else "oldest first"
            }
            "${o.key.label}  ($direction)"
        }
        AlertDialog.Builder(this)
            .setTitle("Sort by")
            .setSingleChoiceItems(labels.toTypedArray(), options.indexOf(sort)) { dialog, which ->
                dialog.dismiss()
                applySort(options[which])
            }
            .show()
    }

    private fun applySort(newSort: Sort) {
        if (newSort == sort) return
        sort = newSort
        store.saveSort(newSort)
        val focused = list.focusedChild?.let { entries.getOrNull(list.getChildAdapterPosition(it)) }
        entries = entries.sortedWith(sort.comparator())
        render()
        list.focusRow(entries.indexOf(focused).coerceAtLeast(0))
    }

    /** Holding OK on a folder pins it to (or unpins it from) the start screen. */
    private fun toggleFavorite(index: Int) {
        val entry = entries.getOrNull(index)?.takeIf { it.isDir } ?: return
        val folder = join(path, entry.name)
        val existing = store.favorite(source.id, folder)
        if (existing == null) {
            store.addFavorite(Favorite(sourceId = source.id, path = folder, name = entry.name))
        } else {
            store.removeFavorite(existing.id)
        }
        val verb = if (existing == null) "Added to" else "Removed from"
        Toast.makeText(this, "$verb favorites: ${entry.name}", Toast.LENGTH_SHORT).show()
        render()
        list.focusRow(index)
    }

    private fun join(dir: String, name: String) = if (dir.isEmpty()) name else "$dir/$name"

    private fun plural(n: Int, word: String) = if (n == 1) "1 $word" else "$n ${word}s"
}
