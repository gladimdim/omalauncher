package dev.gladimdim.superdesktop

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dev.gladimdim.superdesktop.bridge.Bridge
import dev.gladimdim.superdesktop.bridge.BridgeAddress
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * Paired computers, tokens included, encrypted at rest. Read once, then served from [bridges];
 * every change writes through. Tokens never leave this store except into requests.
 */
object SdStore {
    private const val FILE = "sd_bridges_v1"
    private const val KEY = "bridges"

    private var prefs: SharedPreferences? = null
    private val state = MutableStateFlow<List<Bridge>?>(null)

    /** Null until [load] ran. */
    val bridges: StateFlow<List<Bridge>?> get() = state

    @Synchronized private fun prefs(context: Context): SharedPreferences =
        prefs ?: run {
            val app = context.applicationContext
            val key = MasterKey.Builder(app).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
            EncryptedSharedPreferences
                .create(
                    app,
                    FILE,
                    key,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
                ).also { prefs = it }
        }

    /** Blocking: decrypts on first use. Call off the main thread. */
    @Synchronized fun load(context: Context): List<Bridge> {
        state.value?.let { return it }
        val raw = runCatching { prefs(context).getString(KEY, null) }.getOrNull()
        val list = raw?.let { runCatching { decode(JSONArray(it)) }.getOrNull() }.orEmpty()
        state.value = list
        return list
    }

    /** Adds [bridge], or replaces the one with the same certificate (pairing again). */
    @Synchronized fun save(
        context: Context,
        bridge: Bridge,
    ) = write(context, load(context).filterNot { it.key == bridge.key } + bridge)

    /** Replaces [bridge] with [next] only while it is still saved as it was, so a removal or new pairing is kept. */
    @Synchronized fun update(
        context: Context,
        bridge: Bridge,
        next: Bridge,
    ) {
        val list = load(context)
        if (bridge !in list) return
        write(context, list.map { if (it == bridge) next else it })
    }

    @Synchronized fun remove(
        context: Context,
        key: String,
    ) = write(context, load(context).filterNot { it.key == key })

    /** A small value kept encrypted next to the pairings, such as a computer's wake cards. */
    @Synchronized fun putSecret(
        context: Context,
        key: String,
        value: String,
    ) {
        val prefs = runCatching { prefs(context) }.getOrNull() ?: return
        if (prefs.getString(key, null) != value) prefs.edit().putString(key, value).apply()
    }

    @Synchronized fun secret(
        context: Context,
        key: String,
    ): String? = runCatching { prefs(context).getString(key, null) }.getOrNull()

    private fun write(
        context: Context,
        list: List<Bridge>,
    ) {
        prefs(context).edit().putString(KEY, encode(list).toString()).commit()
        state.value = list
    }

    private fun encode(list: List<Bridge>) =
        JSONArray().apply {
            for (b in list) {
                put(
                    JSONObject()
                        .put("host", b.host)
                        .put("port", b.port)
                        .put("token", b.token)
                        .put("pin", b.pin)
                        .put("bridgeId", b.bridgeId)
                        .put("name", b.name)
                        .put(
                            "addresses",
                            JSONArray().apply {
                                for (a in b.addresses) put(JSONObject().put("address", a.address).put("kind", a.network).put("connectable", a.connectable))
                            },
                        ),
                )
            }
        }

    private fun decode(array: JSONArray) =
        (0 until array.length()).mapNotNull { i ->
            val o = array.optJSONObject(i) ?: return@mapNotNull null
            val addresses = o.optJSONArray("addresses")
            Bridge(
                host = o.getString("host"),
                port = o.optInt("port", 8759),
                token = o.getString("token"),
                pin = o.getString("pin"),
                bridgeId = o.optString("bridgeId"),
                name = o.optString("name", "computer"),
                addresses =
                    (0 until (addresses?.length() ?: 0)).mapNotNull { j ->
                        addresses?.optJSONObject(j)?.let { BridgeAddress(it.optString("address"), it.optString("kind"), it.optBoolean("connectable")) }
                    },
            )
        }
}
