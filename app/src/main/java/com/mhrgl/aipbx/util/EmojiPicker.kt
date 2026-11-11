package com.mhrgl.aipbx.util

import android.app.Activity
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView

/**
 * The emoji grid shared by SMS and the internal chat. Same list and layout in both places, so an
 * emoji looks the same wherever it is typed. Tapping an emoji inserts it at the cursor of the given
 * text field.
 */
object EmojiPicker {

    val EMOJIS: List<String> =
        ("😀 😃 😄 😁 😆 😅 😂 🤣 😊 😇 🙂 😉 😍 🥰 😘 😋 😜 🤪 😎 🤩 🥳 😏 😒 😞 😔 😟 😕 🙁 😣 😫 " +
                "😩 🥺 😢 😭 😤 😠 😡 🤯 😳 😱 😨 😰 🤗 🤔 🤭 🤫 😶 😐 😑 😬 🙄 😯 😴 🤤 😷 🤒 🤕 🤢 🤮 🥴 " +
                "😵 🤠 👍 👎 👌 ✌️ 🤞 🤟 🤘 👏 🙌 👐 🙏 💪 👋 👀 ❤️ 🧡 💛 💚 💙 💜 🖤 🤍 💔 💯 🔥 ✨ ⭐ 🎉 " +
                "🎊 🎁 🎂 ☕ 🍕 🍔 🍺 🍷 ⚽ 🏀 🚗 ✈️ 🏠 📞 📱 💻 📷 🎵 ✅ ❌ ⚠️ ❓ ❗ 💤 🌞 🌙 🌧️ ❄️ 🐶 🐱")
            .split(" ")

    /** Shows the grid; tapping an emoji inserts it at the cursor of [target]. */
    fun show(activity: Activity, target: EditText) {
        val dp = { v: Int ->
            TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_DIP,
                    v.toFloat(),
                    activity.resources.displayMetrics,
                )
                .toInt()
        }
        val grid =
            RecyclerView(activity).apply {
                layoutManager = GridLayoutManager(activity, 7)
                setPadding(dp(8), dp(8), dp(8), dp(8))
            }
        val dialog = AlertDialog.Builder(activity).setView(grid).create()
        grid.adapter =
            object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
                override fun onCreateViewHolder(
                    parent: ViewGroup,
                    viewType: Int,
                ): RecyclerView.ViewHolder {
                    val tv =
                        TextView(parent.context).apply {
                            setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
                            gravity = Gravity.CENTER
                            setPadding(0, dp(6), 0, dp(6))
                            layoutParams =
                                ViewGroup.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    ViewGroup.LayoutParams.WRAP_CONTENT,
                                )
                        }
                    return object : RecyclerView.ViewHolder(tv) {}
                }

                override fun getItemCount() = EMOJIS.size

                override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                    val tv = holder.itemView as TextView
                    tv.text = EMOJIS[position]
                    tv.setOnClickListener {
                        val start = target.selectionStart.coerceAtLeast(0)
                        val end = target.selectionEnd.coerceAtLeast(0)
                        target.text.replace(minOf(start, end), maxOf(start, end), EMOJIS[position])
                        target.requestFocus()
                        dialog.dismiss()
                    }
                }
            }
        dialog.show()
    }
}
