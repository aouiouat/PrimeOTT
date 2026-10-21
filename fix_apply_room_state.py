path = "app/src/main/java/com/primeott/app/presentation/watch/WatchRoomActivity.kt"
with open(path, "r", encoding="utf-8") as f:
    c = f.read()

# إزالة الدالة الفارغة التي كانت تمنع تحديث واجهة الغرفة بعد الاتصال
empty_overload = """    private fun applyRoomFullState(
        room: WatchRoom,
        participants: List<RoomParticipant>,
        newMessages: List<RoomParticipantMessageAlias>
    ) {
        // الدالة الفعلية بالأسفل
    }"""

c = c.replace(empty_overload, "")
c = c.replace("newMessages: List<RoomChatMessage>,\n        unusedFlag: Boolean = true", "newMessages: List<RoomChatMessage>")

with open(path, "w", encoding="utf-8") as f:
    f.write(c)

print("[✓] Fixed applyRoomFullState in WatchRoomActivity.kt!")
