package com.jarvis.assistant

import android.Manifest
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels

class MainActivity : ComponentActivity() {

    private val vm: JarvisViewModel by viewModels()

    private var lastPermission = ""

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            vm.onPermissionResult(lastPermission, granted)
        }

    private val pickImage =
        registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            if (uri != null) vm.attachImage(contentResolver, uri)
        }

    private val takePhoto =
        registerForActivityResult(ActivityResultContracts.TakePicturePreview()) { bitmap ->
            if (bitmap != null) vm.attachBitmap(bitmap)
        }

    private fun requestPermission(p: String) {
        lastPermission = p
        permissionLauncher.launch(p)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT)
        )
        super.onCreate(savedInstanceState)

        vm.permissionRequester = { p -> requestPermission(p) }
        vm.micPermissionRequest = { requestPermission(Manifest.permission.RECORD_AUDIO) }

        setContent {
            JarvisTheme {
                JarvisApp(
                    vm = vm,
                    onPickImage = {
                        pickImage.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    },
                    onTakePhoto = { takePhoto.launch(null) },
                    onRequestPermission = { p -> requestPermission(p) }
                )
            }
        }

        if (savedInstanceState == null) vm.onAppOpened()
    }

    override fun onStop() {
        super.onStop()
        vm.onAppBackgrounded()
    }
}
