package com.am.voice
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
class MainActivity : AppCompatActivity() {
 private lateinit var status:TextView; private lateinit var heard:TextView; private lateinit var start:TextView
 override fun onCreate(savedInstanceState:Bundle?){super.onCreate(savedInstanceState);setContentView(R.layout.activity_main)
 status=findViewById(R.id.statusText);heard=findViewById(R.id.heardText);start=findViewById(R.id.startButton)
 start.setOnClickListener{if(hasPermissions())startAssistant() else requestNeededPermissions()}}
 private fun hasPermissions():Boolean=ContextCompat.checkSelfPermission(this,Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED
 private fun requestNeededPermissions(){val p= mutableListOf(Manifest.permission.RECORD_AUDIO);if(Build.VERSION.SDK_INT>=33)p.add(Manifest.permission.POST_NOTIFICATIONS);ActivityCompat.requestPermissions(this,p.toTypedArray(),41)}
 private fun startAssistant(){ContextCompat.startForegroundService(this,Intent(this,VoiceService::class.java).setAction(VoiceService.ACTION_START));status.text="در حال گوش‌دادن...";heard.text="بگو: سلام AM";start.text="توقف دستیار";start.setOnClickListener{stopService(Intent(this,VoiceService::class.java));status.text="دستیار متوقف شد";heard.text="برای شروع دوباره دکمه را بزن";start.text="شروع گوش‌دادن";start.setOnClickListener{if(hasPermissions())startAssistant() else requestNeededPermissions()}}}
 override fun onRequestPermissionsResult(requestCode:Int,permissions:Array<out String>,grantResults:IntArray){super.onRequestPermissionsResult(requestCode,permissions,grantResults);if(requestCode==41&&grantResults.isNotEmpty()&&grantResults[0]==PackageManager.PERMISSION_GRANTED)startAssistant() else status.text="مجوز میکروفون لازم است"}
}
