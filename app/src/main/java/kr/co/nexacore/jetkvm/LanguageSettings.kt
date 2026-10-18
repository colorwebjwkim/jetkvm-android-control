package kr.co.nexacore.jetkvm

import android.app.Application
import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import java.util.Locale

/** App language is explicit; an unset preference always means Korean. */
object LanguageSettings {
    val codes = listOf("ko","en","ja")
    val names = arrayOf("한국어","English","日本語")
    fun selected(context: Context): String = context.getSharedPreferences("language",Context.MODE_PRIVATE)
        .getString("code","ko").takeIf { it in codes } ?: "ko"
    fun wrap(context: Context, code: String = selected(context)): Context {
        require(code in codes)
        val config = Configuration(context.resources.configuration)
        config.setLocales(LocaleList(Locale.forLanguageTag(code)))
        return context.createConfigurationContext(config)
    }
    fun save(context: Context, code: String) {
        require(code in codes)
        check(context.getSharedPreferences("language",Context.MODE_PRIVATE).edit().putString("code",code).commit())
        AppText.initialize(context.applicationContext)
    }
}

/** Localized application resources shared with background connection callbacks. */
object AppText {
    @Volatile private var localized: android.content.res.Resources? = null
    fun initialize(context: Context) { localized = LanguageSettings.wrap(context.applicationContext).resources }
    fun get(id: Int, vararg args: Any): String = checkNotNull(localized) { "Application strings not initialized" }.getString(id,*args)
    // Pure JVM address/HTTP tests have no Android Application or resources.
    fun fallback(id: Int, korean: String, vararg args: Any): String =
        localized?.getString(id,*args) ?: String.format(Locale.KOREAN,korean,*args)
}

class JetKvmApplication : Application() {
    override fun onCreate() { super.onCreate(); AppText.initialize(this) }
}
