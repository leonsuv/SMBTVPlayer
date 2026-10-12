package com.example.smbtv

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.util.UUID

class MainActivity : ComponentActivity() {

    /** What each row on the start screen stands for. */
    private sealed interface Item {
        data class Header(val title: String) : Item
        data class Fav(val favorite: Favorite, val source: SmbSource) : Item
        data class Share(val source: SmbSource) : Item
        data object Add : Item
    }

    private lateinit var store: Store
    private lateinit var list: RecyclerView
    private lateinit var hint: TextView
    private val adapter = RowAdapter(onClick = ::onRowClick, onLongClick = ::showOptions)
    private var items: List<Item> = emptyList()

    /** The row being reordered with Up/Down, or null when not in move mode. */
    private var moving: Item? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        store = Store(this)
        list = findViewById(R.id.sources)
        hint = findViewById(R.id.hint)
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    private fun reload() {
        val sources = store.sources()
        val byId = sources.associateBy { it.id }
        // Favorites whose share was removed are dropped by Store.removeSource; skip any stragglers.
        val favorites = store.favorites().mapNotNull { f -> byId[f.sourceId]?.let { Item.Fav(f, it) } }

        items = buildList {
            if (favorites.isNotEmpty()) {
                add(Item.Header("Favorites"))
                addAll(favorites)
            }
            add(Item.Header("Network shares"))
            sources.forEach { add(Item.Share(it)) }
            add(Item.Add)
        }
        adapter.submit(items.map(::rowFor))

        hint.text = if (moving != null) {
            "Up / Down to move  ·  OK or Back when done"
        } else {
            "Hold OK (or press Menu) on an item to rename, edit, reorder or remove it"
        }
        hint.visibility = if (sources.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun rowFor(item: Item): Row {
        val meta = if (item == moving) "▲ ▼  Moving" else null
        return when (item) {
            is Item.Header -> Row(item.title, header = true)
            is Item.Fav -> Row(
                item.favorite.name,
                R.drawable.ic_star,
                subtitle = (listOf(item.source.name) + item.favorite.path.split('/')).joinToString("  /  "),
                meta = meta,
                chevron = meta == null,
            )
            is Item.Share -> Row(
                item.source.name,
                R.drawable.ic_server,
                subtitle = "\\\\${item.source.host}\\${item.source.share}",
                meta = meta,
                chevron = meta == null,
            )
            Item.Add -> Row("Add network share", R.drawable.ic_add, subtitle = "SMB / Windows file sharing")
        }
    }

    private fun onRowClick(index: Int) {
        when (val item = items.getOrNull(index)) {
            is Item.Fav -> open(item.source, item.favorite.path)
            is Item.Share -> open(item.source, "")
            Item.Add -> showShareDialog(null)
            else -> Unit
        }
    }

    private fun open(source: SmbSource, path: String) {
        startActivity(
            Intent(this, BrowserActivity::class.java)
                .putExtra(BrowserActivity.EXTRA_SOURCE, source.id)
                .putExtra(BrowserActivity.EXTRA_PATH, path)
        )
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val item = moving
        if (item != null) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                when (event.keyCode) {
                    KeyEvent.KEYCODE_DPAD_UP -> move(item, -1)
                    KeyEvent.KEYCODE_DPAD_DOWN -> move(item, 1)
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_BACK -> {
                        moving = null
                        reload()
                        list.focusRow(items.indexOf(item))
                    }
                }
            }
            // Swallow every key in move mode so focus stays on the row being moved.
            return true
        }
        if (event.action == KeyEvent.ACTION_DOWN && event.keyCode == KeyEvent.KEYCODE_MENU) {
            list.focusedChild?.let { showOptions(list.getChildAdapterPosition(it)) }
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    private fun move(item: Item, delta: Int) {
        val moved = when (item) {
            is Item.Fav -> store.moveFavorite(item.favorite.id, delta)
            is Item.Share -> store.moveSource(item.source.id, delta)
            else -> false
        }
        if (moved) {
            reload()
            list.focusRow(items.indexOf(item))
        }
    }

    private fun startMoving(item: Item) {
        moving = item
        reload()
        list.focusRow(items.indexOf(item))
    }

    private fun showOptions(index: Int) {
        when (val item = items.getOrNull(index)) {
            is Item.Fav -> menu(
                item.favorite.name,
                "Open" to { open(item.source, item.favorite.path) },
                "Rename" to {
                    showRenameDialog(item.favorite.name) { store.renameFavorite(item.favorite.id, it) }
                },
                "Move" to { startMoving(item) },
                "Remove from favorites" to {
                    confirm("Remove ${item.favorite.name} from favorites?", "Remove") {
                        store.removeFavorite(item.favorite.id)
                    }
                },
            )
            is Item.Share -> menu(
                item.source.name,
                "Open" to { open(item.source, "") },
                "Rename" to {
                    showRenameDialog(item.source.name) { store.updateSource(item.source.copy(name = it)) }
                },
                "Edit connection" to { showShareDialog(item.source) },
                "Move" to { startMoving(item) },
                "Remove" to {
                    confirm("Remove ${item.source.name}?", "Remove", "Its favorites are removed too.") {
                        store.removeSource(item.source.id)
                    }
                },
            )
            else -> Unit
        }
    }

    private fun menu(title: String, vararg actions: Pair<String, () -> Unit>) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setItems(actions.map { it.first }.toTypedArray()) { _, which -> actions[which].second() }
            .show()
    }

    private fun confirm(title: String, action: String, message: String? = null, onConfirm: () -> Unit) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(action) { _, _ ->
                onConfirm()
                reload()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showRenameDialog(current: String, onRename: (String) -> Unit) {
        val input = EditText(this).apply {
            setText(current)
            setSingleLine(true)
            setSelection(current.length)
        }
        AlertDialog.Builder(this)
            .setTitle("Rename")
            .setView(LinearLayout(this).apply {
                setPadding(48, 24, 48, 0)
                addView(input, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            })
            .setPositiveButton("Save") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) {
                    onRename(name)
                    reload()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Adds a new share, or edits [existing] in place (keeping its id, so favorites and resume points survive). */
    private fun showShareDialog(existing: SmbSource?) {
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 0)
        }

        fun field(hint: String, value: String = "", password: Boolean = false) = EditText(this).apply {
            this.hint = hint
            setText(value)
            setSingleLine(true)
            inputType = if (password) {
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            } else {
                InputType.TYPE_CLASS_TEXT
            }
            form.addView(this)
        }

        val name = field("Name (e.g. Movies NAS)", existing?.name.orEmpty())
        val host = field("Host / IP (e.g. 192.168.1.10)", existing?.host.orEmpty())
        val share = field("Share name (e.g. Media)", existing?.share.orEmpty())
        val port = field("Port", (existing?.port ?: 445).toString())
        val domain = field("Domain (optional)", existing?.domain.orEmpty())
        val user = field("Username (blank = guest)", existing?.user.orEmpty())
        val pass = field("Password", existing?.password.orEmpty(), password = true)

        AlertDialog.Builder(this)
            .setTitle(if (existing == null) "Add SMB share" else "Edit ${existing.name}")
            .setView(ScrollView(this).apply { addView(form) })
            .setPositiveButton("Save", null)
            .setNegativeButton("Cancel", null)
            .create()
            .apply {
                setOnShowListener {
                    getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val h = host.text.toString().trim()
                        val s = share.text.toString().trim()
                        if (h.isEmpty() || s.isEmpty()) {
                            Toast.makeText(this@MainActivity, "Host and share are required", Toast.LENGTH_SHORT).show()
                            return@setOnClickListener
                        }
                        val source = SmbSource(
                            id = existing?.id ?: UUID.randomUUID().toString(),
                            name = name.text.toString().trim().ifEmpty { h },
                            host = h,
                            port = port.text.toString().toIntOrNull() ?: 445,
                            share = s,
                            domain = domain.text.toString().trim(),
                            user = user.text.toString().trim(),
                            password = pass.text.toString(),
                        )
                        if (existing == null) store.addSource(source) else store.updateSource(source)
                        reload()
                        dismiss()
                    }
                }
                show()
            }
    }
}
