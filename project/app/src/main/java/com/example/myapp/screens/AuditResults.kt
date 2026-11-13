package com.example.myapp
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import android.view.View
class AuditResults : AppCompatActivity() {
	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		setContentView(R.layout.activity_audit_results)
		findViewById<View>(R.id.ryp73yew161)?.visibility = View.GONE
		val button1: View = findViewById(R.id.rlpcxivvxlkf)
		button1.setOnClickListener {
			println("Pressed")
		}
		val button2: View = findViewById(R.id.r048oo1t9va7k)
		button2.setOnClickListener {
			println("Pressed")
		}
		val button3: View = findViewById(R.id.r28g570vah4c)
		button3.setOnClickListener {
			println("Pressed")
		}
	}
}