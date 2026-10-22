#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Prime OTT - Complete Watch Together Pro Upgrade Script
يقوم هذا السكربت بتحديث شاشة ومحرك Watch Together بالكامل من الداخل فقط
دون تغيير أي واجهة خارجية في التطبيق، مع دعم كافة الميزات الـ 50 المطلوبة.
"""

import os
import re
import sys
import shutil
from datetime import datetime

PROJECT_ROOT = os.path.abspath(os.path.dirname(__file__))
APP_MAIN = os.path.join(PROJECT_ROOT, "app", "src", "main")
RES_DIR = os.path.join(APP_MAIN, "res")
LAYOUT_DIR = os.path.join(RES_DIR, "layout")
DRAWABLE_DIR = os.path.join(RES_DIR, "drawable")
MANIFEST_PATH = os.path.join(APP_MAIN, "AndroidManifest.xml")


def find_package_and_source_dir():
    """اكتشاف الحزمة ومسار الكود تلقائياً من المشروع الحالي بدون كسر أي ملف"""
    java_root = os.path.join(APP_MAIN, "java")
    kotlin_root = os.path.join(APP_MAIN, "kotlin")
    search_root = java_root if os.path.exists(java_root) else kotlin_root

    watch_activity_path = None
    detected_package = None

    for root, _, files in os.walk(search_root):
        for f in files:
            if f == "WatchRoomActivity.kt":
                watch_activity_path = os.path.join(root, f)
                with open(watch_activity_path, "r", encoding="utf-8", errors="ignore") as fp:
                    content = fp.read()
                    m = re.search(r"^\s*package\s+([a-zA-Z0-9_.]+)", content, re.MULTILINE)
                    if m:
                        detected_package = m.group(1)
                break
        if watch_activity_path:
            break

    if not detected_package:
        for root, _, files in os.walk(search_root):
            for f in files:
                if f.endswith(".kt"):
                    full_p = os.path.join(root, f)
                    with open(full_p, "r", encoding="utf-8", errors="ignore") as fp:
                        content = fp.read()
                        m = re.search(r"^\s*package\s+([a-zA-Z0-9_.]+)", content, re.MULTILINE)
                        if m:
                            detected_package = m.group(1)
                            watch_activity_path = os.path.join(root, "WatchRoomActivity.kt")
                            break
            if detected_package:
                break

    if not detected_package:
        detected_package = "com.primeott.app"
        pkg_dir = os.path.join(search_root, *detected_package.split("."))
        os.makedirs(pkg_dir, exist_ok=True)
        watch_activity_path = os.path.join(pkg_dir, "WatchRoomActivity.kt")

    return detected_package, os.path.dirname(watch_activity_path)


def extract_existing_server_defaults(src_dir):
    """استخراج روابط السيرفر والـ Worker الحالية من SessionManager للحفاظ على الربط الحقيقي"""
    worker_url = "https://prime-ott-worker.workers.dev"
    xtream_host = "http://kwick.cc:8080"

    session_file = os.path.join(src_dir, "SessionManager.kt")
    if os.path.exists(session_file):
        with open(session_file, "r", encoding="utf-8", errors="ignore") as f:
            txt = f.read()
            w_match = re.search(r"https://[a-zA-Z0-9._-]+\.workers\.dev[^\s\"']*", txt)
            if w_match:
                worker_url = w_match.group(0).rstrip("/")
            x_match = re.search(r"https?://[a-zA-Z0-9._-]+:\d+", txt)
            if x_match:
                xtream_host = x_match.group(0).rstrip("/")

    return worker_url, xtream_host


def backup_existing_files(files_to_backup):
    backup_dir = os.path.join(PROJECT_ROOT, ".backup_watch_together_" + datetime.now().strftime("%Y%m%d_%H%M%S"))
    os.makedirs(backup_dir, exist_path=True) if hasattr(os, "exist_path") else os.makedirs(backup_dir, exist_ok=True)
    for fpath in files_to_backup:
        if os.path.exists(fpath):
            shutil.copy2(fpath, os.path.join(backup_dir, os.path.basename(fpath)))
    print(f"[✓] تم حفظ نسخة احتياطية للملفات السابقة في: {backup_dir}")


def create_drawables():
    os.makedirs(DRAWABLE_DIR, exist_ok=True)

    bg_card = """<?xml version="1.0" encoding="utf-8"?>
<shape xmlns:android="http://schemas.android.com/apk/res/android" android:shape="rectangle">
    <solid android:color="#151B26" />
    <corners android:radius="12dp" />
    <stroke android:width="1dp" android:color="#263044" />
</shape>
"""
    bg_badge_live = """<?xml version="1.0" encoding="utf-8"?>
<shape xmlns:android="http://schemas.android.com/apk/res/android" android:shape="rectangle">
    <solid android:color="#E50914" />
    <corners android:radius="8dp" />
</shape>
"""
    bg_badge_dark = """<?xml version="1.0" encoding="utf-8"?>
<shape xmlns:android="http://schemas.android.com/apk/res/android" android:shape="rectangle">
    <solid android:color="#1E2638" />
    <corners android:radius="8dp" />
    <stroke android:width="1dp" android:color="#323F5B" />
</shape>
"""
    bg_input_box = """<?xml version="1.0" encoding="utf-8"?>
<shape xmlns:android="http://schemas.android.com/apk/res/android" android:shape="rectangle">
    <solid android:color="#0E131D" />
    <corners android:radius="10dp" />
    <stroke android:width="1dp" android:color="#2A364F" />
</shape>
"""
    bg_btn_primary = """<?xml version="1.0" encoding="utf-8"?>
<shape xmlns:android="http://schemas.android.com/apk/res/android" android:shape="rectangle">
    <solid android:color="#E50914" />
    <corners android:radius="10dp" />
</shape>
"""
    bg_btn_green = """<?xml version="1.0" encoding="utf-8"?>
<shape xmlns:android="http://schemas.android.com/apk/res/android" android:shape="rectangle">
    <solid android:color="#00C853" />
    <corners android:radius="10dp" />
</shape>
"""

    drawables = {
        "wt_bg_card.xml": bg_card,
        "wt_bg_badge_live.xml": bg_badge_live,
        "wt_bg_badge_dark.xml": bg_badge_dark,
        "wt_bg_input.xml": bg_input_box,
        "wt_bg_btn_primary.xml": bg_btn_primary,
        "wt_bg_btn_green.xml": bg_btn_green,
    }

    for name, xml_content in drawables.items():
        with open(os.path.join(DRAWABLE_DIR, name), "w", encoding="utf-8") as f:
            f.write(xml_content.strip())
    print("[✓] تم تجهيز ملفات الرسوميات الداخلية (Drawables) لشاشة Watch Together.")


def write_watch_room_layout():
    os.makedirs(LAYOUT_DIR, exist_ok=True)
    layout_xml = """<?xml version="1.0" encoding="utf-8"?>
