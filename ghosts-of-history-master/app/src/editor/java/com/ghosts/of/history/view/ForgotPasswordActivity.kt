package com.ghosts.of.history.view

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.ghosts.of.history.R
import com.google.android.material.appbar.MaterialToolbar
import com.google.firebase.Firebase
import com.google.firebase.auth.auth

class ForgotPasswordActivity : AppCompatActivity() {

    private lateinit var emailEditText: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_forgot_password)

        val topAppBar = findViewById<MaterialToolbar>(R.id.topAppBar)
        topAppBar.setNavigationOnClickListener { finish() }

        emailEditText = findViewById(R.id.email_edit_text)

        val sendPasswordResetLinkButton = findViewById<Button>(R.id.send_password_reset_link_button)
        sendPasswordResetLinkButton.setOnClickListener {
            sendPasswordResetLink()
        }
    }

    private fun sendPasswordResetLink() {
        val email = emailEditText.text.toString().trim()
        if (email.isBlank()) {
            Toast.makeText(this, "Please enter an email", Toast.LENGTH_SHORT).show()
            return
        }

        Firebase.auth.sendPasswordResetEmail(email)
            .addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    Toast.makeText(this@ForgotPasswordActivity, "Email sent.", Toast.LENGTH_SHORT).show()
                    finish()
                } else {
                    Toast.makeText(this@ForgotPasswordActivity, "Couldn't send email.", Toast.LENGTH_SHORT).show()
                }
            }
    }
}
