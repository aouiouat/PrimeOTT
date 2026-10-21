package com.primeott.app.presentation.livetv

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.primeott.app.R
import com.primeott.app.databinding.ItemCategoryBinding
import com.primeott.app.databinding.ItemChannelBinding
import com.primeott.app.domain.model.Category
import com.primeott.app.domain.model.Channel
import com.primeott.app.presentation.player.PrimePlayerEngine

class CategoriesAdapter(
    private val items: List<Category>,
    private var selectedId: String,
    private val onSelect: (Category) -> Unit
) : RecyclerView.Adapter<CategoriesAdapter.VH>() {

    inner class VH(val binding: ItemCategoryBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(ItemCategoryBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        val isSelected = item.id == selectedId
        val b = holder.binding

        b.tvCategoryName.text = item.name

        val cleanIconUrl = PrimePlayerEngine.sanitizeImageUrl(item.iconUrl)
        if (cleanIconUrl.isNotEmpty()) {
            Glide.with(holder.itemView.context)
                .asBitmap() // منع Glide من تشغيل VideoDecoder / MediaMetadataRetriever نهائياً
                .load(cleanIconUrl)
                .diskCacheStrategy(DiskCacheStrategy.DATA)
                .circleCrop()
                .placeholder(android.R.drawable.ic_media_play)
                .error(android.R.drawable.ic_media_play)
                .into(b.ivCategoryIcon)
        } else {
            b.ivCategoryIcon.setImageResource(android.R.drawable.ic_media_play)
        }

        if (isSelected) {
            b.categoryRoot.setBackgroundResource(R.drawable.bg_selected_item_crimson)
            b.selectedIndicatorBar.visibility = View.VISIBLE
            b.tvCategoryName.setTextColor(Color.parseColor("#FF1E27"))
        } else {
            b.categoryRoot.setBackgroundColor(Color.TRANSPARENT)
            b.selectedIndicatorBar.visibility = View.INVISIBLE
            b.tvCategoryName.setTextColor(Color.parseColor("#E2E8F0"))
        }

        b.root.setOnClickListener {
            selectedId = item.id
            notifyDataSetChanged()
            onSelect(item)
        }
    }
}

class ChannelsAdapter(
    private val items: List<Channel>,
    private var playingChannelId: String = "",
    private val onChannelClick: (Channel) -> Unit,
    private val onFavoriteClick: (Channel) -> Unit
) : RecyclerView.Adapter<ChannelsAdapter.VH>() {

    inner class VH(val binding: ItemChannelBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(ItemChannelBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val ch = items[position]
        val isPlaying = ch.id == playingChannelId
        val b = holder.binding

        b.tvChannelName.text = ch.name
        b.btnFavorite.text = if (ch.isFavorite) "♥" else "♡"
        b.btnFavorite.setTextColor(
            if (ch.isFavorite) Color.parseColor("#FF1E27") else Color.parseColor("#94A3B8")
        )

        if (isPlaying) {
            b.channelRoot.setBackgroundResource(R.drawable.bg_selected_item_crimson)
            b.tvPlayingArrow.visibility = View.VISIBLE
            b.tvChannelName.setTextColor(Color.parseColor("#FF1E27"))
        } else {
            b.channelRoot.setBackgroundColor(Color.TRANSPARENT)
            b.tvPlayingArrow.visibility = View.GONE
            b.tvChannelName.setTextColor(Color.WHITE)
        }

        b.root.setOnClickListener {
            playingChannelId = ch.id
            notifyDataSetChanged()
            onChannelClick(ch)
        }
        b.btnFavorite.setOnClickListener { onFavoriteClick(ch) }
    }
}