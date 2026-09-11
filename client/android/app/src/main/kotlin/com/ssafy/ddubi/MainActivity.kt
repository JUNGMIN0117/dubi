package com.ssafy.ddubi

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.navercorp.nid.NidOAuth
import com.navercorp.nid.oauth.util.NidOAuthCallback
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel

class MainActivity : FlutterActivity() {
    private companion object {
        const val NAVER_LOGIN_CHANNEL = "com.ssafy.ddubi/naver_login"
        const val APP_PERMISSION_CHANNEL = "com.ssafy.ddubi/app_permissions"
        const val LOCATION_PERMISSION_REQUEST = 7102
    }

    private var locationPermissionResult: MethodChannel.Result? = null

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)

        MethodChannel(
            flutterEngine.dartExecutor.binaryMessenger,
            NAVER_LOGIN_CHANNEL,
        ).setMethodCallHandler { call, result ->
            if (call.method != "login") {
                result.notImplemented()
                return@setMethodCallHandler
            }

            if (BuildConfig.NAVER_CLIENT_ID.isBlank() || BuildConfig.NAVER_CLIENT_SECRET.isBlank()) {
                result.error("NOT_CONFIGURED", "Naver login is not configured.", null)
                return@setMethodCallHandler
            }

            NidOAuth.initialize(
                applicationContext,
                BuildConfig.NAVER_CLIENT_ID,
                BuildConfig.NAVER_CLIENT_SECRET,
                BuildConfig.NAVER_CLIENT_NAME,
            )
            NidOAuth.setLogEnabled(false)
            NidOAuth.requestLogin(
                this,
                object : NidOAuthCallback {
                    override fun onSuccess() {
                        val accessToken = NidOAuth.getAccessToken()
                        if (accessToken.isNullOrBlank()) {
                            result.error("EMPTY_TOKEN", "Naver access token is empty.", null)
                        } else {
                            result.success(accessToken)
                        }
                    }

                    override fun onFailure(errorCode: String, errorDesc: String) {
                        val code = if (errorCode == "CLIENT_USER_CANCEL") "CANCELED" else "LOGIN_FAILED"
                        result.error(code, "Naver login failed.", null)
                    }
                },
            )
        }

        MethodChannel(
            flutterEngine.dartExecutor.binaryMessenger,
            APP_PERMISSION_CHANNEL,
        ).setMethodCallHandler { call, result ->
            when (call.method) {
                "hasLocationPermission" -> result.success(hasLocationPermission())
                "requestLocationPermission" -> requestLocationPermission(result)
                "openAppSettings" -> {
                    startActivity(
                        Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.parse("package:$packageName"),
                        ),
                    )
                    result.success(null)
                }
                else -> result.notImplemented()
            }
        }
    }

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private fun requestLocationPermission(result: MethodChannel.Result) {
        if (hasLocationPermission()) {
            result.success(true)
            return
        }
        locationPermissionResult?.error(
            "REQUEST_IN_PROGRESS",
            "Permission request is already running.",
            null,
        )
        locationPermissionResult = result
        ActivityCompat.requestPermissions(
            this,
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
            ),
            LOCATION_PERMISSION_REQUEST,
        )
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == LOCATION_PERMISSION_REQUEST) {
            locationPermissionResult?.success(hasLocationPermission())
            locationPermissionResult = null
        }
    }
}
