package com.follow.clash.plugins

import android.Manifest
import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.ComponentInfo
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.os.Build
import android.widget.Toast
import androidx.core.content.ContextCompat.getSystemService
import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile
import androidx.core.content.FileProvider
import androidx.core.content.getSystemService
import com.follow.clash.GlobalState
import com.follow.clash.extensions.getBase64
import com.follow.clash.extensions.getProtocol
import com.follow.clash.models.Package
import com.follow.clash.models.Process
import com.google.gson.Gson
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.Result
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.InetSocketAddress
import java.util.zip.ZipFile


class AppPlugin : FlutterPlugin, MethodChannel.MethodCallHandler, ActivityAware {

    private var activity: Activity? = null

    private var toast: Toast? = null

    private var context: Context? = null

    private lateinit var channel: MethodChannel

    private lateinit var scope: CoroutineScope

    private var connectivity: ConnectivityManager? = null

    private val iconMap = mutableMapOf<String, String?>()
    private val packages = mutableListOf<Package>()

    private val skipPrefixList = listOf(
        "com.google",
        "com.android.chrome",
        "com.android.vending",
        "com.microsoft",
        "com.apple",
        "com.zhiliaoapp.musically", // Banned by China
    )

    private val chinaAppPrefixList = listOf(
        "com.tencent",
        "com.alibaba",
        "com.umeng",
        "com.qihoo",
        "com.ali",
        "com.alipay",
        "com.amap",
        "com.sina",
        "com.weibo",
        "com.vivo",
        "com.xiaomi",
        "com.huawei",
        "com.taobao",
        "com.secneo",
        "s.h.e.l.l",
        "com.stub",
        "com.kiwisec",
        "com.secshell",
        "com.wrapper",
        "cn.securitystack",
        "com.mogosec",
        "com.secoen",
        "com.netease",
        "com.mx",
        "com.qq.e",
        "com.baidu",
        "com.bytedance",
        "com.bugly",
        "com.miui",
        "com.oppo",
        "com.coloros",
        "com.iqoo",
        "com.meizu",
        "com.gionee",
        "cn.nubia",
        "com.oplus",
        "andes.oplus",
        "com.unionpay",
        "cn.wps"
    )

    private val chinaAppRegex by lazy {
        ("(" + chinaAppPrefixList.joinToString("|").replace(".", "\\.") + ").*").toRegex()
    }

    override fun onAttachedToEngine(flutterPluginBinding: FlutterPlugin.FlutterPluginBinding) {
        scope = CoroutineScope(Dispatchers.Default)
        context = flutterPluginBinding.applicationContext;
        channel = MethodChannel(flutterPluginBinding.binaryMessenger, "app")
        channel.setMethodCallHandler(this)

    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        channel.setMethodCallHandler(null)
        scope.cancel()
    }

    private fun tip(message: String?) {
        if (GlobalState.flutterEngine == null) {
            if (toast != null) {
                toast!!.cancel()
            }
            toast = Toast.makeText(context, message, Toast.LENGTH_SHORT)
            toast!!.show()
        }
    }

