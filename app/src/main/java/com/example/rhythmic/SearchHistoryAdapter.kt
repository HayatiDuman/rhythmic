import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.rhythmic.R

class SearchHistoryAdapter(
    private var items: List<String>,
    private val isSuggestion: Boolean = false,
    private val onClick: (String) -> Unit
) : RecyclerView.Adapter<SearchHistoryAdapter.VH>() {

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        val icon: ImageView = view.findViewById(R.id.ivIcon)
        val text: TextView = view.findViewById(R.id.tvHistory)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_search_history, parent, false)
        return VH(view)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        holder.text.text = item

        holder.text.setCompoundDrawablesWithIntrinsicBounds(null, null, null, null)

        if (isSuggestion) {
            holder.icon.setImageResource(android.R.drawable.ic_menu_search)
        } else {
            holder.icon.setImageResource(android.R.drawable.ic_menu_recent_history)
        }

        holder.itemView.setOnClickListener {
            onClick(item)
        }
    }

    override fun getItemCount() = items.size

    // 🎯 YENİ: Popup'ı kapatıp açmadan sadece içeriği güncelleme fonksiyonu
    fun updateItems(newItems: List<String>) {
        this.items = newItems
        notifyDataSetChanged()
    }
}