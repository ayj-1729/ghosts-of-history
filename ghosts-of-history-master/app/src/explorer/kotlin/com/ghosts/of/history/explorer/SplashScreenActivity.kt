package com.ghosts.of.history.explorer
import androidx.core.content.ContextCompat
import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat

class SplashScreenActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.statusBarColor = ContextCompat.getColor(this, android.R.color.black)

        // Immediately start MapsActivity
        startActivity(Intent(this, MapsActivity::class.java))
        overridePendingTransition(0, 0)
        finish() // End SplashScreenActivity
    }
}