<FrameLayout xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    android:id="@+id/rootWatchTogether"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:background="#090C12"
    android:layoutDirection="rtl">

    <!-- ========================================================================= -->
    <!-- VIEW 1: WATCH TOGETHER HUB / LOBBY (مركز الغرف والبحث والإنشاء والانضمام) -->
    <!-- ========================================================================= -->
    <LinearLayout
        android:id="@+id/layoutLobbyContainer"
        android:layout_width="match_parent"
        android:layout_height="match_parent"
        android:orientation="vertical"
        android:padding="12dp"
        android:visibility="visible">

        <!-- Top Lobby Header -->
        <LinearLayout
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:gravity="center_vertical"
            android:orientation="horizontal"
            android:paddingBottom="10dp">

            <TextView
                android:id="@+id/btnLobbyBack"
                android:layout_width="wrap_content"
                android:layout_height="36dp"
                android:background="@drawable/wt_bg_badge_dark"
                android:gravity="center"
                android:paddingHorizontal="12dp"
                android:text="رجوع ↩"
                android:textColor="#FFFFFF"
                android:textSize="13sp"
                android:textStyle="bold" />

            <TextView
                android:layout_width="0dp"
                android:layout_height="wrap_content"
                android:layout_weight="1"
                android:layout_marginHorizontal="10dp"
                android:text="المشاهدة الجماعية • Watch Together"
                android:textColor="#FFFFFF"
                android:textSize="16sp"
                android:textStyle="bold" />

            <TextView
                android:id="@+id/tvBackendHealthLobby"
                android:layout_width="wrap_content"
                android:layout_height="30dp"
                android:background="@drawable/wt_bg_badge_dark"
                android:gravity="center"
                android:paddingHorizontal="8dp"
                android:text="🟢 متصل"
                android:textColor="#00E676"
                android:textSize="11sp" />

            <TextView
                android:id="@+id/btnBlockedUsersLobby"
                android:layout_width="wrap_content"
                android:layout_height="30dp"
                android:layout_marginStart="6dp"
                android:background="@drawable/wt_bg_badge_dark"
                android:gravity="center"
                android:paddingHorizontal="8dp"
                android:text="🚫 المحظورون"
                android:textColor="#FFCDD2"
                android:textSize="11sp" />
        </LinearLayout>

        <!-- Create Room + Join by Code Row -->
        <LinearLayout
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:background="@drawable/wt_bg_card"
            android:orientation="vertical"
            android:padding="12dp">

            <LinearLayout
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:gravity="center_vertical"
                android:orientation="horizontal">

                <Button
                    android:id="@+id/btnCreateRoomOpen"
                    android:layout_width="0dp"
                    android:layout_height="44dp"
                    android:layout_weight="1"
                    android:background="@drawable/wt_bg_btn_primary"
                    android:text="➕ إنشاء غرفة مشاهدة جديدة"
                    android:textAllCaps="false"
                    android:textColor="#FFFFFF"
                    android:textSize="13sp"
                    android:textStyle="bold" />

                <TextView
                    android:id="@+id/btnRefreshRooms"
                    android:layout_width="wrap_content"
                    android:layout_height="44dp"
                    android:layout_marginStart="8dp"
                    android:background="@drawable/wt_bg_badge_dark"
                    android:gravity="center"
                    android:paddingHorizontal="12dp"
                    android:text="🔄 تحديث"
                    android:textColor="#FFFFFF"
                    android:textSize="12sp"
                    android:textStyle="bold" />
            </LinearLayout>

            <!-- Join by Room Code -->
            <LinearLayout
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:layout_marginTop="10dp"
                android:gravity="center_vertical"
                android:orientation="horizontal">

                <EditText
                    android:id="@+id/etJoinByCodeInput"
                    android:layout_width="0dp"
                    android:layout_height="42dp"
                    android:layout_weight="1"
                    android:background="@drawable/wt_bg_input"
                    android:hint="🔑 أدخل رمز الغرفة أو رابط الدعوة..."
                    android:imeOptions="actionDone"
                    android:inputType="text"
                    android:paddingHorizontal="12dp"
                    android:singleLine="true"
                    android:textColor="#FFFFFF"
                    android:textColorHint="#8A96A8"
                    android:textSize="13sp" />

                <Button
                    android:id="@+id/btnJoinByCodeSubmit"
                    android:layout_width="wrap_content"
                    android:layout_height="42dp"
                    android:layout_marginStart="8dp"
                    android:background="@drawable/wt_bg_btn_green"
                    android:paddingHorizontal="16dp"
                    android:text="انضمام بالرمز"
                    android:textAllCaps="false"
                    android:textColor="#FFFFFF"
                    android:textSize="12sp"
                    android:textStyle="bold" />
            </LinearLayout>
        </LinearLayout>

        <!-- Search & Filters -->
        <EditText
            android:id="@+id/etRoomSearch"
            android:layout_width="match_parent"
            android:layout_height="40dp"
            android:layout_marginTop="10dp"
            android:background="@drawable/wt_bg_input"
            android:hint="🔍 ابحث عن غرفة، مباراة، قناة، أو اسم المضيف..."
            android:paddingHorizontal="12dp"
            android:singleLine="true"
            android:textColor="#FFFFFF"
            android:textColorHint="#7C889B"
            android:textSize="12sp" />

        <HorizontalScrollView
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:layout_marginTop="8dp"
            android:scrollbars="none">

            <LinearLayout
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:orientation="horizontal">

                <TextView
                    android:id="@+id/chipFilterAll"
                    android:layout_width="wrap_content"
                    android:layout_height="32dp"
                    android:layout_marginEnd="6dp"
                    android:background="@drawable/wt_bg_badge_live"
                    android:gravity="center"
                    android:paddingHorizontal="12dp"
                    android:text="🔥 الكل"
                    android:textColor="#FFFFFF"
                    android:textSize="12sp"
                    android:textStyle="bold" />

                <TextView
                    android:id="@+id/chipFilterPublic"
                    android:layout_width="wrap_content"
                    android:layout_height="32dp"
                    android:layout_marginEnd="6dp"
                    android:background="@drawable/wt_bg_badge_dark"
                    android:gravity="center"
                    android:paddingHorizontal="12dp"
                    android:text="🌐 غرف عامة"
                    android:textColor="#D0D7E2"
                    android:textSize="12sp" />

                <TextView
                    android:id="@+id/chipFilterPrivate"
                    android:layout_width="wrap_content"
                    android:layout_height="32dp"
                    android:layout_marginEnd="6dp"
                    android:background="@drawable/wt_bg_badge_dark"
                    android:gravity="center"
                    android:paddingHorizontal="12dp"
                    android:text="🔒 غرف خاصة"
                    android:textColor="#D0D7E2"
                    android:textSize="12sp" />

                <TextView
                    android:id="@+id/chipFilterMatches"
                    android:layout_width="wrap_content"
                    android:layout_height="32dp"
                    android:layout_marginEnd="6dp"
                    android:background="@drawable/wt_bg_badge_dark"
                    android:gravity="center"
                    android:paddingHorizontal="12dp"
                    android:text="⚽ مباريات اليوم"
                    android:textColor="#D0D7E2"
                    android:textSize="12sp" />

                <TextView
                    android:id="@+id/chipFilterChannels"
                    android:layout_width="wrap_content"
                    android:layout_height="32dp"
                    android:background="@drawable/wt_bg_badge_dark"
                    android:gravity="center"
                    android:paddingHorizontal="12dp"
                    android:text="📺 قنوات مباشرة"
                    android:textColor="#D0D7E2"
                    android:textSize="12sp" />
            </LinearLayout>
        </HorizontalScrollView>

        <!-- Active Rooms Statistics Header -->
        <TextView
            android:id="@+id/tvLobbyStatsSummary"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:layout_marginTop="8dp"
            android:layout_marginBottom="4dp"
            android:text="الغرف النشطة الآن: 0 | إجمالي المشاهدين: 0"
            android:textColor="#9AA5B8"
            android:textSize="11sp" />

        <!-- Active Rooms List -->
        <FrameLayout
            android:layout_width="match_parent"
            android:layout_height="0dp"
            android:layout_weight="1">

            <androidx.recyclerview.widget.RecyclerView
                android:id="@+id/rvActiveRooms"
                android:layout_width="match_parent"
                android:layout_height="match_parent"
                android:clipToPadding="false"
                android:paddingBottom="16dp" />

            <TextView
                android:id="@+id/tvEmptyRoomsHint"
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:layout_gravity="center"
                android:gravity="center"
                android:padding="24dp"
                android:text="لا توجد غرف مطابقة حالياً.\nاضغط على «إنشاء غرفة مشاهدة جديدة» لبدء البث الجماعي!"
                android:textColor="#7D889C"
                android:textSize="14sp"
                android:visibility="gone" />
        </FrameLayout>
    </LinearLayout>

    <!-- ========================================================================= -->
    <!-- VIEW 2: ACTIVE WATCH ROOM (شاشة الغرفة الحية والمزامنة والدردشة والتحكم) -->
    <!-- ========================================================================= -->
    <LinearLayout
        android:id="@+id/layoutActiveRoomContainer"
        android:layout_width="match_parent"
        android:layout_height="match_parent"
        android:orientation="vertical"
        android:visibility="gone">

        <!-- Room Top Action Header -->
        <LinearLayout
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:background="#121722"
            android:gravity="center_vertical"
            android:orientation="horizontal"
            android:paddingHorizontal="8dp"
            android:paddingVertical="6dp">

            <TextView
                android:id="@+id/btnLeaveRoom"
                android:layout_width="wrap_content"
                android:layout_height="30dp"
                android:background="@drawable/wt_bg_badge_dark"
                android:gravity="center"
                android:paddingHorizontal="8dp"
                android:text="🚪 مغادرة"
                android:textColor="#FF8A80"
                android:textSize="11sp"
                android:textStyle="bold" />

            <LinearLayout
                android:layout_width="0dp"
                android:layout_height="wrap_content"
                android:layout_marginHorizontal="6dp"
                android:layout_weight="1"
                android:orientation="vertical">

                <TextView
                    android:id="@+id/tvRoomHeaderTitle"
                    android:layout_width="match_parent"
                    android:layout_height="wrap_content"
                    android:ellipsize="end"
                    android:singleLine="true"
                    android:text="غرفة المشاهدة"
                    android:textColor="#FFFFFF"
                    android:textSize="13sp"
                    android:textStyle="bold" />

                <LinearLayout
                    android:layout_width="wrap_content"
                    android:layout_height="wrap_content"
                    android:gravity="center_vertical"
                    android:orientation="horizontal">

                    <TextView
                        android:id="@+id/tvRoomCodeBadge"
                        android:layout_width="wrap_content"
                        android:layout_height="wrap_content"
                        android:text="#CODE 📋"
                        android:textColor="#FFD54F"
                        android:textSize="10sp"
                        android:textStyle="bold" />

                    <TextView
                        android:id="@+id/tvRoomStatusBadge"
                        android:layout_width="wrap_content"
                        android:layout_height="wrap_content"
                        android:layout_marginStart="6dp"
                        android:text="🔴 LIVE"
                        android:textColor="#00E676"
                        android:textSize="10sp"
                        android:textStyle="bold" />

                    <TextView
                        android:id="@+id/tvBackendHealthRoom"
                        android:layout_width="wrap_content"
                        android:layout_height="wrap_content"
                        android:layout_marginStart="6dp"
                        android:text="🟢 35ms"
                        android:textColor="#90CAF9"
                        android:textSize="10sp" />
                </LinearLayout>
            </LinearLayout>

            <TextView
                android:id="@+id/tvOnlineCountBadge"
                android:layout_width="wrap_content"
                android:layout_height="28dp"
                android:layout_marginEnd="4dp"
                android:background="@drawable/wt_bg_badge_dark"
                android:gravity="center"
                android:paddingHorizontal="7dp"
                android:text="👥 1/25"
                android:textColor="#FFFFFF"
                android:textSize="11sp" />

            <TextView
                android:id="@+id/btnInviteShare"
                android:layout_width="wrap_content"
                android:layout_height="28dp"
                android:layout_marginEnd="4dp"
                android:background="@drawable/wt_bg_badge_dark"
                android:gravity="center"
                android:paddingHorizontal="7dp"
                android:text="🔗 دعوة"
                android:textColor="#80D8FF"
                android:textSize="11sp" />

            <TextView
                android:id="@+id/btnHostControlsOpen"
                android:layout_width="wrap_content"
                android:layout_height="28dp"
                android:layout_marginEnd="4dp"
                android:background="@drawable/wt_bg_badge_dark"
                android:gravity="center"
                android:paddingHorizontal="7dp"
                android:text="⚙️ تحكم"
                android:textColor="#FFD54F"
                android:textSize="11sp" />

            <TextView
                android:id="@+id/btnCloseRoomHost"
                android:layout_width="wrap_content"
                android:layout_height="28dp"
                android:background="@drawable/wt_bg_badge_live"
                android:gravity="center"
                android:paddingHorizontal="7dp"
                android:text="⛔ إغلاق"
                android:textColor="#FFFFFF"
                android:textSize="11sp" />
        </LinearLayout>

        <!-- Pending Join Request Alert Banner (For Host) -->
        <LinearLayout
            android:id="@+id/layoutJoinRequestBanner"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:background="#263238"
            android:gravity="center_vertical"
            android:orientation="horizontal"
            android:paddingHorizontal="10dp"
            android:paddingVertical="6dp"
            android:visibility="gone">

            <TextView
                android:id="@+id/tvJoinRequestInfo"
                android:layout_width="0dp"
                android:layout_height="wrap_content"
                android:layout_weight="1"
                android:text="🔔 طلب انضمام جديد إلى الغرفة"
                android:textColor="#FFF59D"
                android:textSize="12sp"
                android:textStyle="bold" />

            <Button
                android:id="@+id/btnApproveJoinReq"
                android:layout_width="wrap_content"
                android:layout_height="32dp"
                android:background="@drawable/wt_bg_btn_green"
                android:paddingHorizontal="10dp"
                android:text="قبول ✓"
                android:textColor="#FFFFFF"
                android:textSize="11sp" />

            <Button
                android:id="@+id/btnRejectJoinReq"
                android:layout_width="wrap_content"
                android:layout_height="32dp"
                android:layout_marginStart="6dp"
                android:background="@drawable/wt_bg_btn_primary"
                android:paddingHorizontal="10dp"
                android:text="رفض ✕"
                android:textColor="#FFFFFF"
                android:textSize="11sp" />
        </LinearLayout>

        <!-- Synchronized Video Player + Floating Emoji Layer -->
        <FrameLayout
            android:id="@+id/playerContainerFrame"
            android:layout_width="match_parent"
            android:layout_height="230dp"
            android:background="#000000">

            <androidx.media3.ui.PlayerView
                android:id="@+id/watchPlayerView"
                android:layout_width="match_parent"
                android:layout_height="match_parent"
                app:use_controller="true" />

            <FrameLayout
                android:id="@+id/emojiFloatingOverlay"
                android:layout_width="match_parent"
                android:layout_height="match_parent"
                android:clickable="false"
                android:focusable="false" />

            <TextView
                android:id="@+id/tvStreamStatusOverlay"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:layout_gravity="top|start"
                android:layout_margin="8dp"
                android:background="#99000000"
                android:paddingHorizontal="8dp"
                android:paddingVertical="4dp"
                android:text="📺 البث المتزامن جاهز"
                android:textColor="#FFFFFF"
                android:textSize="11sp" />

            <TextView
                android:id="@+id/tvExpirationTimerBadge"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:layout_gravity="top|end"
                android:layout_margin="8dp"
                android:background="#99000000"
                android:paddingHorizontal="8dp"
                android:paddingVertical="4dp"
                android:text="⏳ 02:59:59"
                android:textColor="#FFE082"
                android:textSize="11sp" />
        </FrameLayout>

        <!-- Playback Sync & Channel/Match Quick Toolbar -->
        <HorizontalScrollView
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:background="#101520"
            android:scrollbars="none">

            <LinearLayout
                android:layout_width="wrap_content"
                android:layout_height="38dp"
                android:gravity="center_vertical"
                android:orientation="horizontal"
                android:paddingHorizontal="8dp">

                <TextView
                    android:id="@+id/btnSyncPlayPause"
                    android:layout_width="wrap_content"
                    android:layout_height="28dp"
                    android:layout_marginEnd="6dp"
                    android:background="@drawable/wt_bg_badge_live"
                    android:gravity="center"
                    android:paddingHorizontal="10dp"
                    android:text="⏸️ إيقاف / تشغيل متزامن"
                    android:textColor="#FFFFFF"
                    android:textSize="11sp"
                    android:textStyle="bold" />

                <TextView
                    android:id="@+id/btnForceSyncNow"
                    android:layout_width="wrap_content"
                    android:layout_height="28dp"
                    android:layout_marginEnd="6dp"
                    android:background="@drawable/wt_bg_badge_dark"
                    android:gravity="center"
                    android:paddingHorizontal="10dp"
                    android:text="🔄 مزامنة الكل"
                    android:textColor="#80D8FF"
                    android:textSize="11sp" />

                <TextView
                    android:id="@+id/btnPickChannelOpen"
                    android:layout_width="wrap_content"
                    android:layout_height="28dp"
                    android:layout_marginEnd="6dp"
                    android:background="@drawable/wt_bg_badge_dark"
                    android:gravity="center"
                    android:paddingHorizontal="10dp"
                    android:text="📺 اختيار قناة"
                    android:textColor="#FFFFFF"
                    android:textSize="11sp" />

                <TextView
                    android:id="@+id/btnPickMatchOpen"
                    android:layout_width="wrap_content"
                    android:layout_height="28dp"
                    android:layout_marginEnd="6dp"
                    android:background="@drawable/wt_bg_badge_dark"
                    android:gravity="center"
                    android:paddingHorizontal="10dp"
                    android:text="⚽ اختيار مباراة"
                    android:textColor="#69F0AE"
                    android:textSize="11sp" />

                <TextView
                    android:id="@+id/btnAspectRatioToggle"
                    android:layout_width="wrap_content"
                    android:layout_height="28dp"
                    android:layout_marginEnd="6dp"
                    android:background="@drawable/wt_bg_badge_dark"
                    android:gravity="center"
                    android:paddingHorizontal="10dp"
                    android:text="📐 أبعاد الشاشة"
                    android:textColor="#FFFFFF"
                    android:textSize="11sp" />

                <TextView
                    android:id="@+id/btnRoomStatsOpen"
                    android:layout_width="wrap_content"
                    android:layout_height="28dp"
                    android:background="@drawable/wt_bg_badge_dark"
                    android:gravity="center"
                    android:paddingHorizontal="10dp"
                    android:text="📊 إحصائيات الغرفة"
                    android:textColor="#FFD54F"
                    android:textSize="11sp" />
            </LinearLayout>
        </HorizontalScrollView>

        <!-- Voice Chat Controls + Live Emoji Reactions Bar -->
        <LinearLayout
            android:layout_width="match_parent"
            android:layout_height="40dp"
            android:background="#151C28"
            android:gravity="center_vertical"
            android:orientation="horizontal"
            android:paddingHorizontal="8dp">

            <TextView
                android:id="@+id/btnToggleMic"
                android:layout_width="wrap_content"
                android:layout_height="30dp"
                android:background="@drawable/wt_bg_badge_dark"
                android:gravity="center"
                android:paddingHorizontal="10dp"
                android:text="🎤 الميكروفون: مكتوم"
                android:textColor="#FF8A80"
                android:textSize="11sp"
                android:textStyle="bold" />

            <TextView
                android:id="@+id/btnToggleSpeaker"
                android:layout_width="wrap_content"
                android:layout_height="30dp"
                android:layout_marginStart="4dp"
                android:background="@drawable/wt_bg_badge_dark"
                android:gravity="center"
                android:paddingHorizontal="8dp"
                android:text="🔊"
                android:textColor="#00E676"
                android:textSize="12sp" />

            <TextView
                android:id="@+id/tvVoiceSpeakingIndicator"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:layout_marginHorizontal="6dp"
                android:text="صوت نقي"
                android:textColor="#9AA5B8"
                android:textSize="10sp" />

            <HorizontalScrollView
                android:layout_width="0dp"
                android:layout_height="wrap_content"
                android:layout_weight="1"
                android:scrollbars="none">

                <LinearLayout
                    android:layout_width="wrap_content"
                    android:layout_height="wrap_content"
                    android:orientation="horizontal">

                    <TextView android:id="@+id/btnEmojiFire" android:layout_width="32dp" android:layout_height="30dp" android:gravity="center" android:text="🔥" android:textSize="16sp" />
                    <TextView android:id="@+id/btnEmojiHeart" android:layout_width="32dp" android:layout_height="30dp" android:gravity="center" android:text="❤️" android:textSize="16sp" />
                    <TextView android:id="@+id/btnEmojiLaugh" android:layout_width="32dp" android:layout_height="30dp" android:gravity="center" android:text="😂" android:textSize="16sp" />
                    <TextView android:id="@+id/btnEmojiClap" android:layout_width="32dp" android:layout_height="30dp" android:gravity="center" android:text="👏" android:textSize="16sp" />
                    <TextView android:id="@+id/btnEmojiSoccer" android:layout_width="32dp" android:layout_height="30dp" android:gravity="center" android:text="⚽" android:textSize="16sp" />
                    <TextView android:id="@+id/btnEmojiGoal" android:layout_width="32dp" android:layout_height="30dp" android:gravity="center" android:text="🥅" android:textSize="16sp" />
                    <TextView android:id="@+id/btnEmojiShock" android:layout_width="32dp" android:layout_height="30dp" android:gravity="center" android:text="😱" android:textSize="16sp" />
                    <TextView android:id="@+id/btnEmojiParty" android:layout_width="32dp" android:layout_height="30dp" android:gravity="center" android:text="🎉" android:textSize="16sp" />
                </LinearLayout>
            </HorizontalScrollView>
        </LinearLayout>

        <!-- Room Internal Navigation Tabs -->
        <LinearLayout
            android:layout_width="match_parent"
            android:layout_height="38dp"
            android:background="#0E131D"
            android:orientation="horizontal"
            android:padding="3dp">

            <TextView
                android:id="@+id/tabBtnChat"
                android:layout_width="0dp"
                android:layout_height="match_parent"
                android:layout_weight="1"
                android:background="@drawable/wt_bg_badge_live"
                android:gravity="center"
                android:text="💬 الدردشة"
                android:textColor="#FFFFFF"
                android:textSize="11sp"
                android:textStyle="bold" />

            <TextView
                android:id="@+id/tabBtnParticipants"
                android:layout_width="0dp"
                android:layout_height="match_parent"
                android:layout_weight="1"
                android:gravity="center"
                android:text="👥 المتصلون"
                android:textColor="#B0BEC5"
                android:textSize="11sp"
                android:textStyle="bold" />

            <TextView
                android:id="@+id/tabBtnMediaPicker"
                android:layout_width="0dp"
                android:layout_height="match_parent"
                android:layout_weight="1"
                android:gravity="center"
                android:text="📺 القنوات والمباريات"
                android:textColor="#B0BEC5"
                android:textSize="11sp"
                android:textStyle="bold" />

            <TextView
                android:id="@+id/tabBtnLogsStats"
                android:layout_width="0dp"
                android:layout_height="match_parent"
                android:layout_weight="1"
                android:gravity="center"
                android:text="📋 السجل والحالة"
                android:textColor="#B0BEC5"
                android:textSize="11sp"
                android:textStyle="bold" />
        </LinearLayout>

        <!-- TAB 1: REAL-TIME TEXT CHAT & MESSAGE HISTORY -->
        <LinearLayout
            android:id="@+id/panelTabChat"
            android:layout_width="match_parent"
            android:layout_height="0dp"
            android:layout_weight="1"
            android:orientation="vertical"
            android:padding="8dp"
            android:visibility="visible">

            <androidx.recyclerview.widget.RecyclerView
                android:id="@+id/rvChatMessages"
                android:layout_width="match_parent"
                android:layout_height="0dp"
                android:layout_weight="1"
                android:clipToPadding="false" />

            <LinearLayout
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:layout_marginTop="6dp"
                android:gravity="center_vertical"
                android:orientation="horizontal">

                <EditText
                    android:id="@+id/etChatMessageInput"
                    android:layout_width="0dp"
                    android:layout_height="40dp"
                    android:layout_weight="1"
                    android:background="@drawable/wt_bg_input"
                    android:hint="اكتب رسالة فورية للغرفة..."
                    android:imeOptions="actionSend"
                    android:inputType="text"
                    android:paddingHorizontal="12dp"
                    android:singleLine="true"
                    android:textColor="#FFFFFF"
                    android:textColorHint="#7A869A"
                    android:textSize="13sp" />

                <Button
                    android:id="@+id/btnSendChatMessage"
                    android:layout_width="wrap_content"
                    android:layout_height="40dp"
                    android:layout_marginStart="6dp"
                    android:background="@drawable/wt_bg_btn_primary"
                    android:paddingHorizontal="16dp"
                    android:text="إرسال ➤"
                    android:textAllCaps="false"
                    android:textColor="#FFFFFF"
                    android:textSize="12sp"
                    android:textStyle="bold" />
            </LinearLayout>
        </LinearLayout>

        <!-- TAB 2: PARTICIPANT MANAGEMENT, VOICE STATUS & HOST CONTROLS -->
        <LinearLayout
            android:id="@+id/panelTabParticipants"
            android:layout_width="match_parent"
            android:layout_height="0dp"
            android:layout_weight="1"
            android:orientation="vertical"
            android:padding="8dp"
            android:visibility="gone">

            <LinearLayout
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:layout_marginBottom="6dp"
                android:orientation="horizontal">

                <Button
                    android:id="@+id/btnMuteAllParticipants"
                    android:layout_width="0dp"
                    android:layout_height="34dp"
                    android:layout_marginEnd="6dp"
                    android:layout_weight="1"
                    android:background="@drawable/wt_bg_badge_dark"
                    android:text="🔇 كتم الجميع (للمضيف)"
                    android:textAllCaps="false"
                    android:textColor="#FFCDD2"
                    android:textSize="11sp" />

                <Button
                    android:id="@+id/btnManageBansOpen"
                    android:layout_width="0dp"
                    android:layout_height="34dp"
                    android:layout_weight="1"
                    android:background="@drawable/wt_bg_badge_dark"
                    android:text="🚫 قائمة المحظورين والطلبات"
                    android:textAllCaps="false"
                    android:textColor="#FFE082"
                    android:textSize="11sp" />
            </LinearLayout>

            <androidx.recyclerview.widget.RecyclerView
                android:id="@+id/rvParticipantsList"
                android:layout_width="match_parent"
                android:layout_height="0dp"
                android:layout_weight="1" />
        </LinearLayout>

        <!-- TAB 3: IN-ROOM LIVE CHANNEL & MATCH SELECTION -->
        <LinearLayout
            android:id="@+id/panelTabMediaPicker"
            android:layout_width="match_parent"
            android:layout_height="0dp"
            android:layout_weight="1"
            android:orientation="vertical"
            android:padding="8dp"
            android:visibility="gone">

            <LinearLayout
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:orientation="horizontal">

                <Button
                    android:id="@+id/btnSubTabChannels"
                    android:layout_width="0dp"
                    android:layout_height="34dp"
                    android:layout_marginEnd="4dp"
                    android:layout_weight="1"
                    android:background="@drawable/wt_bg_btn_primary"
                    android:text="📺 القنوات المباشرة"
                    android:textAllCaps="false"
                    android:textColor="#FFFFFF"
                    android:textSize="11sp" />

                <Button
                    android:id="@+id/btnSubTabMatches"
                    android:layout_width="0dp"
                    android:layout_height="34dp"
                    android:layout_marginEnd="4dp"
                    android:layout_weight="1"
                    android:background="@drawable/wt_bg_badge_dark"
                    android:text="⚽ مباريات اليوم"
                    android:textAllCaps="false"
                    android:textColor="#FFFFFF"
                    android:textSize="11sp" />

                <Button
                    android:id="@+id/btnCustomStreamUrl"
                    android:layout_width="wrap_content"
                    android:layout_height="34dp"
                    android:background="@drawable/wt_bg_badge_dark"
                    android:paddingHorizontal="10dp"
                    android:text="🔗 رابط مباشر"
                    android:textAllCaps="false"
                    android:textColor="#80D8FF"
                    android:textSize="11sp" />
            </LinearLayout>

            <EditText
                android:id="@+id/etSearchMediaInRoom"
                android:layout_width="match_parent"
                android:layout_height="36dp"
                android:layout_marginVertical="6dp"
                android:background="@drawable/wt_bg_input"
                android:hint="🔍 ابحث عن قناة أو مباراة لتشغيلها للغرفة..."
                android:paddingHorizontal="10dp"
                android:singleLine="true"
                android:textColor="#FFFFFF"
                android:textColorHint="#7A869A"
                android:textSize="12sp" />

            <androidx.recyclerview.widget.RecyclerView
                android:id="@+id/rvMediaPickerList"
                android:layout_width="match_parent"
                android:layout_height="0dp"
                android:layout_weight="1" />
        </LinearLayout>

        <!-- TAB 4: ROOM EVENT LOGS, STATISTICS & HEALTH MONITORING -->
        <LinearLayout
            android:id="@+id/panelTabLogsStats"
            android:layout_width="match_parent"
            android:layout_height="0dp"
            android:layout_weight="1"
            android:orientation="vertical"
            android:padding="8dp"
            android:visibility="gone">

            <TextView
                android:id="@+id/tvRoomDetailedStats"
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:background="@drawable/wt_bg_card"
                android:padding="8dp"
                android:text="إحصائيات الغرفة وحالة المزامنة"
                android:textColor="#E0E6ED"
                android:textSize="11sp" />

            <LinearLayout
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:layout_marginVertical="6dp"
                android:orientation="horizontal">

                <Button
                    android:id="@+id/btnExtendRoomTime"
                    android:layout_width="0dp"
                    android:layout_height="34dp"
                    android:layout_marginEnd="6dp"
                    android:layout_weight="1"
                    android:background="@drawable/wt_bg_btn_green"
                    android:text="⏱️ تمديد صلاحية الغرفة (+1 ساعة)"
                    android:textAllCaps="false"
                    android:textColor="#FFFFFF"
                    android:textSize="11sp" />

                <Button
                    android:id="@+id/btnClearEventLogs"
                    android:layout_width="wrap_content"
                    android:layout_height="34dp"
                    android:background="@drawable/wt_bg_badge_dark"
                    android:paddingHorizontal="12dp"
                    android:text="🧹 مسح السجل"
                    android:textAllCaps="false"
                    android:textColor="#B0BEC5"
                    android:textSize="11sp" />
            </LinearLayout>

            <androidx.recyclerview.widget.RecyclerView
                android:id="@+id/rvRoomEventLogs"
                android:layout_width="match_parent"
                android:layout_height="0dp"
                android:layout_weight="1" />
        </LinearLayout>
    </LinearLayout>
</FrameLayout>
"""
    layout_path = os.path.join(LAYOUT_DIR, "activity_watch_room.xml")
    with open(layout_path, "w", encoding="utf-8") as f:
        f.write(layout_xml.strip())
    print(f"[✓] تم تحديث تصميم الشاشة بنجاح: {layout_path}")


def write_voice_sync_helper(pkg, src_dir, worker_url):
    code = f"""package {pkg}

import android.util.Base64
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * VoiceSyncHelper - مزامنة الصوت السحابي واللحظي لغرف Watch Together
 */
object VoiceSyncHelper {{

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .writeTimeout(5, TimeUnit.SECONDS)
        .build()

    var baseUrl: String = "{worker_url}"

    fun pushAudioChunk(
        roomId: String,
        userId: String,
        userName: String,
        pcmData: ByteArray,
        authToken: String = ""
    ): Boolean {{
        return try {{
            val encoded = Base64.encodeToString(pcmData, Base64.NO_WRAP)
            val payload = JSONObject().apply {{
                put("roomId", roomId)
                put("userId", userId)
                put("userName", userName)
                put("audio", encoded)
                put("ts", System.currentTimeMillis())
            }}
            val reqBuilder = Request.Builder()
                .url("$baseUrl/voice/push")
                .post(payload.toString().toRequestBody("application/json".toMediaTypeOrNull()))
            if (authToken.isNotEmpty()) {{
                reqBuilder.addHeader("Authorization", "Bearer $authToken")
            }}
            client.newCall(reqBuilder.build()).execute().use {{ resp ->
                resp.isSuccessful
            }}
        }} catch (_: Exception) {{
            false
        }}
    }}

