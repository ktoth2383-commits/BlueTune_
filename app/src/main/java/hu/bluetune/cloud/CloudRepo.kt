package hu.bluetune.cloud

import android.content.Context
import android.os.Build
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.FirebaseTooManyRequestsException
import com.google.firebase.auth.EmailAuthProvider
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.Query
import hu.bluetune.BuildConfig
import hu.bluetune.audio.AudioEngine
import hu.bluetune.data.AppPrefs
import hu.bluetune.data.DeviceProfileStore
import hu.bluetune.data.PresetStore
import hu.bluetune.model.EqSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.tasks.await
import java.text.DateFormat

/**
 * Firebase réteg (csak email + jelszó):
 *  - regisztráció, belépés, email-megerősítés, elfelejtett jelszó, fióktörlés
 *  - hírlevél-hozzájárulás (subscribers/{uid}) – innen szinkronizál a Brevo
 *  - felhős mentés / visszaállítás
 *  - közösségi presetek
 *
 * Ha nincs google-services.json, [available] hamis, és az app Firebase nélkül fut.
 */
object CloudRepo {

    data class Account(val uid: String, val email: String, val verified: Boolean)

    data class CommunityPreset(
        val id: String,
        val name: String,
        val author: String,
        val description: String,
        val settings: EqSettings,
        val likes: Long,
        val ownerUid: String,
    )

    /** Felhasználónak szóló, magyar nyelvű hiba. */
    class CloudException(message: String) : Exception(message)

    private const val USERS = "users"
    private const val SUBSCRIBERS = "subscribers"
    private const val COMMUNITY = "community_presets"
    const val MIN_PASSWORD = 8

    var available: Boolean = false
        private set

    private val _account = MutableStateFlow<Account?>(null)
    val account: StateFlow<Account?> = _account.asStateFlow()

    fun init(ctx: Context) {
        available = try {
            FirebaseApp.getApps(ctx).isNotEmpty() || FirebaseApp.initializeApp(ctx) != null
        } catch (t: Throwable) {
            Log.i("BlueTune", "Firebase nincs beállítva: ${t.message}")
            false
        }
        if (!available) return
        auth().setLanguageCode("hu")
        auth().addAuthStateListener { publish(it.currentUser) }
        // Korábbi (anonim) munkamenet kiléptetése – csak email+jelszó engedélyezett.
        auth().currentUser?.let { if (it.isAnonymous) auth().signOut() }
        publish(auth().currentUser)
    }

    private fun auth() = FirebaseAuth.getInstance()
    private fun db() = FirebaseFirestore.getInstance()

    private fun publish(u: FirebaseUser?) {
        _account.value = u?.takeIf { !it.isAnonymous }?.let {
            Account(it.uid, it.email.orEmpty(), it.isEmailVerified)
        }
    }

    private fun requireUser(): FirebaseUser {
        check(available) { "A Firebase nincs beállítva." }
        return auth().currentUser?.takeIf { !it.isAnonymous }
            ?: throw CloudException("Ehhez be kell jelentkezned.")
    }

    // ------------------------------------------------------------ fiók

    suspend fun register(email: String, password: String, newsletter: Boolean): Unit = guard {
        val cleanEmail = email.trim()
        if (password.length < MIN_PASSWORD) throw CloudException("A jelszó legalább $MIN_PASSWORD karakter legyen.")
        val user = auth().createUserWithEmailAndPassword(cleanEmail, password).await().user
            ?: throw CloudException("A regisztráció nem sikerült.")
        runCatching { user.sendEmailVerification().await() }
        db().collection(USERS).document(user.uid).set(
            hashMapOf(
                "email" to cleanEmail,
                "createdAt" to FieldValue.serverTimestamp(),
                "privacyAcceptedAt" to FieldValue.serverTimestamp(),
                "appVersion" to BuildConfig.VERSION_NAME,
            )
        ).await()
        if (newsletter) writeSubscription(user, true)
        publish(user)
    }

    suspend fun login(email: String, password: String): Unit = guard {
        val user = auth().signInWithEmailAndPassword(email.trim(), password).await().user
        publish(user)
    }

    suspend fun sendPasswordReset(email: String): Unit = guard {
        if (email.isBlank()) throw CloudException("Írd be az email címed.")
        auth().sendPasswordResetEmail(email.trim()).await()
    }

    suspend fun resendVerification(): Unit = guard {
        requireUser().sendEmailVerification().await()
    }

