package com.ghosts.of.history.common.models

import android.content.Context
import android.content.Intent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.ghosts.of.history.R
import com.ghosts.of.history.model.AnchorData
import com.ghosts.of.history.utils.getFileURL
import com.ghosts.of.history.view.EditAnchorActivity
import com.ghosts.of.history.viewmodel.AnchorListActivityViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch


data class ItemModel(
    val anchorData: AnchorData,
    val scope: CoroutineScope,
    val context: Context
)

class ItemAdapter(private val itemList: List<ItemModel>, private val viewModel: AnchorListActivityViewModel) : RecyclerView.Adapter<ItemAdapter.ItemViewHolder>() {

    inner class ItemViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val imageView: ImageView = view.findViewById(R.id.itemImageView)
        val titleView: TextView = view.findViewById(R.id.itemTitleView)
        val descriptionView: TextView = view.findViewById(R.id.itemDescriptionView)
        val checkBox: CheckBox = view.findViewById(R.id.enable_button)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ItemViewHolder {
        val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_layout, parent, false)

        return ItemViewHolder(view)
    }

    private fun processOneItem(holder: ItemViewHolder, item: ItemModel) {
        holder.titleView.text = item.anchorData.name
        holder.descriptionView.text = item.anchorData.description ?: "No description"
        holder.checkBox.isChecked = item.anchorData.isEnabled
        holder.checkBox.setOnCheckedChangeListener { _, isChecked ->
            item.scope.launch {
                viewModel.setEnabled(item.anchorData.anchorId, isChecked)
            }
        }

        item.scope.launch {
            val url = item.anchorData.imageName?.let { getFileURL("images/$it") }
            if (url != null) {
                holder.imageView.load(url) {
                    crossfade(true)
                }
            }
        }
//        holder.imageView.load(item.anchorData.imageName)
//        item.scope.launch {
//            item.anchorData.imageName?.let {imgUrl ->
//                val image = fetchImageFromStorage(imgUrl, item.context).getOrElse { return@let }
//                val bitmap = BitmapFactory.decodeFile(image.absolutePath)
//                holder.imageView.setImageBitmap(bitmap)
//            }
//        }
    }

    override fun onBindViewHolder(holder: ItemViewHolder, position: Int) {
        val item = itemList[position]

        holder.itemView.setOnClickListener {
            onViewHolderClick(it, item.anchorData)
        }
        processOneItem(holder, item)
    }

    private fun onViewHolderClick(view: View, anchorData: AnchorData) {
        Intent(view.context, EditAnchorActivity::class.java).also { intent ->
            intent.putExtra(EditAnchorActivity.PARAM_EDIT_ANCHOR_ID, anchorData.anchorId)
            view.context.startActivity(intent)
        }
    }

    override fun getItemCount() = itemList.size
}
