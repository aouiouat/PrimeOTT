import glob

# 1. إصلاح السطر المنقسم في WatchRoomActivity.kt
w_room = "app/src/main/java/com/primeott/app/presentation/watch/WatchRoomActivity.kt"
with open(w_room, "r", encoding="utf-8") as f:
    c = f.read()

# استبدال نص المشاركة المقسوم بسطر واحد سليم
bad_block = """            val shareText = "🎉 انضم إليّ لمشاهدة (${r.contentTitle}) مباشرة في غرفة Watch Together!
" +
                "🔑 كود الغرفة: ${r.roomCode}
" +
                "🔗 رابط الانضمام المباشر: ${r.inviteUrl}\""""

good_block = '            val shareText = "🎉 انضم إليّ لمشاهدة (${r.contentTitle}) مباشرة في غرفة Watch Together! | 🔑 كود الغرفة: ${r.roomCode} | 🔗 الرابط: ${r.inviteUrl}"'

c = c.replace(bad_block, good_block)

with open(w_room, "w", encoding="utf-8") as f:
    f.write(c)

# 2. إصلاح السطر المنقسم في activity_main.xml و fragment_watch_together.xml إن وجد
main_kt = "app/src/main/java/com/primeott/app/MainActivity.kt"
with open(main_kt, "r", encoding="utf-8") as f:
    mc = f.read()

mc = mc.replace('"🏠\nالرئيسية"', '"🏠 الرئيسية"').replace('"🏠\nAccueil"', '"🏠 Accueil"').replace('"🏠\nHome"', '"🏠 Home"')
mc = mc.replace('"📡\nالمباشر"', '"📡 المباشر"').replace('"📡\nDirect"', '"📡 Direct"').replace('"📡\nLive TV"', '"📡 Live TV"')
mc = mc.replace('"⚽\nالمباريات"', '"⚽ المباريات"').replace('"⚽\nMatchs"', '"⚽ Matchs"').replace('"⚽\nMatches"', '"⚽ Matches"')
mc = mc.replace('"👥\nجماعية"', '"👥 جماعية"').replace('"👥\nEnsemble"', '"👥 Ensemble"').replace('"👥\nWatch Party"', '"👥 Watch"')
mc = mc.replace('"🎬\nالأفلام"', '"🎬 الأفلام"').replace('"🎬\nFilms"', '"🎬 Films"').replace('"🎬\nMovies"', '"🎬 Movies"')
mc = mc.replace('"📺\nالمسلسلات"', '"📺 المسلسلات"').replace('"📺\nSéries"', '"📺 Séries"').replace('"📺\nSeries"', '"📺 Series"')

with open(main_kt, "w", encoding="utf-8") as f:
    f.write(mc)

print("[✓] Fixed broken string literals in Kotlin files!")