    /** Újratölti a felhasználót – a megerősítő link megnyitása után így lesz „megerősítve”. */
    suspend fun reloadUser(): Boolean = guard {
        val u = auth().currentUser ?: return@guard false
        u.reload().await()
        val fresh = auth().currentUser
        if (fresh?.isEmailVerified == true) fresh.getIdToken(true).await() // friss token a szabályokhoz
        publish(fresh)
        fresh?.isEmailVerified == true
    }

    suspend fun changePassword(current: String, newPassword: String): Unit = guard {
        if (newPassword.length < MIN_PASSWORD) throw CloudException("Az új jelszó legalább $MIN_PASSWORD karakter legyen.")
        val u = requireUser()
        u.reauthenticate(EmailAuthProvider.getCredential(u.email.orEmpty(), current)).await()
        u.updatePassword(newPassword).await()
    }

    fun signOut() {
        if (available) auth().signOut()
        _account.value = null
    }

    /**
     * Fiók végleges törlése: közösségi presetek, hírlevél-feliratkozás, mentés, majd maga a fiók.
     * A Google Play előírja, hogy ez az appon belül is elérhető legyen.
     */
    suspend fun deleteAccount(password: String): Unit = guard {
        val u = requireUser()
        u.reauthenticate(EmailAuthProvider.getCredential(u.email.orEmpty(), password)).await()
        val own = db().collection(COMMUNITY).whereEqualTo("ownerUid", u.uid).get().await()
        own.documents.forEach { it.reference.delete().await() }
        db().collection(SUBSCRIBERS).document(u.uid).delete().await()
        db().collection(USERS).document(u.uid).collection("backup").document("latest").delete().await()
        db().collection(USERS).document(u.uid).delete().await()
        u.delete().await()
        AppPrefs.likedCommunityIds = emptySet()
        _account.value = null
    }

    // ------------------------------------------------------------ hírlevél

    suspend fun isSubscribed(): Boolean = guard {
        val u = requireUser()
        db().collection(SUBSCRIBERS).document(u.uid).get().await().exists()
    }

    suspend fun setNewsletter(on: Boolean): Unit = guard {
        writeSubscription(requireUser(), on)
    }

    private suspend fun writeSubscription(u: FirebaseUser, on: Boolean) {
        val ref = db().collection(SUBSCRIBERS).document(u.uid)
        if (on) {
            ref.set(
                hashMapOf(
                    "email" to u.email.orEmpty(),
                    "consent" to true,
                    "consentAt" to FieldValue.serverTimestamp(),
                    "source" to "android ${BuildConfig.VERSION_NAME}",
                )
            ).await()
        } else {
            ref.delete().await()
        }
    }

    // ------------------------------------------------------------ mentés

    suspend fun backup(): Unit = guard {
        val u = requireUser()
        val data = hashMapOf<String, Any>(
            "presets" to PresetStore.exportJson(),
            "profiles" to DeviceProfileStore.exportJson(),
            "current" to AudioEngine.settings.value.toJson().toString(),
            "device" to "${Build.MANUFACTURER} ${Build.MODEL}",
            "updatedAt" to FieldValue.serverTimestamp(),
        )
        db().collection(USERS).document(u.uid).collection("backup").document("latest").set(data).await()
    }

    suspend fun restore(): String = guard {
        val u = requireUser()
        val doc = db().collection(USERS).document(u.uid).collection("backup").document("latest").get().await()
        if (!doc.exists()) return@guard "Még nincs felhős mentés."
        val presets = doc.getString("presets")?.let { PresetStore.importJson(it) } ?: 0
        val profiles = doc.getString("profiles")?.let { DeviceProfileStore.importJson(it) } ?: 0
        doc.getString("current")?.let { EqSettings.fromJsonString(it) }?.let { restored ->
            AudioEngine.update { cur -> restored.copy(enabled = cur.enabled) }
        }
        "Visszaállítva: $presets preset, $profiles eszközprofil."
    }

    suspend fun lastBackupInfo(): String? = guard {
        val u = requireUser()
        val doc = db().collection(USERS).document(u.uid).collection("backup").document("latest").get().await()
        val date = doc.getTimestamp("updatedAt")?.toDate() ?: return@guard null
        val from = doc.getString("device") ?: "?"
        "${DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(date)} ($from)"
    }

    // ------------------------------------------------------------ közösség

