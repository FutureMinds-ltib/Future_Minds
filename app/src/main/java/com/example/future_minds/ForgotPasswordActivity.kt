package com.example.future_minds

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.auth.FirebaseAuth

class ForgotPasswordActivity : AppCompatActivity() {

    private lateinit var auth: FirebaseAuth

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_forgot_password)

        auth = FirebaseAuth.getInstance()

        val etEmail = findViewById<EditText>(R.id.et_reset_email)
        val btnSendReset = findViewById<Button>(R.id.btn_send_reset)
        val btnBack = findViewById<TextView>(R.id.btn_back_to_login) // Changed to TextView

        btnSendReset.setOnClickListener {
            val email = etEmail.text.toString().trim()

            if (email.isEmpty()) {
                etEmail.error = "Introduceți email-ul!"
                return@setOnClickListener
            }

            // Trimite email-ul de resetare parola prin Firebase
            auth.sendPasswordResetEmail(email)
                .addOnCompleteListener { task ->
                    if (task.isSuccessful) {
                        Toast.makeText(
                            this,
                            "Email de resetare trimis cu succes la $email",
                            Toast.LENGTH_LONG
                        ).show()
                        finish() // Ne întoarcem la ecranul de Login
                    } else {
                        Toast.makeText(
                            this,
                            "Eroare: ${task.exception?.message}",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
        }

        btnBack.setOnClickListener {
            finish()
        }
    }
}
