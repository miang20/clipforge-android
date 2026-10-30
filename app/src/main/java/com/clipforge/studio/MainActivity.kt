package com.clipforge.studio

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.media.MediaExtractor
import android.media.MediaFormat
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
import android.view.View
import android.text.InputType
import androidx.media3.common.MediaItem
import androidx.media3.transformer.Effects
import androidx.media3.effect.Presentation
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

 private val bg=Color.rgb(13,3,9)
 private val panel=Color.rgb(34,9,19)
 private val accent=Color.rgb(239,51,62)
 private val soft=Color.rgb(222,178,186)
 private fun dp(v:Int)=(v*resources.displayMetrics.density).toInt()
 private fun shape(color:Int, radius:Int=18)=GradientDrawable().apply { setColor(color);cornerRadius=dp(radius).toFloat() }
 private fun txt(s:String,size:Float,color:Int=Color.WHITE,bold:Boolean=false)=TextView(this).apply {
  text=s;textSize=size;setTextColor(color);if(bold)setTypeface(null,Typeface.BOLD);setPadding(dp(2),dp(7),dp(2),dp(7))
 }
 private fun menu(items:List<String>):ArrayAdapter<String> = object:ArrayAdapter<String>(this,android.R.layout.simple_spinner_item,items) {
  override fun getView(position:Int,convertView:View?,parent:android.view.ViewGroup):View {
   return (super.getView(position,convertView,parent) as TextView).apply {
    setTextColor(Color.WHITE);textSize=14f;setPadding(dp(12),dp(12),dp(8),dp(12))
   }
  }
  override fun getDropDownView(position:Int,convertView:View?,parent:android.view.ViewGroup):View {
   return (super.getDropDownView(position,convertView,parent) as TextView).apply {
    setTextColor(Color.WHITE);setBackgroundColor(panel);setPadding(dp(12),dp(14),dp(8),dp(14))
   }
  }
 }.apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
 private fun button(s:String,color:Int)=Button(this).apply { text=s;setTextColor(if(color==accent)bg else Color.WHITE);background=shape(color,14);isAllCaps=false }
 override fun onCreate(savedInstanceState:Bundle?) {
  super.onCreate(savedInstanceState)
  window.statusBarColor=bg;window.navigationBarColor=bg
  val root=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;setPadding(dp(22),dp(24),dp(22),dp(32));setBackgroundColor(bg) }
  root.addView(txt("◈  CLIPFORGE  /  MONSTER EDITION",13f,accent,true))
  root.addView(txt("UNLEASH THE EDIT.",29f,Color.WHITE,true))
  root.addView(txt("PRIVATE  •  OFFLINE  •  ONE-TAP STUDIO",12f,soft,true))
  val hero=LinearLayout(this).apply {
   orientation=LinearLayout.VERTICAL;setPadding(dp(18),dp(14),dp(18),dp(14))
   background=GradientDrawable(GradientDrawable.Orientation.TL_BR,intArrayOf(Color.rgb(186,7,23),Color.rgb(70,3,18),panel)).apply { cornerRadius=dp(20).toFloat() }
  }
  hero.addView(txt("◈  ONE SOURCE. FOUR EPIC CUTS.",17f,Color.WHITE,true))
  hero.addView(txt("01 CINEMATIC LONG VIDEO   /   03 VERTICAL SHORTS",12f,Color.WHITE,true))
  hero.addView(txt("Offline scene analysis • Original audio • One tap",12f,soft))
  root.addView(hero)
  root.addView(txt(" ",7f))
  val card=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;setPadding(dp(18),dp(18),dp(18),dp(18));background=shape(panel) }
  card.addView(txt("01   SOURCE VIDEO",13f,accent,true))
  sourceLabel=txt("No video selected",14f,Color.LTGRAY);card.addView(sourceLabel)
  val select=button("Choose video from phone",Color.rgb(85,25,39)).apply { setOnClickListener {
   startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE);type="video/*" },100)
  }}
  card.addView(select)
  card.addView(txt("02   LONG VIDEO DURATION (MINUTES)",13f,accent,true))
  minutes=EditText(this).apply { inputType=InputType.TYPE_CLASS_NUMBER;setText("10");setTextColor(Color.WHITE);setHintTextColor(Color.GRAY);hint="10–15 recommended";backgroundTintList=android.content.res.ColorStateList.valueOf(accent) }
  card.addView(minutes)
  val advanced=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;visibility=View.GONE }
  advanced.addView(txt("03   OUTPUT FORMAT",13f,accent,true))
  outputType=Spinner(this).apply {
   adapter=menu(listOf("Long Video (custom minutes)","YouTube Short (9:16 source recommended)"))
  }
  advanced.addView(outputType)
  advanced.addView(txt("SHORT LENGTH",13f,accent,true))
  shortSeconds=Spinner(this).apply {
   adapter=menu(listOf("Auto Fit (15–60 sec)","15 seconds","30 seconds","45 seconds","60 seconds"))
  }
  advanced.addView(shortSeconds)
  advanced.addView(txt("04   EDITING STYLE",13f,accent,true))
  mode=Spinner(this)
  val choices=listOf("Full Remix (Rearrange All)","High-Energy Remix","Story Flow (Chronological)","Reverse Highlights")
  mode.adapter=menu(choices)
  advanced.addView(mode)
  advanced.addView(txt("05   EXPORT QUALITY",13f,accent,true))
  quality=Spinner(this).apply {
   adapter=menu(listOf("Balanced • H.264 + AAC","Original video codec when supported"))
  }
  advanced.addView(quality)
  batchMode=CheckBox(this).apply {
   text="MONSTER BATCH  •  1 long + 3 different Shorts"
   setTextColor(accent);isChecked=true
   buttonTintList=android.content.res.ColorStateList.valueOf(accent)
  }
  advanced.addView(batchMode)
  val toggle=button("⚙  Advanced settings  ▾",Color.rgb(79,24,39)).apply {
   setOnClickListener {
    advanced.visibility=if(advanced.visibility==View.GONE)View.VISIBLE else View.GONE
    text=if(advanced.visibility==View.VISIBLE)"⚙  Hide advanced settings  ▴" else "⚙  Advanced settings  ▾"
   }
  }
  card.addView(toggle)
  card.addView(advanced)
  root.addView(card)
  root.addView(txt("Default: 1 long video + 3 different 9:16 Shorts. Advanced controls are optional.",12f,soft))
  action=button("⚡  UNLEASH 4 VIDEOS",accent).apply { isEnabled=false;setOnClickListener { startExport() } }
  root.addView(action)
  shareButton=button("Share latest long video",Color.rgb(49,66,96)).apply {
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
   sourceLabel.text="✓  Video ready: "+(data.data?.lastPathSegment?.takeLast(48) ?: "source");status.text="Ready to generate four edits.";action.isEnabled=true
  }
 }
 private fun message(s:String)=runOnUiThread { status.text=s }
 private data class Shot(val start:Long,val end:Long,val score:Double=0.0)
 private fun audioActivity(uri:Uri,duration:Long):DoubleArray {
  val bins=64
  val activity=DoubleArray(bins)
  val counts=IntArray(bins)
  val extractor=MediaExtractor()
  try {
   extractor.setDataSource(this,uri,null)
   val track=(0 until extractor.trackCount).firstOrNull { i->
    extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith("audio/")==true
   }?:return activity
   extractor.selectTrack(track)
   val buffer=java.nio.ByteBuffer.allocate(256*1024)
   var n=0
   while(n<1200) {
    buffer.clear()
    val size=extractor.readSampleData(buffer,0)
    if(size<0)break
    val timestamp=extractor.sampleTime
    if(timestamp>=0) {
     val bin=((timestamp/1000.0/duration)*bins).toInt().coerceIn(0,bins-1)
     activity[bin]+=size.toDouble()
     counts[bin]++
    }
    extractor.advance()
    n++
   }
  }catch(_:Exception) {}finally { extractor.release() }
  return DoubleArray(bins) { i->if(counts[i]>0)activity[i]/counts[i] else 0.0 }
 }
 private fun enrichWithAudio(candidates:List<Shot>,duration:Long,uri:Uri):List<Shot> {
  val levels=audioActivity(uri,duration)
  val peak=levels.maxOrNull()?.coerceAtLeast(1.0)?:1.0
  return candidates.map { shot->
   val bin=((shot.start.toDouble()/duration)*levels.size).toInt().coerceIn(0,levels.lastIndex)
   shot.copy(score=shot.score+3.0*(levels[bin]/peak))
  }
 }
 private fun signature(b:Bitmap):IntArray {
  val small=Bitmap.createScaledBitmap(b,12,8,true)
  val px=IntArray(96);small.getPixels(px,0,12,0,0,12,8);small.recycle()
  return IntArray(96) { i->val p=px[i];(((p shr 16) and 255)*3+((p shr 8) and 255)*6+(p and 255))/10 }
 }
 private fun diff(a:IntArray,b:IntArray)=a.indices.sumOf { abs(a[it]-b[it]) }.toDouble()/a.size
 private fun quickScan(uri:Uri):Pair<Long,List<Shot>> {
  val retriever=MediaMetadataRetriever()
  retriever.setDataSource(this,uri)
  try {
   val duration=retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?:error("Duration unavailable")
   require(duration>=15000L) { "Video must be at least 15 seconds" }
   // One bounded scan for the entire batch. At most 100 frame requests.
   val step=max(1000L,ceil(duration/100.0).toLong())
   val marks=mutableListOf(0L)
   val activity=mutableListOf<Pair<Long,Double>>()
   var previous:IntArray?=null
   var t=0L
   var count=0
   while(t<duration) {
    val frame=retriever.getFrameAtTime(t*1000L,MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
    if(frame!=null) {
     val sig=signature(frame)
     previous?.let {
      val delta=diff(it,sig)
      activity.add(t to delta)
      if(delta>29.0 && t-marks.last()>=2000L)marks.add(t)
     }
     previous=sig
     frame.recycle()
    }
    count++
    if(count%10==0)message("Analyzing video... ${min(100,(t*100/duration).toInt())}%")
    t+=step
   }
   marks.add(duration)
   val clips=marks.zipWithNext { a,b->Shot(a,b) }.flatMap { s->
    val n=ceil((s.end-s.start)/12000.0).toInt().coerceAtLeast(1)
    (0 until n).map { i->Shot(s.start+(s.end-s.start)*i/n,s.start+(s.end-s.start)*(i+1)/n) }
   }.filter { it.end-it.start>=1000L }.map { s->
    val values=activity.filter { it.first>=s.start && it.first<s.end }.map { it.second }
    s.copy(score=(if(values.isEmpty())0.0 else values.average())+min(s.end-s.start,12000L)/3000.0)
   }
   require(clips.isNotEmpty()) { "No usable scenes found" }
   return duration to clips
  }finally { retriever.release() }
 }
 private fun chooseShots(candidates:List<Shot>,duration:Long,target:Long,style:Int):List<Shot> {
  // Timeline-aware selection: give the beginning, middle and end a fair chance.
  val bins=8
  val grouped=(0 until bins).map { bin->
   candidates.filter { s-> ((s.start.toDouble()/duration)*bins).toInt().coerceIn(0,bins-1)==bin }
    .let { if(style==0)it.shuffled() else it.sortedByDescending { s->s.score } }
  }
  val ranked=mutableListOf<Shot>()
  var layer=0
  while(grouped.any { it.size>layer }) {
   for(group in grouped)if(group.size>layer)ranked.add(group[layer])
   layer++
  }
  val chosen=mutableListOf<Shot>()
  var remaining=min(target,duration)
  for(s in ranked) {
   if(remaining<1000L)break
   val len=min(s.end-s.start,remaining)
   if(len>=1000L) { chosen.add(s.copy(end=s.start+len));remaining-=len }
  }
  return when(style) {
   2->chosen.sortedBy { it.start }
   3->chosen.sortedByDescending { it.start }
   else->chosen.shuffled()
  }
 }
 private fun chooseStory(candidates:List<Shot>,duration:Long):List<Shot> {
  val lengths=listOf(15000L,20000L,30000L,45000L,60000L).filter { it<=duration }
  val best=lengths.flatMap { length->
   candidates.mapNotNull { s->
    if(s.start+length>duration)null else {
     val nearby=candidates.filter { it.start<s.start+length && it.end>s.start }
     val score=nearby.map { it.score }.average()+min(length,45000L)/15000.0
     Shot(s.start,s.start+length,score)
    }
   }
  }.maxByOrNull { it.score }
  return listOf(best?:Shot(0L,min(duration,30000L)))
 }
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
  val uri=source?:return
  val mins=minutes.text.toString().toLongOrNull()?.coerceIn(1,180)?:10L
  val intent=Intent(this,RenderService::class.java).apply {
   putExtra("source",uri.toString())
   putExtra("minutes",mins)
  }
  try {
   if(Build.VERSION.SDK_INT>=26)startForegroundService(intent) else startService(intent)
   status.text="Rendering started in background. You can use other apps; follow notification for progress."
  }catch(e:Exception) {status.text="Unable to start background export: ${e.message}"}
 }
 private fun renderJobs(
  uri:Uri,
  jobs:List<Pair<String,List<Shot>>>,
  qualityChoice:Int,
  index:Int,
  saved:MutableList<Uri>,
  retry:Int=0
 ) {
  if(index>=jobs.size) {
   busy=false;action.isEnabled=true;spinner.visibility=android.view.View.GONE
   publicVideo=saved.firstOrNull();shareButton.isEnabled=saved.isNotEmpty()
   status.text="Batch complete: ${saved.size}/${jobs.size} videos saved in Movies/ClipForge"
   return
  }
  val (name,originalShots)=jobs[index]
  val shots=when(retry) {
   1 -> {
    // Limit joins without accidentally spanning gaps between unrelated shots.
    originalShots.sortedBy { it.start }.take(8)
   }
   2 -> {
    // Full-duration compatibility fallback: one continuous passage of the requested length.
    // Never silently replace a 15-minute request with a 1-minute clip.
    val requestedMs=originalShots.sumOf { it.end-it.start }
    val retriever=MediaMetadataRetriever()
    val sourceDuration=try {
     retriever.setDataSource(this,uri)
     retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?:requestedMs
    } finally { retriever.release() }
    val length=min(requestedMs,sourceDuration)
    val start=if(name.contains("Short")) originalShots.minOf { it.start }.coerceAtMost((sourceDuration-length).coerceAtLeast(0L)) else 0L
    listOf(Shot(start,start+length))
   }
   else -> originalShots
  }
  status.text="Rendering ${index+1}/${jobs.size}: ${name}..."
  val items=shots.map { s->
   val media=MediaItem.Builder().setUri(uri).setClippingConfiguration(
    MediaItem.ClippingConfiguration.Builder().setStartPositionMs(s.start).setEndPositionMs(s.end).build()
   ).build()
   val edit=EditedMediaItem.Builder(media)
   if(name.contains("Short")) {
    edit.setEffects(Effects(emptyList(),listOf(
     Presentation.createForAspectRatio(9f/16f,Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP)
    )))
   }
   edit.build()
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
     android.util.Log.e("ClipForgeExport","Export failed: ${name}, attempt=${retry}, code=${exportException.errorCode}",exportException)
     if(retry<2) {
      status.text="MP4 muxer failed; trying compatibility mode ${retry+1}/2..."
      output.delete()
      renderJobs(uri,jobs,qualityChoice,index,saved,retry+1)
     } else {
      busy=false;action.isEnabled=true;spinner.visibility=android.view.View.GONE
      val cause=exportException.cause?.message?.take(160) ?: "No additional cause"
      status.text="Export ${index+1}/${jobs.size} failed (code ${exportException.errorCode}): $cause. Saved ${saved.size} videos."
     }
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