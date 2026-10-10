package com.youzeng

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.youzeng.hook.YouzengConfig
import com.youzeng.ui.theme.YouzengTheme

class MainActivity : ComponentActivity() {
    private val rootStatus = mutableStateOf("界面就绪后将申请 Root 权限…")
    private val rootGranted = mutableStateOf(false)
    private var suStarted = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // 把旧 MODE_PRIVATE 里已保存的 did 迁到 LSPosed 可读路径
        val initialDid = YouzengModulePrefsStore.loadDid(this)
        YouzengModulePrefsStore.saveDid(this, initialDid)
        setContent {
            YouzengTheme {
                var deviceDid by remember { mutableStateOf(initialDid) }
                var savedHint by remember { mutableStateOf(false) }
                val suHint by rootStatus
                val suOk by rootGranted
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    Column(
                        modifier = Modifier
                            .padding(innerPadding)
                            .padding(24.dp)
                            .fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Text(suHint)
                        if (!suOk) {
                            Button(
                                onClick = { requestRoot(force = true) },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("重新申请 Root")
                            }
                        }
                        Text(
                            "对接 mock_api_server 时使用的设备编号（did），需与后台 device_registry 中一致。当前业务：邮储银行、建设银行。"
                        )
                        OutlinedTextField(
                            value = deviceDid,
                            onValueChange = {
                                deviceDid = it
                                savedHint = false
                            },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("设备 ID（did）") },
                            singleLine = true
                        )
                        Button(
                            onClick = {
                                YouzengModulePrefsStore.saveDid(this@MainActivity, deviceDid)
                                savedHint = true
                            }
                        ) {
                            Text("保存")
                        }
                        if (savedHint) {
                            Text(
                                "已保存。请强行停止对应银行 App 后再打开；若已在运行，一般下一次网络请求就会用新 did。"
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            requestRoot(force = false)
        }
    }

    private fun requestRoot(force: Boolean) {
        if (!force && suStarted) {
            return
        }
        suStarted = true
        rootGranted.value = false
        rootStatus.value = "正在申请 Root 权限，请在 Magisk / KernelSU 弹窗中点允许。"
        LsposedScopeCli.requestSuThenApply(this, force) { granted, message ->
            runOnUiThread {
                rootGranted.value = granted
                rootStatus.value = message
            }
        }
    }
}