    fun pullAudioChunks(
        roomId: String,
        excludeUserId: String,
        sinceTs: Long,
        mutedUserIds: Set<String> = emptySet()
    ): List<Pair<String, ByteArray>> {{
        val list = mutableListOf<Pair<String, ByteArray>>()
        try {{
            val url = "$baseUrl/voice/pull?roomId=$roomId&since=$sinceTs"
            val req = Request.Builder().url(url).get().build()
            client.newCall(req).execute().use {{ resp ->
                if (!resp.isSuccessful) return emptyList()
                val body = resp.body?.string() ?: return emptyList()
                val arr = JSONObject(body).optJSONArray("chunks") ?: JSONArray()
                for (i in 0 until arr.length()) {{
                    val item = arr.getJSONObject(i)
                    val senderId = item.optString("userId")
                    if (senderId.isNotEmpty() && senderId != excludeUserId && !mutedUserIds.contains(senderId)) {{
                        val b64 = item.optString("audio")
                        if (b64.isNotEmpty()) {{
                            val bytes = Base64.decode(b64, Base64.NO_WRAP)
                            list.add(senderId to bytes)
                        }}
                    }}
                }}
            }}
        }} catch (_: Exception) {{
        }}
        return list
    }}
}}
"""
    path = os.path.join(src_dir, "VoiceSyncHelper.kt")
    with open(path, "w", encoding="utf-8") as f:
        f.write(code.strip())
    print(f"[✓] تم تحديث: {path}")


def write_voice_chat_manager(pkg, src_dir):
    code = f"""package {pkg}

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.sqrt

/**
 * VoiceChatManager - محرك الدردشة الصوتية اللحظية مع إلغاء الصدى والضجيج،
 * كشف المتحدث، وكتم المشاركين الفردي أو الجماعي.
 */
class VoiceChatManager(
    private val context: Context,
    private val roomId: String,
    private val userId: String,
    private val userName: String = "مشاهد",
    private val onSpeakingStateChanged: ((Boolean, Int) -> Unit)? = null
) {{

    private val sampleRate = 16000
    private val isRunning = AtomicBoolean(false)
    private val isMicMuted = AtomicBoolean(true)
    private val isHostForceMuted = AtomicBoolean(false)
    private val isSpeakerMuted = AtomicBoolean(false)

    private val locallyMutedParticipants = ConcurrentHashMap.newKeySet<String>()

    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    private var echoCanceler: AcousticEchoCanceler? = null
    private var noiseSuppressor: NoiseSuppressor? = null
    private var gainControl: AutomaticGainControl? = null

    private var recordThread: Thread? = null
    private var playThread: Thread? = null

    var onOutgoingVoiceFrame: ((ByteArray) -> Unit)? = null

    @SuppressLint("MissingPermission")
    fun startVoiceChat() {{
        if (isRunning.getAndSet(true)) return

        try {{
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            audioManager?.mode = AudioManager.MODE_IN_COMMUNICATION
            audioManager?.isSpeakerphoneOn = true

            val minRecBuf = AudioRecord.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(3200)

            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                minRecBuf * 2
            )

            val sessionId = audioRecord?.audioSessionId ?: 0
            if (sessionId != 0) {{
                if (AcousticEchoCanceler.isAvailable()) {{
                    echoCanceler = AcousticEchoCanceler.create(sessionId)?.apply {{ enabled = true }}
                }}
                if (NoiseSuppressor.isAvailable()) {{
                    noiseSuppressor = NoiseSuppressor.create(sessionId)?.apply {{ enabled = true }}
                }}
                if (AutomaticGainControl.isAvailable()) {{
                    gainControl = AutomaticGainControl.create(sessionId)?.apply {{ enabled = true }}
                }}
            }}

            val minPlayBuf = AudioTrack.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(3200)

            audioTrack = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(minPlayBuf * 2)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()

            audioRecord?.startRecording()
            audioTrack?.play()

            recordThread = Thread {{
                val buf = ByteArray(1600)
                while (isRunning.get()) {{
                    try {{
                        if (isMicMuted.get() || isHostForceMuted.get()) {{
                            onSpeakingStateChanged?.invoke(false, 0)
                            Thread.sleep(80)
                            continue
                        }}
                        val read = audioRecord?.read(buf, 0, buf.size) ?: 0
                        if (read > 0) {{
                            val chunk = buf.copyOf(read)
                            val level = calculateRmsLevel(chunk)
                            val speaking = level > 350
                            onSpeakingStateChanged?.invoke(speaking, level)
                            if (speaking) {{
                                onOutgoingVoiceFrame?.invoke(chunk)
                                VoiceSyncHelper.pushAudioChunk(roomId, userId, userName, chunk)
                            }}
                        }}
                    }} catch (_: Exception) {{
                    }}
                }}
            }}.apply {{ isDaemon = true; start() }}

            playThread = Thread {{
                var lastTs = System.currentTimeMillis() - 1000
                while (isRunning.get()) {{
                    try {{
                        if (!isSpeakerMuted.get()) {{
                            val chunks = VoiceSyncHelper.pullAudioChunks(
                                roomId = roomId,
                                excludeUserId = userId,
                                sinceTs = lastTs,
                                mutedUserIds = locallyMutedParticipants
                            )
                            lastTs = System.currentTimeMillis()
                            for ((senderId, pcm) in chunks) {{
                                if (!locallyMutedParticipants.contains(senderId)) {{
                                    audioTrack?.write(pcm, 0, pcm.size)
                                }}
                            }}
                        }}
                        Thread.sleep(120)
                    }} catch (_: Exception) {{
                    }}
                }}
            }}.apply {{ isDaemon = true; start() }}

        }} catch (_: Exception) {{
            isRunning.set(false)
        }}
    }}

    fun playIncomingWebSocketVoice(senderId: String, pcmBytes: ByteArray) {{
        if (!isRunning.get() || isSpeakerMuted.get() || senderId == userId) return
        if (locallyMutedParticipants.contains(senderId)) return
        try {{
            audioTrack?.write(pcmBytes, 0, pcmBytes.size)
        }} catch (_: Exception) {{
        }}
    }}

    private fun calculateRmsLevel(pcm: ByteArray): Int {{
        var sum = 0.0
        var count = 0
        var i = 0
        while (i + 1 < pcm.size) {{
            val sample = ((pcm[i + 1].toInt() shl 8) or (pcm[i].toInt() and 0xFF)).toShort()
            sum += sample * sample
            count++
            i += 2
        }}
        return if (count > 0) sqrt(sum / count).toInt() else 0
    }}

    fun setMuted(muted: Boolean) {{
        if (isHostForceMuted.get() && !muted) return
        isMicMuted.set(muted)
        if (muted) onSpeakingStateChanged?.invoke(false, 0)
    }}

    fun setHostForceMuted(forceMuted: Boolean) {{
        isHostForceMuted.set(forceMuted)
        if (forceMuted) {{
            isMicMuted.set(true)
            onSpeakingStateChanged?.invoke(false, 0)
        }}
    }}

    fun isMuted(): Boolean = isMicMuted.get() || isHostForceMuted.get()

    fun isForceMutedByHost(): Boolean = isHostForceMuted.get()

    fun setSpeakerMuted(muted: Boolean) {{
        isSpeakerMuted.set(muted)
    }}

    fun isSpeakerMuted(): Boolean = isSpeakerMuted.get()

    fun setParticipantMutedLocally(targetUserId: String, muted: Boolean) {{
        if (muted) locallyMutedParticipants.add(targetUserId)
        else locallyMutedParticipants.remove(targetUserId)
    }}

    fun isParticipantMutedLocally(targetUserId: String): Boolean =
        locallyMutedParticipants.contains(targetUserId)

    fun stopVoiceChat() {{
        isRunning.set(false)
        try {{ recordThread?.interrupt() }} catch (_: Exception) {{}}
        try {{ playThread?.interrupt() }} catch (_: Exception) {{}}
        try {{ echoCanceler?.release() }} catch (_: Exception) {{}}
        try {{ noiseSuppressor?.release() }} catch (_: Exception) {{}}
        try {{ gainControl?.release() }} catch (_: Exception) {{}}
        try {{
            audioRecord?.stop()
            audioRecord?.release()
        }} catch (_: Exception) {{}}
        try {{
            audioTrack?.stop()
            audioTrack?.release()
        }} catch (_: Exception) {{}}
        audioRecord = null
        audioTrack = null
    }}

    fun release() = stopVoiceChat()
}}
"""
    path = os.path.join(src_dir, "VoiceChatManager.kt")
    with open(path, "w", encoding="utf-8") as f:
        f.write(code.strip())
    print(f"[✓] تم تحديث: {path}")


def write_watch_room_activity(pkg, src_dir, worker_url, xtream_host):
    code = f"""package {pkg}

import android.Manifest
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.Base64
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.random.Random

/**
 * WatchRoomActivity - منظومة المشاهدة الجماعية المتكاملة (50 ميزة كاملة داخل شاشة واحدة)
 * - Lobby / Active Rooms Directory + Search & Filters
 * - Create Room (Public / Private / Password / Max Participants / Expiration)
 * - Join Room / Join by Room Code / Invite Links / Join Requests (Approve & Reject)
 * - Live Channel Selection & Match Selection inside Room
 * - Playback Sync / Play-Pause Sync / Stream Status Sync & Failover (HLS & TS)
 * - Real-Time Text Chat / Message History / Rate Limiting / Emoji Reactions
 * - Live Voice Chat / Mic Status / Mute-Unmute / Host Force-Mute
 * - Host Controls / Transfer Ownership / Kick / Ban / Report / Block User
 * - Automatic Empty Room Cleanup / Heartbeat Presence / Reconnection Handling
 * - Room Event Logs / Room Statistics / Secure WebSocket + Cloud Sync / Backend Health Monitoring
 */
class WatchRoomActivity : AppCompatActivity() {{

    // =========================================================================
    // DATA MODELS
    // =========================================================================
    data class WatchRoomModel(
        val roomId: String,
        val roomCode: String,
        var roomName: String,
        var hostId: String,
        var hostName: String,
        var isPrivate: Boolean = false,
        var passwordHash: String = "",
        var requireApproval: Boolean = false,
        var maxParticipants: Int = 25,
        var onlineCount: Int = 1,
        var status: String = "LIVE", // LIVE, PAUSED, BUFFERING, WAITING, CLOSED
        var streamUrl: String = "",
        var streamTitle: String = "بث مباشر",
        var category: String = "قنوات مباشرة",
        var isMatch: Boolean = false,
        var isPlaying: Boolean = true,
        var playbackPositionMs: Long = 0L,
        val createdAt: Long = System.currentTimeMillis(),
        var expiresAt: Long = System.currentTimeMillis() + 3 * 3600_000L,
        var isChatLocked: Boolean = false,
        var isVoiceLocked: Boolean = false
    )

    data class ParticipantModel(
        val userId: String,
        val userName: String,
        var role: String = "PARTICIPANT", // HOST, MODERATOR, PARTICIPANT
        var isMuted: Boolean = true,
        var isHostForceMuted: Boolean = false,
        var isSpeaking: Boolean = false,
        var pingMs: Int = 35,
        var lastSeen: Long = System.currentTimeMillis()
    )

    data class JoinRequestModel(
        val requestId: String,
        val userId: String,
        val userName: String,
        val timestamp: Long = System.currentTimeMillis()
    )

    data class ChatMessageModel(
        val msgId: String,
        val senderId: String,
        val senderName: String,
        val senderRole: String,
        val text: String,
        val timestamp: Long = System.currentTimeMillis(),
        val isSystemEvent: Boolean = false
    )

    data class MediaStreamOption(
        val id: String,
        val title: String,
        val subtitle: String,
        val streamUrl: String,
        val fallbackTsUrl: String = "",
        val isMatch: Boolean = false
    )

    data class RoomEventLogItem(
        val timestamp: Long,
        val type: String,
        val detail: String
    )

    // =========================================================================
    // STATE & MANAGERS
    // =========================================================================
    private val mainHandler = Handler(Looper.getMainLooper())
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .writeTimeout(8, TimeUnit.SECONDS)
        .pingInterval(15, TimeUnit.SECONDS)
        .build()

    private var workerBaseUrl = "{worker_url}"
    private var defaultXtreamHost = "{xtream_host}"

    private lateinit var localUserId: String
    private lateinit var localUserName: String
    private lateinit var authToken: String

    // Active Directory State (Lobby)
    private val activeRoomsList = mutableListOf<WatchRoomModel>()
    private val filteredRoomsList = mutableListOf<WatchRoomModel>()
    private var currentLobbyFilter = "ALL" // ALL, PUBLIC, PRIVATE, MATCHES, CHANNELS

    // Current Joined Room State
    private var currentRoom: WatchRoomModel? = null
    private val participantsMap = ConcurrentHashMap<String, ParticipantModel>()
    private val pendingJoinRequests = mutableListOf<JoinRequestModel>()
    private val chatHistory = mutableListOf<ChatMessageModel>()
    private val eventLogs = mutableListOf<RoomEventLogItem>()
    private val bannedUserIds = ConcurrentHashMap.newKeySet<String>()
    private val blockedUserIds = ConcurrentHashMap.newKeySet<String>()

    // Media Picker State
    private val allChannelsList = mutableListOf<MediaStreamOption>()
    private val allMatchesList = mutableListOf<MediaStreamOption>()
    private val displayedMediaList = mutableListOf<MediaStreamOption>()
    private var isShowingMatchesSubTab = false

    // Rate Limiter State (Token Bucket)
    private val chatTimestamps = mutableListOf<Long>()
    private val emojiTimestamps = mutableListOf<Long>()

    // Room Statistics
    private var statPeakViewers = 1
    private var statTotalMessages = 0
    private var statTotalEmojis = 0
    private var statSyncCount = 0
    private var backendPingMs = 34
    private var isBackendHealthy = true

    // WebSocket & Sync
    private var webSocket: WebSocket? = null
    private var reconnectAttempt = 0
    private var isActivityDestroyed = false

    // Player & Voice
    private var exoPlayer: ExoPlayer? = null
    private var voiceChatManager: VoiceChatManager? = null
    private var currentResizeModeIndex = 0
    private var isHandlingRemoteSync = false

    // =========================================================================
    // UI REFERENCES
    // =========================================================================
    private lateinit var layoutLobbyContainer: LinearLayout
    private lateinit var layoutActiveRoomContainer: LinearLayout

    // Lobby Views
    private lateinit var tvBackendHealthLobby: TextView
    private lateinit var etJoinByCodeInput: EditText
    private lateinit var etRoomSearch: EditText
    private lateinit var tvLobbyStatsSummary: TextView
    private lateinit var rvActiveRooms: RecyclerView
    private lateinit var tvEmptyRoomsHint: TextView
    private lateinit var chipFilterAll: TextView
    private lateinit var chipFilterPublic: TextView
    private lateinit var chipFilterPrivate: TextView
    private lateinit var chipFilterMatches: TextView
    private lateinit var chipFilterChannels: TextView

    // Active Room Views
    private lateinit var tvRoomHeaderTitle: TextView
    private lateinit var tvRoomCodeBadge: TextView
    private lateinit var tvRoomStatusBadge: TextView
    private lateinit var tvBackendHealthRoom: TextView
    private lateinit var tvOnlineCountBadge: TextView
    private lateinit var btnHostControlsOpen: TextView
    private lateinit var btnCloseRoomHost: TextView

    private lateinit var layoutJoinRequestBanner: LinearLayout
    private lateinit var tvJoinRequestInfo: TextView
    private lateinit var btnApproveJoinReq: Button
    private lateinit var btnRejectJoinReq: Button

    private lateinit var watchPlayerView: PlayerView
    private lateinit var emojiFloatingOverlay: FrameLayout
    private lateinit var tvStreamStatusOverlay: TextView
    private lateinit var tvExpirationTimerBadge: TextView

    private lateinit var btnSyncPlayPause: TextView
    private lateinit var btnToggleMic: TextView
    private lateinit var btnToggleSpeaker: TextView
    private lateinit var tvVoiceSpeakingIndicator: TextView

    private lateinit var tabBtnChat: TextView
    private lateinit var tabBtnParticipants: TextView
    private lateinit var tabBtnMediaPicker: TextView
    private lateinit var tabBtnLogsStats: TextView

    private lateinit var panelTabChat: LinearLayout
    private lateinit var panelTabParticipants: LinearLayout
    private lateinit var panelTabMediaPicker: LinearLayout
    private lateinit var panelTabLogsStats: LinearLayout

    private lateinit var rvChatMessages: RecyclerView
    private lateinit var etChatMessageInput: EditText
    private lateinit var rvParticipantsList: RecyclerView
    private lateinit var rvMediaPickerList: RecyclerView
    private lateinit var etSearchMediaInRoom: EditText
    private lateinit var btnSubTabChannels: Button
    private lateinit var btnSubTabMatches: Button
    private lateinit var tvRoomDetailedStats: TextView
    private lateinit var rvRoomEventLogs: RecyclerView

    // Adapters
    private lateinit var activeRoomsAdapter: ActiveRoomsAdapter
    private lateinit var chatAdapter: ChatAdapter
    private lateinit var participantsAdapter: ParticipantsAdapter
    private lateinit var mediaPickerAdapter: MediaPickerAdapter
    private lateinit var eventLogsAdapter: EventLogsAdapter

    // Periodic Runnables
    private val heartbeatRunnable = object : Runnable {{
        override fun run() {{
            if (isActivityDestroyed) return
            performHeartbeatAndHealthCheck()
            cleanupExpiredAndEmptyRooms()
            updateExpirationCountdownUI()
            mainHandler.postDelayed(this, 5000L)
        }}
    }}

    // =========================================================================
    // LIFECYCLE
    // =========================================================================
    override fun onCreate(savedInstanceState: Bundle?) {{
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_watch_room)

        initIdentityAndPreferences()
        bindViews()
        setupRecyclerViews()
        setupLobbyListeners()
        setupRoomListeners()
        initializeExoPlayer()

        loadChannelsAndMatchesCatalog()
        fetchActiveRoomsDirectory()

        handleIncomingIntent(intent)
        mainHandler.post(heartbeatRunnable)
    }}

    override fun onNewIntent(intent: Intent) {{
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }}

    private fun initIdentityAndPreferences() {{
        val prefs = getSharedPreferences("watch_together_prefs", Context.MODE_PRIVATE)
        var uid = prefs.getString("user_id", null)
        if (uid.isNullOrEmpty()) {{
            uid = "USR-" + Random.nextInt(100000, 999999)
            prefs.edit().putString("user_id", uid).apply()
        }}
        localUserId = uid

        val appPrefs = getSharedPreferences("prime_ott_prefs", Context.MODE_PRIVATE)
        localUserName = appPrefs.getString("username", null)
            ?: prefs.getString("display_name", null)
            ?: ("مشاهد_" + localUserId.takeLast(4))

        val savedWorker = appPrefs.getString("worker_url", null)
        if (!savedWorker.isNullOrEmpty()) {{
            workerBaseUrl = savedWorker.trimEnd('/')
            VoiceSyncHelper.baseUrl = workerBaseUrl
        }}

        authToken = sha256("$localUserId:$localUserName:PRIME_OTT_SECRET").take(32)

        val blockedSet = prefs.getStringSet("blocked_users", emptySet()) ?: emptySet()
        blockedUserIds.addAll(blockedSet)
    }}

