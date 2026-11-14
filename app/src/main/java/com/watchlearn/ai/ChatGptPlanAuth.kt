package com.watchlearn.ai

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import androidx.core.content.ContextCompat
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.math.BigInteger
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URL
import java.net.URLEncoder
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.RSAPublicKeySpec
import java.util.concurrent.TimeUnit
import java.util.UUID

data class ChatGptPlanSession(
    val accessToken: String,
    val refreshToken: String?,
    val clientId: String,
    val expiresAtSeconds: Long,
    val scopes: Set<String>
)

class ChatGptPlanAuth(private val context: Context) {
    private val preferences by lazy {
        val masterKey = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
        EncryptedSharedPreferences.create(
            "watchlearn_chatgpt_auth",
            masterKey,
            context,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    suspend fun loadSession(): ChatGptPlanSession? = withContext(Dispatchers.IO) {
        val accessToken = preferences.getString(KEY_ACCESS_TOKEN, null) ?: return@withContext null
        val refreshToken = preferences.getString(KEY_REFRESH_TOKEN, null)
        val clientId = preferences.getString(KEY_CLIENT_ID, null) ?: return@withContext null
        val expiresAt = preferences.getLong(KEY_EXPIRES_AT, 0L)
        val scopes = preferences.getString(KEY_SCOPES, "").orEmpty()
            .split(" ")
            .filter { it.isNotBlank() }
            .toSet()
        if (expiresAt > System.currentTimeMillis() / 1000 + 60) {
            return@withContext ChatGptPlanSession(accessToken, refreshToken, clientId, expiresAt, scopes)
        }
        if (refreshToken.isNullOrBlank()) {
            clear()
            return@withContext null
        }
        runCatching { refresh(clientId, refreshToken) }.getOrNull()
    }

    suspend fun signIn(): ChatGptPlanSession = withContext(Dispatchers.IO) {
        val verifier = randomToken(48)
        val challenge = base64Url(
            MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.UTF_8))
        )
        val state = randomToken(32)
        val nonce = randomToken(32)
        // OpenAI expects ext_agent_host_id to be a stable, opaque host
        // identifier.  Use a URN UUID so installs created by older builds
        // with a raw Base64 value are migrated automatically.
        val hostId = preferences.getString(KEY_HOST_ID, null)
            ?.takeIf { it.startsWith("urn:uuid:") && it.length > "urn:uuid:".length }
            ?: "urn:uuid:${UUID.randomUUID()}".also {
                preferences.edit().putString(KEY_HOST_ID, it).apply()
            }
        val savedClientId = preferences.getString(KEY_CLIENT_ID, null)
        val clientIdForAuthorization = savedClientId ?: "dynamic_agent_client"

        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            val redirectUri = "http://127.0.0.1:" + server.localPort + "/auth/callback"
            val builder = Uri.Builder()
                .scheme("https")
                .authority("auth.openai.com")
                .path("/api/accounts/authorize")
                .appendQueryParameter("client_id", clientIdForAuthorization)
                .appendQueryParameter("response_type", "code")
                .appendQueryParameter("redirect_uri", redirectUri)
                .appendQueryParameter(
                    "scope",
                    "openid profile email offline_access resource.invoke chatgpt.tokens.use.direct"
                )
                .appendQueryParameter("resource", "https://api.openai.com/v1")
                .appendQueryParameter("state", state)
                .appendQueryParameter("nonce", nonce)
                .appendQueryParameter("code_challenge_method", "S256")
                .appendQueryParameter("code_challenge", challenge)
            if (savedClientId == null) {
                builder.appendQueryParameter("agent_name_hint", "WatchLearn AI")
                builder.appendQueryParameter("ext_agent_host_id", hostId)
            }

            withContext(Dispatchers.Main) {
                ContextCompat.startActivity(
                    context,
                    Intent(Intent.ACTION_VIEW, Uri.parse(builder.build().toString())),
                    null
                )
            }

            val callback = readCallback(server)
            if (callback.getQueryParameter("state") != state) {
                throw IOException("ChatGPT sign-in state validation failed.")
            }
            val error = callback.getQueryParameter("error")
            if (!error.isNullOrBlank()) {
                throw IOException("ChatGPT sign-in was not approved: " + error)
            }
            val code = callback.getQueryParameter("code")
                ?: throw IOException("ChatGPT sign-in did not return an authorization code.")
            val issuedClientId = callback.getQueryParameter("client_id")
                ?: savedClientId
                ?: throw IOException("ChatGPT did not return an application client ID.")
            if (savedClientId != null && issuedClientId != savedClientId) {
                throw IOException("ChatGPT returned a different client ID than the saved connection.")
            }

            val session = exchangeCode(issuedClientId, code, verifier, redirectUri)
            validateIdToken(session.idToken, issuedClientId, nonce)
            val scopes = session.scope.split(" ").filter { it.isNotBlank() }.toSet()
            if (!scopes.contains("chatgpt.tokens.use.direct")) {
                throw IOException("ChatGPT plan usage was not approved.")
            }
            val result = ChatGptPlanSession(
                session.accessToken,
                session.refreshToken,
                issuedClientId,
                System.currentTimeMillis() / 1000 + session.expiresIn,
                scopes
            )
            save(result, session.idToken)
            result
        }
    }

    fun clear() {
        preferences.edit().clear().apply()
    }

    private suspend fun refresh(clientId: String, refreshToken: String): ChatGptPlanSession =
        withContext(Dispatchers.IO) {
            val body = formBody(
                "grant_type" to "refresh_token",
                "client_id" to clientId,
                "refresh_token" to refreshToken,
                "resource" to "https://api.openai.com/v1"
            )
            val response = postForm("https://auth.openai.com/api/accounts/oauth/token", body)
            val scopes = response.optString("scope").split(" ")
                .filter { it.isNotBlank() }
                .toSet()
            if (!scopes.contains("chatgpt.tokens.use.direct")) {
                throw IOException("ChatGPT plan permission is no longer active.")
            }
            val session = ChatGptPlanSession(
                response.getString("access_token"),
                response.optString("refresh_token").ifBlank { refreshToken },
                clientId,
                System.currentTimeMillis() / 1000 + response.optLong("expires_in", 3600),
                scopes
            )
            save(session, response.optString("id_token"))
            session
        }

    private fun exchangeCode(
        clientId: String,
        code: String,
        verifier: String,
        redirectUri: String
    ): TokenResponse {
        val body = formBody(
            "grant_type" to "authorization_code",
            "client_id" to clientId,
            "code" to code,
            "code_verifier" to verifier,
            "redirect_uri" to redirectUri,
            "resource" to "https://api.openai.com/v1"
        )
        val response = postForm("https://auth.openai.com/api/accounts/oauth/token", body)
        return TokenResponse(
            accessToken = response.getString("access_token"),
            refreshToken = response.optString("refresh_token").ifBlank { null },
            idToken = response.getString("id_token"),
            scope = response.optString("scope"),
            expiresIn = response.optLong("expires_in", 3600)
        )
    }

    private fun save(session: ChatGptPlanSession, idToken: String) {
        preferences.edit()
            .putString(KEY_ACCESS_TOKEN, session.accessToken)
            .putString(KEY_REFRESH_TOKEN, session.refreshToken)
            .putString(KEY_CLIENT_ID, session.clientId)
            .putLong(KEY_EXPIRES_AT, session.expiresAtSeconds)
            .putString(KEY_SCOPES, session.scopes.joinToString(" "))
            .putString(KEY_ID_TOKEN, idToken)
            .apply()
    }

    private fun readCallback(server: ServerSocket): Uri {
        server.soTimeout = TimeUnit.MINUTES.toMillis(10).toInt()
        server.accept().use { socket ->
            val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
            val requestLine = reader.readLine().orEmpty()
            val target = requestLine.split(" ").getOrNull(1)
                ?: throw IOException("Invalid ChatGPT callback.")
            val callback = Uri.parse("http://127.0.0.1$target")
            OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8).use { writer ->
                val html = "<html><body>You may return to WatchLearn AI.</body></html>"
                writer.write(
                    "HTTP/1.1 200 OK\\r\\n" +
                        "Content-Type: text/html\\r\\n" +
                        "Content-Length: " + html.toByteArray().size +
                        "\\r\\nConnection: close\\r\\n\\r\\n" + html
                )
                writer.flush()
            }
            return callback
        }
    }

    private fun postForm(url: String, body: String): JSONObject {
        val connection = URL(url).openConnection() as java.net.HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                throw IOException("ChatGPT sign-in failed ($code): " + text.take(320))
            }
            return JSONObject(text)
        } finally {
            connection.disconnect()
        }
    }

    private fun validateIdToken(idToken: String, clientId: String, nonce: String) {
        val parts = idToken.split(".")
        if (parts.size != 3) throw IOException("ChatGPT returned an invalid identity token.")
        val header = JSONObject(
            String(Base64.decode(parts[0], Base64.URL_SAFE or Base64.NO_WRAP), Charsets.UTF_8)
        )
        val claims = JSONObject(
            String(Base64.decode(parts[1], Base64.URL_SAFE or Base64.NO_WRAP), Charsets.UTF_8)
        )
        if (claims.optString("iss") != "https://auth.openai.com") {
            throw IOException("ChatGPT identity issuer was not verified.")
        }
        if (claims.optString("nonce") != nonce) {
            throw IOException("ChatGPT identity nonce was not verified.")
        }
        if (claims.optLong("exp", 0L) <= System.currentTimeMillis() / 1000) {
            throw IOException("ChatGPT identity token expired.")
        }
        val audience = claims.opt("aud")
        val audienceMatches = when (audience) {
            is String -> audience == clientId
            is org.json.JSONArray -> (0 until audience.length()).any { audience.optString(it) == clientId }
            else -> false
        }
        if (!audienceMatches) throw IOException("ChatGPT identity audience was not verified.")

        val jwks = JSONObject(URL("https://auth.openai.com/.well-known/jwks.json").readText())
            .getJSONArray("keys")
        val keyId = header.optString("kid")
        val key = (0 until jwks.length())
            .map { jwks.getJSONObject(it) }
            .firstOrNull { it.optString("kid") == keyId }
            ?: throw IOException("ChatGPT signing key was not found.")
        val modulus = BigInteger(
            1,
            Base64.decode(key.getString("n"), Base64.URL_SAFE or Base64.NO_WRAP)
        )
        val exponent = BigInteger(
            1,
            Base64.decode(key.getString("e"), Base64.URL_SAFE or Base64.NO_WRAP)
        )
        val publicKey = KeyFactory.getInstance("RSA")
            .generatePublic(RSAPublicKeySpec(modulus, exponent))
        val signature = Signature.getInstance("SHA256withRSA")
        signature.initVerify(publicKey)
        signature.update((parts[0] + "." + parts[1]).toByteArray(Charsets.UTF_8))
        if (!signature.verify(Base64.decode(parts[2], Base64.URL_SAFE or Base64.NO_WRAP))) {
            throw IOException("ChatGPT identity signature was not verified.")
        }
    }

    private fun randomToken(bytes: Int): String {
        val value = ByteArray(bytes)
        SecureRandom().nextBytes(value)
        return base64Url(value)
    }

    private fun base64Url(value: ByteArray): String =
        Base64.encodeToString(value, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

    private fun formBody(vararg values: Pair<String, String>): String =
        values.joinToString("&") {
            URLEncoder.encode(it.first, "UTF-8") + "=" + URLEncoder.encode(it.second, "UTF-8")
        }

    private data class TokenResponse(
        val accessToken: String,
        val refreshToken: String?,
        val idToken: String,
        val scope: String,
        val expiresIn: Long
    )

    private companion object {
        const val KEY_ACCESS_TOKEN = "access_token"
        const val KEY_REFRESH_TOKEN = "refresh_token"
        const val KEY_CLIENT_ID = "client_id"
        const val KEY_EXPIRES_AT = "expires_at"
        const val KEY_SCOPES = "scopes"
        const val KEY_ID_TOKEN = "id_token"
        const val KEY_HOST_ID = "host_id"
    }
}

