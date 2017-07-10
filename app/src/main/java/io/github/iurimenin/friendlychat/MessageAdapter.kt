package io.github.iurimenin.friendlychat

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.ImageView
import android.widget.TextView
import com.bumptech.glide.Glide

/**
 * Created by Iuri Menin on 04/07/17.
 */
class MessageAdapter(context: Context, resource: Int, objects: List<FriendlyMessage>) :
        ArrayAdapter<FriendlyMessage>(context, resource, objects) {

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val convertView = LayoutInflater.from(context).inflate(R.layout.item_message, parent, false)

        val photoImageView = convertView.findViewById(R.id.photoImageView) as ImageView
        val messageTextView = convertView.findViewById(R.id.messageTextView) as TextView
        val authorTextView = convertView.findViewById(R.id.nameTextView) as TextView

        val message = getItem(position)

        val isPhoto = !message?.photoUrl.isNullOrBlank()
        if (isPhoto) {
            messageTextView.visibility = View.GONE
            photoImageView.visibility = View.VISIBLE
            Glide.with(photoImageView.context)
                    .load(message.photoUrl)
                    .into(photoImageView)
        } else {
            messageTextView.visibility = View.VISIBLE
            photoImageView.visibility = View.GONE
            messageTextView.text = message.text
        }
        authorTextView.text = message.name

        return convertView
    }
}