package com.example.future_minds

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.auth.FirebaseAuth

class LoginActivity : AppCompatActivity() {

    private lateinit var auth: FirebaseAuth

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.login)

        auth = FirebaseAuth.getInstance()

        // Verificam daca utilizatorul este logat SI daca are email-ul verificat (sau e Guest)
        val currentUser = auth.currentUser
        if (currentUser != null) {
            if (currentUser.isEmailVerified || currentUser.isAnonymous) {
                goToMapScreen()
            }
        }
        
        val etEmail = findViewById<EditText>(R.id.et_email)
        val etPassword = findViewById<EditText>(R.id.et_password)
        val btnLogin = findViewById<Button>(R.id.btn_login)
        val btnRegister = findViewById<TextView>(R.id.btn_register) // Changed to TextView
        val btnGuest = findViewById<Button>(R.id.btn_guest)
        val btnForgotPassword = findViewById<TextView>(R.id.btn_forgot_password) // Changed to TextView

        btnGuest.setOnClickListener {
            auth.signInAnonymously()
                .addOnCompleteListener(this) { task ->
                    if (task.isSuccessful) {
                        goToMapScreen()
                    } else {
                        val errorMsg = task.exception?.message ?: "Unknown error"
                        Toast.makeText(this, getString(R.string.guest_login_failed) + ": $errorMsg", Toast.LENGTH_LONG).show()
                    }
                }
        }

        // Handle Login Click
        btnLogin.setOnClickListener {
            val email = etEmail.text.toString().trim()
            val pass = etPassword.text.toString().trim()

            if (email.isNotEmpty() && pass.isNotEmpty()) {
                auth.signInWithEmailAndPassword(email, pass)
                    .addOnCompleteListener(this) { task ->
                        if (task.isSuccessful) {
                            val user = auth.currentUser
                            if (user != null && (user.isEmailVerified || user.isAnonymous)) {
                                goToMapScreen()
                            } else {
                                Toast.makeText(this, getString(R.string.verify_email_msg), Toast.LENGTH_LONG).show()
                                auth.signOut()
                            }
                        } else {
                            Toast.makeText(this, getString(R.string.login_failed_format, task.exception?.message), Toast.LENGTH_SHORT).show()
                        }
                    }
            } else {
                Toast.makeText(this, getString(R.string.fill_all_fields), Toast.LENGTH_SHORT).show()
            }
        }

        // Redirectionare către ecranul de înregistrare
        btnRegister.setOnClickListener {
            val intent = Intent(this@LoginActivity, RegisterActivity::class.java)
            startActivity(intent)
        }

        btnForgotPassword.setOnClickListener {
            val intent = Intent(this@LoginActivity, ForgotPasswordActivity::class.java)
            startActivity(intent)
        }
    }

    private fun goToMapScreen() {
        val intent = Intent(this, MainActivity::class.java)
        startActivity(intent)
        finish()
    }
}
