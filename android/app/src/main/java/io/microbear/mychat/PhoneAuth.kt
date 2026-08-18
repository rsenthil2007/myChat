package io.microbear.mychat

import android.app.Activity
import com.google.firebase.FirebaseException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.PhoneAuthCredential
import com.google.firebase.auth.PhoneAuthOptions
import com.google.firebase.auth.PhoneAuthProvider
import java.util.concurrent.TimeUnit

fun toE164(raw: String): String {
    val trimmed = raw.trim()
    val digits = trimmed.filter { it.isDigit() }
    return when {
        trimmed.startsWith("+") && digits.length >= 10 -> "+" + digits
        digits.length == 10 -> "+91$digits"
        digits.startsWith("91") && digits.length == 12 -> "+$digits"
        digits.isNotEmpty() -> "+$digits"
        else -> trimmed
    }
}

class PhoneAuthHelper(
    private val onCodeSent: () -> Unit,
    private val onIdToken: (String) -> Unit,
    private val onError: (String) -> Unit,
) {
    private val auth = FirebaseAuth.getInstance()
    var verificationId: String? = null
        private set

    fun sendSms(activity: Activity, e164: String) {
        val options = PhoneAuthOptions.newBuilder(auth)
            .setPhoneNumber(e164)
            .setTimeout(60L, TimeUnit.SECONDS)
            .setActivity(activity)
            .setCallbacks(object : PhoneAuthProvider.OnVerificationStateChangedCallbacks() {
                override fun onVerificationCompleted(credential: PhoneAuthCredential) {
                    signIn(credential)
                }

                override fun onVerificationFailed(e: FirebaseException) {
                    onError(e.localizedMessage ?: "Could not send SMS. Check the number and try again.")
                }

                override fun onCodeSent(id: String, token: PhoneAuthProvider.ForceResendingToken) {
                    verificationId = id
                    onCodeSent()
                }
            })
            .build()
        PhoneAuthProvider.verifyPhoneNumber(options)
    }

    fun confirmSms(code: String) {
        val id = verificationId
        if (id.isNullOrBlank()) {
            onError("Request an SMS code first.")
            return
        }
        val trimmed = code.filter { it.isDigit() }
        if (trimmed.length < 4) {
            onError("Enter the 6-digit SMS code.")
            return
        }
        signIn(PhoneAuthProvider.getCredential(id, trimmed))
    }

    fun currentIdToken(done: (String?) -> Unit) {
        val user = auth.currentUser
        if (user == null) {
            done(null)
            return
        }
        user.getIdToken(true).addOnCompleteListener { task ->
            done(if (task.isSuccessful) task.result?.token else null)
        }
    }

    private fun signIn(credential: PhoneAuthCredential) {
        auth.signInWithCredential(credential).addOnCompleteListener { task ->
            if (!task.isSuccessful) {
                onError(task.exception?.localizedMessage ?: "SMS code was not accepted.")
                return@addOnCompleteListener
            }
            val user = task.result?.user
            if (user == null) {
                onError("SMS sign-in failed.")
                return@addOnCompleteListener
            }
            user.getIdToken(true).addOnCompleteListener { tokenTask ->
                val token = tokenTask.result?.token
                if (!tokenTask.isSuccessful || token.isNullOrBlank()) {
                    onError("Could not read the Firebase session.")
                } else {
                    onIdToken(token)
                }
            }
        }
    }
}
