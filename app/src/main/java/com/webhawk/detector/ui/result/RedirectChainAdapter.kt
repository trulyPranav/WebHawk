package com.webhawk.detector.ui.result

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.webhawk.detector.data.model.UrlEntry
import com.webhawk.detector.databinding.ItemRedirectBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class RedirectChainAdapter(
    private val entries: List<UrlEntry>
) : RecyclerView.Adapter<RedirectChainAdapter.ViewHolder>() {

    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())

    inner class ViewHolder(val binding: ItemRedirectBinding) :
        RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemRedirectBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val entry = entries[position]
        holder.binding.tvStep.text = (position + 1).toString()
        holder.binding.tvUrl.text = entry.url
        holder.binding.tvTimestamp.text = timeFormat.format(Date(entry.timestamp))
        holder.binding.ivArrow.visibility = if (position < entries.size - 1)
            android.view.View.VISIBLE else android.view.View.GONE
    }

    override fun getItemCount() = entries.size
}
