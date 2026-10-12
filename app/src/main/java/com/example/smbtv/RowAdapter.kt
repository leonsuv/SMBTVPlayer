package com.example.smbtv

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.recyclerview.widget.RecyclerView

/**
 * One list row: icon, title, optional subtitle, optional right-aligned meta, optional chevron.
 * A row with [header] set is a non-focusable section label that only shows its title.
 */
data class Row(
    val title: String,
    @DrawableRes val icon: Int = 0,
    val subtitle: String? = null,
    val meta: String? = null,
    val chevron: Boolean = false,
    val header: Boolean = false,
)

/** Focusable rows, used for both the start screen and the file browser. */
class RowAdapter(
    private val onClick: (Int) -> Unit,
    private val onLongClick: ((Int) -> Unit)? = null,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private val items = mutableListOf<Row>()

    fun submit(newItems: List<Row>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val icon: ImageView = view.findViewById(R.id.icon)
        val title: TextView = view.findViewById(R.id.title)
        val subtitle: TextView = view.findViewById(R.id.subtitle)
        val meta: TextView = view.findViewById(R.id.meta)
        val chevron: ImageView = view.findViewById(R.id.chevron)
    }

    class HeaderHolder(view: View) : RecyclerView.ViewHolder(view) {
        val title: TextView = view as TextView
    }

    override fun getItemViewType(position: Int) = if (items[position].header) TYPE_HEADER else TYPE_ROW

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_HEADER) {
            HeaderHolder(inflater.inflate(R.layout.item_header, parent, false))
        } else {
            Holder(inflater.inflate(R.layout.item_row, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val row = items[position]
        if (holder is HeaderHolder) {
            holder.title.text = row.title
            return
        }
        holder as Holder
        holder.icon.setImageResource(row.icon)
        holder.title.text = row.title
        holder.subtitle.setTextOrHide(row.subtitle)
        holder.meta.setTextOrHide(row.meta)
        holder.chevron.visibility = if (row.chevron) View.VISIBLE else View.GONE

        holder.itemView.setOnClickListener { onClick(holder.bindingAdapterPosition) }
        holder.itemView.setOnLongClickListener {
            onLongClick?.invoke(holder.bindingAdapterPosition)
            onLongClick != null
        }
    }

    override fun getItemCount() = items.size

    private companion object {
        const val TYPE_ROW = 0
        const val TYPE_HEADER = 1
    }

    private fun TextView.setTextOrHide(value: String?) {
        text = value
        visibility = if (value.isNullOrEmpty()) View.GONE else View.VISIBLE
    }
}

/** Moves focus to [position] once the row is laid out, scrolling it into view first. */
fun RecyclerView.focusRow(position: Int) {
    if (position < 0) return
    scrollToPosition(position)
    post { findViewHolderForAdapterPosition(position)?.itemView?.requestFocus() ?: requestFocus() }
}
