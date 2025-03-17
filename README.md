# QRXfer - QR Code File Transfer

QRXfer is an Android application that allows users to transfer files between devices using QR codes, without requiring an internet connection or Bluetooth pairing.

## Features

- Send files by generating a series of QR codes
- Receive files by scanning QR codes
- Works completely offline
- Supports any file type
- Automatic file verification using checksums
- Progress tracking for both sending and receiving

## How It Works

1. **Sending a File:**
   - Select a file from your device
   - The app processes the file into chunks
   - Each chunk is encoded as a QR code
   - Display QR codes sequentially for the receiver to scan

2. **Receiving a File:**
   - Scan QR codes using the device camera
   - The app reconstructs the file from the scanned chunks
   - Verify file integrity using checksums
   - Save and open the received file

## Technical Details

- Uses ZXing for QR code generation and scanning
- Implements a custom file transfer protocol
- Files are split into chunks to fit within QR code capacity
- Each chunk contains metadata for reconstruction
- MD5 checksums ensure file integrity

## Requirements

- Android 7.0 (API level 24) or higher
- Camera permission for QR code scanning
- Storage permission for file access

## Development

This project is built with:
- Kotlin
- Android Jetpack components
- CameraX for camera functionality
- ZXing for QR code processing
- Coroutines for asynchronous operations

## License

This project is open source and available under the MIT License.

## Acknowledgements

This project is inspired by the need for simple, offline file transfer solutions that don't rely on network connectivity or device pairing.
