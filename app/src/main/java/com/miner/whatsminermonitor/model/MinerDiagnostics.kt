package com.miner.whatsminermonitor.model

import android.util.Log

/**
 * Raw diagnostics collector - stores last raw API responses per IP for troubleshooting
 * and exposes detailed per-field resolution logs in the exact format requested:
 * IP → endpoint → JSON key → raw value → parsed value → final displayed value
 */
object MinerDiagnostics {

    data class RawCapture(
        val endpoint: String,
        val port: Int,
        val rawJson: String?,
        val parsedOk: Boolean,
        val error: String? = null,
        val timestampMs: Long = System.currentTimeMillis()
    )

    data class FieldResolution(
        val ip: String,
        val field: String, // e.g., "hashrate", "elapsed", "power", "accepted", "rejected", "fanIn", "fanOut"
        val endpoint: String, // e.g., "SUMMARY", "STATS", "PSU-Msg", "APIv3-msg.summary"
        val jsonKey: String, // e.g., "MHS 5s", "hash-average", "elapsed"
        val rawValue: String, // string representation of raw JSON value (or "MISSING")
        val parsedValue: String, // e.g., "101847.5 MH/s"
        val finalValue: String, // e.g., "101847 GHS (101.8 TH/s)"
        val status: Status
    ) {
        enum class Status { OK, ZERO, MISSING, INVALID, ERROR }

        fun logLine(): String =
            "$ip → $endpoint → $jsonKey → raw=$rawValue → parsed=$parsedValue → final=$finalValue [${status.name}]"
    }

    private val rawCaptures = mutableMapOf<String, MutableList<RawCapture>>() // ip -> list
    private val fieldResolutions = mutableMapOf<String, MutableList<FieldResolution>>() // ip -> list
    private const val TAG = "MinerDiagnostics"
    private const val MAX_RAW_LEN = 3500

    @Synchronized
    fun recordRaw(ip: String, endpoint: String, port: Int, raw: String?, parsedOk: Boolean, error: String? = null) {
        val list = rawCaptures.getOrPut(ip) { mutableListOf() }
        // Keep last 20 per IP
        if (list.size >= 20) list.removeAt(0)
        val truncated = raw?.take(MAX_RAW_LEN)
        list.add(RawCapture(endpoint, port, truncated, parsedOk, error))
        // Always log raw for real-device verification (first 2000 chars to avoid log overflow)
        val preview = truncated?.take(1800)?.replace("\n", "\\n") ?: "null"
        if (raw == null) {
            Log.w(TAG, "RAW ip=$ip endpoint=$endpoint:$port FAILED error=$error")
        } else if (!parsedOk) {
            Log.w(TAG, "RAW ip=$ip endpoint=$endpoint:$port PARSE_FAIL preview=$preview error=$error")
        } else {
            Log.d(TAG, "RAW ip=$ip endpoint=$endpoint:$port OK len=${raw.length} preview=$preview")
        }
    }

    @Synchronized
    fun recordField(r: FieldResolution) {
        val list = fieldResolutions.getOrPut(r.ip) { mutableListOf() }
        if (list.size >= 40) list.removeAt(0)
        list.add(r)
        // Required diagnostic format
        val line = r.logLine()
        when (r.status) {
            FieldResolution.Status.OK, FieldResolution.Status.ZERO -> Log.d(TAG, line)
            FieldResolution.Status.MISSING -> Log.d(TAG, "[MISSING] $line")
            FieldResolution.Status.INVALID -> Log.w(TAG, "[INVALID] $line")
            FieldResolution.Status.ERROR -> Log.w(TAG, "[ERROR] $line")
        }
    }

    @Synchronized
    fun getRawCaptures(ip: String): List<RawCapture> = rawCaptures[ip]?.toList() ?: emptyList()

    @Synchronized
    fun getFieldResolutions(ip: String): List<FieldResolution> = fieldResolutions[ip]?.toList() ?: emptyList()

    @Synchronized
    fun getLastRawForShare(ip: String): String {
        val raws = rawCaptures[ip] ?: return "No captures for $ip"
        val sb = StringBuilder()
        sb.appendLine("=== Raw API responses for $ip ===")
        sb.appendLine("Captured at ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(java.util.Date())}")
        for (c in raws) {
            sb.appendLine("--- endpoint=${c.endpoint} port=${c.port} parsedOk=${c.parsedOk} error=${c.error ?: "-"} ---")
            sb.appendLine(c.rawJson ?: "<null>")
            sb.appendLine()
        }
        sb.appendLine("=== Field resolutions ===")
        for (f in fieldResolutions[ip] ?: emptyList()) {
            sb.appendLine(f.logLine())
        }
        return sb.toString()
    }

    @Synchronized
    fun clear(ip: String) {
        rawCaptures.remove(ip)
        fieldResolutions.remove(ip)
    }
}
