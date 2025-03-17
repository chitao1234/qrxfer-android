package io.github.chitao1234.qrxfer

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.textfield.TextInputEditText
import io.github.chitao1234.qrxfer.databinding.ActivitySendBinding
import io.github.chitao1234.qrxfer.utils.FileTransferProtocol
import io.github.chitao1234.qrxfer.utils.FileUtils
import io.github.chitao1234.qrxfer.utils.PermissionUtils
import io.github.chitao1234.qrxfer.utils.QRCodeUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DecimalFormat
import androidx.core.view.isEmpty

/**
 * Activity for sending files via QR code
 */
class SendActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySendBinding
    private val fileTransferProtocol = FileTransferProtocol()
    private var selectedFileUri: Uri? = null
    private var qrCodeUpdateHandler: Handler? = null
    private var qrCodeUpdateRunnable: Runnable? = null
    private var chunkSize = 200 // Default chunk size in bytes
    private var displayDelay = 1000 // Default delay between chunks in ms
    private var errorCorrectionLevel = "H" // Default error correction level
    private var autoAdvance = true // Auto-advance through chunks
    
    companion object {
        private const val TAG = "SendActivity"
    }
    
    // Activity result launcher for file picking
    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            selectedFileUri = it
            binding.fileNameText.text = FileUtils.getFileName(this, it)
            updateSelectedFileInfo(it)
        }
    }
    
    // Activity result launcher for permissions
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.entries.all { it.value }
        if (allGranted) {
            Toast.makeText(this, getString(R.string.permissions_granted), Toast.LENGTH_SHORT).show()
            openFilePicker()
        } else {
            Toast.makeText(this, getString(R.string.permissions_denied), Toast.LENGTH_SHORT).show()
        }
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivitySendBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
        
        setupUI()
    }
    
    private fun setupUI() {
        // Setup toolbar
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.send_file)
        
        binding.toolbar.setNavigationOnClickListener {
            finish()
        }
        
        // Setup file selection
        binding.selectFileButton.setOnClickListener {
            checkAndRequestStoragePermission()
        }
        
        // Setup chunk size slider
        binding.chunkSizeSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                // Map progress (0-1000) to chunk size (100-2000)
                chunkSize = 100 + progress
                binding.chunkSizeText.text = "$chunkSize bytes"
            }
            
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        
        // Setup delay slider
        binding.delaySeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                // Map progress (0-10000) to delay (100-10000ms)
                displayDelay = 100 + progress
                binding.delayText.text = "$displayDelay ms"
            }
            
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        
        // Setup chunk size direct input
        binding.chunkSizeText.setOnClickListener {
            showInputDialog(
                "Enter Chunk Size (bytes)",
                "Chunk Size",
                chunkSize.toString()
            ) { newValue ->
                val customChunkSize = newValue.toIntOrNull()
                if (customChunkSize != null && customChunkSize > 0) {
                    chunkSize = customChunkSize
                    binding.chunkSizeText.text = "$chunkSize bytes"
                    
                    // Update slider if within range, otherwise max it out
                    if (chunkSize <= 1100) {
                        binding.chunkSizeSeekBar.progress = chunkSize - 100
                    } else {
                        binding.chunkSizeSeekBar.progress = 1000
                    }
                } else {
                    Toast.makeText(this, "Please enter a valid chunk size", Toast.LENGTH_SHORT).show()
                }
            }
        }
        
        // Setup delay direct input
        binding.delayText.setOnClickListener {
            showInputDialog(
                "Enter Delay (milliseconds)",
                "Delay",
                displayDelay.toString()
            ) { newValue ->
                val customDelay = newValue.toIntOrNull()
                if (customDelay != null && customDelay > 0) {
                    displayDelay = customDelay
                    binding.delayText.text = "$displayDelay ms"
                    
                    // Update slider if within range, otherwise max it out
                    if (displayDelay <= 10100) {
                        binding.delaySeekBar.progress = displayDelay - 100
                    } else {
                        binding.delaySeekBar.progress = 10000
                    }
                    
                    // If auto-advance is enabled, restart with new delay
                    if (autoAdvance) {
                        stopQRCodeDisplay()
                        startQRCodeDisplay()
                    }
                } else {
                    Toast.makeText(this, "Please enter a valid delay", Toast.LENGTH_SHORT).show()
                }
            }
        }
        
        // Setup error correction spinner
        val errorCorrectionOptions = arrayOf("L (7%)", "M (15%)", "Q (25%)", "H (30%)")
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, errorCorrectionOptions)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.errorCorrectionSpinner.adapter = adapter
        binding.errorCorrectionSpinner.setSelection(3) // Default to H (30%)
        
        binding.errorCorrectionSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                errorCorrectionLevel = when(position) {
                    0 -> "L"
                    1 -> "M"
                    2 -> "Q"
                    else -> "H"
                }
            }
            
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        
        // Setup auto-advance switch
        binding.autoAdvanceSwitch.isChecked = autoAdvance
        binding.autoAdvanceSwitch.setOnCheckedChangeListener { _, isChecked ->
            autoAdvance = isChecked
            if (isChecked) {
                startQRCodeDisplay()
            } else {
                stopQRCodeDisplay()
            }
        }
        
        // Setup manual navigation buttons
        binding.prevChunkButton.setOnClickListener {
            navigateChunk(-1)
        }
        
        binding.nextChunkButton.setOnClickListener {
            navigateChunk(1)
        }
        
        // Generate QR button
        binding.generateQrButton.setOnClickListener {
            selectedFileUri?.let { uri ->
                processSelectedFile(uri)
            } ?: run {
                Toast.makeText(this, getString(R.string.no_file_selected), Toast.LENGTH_SHORT).show()
            }
        }
    }
    
    private fun navigateChunk(direction: Int) {
        if (fileTransferProtocol.getTotalChunks() == 0) return
        
        val currentChunk = fileTransferProtocol.getCurrentChunkIndex()
        val totalChunks = fileTransferProtocol.getTotalChunks()
        
        // Calculate new index with wrap-around
        val newIndex = (currentChunk + direction + totalChunks) % totalChunks
        
        // Update UI
        displayQRCode(newIndex)
    }
    
    private fun displayQRCode(index: Int) {
        // Safety check
        if (index < 0) {
            Log.e(TAG, "Invalid index: $index")
            return
        }
        
        // Set the current chunk index
        fileTransferProtocol.setCurrentChunkIndex(index)
        
        // Get the chunk at the specified index
        val chunk = fileTransferProtocol.getChunkAt(index)
        
        Log.d(TAG, "displayQRCode for index: $index, chunk: ${chunk != null}")
        
        chunk?.let {
            // Update QR code display
            val qrBitmap = QRCodeUtils.generateQRCode(it, errorCorrectionLevel)
            if (qrBitmap != null) {
                Log.d(TAG, "QR code generated successfully, updating ImageView")
                binding.qrCodeImageView.setImageBitmap(qrBitmap)
                
                // Update chunk info
                val totalChunks = fileTransferProtocol.getTotalChunks()
                if (totalChunks > 0) {
                    binding.chunkInfoText.text = getString(R.string.chunk_info, index + 1, totalChunks)
                    
                    // Update progress
                    val progress = ((index + 1) * 100) / totalChunks
                    binding.sendProgressBar.progress = progress
                }
            } else {
                // QR code generation failed
                Log.e(TAG, "Failed to generate QR code for chunk $index")
                Toast.makeText(this, getString(R.string.qr_generation_failed), Toast.LENGTH_SHORT).show()
            }
        } ?: run {
            Log.e(TAG, "No chunk found at index $index")
        }
    }
    
    private fun updateSelectedFileInfo(uri: Uri) {
        lifecycleScope.launch {
            val fileSize = FileUtils.getFileSize(this@SendActivity, uri)
            val formattedSize = formatFileSize(fileSize)
            binding.fileNameText.text = "${FileUtils.getFileName(this@SendActivity, uri)} ($formattedSize)"
            
            // Enable generate button
            binding.generateQrButton.isEnabled = true
        }
    }
    
    private fun formatFileSize(size: Long): String {
        val formatter = DecimalFormat("#,###.##")
        return when {
            size < 1024 -> "$size B"
            size < 1024 * 1024 -> "${formatter.format(size / 1024.0)} KB"
            size < 1024 * 1024 * 1024 -> "${formatter.format(size / (1024.0 * 1024.0))} MB"
            else -> "${formatter.format(size / (1024.0 * 1024.0 * 1024.0))} GB"
        }
    }
    
    private fun processSelectedFile(uri: Uri) {
        lifecycleScope.launch {
            try {
                // Show progress
                withContext(Dispatchers.Main) {
                    binding.sendProgressBar.visibility = View.VISIBLE
                    binding.sendProgressBar.isIndeterminate = true
                    binding.generateQrButton.isEnabled = false
                }
                
                // Process the file
                Log.d(TAG, "Starting file processing: $uri")
                val chunksCount = fileTransferProtocol.processFile(this@SendActivity, uri, chunkSize)
                Log.d(TAG, "File processing complete: $chunksCount chunks created")
                
                withContext(Dispatchers.Main) {
                    if (chunksCount > 0) {
                        // Setup UI for QR display
                        binding.sendProgressBar.isIndeterminate = false
                        binding.sendProgressBar.progress = 0
                        binding.sendProgressBar.max = 100
                        
                        // Make QR display card visible
                        binding.qrDisplayCard.visibility = View.VISIBLE
                        
                        binding.qrControlsLayout.visibility = View.VISIBLE
                        binding.chunkInfoText.visibility = View.VISIBLE
                        binding.chunkInfoText.text = getString(R.string.chunk_info, 1, fileTransferProtocol.getTotalChunks())
                        
                        // Create chunk indicators
                        createChunkIndicators(fileTransferProtocol.getTotalChunks())
                        
                        // Display first QR code
                        Log.d(TAG, "Displaying first QR code")
                        displayQRCode(0)
                        
                        // Start auto-display if enabled
                        if (autoAdvance) {
                            startQRCodeDisplay()
                        }
                    } else {
                        Toast.makeText(
                            this@SendActivity,
                            getString(R.string.error_processing_file, "Failed to process file"),
                            Toast.LENGTH_SHORT
                        ).show()
                        binding.sendProgressBar.visibility = View.GONE
                        binding.generateQrButton.isEnabled = true
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error processing file: ${e.message}")
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@SendActivity,
                        getString(R.string.error_processing_file, e.message ?: "Unknown error"),
                        Toast.LENGTH_SHORT
                    ).show()
                    binding.sendProgressBar.visibility = View.GONE
                    binding.generateQrButton.isEnabled = true
                }
            }
        }
    }
    
    private fun createChunkIndicators(totalChunks: Int) {
        binding.chunksContainer.removeAllViews()
        
        for (i in 0 until totalChunks) {
            val indicator = layoutInflater.inflate(R.layout.chunk_indicator, binding.chunksContainer, false)
            indicator.tag = i
            // Set the chunk number (i+1) in the TextView
            val chunkNumberText = indicator.findViewById<TextView>(R.id.chunkNumber)
            chunkNumberText.text = i.toString()
            binding.chunksContainer.addView(indicator)
        }
    }
    
    private fun updateChunkIndicator(index: Int) {
        // Safety check
        if (index < 0 || binding.chunksContainer.isEmpty()) {
            return
        }
        
        // Find the indicator view by tag
        for (i in 0 until binding.chunksContainer.childCount) {
            val indicator = binding.chunksContainer.getChildAt(i)
            if (indicator != null && indicator.tag as? Int == index) {
                indicator.setBackgroundResource(R.drawable.chunk_indicator_sent)
                break
            }
        }
    }
    
    private fun startQRCodeDisplay() {
        // Stop any existing display
        stopQRCodeDisplay()
        
        // Make sure we have chunks to display
        val totalChunks = fileTransferProtocol.getTotalChunks()
        if (totalChunks == 0) {
            Log.e(TAG, "Cannot start QR code display with 0 chunks")
            return
        }
        
        // Create new handler and runnable
        qrCodeUpdateHandler = Handler(Looper.getMainLooper())
        qrCodeUpdateRunnable = object : Runnable {
            override fun run() {
                val currentIndex = fileTransferProtocol.getCurrentChunkIndex()
                val totalChunks = fileTransferProtocol.getTotalChunks()
                
                // Safety check to prevent divide by zero
                if (totalChunks == 0) {
                    Log.e(TAG, "Total chunks became 0 during display, stopping auto-advance")
                    stopQRCodeDisplay()
                    return
                }
                
                val nextIndex = (currentIndex + 1) % totalChunks
                
                // Display the next QR code
                displayQRCode(nextIndex)
                
                // Update the chunk indicator
                updateChunkIndicator(nextIndex)
                
                // Schedule the next update
                qrCodeUpdateHandler?.postDelayed(this, displayDelay.toLong())
            }
        }
        
        // Start the display
        qrCodeUpdateHandler?.post(qrCodeUpdateRunnable!!)
        binding.autoAdvanceSwitch.isChecked = true
    }
    
    private fun stopQRCodeDisplay() {
        qrCodeUpdateRunnable?.let {
            qrCodeUpdateHandler?.removeCallbacks(it)
        }
        qrCodeUpdateHandler = null
        qrCodeUpdateRunnable = null
    }
    
    override fun onPause() {
        super.onPause()
        stopQRCodeDisplay()
    }
    
    override fun onDestroy() {
        super.onDestroy()
        stopQRCodeDisplay()
    }
    
    private fun checkAndRequestStoragePermission() {
        if (PermissionUtils.hasStoragePermissions(this)) {
            openFilePicker()
        } else {
            PermissionUtils.requestStoragePermissions(this)
        }
    }
    
    private fun openFilePicker() {
        filePickerLauncher.launch("*/*")
    }
    
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        
        if (requestCode == PermissionUtils.REQUEST_STORAGE_PERMISSION) {
            if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                openFilePicker()
            } else {
                Toast.makeText(this, getString(R.string.storage_permission_required), Toast.LENGTH_SHORT).show()
            }
        }
    }
    
    /**
     * Show an input dialog to allow the user to enter a custom value
     */
    private fun showInputDialog(title: String, hint: String, defaultValue: String, onConfirm: (String) -> Unit) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_input, null)
        val editText = dialogView.findViewById<EditText>(R.id.inputEditText)
        editText.hint = hint
        editText.setText(defaultValue)
        editText.setSelection(defaultValue.length)
        
        val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(title)
            .setView(dialogView)
            .setPositiveButton("Apply") { _, _ -> 
                onConfirm(editText.text.toString())
            }
            .setNegativeButton("Cancel", null)
            .create()
        
        dialog.show()
    }
}