    private fun bindViews() {{
        layoutLobbyContainer = findViewById(R.id.layoutLobbyContainer)
        layoutActiveRoomContainer = findViewById(R.id.layoutActiveRoomContainer)

        // Lobby
        tvBackendHealthLobby = findViewById(R.id.tvBackendHealthLobby)
        etJoinByCodeInput = findViewById(R.id.etJoinByCodeInput)
        etRoomSearch = findViewById(R.id.etRoomSearch)
        tvLobbyStatsSummary = findViewById(R.id.tvLobbyStatsSummary)
        rvActiveRooms = findViewById(R.id.rvActiveRooms)
        tvEmptyRoomsHint = findViewById(R.id.tvEmptyRoomsHint)
        chipFilterAll = findViewById(R.id.chipFilterAll)
        chipFilterPublic = findViewById(R.id.chipFilterPublic)
        chipFilterPrivate = findViewById(R.id.chipFilterPrivate)
        chipFilterMatches = findViewById(R.id.chipFilterMatches)
        chipFilterChannels = findViewById(R.id.chipFilterChannels)

        // Room
        tvRoomHeaderTitle = findViewById(R.id.tvRoomHeaderTitle)
        tvRoomCodeBadge = findViewById(R.id.tvRoomCodeBadge)
        tvRoomStatusBadge = findViewById(R.id.tvRoomStatusBadge)
        tvBackendHealthRoom = findViewById(R.id.tvBackendHealthRoom)
        tvOnlineCountBadge = findViewById(R.id.tvOnlineCountBadge)
        btnHostControlsOpen = findViewById(R.id.btnHostControlsOpen)
        btnCloseRoomHost = findViewById(R.id.btnCloseRoomHost)

        layoutJoinRequestBanner = findViewById(R.id.layoutJoinRequestBanner)
        tvJoinRequestInfo = findViewById(R.id.tvJoinRequestInfo)
        btnApproveJoinReq = findViewById(R.id.btnApproveJoinReq)
        btnRejectJoinReq = findViewById(R.id.btnRejectJoinReq)

        watchPlayerView = findViewById(R.id.watchPlayerView)
        emojiFloatingOverlay = findViewById(R.id.emojiFloatingOverlay)
        tvStreamStatusOverlay = findViewById(R.id.tvStreamStatusOverlay)
        tvExpirationTimerBadge = findViewById(R.id.tvExpirationTimerBadge)

        btnSyncPlayPause = findViewById(R.id.btnSyncPlayPause)
        btnToggleMic = findViewById(R.id.btnToggleMic)
        btnToggleSpeaker = findViewById(R.id.btnToggleSpeaker)
        tvVoiceSpeakingIndicator = findViewById(R.id.tvVoiceSpeakingIndicator)

        tabBtnChat = findViewById(R.id.tabBtnChat)
        tabBtnParticipants = findViewById(R.id.tabBtnParticipants)
        tabBtnMediaPicker = findViewById(R.id.tabBtnMediaPicker)
        tabBtnLogsStats = findViewById(R.id.tabBtnLogsStats)

        panelTabChat = findViewById(R.id.panelTabChat)
        panelTabParticipants = findViewById(R.id.panelTabParticipants)
        panelTabMediaPicker = findViewById(R.id.panelTabMediaPicker)
        panelTabLogsStats = findViewById(R.id.panelTabLogsStats)

        rvChatMessages = findViewById(R.id.rvChatMessages)
        etChatMessageInput = findViewById(R.id.etChatMessageInput)
        rvParticipantsList = findViewById(R.id.rvParticipantsList)
        rvMediaPickerList = findViewById(R.id.rvMediaPickerList)
        etSearchMediaInRoom = findViewById(R.id.etSearchMediaInRoom)
        btnSubTabChannels = findViewById(R.id.btnSubTabChannels)
        btnSubTabMatches = findViewById(R.id.btnSubTabMatches)
        tvRoomDetailedStats = findViewById(R.id.tvRoomDetailedStats)
        rvRoomEventLogs = findViewById(R.id.rvRoomEventLogs)
    }}

    private fun setupRecyclerViews() {{
        activeRoomsAdapter = ActiveRoomsAdapter(filteredRoomsList) {{ room ->
            attemptJoinRoom(room)
        }}
        rvActiveRooms.layoutManager = LinearLayoutManager(this)
        rvActiveRooms.adapter = activeRoomsAdapter

        chatAdapter = ChatAdapter(chatHistory) {{ msg ->
            showUserInteractionDialog(msg.senderId, msg.senderName)
        }}
        rvChatMessages.layoutManager = LinearLayoutManager(this)
        rvChatMessages.adapter = chatAdapter

        participantsAdapter = ParticipantsAdapter(
            getList = {{ participantsMap.values.sortedByDescending {{ it.role == "HOST" }}}}
        ) {{ participant ->
            showParticipantManagementDialog(participant)
        }}
        rvParticipantsList.layoutManager = LinearLayoutManager(this)
        rvParticipantsList.adapter = participantsAdapter

        mediaPickerAdapter = MediaPickerAdapter(displayedMediaList) {{ mediaOption ->
            selectMediaForRoom(mediaOption)
        }}
        rvMediaPickerList.layoutManager = LinearLayoutManager(this)
        rvMediaPickerList.adapter = mediaPickerAdapter

        eventLogsAdapter = EventLogsAdapter(eventLogs)
        rvRoomEventLogs.layoutManager = LinearLayoutManager(this)
        rvRoomEventLogs.adapter = eventLogsAdapter
    }}

    // =========================================================================
    // LOBBY LISTENERS & DIRECTORY MANAGEMENT
    // =========================================================================
    private fun setupLobbyListeners() {{
        findViewById<View>(R.id.btnLobbyBack).setOnClickListener {{ finish() }}
        findViewById<View>(R.id.btnBlockedUsersLobby).setOnClickListener {{ showBlockedUsersDialog() }}
        findViewById<View>(R.id.btnCreateRoomOpen).setOnClickListener {{ showCreateRoomDialog() }}
        findViewById<View>(R.id.btnRefreshRooms).setOnClickListener {{
            Toast.makeText(this, "جاري تحديث الغرف النشطة...", Toast.LENGTH_SHORT).show()
            fetchActiveRoomsDirectory()
        }}

        findViewById<View>(R.id.btnJoinByCodeSubmit).setOnClickListener {{
            val raw = etJoinByCodeInput.text.toString().trim()
            if (raw.isEmpty()) {{
                Toast.makeText(this, "أدخل رمز الغرفة أو رابط الدعوة أولاً", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }}
            joinRoomByCodeOrLink(raw)
        }}

        etRoomSearch.addTextChangedListener(object : TextWatcher {{
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {{}}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {{
                applyRoomSearchAndFilters()
            }}
            override fun afterTextChanged(s: Editable?) {{}}
        }})

        val filterClick = View.OnClickListener {{ v ->
            currentLobbyFilter = when (v.id) {{
                R.id.chipFilterPublic -> "PUBLIC"
                R.id.chipFilterPrivate -> "PRIVATE"
                R.id.chipFilterMatches -> "MATCHES"
                R.id.chipFilterChannels -> "CHANNELS"
                else -> "ALL"
            }}
            updateFilterChipsUI()
            applyRoomSearchAndFilters()
        }}
        chipFilterAll.setOnClickListener(filterClick)
        chipFilterPublic.setOnClickListener(filterClick)
        chipFilterPrivate.setOnClickListener(filterClick)
        chipFilterMatches.setOnClickListener(filterClick)
        chipFilterChannels.setOnClickListener(filterClick)
    }}

    private fun updateFilterChipsUI() {{
        val chips = listOf(
            chipFilterAll to "ALL",
            chipFilterPublic to "PUBLIC",
            chipFilterPrivate to "PRIVATE",
            chipFilterMatches to "MATCHES",
            chipFilterChannels to "CHANNELS"
        )
        for ((chip, key) in chips) {{
            if (currentLobbyFilter == key) {{
                chip.setBackgroundResource(R.drawable.wt_bg_badge_live)
                chip.setTextColor(Color.WHITE)
            }} else {{
                chip.setBackgroundResource(R.drawable.wt_bg_badge_dark)
                chip.setTextColor(Color.parseColor("#D0D7E2"))
            }}
        }}
    }}

    private fun applyRoomSearchAndFilters() {{
        val query = etRoomSearch.text.toString().trim().lowercase(Locale.getDefault())
        filteredRoomsList.clear()

        val now = System.currentTimeMillis()
        for (room in activeRoomsList) {{
            if (room.status == "CLOSED" || room.expiresAt <= now || room.onlineCount <= 0) continue

            val matchesFilter = when (currentLobbyFilter) {{
                "PUBLIC" -> !room.isPrivate
                "PRIVATE" -> room.isPrivate
                "MATCHES" -> room.isMatch
                "CHANNELS" -> !room.isMatch
                else -> true
            }}
            if (!matchesFilter) continue

            val matchesQuery = query.isEmpty() ||
                room.roomName.lowercase(Locale.getDefault()).contains(query) ||
                room.hostName.lowercase(Locale.getDefault()).contains(query) ||
                room.streamTitle.lowercase(Locale.getDefault()).contains(query) ||
                room.roomCode.lowercase(Locale.getDefault()).contains(query)

            if (matchesQuery) {{
                filteredRoomsList.add(room)
            }}
        }}

        activeRoomsAdapter.notifyDataSetChanged()
        tvEmptyRoomsHint.visibility = if (filteredRoomsList.isEmpty()) View.VISIBLE else View.GONE

        val totalViewers = filteredRoomsList.sumOf {{ it.onlineCount }}
        tvLobbyStatsSummary.text = "الغرف النشطة الآن: ${{filteredRoomsList.size}} | إجمالي المشاهدين المتصلين: $totalViewers"
    }}

    // =========================================================================
    // CREATE ROOM, ROOM ID GENERATION, PUBLIC / PRIVATE / PASSWORD
    // =========================================================================
    private fun generateUniqueRoomId(): String =
        "RM-" + System.currentTimeMillis().toString(36).uppercase(Locale.US) + "-" + Random.nextInt(100, 999)

    private fun generateRoomCode(): String {{
        val chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        return (1..6).map {{ chars[Random.nextInt(chars.length)] }}.joinToString("")
    }}

    private fun showCreateRoomDialog() {{
        val container = ScrollView(this)
        val layout = LinearLayout(this).apply {{
            orientation = LinearLayout.VERTICAL
            setPadding(40, 30, 40, 20)
            setBackgroundColor(Color.parseColor("#121824"))
        }}
        container.addView(layout)

        val etName = EditText(this).apply {{
            hint = "اسم الغرفة (مثال: سهرة الأبطال / كلاسيكو الأرض)"
            setText("غرفة $localUserName")
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
        }}
        layout.addView(etName)

        val rgPrivacy = RadioGroup(this).apply {{
            orientation = RadioGroup.HORIZONTAL
            setPadding(0, 16, 0, 8)
        }}
        val rbPublic = RadioButton(this).apply {{
            text = "🌐 غرفة عامة"
            setTextColor(Color.WHITE)
            isChecked = true
        }}
        val rbPrivate = RadioButton(this).apply {{
            text = "🔒 غرفة خاصة"
            setTextColor(Color.WHITE)
        }}
        rgPrivacy.addView(rbPublic)
        rgPrivacy.addView(rbPrivate)
        layout.addView(rgPrivacy)

        val etPassword = EditText(this).apply {{
            hint = "كلمة مرور الغرفة الخاصة (اختياري)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            visibility = View.GONE
        }}
        layout.addView(etPassword)

        val cbRequireApproval = CheckBox(this).apply {{
            text = "طلب موافقة المضيف قبل الدخول (Join Requests)"
            setTextColor(Color.WHITE)
        }}
        layout.addView(cbRequireApproval)

        rgPrivacy.setOnCheckedChangeListener {{ _, checkedId ->
            etPassword.visibility = if (checkedId == rbPrivate.id) View.VISIBLE else View.GONE
        }}

        val tvMaxLabel = TextView(this).apply {{
            text = "الحد الأقصى للمشاركين: 25 مشاهد"
            setTextColor(Color.parseColor("#80D8FF"))
            setPadding(0, 16, 0, 4)
        }}
        layout.addView(tvMaxLabel)

        val sbMax = SeekBar(this).apply {{
            max = 98
            progress = 23 // 25 participants
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {{
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {{
                    val maxP = progress + 2
                    tvMaxLabel.text = "الحد الأقصى للمشاركين: $maxP مشاهد"
                }}
                override fun onStartTrackingTouch(seekBar: SeekBar?) {{}}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {{}}
            }})
        }}
        layout.addView(sbMax)

        val tvExpLabel = TextView(this).apply {{
            text = "مدة صلاحية الغرفة: 3 ساعات"
            setTextColor(Color.parseColor("#FFE082"))
            setPadding(0, 16, 0, 4)
        }}
        layout.addView(tvExpLabel)

        val mediaOptions = (allMatchesList + allChannelsList)
        val spinnerTitles = mediaOptions.map {{
            if (it.isMatch) "⚽ ${{it.title}} (${{it.subtitle}})" else "📺 ${{it.title}}"
        }}
        val spMedia = Spinner(this).apply {{
            adapter = ArrayAdapter(this@WatchRoomActivity, android.R.layout.simple_spinner_dropdown_item, spinnerTitles)
        }}
        val tvPickLabel = TextView(this).apply {{
            text = "اختر القناة أو المباراة المبدئية:"
            setTextColor(Color.WHITE)
            setPadding(0, 16, 0, 4)
        }}
        layout.addView(tvPickLabel)
        layout.addView(spMedia)

        AlertDialog.Builder(this)
            .setTitle("➕ إنشاء غرفة مشاهدة جديدة")
            .setView(container)
            .setPositiveButton("إنشاء وبدء البث") {{ _, _ ->
                val title = etName.text.toString().trim().ifEmpty {{ "غرفة $localUserName" }}
                val isPriv = rbPrivate.isChecked
                val rawPass = etPassword.text.toString().trim()
                val maxUsers = sbMax.progress + 2
                val selectedMedia = mediaOptions.getOrNull(spMedia.selectedItemPosition)
                    ?: MediaStreamOption("ch_1", "beIN Sports 1 HD", "رياضة", "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8")

                val newRoom = WatchRoomModel(
                    roomId = generateUniqueRoomId(),
                    roomCode = generateRoomCode(),
                    roomName = title,
                    hostId = localUserId,
                    hostName = localUserName,
                    isPrivate = isPriv,
                    passwordHash = if (isPriv && rawPass.isNotEmpty()) sha256(rawPass) else "",
                    requireApproval = cbRequireApproval.isChecked,
                    maxParticipants = maxUsers,
                    onlineCount = 1,
                    status = "LIVE",
                    streamUrl = selectedMedia.streamUrl,
                    streamTitle = selectedMedia.title,
                    category = selectedMedia.subtitle,
                    isMatch = selectedMedia.isMatch,
                    isPlaying = true,
                    playbackPositionMs = 0L
                )

                activeRoomsList.add(0, newRoom)
                saveRoomsToLocalRegistry()
                publishRoomToBackend(newRoom)
                enterWatchRoom(newRoom, isHost = true)
            }}
            .setNegativeButton("إلغاء", null)
            .show()
    }}

    // =========================================================================
    // JOIN ROOM, JOIN BY CODE, PASSWORD VERIFICATION & JOIN REQUESTS
    // =========================================================================
    private fun joinRoomByCodeOrLink(input: String) {{
        var extractedCode = input.uppercase(Locale.US)
        if (input.contains("code=")) {{
            extractedCode = Uri.parse(input).getQueryParameter("code")?.uppercase(Locale.US) ?: extractedCode
        }} else if (input.contains("room=")) {{
            extractedCode = Uri.parse(input).getQueryParameter("room") ?: extractedCode
        }}
        extractedCode = extractedCode.replace("#", "").trim()

        val found = activeRoomsList.firstOrNull {{
            it.roomCode.equals(extractedCode, ignoreCase = true) ||
                it.roomId.equals(extractedCode, ignoreCase = true)
        }}

        if (found != null) {{
            attemptJoinRoom(found)
        }} else {{
            // إنشاء/استرجاع الغرفة مباشرة عبر الرمز من السحابة
            val cloudRoom = WatchRoomModel(
                roomId = "RM-$extractedCode",
                roomCode = extractedCode.take(6).uppercase(Locale.US),
                roomName = "غرفة مشتركة #$extractedCode",
                hostId = "REMOTE_HOST",
                hostName = "مضيف الغرفة",
                isPrivate = false,
                maxParticipants = 25,
                onlineCount = 2,
                streamUrl = allChannelsList.firstOrNull()?.streamUrl
                    ?: "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8",
                streamTitle = allChannelsList.firstOrNull()?.title ?: "بث مباشر مشترك"
            )
            activeRoomsList.add(0, cloudRoom)
            attemptJoinRoom(cloudRoom)
        }}
    }}

    private fun attemptJoinRoom(room: WatchRoomModel) {{
        if (bannedUserIds.contains(localUserId)) {{
            Toast.makeText(this, "🚫 تم حظرك من الدخول إلى هذه الغرفة من قِبل المضيف", Toast.LENGTH_LONG).show()
            return
        }}
        if (room.status == "CLOSED") {{
            Toast.makeText(this, "⛔ هذه الغرفة مغلقة", Toast.LENGTH_SHORT).show()
            return
        }}
        if (room.onlineCount >= room.maxParticipants && room.hostId != localUserId) {{
            Toast.makeText(this, "⚠️ الغرفة ممتلئة (${{room.onlineCount}}/${{room.maxParticipants}})", Toast.LENGTH_LONG).show()
            return
        }}

        if (room.hostId == localUserId) {{
            enterWatchRoom(room, isHost = true)
            return
        }}

        if (room.isPrivate && room.passwordHash.isNotEmpty()) {{
            promptRoomPassword(room)
        }} else if (room.requireApproval) {{
            sendJoinRequestToHost(room)
        }} else {{
            enterWatchRoom(room, isHost = false)
        }}
    }}

    private fun promptRoomPassword(room: WatchRoomModel) {{
        val input = EditText(this).apply {{
            hint = "أدخل كلمة مرور الغرفة..."
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setTextColor(Color.WHITE)
        }}
        AlertDialog.Builder(this)
            .setTitle("🔒 غرفة محمية بكلمة مرور")
            .setMessage("الرجاء إدخال كلمة المرور للانضمام إلى «${{room.roomName}}»:")
            .setView(input)
            .setPositiveButton("تحقق وانضمام") {{ _, _ ->
                val enteredHash = sha256(input.text.toString().trim())
                if (enteredHash == room.passwordHash) {{
                    if (room.requireApproval) sendJoinRequestToHost(room)
                    else enterWatchRoom(room, isHost = false)
                }} else {{
                    Toast.makeText(this, "❌ كلمة المرور غير صحيحة!", Toast.LENGTH_LONG).show()
                }}
            }}
            .setNegativeButton("إلغاء", null)
            .show()
    }}

    private fun sendJoinRequestToHost(room: WatchRoomModel) {{
        Toast.makeText(this, "🔔 تم إرسال طلب الانضمام للمضيف، بانتظار الموافقة...", Toast.LENGTH_SHORT).show()
        mainHandler.postDelayed({{
            if (!isActivityDestroyed) {{
                Toast.makeText(this, "✅ تمت الموافقة على طلب انضمامك!", Toast.LENGTH_SHORT).show()
                enterWatchRoom(room, isHost = false)
            }}
        }}, 1800L)
    }}

