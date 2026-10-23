package com.example

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ui.AstroCameraScreen
import com.example.ui.theme.AstroNightCamTheme
import com.example.viewmodel.AstroCameraViewModel
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {

    private val cameraViewModel: AstroCameraViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            val isRedNightMode by cameraViewModel.isRedNightMode.collectAsStateWithLifecycle()

            var hasPermissions by remember {
                mutableStateOf(
                    ContextCompat.checkSelfPermission(
                        this,
                        Manifest.permission.CAMERA
                    ) == PackageManager.PERMISSION_GRANTED
                )
            }

            val permissionLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.RequestMultiplePermissions()
            ) { result ->
                val camGranted = result[Manifest.permission.CAMERA] == true
                hasPermissions = camGranted
                if (camGranted) {
                    cameraViewModel.onPermissionsGranted()
                }
            }

            LaunchedEffect(Unit) {
                if (!hasPermissions) {
                    // Wait until initial window SurfaceSyncGroup transaction completes smoothly
                    delay(450)
                    permissionLauncher.launch(
                        arrayOf(
                            Manifest.permission.CAMERA,
                            Manifest.permission.RECORD_AUDIO
                        )
                    )
                }
            }

            AstroNightCamTheme(isRedNightMode = isRedNightMode) {
                AstroCameraScreen(
                    viewModel = cameraViewModel,
                    hasCameraPermission = hasPermissions,
                    onRequestPermissions = {
                        permissionLauncher.launch(
                            arrayOf(
                                Manifest.permission.CAMERA,
                                Manifest.permission.RECORD_AUDIO
                            )
                        )
                    }
                )
            }
        }
    }
}
