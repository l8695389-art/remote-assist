package com.example.remoteassist

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.example.remoteassist.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnHostMode.setOnClickListener {
            startActivity(Intent(this, HostActivity::class.java))
        }
        binding.btnControllerMode.setOnClickListener {
            startActivity(Intent(this, ControllerActivity::class.java))
        }
    }
}