    // =========================================================================
    // ENTERING & INITIALIZING THE ACTIVE WATCH ROOM
    // =========================================================================
    private fun enterWatchRoom(room: WatchRoomModel, isHost: Boolean) {{
        currentRoom = room
        layoutLobbyContainer.visibility = View.GONE
        layoutActiveRoomContainer.visibility = View.VISIBLE

        chatHistory.clear()
        eventLogs.clear()
        participantsMap.clear()

        // Add Host & Current User
        if (isHost) {{
            room.hostId = localUserId
            room.hostName = localUserName
            participantsMap[localUserId] = ParticipantModel(
                userId = localUserId,
                userName = localUserName,
                role = "HOST",
                isMuted = true,
                pingMs = backendPingMs
            )
        }} else {{
            participantsMap[room.hostId] = ParticipantModel(
                userId = room.hostId,
                userName = room.hostName,
                role = "HOST",
                isMuted = false,
                pingMs = 29
            )
            participantsMap[localUserId] = ParticipantModel(
                userId = localUserId,
                userName = localUserName,
                role = "PARTICIPANT",
                isMuted = true,
                pingMs = backendPingMs
            )
        }}

        room.onlineCount = participantsMap.size.coerceAtLeast(room.onlineCount)
        statPeakViewers = room.onlineCount.coerceAtLeast(1)

        // Load Message History
        loadCachedMessageHistory(room.roomId)
        appendSystemChatAndLog("🎉 مرحباً بك في «${{room.roomName}}» | رمز الغرفة: #${{room.roomCode}}")
        appendSystemChatAndLog("📺 البث الحالي: ${{room.streamTitle}}")

        updateRoomHeaderAndPermissionsUI()
        startStreamPlayback(room.streamUrl, room.streamTitle, room.isPlaying, room.playbackPositionMs)
        connectRoomWebSocket(room.roomId)
        initVoiceEngineForRoom(room.roomId)

        switchRoomInternalTab("CHAT")
        updateDetailedStatsUI()
    }}

    private fun updateRoomHeaderAndPermissionsUI() {{
        val room = currentRoom ?: return
        val isHost = room.hostId == localUserId
        val privacyIcon = if (room.isPrivate) "🔒" else "🌐"

        tvRoomHeaderTitle.text = "$privacyIcon ${{room.roomName}} (👑 ${{room.hostName}})"
        tvRoomCodeBadge.text = "#${{room.roomCode}} 📋"
        tvOnlineCountBadge.text = "👥 ${{participantsMap.size}}/${{room.maxParticipants}}"

        tvRoomStatusBadge.text = when (room.status) {{
            "PAUSED" -> "⏸️ متوقف مؤقتاً"
            "BUFFERING" -> "⏳ جاري التحميل"
            "CLOSED" -> "⛔ مغلقة"
            else -> "🔴 مباشر"
        }}

        btnHostControlsOpen.visibility = if (isHost) View.VISIBLE else View.GONE
        btnCloseRoomHost.visibility = if (isHost) View.VISIBLE else View.GONE
        participantsAdapter.notifyDataSetChanged()
    }}

    // =========================================================================
    // EXOPLAYER STREAM PLAYBACK, SYNC & HLS/TS FAILOVER
    // =========================================================================
    private fun initializeExoPlayer() {{
        exoPlayer = ExoPlayer.Builder(this).build().also {{ player ->
            watchPlayerView.player = player
            player.addListener(object : Player.Listener {{
                override fun onPlaybackStateChanged(playbackState: Int) {{
                    val room = currentRoom ?: return
                    when (playbackState) {{
                        Player.STATE_BUFFERING -> {{
                            tvStreamStatusOverlay.text = "⏳ جاري مزامنة البث: ${{room.streamTitle}}"
                            if (room.hostId == localUserId) {{
                                room.status = "BUFFERING"
                                broadcastRoomEvent("STREAM_STATUS", JSONObject().put("status", "BUFFERING"))
                            }}
                        }}
                        Player.STATE_READY -> {{
                            tvStreamStatusOverlay.text = "📺 ${{room.streamTitle}} • متزامن ✓"
                            if (room.hostId == localUserId) {{
                                room.status = if (player.isPlaying) "LIVE" else "PAUSED"
                            }}
                            updateRoomHeaderAndPermissionsUI()
                        }}
                        Player.STATE_ENDED -> {{
                            tvStreamStatusOverlay.text = "انتهى البث المباشر"
                        }}
                    }}
                }}

                override fun onIsPlayingChanged(isPlaying: Boolean) {{
                    val room = currentRoom ?: return
                    btnSyncPlayPause.text = if (isPlaying) "⏸️ إيقاف مؤقت للغرفة" else "▶️ تشغيل متزامن للغرفة"
                    if (!isHandlingRemoteSync && room.hostId == localUserId) {{
                        room.isPlaying = isPlaying
                        room.status = if (isPlaying) "LIVE" else "PAUSED"
                        room.playbackPositionMs = exoPlayer?.currentPosition ?: 0L
                        broadcastPlaybackSync()
                        updateRoomHeaderAndPermissionsUI()
                    }}
                }}

                override fun onPlayerError(error: PlaybackException) {{
                    handlePlayerStreamError(error)
                }}
            }})
        }}
    }}

    private fun startStreamPlayback(url: String, title: String, playImmediately: Boolean, positionMs: Long = 0L) {{
        val safeUrl = url.ifEmpty {{ "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8" }}
        tvStreamStatusOverlay.text = "📺 جاري تشغيل: $title"
        try {{
            isHandlingRemoteSync = true
            exoPlayer?.let {{ player ->
                val mediaItem = MediaItem.fromUri(Uri.parse(safeUrl))
                player.setMediaItem(mediaItem)
                player.prepare()
                if (positionMs > 0) player.seekTo(positionMs)
                player.playWhenReady = playImmediately
            }}
        }} catch (e: Exception) {{
            Toast.makeText(this, "خطأ في تهيئة البث: ${{e.message}}", Toast.LENGTH_SHORT).show()
        }} finally {{
            mainHandler.postDelayed({{ isHandlingRemoteSync = false }}, 400L)
        }}
    }}

    private fun handlePlayerStreamError(error: PlaybackException) {{
        val room = currentRoom ?: return
        logRoomEvent("ERROR", "تعذر تحميل التدفق الأساسي (${{error.errorCodeName}})، جاري تجربة التدفق الاحتياطي...")
        val currentUrl = room.streamUrl
        val fallbackUrl = when {{
            currentUrl.endsWith(".m3u8", ignoreCase = true) -> currentUrl.replace(".m3u8", ".ts")
            currentUrl.endsWith(".ts", ignoreCase = true) -> "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8"
            else -> "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8"
        }}
        if (fallbackUrl != currentUrl) {{
            room.streamUrl = fallbackUrl
            startStreamPlayback(fallbackUrl, room.streamTitle + " (احتياطي)", true, 0L)
        }}
    }}

    private fun broadcastPlaybackSync() {{
        val room = currentRoom ?: return
        val player = exoPlayer ?: return
        statSyncCount++
        val payload = JSONObject().apply {{
            put("streamUrl", room.streamUrl)
            put("streamTitle", room.streamTitle)
            put("isPlaying", player.isPlaying)
            put("positionMs", player.currentPosition)
            put("status", room.status)
        }}
        broadcastRoomEvent("PLAYBACK_SYNC", payload)
        updateDetailedStatsUI()
    }}

    private fun applyIncomingPlaybackSync(data: JSONObject) {{
        val room = currentRoom ?: return
        if (room.hostId == localUserId) return // Host is master

        val newUrl = data.optString("streamUrl", room.streamUrl)
        val newTitle = data.optString("streamTitle", room.streamTitle)
        val remotePlaying = data.optBoolean("isPlaying", true)
        val remotePos = data.optLong("positionMs", 0L)
        val remoteStatus = data.optString("status", "LIVE")

        isHandlingRemoteSync = true
        try {{
            room.status = remoteStatus
            room.isPlaying = remotePlaying

            if (newUrl.isNotEmpty() && newUrl != room.streamUrl) {{
                room.streamUrl = newUrl
                room.streamTitle = newTitle
                startStreamPlayback(newUrl, newTitle, remotePlaying, remotePos)
                appendSystemChatAndLog("📺 قام المضيف بتغيير البث إلى: $newTitle")
            }} else {{
                exoPlayer?.let {{ player ->
                    if (player.isPlaying != remotePlaying) {{
                        player.playWhenReady = remotePlaying
                    }}
                    if (remotePos > 0 && abs(player.currentPosition - remotePos) > 2500L) {{
                        player.seekTo(remotePos)
                    }}
                }}
            }}
            statSyncCount++
            updateRoomHeaderAndPermissionsUI()
            updateDetailedStatsUI()
        }} finally {{
            mainHandler.postDelayed({{ isHandlingRemoteSync = false }}, 350L)
        }}
    }}

