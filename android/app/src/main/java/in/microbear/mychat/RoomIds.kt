package in.microbear.mychat

object RoomIds {
    fun normalize(raw: String): String {
        val cleaned = raw.trim().lowercase()
            .replace(Regex("\\s+"), "-")
            .replace(Regex("[^a-z0-9-]"), "")
            .take(24)
        return cleaned.ifEmpty { "lobby" }
    }
}
