package com.spectrefi.app.ui.welcome

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatDelegate
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import com.spectrefi.app.R
import com.spectrefi.app.WelcomeActivity
import com.spectrefi.app.WelcomeViewModel
import com.spectrefi.app.databinding.FragmentWelcomeThemeBinding
import com.spectrefi.app.ui.settings.SettingsViewModel

class WelcomeThemeFragment : Fragment() {

    private var _binding: FragmentWelcomeThemeBinding? = null
    private val binding get() = _binding!!
    private val settingsViewModel: SettingsViewModel by activityViewModels()
    private val welcomeViewModel: WelcomeViewModel by activityViewModels()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentWelcomeThemeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        (activity as? WelcomeActivity)?.setBottomHint(null)
        setupThemeButtons()
        setupColorTheme()
    }

    private fun setupThemeButtons() {
        binding.radioGroupTheme.setOnCheckedChangeListener { _, checkedId ->
            val theme = when (checkedId) {
                R.id.radioButtonSystemTheme -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                R.id.radioButtonLightTheme -> AppCompatDelegate.MODE_NIGHT_NO
                R.id.radioButtonDarkTheme -> AppCompatDelegate.MODE_NIGHT_YES
                else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            }
            settingsViewModel.setTheme(theme)
            welcomeViewModel.setSelectedTheme(theme)
        }
    }

    private fun setupColorTheme() {
        binding.radioGroupColorTheme.setOnCheckedChangeListener { _, checkedId ->
            val colorTheme = when (checkedId) {
                R.id.radioButtonPurpleTheme -> "purple"
                R.id.radioButtonGreenTheme -> "green"
                R.id.radioButtonBlueTheme -> "blue"
                else -> "green"
            }
            settingsViewModel.setColorTheme(colorTheme)
            welcomeViewModel.setSelectedColorTheme(colorTheme)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        fun newInstance() = WelcomeThemeFragment()
    }
}
