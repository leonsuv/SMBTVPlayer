package com.example.smbtv

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.recyclerview.widget.RecyclerView

/** One list row: icon, title, optional subtitle, optional right-aligned meta, optional chevron. */
data class Row(
    val title: String,
    @DrawableRes val icon: Int,
    val subtitle: String? = null,
    val meta: String? = null,
    val chevron: Boolean = false,
)

/** Focusable rows, used for both the share list and the file browser. */
class RowAdapter(
    private val onClick: (Int) -> Unit,
    private val onLongClick: ((Int) -> Unit)? = null,
) : RecyclerView.Adapter<RowAdapter.Holder>() {

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

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_row, parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val row = items[position]
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
