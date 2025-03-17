package io.github.chitao1234.qrxfer

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import io.github.chitao1234.qrxfer.databinding.ActivityMainBinding
import io.github.chitao1234.qrxfer.utils.PermissionUtils

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private val TAG = "MainActivity"
    
    // Activity result launcher for permissions
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.entries.all { it.value }
        if (allGranted) {
            Toast.makeText(this, getString(R.string.permissions_granted), Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, getString(R.string.permissions_denied), Toast.LENGTH_SHORT).show()
        }
    }
    
    // Activity result launcher for receive activity
    private val receiveActivityLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val transferComplete = result.data?.getBooleanExtra("EXTRA_TRANSFER_COMPLETE", false) ?: false
            val filePath = result.data?.getStringExtra("EXTRA_FILE_PATH")
            
            if (transferComplete && filePath != null) {
                Toast.makeText(this, getString(R.string.file_saved_to, filePath), Toast.LENGTH_LONG).show()
            }
        }
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
        
        setupUI()
        checkAndRequestPermissions()
    }
    
    private fun setupUI() {
        // Setup send button
        binding.sendCardView.setOnClickListener {
            val intent = Intent(this, SendActivity::class.java)
            startActivity(intent)
        }
        
        binding.sendButton.setOnClickListener {
            val intent = Intent(this, SendActivity::class.java)
            startActivity(intent)
        }
        
        // Setup receive button
        binding.receiveCardView.setOnClickListener {
            val intent = Intent(this, ReceiveActivity::class.java)
            receiveActivityLauncher.launch(intent)
        }
        
        binding.receiveButton.setOnClickListener {
            val intent = Intent(this, ReceiveActivity::class.java)
            receiveActivityLauncher.launch(intent)
        }
    }
    
    private fun checkAndRequestPermissions() {
        val missingPermissions = PermissionUtils.getMissingPermissions(this)
        if (missingPermissions.isNotEmpty()) {
            permissionLauncher.launch(missingPermissions)
        }
    }
}