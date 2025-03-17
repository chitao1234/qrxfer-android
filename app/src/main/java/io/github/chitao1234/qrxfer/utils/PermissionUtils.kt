package io.github.chitao1234.qrxfer.utils

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * Utility class for handling runtime permissions
 */
class PermissionUtils {
    companion object {
        const val REQUEST_CAMERA_PERMISSION = 100
        const val REQUEST_STORAGE_PERMISSION = 101
        const val REQUEST_ALL_PERMISSIONS = 102
        
        /**
         * Check if camera permission is granted
         * @param context Application context
         * @return True if permission is granted, false otherwise
         */
        fun hasCameraPermission(context: Context): Boolean {
            return ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        }
        
        /**
         * Request camera permission
         * @param activity Activity to request permission from
         */
        fun requestCameraPermission(activity: Activity) {
            ActivityCompat.requestPermissions(
                activity,
                arrayOf(Manifest.permission.CAMERA),
                REQUEST_CAMERA_PERMISSION
            )
        }
        
        /**
         * Check if storage permissions are granted
         * @param context Application context
         * @return True if permissions are granted, false otherwise
         */
        fun hasStoragePermissions(context: Context): Boolean {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.READ_MEDIA_IMAGES
                ) == PackageManager.PERMISSION_GRANTED
            } else {
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.READ_EXTERNAL_STORAGE
                ) == PackageManager.PERMISSION_GRANTED &&
                (Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q ||
                        ContextCompat.checkSelfPermission(
                            context,
                            Manifest.permission.WRITE_EXTERNAL_STORAGE
                        ) == PackageManager.PERMISSION_GRANTED)
            }
        }
        
        /**
         * Request storage permissions
         * @param activity Activity to request permissions from
         */
        fun requestStoragePermissions(activity: Activity) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ActivityCompat.requestPermissions(
                    activity,
                    arrayOf(Manifest.permission.READ_MEDIA_IMAGES),
                    REQUEST_STORAGE_PERMISSION
                )
            } else {
                val permissions = if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q) {
                    arrayOf(
                        Manifest.permission.READ_EXTERNAL_STORAGE,
                        Manifest.permission.WRITE_EXTERNAL_STORAGE
                    )
                } else {
                    arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
                }
                
                ActivityCompat.requestPermissions(
                    activity,
                    permissions,
                    REQUEST_STORAGE_PERMISSION
                )
            }
        }
        
        /**
         * Get a list of missing permissions
         * @param context Application context
         * @return Array of missing permissions
         */
        fun getMissingPermissions(context: Context): Array<String> {
            val missingPermissions = mutableListOf<String>()
            
            // Check camera permission
            if (!hasCameraPermission(context)) {
                missingPermissions.add(Manifest.permission.CAMERA)
            }
            
            // Check storage permissions
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                if (ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.READ_MEDIA_IMAGES
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    missingPermissions.add(Manifest.permission.READ_MEDIA_IMAGES)
                }
            } else {
                if (ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.READ_EXTERNAL_STORAGE
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    missingPermissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
                }
                
                if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q &&
                    ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.WRITE_EXTERNAL_STORAGE
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    missingPermissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                }
            }
            
            return missingPermissions.toTypedArray()
        }
        
        /**
         * Request all required permissions
         * @param activity Activity to request permissions from
         */
        fun requestAllPermissions(activity: Activity) {
            val missingPermissions = getMissingPermissions(activity)
            if (missingPermissions.isNotEmpty()) {
                ActivityCompat.requestPermissions(
                    activity,
                    missingPermissions,
                    REQUEST_ALL_PERMISSIONS
                )
            }
        }
    }
}
