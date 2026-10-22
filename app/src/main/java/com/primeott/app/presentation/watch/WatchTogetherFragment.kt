package com.primeott.app.presentation.watch

import android.app.Activity
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import com.primeott.app.R
import com.primeott.app.presentation.auth.ProfileSettingsBinder

class WatchTogetherFragment : Fragment() {

    private var rootViewRef: View? = null

    private val photoLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { res ->
        if (res.resultCode == Activity.RESULT_OK && context != null) {
            ProfileSettingsBinder.handlePickedPhoto(requireContext(), rootViewRef, res.data?.data)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_profile_settings, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        rootViewRef = view
        ProfileSettingsBinder.bind(
            hostContext = requireContext(),
            rootView = view,
            hostActivity = activity,
            isStandalone = false,
            photoPickLauncher = photoLauncher
        )
    }

    companion object {
        fun newInstance(): WatchTogetherFragment = WatchTogetherFragment()
    }
}