    override fun onMethodCall(call: MethodCall, result: Result) {
        when (call.method) {
            "moveTaskToBack" -> {
                activity?.moveTaskToBack(true)
                result.success(true);
            }

            "updateExcludeFromRecents" -> {
                val value = call.argument<Boolean>("value")
                updateExcludeFromRecents(value)
                result.success(true);
            }

            "getPackages" -> {
                scope.launch {
                    result.success(getPackagesToJson())
                }
            }

            "getChinaPackageNames" -> {
                scope.launch {
                    result.success(getChinaPackageNames())
                }
            }

            "getPackageIcon" -> {
                scope.launch {
                    val packageName = call.argument<String>("packageName")
                    if (packageName == null) {
                        result.success(null)
                        return@launch
                    }
                    val packageIcon = getPackageIcon(packageName)
                    packageIcon.let {
                        if (it != null) {
                            result.success(it)
                            return@launch
                        }
                        if (iconMap["default"] == null) {
                            iconMap["default"] =
                                context?.packageManager?.defaultActivityIcon?.getBase64()
                        }
                        result.success(iconMap["default"])
                        return@launch
                    }
                }
            }

            "resolverProcess" -> {
                val data = call.argument<String>("data")
                val process =
                    if (data != null) Gson().fromJson(
                        data,
                        Process::class.java
                    ) else null
                val metadata = process?.metadata
                val protocol = metadata?.getProtocol()
                if (protocol == null) {
                    result.success(null)
                    return
                }
                scope.launch {
                    withContext(Dispatchers.Default) {
                        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                            result.success(null)
                            return@withContext
                        }
                        if (context == null) {
                            result.success(null)
                            return@withContext
                        }
                        if (connectivity == null) {
                            connectivity = context!!.getSystemService<ConnectivityManager>()
                        }
                        val src = InetSocketAddress(metadata.sourceIP, metadata.sourcePort)
                        val dst = InetSocketAddress(
                            metadata.destinationIP.ifEmpty { metadata.host },
                            metadata.destinationPort
                        )
                        val uid = try {
                            connectivity?.getConnectionOwnerUid(protocol, src, dst)
                        } catch (_: Exception) {
                            null
                        }
                        if (uid == null || uid == -1) {
                            result.success(null)
                            return@withContext
                        }
                        val packages = context?.packageManager?.getPackagesForUid(uid)
                        result.success(packages?.first())
                    }
                }
            }

            "tip" -> {
                val message = call.argument<String>("message")
                tip(message)
                result.success(true)
            }

            "openFile" -> {
                val path = call.argument<String>("path")!!
                openFile(path)
                result.success(true)
            }

            else -> {
                result.notImplemented();
            }
        }
    }

    private fun openFile(path: String) {
        context?.let {
            val file = File(path)
            val uri = FileProvider.getUriForFile(
                it,
                "${it.packageName}.fileProvider",
                file
            )

            val intent = Intent(Intent.ACTION_VIEW).setDataAndType(
                uri,
                "text/plain"
            )

            val flags =
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION

            val resInfoList = it.packageManager.queryIntentActivities(
                intent, PackageManager.MATCH_DEFAULT_ONLY
            )

            for (resolveInfo in resInfoList) {
                val packageName = resolveInfo.activityInfo.packageName
                it.grantUriPermission(
                    packageName,
                    uri,
                    flags
                )
            }

            try {
                activity?.startActivity(intent)
            } catch (e: Exception) {
                println(e)
            }
        }
    }

    private fun updateExcludeFromRecents(value: Boolean?) {
        if (context == null) return
        val am = getSystemService(context!!, ActivityManager::class.java)
        val task = am?.appTasks?.firstOrNull {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                it.taskInfo.taskId == activity?.taskId
            } else {
                it.taskInfo.id == activity?.taskId
            }
        }

        when (value) {
            true -> task?.setExcludeFromRecents(value)
            false -> task?.setExcludeFromRecents(value)
            null -> task?.setExcludeFromRecents(false)
        }
    }

    private suspend fun getPackageIcon(packageName: String): String? {
        val packageManager = context?.packageManager
        if (iconMap[packageName] == null) {
            iconMap[packageName] = try {
                packageManager?.getApplicationIcon(packageName)?.getBase64()
            } catch (_: Exception) {
                null
            }

        }
        return iconMap[packageName]
    }

    private fun getPackages(): List<Package> {
        val packageManager = context?.packageManager
        if (packages.isNotEmpty()) return packages;
        packageManager?.getInstalledPackages(PackageManager.GET_META_DATA)?.filter {
            it.packageName != context?.packageName
                    || it.requestedPermissions?.contains(Manifest.permission.INTERNET) == true
                    || it.packageName == "android"

        }?.map {
            Package(
                packageName = it.packageName,
                label = it.applicationInfo.loadLabel(packageManager).toString(),
                isSystem = (it.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM) == 1,
                firstInstallTime = it.firstInstallTime
            )
        }?.let { packages.addAll(it) }
        return packages;
    }

    private suspend fun getPackagesToJson(): String {
        return withContext(Dispatchers.Default) {
            Gson().toJson(getPackages())
        }
    }

    private suspend fun getChinaPackageNames(): String {
        return withContext(Dispatchers.Default) {
            val packages: List<String> =
                getPackages().map { it.packageName }.filter { isChinaPackage(it) }
            Gson().toJson(packages)
        }
    }

    private fun isChinaPackage(packageName: String): Boolean {
        val packageManager = context?.packageManager ?: return false
        skipPrefixList.forEach {
            if (packageName == it || packageName.startsWith("$it.")) return false
        }
        val packageManagerFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            PackageManager.MATCH_UNINSTALLED_PACKAGES or PackageManager.GET_ACTIVITIES or PackageManager.GET_SERVICES or PackageManager.GET_RECEIVERS or PackageManager.GET_PROVIDERS
        } else {
            @Suppress("DEPRECATION")
            PackageManager.GET_UNINSTALLED_PACKAGES or PackageManager.GET_ACTIVITIES or PackageManager.GET_SERVICES or PackageManager.GET_RECEIVERS or PackageManager.GET_PROVIDERS
        }
        if (packageName.matches(chinaAppRegex)) {
            return true
        }
        try {
            val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getPackageInfo(
                    packageName,
                    PackageManager.PackageInfoFlags.of(packageManagerFlags.toLong())
                )
            } else {
                @Suppress("DEPRECATION") packageManager.getPackageInfo(
                    packageName, packageManagerFlags
                )
            }
            mutableListOf<ComponentInfo>().apply {
                packageInfo.services?.let { addAll(it) }
                packageInfo.activities?.let { addAll(it) }
                packageInfo.receivers?.let { addAll(it) }
                packageInfo.providers?.let { addAll(it) }
            }.forEach {
                if (it.name.matches(chinaAppRegex)) return true
            }
            ZipFile(File(packageInfo.applicationInfo.publicSourceDir)).use {
                for (packageEntry in it.entries()) {
                    if (packageEntry.name.startsWith("firebase-")) return false
                }
                for (packageEntry in it.entries()) {
                    if (!(packageEntry.name.startsWith("classes") && packageEntry.name.endsWith(
                            ".dex"
                        ))
                    ) {
                        continue
                    }
                    if (packageEntry.size > 15000000) {
                        return true
                    }
                    val input = it.getInputStream(packageEntry).buffered()
                    val dexFile = try {
                        DexBackedDexFile.fromInputStream(null, input)
                    } catch (e: Exception) {
                        return false
                    }
                    for (clazz in dexFile.classes) {
                        val clazzName =
                            clazz.type.substring(1, clazz.type.length - 1).replace("/", ".")
                                .replace("$", ".")
                        if (clazzName.matches(chinaAppRegex)) return true
                    }
                }
            }
        } catch (_: Exception) {
            return false
        }
        return false
    }

    fun requestGc() {
        channel.invokeMethod("gc", null)
    }

    override fun onAttachedToActivity(binding: ActivityPluginBinding) {
        activity = binding.activity;
    }

    override fun onDetachedFromActivityForConfigChanges() {
        activity = null
    }

    override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) {
        activity = binding.activity;
    }

    override fun onDetachedFromActivity() {
        channel.invokeMethod("exit", null)
        activity = null
    }
}