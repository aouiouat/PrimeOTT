#!/bin/bash

DIR="./main"
MANIFEST="$DIR/AndroidManifest.xml"

echo "🔥 Début de la suppression complète de Watch Together..."

# 1. مسح المجلدات والملفات الخاصة بـ Watch Together
echo "📂 Suppression des fichiers..."
rm -rf "$DIR/java/com/primeott/app/presentation/watch/"
rm -f "$DIR/res/layout/activity_watch_room.xml"
rm -f "$DIR/res/layout/fragment_watch_together.xml"
rm -f "$DIR/res/layout/item_watch_room.xml"
find "$DIR/res/drawable" -type f -name "wt_*.xml" -delete

# 2. حذف الأكتيفيتي من ملف AndroidManifest.xml
echo "⚙️ Nettoyage du AndroidManifest.xml..."
if [ -f "$MANIFEST" ]; then
    # مسح البلوك تاع الأكتيفيتي إذا كان متكون من عدة أسطر
    perl -0777 -pi -e 's/<activity[^>]*WatchRoomActivity.*?<\/activity>//gs' "$MANIFEST"
    # مسح الأكتيفيتي إذا كانت مكتوبة في سطر واحد
    perl -0777 -pi -e 's/<activity[^>]*WatchRoomActivity.*?\/>//gs' "$MANIFEST"
fi

# 3. تنظيف ملفات الكود Kotlin من الـ Imports و الـ Intents
echo "🧹 Nettoyage des références dans le code Kotlin..."
find "$DIR/java" -type f -name "*.kt" -exec sed -i '/com.primeott.app.presentation.watch/d' {} +
find "$DIR/java" -type f -name "*.kt" -exec sed -i '/WatchRoomActivity/d' {} +
find "$DIR/java" -type f -name "*.kt" -exec sed -i '/WatchTogether/d' {} +

# 4. تنظيف ملفات الـ XML (مثل قوائم الميني أو الـ Navigation)
echo "🎨 Nettoyage des références dans les fichiers XML..."
find "$DIR/res" -type f -name "*.xml" -exec sed -i '/WatchRoomActivity/d' {} +
find "$DIR/res" -type f -name "*.xml" -exec sed -i '/fragment_watch_together/d' {} +
find "$DIR/res/menu" -type f -name "*.xml" -exec sed -i '/watch_together/d' {} +

echo "✅ Terminé avec succès ! (N'oublie pas de faire 'Build -> Clean Project' dans Android Studio)"
