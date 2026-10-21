with open("app/src/main/java/com/primeott/app/data/repository/OttRepository.kt", "r", encoding="utf-8") as f:
    repo = f.read()

# قراءة remote_config من Cloudflare وتحديث الـ SessionManager فوراً
old_sync_line = 'SessionManager.AD_URL = root.optString("ad_url", SessionManager.AD_URL)'
new_sync_block = """SessionManager.AD_URL = root.optString("ad_url", SessionManager.AD_URL)
                root.optJSONObject("remote_config")?.let { rc ->
                    SessionManager.ADS_ENABLED = rc.optBoolean("ads_enabled", true)
                    SessionManager.AD_SECONDS = rc.optInt("ad_seconds", 10)
                    SessionManager.BANNER_BADGE = rc.optString("banner_badge", SessionManager.BANNER_BADGE)
                    SessionManager.BANNER_TITLE = rc.optString("banner_title", SessionManager.BANNER_TITLE)
                    SessionManager.BANNER_STREAM_ID = rc.optString("banner_stream_id", "6171")
                    SessionManager.ANNOUNCEMENT_TEXT = rc.optString("announcement", "")
                    SessionManager.REMOTE_UPDATE_VERSION = rc.optInt("update_version", 2)
                    SessionManager.REMOTE_UPDATE_URL = rc.optString("update_url", "")
                    SessionManager.REMOTE_UPDATE_MSG = rc.optString("update_message", "")
                }"""

# السماح بالمزامنة الحية دائماً عند فتح الرئيسية أو المباريات
repo = repo.replace("if (isConfigLoaded) return@withContext", "// Live Remote Sync Enabled")
repo = repo.replace(old_sync_line, new_sync_block)

with open("app/src/main/java/com/primeott/app/data/repository/OttRepository.kt", "w", encoding="utf-8") as f:
    f.write(repo)

# تحديث PlayerActivity لتخطي الإعلان فوراً إذا قمت بإيقاف الإعلانات من لوحة التحكم
with open("app/src/main/java/com/primeott/app/presentation/player/PlayerActivity.kt", "r", encoding="utf-8") as f:
    player_code = f.read()

old_ad_call = """    private fun showMandatoryAd() {
        AdCountdownDialog("""

new_ad_call = """    private fun showMandatoryAd() {
        if (!SessionManager.ADS_ENABLED || SessionManager.AD_SECONDS <= 0) {
            isAdCompleted = true
            initAndPlayExoPlayer()
            return
        }
        AdCountdownDialog("""

player_code = player_code.replace(old_ad_call, new_ad_call)

with open("app/src/main/java/com/primeott/app/presentation/player/PlayerActivity.kt", "w", encoding="utf-8") as f:
    f.write(player_code)

# تحديث HomeFragment ليقرأ البانر، الرسالة الفورية، وفاحص التحديث من Cloudflare مباشرة
with open("app/src/main/java/com/primeott/app/presentation/sections/AppSectionsFragments.kt", "r", encoding="utf-8") as f:
    frag_code = f.read()

old_home = """class HomeFragment : Fragment() {
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_home, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val binding = FragmentHomeBinding.bind(view)

        binding.rvHomeChannels.layoutManager = LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)

        viewLifecycleOwner.lifecycleScope.launch {
            OttRepository.syncWithCloudflare()
            val movies = OttRepository.fetchMovies()
            binding.rvHomeChannels.adapter = VodGridAdapter(movies.take(15)) { vod ->
                val tempChannel = Channel(vod.id, "vod", vod.title, vod.posterUrl, vod.streamUrls)
                startActivity(Intent(requireContext(), PlayerActivity::class.java).apply {
                    putExtra(PlayerActivity.EXTRA_CHANNEL, tempChannel)
                })
            }
        }

        binding.btnWatchFeatured.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch {
                val matches = OttRepository.fetchMatchesFromCloudflare()
                if (matches.isNotEmpty()) {
                    startActivity(Intent(requireContext(), PlayerActivity::class.java).apply {
                        putExtra(PlayerActivity.EXTRA_CHANNEL, matches.first().channel)
                    })
                }
            }
        }
    }
}"""

new_home = """class HomeFragment : Fragment() {
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_home, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val binding = FragmentHomeBinding.bind(view)

        binding.rvHomeChannels.layoutManager = LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)

        viewLifecycleOwner.lifecycleScope.launch {
            OttRepository.syncWithCloudflare()

            // 1. تحديث البانر الرئيسي والرسالة العاجلة فوراً من لوحة التحكم
            binding.tvBannerBadge.text = SessionManager.BANNER_BADGE
            binding.tvBannerTitle.text = SessionManager.BANNER_TITLE

            if (SessionManager.ANNOUNCEMENT_TEXT.isNotEmpty()) {
                binding.tvRemoteAnnouncement.visibility = View.VISIBLE
                binding.tvRemoteAnnouncement.text = "📢 " + SessionManager.ANNOUNCEMENT_TEXT
            } else {
                binding.tvRemoteAnnouncement.visibility = View.GONE
            }

            // 2. فحص إذا قمت بإرسال تحديث APK عن بعد
            if (SessionManager.REMOTE_UPDATE_VERSION > SessionManager.CURRENT_APP_VERSION && SessionManager.REMOTE_UPDATE_URL.isNotEmpty()) {
                AlertDialog.Builder(requireContext())
                    .setTitle("🚀 تحديث جديد متوفر")
                    .setMessage(SessionManager.REMOTE_UPDATE_MSG)
                    .setCancelable(false)
                    .setPositiveButton("تحديث الآن") { _, _ ->
                        startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(SessionManager.REMOTE_UPDATE_URL)))
                    }
                    .setNegativeButton("لاحقاً", null)
                    .show()
            }

            val movies = OttRepository.fetchMovies()
            binding.rvHomeChannels.adapter = VodGridAdapter(movies.take(15)) { vod ->
                val tempChannel = Channel(vod.id, "vod", vod.title, vod.posterUrl, vod.streamUrls)
                startActivity(Intent(requireContext(), PlayerActivity::class.java).apply {
                    putExtra(PlayerActivity.EXTRA_CHANNEL, tempChannel)
                })
            }
        }

        binding.btnWatchFeatured.setOnClickListener {
            val sid = SessionManager.BANNER_STREAM_ID
            val hls = "${SessionManager.XTREAM_BASE_URL}/live/${SessionManager.XTREAM_USER}/${SessionManager.XTREAM_PASS}/$sid.m3u8"
            val ts = "${SessionManager.XTREAM_BASE_URL}/live/${SessionManager.XTREAM_USER}/${SessionManager.XTREAM_PASS}/$sid.ts"
            val ch = Channel(sid, "featured", SessionManager.BANNER_TITLE, "", listOf(hls, ts))
            startActivity(Intent(requireContext(), PlayerActivity::class.java).apply {
                putExtra(PlayerActivity.EXTRA_CHANNEL, ch)
            })
        }
    }
}"""

frag_code = frag_code.replace(old_home, new_home)

with open("app/src/main/java/com/primeott/app/presentation/sections/AppSectionsFragments.kt", "w", encoding="utf-8") as f:
    f.write(frag_code)

print("✅ تم تفعيل نظام التحديث الحي الفوري (Zero-APK Live Update) في التطبيق!")
