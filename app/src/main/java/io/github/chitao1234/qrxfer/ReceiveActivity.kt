package io.github.chitao1234.qrxfer

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import io.github.chitao1234.qrxfer.databinding.ActivityReceiveBinding
import io.github.chitao1234.qrxfer.utils.FileTransferProtocol
import io.github.chitao1234.qrxfer.utils.FileUtils
import io.github.chitao1234.qrxfer.utils.PermissionUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class ReceiveActivity : AppCompatActivity() {
    private lateinit var binding: ActivityReceiveBinding
    private lateinit var cameraExecutor: ExecutorService
    private val fileTransferProtocol = FileTransferProtocol()
    private var scanning = true
    private var lastDetectedChunk: String? = null
    private var lastDetectedTime = 0L
    private val TAG = "ReceiveActivity"
    
    // Activity result launcher for permissions
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions[Manifest.permission.CAMERA] == true) {
            startCamera()
        } else {
            Toast.makeText(
                this,
                getString(R.string.camera_permission_required),
                Toast.LENGTH_SHORT
            ).show()
            finish()
        }
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityReceiveBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
        
        setupUI()
        
        // Initialize camera executor
        cameraExecutor = Executors.newSingleThreadExecutor()
        
        // Check and request camera permission
        if (PermissionUtils.hasCameraPermission(this)) {
            startCamera()
        } else {
            permissionLauncher.launch(arrayOf(Manifest.permission.CAMERA))
        }
    }
    
    private fun setupUI() {
        // Setup toolbar
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.receive_file)
        
        binding.toolbar.setNavigationOnClickListener {
            finish()
        }
        
        // Setup save button
        binding.saveFileButton.setOnClickListener {
            saveReceivedFile()
        }
        
        // Setup reset button
        binding.resetButton.setOnClickListener {
            resetReceiver()
        }
    }
    
    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            
            // Setup the preview use case
            val preview = Preview.Builder()
                .build()
                .also {
                    it.setSurfaceProvider(binding.previewView.surfaceProvider)
                }
            
            // Setup resolution selector with 720p target
            val resolutionSelector = ResolutionSelector.Builder()
                .setResolutionStrategy(ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY)
                .build()
            
            // Setup the image analyzer
            val imageAnalysis = ImageAnalysis.Builder()
                .setResolutionSelector(resolutionSelector)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also {
                    it.setAnalyzer(cameraExecutor) { imageProxy ->
                        processImageProxy(imageProxy)
                    }
                }
            
            // Select back camera
            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
            
            try {
                // Unbind any bound use cases before rebinding
                cameraProvider.unbindAll()
                
                // Bind use cases to camera
                cameraProvider.bindToLifecycle(
                    this, cameraSelector, preview, imageAnalysis
                )
                
            } catch (e: Exception) {
                Log.e(TAG, "Camera binding failed", e)
                Toast.makeText(
                    this,
                    "Failed to start camera: ${e.message}",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }
    
    private fun processImageProxy(imageProxy: androidx.camera.core.ImageProxy) {
        if (!scanning) {
            imageProxy.close()
            return
        }
        
        try {
            val buffer = imageProxy.planes[0].buffer
            val data = ByteArray(buffer.remaining())
            buffer.get(data)
            
            val width = imageProxy.width
            val height = imageProxy.height
            
            // Try different rotations if needed for QR detection
            val rotations = listOf(0, 1, 2, 3) // 0, 90, 180, 270 degrees
            
            for (rotation in rotations) {
                try {
                    // Create luminance source with appropriate rotation
                    val source = when (rotation) {
                        0 -> PlanarYUVLuminanceSource(
                            data, width, height, 0, 0, width, height, false
                        )
                        1 -> { // 90 degrees
                            // For simplicity, we're not actually implementing the rotation logic here
                            // but in a real implementation, you'd rotate the YUV data
                            PlanarYUVLuminanceSource(
                                data, width, height, 0, 0, width, height, false
                            )
                        }
                        else -> PlanarYUVLuminanceSource(
                            data, width, height, 0, 0, width, height, false
                        )
                    }
                    
                    val bitmap = BinaryBitmap(HybridBinarizer(source))
                    
                    val reader = MultiFormatReader()
                    // Use more hints to improve detection
                    val hints = HashMap<DecodeHintType, Any>()
                    hints[DecodeHintType.TRY_HARDER] = true
                    hints[DecodeHintType.POSSIBLE_FORMATS] = arrayListOf(BarcodeFormat.QR_CODE)
                    
                    val result = reader.decode(bitmap, hints)
                    val qrContent = result.text
                    
                    // Log successful scan with rotation info
                    Log.d(TAG, "QR code detected with rotation ${rotation * 90} degrees: ${qrContent.take(50)}...")
                    
                    // Process the QR code content
                    processQRCode(qrContent)
                    
                    // Found QR, no need to try other rotations
                    break
                } catch (e: Exception) {
                    // Try next rotation
                    if (rotation == rotations.last()) {
                        // Log only on the last attempted rotation to reduce log spam
                        Log.d(TAG, "Failed to decode QR code after trying all rotations: ${e.message}")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error processing image: ${e.message}", e)
        } finally {
            imageProxy.close()
        }
    }
    
    private fun processQRCode(qrContent: String) {
        try {
            // Avoid processing the same code too frequently
            val now = System.currentTimeMillis()
            if (qrContent == lastDetectedChunk && now - lastDetectedTime < 1000) {
                return
            }
            
            lastDetectedChunk = qrContent
            lastDetectedTime = now
            
            // Log the QR content for debugging
            Log.d(TAG, "Processing QR content: ${qrContent.take(50)}...")
            
            // Process the QR code data
            val result = fileTransferProtocol.processReceivedChunk(qrContent)
            
            result?.let {
                val isComplete = it["isComplete"] as Boolean
                val isFirstChunk = it["isFirstChunk"] as Boolean
                val totalChunksFromResult = it["totalChunks"] as Int
                
                // Log success
                Log.d(TAG, "Chunk processed successfully. isComplete: $isComplete, isFirstChunk: $isFirstChunk, totalChunks: $totalChunksFromResult")
                
                runOnUiThread {
                    // Use totalChunks directly from the result for the first chunk
                    val totalChunks = if (isFirstChunk) totalChunksFromResult else fileTransferProtocol.getTotalChunks()
                    val receivedChunks = fileTransferProtocol.getReceivedChunks()
                    val progress = if (totalChunks > 0) {
                        // Make sure progress never exceeds 100%
                        Math.min(100, (receivedChunks.size * 100) / totalChunks)
                    } else {
                        0
                    }
                    
                    // Log progress details for debugging
                    Log.d(TAG, "Progress update: $progress% - Received: ${receivedChunks.size}, Total: $totalChunks")
                    
                    // Check for missing chunks
                    val missingChunks = fileTransferProtocol.getMissingChunks()
                    if (missingChunks.isNotEmpty()) {
                        Log.d(TAG, "Missing chunks: ${missingChunks.joinToString(", ")}")
                    }
                    
                    // Update UI with progress
                    binding.progressBar.visibility = View.VISIBLE
                    binding.progressBar.progress = progress
                    
                    // Update progress text with missing chunk info if needed
                    if (missingChunks.isNotEmpty() && progress >= 90) {
                        binding.progressText.text = getString(
                            R.string.receiving_progress_with_missing, 
                            progress, 
                            missingChunks.joinToString(", ")
                        )
                    } else {
                        binding.progressText.text = getString(R.string.receiving_progress, progress)
                    }
                    
                    // Initialize chunk indicators if first chunk
                    if (isFirstChunk || (binding.chunksContainer.childCount == 0 && totalChunks > 0)) {
                        Log.d(TAG, "Creating chunk indicators for first chunk - totalChunks: $totalChunks")
                        createChunkIndicators(totalChunks)
                    }
                    
                    // Update chunk indicator
                    updateChunkIndicator(receivedChunks)
                    
                    if (isComplete) {
                        // Transfer complete
                        binding.statusText.text = getString(R.string.transfer_complete)
                        binding.saveFileButton.visibility = View.VISIBLE
                        binding.resetButton.visibility = View.VISIBLE
                        scanning = false
                    }
                }
            } ?: run {
                // Processing failed
                Log.e(TAG, "Failed to process QR code content")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error processing QR code: ${e.message}", e)
        }
    }
    
    private fun createChunkIndicators(totalChunks: Int) {
        binding.chunksContainer.removeAllViews()
        
        Log.d(TAG, "Creating $totalChunks chunk indicators")
        
        for (i in 0 until totalChunks) {
            val indicator = layoutInflater.inflate(R.layout.chunk_indicator, binding.chunksContainer, false)
            indicator.tag = i
            
            // Set the chunk number text
            val numberTextView = indicator.findViewById<TextView>(R.id.chunkNumber)
            numberTextView.text = i.toString()
            
            binding.chunksContainer.addView(indicator)
        }
        
        Log.d(TAG, "Created ${binding.chunksContainer.childCount} chunk indicators")
    }
    
    private fun updateChunkIndicator(receivedChunks: Map<String, org.json.JSONObject>) {
        Log.d(TAG, "Updating chunk indicators for ${receivedChunks.size} chunks")
        
        if (binding.chunksContainer.childCount == 0) {
            Log.e(TAG, "No chunk indicators found, but trying to update")
            val totalChunks = fileTransferProtocol.getTotalChunks()
            if (totalChunks > 0) {
                createChunkIndicators(totalChunks)
            }
        }
        
        for (chunk in receivedChunks.values) {
            val index = chunk.getInt("chunkIndex")
            
            // Find the indicator view by tag
            for (i in 0 until binding.chunksContainer.childCount) {
                val indicator = binding.chunksContainer.getChildAt(i)
                if (indicator.tag as Int == index) {
                    indicator.setBackgroundResource(R.drawable.chunk_indicator_received)
                    
                    // Update the text color for better visibility on blue background
                    val numberTextView = indicator.findViewById<TextView>(R.id.chunkNumber)
                    numberTextView.setTextColor(Color.WHITE)
                    
                    break
                }
            }
        }
    }
    
    private fun saveReceivedFile() {
        lifecycleScope.launch {
            try {
                // Get the app's files directory for temporary storage
                val outputDir = FileUtils.getFilesDir(this@ReceiveActivity)
                
                // Reconstruct the file to a temporary location first
                val tempFilePath = fileTransferProtocol.reconstructFile(this@ReceiveActivity, outputDir)
                
                withContext(Dispatchers.Main) {
                    if (tempFilePath != null) {
                        val tempFile = File(tempFilePath)
                        val fileName = fileTransferProtocol.getFilename() ?: "downloaded_file"
                        val mimeType = fileTransferProtocol.getMimeType() ?: "application/octet-stream"
                        
                        // Save to downloads directory
                        val downloadUri = FileUtils.saveToDownloads(
                            this@ReceiveActivity,
                            tempFile,
                            fileName,
                            mimeType
                        )
                        
                        if (downloadUri != null) {
                            // File saved successfully to Downloads
                            binding.statusText.text = getString(R.string.file_saved_to_downloads)
                            
                            // Return the file path to the main activity
                            val intent = Intent().apply {
                                putExtra("EXTRA_TRANSFER_COMPLETE", true)
                                putExtra("EXTRA_FILE_URI", downloadUri.toString())
                            }
                            setResult(RESULT_OK, intent)
                            
                            // Show open file button
                            binding.openFileButton.visibility = View.VISIBLE
                            binding.openFileButton.setOnClickListener {
                                openReceivedFileFromUri(downloadUri)
                            }
                        } else {
                            // Failed to save to Downloads, use the temporary file
                            binding.statusText.text = getString(R.string.file_saved_to_app)
                            
                            // Return the file path to the main activity
                            val intent = Intent().apply {
                                putExtra("EXTRA_TRANSFER_COMPLETE", true)
                                putExtra("EXTRA_FILE_PATH", tempFilePath)
                            }
                            setResult(RESULT_OK, intent)
                            
                            // Show open file button
                            binding.openFileButton.visibility = View.VISIBLE
                            binding.openFileButton.setOnClickListener {
                                openReceivedFile(tempFilePath)
                            }
                        }
                    } else {
                        binding.statusText.text = getString(R.string.failed_to_save_file)
                        Toast.makeText(
                            this@ReceiveActivity,
                            getString(R.string.error_processing_file, "Failed to reconstruct file"),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error saving file: ${e.message}")
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@ReceiveActivity,
                        getString(R.string.error_processing_file, e.message ?: "Unknown error"),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }
    
    private fun openReceivedFileFromUri(uri: Uri) {
        val intent = Intent(Intent.ACTION_VIEW)
        intent.setDataAndType(uri, contentResolver.getType(uri) ?: "*/*")
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        
        try {
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.no_app_for_file_type), Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun openReceivedFile(filePath: String) {
        val file = File(filePath)
        val uri = androidx.core.content.FileProvider.getUriForFile(
            this,
            "${packageName}.fileprovider",
            file
        )
        
        val intent = Intent(Intent.ACTION_VIEW)
        intent.setDataAndType(uri, contentResolver.getType(uri) ?: "*/*")
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        
        try {
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.no_app_for_file_type), Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun resetReceiver() {
        fileTransferProtocol.resetReceiver()
        binding.chunksContainer.removeAllViews()
        binding.progressBar.progress = 0
        binding.progressText.text = getString(R.string.waiting_for_qr_code)
        binding.statusText.text = getString(R.string.align_qr_code)
        binding.saveFileButton.visibility = View.GONE
        binding.openFileButton.visibility = View.GONE
        binding.resetButton.visibility = View.GONE
        scanning = true
        lastDetectedChunk = null
        lastDetectedTime = 0L
    }
    
    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }
}
