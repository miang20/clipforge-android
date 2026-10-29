package com.clipforge.studio

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.widget.*
import android.graphics.Color
import android.graphics.Typeface
import android.content.ContentValues
import android.provider.MediaStore
import android.os.Build
import java.io.FileInputStream
import android.graphics.drawable.GradientDrawable
import android.text.InputType
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
 private lateinit var mode:Spinner
 private lateinit var spinner:ProgressBar
 private lateinit var sourceLabel:TextView
 private var exported:File?=null
 private var publicVideo:Uri?=null
 private lateinit var shareButton:Button

 private val bg=Color.rgb(10,15,29)
 private val panel=Color.rgb(24,33,53)
 private val accent=Color.rgb(72,216,199)
 private fun dp(v:Int)=(v*resources.displayMetrics.density).toInt()
 private fun shape(color:Int, radius:Int=18)=GradientDrawable().apply { setColor(color);cornerRadius=dp(radius).toFloat() }
 private fun txt(s:String,size:Float,color:Int=Color.WHITE,bold:Boolean=false)=TextView(this).apply {
  text=s;textSize=size;setTextColor(color);if(bold)setTypeface(null,Typeface.BOLD);setPadding(dp(2),dp(7),dp(2),dp(7))
 }
 private fun button(s:String,color:Int)=Button(this).apply { text=s;setTextColor(if(color==accent)bg else Color.WHITE);background=shape(color,14);isAllCaps=false }
 override fun onCreate(savedInstanceState:Bundle?) {
  super.onCreate(savedInstanceState)
  window.statusBarColor=bg;window.navigationBarColor=bg
  val root=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;setPadding(dp(22),dp(24),dp(22),dp(32));setBackgroundColor(bg) }
  root.addView(txt("CLIPFORGE  /  PRO STUDIO",13f,accent,true))
  root.addView(txt("Make every cut count.",29f,Color.WHITE,true))
  root.addView(txt("Offline auto-editing • Original audio • MP4",13f,Color.LTGRAY))
  val card=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;setPadding(dp(18),dp(18),dp(18),dp(18));background=shape(panel) }
  card.addView(txt("01   SOURCE VIDEO",13f,accent,true))
  sourceLabel=txt("No video selected",14f,Color.LTGRAY);card.addView(sourceLabel)
  val select=button("Choose video from phone",Color.rgb(49,66,96)).apply { setOnClickListener {
   startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE);type="video/*" },100)
  }}
  card.addView(select)
  card.addView(txt("02   TARGET DURATION (MINUTES)",13f,accent,true))
  minutes=EditText(this).apply { inputType=InputType.TYPE_CLASS_NUMBER;setText("10");setTextColor(Color.WHITE);setHintTextColor(Color.GRAY);hint="10–15 recommended";backgroundTintList=android.content.res.ColorStateList.valueOf(accent) }
  card.addView(minutes)
  card.addView(txt("03   EDITING STYLE",13f,accent,true))
  mode=Spinner(this)
  val choices=listOf("Dynamic Shuffle","Chronological Highlights","Reverse Sequence")
  mode.adapter=ArrayAdapter(this,android.R.layout.simple_spinner_dropdown_item,choices)
  card.addView(mode)
  root.addView(card)
  root.addView(txt("Visual scene detection identifies cuts and selects variable-length segments. It does not understand story meaning.",12f,Color.LTGRAY))
  action=button("Generate edited MP4",accent).apply { isEnabled=false;setOnClickListener { startExport() } }
  root.addView(action)
  shareButton=button("Share exported MP4",Color.rgb(49,66,96)).apply {
   isEnabled=false
   setOnClickListener {
    val link=publicVideo
    if(link==null) { status.text="Export is not yet available in gallery.";return@setOnClickListener }
    startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
     type="video/mp4";putExtra(Intent.EXTRA_STREAM,link);addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    },"Share ClipForge video"))
   }
  }
  root.addView(shareButton)
  spinner=ProgressBar(this).apply { visibility=android.view.View.GONE;isIndeterminate=true;indeterminateTintList=android.content.res.ColorStateList.valueOf(accent) }
  root.addView(spinner)
  status=txt("Ready when you are.",14f,Color.LTGRAY);root.addView(status)
  root.addView(txt("Finished videos are saved in Movies/ClipForge in your phone gallery on Android 10+. Keep the app open during export.",12f,Color.GRAY))
  setContentView(ScrollView(this).apply { addView(root) })
 }
 override fun onActivityResult(requestCode:Int,resultCode:Int,data:Intent?) {
  super.onActivityResult(requestCode,resultCode,data)
  if(requestCode==100 && resultCode==RESULT_OK && data?.data!=null) {
   source=data.data
   try { contentResolver.takePersistableUriPermission(source!!,Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_:Exception) {}
   sourceLabel.text="Video selected: "+(data.data?.lastPathSegment?.takeLast(48) ?: "source");status.text="Ready to analyze.";action.isEnabled=true
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
   val shuffled=when(mode.selectedItemPosition){1->shots;2->shots.reversed();else->shots.shuffled()}
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
  busy=true;action.isEnabled=false;spinner.visibility=android.view.View.VISIBLE;message("Analyzing video...")
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
        busy=false;action.isEnabled=true;spinner.visibility=android.view.View.GONE
        exported=output
        status.text="Video rendered. Saving to phone gallery..."
        thread {
         try {
          val gallery=saveToGallery(output)
          runOnUiThread {
           publicVideo=gallery
           shareButton.isEnabled=gallery!=null
           status.text=if(gallery!=null) "Success! Saved to Movies / ClipForge. Tap Share to send it." else "Export saved in app Movies folder: ${output.absolutePath}"
          }
         } catch(e:Exception) { runOnUiThread { status.text="MP4 exported, but gallery save failed: ${e.message}. File: ${output.absolutePath}" } }
        }
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
 private fun saveToGallery(file:File):Uri? {
  if(Build.VERSION.SDK_INT<29)return null
  val values=ContentValues().apply {
   put(MediaStore.Video.Media.DISPLAY_NAME,file.name)
   put(MediaStore.Video.Media.MIME_TYPE,"video/mp4")
   put(MediaStore.Video.Media.RELATIVE_PATH,Environment.DIRECTORY_MOVIES+"/ClipForge")
   put(MediaStore.Video.Media.IS_PENDING,1)
  }
  val uri=contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI,values)?:return null
  try {
   contentResolver.openOutputStream(uri)?.use { dest->FileInputStream(file).use { it.copyTo(dest) } } ?: error("Cannot open gallery output")
   contentResolver.update(uri,ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING,0) },null,null)
   return uri
  }catch(e:Exception){contentResolver.delete(uri,null,null);throw e}
 }

}