package com.am.voice
import android.app.*
import android.content.Intent
import android.media.*
import android.hardware.camera2.CameraManager
import android.os.*
import android.provider.Settings
import android.speech.tts.TextToSpeech
import androidx.core.app.NotificationCompat
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import kotlin.concurrent.thread
class VoiceService:Service(){
 companion object{const val ACTION_START="com.am.voice.START";private const val CHANNEL="am_voice_channel";private const val NID=7001}
 @Volatile private var running=false
 private var worker:Thread?=null;private var recorder:AudioRecord?=null;private var model:Model?=null;private var recognizer:Recognizer?=null
 private var wakeUntil=0L;private var tts:TextToSpeech?=null
 override fun onCreate(){super.onCreate();val ch=NotificationChannel(CHANNEL,"AM Voice",NotificationManager.IMPORTANCE_LOW);getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
 startForeground(NID,notification("آماده شنیدن سلام AM"));tts=TextToSpeech(this){s->if(s==TextToSpeech.SUCCESS)tts?.language=Locale("fa","IR")}}
 override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int{if(!running)startListening();return START_STICKY}
 private fun startListening(){running=true;worker=thread(name="AMVoiceAudio"){
 try{
 val dir=File(filesDir,"vosk-model")
 if(!File(dir,"conf/model.conf").exists()){copyAssets("vosk-model",dir)}
 if(!File(dir,"conf/model.conf").exists())throw IllegalStateException("مدل فارسی در APK نیست")
 model=Model(dir.absolutePath);recognizer=Recognizer(model,16000f)
 val min=AudioRecord.getMinBufferSize(16000,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT)
 recorder=AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION,16000,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT,maxOf(min,8192))
 if(recorder?.state!=AudioRecord.STATE_INITIALIZED)throw IllegalStateException("میکروفون آماده نیست")
 recorder?.startRecording();update("در انتظار: سلام AM");val buffer=ByteArray(4096)
 while(running&&!Thread.currentThread().isInterrupted){val n=recorder?.read(buffer,0,buffer.size)?:-1;if(n>0&&recognizer?.acceptWaveForm(buffer,n)==true){val heard=JSONObject(recognizer!!.result).optString("text").lowercase(Locale.ROOT);if(heard.isNotBlank())process(heard)}}
 }catch(e:Exception){update("خطا: ${e.message}");stopSelf()}finally{try{recorder?.stop()}catch(_:Exception){};recorder?.release();recorder=null;recognizer?.close();recognizer=null;model?.close();model=null}
 }}
 private fun copyAssets(src:String,dst:File){val names=assets.list(src)?:return;if(names.isEmpty()){dst.parentFile?.mkdirs();assets.open(src).use{i->dst.outputStream().use{o->i.copyTo(o)}}}else{dst.mkdirs();names.forEach{copyAssets("$src/$it",File(dst,it))}}}
 private fun process(s:String){val now=System.currentTimeMillis();if(s.contains("سلام")&&(s.contains("ام")||s.contains("ای ام"))){wakeUntil=now+9000;update("بیدار شدم؛ فرمانت را بگو");say("بله، گوش می‌کنم");return};if(now>wakeUntil)return;wakeUntil=0;update("فرمان: $s");command(s)}
 private fun command(s:String){when{
 s.contains("کروم")||s.contains("chrome")->open("com.android.chrome","کروم")
 s.contains("واتساپ")||s.contains("whatsapp")->open("com.whatsapp","واتساپ")
 s.contains("تلگرام")||s.contains("telegram")->open("org.telegram.messenger","تلگرام")
 s.contains("دوربین")->launch(Intent("android.media.action.IMAGE_CAPTURE"),"دوربین")
 s.contains("تنظیمات")->launch(Intent(Settings.ACTION_SETTINGS),"تنظیمات")
 s.contains("وای فای")||s.contains("وایفای")->launch(Intent(Settings.Panel.ACTION_WIFI),"پنل وای‌فای")
 s.contains("چراغ")&&s.contains("روشن")->torch(true)
 s.contains("چراغ")&&s.contains("خاموش")->torch(false)
 s.contains("صدا")&&s.contains("کم")->volume(-1)
 s.contains("صدا")&&s.contains("زیاد")->volume(1)
 s.contains("ساعت")->say("ساعت "+SimpleDateFormat("HH:mm",Locale("fa","IR")).format(Date()))
 s.contains("توقف")||s.contains("خداحافظ")->stopSelf()
 else->say("این فرمان را هنوز بلد نیستم")
 }}
 private fun open(pkg:String,label:String){val i=packageManager.getLaunchIntentForPackage(pkg);if(i==null){say("$label نصب نیست");return};i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);startActivity(i);say("$label باز شد")}
 private fun launch(i:Intent,label:String){try{i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);startActivity(i);say("$label باز شد")}catch(_:Exception){say("امکان باز کردن $label نیست")}}
 private fun torch(on:Boolean){try{val cm=getSystemService(CAMERA_SERVICE) as CameraManager;val id=cm.cameraIdList.firstOrNull{cm.getCameraCharacteristics(it).get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE)==true}?:return say("چراغ‌قوه موجود نیست");cm.setTorchMode(id,on);say(if(on)"چراغ روشن شد" else "چراغ خاموش شد")}catch(_:Exception){say("کنترل چراغ‌قوه ممکن نیست")}}
 private fun volume(d:Int){val a=getSystemService(AUDIO_SERVICE) as AudioManager;a.adjustStreamVolume(AudioManager.STREAM_MUSIC,if(d>0)AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER,AudioManager.FLAG_SHOW_UI);say("صدا تغییر کرد")}
 private fun say(s:String){update(s);val e=tts?:return;val v=e.voice;if(v?.locale?.language=="fa"&&v.isNetworkConnectionRequired.not())e.speak(s,TextToSpeech.QUEUE_FLUSH,null,"am${System.currentTimeMillis()}")}
 private fun notification(s:String):Notification{val pi=PendingIntent.getActivity(this,0,Intent(this,MainActivity::class.java),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE);return NotificationCompat.Builder(this,CHANNEL).setSmallIcon(android.R.drawable.ic_btn_speak_now).setContentTitle("AM Voice").setContentText(s).setContentIntent(pi).setOngoing(true).build()}
 private fun update(s:String){getSystemService(NotificationManager::class.java).notify(NID,notification(s))}
 override fun onDestroy(){running=false;worker?.interrupt();try{recorder?.stop()}catch(_:Exception){};tts?.stop();tts?.shutdown();super.onDestroy()}
 override fun onBind(i:Intent?):IBinder?=null
}