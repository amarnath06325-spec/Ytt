package com.example

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.databinding.ActivityDownloadsBinding
import com.example.databinding.ItemDownloadBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class DownloadsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDownloadsBinding
    private lateinit var adapter: DownloadsAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDownloadsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupToolbar()
        setupRecyclerView()
        loadDownloads()
    }

    override fun onResume() {
        super.onResume()
        loadDownloads()
    }

    private fun setupToolbar() {
        binding.btnBack.setOnClickListener {
            finish()
        }

        binding.btnClearAll.setOnClickListener {
            val list = DownloadHistoryStore.getDownloads(this)
            if (list.isEmpty()) {
                Toast.makeText(this, "Downloads list is empty", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            MaterialAlertDialogBuilder(this)
                .setTitle("Clear Downloads History?")
                .setMessage("This will clear the download history list. Downloaded files in your device's Download folder will remain intact.")
                .setPositiveButton("Clear") { _, _ ->
                    DownloadHistoryStore.clearAll(this)
                    loadDownloads()
                    Toast.makeText(this, "Downloads history cleared", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private fun setupRecyclerView() {
        adapter = DownloadsAdapter(
            onItemClick = { item -> openDownloadedFile(item) },
            onDeleteClick = { item ->
                DownloadHistoryStore.removeDownload(this, item.id)
                loadDownloads()
            }
        )
        binding.rvDownloads.layoutManager = LinearLayoutManager(this)
        binding.rvDownloads.adapter = adapter
    }

    private fun loadDownloads() {
        val list = DownloadHistoryStore.getDownloads(this)
        if (list.isEmpty()) {
            binding.rvDownloads.visibility = View.GONE
            binding.layoutEmptyState.visibility = View.VISIBLE
        } else {
            binding.layoutEmptyState.visibility = View.GONE
            binding.rvDownloads.visibility = View.VISIBLE
            adapter.submitList(list)
        }
    }

    private fun openDownloadedFile(item: DownloadItem) {
        val filePath = item.filePath
        if (!filePath.isNullOrBlank()) {
            val file = File(filePath)
            if (file.exists()) {
                try {
                    val uri: Uri = FileProvider.getUriForFile(
                        this,
                        "${applicationContext.packageName}.fileprovider",
                        file
                    )
                    val mimeType = item.mimeType?.takeIf { it.isNotBlank() } ?: "*/*"
                    val intent = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(uri, mimeType)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    startActivity(Intent.createChooser(intent, "Open with"))
                    return
                } catch (e: Exception) {
                    Toast.makeText(this, "Unable to open file: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }

        // Fallback: file might be located in standard public Downloads folder
        val downloadsDir = android.os.Environment.getExternalStoragePublicDirectory(
            android.os.Environment.DIRECTORY_DOWNLOADS
        )
        val candidate = File(downloadsDir, item.fileName)
        if (candidate.exists()) {
            try {
                val uri: Uri = FileProvider.getUriForFile(
                    this,
                    "${applicationContext.packageName}.fileprovider",
                    candidate
                )
                val mimeType = item.mimeType?.takeIf { it.isNotBlank() } ?: "*/*"
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, mimeType)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(Intent.createChooser(intent, "Open with"))
                return
            } catch (e: Exception) {
                Toast.makeText(this, "Unable to open file: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }

        Toast.makeText(this, "File not found or still downloading: ${item.fileName}", Toast.LENGTH_SHORT).show()
    }
}

class DownloadsAdapter(
    private val onItemClick: (DownloadItem) -> Unit,
    private val onDeleteClick: (DownloadItem) -> Unit
) : RecyclerView.Adapter<DownloadsAdapter.DownloadViewHolder>() {

    private var items = listOf<DownloadItem>()

    fun submitList(newItems: List<DownloadItem>) {
        items = newItems
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DownloadViewHolder {
        val binding = ItemDownloadBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return DownloadViewHolder(binding)
    }

    override fun onBindViewHolder(holder: DownloadViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class DownloadViewHolder(private val binding: ItemDownloadBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(item: DownloadItem) {
            binding.tvFileName.text = item.fileName

            val dateStr = SimpleDateFormat("MMM d, h:mm a", Locale.getDefault()).format(Date(item.timestamp))
            val sizeStr = formatFileSize(item.fileSize)
            val statusLabel = when (item.status) {
                "DOWNLOADING" -> "Downloading…"
                "FAILED" -> "Failed"
                else -> "Completed"
            }

            binding.tvFileInfo.text = if (sizeStr.isNotBlank()) {
                "$sizeStr • $statusLabel • $dateStr"
            } else {
                "$statusLabel • $dateStr"
            }

            if (item.status == "DOWNLOADING") {
                binding.pbDownloadProgress.visibility = View.VISIBLE
            } else {
                binding.pbDownloadProgress.visibility = View.GONE
            }

            // Choose icon according to file extension / mime type
            val ext = item.fileName.substringAfterLast('.', "").lowercase()
            when {
                ext in listOf("pdf") -> binding.ivFileIcon.setImageResource(R.drawable.ic_bookmark)
                ext in listOf("png", "jpg", "jpeg", "webp", "gif") -> binding.ivFileIcon.setImageResource(R.drawable.ic_desktop)
                ext in listOf("mp4", "webm", "mkv", "avi") -> binding.ivFileIcon.setImageResource(R.drawable.ic_arrow_forward)
                ext in listOf("html", "htm") -> binding.ivFileIcon.setImageResource(R.drawable.ic_shield)
                else -> binding.ivFileIcon.setImageResource(R.drawable.ic_download)
            }

            binding.cardDownloadItem.setOnClickListener {
                onItemClick(item)
            }

            binding.btnDelete.setOnClickListener {
                onDeleteClick(item)
            }
        }

        private fun formatFileSize(size: Long): String {
            if (size <= 0) return ""
            return when {
                size < 1024 -> "$size B"
                size < 1024 * 1024 -> String.format(Locale.US, "%.1f KB", size / 1024.0)
                else -> String.format(Locale.US, "%.1f MB", size / (1024.0 * 1024.0))
            }
        }
    }
}
