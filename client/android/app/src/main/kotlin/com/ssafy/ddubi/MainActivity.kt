package com.ssafy.ddubi

import android.content.ComponentName
import android.content.pm.PackageManager
import com.navercorp.nid.NidOAuth
import com.navercorp.nid.oauth.util.NidOAuthCallback
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel

class MainActivity : FlutterActivity() {
    private companion object {
        const val NAVER_LOGIN_CHANNEL = "com.ssafy.ddubi/naver_login"
        const val APP_ICON_CHANNEL = "com.ssafy.ddubi/app_icon"
        const val DAY_ICON_ALIAS = "com.ssafy.ddubi.DayIconAlias"
        const val NIGHT_ICON_ALIAS = "com.ssafy.ddubi.NightIconAlias"
    }

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
            APP_ICON_CHANNEL,
        ).setMethodCallHandler { call, result ->
            when (call.method) {
                "getActiveIcon" -> result.success(getActiveIcon())
                "setActiveIcon" -> {
                    val icon = call.argument<String>("icon")
                    if (icon != "day" && icon != "night") {
                        result.error("INVALID_ICON", "Icon must be day or night.", null)
                        return@setMethodCallHandler
                    }

                    result.success(setActiveIcon(icon))
                }
                else -> result.notImplemented()
            }
        }
    }

    private fun getActiveIcon(): String {
        val nightState = packageManager.getComponentEnabledSetting(
            ComponentName(this, NIGHT_ICON_ALIAS),
        )
        return if (nightState == PackageManager.COMPONENT_ENABLED_STATE_ENABLED) {
            "night"
        } else {
            "day"
        }
    }

    private fun setActiveIcon(icon: String): Boolean {
        if (getActiveIcon() == icon) return false

        val dayAlias = ComponentName(this, DAY_ICON_ALIAS)
        val nightAlias = ComponentName(this, NIGHT_ICON_ALIAS)
        val enabledAlias = if (icon == "day") dayAlias else nightAlias
        val disabledAlias = if (icon == "day") nightAlias else dayAlias

        packageManager.setComponentEnabledSetting(
            enabledAlias,
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
            PackageManager.DONT_KILL_APP,
        )
        packageManager.setComponentEnabledSetting(
            disabledAlias,
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.DONT_KILL_APP,
        )
        return true
    }
}
