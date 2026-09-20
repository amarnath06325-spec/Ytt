package com.example

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.widget.ImageView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

object ImageLoaderHelper {

    fun loadCircularImage(
        scope: CoroutineScope,
        imageView: ImageView,
        imageUrl: String?,
        fallbackInitial: String? = null
    ) {
        if (imageUrl.isNullOrBlank()) {
            imageView.setImageResource(R.drawable.ic_account_circle)
            return
        }

        scope.launch(Dispatchers.IO) {
            val bitmap = fetchBitmap(imageUrl)
            withContext(Dispatchers.Main) {
                if (bitmap != null) {
                    imageView.setImageBitmap(bitmap)
                } else {
                    imageView.setImageResource(R.drawable.ic_account_circle)
                }
            }
        }
    }

    private fun fetchBitmap(src: String): Bitmap? {
        return try {
            val url = URL(src)
            val connection = url.openConnection() as HttpURLConnection
            connection.doInput = true
            connection.connectTimeout = 4000
            connection.readTimeout = 4000
            connection.connect()
            val input = connection.inputStream
            BitmapFactory.decodeStream(input)
        } catch (_: Exception) {
            null
        }
    }
}
