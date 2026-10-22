package com.primeott.app.presentation.watch

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.primeott.app.R
import com.primeott.app.presentation.auth.ProfileSettingsBinder

class WatchTogetherFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_profile_settings, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        ProfileSettingsBinder.bind(
            hostContext = requireContext(),
            rootView = view,
            hostActivity = activity,
            isStandalone = false
        )
    }

    companion object {
        fun newInstance(): WatchTogetherFragment = WatchTogetherFragment()
    }
}