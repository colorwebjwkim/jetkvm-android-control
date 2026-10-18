package kr.co.nexacore.jetkvm

import android.app.AlertDialog
import android.app.Instrumentation
import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.content.Context
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.RadioButton
import android.widget.TextView

/** Exercises the real picker, Activity recreation and preference reload; restores the user's setting. */
object LanguageSmoke {
    fun run(instrumentation: Instrumentation) {
        val context = instrumentation.targetContext
        val prefs = context.getSharedPreferences("language",Context.MODE_PRIVATE)
        val original = prefs.getString("code",null)
        var activity: MainActivity? = null
        fun onMain(block: () -> Unit) {
            var failure: Throwable? = null
            instrumentation.runOnMainSync { try { block() } catch (error: Throwable) { failure = error } }
            failure?.let { throw it }
        }
        fun launch(): MainActivity = (instrumentation.startActivitySync(Intent(context,MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity).also { instrumentation.waitForIdleSync() }
        fun views(view: View): List<View> = listOf(view) + if (view is ViewGroup) (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()
        fun verify(code: String, listLabel: String, connectLabel: String) {
            onMain {
                val current = checkNotNull(activity)
                check(current.resources.configuration.locales[0].language == code) { "Activity locale did not change to $code" }
                check(LanguageSettings.selected(context) == code) { "Language preference was not retained" }
                check(AppText.get(R.string.connect) == connectLabel) { "Background messages use a different language" }
                val root = current.findViewById<View>(android.R.id.content)
                check(views(root).filterIsInstance<TextView>().any { it.text.startsWith(listLabel) }) { "Device list not translated to $code" }
                val toolbar = views(root).filterIsInstance<ControlToolbar>().single()
                check(toolbar.list.contentDescription == current.getString(R.string.device_list))
                val addressError = runCatching { DeviceAddress.normalize("","") }.exceptionOrNull()
                check(addressError?.message == current.getString(R.string.address_required)) { "Input errors not translated" }
                check(current.getString(R.string.connection_timeout,"AUTH",1,2).contains("AUTH"))
                check(!current.getString(R.string.connection_details,"a","b",1280,720).contains("%"))
            }
        }
        try {
            // Simulate an unset preference under a non-Korean device default without changing system settings.
            check(prefs.edit().remove("code").commit())
            AppText.initialize(context)
            val originalLocale = java.util.Locale.getDefault()
            try {
                java.util.Locale.setDefault(java.util.Locale.JAPANESE)
                check(LanguageSettings.selected(context) == "ko")
                check(LanguageSettings.wrap(context).resources.configuration.locales[0].language == "ko")
            } finally { java.util.Locale.setDefault(originalLocale) }
            activity = launch()
            verify("ko","내 장비","연결")
            listOf(Triple("en","My devices","Connect"),Triple("ja","マイデバイス","接続"),Triple("ko","내 장비","연결")).forEach { (code,listLabel,connectLabel) ->
                val previous = activity
                val resumed = java.util.concurrent.CountDownLatch(1)
                var replacement: MainActivity? = null
                val application = context.applicationContext as Application
                val listener = object : Application.ActivityLifecycleCallbacks {
                    override fun onActivityResumed(candidate: Activity) {
                        if (candidate is MainActivity && candidate !== previous) { replacement = candidate; resumed.countDown() }
                    }
                    override fun onActivityCreated(activity: Activity,state: Bundle?) {}
                    override fun onActivityStarted(activity: Activity) {}
                    override fun onActivityPaused(activity: Activity) {}
                    override fun onActivityStopped(activity: Activity) {}
                    override fun onActivitySaveInstanceState(activity: Activity,state: Bundle) {}
                    override fun onActivityDestroyed(activity: Activity) {}
                }
                application.registerActivityLifecycleCallbacks(listener)
                try {
                    lateinit var dialog: AlertDialog
                    onMain { dialog = checkNotNull(activity).showLanguageSettings() }
                    instrumentation.waitForIdleSync() // AlertDialog installs the Apply listener via its queued onShow callback.
                    onMain {
                        val index = LanguageSettings.codes.indexOf(code)
                        val radios = views(dialog.window!!.decorView).filterIsInstance<RadioButton>()
                        check(radios.size == 3)
                        radios[index].performClick()
                        check(radios[index].isChecked) { "Language option not selected: $code" }
                        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
                        check(LanguageSettings.selected(context) == code) { "Picker did not save $code; current=${LanguageSettings.selected(context)}" }
                    }
                    check(resumed.await(8,java.util.concurrent.TimeUnit.SECONDS)) { "Language change did not recreate Activity" }
                    activity = checkNotNull(replacement)
                } finally { application.unregisterActivityLifecycleCallbacks(listener) }
                instrumentation.waitForIdleSync()
                verify(code,listLabel,connectLabel)
                onMain { activity?.finish() }
                instrumentation.waitForIdleSync()
                activity = launch()
                verify(code,listLabel,connectLabel)
            }
            // Cancel must leave the selected locale unchanged.
            onMain {
                val dialog = checkNotNull(activity).showLanguageSettings()
                views(dialog.window!!.decorView).filterIsInstance<RadioButton>()[1].performClick()
                dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
                check(LanguageSettings.selected(context) == "ko")
            }
        } finally {
            onMain { activity?.finish() }
            instrumentation.waitForIdleSync()
            val edit = prefs.edit()
            if (original == null) edit.remove("code") else edit.putString("code",original)
            check(edit.commit())
            AppText.initialize(context)
        }
    }
}
