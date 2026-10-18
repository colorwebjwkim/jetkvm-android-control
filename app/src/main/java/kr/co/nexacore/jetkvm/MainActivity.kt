package kr.co.nexacore.jetkvm

import android.app.*
import android.os.Bundle
import android.graphics.Color
import android.text.InputType
import android.view.View
import android.view.WindowManager

import android.widget.*
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.UUID
import java.util.concurrent.Executors

class MainActivity : Activity() {
    data class Device(val id: String, val name: String, val url: String)
    private val devices = mutableListOf<Device>()
    private val worker = Executors.newSingleThreadExecutor()
    private val agentWorker = Executors.newFixedThreadPool(4)
    private val prefs by lazy { getSharedPreferences("devices", MODE_PRIVATE) }
    private val passwords by lazy { PasswordStore(this) }
    private var session: NativeSessionView? = null
    private var foreground = false
    private var homeToolbar: ControlToolbar? = null
    private val deviceActions = mutableMapOf<String,DeviceActionRow>()
    private val powerLabels = mutableMapOf<String,TextView>()
    private val agentStates = mutableMapOf<String,AgentStatus>()
    private val agentRequests = mutableMapOf<String,StatusAgentClient>()
    private val agentEpochs = mutableMapOf<String,Int>()
    private var agentGeneration = 0
    private val ui = android.os.Handler(android.os.Looper.getMainLooper())
    private val pollAgents = object : Runnable {
        override fun run() {
            if (foreground && session == null) devices.toList().forEach { queryAgent(it) }
            ui.postDelayed(this,5000)
        }
    }
    private lateinit var root: LinearLayout
    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
    override fun attachBaseContext(newBase: android.content.Context) { super.attachBaseContext(LanguageSettings.wrap(newBase)) }
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        runCatching {
            val array = JSONArray(prefs.getString("list", "[]"))
            for (i in 0 until array.length()) {
                val item = array.getJSONObject(i)
                devices.add(Device(item.getString("id"), item.getString("name"), item.getString("url")))
            }
        }.onFailure { Toast.makeText(this, AppText.get(R.string.devices_load_failed), Toast.LENGTH_LONG).show() }
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT) {
                if (session != null) home() else finish()
            }
        }
        home()
    }
    private fun screen() {
        homeToolbar = null
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(244,246,250))
            setOnApplyWindowInsetsListener { v, insets ->
                v.setPadding(insets.systemWindowInsetLeft,insets.systemWindowInsetTop,insets.systemWindowInsetRight,insets.systemWindowInsetBottom)
                insets
            }
        }
        setContentView(root)
        root.requestApplyInsets()
    }
    private fun label(parent: LinearLayout, text: String) = parent.addView(TextView(this).apply { this.text = text; textSize = 15f; setPadding(0, dp(12),0,dp(8)) })
    private fun listIcon(parent: LinearLayout, resource: Int, title: String, click: () -> Unit) = ImageButton(this).also {
        it.setImageResource(resource); it.contentDescription = title; it.tooltipText = title
        it.setPadding(dp(12),dp(12),dp(12),dp(12))
        it.imageTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#435A9A"))
        it.background = android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(Color.parseColor("#225EC4AF")),null,null)
        it.setOnClickListener { click() }; parent.addView(it,LinearLayout.LayoutParams(dp(48),dp(48)))
    }
    private fun home() {
        closeSession()
        deviceActions.clear(); powerLabels.clear()
        screen()
        val toolbar = ControlToolbar(this,{}, {}, {}, {}, { anchor ->
            PopupMenu(this,anchor).apply {
                menu.add(0,0,0,AppText.get(R.string.add_device))
                menu.add(0,1,0,AppText.get(R.string.import_tailscale))
                menu.add(0,2,0,AppText.get(R.string.language_settings))
                setOnMenuItemClickListener { when(it.itemId) { 0 -> edit(); 1 -> tailscale(); 2 -> showLanguageSettings() }; true }; show()
            }
        },remoteSessionControls = false)
        homeToolbar = toolbar
        root.addView(toolbar,LinearLayout.LayoutParams(-1,dp(56)))
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this)
        scroll.clipToPadding = false; scroll.setPadding(dp(8),dp(8),dp(8),dp(16))
        scroll.addView(list,FrameLayout.LayoutParams(-1,-2)); root.addView(scroll, LinearLayout.LayoutParams(-1,0,1f))
        val actions = LinearLayout(this).apply { gravity = android.view.Gravity.CENTER_VERTICAL }; list.addView(actions)
        actions.addView(TextView(this).apply { text = AppText.get(R.string.my_devices, devices.size); textSize = 18f; setTextColor(Color.rgb(30,45,65)) },LinearLayout.LayoutParams(0,-2,1f))
        listIcon(actions,R.drawable.ic_import,AppText.get(R.string.import_tailscale)) { tailscale() }
        listIcon(actions,R.drawable.ic_add,AppText.get(R.string.add_device)) { edit() }
        if (devices.isEmpty()) label(list,AppText.get(R.string.devices_empty))
        devices.forEach { d ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; setPadding(dp(16),dp(12),dp(16),dp(8))
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(Color.WHITE); cornerRadius = dp(16).toFloat(); setStroke(dp(1),Color.rgb(224,230,238))
                }
            }
            list.addView(card,LinearLayout.LayoutParams(-1,-2).apply { topMargin = dp(12) })
            val heading = LinearLayout(this).apply { gravity = android.view.Gravity.CENTER_VERTICAL }
            heading.addView(TextView(this).apply { text = d.name; textSize = 18f; setTextColor(Color.rgb(30,45,65)); maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END },LinearLayout.LayoutParams(0,-2,1f))
            heading.addView(DeviceOptionsButton(this,d.name,{ queryAgent(d) },{ closeDevicePower(d.id) },
                { agentRequests.containsKey(d.id) },{ configureMonitor(d) },{ configureAgent(d) }),LinearLayout.LayoutParams(dp(48),dp(48)))
            card.addView(heading)
            card.addView(TextView(this).apply { text = d.url; textSize = 13f; setTextColor(Color.rgb(80,96,117)); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.MIDDLE; setPadding(0,dp(4),0,dp(8)) })
            val powerLabel = TextView(this).apply {
                textSize = 12f; setPadding(dp(10),dp(6),dp(10),dp(6)); maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                gravity = android.view.Gravity.CENTER_VERTICAL; minimumHeight = dp(48)
                compoundDrawablePadding = dp(6)
            }
            card.addView(powerLabel,LinearLayout.LayoutParams(-2,-2).apply { bottomMargin = dp(4) })
            powerLabels[d.id] = powerLabel
            showIdlePowerLabel(d.id)
            val row = DeviceActionRow(this,{ connect(d) },{ edit(d) },{ deleteDevice(d) },{})
            deviceActions[d.id] = row
            card.addView(HorizontalScrollView(this).apply {
                isFillViewport = true; isHorizontalScrollBarEnabled = false; addView(row)
            },LinearLayout.LayoutParams(-1,dp(48)).apply { leftMargin = -dp(16); rightMargin = -dp(16) })
        }
        if (foreground) devices.toList().forEach { queryAgent(it) }
        val monitorDevices=devices.toList()
        if (PowerRestorePolicy.automaticConnectionsAllowed) worker.execute { monitorDevices.forEach { device ->
            runCatching { passwords.read("monitor.${device.id}",device.url)?.let { MonitorSessionReporter(applicationContext,it).close() } }
        } }
    }
    private fun deleteDevice(device: Device) {
        AlertDialog.Builder(this).setTitle(AppText.get(R.string.delete_device_title, device.name)).setMessage(AppText.get(R.string.delete_device_confirm))
            .setNegativeButton(AppText.get(R.string.cancel),null).setPositiveButton(AppText.get(R.string.delete)) { _,_ ->
                runCatching { passwords.remove(device.id); passwords.remove("monitor.${device.id}"); passwords.remove("agent.${device.id}"); prefs.edit().remove("agent.${device.id}").apply(); prefs.edit().remove("extension.${device.id}").apply(); closeDevicePower(device.id); devices.remove(device); save(); home() }
                    .onFailure { Toast.makeText(this,AppText.get(R.string.password_delete_failed),Toast.LENGTH_LONG).show() }
            }.show()
    }
    private fun showIdlePowerLabel(id: String) {
        showPowerLabel(id,AppText.get(if (prefs.contains("agent.$id")) R.string.agent_unavailable else R.string.agent_connection_only))
    }
    private fun showPowerLabel(id: String,message: String) {
        powerLabels[id]?.apply {
            text = message; maxLines = 6
            contentDescription = message
            setTextColor(Color.parseColor("#506075"))
            setOnClickListener { AlertDialog.Builder(this@MainActivity).setTitle(AppText.get(R.string.agent_settings))
                .setMessage(message).setPositiveButton(AppText.get(R.string.ok),null).show() }
        }
    }
    private fun closeDevicePower(id: String) {
        agentEpochs[id] = (agentEpochs[id] ?: 0) + 1
        agentRequests.remove(id)?.close()
        agentStates.remove(id)
        showIdlePowerLabel(id)
    }
    private fun closePowerControl() {
        agentGeneration++
        devices.toList().forEach { closeDevicePower(it.id) }
    }
    private fun queryAgent(device: Device) {
        if (!foreground || session != null || agentRequests.containsKey(device.id)) return
        val base = prefs.getString("agent.${device.id}",null)
        if (base == null) { showIdlePowerLabel(device.id); return }
        val generation = agentGeneration
        val epoch = agentEpochs[device.id] ?: 0
        val request = StatusAgentClient()
        agentRequests[device.id] = request
        // Clear old readings before a request; failed refreshes never leave a current state.
        agentStates.remove(device.id)
        showPowerLabel(device.id,AppText.get(R.string.agent_querying))
        agentWorker.execute {
            val result = runCatching {
                val token = passwords.read("agent.${device.id}",device.url) ?: error("token")
                request.fetch(base,token)
            }
            runOnUiThread {
                if (generation != agentGeneration || epoch != (agentEpochs[device.id] ?: 0) || agentRequests[device.id] !== request) return@runOnUiThread
                agentRequests.remove(device.id); request.close()
                if (!foreground || session != null || device !in devices) return@runOnUiThread
                result.onSuccess { state -> agentStates[device.id] = state; showPowerLabel(device.id,state.display()) }
                    .onFailure { showPowerLabel(device.id,AppText.get(if (it is AgentAuthException) R.string.agent_auth_failed else R.string.agent_unavailable)) }
            }
        }
    }
    private fun configureAgent(device: Device): AlertDialog {
        val box = form()
        label(box,AppText.get(R.string.agent_help))
        val address = field(box,AppText.get(R.string.agent_address),prefs.getString("agent.${device.id}","") ?: "")
        val token = field(box,AppText.get(R.string.agent_token),secret = true)
        val error = TextView(this).also { box.addView(it) }
        val dialog = AlertDialog.Builder(this).setTitle(AppText.get(R.string.agent_settings)).setView(scrollForm(box))
            .setNegativeButton(AppText.get(R.string.cancel),null).setNeutralButton(AppText.get(R.string.agent_remove),null)
            .setPositiveButton(AppText.get(R.string.save),null).create()
        dialog.setOnDismissListener { token.text.clear() }
        dialog.show(); fitDialog(dialog)
        fun persist(remove: Boolean) {
            val base = if (remove) "" else runCatching { StatusAgentClient.normalize(address.text.toString()) }.getOrElse { address.error = AppText.get(R.string.agent_address_invalid); return }
            val entered = token.text.toString()
            dialog.getButton(-1).isEnabled = false; dialog.getButton(-3).isEnabled = false
            closeDevicePower(device.id)
            worker.execute {
                val result = runCatching {
                    if (remove) {
                        passwords.remove("agent.${device.id}")
                        check(prefs.edit().remove("agent.${device.id}").commit())
                    } else {
                        val oldBase = prefs.getString("agent.${device.id}",null)
                        val secret = entered.ifBlank { if (oldBase == base) passwords.read("agent.${device.id}",device.url).orEmpty() else "" }
                        require(secret.isNotBlank() && secret.all { it.code in 33..126 })
                        passwords.write("agent.${device.id}",device.url,secret)
                        check(prefs.edit().putString("agent.${device.id}",base).commit())
                    }
                }
                runOnUiThread {
                    if (isDestroyed || !dialog.isShowing) return@runOnUiThread
                    result.onSuccess { dialog.dismiss(); closeDevicePower(device.id); queryAgent(device) }
                        .onFailure { error.text = AppText.get(R.string.agent_save_failed); dialog.getButton(-1).isEnabled = true; dialog.getButton(-3).isEnabled = true }
                }
            }
        }
        dialog.getButton(-1).setOnClickListener { persist(false) }
        dialog.getButton(-3).setOnClickListener { persist(true) }
        EditorActions.bind(listOf(address,token)) { dialog.getButton(-1).let { if (it.isEnabled) it.performClick() } }
        return dialog
    }
    private fun save() {
        val array = JSONArray()
        devices.forEach { array.put(JSONObject().put("id",it.id).put("name",it.name).put("url",it.url)) }
        prefs.edit().putString("list",array.toString()).apply()
    }
    private fun field(parent: LinearLayout, hint: String, value: String = "", secret: Boolean = false): EditText = EditText(this).also {
        it.hint = hint; it.setText(value); it.isSingleLine = true
        if (secret) { it.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD; it.isSaveEnabled = false }
        parent.addView(it)
    }
    private fun form() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20),dp(8),dp(20),dp(8)) }
    private fun scrollForm(box: LinearLayout) = ScrollView(this).apply { addView(box) }
    private fun fitDialog(dialog: AlertDialog) {
        dialog.window?.setLayout(dp((resources.configuration.screenWidthDp-32).coerceIn(240,480)),WindowManager.LayoutParams.WRAP_CONTENT)
    }
    private fun edit(old: Device? = null, importedName: String = "", importedAddress: String = "") {
        val box = form()
        val name = field(box,AppText.get(R.string.device_name), old?.name ?: importedName)
        val address = field(box,AppText.get(R.string.address_or_url), old?.url ?: importedAddress)
        val port = field(box,AppText.get(R.string.port_optional)).apply { inputType = InputType.TYPE_CLASS_NUMBER }
        val error = TextView(this).apply { setTextColor(Color.rgb(165,42,42)) }; box.addView(error)
        val dialog = AlertDialog.Builder(this).setTitle(if (old == null) AppText.get(R.string.add_device) else AppText.get(R.string.edit_device)).setView(scrollForm(box)).setNegativeButton(AppText.get(R.string.cancel),null).setPositiveButton(AppText.get(R.string.save),null).create()
        dialog.setOnShowListener { dialog.getButton(-1).setOnClickListener {
            runCatching {
                require(name.text.toString().isNotBlank()) { AppText.get(R.string.device_name_required) }
                val url = DeviceAddress.normalize(address.text.toString(),port.text.toString())
                require(devices.none { it.id != old?.id && it.url == url }) { AppText.get(R.string.duplicate_address) }
                val d = Device(old?.id ?: UUID.randomUUID().toString(),name.text.toString().trim(),url)
                if (old != null && old.url != url) { passwords.remove(old.id); passwords.remove("monitor.${old.id}"); passwords.remove("agent.${old.id}"); prefs.edit().remove("agent.${old.id}").apply(); prefs.edit().remove("extension.${old.id}").apply() }
                if (old == null) devices.add(d) else devices[devices.indexOf(old)] = d
                closeDevicePower(d.id); save(); dialog.dismiss(); home()
            }.onFailure { error.text = it.message ?: AppText.get(R.string.check_address) }
        } }; dialog.show(); fitDialog(dialog)
        val fields = (0 until box.childCount).mapNotNull { box.getChildAt(it) as? EditText }
        EditorActions.bind(fields) {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).let { if (it.isEnabled) it.performClick() }
        }
        fields.firstOrNull()?.requestFocus()
    }
    private fun connect(device: Device) {
        val box = form()
        val password = field(box,AppText.get(R.string.device_password),secret = true).apply { isSaveEnabled = false }
        val remember = CheckBox(this).apply { text = AppText.get(R.string.remember_password); isEnabled = false; box.addView(this) }
        label(box, AppText.get(R.string.password_storage_help))
        if (device.url.startsWith("http://")) label(box,AppText.get(R.string.http_connection_help))
        val status = TextView(this).apply { text = AppText.get(R.string.password_loading); box.addView(this) }
        val dialog = AlertDialog.Builder(this).setTitle(device.name).setView(scrollForm(box)).setNegativeButton(AppText.get(R.string.cancel),null)
            .setPositiveButton(AppText.get(R.string.connect),null).create()
        dialog.setOnDismissListener { password.text.clear() }
        dialog.show()
        fitDialog(dialog)
        password.isEnabled = false
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
        worker.execute {
            val loaded = runCatching { passwords.read(device.id,device.url) }
            runOnUiThread {
                if (isDestroyed || !dialog.isShowing) return@runOnUiThread
                loaded.onSuccess { value -> password.setText(value ?: ""); remember.isChecked = value != null }
                status.text = if (loaded.isFailure) AppText.get(R.string.password_decrypt_failed) else ""
                password.isEnabled = true; remember.isEnabled = true
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = true
                password.requestFocus()
            }
        }
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val value = password.text.toString()
            val shouldSave = remember.isChecked
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
            password.isEnabled = false; remember.isEnabled = false
            worker.execute {
                val saved = runCatching {
                    if (shouldSave) passwords.write(device.id,device.url,value) else passwords.remove(device.id)
                }
                val monitorCode=runCatching { passwords.read("monitor.${device.id}",device.url) }.getOrNull()
                runOnUiThread {
                    if (isDestroyed || !dialog.isShowing) return@runOnUiThread
                    if (saved.isFailure) {
                        status.text = AppText.get(R.string.password_save_failed)
                        dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = true
                        password.isEnabled = true; remember.isEnabled = true
                        return@runOnUiThread
                    }
                    if (!foreground) {
                        status.text = AppText.get(R.string.return_then_connect)
                        dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = true
                        password.isEnabled = true; remember.isEnabled = true
                        return@runOnUiThread
                    }
                    dialog.dismiss()
                    closePowerControl()
                    closeSession()
                    screen()
                    val monitor=monitorCode?.let { MonitorSessionReporter(applicationContext,it) {
                        Toast.makeText(this,AppText.get(R.string.monitor_link_failed),Toast.LENGTH_LONG).show()
                    } }
                    val native = NativeSessionView(this,device.url,{ home() },{ connect(device) },{ showLanguageSettings() },{ disconnectToDeviceList() },monitor)
                    session = native; root.addView(native,LinearLayout.LayoutParams(-1,0,1f))
                    native.start(value)
                }
            }
        }
        EditorActions.bind(listOf(password),android.view.inputmethod.EditorInfo.IME_ACTION_GO) {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).let { if (it.isEnabled) it.performClick() }
        }
        password.requestFocus()
    }
    private fun closeSession() { session?.close(); session = null }
    private fun configureMonitor(device: Device) {
        val box=form()
        label(box,AppText.get(R.string.monitor_link_help))
        val code=field(box,AppText.get(R.string.monitor_pairing_code),secret=true)
        code.inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        val dialog=AlertDialog.Builder(this).setTitle(AppText.get(R.string.monitor_link)).setView(scrollForm(box))
            .setNegativeButton(AppText.get(R.string.cancel),null).setNeutralButton(AppText.get(R.string.monitor_unlink),null).setPositiveButton(AppText.get(R.string.save),null).create()
        dialog.setOnDismissListener { code.text.clear() }
        dialog.show(); fitDialog(dialog)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val value=code.text.toString().trim()
            if (runCatching { MonitorLink.parse(value) }.isFailure) { code.error=AppText.get(R.string.monitor_code_invalid); return@setOnClickListener }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled=false
            worker.execute {
                val result=runCatching { passwords.write("monitor.${device.id}",device.url,value); MonitorSessionReporter(applicationContext,value).close() }
                runOnUiThread { if (result.isSuccess) dialog.dismiss() else { dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled=true; code.error=AppText.get(R.string.monitor_link_failed) } }
            }
        }
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
            worker.execute {
                val result=runCatching { passwords.read("monitor.${device.id}",device.url)?.let { MonitorSessionReporter(applicationContext,it).close() }; passwords.remove("monitor.${device.id}") }
                runOnUiThread { if (result.isSuccess) dialog.dismiss() else code.error=AppText.get(R.string.monitor_link_failed) }
            }
        }
    }
    internal fun disconnectToDeviceList() {
        // Status polling never opens a remote session.
        closePowerControl()
        home()
    }
    internal fun showLanguageSettings(): AlertDialog {
        val current = LanguageSettings.selected(this)
        var selected = current
        val box = form()
        if (session != null) label(box,AppText.get(R.string.language_connection_notice))
        val group = RadioGroup(this)
        LanguageSettings.codes.forEachIndexed { index,code ->
            group.addView(RadioButton(this).apply {
                id = View.generateViewId(); text = LanguageSettings.names[index]
                minHeight = dp(48); isChecked = code == current
                setOnCheckedChangeListener { _,checked -> if (checked) selected = code }
            })
        }
        box.addView(group)
        val dialog = AlertDialog.Builder(this).setTitle(AppText.get(R.string.language_settings)).setView(scrollForm(box))
            .setNegativeButton(AppText.get(R.string.cancel),null).setPositiveButton(AppText.get(R.string.apply_language),null).create()
        dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            if (selected == current) { dialog.dismiss(); return@setOnClickListener }
            runCatching { LanguageSettings.save(this,selected) }.onSuccess {
                dialog.dismiss(); recreate()
            }.onFailure { Toast.makeText(this,AppText.get(R.string.language_save_failed),Toast.LENGTH_LONG).show() }
        } }
        dialog.show(); fitDialog(dialog)
        return dialog
    }
    override fun onResume() { super.onResume(); foreground = true; ui.removeCallbacks(pollAgents); ui.post(pollAgents) }
    override fun onPause() { foreground = false; ui.removeCallbacks(pollAgents); session?.pauseInput(); closePowerControl(); super.onPause() }
    override fun onRestart() { super.onRestart(); if (homeToolbar == null) home() }
    override fun onStop() { closeSession(); closePowerControl(); super.onStop() }
    private fun tailscale() {
        val box = form()
        val tailnet = field(box,"Tailnet", "-")
        val token = field(box,AppText.get(R.string.tailscale_token),secret = true)
        label(box,AppText.get(R.string.tailscale_help))
        val status = TextView(this); box.addView(status)
        val dialog = AlertDialog.Builder(this).setTitle(AppText.get(R.string.tailscale_import_title)).setView(scrollForm(box)).setNegativeButton(AppText.get(R.string.close),null).setPositiveButton(AppText.get(R.string.query),null).create()
        dialog.setOnShowListener { dialog.getButton(-1).setOnClickListener {
            val key = token.text.toString().trim(); val net = tailnet.text.toString().trim()
            if (key.isBlank() || net.isBlank()) { status.text = AppText.get(R.string.tailscale_fields_required); return@setOnClickListener }
            dialog.getButton(-1).isEnabled = false; status.text = AppText.get(R.string.querying)
            worker.execute {
                val result = runCatching {
                    val connection = URL("https://api.tailscale.com/api/v2/tailnet/${URLEncoder.encode(net,"UTF-8")}/devices").openConnection() as HttpURLConnection
                    try {
                        connection.connectTimeout = 10000; connection.readTimeout = 15000; connection.instanceFollowRedirects = false
                        connection.setRequestProperty("Authorization","Bearer $key")
                        require(connection.responseCode == 200) { AppText.get(R.string.tailscale_query_failed, connection.responseCode) }
                        val data = connection.inputStream.bufferedReader().use { reader ->
                            val body = StringBuilder(); val buffer = CharArray(8192)
                            while (true) { val count = reader.read(buffer); if (count < 0) break; require(body.length + count <= 4_000_000) { AppText.get(R.string.device_list_too_large) }; body.append(buffer, 0, count) }
                            JSONObject(body.toString()).getJSONArray("devices")
                        }
                        buildList<Pair<String,String>> {
                            for (i in 0 until data.length()) {
                                val d = data.getJSONObject(i); val name = d.optString("hostname",d.optString("name",AppText.get(R.string.device)))
                                val dns = d.optString("name").trimEnd('.')
                                if (dns.isNotBlank()) add(name to dns)
                                val addresses = d.optJSONArray("addresses") ?: JSONArray()
                                for (j in 0 until addresses.length()) {
                                    val ip = addresses.getString(j); add(name to if (ip.contains(':')) "[$ip]" else ip)
                                }
                            }
                        }
                    } finally { connection.disconnect() }
                }
                runOnUiThread {
                    if (isDestroyed || !dialog.isShowing) return@runOnUiThread
                    dialog.getButton(-1).isEnabled = true
                    result.onSuccess { entries ->
                        token.text.clear()
                        if (entries.isEmpty()) { status.text = AppText.get(R.string.no_import_addresses) } else {
                            dialog.dismiss()
                            AlertDialog.Builder(this).setTitle(AppText.get(R.string.select_import_address)).setItems(entries.map { "${it.first}\n${it.second}" }.toTypedArray()) { _,index ->
                                edit(importedName = entries[index].first, importedAddress = entries[index].second)
                            }.setNegativeButton(AppText.get(R.string.cancel),null).show()
                        }
                    }.onFailure { status.text = if (it is IllegalArgumentException) it.message else AppText.get(R.string.network_error) }
                }
            }
        } }; dialog.show(); fitDialog(dialog)
        val fields = (0 until box.childCount).mapNotNull { box.getChildAt(it) as? EditText }
        EditorActions.bind(fields) {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).let { if (it.isEnabled) it.performClick() }
        }
        fields.firstOrNull()?.requestFocus()
    }
    // API 33+ uses OnBackInvokedDispatcher above; this override supports API 26-32.
    @android.annotation.SuppressLint("GestureBackNavigation")
    @Deprecated("Legacy back handling")
    override fun onBackPressed() { if (session != null) home() else super.onBackPressed() }
    override fun onDestroy() { ui.removeCallbacks(pollAgents); closePowerControl(); closeSession(); worker.shutdownNow(); agentWorker.shutdownNow(); super.onDestroy() }
}
