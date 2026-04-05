package com.example.future_minds

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.FirebaseException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.PhoneAuthCredential
import com.google.firebase.auth.PhoneAuthOptions
import com.google.firebase.auth.PhoneAuthProvider
import com.google.firebase.firestore.FirebaseFirestore
import java.util.concurrent.TimeUnit

class PersonalDataActivity : AppCompatActivity() {

    private lateinit var auth: FirebaseAuth
    private lateinit var db: FirebaseFirestore
    private var verificationId: String? = null

    private lateinit var tvEmail: TextView
    private lateinit var tvPhone: TextView
    private lateinit var ivPhoneStatus: ImageView
    private lateinit var tvPhoneStatusText: TextView
    private lateinit var btnVerifyPhone: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_personal_data)

        auth = FirebaseAuth.getInstance()
        db = FirebaseFirestore.getInstance()

        tvEmail = findViewById(R.id.tv_pd_email)
        tvPhone = findViewById(R.id.tv_pd_phone)
        ivPhoneStatus = findViewById(R.id.iv_phone_status)
        tvPhoneStatusText = findViewById(R.id.tv_phone_status_text)
        btnVerifyPhone = findViewById(R.id.btn_verify_phone)

        // Fixed ClassCastException: btn_pd_back is a TextView in XML
        findViewById<View>(R.id.btn_pd_back).setOnClickListener { finish() }

        btnVerifyPhone.setOnClickListener {
            startPhoneNumberVerification()
        }

        // Add navigation to Guardians and Protected
        findViewById<Button>(R.id.btn_nav_guardians).setOnClickListener {
            startActivity(Intent(this, GuardianActivity::class.java))
        }
        findViewById<Button>(R.id.btn_nav_protected).setOnClickListener {
            startActivity(Intent(this, ProtectedActivity::class.java))
        }

        loadUserData()
    }

    private fun loadUserData() {
        val user = auth.currentUser ?: return
        tvEmail.text = user.email

        db.collection("users").document(user.uid).addSnapshotListener { snapshot, _ ->
            if (snapshot != null && snapshot.exists()) {
                val phone = snapshot.getString("phone") ?: ""
                val isVerified = snapshot.getBoolean("phoneVerified") ?: false

                tvPhone.text = if (phone.isEmpty()) "Nesetat" else phone

                if (isVerified) {
                    ivPhoneStatus.setImageResource(android.R.drawable.checkbox_on_background)
                    ivPhoneStatus.setColorFilter(android.graphics.Color.parseColor("#4CAF50"))
                    tvPhoneStatusText.text = "Verificat"
                    tvPhoneStatusText.setTextColor(android.graphics.Color.parseColor("#4CAF50"))
                    btnVerifyPhone.visibility = View.GONE
                } else {
                    ivPhoneStatus.setImageResource(android.R.drawable.ic_dialog_alert)
                    ivPhoneStatus.setColorFilter(android.graphics.Color.parseColor("#F44336"))
                    tvPhoneStatusText.text = "Neverificat"
                    tvPhoneStatusText.setTextColor(android.graphics.Color.parseColor("#F44336"))
                    btnVerifyPhone.visibility = if (phone.isNotEmpty()) View.VISIBLE else View.GONE
                }
            }
        }
    }

    private fun startPhoneNumberVerification() {
        val phone = tvPhone.text.toString()
        if (phone.isEmpty() || phone == "Nesetat") {
            Toast.makeText(this, "Numărul de telefon nu este setat!", Toast.LENGTH_SHORT).show()
            return
        }

        // Adaugă prefixul de țară dacă lipsește (+40 pentru România)
        val formattedPhone = if (phone.startsWith("+")) phone else "+40${phone.removePrefix("0")}"

        val options = PhoneAuthOptions.newBuilder(auth)
            .setPhoneNumber(formattedPhone)
            .setTimeout(60L, TimeUnit.SECONDS)
            .setActivity(this)
            .setCallbacks(callbacks)
            .build()
        PhoneAuthProvider.verifyPhoneNumber(options)
        
        Toast.makeText(this, "Se trimite SMS către $formattedPhone...", Toast.LENGTH_SHORT).show()
    }

    private val callbacks = object : PhoneAuthProvider.OnVerificationStateChangedCallbacks() {
        override fun onVerificationCompleted(credential: PhoneAuthCredential) {
            verifyPhoneNumberInFirestore()
        }

        override fun onVerificationFailed(e: FirebaseException) {
            Toast.makeText(this@PersonalDataActivity, "Eroare SMS: ${e.message}", Toast.LENGTH_LONG).show()
        }

        override fun onCodeSent(verificationId: String, token: PhoneAuthProvider.ForceResendingToken) {
            this@PersonalDataActivity.verificationId = verificationId
            showCodeInputDialog()
        }
    }

    private fun showCodeInputDialog() {
        val input = EditText(this)
        input.hint = "Codul din 6 cifre"
        input.inputType = android.text.InputType.TYPE_CLASS_NUMBER

        AlertDialog.Builder(this)
            .setTitle("Verificare SMS")
            .setMessage("Introdu codul primit prin SMS:")
            .setView(input)
            .setPositiveButton("Confirmă") { _, _ ->
                val code = input.text.toString().trim()
                if (code.isNotEmpty() && verificationId != null) {
                    val credential = PhoneAuthProvider.getCredential(verificationId!!, code)
                    linkPhoneWithAccount(credential)
                }
            }
            .setNegativeButton("Anulează", null)
            .show()
    }

    private fun linkPhoneWithAccount(credential: PhoneAuthCredential) {
        auth.currentUser?.linkWithCredential(credential)
            ?.addOnCompleteListener(this) { task ->
                if (task.isSuccessful) {
                    verifyPhoneNumberInFirestore()
                } else {
                    Toast.makeText(this, "Codul introdus este incorect!", Toast.LENGTH_SHORT).show()
                }
            }
    }

    private fun verifyPhoneNumberInFirestore() {
        val uid = auth.currentUser?.uid ?: return
        db.collection("users").document(uid).update("phoneVerified", true)
            .addOnSuccessListener {
                Toast.makeText(this, "Număr de telefon verificat cu succes!", Toast.LENGTH_SHORT).show()
            }
    }
}
