package dev.opencaller.app

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

/**
 * In-app language override. "" follows the system; otherwise user-visible
 * strings resolve through a context wrapped with the chosen locale.
 * Wrapped manually (not the per-app locale APIs) so it covers every
 * surface uniformly on all supported Android versions — including the
 * notifications and overlays posted from services, which per-app locale
 * only localizes on Android 13+.
 */
object L10n {
  /** Selectable language tags, in picker order. "" = system default. */
  val CHOICES = listOf("", "en", "id")

  fun wrap(context: Context): Context {
    val tag = Prefs.language(context)
    if (tag.isEmpty()) return context
    val config = Configuration(context.resources.configuration)
    config.setLocale(Locale.forLanguageTag(tag))
    return context.createConfigurationContext(config)
  }

  /** Localized string for non-activity contexts (services, managers). */
  fun str(context: Context, resId: Int, vararg args: Any): String =
    if (args.isEmpty()) wrap(context).getString(resId)
    else wrap(context).getString(resId, *args)

  /** Display label for a DB category slug (slugs stay English on disk). */
  fun category(context: Context, slug: String): String = when (slug) {
    "scam" -> str(context, R.string.cat_scam)
    "robocall" -> str(context, R.string.cat_robocall)
    "telemarketing" -> str(context, R.string.cat_telemarketing)
    "debt-collection" -> str(context, R.string.cat_debt)
    "survey" -> str(context, R.string.cat_survey)
    "other" -> str(context, R.string.cat_other)
    "sms-spam" -> str(context, R.string.cat_sms_spam)
    Prefs.HEURISTIC -> str(context, R.string.cat_suspicious)
    else -> slug
  }
}
