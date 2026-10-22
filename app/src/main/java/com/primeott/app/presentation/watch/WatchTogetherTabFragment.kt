package com.primeott.app.presentation.watch

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.primeott.app.R

class WatchTogetherTabFragment : Fragment() {

    private var controller: WatchTogetherController? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.activity_watch_room, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        controller = WatchTogetherController(
            hostContext = requireContext(),
            rootView = view,
            hostActivity = activity,
            isStandaloneActivity = false
        ).also { it.init() }
    }

    override fun onDestroyView() {
        controller?.release()
        controller = null
        super.onDestroyView()
    }

    companion object {
        fun newInstance(): WatchTogetherTabFragment = WatchTogetherTabFragment()
    }
}