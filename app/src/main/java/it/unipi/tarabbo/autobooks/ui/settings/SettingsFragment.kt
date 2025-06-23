package it.unipi.tarabbo.autobooks.ui.settings

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Spinner
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import it.unipi.tarabbo.autobooks.databinding.FragmentSettingsBinding
import kotlinx.coroutines.launch
import java.util.Locale


class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null

    // This property is only valid between onCreateView and
    // onDestroyView.
    private val binding get() = _binding!!

    lateinit var languageSpinner : Spinner
    lateinit var voiceSpinner: Spinner
    private var tts : TextToSpeech? = null

    private var avaliableLanguages = mutableListOf<Locale>()
    private var avaliableVoices = mutableListOf<Voice>()

    private var selectedLocale: Locale? = null
    private var selectedVoice : Voice? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val settingsViewModel =
            ViewModelProvider(this).get(SettingsViewModel::class.java)

        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        val root: View = binding.root

        languageSpinner = binding.languageSpinner
        voiceSpinner = binding.voiceSpinner

        // setup in another thread
        lifecycleScope.launch{
            tts = TextToSpeech(context){
                //tts onInit fun
                    status ->
                if(status  == TextToSpeech.SUCCESS){
                    //add all available languages to the list
                    avaliableLanguages.addAll(tts!!.availableLanguages.sortedBy { it.displayName })

                    //add languages to the spinner
                    val langNames = avaliableLanguages.map { it.displayName }
                    languageSpinner.adapter = ArrayAdapter(requireContext() , android.R.layout.simple_spinner_dropdown_item , langNames)

                    //set onItemSelectedListener for each item
                    languageSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                        override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                            selectedLocale = avaliableLanguages[position]
                            tts?.language = selectedLocale
                            selectedLocale?.let { updateVoicesForLocale(it) } //safe access operator, use if not null
                        }
                        override fun onNothingSelected(parent: AdapterView<*>) {
                        }
                    }
                }
            }
        }

        //Set test audio button on click listener
        binding.testSettingsButton.setOnClickListener {
            val locale = selectedLocale
            val voice = selectedVoice

            if (locale != null && voice != null && tts != null) {
                tts?.language = locale
                tts?.voice = voice

                val testString = "1 2 3 4 10 100 1000 !"
                tts?.speak(testString, TextToSpeech.QUEUE_FLUSH, null, "TEST_UTTERANCE")
            } else {
                Toast.makeText(requireContext(), "Please select both language and voice first", Toast.LENGTH_SHORT).show()
            }
        }


        //Set save button on click listener
        binding.saveSettingsButton.setOnClickListener {

            val locale = selectedLocale
            val voice = selectedVoice

            if(locale != null && voice != null){
                val prefs = requireContext().getSharedPreferences("tts_settings" , Context.MODE_PRIVATE)
                prefs.edit()
                    .putString("selected_language" , locale.toLanguageTag())
                    .putString("selected_voice" , voice.name)
                    .apply()

                Toast.makeText(requireContext() , "Settings saved" , Toast.LENGTH_SHORT).show()
            }else {
                Toast.makeText(requireContext(), "Please select both language and voice", Toast.LENGTH_SHORT).show()
            }
        }

        return root
    }

    override fun onDestroyView() {
        tts?.shutdown()
        super.onDestroyView()
        _binding = null
    }


    private fun updateVoicesForLocale(locale : Locale){
        //filter out voices that require an internet connection and that are not the correct language
        val voices = tts?.voices?.filter {
            it.locale == locale && !it.isNetworkConnectionRequired
        } ?: emptyList()

        avaliableVoices.clear()
        avaliableVoices.addAll(voices)

        val voiceNames = voices.map {it.name}
        voiceSpinner.adapter = ArrayAdapter(requireContext() , android.R.layout.simple_spinner_dropdown_item , voiceNames)

        //set onItemSelectedListener
        voiceSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener{
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                selectedVoice = avaliableVoices[position]
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }
}