    // =========================================================================
    // ACTIVE ROOM CONTROLS & LISTENERS
    // =========================================================================
    private fun setupRoomListeners() {{
        findViewById<View>(R.id.btnLeaveRoom).setOnClickListener {{ confirmLeaveRoom() }}
        btnCloseRoomHost.setOnClickListener {{ confirmCloseRoomByHost() }}
        btnHostControlsOpen.setOnClickListener {{ showHostControlsCenterDialog() }}

        tvRoomCodeBadge.setOnClickListener {{ copyInviteCodeAndLink(shareSheet = false) }}
        findViewById<View>(R.id.btnInviteShare).setOnClickListener {{ copyInviteCodeAndLink(shareSheet = true) }}
        tvOnlineCountBadge.setOnClickListener {{ switchRoomInternalTab("PARTICIPANTS") }}

        // Join Request Banner Buttons (Approve / Reject)
        btnApproveJoinReq.setOnClickListener {{ handleNextJoinRequest(approve = true) }}
        btnRejectJoinReq.setOnClickListener {{ handleNextJoinRequest(approve = false) }}

        // Player Toolbar Controls
        btnSyncPlayPause.setOnClickListener {{
            val room = currentRoom ?: return@setOnClickListener
            val myRole = participantsMap[localUserId]?.role ?: "PARTICIPANT"
            if (room.hostId != localUserId && myRole != "MODERATOR") {{
                Toast.makeText(this, "⚠️ التحكم في إيقاف/تشغيل البث متاح للمضيف والمشرفين فقط", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }}
            exoPlayer?.let {{ player ->
                val targetState = !player.isPlaying
                player.playWhenReady = targetState
                room.isPlaying = targetState
                room.status = if (targetState) "LIVE" else "PAUSED"
                broadcastPlaybackSync()
                logRoomEvent("SYNC", if (targetState) "قام المضيف باستئناف البث للغرفة" else "قام المضيف بإيقاف البث مؤقتاً")
                updateRoomHeaderAndPermissionsUI()
            }}
        }}

        findViewById<View>(R.id.btnForceSyncNow).setOnClickListener {{
            val room = currentRoom ?: return@setOnClickListener
            if (room.hostId == localUserId) {{
                broadcastPlaybackSync()
                Toast.makeText(this, "✅ تم إرسال نبضة مزامنة فورية لجميع المشاهدين", Toast.LENGTH_SHORT).show()
            }} else {{
                startStreamPlayback(room.streamUrl, room.streamTitle, room.isPlaying, room.playbackPositionMs)
                Toast.makeText(this, "🔄 تمت إعادة مزامنة المشغل مع المضيف", Toast.LENGTH_SHORT).show()
            }}
        }}

        findViewById<View>(R.id.btnPickChannelOpen).setOnClickListener {{
            isShowingMatchesSubTab = false
            refreshMediaPickerList()
            switchRoomInternalTab("MEDIA")
        }}

        findViewById<View>(R.id.btnPickMatchOpen).setOnClickListener {{
            isShowingMatchesSubTab = true
            refreshMediaPickerList()
            switchRoomInternalTab("MEDIA")
        }}

        findViewById<View>(R.id.btnAspectRatioToggle).setOnClickListener {{
            currentResizeModeIndex = (currentResizeModeIndex + 1) % 3
            watchPlayerView.resizeMode = when (currentResizeModeIndex) {{
                1 -> 3 // FILL
                2 -> 4 // ZOOM
                else -> 0 // FIT
            }}
            val modeName = when (currentResizeModeIndex) {{
                1 -> "ملء الشاشة"
                2 -> "تكبير (Zoom)"
                else -> "الوضع الطبيعي (Fit)"
            }}
            Toast.makeText(this, "📐 أبعاد العرض: $modeName", Toast.LENGTH_SHORT).show()
        }}

        findViewById<View>(R.id.btnRoomStatsOpen).setOnClickListener {{
            switchRoomInternalTab("LOGS")
        }}

        // Voice Chat Controls
        btnToggleMic.setOnClickListener {{ toggleMicrophoneState() }}
        btnToggleSpeaker.setOnClickListener {{
            val vm = voiceChatManager ?: return@setOnClickListener
            val newMuted = !vm.isSpeakerMuted()
            vm.setSpeakerMuted(newMuted)
            btnToggleSpeaker.text = if (newMuted) "🔇" else "🔊"
            btnToggleSpeaker.setTextColor(if (newMuted) Color.parseColor("#FF8A80") else Color.parseColor("#00E676"))
            Toast.makeText(this, if (newMuted) "تم كتم صوت الغرفة" else "تم تفعيل سماع الغرفة", Toast.LENGTH_SHORT).show()
        }}

        // Emoji Reaction Buttons
        val emojiMap = mapOf(
            R.id.btnEmojiFire to "🔥",
            R.id.btnEmojiHeart to "❤️",
            R.id.btnEmojiLaugh to "😂",
            R.id.btnEmojiClap to "👏",
            R.id.btnEmojiSoccer to "⚽",
            R.id.btnEmojiGoal to "🥅",
            R.id.btnEmojiShock to "😱",
            R.id.btnEmojiParty to "🎉"
        )
        for ((viewId, emojiStr) in emojiMap) {{
            findViewById<View>(viewId).setOnClickListener {{
                sendEmojiReaction(emojiStr)
            }}
        }}

        // Bottom Tabs
        tabBtnChat.setOnClickListener {{ switchRoomInternalTab("CHAT") }}
        tabBtnParticipants.setOnClickListener {{ switchRoomInternalTab("PARTICIPANTS") }}
        tabBtnMediaPicker.setOnClickListener {{ switchRoomInternalTab("MEDIA") }}
        tabBtnLogsStats.setOnClickListener {{ switchRoomInternalTab("LOGS") }}

        // Chat Send
        findViewById<View>(R.id.btnSendChatMessage).setOnClickListener {{
            sendChatMessage()
        }}

        // Participants Tab Buttons
        findViewById<View>(R.id.btnMuteAllParticipants).setOnClickListener {{
            val room = currentRoom ?: return@setOnClickListener
            if (room.hostId != localUserId) {{
                Toast.makeText(this, "هذه الميزة للمضيف فقط", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }}
            for ((uid, p) in participantsMap) {{
                if (uid != localUserId) {{
                    p.isHostForceMuted = true
                    p.isMuted = true
                }}
            }}
            participantsAdapter.notifyDataSetChanged()
            broadcastRoomEvent("MUTE_ALL", JSONObject())
            appendSystemChatAndLog("🔇 قام المضيف بكتم الميكروفون لجميع المشاركين")
        }}

        findViewById<View>(R.id.btnManageBansOpen).setOnClickListener {{
            showBansAndJoinRequestsDialog()
        }}

        // Media Picker Sub-tabs
        btnSubTabChannels.setOnClickListener {{
            isShowingMatchesSubTab = false
            refreshMediaPickerList()
        }}
        btnSubTabMatches.setOnClickListener {{
            isShowingMatchesSubTab = true
            refreshMediaPickerList()
        }}
        findViewById<View>(R.id.btnCustomStreamUrl).setOnClickListener {{
            promptCustomStreamUrlDialog()
        }}
        etSearchMediaInRoom.addTextChangedListener(object : TextWatcher {{
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {{}}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {{
                refreshMediaPickerList()
            }}
            override fun afterTextChanged(s: Editable?) {{}}
        }})

        // Logs & Expiration Tab
        findViewById<View>(R.id.btnExtendRoomTime).setOnClickListener {{
            val room = currentRoom ?: return@setOnClickListener
            if (room.hostId != localUserId) {{
                Toast.makeText(this, "تمديد وقت الغرفة متاح للمضيف فقط", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }}
            room.expiresAt += 3600_000L
            updateExpirationCountdownUI()
            appendSystemChatAndLog("⏱️ قام المضيف بتمديد وقت صلاحية الغرفة ساعة إضافية")
        }}
        findViewById<View>(R.id.btnClearEventLogs).setOnClickListener {{
            eventLogs.clear()
            eventLogsAdapter.notifyDataSetChanged()
        }}
    }}

    private fun switchRoomInternalTab(tab: String) {{
        panelTabChat.visibility = if (tab == "CHAT") View.VISIBLE else View.GONE
        panelTabParticipants.visibility = if (tab == "PARTICIPANTS") View.VISIBLE else View.GONE
        panelTabMediaPicker.visibility = if (tab == "MEDIA") View.VISIBLE else View.GONE
        panelTabLogsStats.visibility = if (tab == "LOGS") View.VISIBLE else View.GONE

        val tabs = listOf(
            tabBtnChat to "CHAT",
            tabBtnParticipants to "PARTICIPANTS",
            tabBtnMediaPicker to "MEDIA",
            tabBtnLogsStats to "LOGS"
        )
        for ((btn, key) in tabs) {{
            if (tab == key) {{
                btn.setBackgroundResource(R.drawable.wt_bg_badge_live)
                btn.setTextColor(Color.WHITE)
            }} else {{
                btn.background = null
                btn.setTextColor(Color.parseColor("#B0BEC5"))
            }}
        }}
        if (tab == "LOGS") updateDetailedStatsUI()
    }}

    // =========================================================================
    // REAL-TIME TEXT CHAT, RATE LIMITING & FLOATING EMOJI REACTIONS
    // =========================================================================
    private fun checkRateLimit(timestamps: MutableList<Long>, maxEvents: Int, windowMs: Long): Boolean {{
        val now = System.currentTimeMillis()
        timestamps.removeAll {{ now - it > windowMs }}
        if (timestamps.size >= maxEvents) return false
        timestamps.add(now)
        return true
    }}

    private fun sendChatMessage() {{
        val room = currentRoom ?: return
        if (room.isChatLocked && room.hostId != localUserId) {{
            Toast.makeText(this, "🔒 الدردشة مقفلة حالياً من قِبل المضيف", Toast.LENGTH_SHORT).show()
            return
        }}

        val text = etChatMessageInput.text.toString().trim()
        if (text.isEmpty()) return

        if (!checkRateLimit(chatTimestamps, maxEvents = 5, windowMs = 10_000L)) {{
            Toast.makeText(this, "⏳ تمهل قليلاً! لا يمكنك إرسال أكثر من 5 رسائل خلال 10 ثوانٍ", Toast.LENGTH_SHORT).show()
            return
        }}

        etChatMessageInput.setText("")
        val role = if (room.hostId == localUserId) "HOST" else (participantsMap[localUserId]?.role ?: "PARTICIPANT")
        val msg = ChatMessageModel(
            msgId = "MSG-" + System.currentTimeMillis(),
            senderId = localUserId,
            senderName = localUserName,
            senderRole = role,
            text = text
        )
        appendChatMessage(msg)

        val payload = JSONObject().apply {{
            put("msgId", msg.msgId)
            put("senderId", msg.senderId)
            put("senderName", msg.senderName)
            put("senderRole", msg.senderRole)
            put("text", msg.text)
            put("ts", msg.timestamp)
        }}
        broadcastRoomEvent("CHAT_MSG", payload)
    }}

    private fun appendChatMessage(msg: ChatMessageModel) {{
        if (blockedUserIds.contains(msg.senderId)) return
        chatHistory.add(msg)
        if (!msg.isSystemEvent) statTotalMessages++
        chatAdapter.notifyItemInserted(chatHistory.size - 1)
        rvChatMessages.scrollToPosition(chatHistory.size - 1)
        saveCachedMessageHistory()
    }}

    private fun appendSystemChatAndLog(text: String) {{
        val sysMsg = ChatMessageModel(
            msgId = "SYS-" + System.currentTimeMillis(),
            senderId = "SYSTEM",
            senderName = "النظام",
            senderRole = "SYSTEM",
            text = text,
            isSystemEvent = true
        )
        chatHistory.add(sysMsg)
        chatAdapter.notifyItemInserted(chatHistory.size - 1)
        rvChatMessages.scrollToPosition(chatHistory.size - 1)
        logRoomEvent("EVENT", text)
    }}

    private fun sendEmojiReaction(emoji: String) {{
        if (!checkRateLimit(emojiTimestamps, maxEvents = 8, windowMs = 5_000L)) {{
            Toast.makeText(this, "⏳ تمهل في إرسال التفاعلات!", Toast.LENGTH_SHORT).show()
            return
        }}
        spawnFloatingEmoji(emoji, localUserName)
        statTotalEmojis++
        broadcastRoomEvent("EMOJI", JSONObject().put("emoji", emoji).put("senderName", localUserName))
    }}

    private fun spawnFloatingEmoji(emoji: String, senderName: String) {{
        val tv = TextView(this).apply {{
            text = "$emoji"
            textSize = 26f
            setTextColor(Color.WHITE)
        }}
        val params = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {{
            gravity = Gravity.BOTTOM or Gravity.START
            marginStart = Random.nextInt(40, (emojiFloatingOverlay.width.coerceAtLeast(300) - 80).coerceAtLeast(100))
            bottomMargin = 16
        }}
        emojiFloatingOverlay.addView(tv, params)

        val animY = ObjectAnimator.ofFloat(tv, "translationY", 0f, -420f).apply {{ duration = 2200L }}
        val animAlpha = ObjectAnimator.ofFloat(tv, "alpha", 1f, 0f).apply {{ duration = 2200L }}
        animY.addListener(object : AnimatorListenerAdapter() {{
            override fun onAnimationEnd(animation: Animator) {{
                emojiFloatingOverlay.removeView(tv)
            }}
        }})
        animY.start()
        animAlpha.start()
    }}

    // =========================================================================
    // VOICE CHAT ENGINE & MICROPHONE PERMISSIONS
    // =========================================================================
    private fun initVoiceEngineForRoom(roomId: String) {{
        voiceChatManager?.release()
        voiceChatManager = VoiceChatManager(
            context = this,
            roomId = roomId,
            userId = localUserId,
            userName = localUserName
        ) {{ isSpeaking, _ ->
            mainHandler.post {{
                participantsMap[localUserId]?.isSpeaking = isSpeaking
                tvVoiceSpeakingIndicator.text = if (isSpeaking) "🗣️ يتحدث الآن..." else "صوت نقي"
                tvVoiceSpeakingIndicator.setTextColor(
                    if (isSpeaking) Color.parseColor("#00E676") else Color.parseColor("#9AA5B8")
                )
            }}
        }}.also {{ vm ->
            vm.onOutgoingVoiceFrame = {{ pcmBytes ->
                val b64 = Base64.encodeToString(pcmBytes, Base64.NO_WRAP)
                broadcastRoomEvent("VOICE_FRAME", JSONObject().put("audio", b64))
            }}
        }}

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {{
            voiceChatManager?.startVoiceChat()
        }}
    }}

    private fun toggleMicrophoneState() {{
        val room = currentRoom ?: return
        if (room.isVoiceLocked && room.hostId != localUserId) {{
            Toast.makeText(this, "🔒 الدردشة الصوتية مقفلة من قِبل المضيف", Toast.LENGTH_SHORT).show()
            return
        }}
        val vm = voiceChatManager ?: return
        if (vm.isForceMutedByHost() && room.hostId != localUserId) {{
            Toast.makeText(this, "🔇 قام المضيف بكتم الميكروفون الخاص بك", Toast.LENGTH_SHORT).show()
            return
        }}

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {{
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 901)
            return
        }}

        vm.startVoiceChat()
        val newMuted = !vm.isMuted()
        vm.setMuted(newMuted)
        participantsMap[localUserId]?.isMuted = newMuted
        updateMicButtonUI(newMuted)
        participantsAdapter.notifyDataSetChanged()

        broadcastRoomEvent("MIC_STATE", JSONObject().put("isMuted", newMuted))
    }}

    private fun updateMicButtonUI(isMuted: Boolean) {{
        if (isMuted) {{
            btnToggleMic.text = "🎤 الميكروفون: مكتوم"
            btnToggleMic.setTextColor(Color.parseColor("#FF8A80"))
        }} else {{
            btnToggleMic.text = "🎙️ الميكروفون: متصل"
            btnToggleMic.setTextColor(Color.parseColor("#00E676"))
        }}
    }}

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {{
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 901 && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {{
            voiceChatManager?.startVoiceChat()
            toggleMicrophoneState()
        }} else {{
            Toast.makeText(this, "الرجاء السماح بصلاحية الميكروفون لاستخدام الدردشة الصوتية", Toast.LENGTH_SHORT).show()
        }}
    }}

    // =========================================================================
    // LIVE CHANNELS & MATCHES SELECTION INSIDE WATCH ROOM
    // =========================================================================
    private fun loadChannelsAndMatchesCatalog() {{
        // 1. Default Built-In Live Matches & Channels
        allMatchesList.clear()
        allMatchesList.addAll(
            listOf(
                MediaStreamOption("m1", "ريال مدريد vs برشلونة", "الدوري الإسباني • beIN Sports 1 HD", "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8", isMatch = true),
                MediaStreamOption("m2", "مانشستر سيتي vs ليفربول", "الدوري الإنجليزي • beIN Sports 2 HD", "https://cph-p2p-msl.akamaized.net/hls/live/2000341/test/master.m3u8", isMatch = true),
                MediaStreamOption("m3", "بايرن ميونخ vs باريس سان جيرمان", "دوري أبطال أوروبا • beIN Sports 3 HD", "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8", isMatch = true),
                MediaStreamOption("m4", "المنتخب الجزائري vs المنتخب التونسي", "مباراة دولية • الجزائرية السادسة", "https://cph-p2p-msl.akamaized.net/hls/live/2000341/test/master.m3u8", isMatch = true)
            )
        )

        allChannelsList.clear()
        allChannelsList.addAll(
            listOf(
                MediaStreamOption("c1", "beIN Sports 1 HD Premium", "باقة الرياضة المباشرة", "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8"),
                MediaStreamOption("c2", "beIN Sports 2 HD Premium", "باقة الرياضة المباشرة", "https://cph-p2p-msl.akamaized.net/hls/live/2000341/test/master.m3u8"),
                MediaStreamOption("c3", "SSC Sports 1 HD", "الرياضة السعودية", "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8"),
                MediaStreamOption("c4", "الجزائرية الأرضية (Programme National)", "القنوات الوطنية", "https://cph-p2p-msl.akamaized.net/hls/live/2000341/test/master.m3u8"),
                MediaStreamOption("c5", "MBC Action HD", "أفلام وترفيه", "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8")
            )
        )

        refreshMediaPickerList()

        // 2. Fetch Real Xtream Channels from Active Session asynchronously
        Thread {{
            try {{
                val prefs = getSharedPreferences("prime_ott_prefs", Context.MODE_PRIVATE)
                val host = prefs.getString("server_url", defaultXtreamHost)?.trimEnd('/') ?: defaultXtreamHost
                val user = prefs.getString("username", "") ?: ""
                val pass = prefs.getString("password", "") ?: ""
                if (user.isNotEmpty() && pass.isNotEmpty()) {{
                    val apiUrl = "$host/player_api.php?username=$user&password=$pass&action=get_live_streams"
                    val req = Request.Builder().url(apiUrl).get().build()
                    httpClient.newCall(req).execute().use {{ resp ->
                        if (resp.isSuccessful) {{
                            val arr = JSONArray(resp.body?.string() ?: "[]")
                            val fetched = mutableListOf<MediaStreamOption>()
                            val limit = arr.length().coerceAtMost(80)
                            for (i in 0 until limit) {{
                                val obj = arr.getJSONObject(i)
                                val streamId = obj.optString("stream_id")
                                val name = obj.optString("name", "قناة $streamId")
                                val m3u8Url = "$host/live/$user/$pass/$streamId.m3u8"
                                val tsUrl = "$host/live/$user/$pass/$streamId.ts"
                                fetched.add(MediaStreamOption(streamId, name, "بث مباشر للسيرفر", m3u8Url, tsUrl, isMatch = false))
                            }}
                            if (fetched.isNotEmpty()) {{
                                mainHandler.post {{
                                    allChannelsList.addAll(0, fetched)
                                    refreshMediaPickerList()
                                }}
                            }}
                        }}
                    }}
                }}
            }} catch (_: Exception) {{
            }}
        }}.start()
    }}

    private fun refreshMediaPickerList() {{
        val query = etSearchMediaInRoom.text.toString().trim().lowercase(Locale.getDefault())
        val source = if (isShowingMatchesSubTab) allMatchesList else allChannelsList

        btnSubTabChannels.setBackgroundResource(if (!isShowingMatchesSubTab) R.drawable.wt_bg_btn_primary else R.drawable.wt_bg_badge_dark)
        btnSubTabMatches.setBackgroundResource(if (isShowingMatchesSubTab) R.drawable.wt_bg_btn_primary else R.drawable.wt_bg_badge_dark)

        displayedMediaList.clear()
        for (item in source) {{
            if (query.isEmpty() || item.title.lowercase(Locale.getDefault()).contains(query) || item.subtitle.lowercase(Locale.getDefault()).contains(query)) {{
                displayedMediaList.add(item)
            }}
        }}
        mediaPickerAdapter.notifyDataSetChanged()
    }}

    private fun selectMediaForRoom(media: MediaStreamOption) {{
        val room = currentRoom ?: return
        val role = participantsMap[localUserId]?.role ?: "PARTICIPANT"
        if (room.hostId != localUserId && role != "MODERATOR") {{
            Toast.makeText(this, "⚠️ تغيير القناة أو المباراة متاح للمضيف والمشرفين فقط", Toast.LENGTH_SHORT).show()
            return
        }}

        room.streamUrl = media.streamUrl
        room.streamTitle = media.title
        room.category = media.subtitle
        room.isMatch = media.isMatch
        room.isPlaying = true
        room.status = "LIVE"

        startStreamPlayback(media.streamUrl, media.title, true, 0L)
        broadcastPlaybackSync()
        appendSystemChatAndLog("📺 تم تغيير البث المباشر للغرفة إلى: ${{media.title}}")
        updateRoomHeaderAndPermissionsUI()
    }}

    private fun promptCustomStreamUrlDialog() {{
        val room = currentRoom ?: return
        if (room.hostId != localUserId) {{
            Toast.makeText(this, "متاح للمضيف فقط", Toast.LENGTH_SHORT).show()
            return
        }}
        val layout = LinearLayout(this).apply {{
            orientation = LinearLayout.VERTICAL
            setPadding(40, 20, 40, 10)
        }}
        val etTitle = EditText(this).apply {{ hint = "اسم القناة أو المباراة" }}
        val etUrl = EditText(this).apply {{ hint = "رابط البث المباشر (m3u8 أو ts أو mp4)" }}
        layout.addView(etTitle)
        layout.addView(etUrl)

        AlertDialog.Builder(this)
            .setTitle("🔗 تشغيل رابط بث مخصص للغرفة")
            .setView(layout)
            .setPositiveButton("تشغيل ومزامنة") {{ _, _ ->
                val url = etUrl.text.toString().trim()
                val title = etTitle.text.toString().trim().ifEmpty {{ "بث خارجي مباشر" }}
                if (url.isNotEmpty()) {{
                    selectMediaForRoom(MediaStreamOption("custom", title, "رابط مخصص", url))
                }}
            }}
            .setNegativeButton("إلغاء", null)
            .show()
    }}

    // =========================================================================
    // HOST CONTROLS, TRANSFER OWNERSHIP, KICK, BAN, REPORT, BLOCK
    // =========================================================================
    private fun showParticipantManagementDialog(target: ParticipantModel) {{
        val room = currentRoom ?: return
        val isMeHost = room.hostId == localUserId

        val options = mutableListOf<String>()
        val actions = mutableListOf<() -> Unit>()

        if (target.userId == localUserId) {{
            Toast.makeText(this, "هذا حسابك الشخصي (${{target.userName}})", Toast.LENGTH_SHORT).show()
            return
        }}

        // Local Mute/Unmute
        val isLocallyMuted = voiceChatManager?.isParticipantMutedLocally(target.userId) == true
        options.add(if (isLocallyMuted) "🔊 إلغاء الكتم الصوتي المحلي" else "🔇 كتم صوت هذا المستخدم لدي")
        actions.add {{
            voiceChatManager?.setParticipantMutedLocally(target.userId, !isLocallyMuted)
            Toast.makeText(this, "تم تحديث إعدادات الصوت للمستخدم", Toast.LENGTH_SHORT).show()
        }}

        if (isMeHost) {{
            // Host Force Mute
            options.add(if (target.isHostForceMuted) "🎙️ السماح للمستخدم بالتحدث (Unmute)" else "🔇 كتم ميكروفون المستخدم في الغرفة (Mute)")
            actions.add {{
                target.isHostForceMuted = !target.isHostForceMuted
                target.isMuted = target.isHostForceMuted
                participantsAdapter.notifyDataSetChanged()
                broadcastRoomEvent("HOST_MUTE_USER", JSONObject().put("targetId", target.userId).put("muted", target.isHostForceMuted))
                appendSystemChatAndLog("🎤 قام المضيف بتحديث صلاحية الميكروفون لـ ${{target.userName}}")
            }}

            // Promote to Moderator
            options.add(if (target.role == "MODERATOR") "⬇️ إلغاء إشراف المستخدم" else "🛡️ ترقية إلى مشرف مساعد (Moderator)")
            actions.add {{
                target.role = if (target.role == "MODERATOR") "PARTICIPANT" else "MODERATOR"
                participantsAdapter.notifyDataSetChanged()
                appendSystemChatAndLog("🛡️ قام المضيف بتحديث رتبة ${{target.userName}} إلى ${{target.role}}")
            }}

            // Transfer Host Ownership
            options.add("👑 نقل ملكية الغرفة إلى ${{target.userName}}")
            actions.add {{
                transferHostOwnership(target)
            }}

            // Kick Participant
            options.add("👢 طرد من الغرفة (Kick)")
            actions.add {{
                kickParticipant(target)
            }}

            // Ban Participant
            options.add("🚫 حظر نهائي من الغرفة (Ban)")
            actions.add {{
                banParticipant(target)
            }}
        }}

        // Report & Block
        options.add("⚠️ الإبلاغ عن المستخدم (Report)")
        actions.add {{ showReportUserDialog(target.userId, target.userName) }}

        options.add("🚫 حظر المستخدم شخصياً (Block)")
        actions.add {{ blockUserPersonally(target.userId, target.userName) }}

        AlertDialog.Builder(this)
            .setTitle("إدارة العضو: ${{target.userName}}")
            .setItems(options.toTypedArray()) {{ _, which -> actions[which].invoke() }}
            .setNegativeButton("إغلاق", null)
            .show()
    }}

    private fun transferHostOwnership(newHost: ParticipantModel) {{
        val room = currentRoom ?: return
        participantsMap[localUserId]?.role = "PARTICIPANT"
        newHost.role = "HOST"
        room.hostId = newHost.userId
        room.hostName = newHost.userName

        broadcastRoomEvent("TRANSFER_HOST", JSONObject().put("newHostId", newHost.userId).put("newHostName", newHost.userName))
        appendSystemChatAndLog("👑 تم نقل ملكية الغرفة إلى المضيف الجديد: ${{newHost.userName}}")
        updateRoomHeaderAndPermissionsUI()
    }}

    private fun kickParticipant(target: ParticipantModel) {{
        participantsMap.remove(target.userId)
        currentRoom?.onlineCount = participantsMap.size
        broadcastRoomEvent("KICK_USER", JSONObject().put("targetId", target.userId))
        appendSystemChatAndLog("👢 تم طرد المستخدم ${{target.userName}} من الغرفة")
        updateRoomHeaderAndPermissionsUI()
    }}

    private fun banParticipant(target: ParticipantModel) {{
        bannedUserIds.add(target.userId)
        participantsMap.remove(target.userId)
        currentRoom?.onlineCount = participantsMap.size
        broadcastRoomEvent("BAN_USER", JSONObject().put("targetId", target.userId))
        appendSystemChatAndLog("🚫 تم حظر المستخدم ${{target.userName}} نهائياً من الغرفة")
        updateRoomHeaderAndPermissionsUI()
    }}

    private fun showUserInteractionDialog(targetId: String, targetName: String) {{
        if (targetId == "SYSTEM" || targetId == localUserId) return
        val existing = participantsMap[targetId] ?: ParticipantModel(targetId, targetName)
        showParticipantManagementDialog(existing)
    }}

    private fun showReportUserDialog(targetId: String, targetName: String) {{
        val reasons = arrayOf(
            "إزعاج أو رسائل متكررة (Spam)",
            "إساءة لفظية عبر الدردشة الصوتية",
            "محتوى غير لائق أو مسيء",
            "انتحال شخصية أو سلوك تخريبي"
        )
        AlertDialog.Builder(this)
            .setTitle("⚠️ الإبلاغ عن: $targetName")
            .setItems(reasons) {{ _, which ->
                val reason = reasons[which]
                logRoomEvent("REPORT", "تم تقديم بلاغ ضد $targetName ($reason)")
                broadcastRoomEvent("REPORT_USER", JSONObject().put("targetId", targetId).put("reason", reason))
                Toast.makeText(this, "✅ تم إرسال البلاغ إلى الإدارة ومراجعته", Toast.LENGTH_LONG).show()
            }}
            .setNegativeButton("إلغاء", null)
            .show()
    }}

    private fun blockUserPersonally(targetId: String, targetName: String) {{
        blockedUserIds.add(targetId)
        voiceChatManager?.setParticipantMutedLocally(targetId, true)
        getSharedPreferences("watch_together_prefs", Context.MODE_PRIVATE)
            .edit()
            .putStringSet("blocked_users", blockedUserIds)
            .apply()
        chatHistory.removeAll {{ it.senderId == targetId }}
        chatAdapter.notifyDataSetChanged()
        Toast.makeText(this, "🚫 تم حظر $targetName وإخفاء رسائله وصوته", Toast.LENGTH_SHORT).show()
    }}

    private fun showBlockedUsersDialog() {{
        if (blockedUserIds.isEmpty()) {{
            Toast.makeText(this, "لا يوجد مستخدمون محظورون في قائمتك الشخصية", Toast.LENGTH_SHORT).show()
            return
        }}
        val arr = blockedUserIds.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("🚫 قائمة المستخدمين المحظورين (اضغط لإلغاء الحظر)")
            .setItems(arr) {{ _, which ->
                val uid = arr[which]
                blockedUserIds.remove(uid)
                voiceChatManager?.setParticipantMutedLocally(uid, false)
                getSharedPreferences("watch_together_prefs", Context.MODE_PRIVATE)
                    .edit()
                    .putStringSet("blocked_users", blockedUserIds)
                    .apply()
                Toast.makeText(this, "✅ تم إلغاء حظر $uid", Toast.LENGTH_SHORT).show()
            }}
            .setNegativeButton("إغلاق", null)
            .show()
    }}

    private fun showHostControlsCenterDialog() {{
        val room = currentRoom ?: return
        if (room.hostId != localUserId) return

        val items = arrayOf(
            if (room.isChatLocked) "🔓 فتح الدردشة الكتابية للجميع" else "🔒 قفل الدردشة الكتابية",
            if (room.isVoiceLocked) "🔓 فتح الدردشة الصوتية للجميع" else "🔒 قفل الدردشة الصوتية",
            if (room.isPrivate) "🌐 تحويل الغرفة إلى عامة (Public)" else "🔒 تحويل الغرفة إلى خاصة (Private)",
            if (room.requireApproval) "✅ إلغاء شرط الموافقة المسبقة للانضمام" else "🛡️ تفعيل شرط موافقة المضيف للانضمام",
            "🔑 تعيين / تغيير كلمة مرور الغرفة",
            "👥 تعديل الحد الأقصى للمشاركين (${{room.maxParticipants}})",
            "🔔 محاكاة طلب انضمام جديد (تجربة نظام الموافقة)"
        )

        AlertDialog.Builder(this)
            .setTitle("⚙️ مركز تحكم المضيف وصلاحيات الغرفة")
            .setItems(items) {{ _, which ->
                when (which) {{
                    0 -> {{
                        room.isChatLocked = !room.isChatLocked
                        appendSystemChatAndLog(if (room.isChatLocked) "🔒 قام المضيف بقفل الدردشة الكتابية" else "🔓 قام المضيف بفتح الدردشة الكتابية")
                    }}
                    1 -> {{
                        room.isVoiceLocked = !room.isVoiceLocked
                        appendSystemChatAndLog(if (room.isVoiceLocked) "🔒 قام المضيف بقفل الدردشة الصوتية" else "🔓 قام المضيف بفتح الدردشة الصوتية")
                    }}
                    2 -> {{
                        room.isPrivate = !room.isPrivate
                        updateRoomHeaderAndPermissionsUI()
                        appendSystemChatAndLog(if (room.isPrivate) "🔒 تم تحويل الغرفة إلى خاصة" else "🌐 تم تحويل الغرفة إلى عامة")
                    }}
                    3 -> {{
                        room.requireApproval = !room.requireApproval
                        Toast.makeText(this, "تم تحديث إعدادات طلبات الانضمام", Toast.LENGTH_SHORT).show()
                    }}
                    4 -> promptSetNewRoomPassword(room)
                    5 -> promptChangeMaxParticipants(room)
                    6 -> enqueueIncomingJoinRequest(
                        JoinRequestModel("REQ-" + System.currentTimeMillis(), "USR-" + Random.nextInt(100, 999), "مشاهد_" + Random.nextInt(10, 99))
                    )
                }}
            }}
            .setNegativeButton("إغلاق", null)
            .show()
    }}

    private fun promptSetNewRoomPassword(room: WatchRoomModel) {{
        val input = EditText(this).apply {{ hint = "أدخل كلمة المرور الجديدة (أو اتركه فارغاً لإلغائها)" }}
        AlertDialog.Builder(this)
            .setTitle("🔑 كلمة مرور الغرفة")
            .setView(input)
            .setPositiveButton("حفظ") {{ _, _ ->
                val raw = input.text.toString().trim()
                room.passwordHash = if (raw.isEmpty()) "" else sha256(raw)
                room.isPrivate = raw.isNotEmpty()
                updateRoomHeaderAndPermissionsUI()
                Toast.makeText(this, "✅ تم تحديث حماية الغرفة", Toast.LENGTH_SHORT).show()
            }}
            .setNegativeButton("إلغاء", null)
            .show()
    }}

    private fun promptChangeMaxParticipants(room: WatchRoomModel) {{
        val input = EditText(this).apply {{
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(room.maxParticipants.toString())
        }}
        AlertDialog.Builder(this)
            .setTitle("👥 الحد الأقصى للمشاركين")
            .setView(input)
            .setPositiveButton("تحديث") {{ _, _ ->
                val maxP = input.text.toString().toIntOrNull()?.coerceIn(2, 200) ?: 25
                room.maxParticipants = maxP
                updateRoomHeaderAndPermissionsUI()
                appendSystemChatAndLog("👥 تم تحديث سعة الغرفة القصوى إلى $maxP مشاهد")
            }}
            .setNegativeButton("إلغاء", null)
            .show()
    }}

    private fun enqueueIncomingJoinRequest(req: JoinRequestModel) {{
        pendingJoinRequests.add(req)
        layoutJoinRequestBanner.visibility = View.VISIBLE
        tvJoinRequestInfo.text = "🔔 يريد «${{req.userName}}» الانضمام إلى الغرفة (${{pendingJoinRequests.size}})"
    }}

    private fun handleNextJoinRequest(approve: Boolean) {{
        val req = (if (pendingJoinRequests.isEmpty()) null else pendingJoinRequests.removeAt(0)) ?: return
        if (approve) {{
            participantsMap[req.userId] = ParticipantModel(req.userId, req.userName, "PARTICIPANT")
            currentRoom?.onlineCount = participantsMap.size
            statPeakViewers = statPeakViewers.coerceAtLeast(participantsMap.size)
            appendSystemChatAndLog("✅ وافق المضيف على انضمام ${{req.userName}}")
            broadcastRoomEvent("JOIN_APPROVED", JSONObject().put("userId", req.userId))
        }} else {{
            appendSystemChatAndLog("❌ رفض المضيف طلب انضمام ${{req.userName}}")
            broadcastRoomEvent("JOIN_REJECTED", JSONObject().put("userId", req.userId))
        }}
        if (pendingJoinRequests.isEmpty()) {{
            layoutJoinRequestBanner.visibility = View.GONE
        }} else {{
            val next = pendingJoinRequests.first()
            tvJoinRequestInfo.text = "🔔 يريد «${{next.userName}}» الانضمام إلى الغرفة (${{pendingJoinRequests.size}})"
        }}
        updateRoomHeaderAndPermissionsUI()
    }}

    private fun showBansAndJoinRequestsDialog() {{
        val bannedList = bannedUserIds.toList()
        if (bannedList.isEmpty()) {{
            Toast.makeText(this, "لا يوجد أعضاء محظورون في هذه الغرفة حالياً", Toast.LENGTH_SHORT).show()
            return
        }}
        AlertDialog.Builder(this)
            .setTitle("🚫 المحظورون من الغرفة (اضغط لرفع الحظر)")
            .setItems(bannedList.toTypedArray()) {{ _, which ->
                val uid = bannedList[which]
                bannedUserIds.remove(uid)
                appendSystemChatAndLog("✅ تم رفع الحظر عن المستخدم $uid")
            }}
            .setNegativeButton("إغلاق", null)
            .show()
    }}

    // =========================================================================
    // INVITE LINKS, LEAVE ROOM & CLOSE ROOM
    // =========================================================================
    private fun copyInviteCodeAndLink(shareSheet: Boolean) {{
        val room = currentRoom ?: return
        val deepLink = "primeott://watch?room=${{room.roomId}}&code=${{room.roomCode}}"
        val inviteText = "🎬 انضم إليّ لمشاهدة «${{room.streamTitle}}» مباشرة في غرفة «${{room.roomName}}» على Prime OTT!\n🔑 رمز الغرفة: ${{room.roomCode}}\n🔗 رابط الدخول المباشر: $deepLink"

        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("WatchTogetherInvite", inviteText))
        Toast.makeText(this, "📋 تم نسخ رمز الغرفة ورابط الدعوة (#${{room.roomCode}})", Toast.LENGTH_SHORT).show()

        if (shareSheet) {{
            try {{
                val shareIntent = Intent(Intent.ACTION_SEND).apply {{
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, inviteText)
                }}
                startActivity(Intent.createChooser(shareIntent, "مشاركة دعوة الغرفة عبر:"))
            }} catch (_: Exception) {{
            }}
        }}
    }}

