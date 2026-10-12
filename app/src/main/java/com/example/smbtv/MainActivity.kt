package com.example.smbtv

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class MainActivity : ComponentActivity() {

    private lateinit var store: Store
    private lateinit var list: RecyclerView
    private lateinit var hint: TextView
    private val adapter = RowAdapter(onClick = ::onRowClick, onLongClick = ::confirmRemove)
    private var sources: List<SmbSource> = emptyList()

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
        sources = store.sources()
        val rows = sources.map {
            Row(it.name, R.drawable.ic_server, subtitle = "\\\\${it.host}\\${it.share}", chevron = true)
        } + Row("Add network share", R.drawable.ic_add, subtitle = "SMB / Windows file sharing")
        adapter.submit(rows)
        hint.visibility = if (sources.isEmpty()) View.GONE else View.VISIBLE
    }

    /** The last row is "Add network share"; every other row is a saved share. */
    private fun onRowClick(index: Int) {
        if (index < sources.size) openSource(index) else showAddDialog()
    }

    private fun openSource(index: Int) {
        startActivity(
            Intent(this, BrowserActivity::class.java)
                .putExtra(BrowserActivity.EXTRA_SOURCE, sources[index].id)
        )
    }

    private fun confirmRemove(index: Int) {
        if (index >= sources.size) return
        val source = sources[index]
        AlertDialog.Builder(this)
            .setTitle("Remove ${source.name}?")
            .setPositiveButton("Remove") { _, _ ->
                store.removeSource(source)
                reload()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showAddDialog() {
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

        val name = field("Name (e.g. Movies NAS)")
        val host = field("Host / IP (e.g. 192.168.1.10)")
        val share = field("Share name (e.g. Media)")
        val port = field("Port", "445")
        val domain = field("Domain (optional)")
        val user = field("Username (blank = guest)")
        val pass = field("Password", password = true)

        AlertDialog.Builder(this)
            .setTitle("Add SMB share")
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
                        store.addSource(
                            SmbSource(
                                name = name.text.toString().trim().ifEmpty { h },
                                host = h,
                                port = port.text.toString().toIntOrNull() ?: 445,
                                share = s,
                                domain = domain.text.toString().trim(),
                                user = user.text.toString().trim(),
                                password = pass.text.toString(),
                            )
                        )
                        reload()
                        dismiss()
                    }
                }
                show()
            }
    }
}
