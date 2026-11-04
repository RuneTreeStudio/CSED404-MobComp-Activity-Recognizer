package com.example.csed404_mobcomp_assignment_1_sensor_monitor

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.csed404_mobcomp_assignment_1_sensor_monitor.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnCollectData.setOnClickListener {
            startActivity(Intent(this, CollectorActivity::class.java))
        }

        binding.btnStartDetecting.setOnClickListener {
            Toast.makeText(this, "Phase 2: Real-time Classifier", Toast.LENGTH_SHORT).show()
        }
    }
}