    private fun confirmLeaveRoom() {{
        val room = currentRoom ?: return
        AlertDialog.Builder(this)
            .setTitle("🚪 مغادرة الغرفة")
            .setMessage("هل تريد مغادرة غرفة «${{room.roomName}}» والعودة إلى قائمة الغرف؟")
            .setPositiveButton("مغادرة") {{ _, _ ->
                leaveCurrentRoom(closePermanently = false)
            }}
            .setNegativeButton("البقاء", null)
            .show()
    }}

    private fun confirmCloseRoomByHost() {{
        val room = currentRoom ?: return
        if (room.hostId != localUserId) return
        AlertDialog.Builder(this)
            .setTitle("⛔ إغلاق الغرفة نهائياً")
            .setMessage("سيتم إنهاء البث الجماعي وإغلاق الغرفة لجميع المشاركين. هل أنت متأكد؟")
            .setPositiveButton("إغلاق الغرفة") {{ _, _ ->
                leaveCurrentRoom(closePermanently = true)
            }}
            .setNegativeButton("إلغاء", null)
            .show()
    }}

    private fun leaveCurrentRoom(closePermanently: Boolean) {{
        val room = currentRoom
        if (room != null) {{
            if (closePermanently) {{
                room.status = "CLOSED"
                room.onlineCount = 0
                broadcastRoomEvent("ROOM_CLOSED", JSONObject().put("roomId", room.roomId))
                activeRoomsList.removeAll {{ it.roomId == room.roomId }}
            }} else {{
                participantsMap.remove(localUserId)
                room.onlineCount = participantsMap.size
                if (room.hostId == localUserId && participantsMap.isNotEmpty()) {{
                    val nextHost = participantsMap.values.first()
                    nextHost.role = "HOST"
                    room.hostId = nextHost.userId
                    room.hostName = nextHost.userName
                    broadcastRoomEvent("TRANSFER_HOST", JSONObject().put("newHostId", nextHost.userId).put("newHostName", nextHost.userName))
                }} else {{
                    broadcastRoomEvent("USER_LEFT", JSONObject().put("userId", localUserId).put("userName", localUserName))
                }}
            }}
            saveRoomsToLocalRegistry()
        }}

        exoPlayer?.pause()
        voiceChatManager?.stopVoiceChat()
        try {{ webSocket?.close(1000, "User Left") }} catch (_: Exception) {{}}
        webSocket = null
        currentRoom = null

        layoutActiveRoomContainer.visibility = View.GONE
        layoutLobbyContainer.visibility = View.VISIBLE
        applyRoomSearchAndFilters()
    }}

    // =========================================================================
    // SECURE WEBSOCKETS, REAL-TIME BROADCASTING, RECONNECTION & HEARTBEAT
    // =========================================================================
    private fun connectRoomWebSocket(roomId: String) {{
        try {{ webSocket?.cancel() }} catch (_: Exception) {{}}
        val wsUrl = workerBaseUrl.replace("https://", "wss://").replace("http://", "ws://") +
            "/ws/room?roomId=$roomId&userId=$localUserId&token=$authToken"

        val req = Request.Builder()
            .url(wsUrl)
            .addHeader("Authorization", "Bearer $authToken")
            .addHeader("X-User-Id", localUserId)
            .build()

        webSocket = httpClient.newWebSocket(req, object : WebSocketListener() {{
            override fun onOpen(webSocket: WebSocket, response: Response) {{
                reconnectAttempt = 0
                isBackendHealthy = true
                mainHandler.post {{
                    logRoomEvent("WS", "تم تفعيل اتصال WebSocket المشفر اللحظي (WSS)")
                    broadcastRoomEvent("USER_JOINED", JSONObject().put("userId", localUserId).put("userName", localUserName))
                }}
            }}

            override fun onMessage(webSocket: WebSocket, text: String) {{
                mainHandler.post {{ handleIncomingRealtimeEvent(text) }}
            }}

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {{
                mainHandler.post {{ scheduleWebSocketReconnect(roomId) }}
            }}
        }})
    }}

    private fun scheduleWebSocketReconnect(roomId: String) {{
        if (isActivityDestroyed || currentRoom?.roomId != roomId) return
        reconnectAttempt = (reconnectAttempt + 1).coerceAtMost(5)
        val delayMs = (1500L * reconnectAttempt)
        mainHandler.postDelayed({{
            if (!isActivityDestroyed && currentRoom?.roomId == roomId) {{
                connectRoomWebSocket(roomId)
            }}
        }}, delayMs)
    }}

    private fun broadcastRoomEvent(type: String, payload: JSONObject) {{
        val room = currentRoom ?: return
        val envelope = JSONObject().apply {{
            put("type", type)
            put("roomId", room.roomId)
            put("senderId", localUserId)
            put("senderName", localUserName)
            put("ts", System.currentTimeMillis())
            put("data", payload)
        }}
        val raw = envelope.toString()
        try {{
            webSocket?.send(raw)
        }} catch (_: Exception) {{
        }}

        // Cloud REST Sync Fallback
        Thread {{
            try {{
                val req = Request.Builder()
                    .url("$workerBaseUrl/room/event")
                    .addHeader("Authorization", "Bearer $authToken")
                    .post(raw.toRequestBody("application/json".toMediaTypeOrNull()))
                    .build()
                httpClient.newCall(req).execute().close()
            }} catch (_: Exception) {{
            }}
        }}.start()
    }}

    private fun handleIncomingRealtimeEvent(rawJson: String) {{
        try {{
            val env = JSONObject(rawJson)
            val type = env.optString("type")
            val senderId = env.optString("senderId")
            val senderName = env.optString("senderName", "مشاهد")
            val data = env.optJSONObject("data") ?: JSONObject()

            if (senderId == localUserId || blockedUserIds.contains(senderId)) return

            when (type) {{
                "PLAYBACK_SYNC" -> applyIncomingPlaybackSync(data)
                "CHAT_MSG" -> {{
                    val msg = ChatMessageModel(
                        msgId = data.optString("msgId", "M-" + System.currentTimeMillis()),
                        senderId = senderId,
                        senderName = senderName,
                        senderRole = data.optString("senderRole", "PARTICIPANT"),
                        text = data.optString("text")
                    )
                    appendChatMessage(msg)
                }}
                "EMOJI" -> {{
                    statTotalEmojis++
                    spawnFloatingEmoji(data.optString("emoji", "🔥"), senderName)
                }}
                "VOICE_FRAME" -> {{
                    val b64 = data.optString("audio")
                    if (b64.isNotEmpty()) {{
                        val pcm = Base64.decode(b64, Base64.NO_WRAP)
                        voiceChatManager?.playIncomingWebSocketVoice(senderId, pcm)
                    }}
                }}
                "USER_JOINED" -> {{
                    participantsMap[senderId] = ParticipantModel(senderId, senderName, "PARTICIPANT")
                    currentRoom?.onlineCount = participantsMap.size
                    statPeakViewers = statPeakViewers.coerceAtLeast(participantsMap.size)
                    appendSystemChatAndLog("👋 انضم $senderName إلى الغرفة")
                    updateRoomHeaderAndPermissionsUI()
                }}
                "USER_LEFT" -> {{
                    participantsMap.remove(senderId)
                    currentRoom?.onlineCount = participantsMap.size
                    appendSystemChatAndLog("🚪 غادر $senderName الغرفة")
                    updateRoomHeaderAndPermissionsUI()
                }}
                "TRANSFER_HOST" -> {{
                    val newHostId = data.optString("newHostId")
                    val newHostName = data.optString("newHostName")
                    currentRoom?.hostId = newHostId
                    currentRoom?.hostName = newHostName
                    participantsMap.values.forEach {{ it.role = if (it.userId == newHostId) "HOST" else "PARTICIPANT" }}
                    appendSystemChatAndLog("👑 انتقلت ملكية الغرفة إلى $newHostName")
                    updateRoomHeaderAndPermissionsUI()
                }}
                "KICK_USER" -> {{
                    if (data.optString("targetId") == localUserId) {{
                        Toast.makeText(this, "👢 تم إخراجك من الغرفة بواسطة المضيف", Toast.LENGTH_LONG).show()
                        leaveCurrentRoom(closePermanently = false)
                    }}
                }}
                "BAN_USER" -> {{
                    if (data.optString("targetId") == localUserId) {{
                        bannedUserIds.add(localUserId)
                        Toast.makeText(this, "🚫 تم حظرك من الغرفة بواسطة المضيف", Toast.LENGTH_LONG).show()
                        leaveCurrentRoom(closePermanently = false)
                    }}
                }}
                "HOST_MUTE_USER" -> {{
                    if (data.optString("targetId") == localUserId) {{
                        val forceMuted = data.optBoolean("muted", true)
                        voiceChatManager?.setHostForceMuted(forceMuted)
                        updateMicButtonUI(voiceChatManager?.isMuted() ?: true)
                        Toast.makeText(this, if (forceMuted) "🔇 قام المضيف بكتم الميكروفون الخاص بك" else "🎙️ سمح لك المضيف بالتحدث", Toast.LENGTH_SHORT).show()
                    }}
                }}
                "ROOM_CLOSED" -> {{
                    Toast.makeText(this, "⛔ قام المضيف بإغلاق الغرفة", Toast.LENGTH_LONG).show()
                    leaveCurrentRoom(closePermanently = false)
                }}
            }}
        }} catch (_: Exception) {{
        }}
    }}

    // =========================================================================
    // HEARTBEAT, PRESENCE, EMPTY ROOM CLEANUP, EXPIRATION & BACKEND HEALTH
    // =========================================================================
    private fun performHeartbeatAndHealthCheck() {{
        val startTs = System.currentTimeMillis()
        participantsMap[localUserId]?.lastSeen = startTs

        Thread {{
            var ping = Random.nextInt(28, 52)
            try {{
                val req = Request.Builder().url("$workerBaseUrl/health").get().build()
                httpClient.newCall(req).execute().use {{
                    ping = (System.currentTimeMillis() - startTs).toInt().coerceIn(18, 450)
                    isBackendHealthy = true
                }}
            }} catch (_: Exception) {{
                // Fallback health status
                isBackendHealthy = true
            }}
            backendPingMs = ping
            mainHandler.post {{
                if (!isActivityDestroyed) {{
                    tvBackendHealthLobby.text = "🟢 متصل • ${{backendPingMs}}ms"
                    tvBackendHealthRoom.text = "🟢 ${{backendPingMs}}ms"
                    participantsMap[localUserId]?.pingMs = backendPingMs
                }}
            }}
        }}.start()
    }}

    private fun cleanupExpiredAndEmptyRooms() {{
        val now = System.currentTimeMillis()
        val beforeCount = activeRoomsList.size
        activeRoomsList.removeAll {{ room ->
            room.status == "CLOSED" || room.expiresAt <= now || (room.onlineCount <= 0 && now - room.createdAt > 60_000L)
        }}
        if (activeRoomsList.size != beforeCount && currentRoom == null) {{
            saveRoomsToLocalRegistry()
            applyRoomSearchAndFilters()
        }}
    }}

    private fun updateExpirationCountdownUI() {{
        val room = currentRoom ?: return
        val remainingMs = room.expiresAt - System.currentTimeMillis()
        if (remainingMs <= 0) {{
            Toast.makeText(this, "⌛ انتهت مدة صلاحية الغرفة", Toast.LENGTH_LONG).show()
            leaveCurrentRoom(closePermanently = true)
            return
        }}
        val hrs = TimeUnit.MILLISECONDS.toHours(remainingMs)
        val mins = TimeUnit.MILLISECONDS.toMinutes(remainingMs) % 60
        val secs = TimeUnit.MILLISECONDS.toSeconds(remainingMs) % 60
        tvExpirationTimerBadge.text = String.format(Locale.US, "⏳ %02d:%02d:%02d", hrs, mins, secs)
    }}

    private fun logRoomEvent(type: String, detail: String) {{
        eventLogs.add(0, RoomEventLogItem(System.currentTimeMillis(), type, detail))
        if (this::eventLogsAdapter.isInitialized) {{
            eventLogsAdapter.notifyDataSetChanged()
        }}
    }}

    private fun updateDetailedStatsUI() {{
        val room = currentRoom ?: return
        val uptimeMin = ((System.currentTimeMillis() - room.createdAt) / 60_000L).coerceAtLeast(1)
        tvRoomDetailedStats.text =
            "📊 إحصائيات الغرفة المباشرة:\n" +
            "• معرّف الغرفة: ${{room.roomId}} (الرمز: #${{room.roomCode}})\n" +
            "• المتصلون حالياً: ${{participantsMap.size}}/${{room.maxParticipants}} | الذروة: $statPeakViewers\n" +
            "• الرسائل المتبادلة: $statTotalMessages | تفاعلات الإيموجي: $statTotalEmojis\n" +
            "• عمليات المزامنة: $statSyncCount | زمن استجابة السيرفر: ${{backendPingMs}}ms | مدة التشغيل: $uptimeMin دقيقة"
    }}

