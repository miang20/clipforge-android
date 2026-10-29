package com.clipforge.studio

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.widget.*
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import java.io.File
import kotlin.concurrent.thread
import kotlin.math.*

class MainActivity : Activity() {
 private lateinit var status:TextView
 private lateinit var minutes:EditText
 private lateinit var action:Button
 private var source:Uri?=null
 private var busy=false

 override fun onCreate(savedInstanceState:Bundle?) {
  super.onCreate(savedInstanceState)
  val root=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;setPadding(28,40,28,24) }
  val title=TextView(this).apply { text="CLIPFORGE SMART STUDIO";textSize=24f }
  val info=TextView(this).apply { text="Choose a video. The editor automatically detects visual cuts, varies clip length, shuffles scenes and exports an MP4 with original audio.";textSize=16f }
  val select=Button(this).apply { text="SELECT VIDEO";setOnClickListener {
   startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE);type="video/*" },100)
  }}
  minutes=EditText(this).apply { inputType=2;setText("10");hint="Target minutes" }
  action=Button(this).apply { text="AUTO EDIT & EXPORT MP4";isEnabled=false;setOnClickListener { startExport() } }
  status=TextView(this).apply { text="Select your video.";textSize=15f }
  listOf(title,info,select,minutes,action,status).forEach { root.addView(it) }
  setContentView(ScrollView(this).apply { addView(root) })
 }
 override fun onActivityResult(requestCode:Int,resultCode:Int,data:Intent?) {
  super.onActivityResult(requestCode,resultCode,data)
  if(requestCode==100 && resultCode==RESULT_OK && data?.data!=null) {
   source=data.data
   try { contentResolver.takePersistableUriPermission(source!!,Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_:Exception) {}
   status.text="Video selected. Ready.";action.isEnabled=true
  }
 }
 private fun message(s:String)=runOnUiThread { status.text=s }
 private data class Shot(val start:Long,val end:Long)
 private fun signature(b:Bitmap):IntArray {
  val small=Bitmap.createScaledBitmap(b,12,8,true)
  val px=IntArray(96);small.getPixels(px,0,12,0,0,12,8);small.recycle()
  return IntArray(96) { i->val p=px[i];(((p shr 16) and 255)*3+((p shr 8) and 255)*6+(p and 255))/10 }
 }
 private fun diff(a:IntArray,b:IntArray)=a.indices.sumOf { abs(a[it]-b[it]) }.toDouble()/a.size
 private fun analyze(uri:Uri,target:Long):List<Shot> {
  val r=MediaMetadataRetriever()
  r.setDataSource(this,uri)
  try {
   val duration=r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?:error("Duration unavailable")
   require(duration>1500) { "Video too short" }
   val step=max(1800L,duration/420L)
   val boundaries=mutableListOf(0L)
   var prev:IntArray?=null
   var t=0L
   while(t<duration) {
    val frame=r.getFrameAtTime(t*1000,MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
    if(frame!=null) {
     val current=signature(frame)
     val old=prev
     if(old!=null && diff(old,current)>24 && t-boundaries.last()>2500)boundaries.add(t)
     prev=current;frame.recycle()
    }
    t+=step
   }
   boundaries.add(duration)
   val shots=boundaries.zipWithNext { a,b->Shot(a,b) }.flatMap { s->
    val n=ceil((s.end-s.start)/18000.0).toInt().coerceAtLeast(1)
    (0 until n).map { i->
     Shot(s.start+(s.end-s.start)*i/n,s.start+(s.end-s.start)*(i+1)/n)
    }
   }.filter { it.end-it.start>=700 }
   val shuffled=shots.shuffled()
   val picked=mutableListOf<Shot>();var remaining=min(target,duration)
   for(s in shuffled) {
    if(remaining<700)break
    val length=min(s.end-s.start,remaining)
    if(length>=700) { picked.add(Shot(s.start,s.start+length));remaining-=length }
   }
   return picked
  }finally { r.release() }
 }
 private fun startExport() {
  if(busy)return
  val uri=source?:return
  val mins=minutes.text.toString().toLongOrNull()?.coerceIn(1,30)?:10L
  busy=true;action.isEnabled=false;message("Analyzing video...")
  thread {
   try {
    val shots=analyze(uri,mins*60000L)
    if(shots.isEmpty())error("No usable scenes found")
    message("Selected ${shots.size} clips. Exporting...")
    val items=shots.map { s->
     val media=MediaItem.Builder().setUri(uri).setClippingConfiguration(
      MediaItem.ClippingConfiguration.Builder().setStartPositionMs(s.start).setEndPositionMs(s.end).build()
     ).build()
     EditedMediaItem.Builder(media).build()
    }
    val sequence=EditedMediaItemSequence.Builder(items).build()
    val composition=Composition.Builder(sequence).build()
    val dir=getExternalFilesDir(Environment.DIRECTORY_MOVIES)?:filesDir
    val output=File(dir,"ClipForge_${System.currentTimeMillis()}.mp4")
    runOnUiThread {
     Transformer.Builder(this).setVideoMimeType(MimeTypes.VIDEO_H264).setAudioMimeType(MimeTypes.AUDIO_AAC)
      .addListener(object:Transformer.Listener {
       override fun onCompleted(composition:Composition,exportResult:ExportResult) {
        busy=false;action.isEnabled=true
        status.text="Saved: ${output.absolutePath}"
       }
       override fun onError(composition:Composition,exportResult:ExportResult,exportException:ExportException) {
        busy=false;action.isEnabled=true
        status.text="Export failed: ${exportException.message}"
       }
      }).build().start(composition,output.absolutePath)
    }
   }catch(e:Exception) { runOnUiThread { busy=false;action.isEnabled=true;status.text="Error: ${e.message}" } }
  }
 }
}