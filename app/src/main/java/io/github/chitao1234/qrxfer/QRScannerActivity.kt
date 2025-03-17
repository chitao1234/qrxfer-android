package io.github.chitao1234.qrxfer

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.zxing.BinaryBitmap
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import io.github.chitao1234.qrxfer.databinding.ActivityQrScannerBinding
import io.github.chitao1234.qrxfer.utils.FileTransferProtocol
import io.github.chitao1234.qrxfer.utils.FileUtils
import io.github.chitao1234.qrxfer.utils.PermissionUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class QRScannerActivity : AppCompatActivity() {
    private lateinit var binding: ActivityQrScannerBinding
    private lateinit var cameraExecutor: ExecutorService
    private val fileTransferProtocol = FileTransferProtocol()
    private var scanning = true
    
    companion object {
        private const val TAG = "QRScannerActivity"
        private const val REQUEST_CAMERA_PERMISSION = 100
        const val EXTRA_TRANSFER_COMPLETE = "transfer_complete"
        const val EXTRA_FILE_PATH = "file_path"
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityQrScannerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        cameraExecutor = Executors.newSingleThreadExecutor()
        
        binding.finishScanningButton.setOnClickListener {
            finishScanning()
        }
        
        // Request camera permission if needed
        if (allPermissionsGranted()) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.CAMERA), REQUEST_CAMERA_PERMISSION
            )
        }
    }
    
    private fun allPermissionsGranted() = ContextCompat.checkSelfPermission(
        baseContext, Manifest.permission.CAMERA
    ) == PackageManager.PERMISSION_GRANTED
    
    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CAMERA_PERMISSION) {
            if (allPermissionsGranted()) {
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
    }
    
    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(binding.previewView.surfaceProvider)
            }
            
            val imageAnalyzer = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also {
                    it.setAnalyzer(cameraExecutor, QRCodeAnalyzer { qrContent ->
                        processQRCode(qrContent)
                    })
                }
            
            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    imageAnalyzer
                )
            } catch (e: Exception) {
                Log.e(TAG, "Use case binding failed", e)
            }
        }, ContextCompat.getMainExecutor(this))
    }
    
    private fun processQRCode(qrContent: String) {
        if (!scanning) return
        
        try {
            val result = fileTransferProtocol.processReceivedChunk(qrContent)
            
            result?.let {
                val isComplete = it["isComplete"] as Boolean
                
                runOnUiThread {
                    val totalChunks = fileTransferProtocol.getTotalChunks()
                    val receivedChunks = fileTransferProtocol.getReceivedChunks()
                    val progress = if (totalChunks > 0) {
                        (receivedChunks.size * 100) / totalChunks
                    } else {
                        0
                    }
                    
                    binding.scannerProgressBar.visibility = View.VISIBLE
                    binding.scannerProgressBar.progress = progress
                    binding.scannerStatusTextView.text = getString(R.string.receiving_progress, progress)
                    
                    if (isComplete) {
                        binding.scannerInstructionTextView.text = getString(R.string.transfer_complete)
                        binding.finishScanningButton.visibility = View.VISIBLE
                        scanning = false
                        
                        // Save the file
                        lifecycleScope.launch {
                            val filePath = saveReceivedFile()
                            if (filePath != null) {
                                withContext(Dispatchers.Main) {
                                    binding.scannerStatusTextView.text = getString(R.string.file_saved)
                                }
                                // Return the file path to the main activity
                                val intent = Intent().apply {
                                    putExtra(EXTRA_TRANSFER_COMPLETE, true)
                                    putExtra(EXTRA_FILE_PATH, filePath)
                                }
                                setResult(RESULT_OK, intent)
                                // Wait a moment before finishing to show the success message
                                lifecycleScope.launch {
                                    delay(2000)
                                    finish()
                                }
                            } else {
                                withContext(Dispatchers.Main) {
                                    binding.scannerStatusTextView.text = getString(R.string.failed_to_save_file)
                                    Toast.makeText(
                                        this@QRScannerActivity,
                                        getString(R.string.error_processing_file, "Failed to reconstruct file"),
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error processing QR code: ${e.message}")
        }
    }
    
    private suspend fun saveReceivedFile(): String? {
        return withContext(Dispatchers.IO) {
            try {
                // Get the app's files directory
                val outputDir = FileUtils.getFilesDir(this@QRScannerActivity)
                
                // Reconstruct the file
                val filePath = fileTransferProtocol.reconstructFile(this@QRScannerActivity, outputDir)
                
                filePath
            } catch (e: Exception) {
                Log.e(TAG, "Error saving file: ${e.message}")
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@QRScannerActivity,
                        getString(R.string.error_processing_file, e.message ?: "Unknown error"),
                        Toast.LENGTH_SHORT
                    ).show()
                }
                null
            }
        }
    }
    
    private fun finishScanning() {
        val intent = Intent().apply {
            putExtra(EXTRA_TRANSFER_COMPLETE, false)
        }
        setResult(RESULT_CANCELED, intent)
        finish()
    }
    
    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }
    
    private inner class QRCodeAnalyzer(private val onQRCodeDetected: (String) -> Unit) :
        ImageAnalysis.Analyzer {
        
        private val reader = MultiFormatReader()
        
        override fun analyze(image: ImageProxy) {
            if (!scanning) {
                image.close()
                return
            }
            
            val buffer = image.planes[0].buffer
            val data = buffer.toByteArray()
            val width = image.width
            val height = image.height
            
            val source = PlanarYUVLuminanceSource(
                data, width, height, 0, 0, width, height, false
            )
            
            val binaryBitmap = BinaryBitmap(HybridBinarizer(source))
            
            try {
                val result = reader.decode(binaryBitmap)
                onQRCodeDetected(result.text)
            } catch (e: Exception) {
                // QR code not detected, just ignore
            } finally {
                image.close()
            }
        }
        
        private fun ByteBuffer.toByteArray(): ByteArray {
            rewind()
            val data = ByteArray(remaining())
            get(data)
            return data
        }
    }
}