    suspend fun community(): List<CommunityPreset> = guard {
        requireUser()
        val snap = db().collection(COMMUNITY)
            .orderBy("likes", Query.Direction.DESCENDING)
            .limit(100)
            .get()
            .await()
        snap.documents.mapNotNull { d ->
            val s = d.getString("settings")?.let { EqSettings.fromJsonString(it) } ?: return@mapNotNull null
            CommunityPreset(
                id = d.id,
                name = d.getString("name") ?: "Névtelen",
                author = d.getString("author")?.takeIf { it.isNotBlank() } ?: "Névtelen",
                description = d.getString("description") ?: "",
                settings = s,
                likes = d.getLong("likes") ?: 0L,
                ownerUid = d.getString("ownerUid") ?: "",
            )
        }
    }

    suspend fun publish(name: String, author: String, description: String, settings: EqSettings): Unit = guard {
        val u = requireUser()
        if (!u.isEmailVerified) throw CloudException("Közzététel előtt erősítsd meg az email címed.")
        val clean = name.trim().take(40)
        if (clean.isEmpty()) throw CloudException("Adj nevet a presetnek.")
        val data = hashMapOf<String, Any>(
            "name" to clean,
            "author" to author.trim().take(30),
            "description" to description.trim().take(200),
            "settings" to settings.copy(enabled = true, presetName = clean).toJson().toString(),
            "likes" to 0L,
            "ownerUid" to u.uid,
            "createdAt" to FieldValue.serverTimestamp(),
        )
        db().collection(COMMUNITY).add(data).await()
    }

    suspend fun like(presetId: String): Unit = guard {
        requireUser()
        if (presetId in AppPrefs.likedCommunityIds) return@guard
        db().collection(COMMUNITY).document(presetId).update("likes", FieldValue.increment(1)).await()
        AppPrefs.likedCommunityIds = AppPrefs.likedCommunityIds + presetId
    }

    suspend fun delete(presetId: String): Unit = guard {
        requireUser()
        db().collection(COMMUNITY).document(presetId).delete().await()
    }

    // ------------------------------------------------------------ hibakezelés

    private inline fun <T> guard(block: () -> T): T = try {
        block()
    } catch (e: CloudException) {
        throw e
    } catch (e: IllegalStateException) {
        throw CloudException(e.message ?: "Hiba történt.")
    } catch (e: FirebaseAuthException) {
        throw CloudException(authMessage(e.errorCode))
    } catch (e: FirebaseNetworkException) {
        throw CloudException("Nincs internetkapcsolat.")
    } catch (e: FirebaseTooManyRequestsException) {
        throw CloudException("Túl sok próbálkozás. Várj pár percet.")
    } catch (e: FirebaseFirestoreException) {
        throw CloudException(
            when (e.code) {
                FirebaseFirestoreException.Code.PERMISSION_DENIED -> "Ehhez nincs jogosultságod (lehet, hogy előbb meg kell erősítened az email címed)."
                FirebaseFirestoreException.Code.UNAVAILABLE -> "A szerver most nem érhető el. Próbáld újra."
                else -> "Adatbázis hiba: ${e.code}"
            }
        )
    }

    private fun authMessage(code: String): String = when (code) {
        "ERROR_INVALID_EMAIL" -> "Érvénytelen email cím."
        "ERROR_INVALID_CREDENTIAL", "ERROR_WRONG_PASSWORD", "ERROR_USER_NOT_FOUND" -> "Hibás email cím vagy jelszó."
        "ERROR_EMAIL_ALREADY_IN_USE" -> "Ezzel az email címmel már van fiók. Jelentkezz be, vagy kérj új jelszót."
        "ERROR_WEAK_PASSWORD" -> "Túl gyenge jelszó (legalább $MIN_PASSWORD karakter)."
        "ERROR_USER_DISABLED" -> "Ez a fiók le van tiltva."
        "ERROR_REQUIRES_RECENT_LOGIN" -> "Biztonsági okból jelentkezz be újra."
        "ERROR_TOO_MANY_REQUESTS" -> "Túl sok próbálkozás. Várj pár percet."
        "ERROR_OPERATION_NOT_ALLOWED" -> "Az email+jelszó belépés nincs bekapcsolva a Firebase konzolban."
        "ERROR_NETWORK_REQUEST_FAILED" -> "Nincs internetkapcsolat."
        else -> "Hitelesítési hiba ($code)."
    }
}
