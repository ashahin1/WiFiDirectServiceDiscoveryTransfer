package esnetlab.apps.android.wifidirect.wfdservicediscoverytransfer.ui

import android.os.Bundle
import android.text.InputType
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import esnetlab.apps.android.wifidirect.wfdservicediscoverytransfer.R
import esnetlab.apps.android.wifidirect.wfdservicediscoverytransfer.protocol.AdsPolicyConfig
import esnetlab.apps.android.wifidirect.wfdservicediscoverytransfer.service.AdsDisseminationService

class SettingsFragment : PreferenceFragmentCompat() {

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.pref_general, rootKey)

        configureNumericPreference(
            key = AdsDisseminationService.PREF_MAX_TTL_SECONDS,
            minValue = AdsPolicyConfig.MIN_TTL_SECONDS,
            maxValue = AdsPolicyConfig.MAX_TTL_SECONDS_LIMIT
        )
        configureNumericPreference(
            key = AdsDisseminationService.PREF_DISCOVERY_PERIOD_SECONDS,
            minValue = AdsPolicyConfig.MIN_DISCOVERY_PERIOD_SECONDS,
            maxValue = AdsPolicyConfig.MAX_DISCOVERY_PERIOD_SECONDS
        )
    }

    private fun configureNumericPreference(key: String, minValue: Int, maxValue: Int) {
        val pref = findPreference<EditTextPreference>(key) ?: return
        pref.setOnBindEditTextListener { editText ->
            editText.inputType = InputType.TYPE_CLASS_NUMBER
        }
        pref.onPreferenceChangeListener = Preference.OnPreferenceChangeListener { preference, newValue ->
            val parsed = newValue?.toString()?.trim()?.toIntOrNull()
            if (parsed == null) return@OnPreferenceChangeListener false
            val clamped = parsed.coerceIn(minValue, maxValue)
            if (clamped != parsed) {
                (preference as? EditTextPreference)?.text = clamped.toString()
                return@OnPreferenceChangeListener false
            }
            true
        }
    }
}
