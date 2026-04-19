package com.example.future_minds

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore

class RegisterActivity : AppCompatActivity() {

    private lateinit var auth: FirebaseAuth
    private lateinit var db: FirebaseFirestore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_register)

        auth = FirebaseAuth.getInstance()
        db = FirebaseFirestore.getInstance()

        // In noul design, et_register_username este folosit pentru "Your Name"
        val etUsername = findViewById<EditText>(R.id.et_register_username)
        val etEmail = findViewById<EditText>(R.id.et_register_email)
        val etPassword = findViewById<EditText>(R.id.et_register_password)
        val etPhone = findViewById<EditText>(R.id.et_register_phone)
        val btnRegister = findViewById<Button>(R.id.btn_do_register)
        val btnBack = findViewById<TextView>(R.id.btn_back_to_login_from_reg) // Changed to TextView in new design

        btnRegister.setOnClickListener {
            val username = etUsername.text.toString().trim()
            val email = etEmail.text.toString().trim()
            val pass = etPassword.text.toString().trim()
            val phone = etPhone.text.toString().trim()
            val trustFactor = 100 

            if (username.isNotEmpty() && email.isNotEmpty() && pass.isNotEmpty() && phone.isNotEmpty()) {
                auth.createUserWithEmailAndPassword(email, pass)
                    .addOnCompleteListener(this) { task ->
                        if (task.isSuccessful) {
                            val user = auth.currentUser

                            val userData = hashMapOf(
                                "username" to username,
                                "email" to email,
                                "phone" to phone,
                                "phoneVerified" to false,
                                "trustFactor" to trustFactor
                            )

                            user?.uid?.let { uid ->
                                db.collection("users").document(uid)
                                    .set(userData)
                                    .addOnSuccessListener {
                                        user.sendEmailVerification()
                                            .addOnCompleteListener { verifyTask ->
                                                if (verifyTask.isSuccessful) {
                                                    Toast.makeText(this, getString(R.string.account_created_verify), Toast.LENGTH_LONG).show()
                                                    auth.signOut()
                                                    finish()
                                                } else {
                                                    Toast.makeText(this, getString(R.string.error_sending_verification), Toast.LENGTH_SHORT).show()
                                                }
                                            }
                                    }
                                    .addOnFailureListener { e ->
                                        Toast.makeText(this, getString(R.string.error_saving_data) + ": ${e.message}", Toast.LENGTH_SHORT).show()
                                    }
                            }
                        } else {
                            Toast.makeText(this, "Error: ${task.exception?.message}", Toast.LENGTH_SHORT).show()
                        }
                    }
            } else {
                Toast.makeText(this, getString(R.string.fill_all_fields), Toast.LENGTH_SHORT).show()
            }
        }

        btnBack.setOnClickListener {
            finish()
        }
    }
}