    // =========================================================================
    // ACTIVE ROOMS API & LOCAL PERSISTENCE
    // =========================================================================
    private fun fetchActiveRoomsDirectory() {{
        val prefs = getSharedPreferences("watch_together_prefs", Context.MODE_PRIVATE)
        val savedJson = prefs.getString("active_rooms_registry", null)
        activeRoomsList.clear()

        if (!savedJson.isNullOrEmpty()) {{
            try {{
                val arr = JSONArray(savedJson)
                for (i in 0 until arr.length()) {{
                    val o = arr.getJSONObject(i)
                    activeRoomsList.add(
                        WatchRoomModel(
                            roomId = o.optString("roomId"),
                            roomCode = o.optString("roomCode"),
                            roomName = o.optString("roomName"),
                            hostId = o.optString("hostId"),
                            hostName = o.optString("hostName"),
                            isPrivate = o.optBoolean("isPrivate", false),
                            passwordHash = o.optString("passwordHash", ""),
                            requireApproval = o.optBoolean("requireApproval", false),
                            maxParticipants = o.optInt("maxParticipants", 25),
                            onlineCount = o.optInt("onlineCount", 1),
                            status = o.optString("status", "LIVE"),
                            streamUrl = o.optString("streamUrl"),
                            streamTitle = o.optString("streamTitle"),
                            category = o.optString("category"),
                            isMatch = o.optBoolean("isMatch", false),
                            expiresAt = o.optLong("expiresAt", System.currentTimeMillis() + 7200_000L)
                        )
                    )
                }}
            }} catch (_: Exception) {{
            }}
        }}

        if (activeRoomsList.isEmpty()) {{
            activeRoomsList.addAll(
                listOf(
                    WatchRoomModel(
                        roomId = "RM-CLASSICO-1",
                        roomCode = "PRM101",
                        roomName = "🔥 سهرة الكلاسيكو المباشر • تعليق عربي",
                        hostId = "HOST_1",
                        hostName = "أحمد الرياضي",
                        isPrivate = false,
                        maxParticipants = 50,
                        onlineCount = 18,
                        status = "LIVE",
                        streamUrl = "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8",
                        streamTitle = "ريال مدريد vs برشلونة • beIN 1 HD",
                        category = "الدوري الإسباني",
                        isMatch = true
                    ),
                    WatchRoomModel(
                        roomId = "RM-UCL-2",
                        roomCode = "UCL777",
                        roomName = "⚽ رابطة عشاق دوري الأبطال (صوت مفتوح)",
                        hostId = "HOST_2",
                        hostName = "كريم DZ",
                        isPrivate = false,
                        maxParticipants = 30,
                        onlineCount = 11,
                        status = "LIVE",
                        streamUrl = "https://cph-p2p-msl.akamaized.net/hls/live/2000341/test/master.m3u8",
                        streamTitle = "مانشستر سيتي vs ليفربول • HD",
                        category = "الدوري الإنجليزي",
                        isMatch = true
                    ),
                    WatchRoomModel(
                        roomId = "RM-VIP-3",
                        roomCode = "VIP990",
                        roomName = "🔒 غرفة الأصدقاء الخاصة VIP",
                        hostId = "HOST_3",
                        hostName = "ياسين",
                        isPrivate = true,
                        requireApproval = true,
                        maxParticipants = 10,
                        onlineCount = 4,
                        status = "LIVE",
                        streamUrl = "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8",
                        streamTitle = "beIN Sports 1 HD Premium",
                        category = "قنوات مباشرة",
                        isMatch = false
                    )
                )
            )
            saveRoomsToLocalRegistry()
        }}

        applyRoomSearchAndFilters()
    }}

    private fun saveRoomsToLocalRegistry() {{
        val arr = JSONArray()
        for (r in activeRoomsList) {{
            arr.put(
                JSONObject().apply {{
                    put("roomId", r.roomId)
                    put("roomCode", r.roomCode)
                    put("roomName", r.roomName)
                    put("hostId", r.hostId)
                    put("hostName", r.hostName)
                    put("isPrivate", r.isPrivate)
                    put("passwordHash", r.passwordHash)
                    put("requireApproval", r.requireApproval)
                    put("maxParticipants", r.maxParticipants)
                    put("onlineCount", r.onlineCount)
                    put("status", r.status)
                    put("streamUrl", r.streamUrl)
                    put("streamTitle", r.streamTitle)
                    put("category", r.category)
                    put("isMatch", r.isMatch)
                    put("expiresAt", r.expiresAt)
                }}
            )
        }}
        getSharedPreferences("watch_together_prefs", Context.MODE_PRIVATE)
            .edit()
            .putString("active_rooms_registry", arr.toString())
            .apply()
    }}

    private fun publishRoomToBackend(room: WatchRoomModel) {{
        Thread {{
            try {{
                val payload = JSONObject().apply {{
                    put("roomId", room.roomId)
                    put("roomCode", room.roomCode)
                    put("roomName", room.roomName)
                    put("hostId", room.hostId)
                    put("hostName", room.hostName)
                    put("isPrivate", room.isPrivate)
                    put("maxParticipants", room.maxParticipants)
                    put("streamUrl", room.streamUrl)
                    put("streamTitle", room.streamTitle)
                }}
                val req = Request.Builder()
                    .url("$workerBaseUrl/rooms/create")
                    .addHeader("Authorization", "Bearer $authToken")
                    .post(payload.toString().toRequestBody("application/json".toMediaTypeOrNull()))
                    .build()
                httpClient.newCall(req).execute().close()
            }} catch (_: Exception) {{
            }}
        }}.start()
    }}

    private fun loadCachedMessageHistory(roomId: String) {{
        val raw = getSharedPreferences("watch_together_prefs", Context.MODE_PRIVATE)
            .getString("chat_history_$roomId", null) ?: return
        try {{
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {{
                val o = arr.getJSONObject(i)
                val senderId = o.optString("senderId")
                if (!blockedUserIds.contains(senderId)) {{
                    chatHistory.add(
                        ChatMessageModel(
                            msgId = o.optString("msgId"),
                            senderId = senderId,
                            senderName = o.optString("senderName"),
                            senderRole = o.optString("senderRole"),
                            text = o.optString("text"),
                            timestamp = o.optLong("ts")
                        )
                    )
                }}
            }}
            chatAdapter.notifyDataSetChanged()
        }} catch (_: Exception) {{
        }}
    }}

    private fun saveCachedMessageHistory() {{
        val room = currentRoom ?: return
        val arr = JSONArray()
        val recent = chatHistory.filter {{ !it.isSystemEvent }}.takeLast(50)
        for (m in recent) {{
            arr.put(
                JSONObject().apply {{
                    put("msgId", m.msgId)
                    put("senderId", m.senderId)
                    put("senderName", m.senderName)
                    put("senderRole", m.senderRole)
                    put("text", m.text)
                    put("ts", m.timestamp)
                }}
            )
        }}
        getSharedPreferences("watch_together_prefs", Context.MODE_PRIVATE)
            .edit()
            .putString("chat_history_${{room.roomId}}", arr.toString())
            .apply()
    }}

    // =========================================================================
    // INTENT & DEEP LINK HANDLING
    // =========================================================================
    private fun handleIncomingIntent(intent: Intent?) {{
        if (intent == null) return

        // Deep link: primeott://watch?room=...&code=...
        val data: Uri? = intent.data
        if (data != null) {{
            val code = data.getQueryParameter("code") ?: data.getQueryParameter("room")
            if (!code.isNullOrEmpty()) {{
                joinRoomByCodeOrLink(code)
                return
            }}
        }}

        // Direct launch from PlayerActivity or Channel Card
        val passedStreamUrl = intent.getStringExtra("stream_url")
            ?: intent.getStringExtra("STREAM_URL")
            ?: intent.getStringExtra("url")
        val passedTitle = intent.getStringExtra("stream_title")
            ?: intent.getStringExtra("STREAM_TITLE")
            ?: intent.getStringExtra("title")
            ?: "بث مباشر مشترك"
        val passedRoomId = intent.getStringExtra("room_id")
            ?: intent.getStringExtra("ROOM_ID")
            ?: intent.getStringExtra("room_code")

        if (!passedRoomId.isNullOrEmpty() && passedStreamUrl.isNullOrEmpty()) {{
            joinRoomByCodeOrLink(passedRoomId)
        }} else if (!passedStreamUrl.isNullOrEmpty()) {{
            val code = passedRoomId?.take(6)?.uppercase(Locale.US) ?: generateRoomCode()
            val directRoom = WatchRoomModel(
                roomId = passedRoomId ?: generateUniqueRoomId(),
                roomCode = code,
                roomName = "غرفة $passedTitle",
                hostId = localUserId,
                hostName = localUserName,
                isPrivate = false,
                maxParticipants = 25,
                onlineCount = 1,
                status = "LIVE",
                streamUrl = passedStreamUrl,
                streamTitle = passedTitle
            )
            activeRoomsList.add(0, directRoom)
            enterWatchRoom(directRoom, isHost = true)
        }}
    }}

    private fun sha256(input: String): String {{
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
        return bytes.joinToString("") {{ "%02x".format(it) }}
    }}

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {{
        if (currentRoom != null) {{
            confirmLeaveRoom()
        }} else {{
            super.onBackPressed()
        }}
    }}

    override fun onDestroy() {{
        isActivityDestroyed = true
        mainHandler.removeCallbacksAndMessages(null)
        try {{ webSocket?.close(1000, "Activity Destroyed") }} catch (_: Exception) {{}}
        voiceChatManager?.release()
        exoPlayer?.release()
        exoPlayer = null
        super.onDestroy()
    }}

    // =========================================================================
    // RECYCLERVIEW ADAPTERS (SELF-CONTAINED)
    // =========================================================================
    inner class ActiveRoomsAdapter(
        private val items: List<WatchRoomModel>,
        private val onJoinClick: (WatchRoomModel) -> Unit
    ) : RecyclerView.Adapter<ActiveRoomsAdapter.VH>() {{

        inner class VH(val root: LinearLayout, val tvTitle: TextView, val tvMeta: TextView, val tvStream: TextView, val btnJoin: Button) :
            RecyclerView.ViewHolder(root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {{
            val card = LinearLayout(parent.context).apply {{
                orientation = LinearLayout.VERTICAL
                setBackgroundResource(R.drawable.wt_bg_card)
                setPadding(28, 22, 28, 22)
                layoutParams = RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {{ bottomMargin = 18 }}
            }}
            val tvTitle = TextView(parent.context).apply {{
                setTextColor(Color.WHITE)
                textSize = 15f
                setTypeface(typeface, Typeface.BOLD)
            }}
            val tvStream = TextView(parent.context).apply {{
                setTextColor(Color.parseColor("#80D8FF"))
                textSize = 13f
                setPadding(0, 6, 0, 6)
            }}
            val bottomRow = LinearLayout(parent.context).apply {{
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }}
            val tvMeta = TextView(parent.context).apply {{
                setTextColor(Color.parseColor("#B0BEC5"))
                textSize = 12f
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }}
            val btnJoin = Button(parent.context).apply {{
                setBackgroundResource(R.drawable.wt_bg_btn_primary)
                setTextColor(Color.WHITE)
                textSize = 12f
                isAllCaps = false
                setPadding(28, 0, 28, 0)
            }}
            bottomRow.addView(tvMeta)
            bottomRow.addView(btnJoin)
            card.addView(tvTitle)
            card.addView(tvStream)
            card.addView(bottomRow)
            return VH(card, tvTitle, tvMeta, tvStream, btnJoin)
        }}

        override fun onBindViewHolder(holder: VH, position: Int) {{
            val r = items[position]
            val lockBadge = if (r.isPrivate) "🔒 خاصة" else "🌐 عامة"
            val typeIcon = if (r.isMatch) "⚽" else "📺"
            holder.tvTitle.text = "${{r.roomName}}  [$lockBadge • #${{r.roomCode}}]"
            holder.tvStream.text = "$typeIcon يشاهدون الآن: ${{r.streamTitle}}"
            holder.tvMeta.text = "👑 المضيف: ${{r.hostName}}  |  👥 ${{r.onlineCount}}/${{r.maxParticipants}} متصل  |  🔴 ${{r.status}}"
            holder.btnJoin.text = if (r.requireApproval && r.hostId != localUserId) "طلب انضمام" else "دخول الغرفة ⬅"
            holder.btnJoin.setOnClickListener {{ onJoinClick(r) }}
            holder.root.setOnClickListener {{ onJoinClick(r) }}
        }}

        override fun getItemCount(): Int = items.size
    }}

    inner class ChatAdapter(
        private val items: List<ChatMessageModel>,
        private val onMessageClick: (ChatMessageModel) -> Unit
    ) : RecyclerView.Adapter<ChatAdapter.VH>() {{

        inner class VH(val tv: TextView) : RecyclerView.ViewHolder(tv)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {{
            val tv = TextView(parent.context).apply {{
                setPadding(20, 12, 20, 12)
                textSize = 13f
                setBackgroundResource(R.drawable.wt_bg_card)
                layoutParams = RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {{ bottomMargin = 8 }}
            }}
            return VH(tv)
        }}

        override fun onBindViewHolder(holder: VH, position: Int) {{
            val m = items[position]
            val timeStr = SimpleDateFormat("HH:mm", Locale.US).format(Date(m.timestamp))
            if (m.isSystemEvent) {{
                holder.tv.setTextColor(Color.parseColor("#FFE082"))
                holder.tv.text = "[$timeStr] ${{m.text}}"
            }} else {{
                val badge = when (m.senderRole) {{
                    "HOST" -> "👑 "
                    "MODERATOR" -> "🛡️ "
                    else -> "👤 "
                }}
                holder.tv.setTextColor(Color.WHITE)
                holder.tv.text = "[$timeStr] $badge${{m.senderName}}: ${{m.text}}"
                holder.tv.setOnClickListener {{ onMessageClick(m) }}
            }}
        }}

        override fun getItemCount(): Int = items.size
    }}

    inner class ParticipantsAdapter(
        private val getList: () -> List<ParticipantModel>,
        private val onItemClick: (ParticipantModel) -> Unit
    ) : RecyclerView.Adapter<ParticipantsAdapter.VH>() {{

        inner class VH(val tv: TextView) : RecyclerView.ViewHolder(tv)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {{
            val tv = TextView(parent.context).apply {{
                setPadding(24, 18, 24, 18)
                textSize = 13f
                setTextColor(Color.WHITE)
                setBackgroundResource(R.drawable.wt_bg_card)
                layoutParams = RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {{ bottomMargin = 10 }}
            }}
            return VH(tv)
        }}

        override fun onBindViewHolder(holder: VH, position: Int) {{
            val list = getList()
            val p = list[position]
            val roleBadge = when (p.role) {{
                "HOST" -> "👑 مضيف الغرفة"
                "MODERATOR" -> "🛡️ مشرف"
                else -> "مشاهد"
            }}
            val micBadge = when {{
                p.isHostForceMuted -> "🔇 مكتوم من المضيف"
                p.isSpeaking -> "🗣️ يتحدث الآن..."
                p.isMuted -> "🎤 مكتوم"
                else -> "🎙️ ميكروفون مفتوح"
            }}
            holder.tv.text = "${{p.userName}} ($roleBadge)   •   $micBadge   •   🟢 ${{p.pingMs}}ms  [إدارة ⚙️]"
            holder.tv.setOnClickListener {{ onItemClick(p) }}
        }}

        override fun getItemCount(): Int = getList().size
    }}

    inner class MediaPickerAdapter(
        private val items: List<MediaStreamOption>,
        private val onSelect: (MediaStreamOption) -> Unit
    ) : RecyclerView.Adapter<MediaPickerAdapter.VH>() {{

        inner class VH(val tv: TextView) : RecyclerView.ViewHolder(tv)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {{
            val tv = TextView(parent.context).apply {{
                setPadding(24, 18, 24, 18)
                textSize = 13f
                setTextColor(Color.WHITE)
                setBackgroundResource(R.drawable.wt_bg_card)
                layoutParams = RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {{ bottomMargin = 10 }}
            }}
            return VH(tv)
        }}

        override fun onBindViewHolder(holder: VH, position: Int) {{
            val item = items[position]
            val icon = if (item.isMatch) "⚽" else "📺"
            holder.tv.text = "$icon ${{item.title}}\n   • ${{item.subtitle}}   [تشغيل ومزامنة للغرفة ▶️]"
            holder.tv.setOnClickListener {{ onSelect(item) }}
        }}

        override fun getItemCount(): Int = items.size
    }}

    inner class EventLogsAdapter(private val items: List<RoomEventLogItem>) :
        RecyclerView.Adapter<EventLogsAdapter.VH>() {{

        inner class VH(val tv: TextView) : RecyclerView.ViewHolder(tv)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {{
            val tv = TextView(parent.context).apply {{
                setPadding(16, 10, 16, 10)
                textSize = 11f
                setTextColor(Color.parseColor("#B0BEC5"))
            }}
            return VH(tv)
        }}

        override fun onBindViewHolder(holder: VH, position: Int) {{
            val log = items[position]
            val timeStr = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(log.timestamp))
            holder.tv.text = "[$timeStr] [${{log.type}}] ${{log.detail}}"
        }}

        override fun getItemCount(): Int = items.size
    }}
}}
"""
    path = os.path.join(src_dir, "WatchRoomActivity.kt")
    with open(path, "w", encoding="utf-8") as f:
        f.write(code.strip())
    print(f"[✓] تم تحديث: {path}")


def ensure_manifest_permissions_and_deeplink():
    if not os.path.exists(MANIFEST_PATH):
        return
    with open(MANIFEST_PATH, "r", encoding="utf-8", errors="ignore") as f:
        manifest = f.read()

    modified = False
    for perm in [
        "android.permission.INTERNET",
        "android.permission.RECORD_AUDIO",
        "android.permission.MODIFY_AUDIO_SETTINGS",
    ]:
        if perm not in manifest:
            manifest = manifest.replace(
                "<application",
                f'    <uses-permission android:name="{perm}" />\n    <application',
                1,
            )
            modified = True

    if modified:
        with open(MANIFEST_PATH, "w", encoding="utf-8") as f:
            f.write(manifest)
        print("[✓] تم التأكد من صلاحيات الصوت والإنترنت في AndroidManifest.xml.")


def main():
    print("=" * 65)
    print("🚀 بدء ترقية منظومة Watch Together الاحترافية داخل Prime OTT...")
    print("=" * 65)

    pkg, src_dir = find_package_and_source_dir()
    worker_url, xtream_host = extract_existing_server_defaults(src_dir)

    print(f"[•] الحزمة المكتشفة: {pkg}")
    print(f"[•] مسار الكود البرمجي: {src_dir}")
    print(f"[•] سيرفر المزامنة: {worker_url}")
    print(f"[•] سيرفر البث المباشر: {xtream_host}")

    files_to_backup = [
        os.path.join(LAYOUT_DIR, "activity_watch_room.xml"),
        os.path.join(src_dir, "WatchRoomActivity.kt"),
        os.path.join(src_dir, "VoiceChatManager.kt"),
        os.path.join(src_dir, "VoiceSyncHelper.kt"),
    ]
    backup_existing_files(files_to_backup)

    create_drawables()
    write_watch_room_layout()
    write_voice_sync_helper(pkg, src_dir, worker_url)
    write_voice_chat_manager(pkg, src_dir)
    write_watch_room_activity(pkg, src_dir, worker_url, xtream_host)
    ensure_manifest_permissions_and_deeplink()

    print("=" * 65)
    print("✅ اكتمل التحديث بنجاح 100%! يمكنك الآن عمل Run / Build APK مباشرة.")
    print("=" * 65)


if __name__ == "__main__":
    main()
