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
 private lateinit var outputType:Spinner
 private lateinit var shortSeconds:Spinner
 private lateinit var spinner:ProgressBar
 private lateinit var sourceLabel:TextView
 private var exported:File?=null
 private var publicVideo:Uri?=null
 private lateinit var shareButton:Button
 private lateinit var summary:TextView
 private lateinit var quality:Spinner
 private lateinit var batchMode:CheckBox
 private var activeTransformer:Transformer?=null

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
  card.addView(txt("03   OUTPUT FORMAT",13f,accent,true))
  outputType=Spinner(this).apply {
   adapter=ArrayAdapter(this@MainActivity,android.R.layout.simple_spinner_dropdown_item,listOf("Long Video (custom minutes)","YouTube Short (9:16 source recommended)"))
  }
  card.addView(outputType)
  card.addView(txt("SHORT LENGTH",13f,accent,true))
  shortSeconds=Spinner(this).apply {
   adapter=ArrayAdapter(this@MainActivity,android.R.layout.simple_spinner_dropdown_item,listOf("Auto Fit (15–60 sec)","15 seconds","30 seconds","45 seconds","60 seconds"))
  }
  card.addView(shortSeconds)
  card.addView(txt("04   EDITING STYLE",13f,accent,true))
  mode=Spinner(this)
  val choices=listOf("Full Remix (Rearrange All)","High-Energy Remix","Story Flow (Chronological)","Reverse Highlights")
  mode.adapter=ArrayAdapter(this,android.R.layout.simple_spinner_dropdown_item,choices)
  card.addView(mode)
  card.addView(txt("05   EXPORT QUALITY",13f,accent,true))
  quality=Spinner(this).apply {
   adapter=ArrayAdapter(this@MainActivity,android.R.layout.simple_spinner_dropdown_item,listOf("Balanced • H.264 + AAC","Original video codec when supported"))
  }
  card.addView(quality)
  batchMode=CheckBox(this).apply {
   text="MONSTER BATCH  •  1 long + 3 different Shorts"
   setTextColor(accent);isChecked=true
   buttonTintList=android.content.res.ColorStateList.valueOf(accent)
  }
  card.addView(batchMode)
  root.addView(card)
  root.addView(txt("Full Remix rearranges clips each export. Auto Fit scans the entire source for a strong continuous 15–60s passage. Vertical cropping is not yet available.",12f,Color.LTGRAY))
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
  summary=txt("No export plan yet.",13f,accent);root.addView(summary)
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
 private data class Shot(val start:Long,val end:Long,val score:Double=0.0)
 private fun signature(b:Bitmap):IntArray {
  val small=Bitmap.createScaledBitmap(b,12,8,true)
  val px=IntArray(96);small.getPixels(px,0,12,0,0,12,8);small.recycle()
  return IntArray(96) { i->val p=px[i];(((p shr 16) and 255)*3+((p shr 8) and 255)*6+(p and 255))/10 }
 }
 private fun diff(a:IntArray,b:IntArray)=a.indices.sumOf { abs(a[it]-b[it]) }.toDouble()/a.size
 private fun analyze(uri:Uri,target:Long,style:Int):List<Shot> {
  val r=MediaMetadataRetriever()
  r.setDataSource(this,uri)
  try {
   val duration=r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?:error("Duration unavailable")
   require(duration>1500) { "Video too short" }
   // Sample at most ~500 frames to keep analysis bounded on mobile devices.
   val step=max(1400L,duration/500L)
   val boundaries=mutableListOf(0L)
   val motion=mutableListOf<Pair<Long,Double>>()
   var prev:IntArray?=null
   var t=0L
   while(t<duration) {
    val frame=r.getFrameAtTime(t*1000,MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
    if(frame!=null) {
     val current=signature(frame)
     val old=prev
     if(old!=null) {
      val change=diff(old,current)
      motion.add(t to change)
      // A large visual discontinuity is a likely scene boundary.
      if(change>29.0 && t-boundaries.last()>=2200L)boundaries.add(t)
     }
     prev=current;frame.recycle()
    }
    t+=step
   }
   boundaries.add(duration)
   // Split unusually long shots; do not force every clip to a fixed length.
   val candidates=boundaries.zipWithNext { a,b->Shot(a,b) }.flatMap { s->
    val pieces=ceil((s.end-s.start)/14000.0).toInt().coerceAtLeast(1)
    (0 until pieces).map { i->
     Shot(s.start+(s.end-s.start)*i/pieces,s.start+(s.end-s.start)*(i+1)/pieces)
    }
   }.filter { it.end-it.start>=1100L }.map { shot->
    val changes=motion.filter { it.first>=shot.start && it.first<shot.end }.map { it.second }
    val activity=if(changes.isEmpty())0.0 else changes.average()
    // Penalize almost-static footage and extreme flashes/cut artifacts.
    val score=activity.coerceIn(0.0,28.0) + min(shot.end-shot.start,11000L)/2500.0
    shot.copy(score=score)
   }
   if(candidates.isEmpty())error("No usable video shots detected")
   // Rank first, then fill the requested duration with diverse non-overlapping shots.
   val ranked=if(style==0) candidates else candidates.sortedByDescending { it.score }
   val chosen=mutableListOf<Shot>()
   var remaining=min(target,duration)
   for(s in ranked) {
    if(remaining<1100L)break
    val length=min(s.end-s.start,remaining)
    if(length<1100L)continue
    chosen.add(s.copy(end=s.start+length))
    remaining-=length
   }
   // Source-order mode preserves narrative continuity; shuffle is deterministic per video.
   return when(style) {
    2->chosen.sortedBy { it.start }
    3->chosen.sortedByDescending { it.start }
    else->{
     // A new random ordering on each export, including repeated exports of the same source.
     val shuffled=chosen.shuffled()
     if(shuffled.size>1 && shuffled.zipWithNext().all { (a,b)->a.start<b.start }) shuffled.reversed() else shuffled
    }
   }
  }finally { r.release() }
 }
 private fun analyzeAutoShort(uri:Uri,style:Int):List<Shot> {
  // Scan the entire source, then find the strongest continuous 15–60 second passage.
  val r=MediaMetadataRetriever()
  r.setDataSource(this,uri)
  try {
   val duration=r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?:error("Duration unavailable")
   require(duration>=15000L) { "Auto Fit needs a source at least 15 seconds long" }
   val step=max(1000L,duration/600L)
   val samples=mutableListOf<Pair<Long,Double>>()
   var previous:IntArray?=null
   var t=0L
   while(t<duration) {
    val frame=r.getFrameAtTime(t*1000L,MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
    if(frame!=null) {
     val now=signature(frame)
     previous?.let { samples.add(t to diff(it,now).coerceIn(0.0,45.0)) }
     previous=now
     frame.recycle()
    }
    t+=step
   }
   if(samples.isEmpty())return listOf(Shot(0L,min(30000L,duration)))
   // 15, 20, 30, 40, 50 and 60 second candidates; preserve each selected passage intact.
   val lengths=listOf(15000L,20000L,30000L,40000L,50000L,60000L).filter { it<=duration }
   var bestStart=0L
   var bestLength=15000L
   var bestScore=Double.NEGATIVE_INFINITY
   for(length in lengths) {
    for((start,_) in samples) {
     if(start+length>duration)continue
     val inside=samples.filter { it.first>=start && it.first<start+length }
     if(inside.isEmpty())continue
     val avg=inside.map { it.second }.average()
     // Favor dynamic footage without rewarding extremely short clips.
     val score=avg+min(length,45000L)/15000.0
     if(score>bestScore) { bestScore=score;bestStart=start;bestLength=length }
    }
   }
   return listOf(Shot(bestStart,bestStart+bestLength,bestScore))
  } finally { r.release() }
 }
 private fun startExport() {
  if(busy)return
  val uri=source?:return
  val mins=minutes.text.toString().toLongOrNull()?.coerceIn(1,180)?:10L
  val isShort=outputType.selectedItemPosition==1
  val autoShort=isShort && shortSeconds.selectedItemPosition==0
  val targetMs=if(isShort) {
   if(autoShort) 60000L else listOf(15L,30L,45L,60L)[shortSeconds.selectedItemPosition-1]*1000L
  } else mins*60000L
  val style=mode.selectedItemPosition
  val batch=batchMode.isChecked
  val qualityChoice=quality.selectedItemPosition
  publicVideo=null;shareButton.isEnabled=false
  busy=true;action.isEnabled=false;spinner.visibility=android.view.View.VISIBLE;message("Analyzing video...")
  thread {
   try {
    val jobs=if(batch) {
     val longShots=analyze(uri,mins*60000L,1)
     val fast=analyze(uri,30000L,1)
     val hook=analyze(uri,45000L,0)
     val story=analyzeAutoShort(uri,2)
     listOf(
      "Monster_Long" to longShots,
      "Short_Energy" to fast,
      "Short_Curiosity" to hook,
      "Short_Story" to story
     )
    } else {
     listOf((if(isShort)"Monster_Short" else "Monster_Long") to
      (if(autoShort)analyzeAutoShort(uri,style) else analyze(uri,targetMs,style)))
    }
    require(jobs.all { it.second.isNotEmpty() }) { "Not enough usable scenes" }
    val freeSpace=(getExternalFilesDir(Environment.DIRECTORY_MOVIES)?:filesDir).usableSpace
    require(freeSpace>300L*1024*1024) { "Please free at least 300 MB before batch export" }
    runOnUiThread {
     summary.text="EXPORT PLAN  •  ${jobs.size} MP4 videos  •  ${jobs.sumOf { it.second.size }} selected segments"
    }
    runOnUiThread { renderJobs(uri,jobs,qualityChoice,0,mutableListOf()) }
   }catch(e:Exception) {
    runOnUiThread { busy=false;action.isEnabled=true;spinner.visibility=android.view.View.GONE;status.text="Error: ${e.message}" }
   }
  }
 }
 private fun renderJobs(
  uri:Uri,
  jobs:List<Pair<String,List<Shot>>>,
  qualityChoice:Int,
  index:Int,
  saved:MutableList<Uri>
 ) {
  if(index>=jobs.size) {
   busy=false;action.isEnabled=true;spinner.visibility=android.view.View.GONE
   publicVideo=saved.firstOrNull();shareButton.isEnabled=saved.isNotEmpty()
   status.text="Batch complete: ${saved.size}/${jobs.size} videos saved in Movies/ClipForge"
   return
  }
  val (name,shots)=jobs[index]
  status.text="Rendering ${index+1}/${jobs.size}: ${name}..."
  val items=shots.map { s->
   val media=MediaItem.Builder().setUri(uri).setClippingConfiguration(
    MediaItem.ClippingConfiguration.Builder().setStartPositionMs(s.start).setEndPositionMs(s.end).build()
   ).build()
   EditedMediaItem.Builder(media).build()
  }
  val composition=Composition.Builder(EditedMediaItemSequence.Builder(items).build()).build()
  val dir=getExternalFilesDir(Environment.DIRECTORY_MOVIES)?:filesDir
  val output=File(dir,"${name}_${System.currentTimeMillis()}.mp4")
  try {
   val builder=Transformer.Builder(this)
   if(qualityChoice==0)builder.setVideoMimeType(MimeTypes.VIDEO_H264).setAudioMimeType(MimeTypes.AUDIO_AAC)
   activeTransformer=builder.addListener(object:Transformer.Listener {
    override fun onCompleted(composition:Composition,exportResult:ExportResult) {
     status.text="Saving ${index+1}/${jobs.size} to gallery..."
     thread {
      val gallery=try { saveToGallery(output) }catch(_:Exception){null}
      runOnUiThread {
       if(gallery!=null)saved.add(gallery)
       if(index==0)exported=output
       renderJobs(uri,jobs,qualityChoice,index+1,saved)
      }
     }
    }
    override fun onError(composition:Composition,exportResult:ExportResult,exportException:ExportException) {
     busy=false;action.isEnabled=true;spinner.visibility=android.view.View.GONE
     status.text="Export ${index+1}/${jobs.size} failed: ${exportException.message}. Saved ${saved.size} earlier videos."
    }
   }).build()
   activeTransformer?.start(composition,output.absolutePath)
  }catch(e:Exception) {
   busy=false;action.isEnabled=true;spinner.visibility=android.view.View.GONE
   status.text="Export ${index+1}/${jobs.size} failed: ${e.message}"
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