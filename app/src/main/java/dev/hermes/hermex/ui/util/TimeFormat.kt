package dev.hermes.hermex.ui.util

/**
 * Shared timestamp formatting utilities.
 * QUAL-3 fix: extracted from duplicate implementations in
 * SessionListScreen and ArchivedSessionsScreen.
 */
fun formatTimestamp(timestamp: Long): String {
    val date = java.util.Date(timestamp)
    return java.text.SimpleDateFormat("MMM d, HH:mm", java.util.Locale.getDefault()).format(date)
}

fun formatMessageTime(timestamp: Long): String {
    if (timestamp == 0L) return ""
    val date = java.util.Date(timestamp)
    return java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(date)
}
