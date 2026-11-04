package com.clipforge.studio

import android.app.*
import android.content.*
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.content.ContentValues
import android.media.MediaMetadataRetriever
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.*
import android.provider.MediaStore
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.effect.Presentation
import androidx.media3.effect.ScaleAndRotateTransformation
import androidx.media3.transformer.*
import java.io.*
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.math.*

class RenderService:Service() {
 private data class Shot(val start:Long,val end:Long,val score:Double=0.0)
 private val handler=Handler(Looper.getMainLooper())
 private var transformer:Transformer?=null
 private var exportWatchdog:Runnable?=null
 private val channel="clipforge_render"
 private val notificationId=91
 private val running=AtomicBoolean(false)
 private var activeSource:Uri?=null
 private var activeJobs:List<Pair<String,List<Shot>>> = emptyList()
 private val prefs by lazy { getSharedPreferences("clipforge_jobs",MODE_PRIVATE) }
 private var lastProgress=0
 override fun onBind(intent:Intent?)=null
 override fun onCreate() {
  super.onCreate()
  if(Build.VERSION.SDK_INT>=26) {
   (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(
    NotificationChannel(channel,"ClipForge rendering",NotificationManager.IMPORTANCE_LOW))
  }
 }
 private fun notification(s:String):Notification {
  val open=PendingIntent.getActivity(this,0,Intent(this,MainActivity::class.java),
   PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
  return Notification.Builder(this,channel).setContentTitle("ClipForge Monster Edition")
   .setContentText(s).setSmallIcon(android.R.drawable.stat_sys_upload)
   .setContentIntent(open).setOngoing(true).build()
 }
 private fun notifyStatus(s:String) {
  (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(notificationId,notification(s))
 }
 override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int {
  if(intent==null) {
   val savedSource=prefs.getString("source",null)?:return START_NOT_STICKY
   val state=prefs.getString("state","")?: ""
   if(state!="rendering")return START_NOT_STICKY
   val resume=Intent(this,RenderService::class.java).apply {
    putExtra("source",savedSource)
    putExtra("minutes",prefs.getLong("minutes",10L))
    putExtra("shortSeconds",prefs.getLong("shortSeconds",30L))
    putExtra("remixStyle",prefs.getInt("remixStyle",0))
    putExtra("batch",prefs.getBoolean("batch",true))
    putExtra("resume",true)
   }
   return onStartCommand(resume,flags,startId)
  }
  if(!running.compareAndSet(false,true))return START_STICKY
  startForeground(notificationId,notification("Preparing 4 videos..."))
  val uri=Uri.parse(intent.getStringExtra("source")?:return START_NOT_STICKY)
  val minutes=intent.getLongExtra("minutes",10L).coerceIn(1,180)
  val shortLength=intent.getLongExtra("shortSeconds",0L).let { if(it==0L)30L else it.coerceIn(15,60) }
  val remixStyle=intent.getIntExtra("remixStyle",0).coerceIn(0,3)
  val batch=intent.getBooleanExtra("batch",true)
  activeSource=uri
  val resuming=intent.getBooleanExtra("resume",false)
  prefs.edit().putLong("minutes",minutes).putLong("shortSeconds",shortLength).putInt("remixStyle",remixStyle).putBoolean("batch",batch).apply()
  thread {
   try {
    val (duration,visual)=quickScan(uri)
    val candidates=deduplicate(enrichWithAudio(visual,duration,uri),uri)
    val long=chooseLongExportShots(duration,min(duration,minutes*60_000L),remixStyle,candidates)
    // Preserve continuous story windows for the Shorts selector.
    // Short selections are ranked independently of the long timeline.
    val energy=chooseCompleteShort(candidates,duration,emptyList(),shortLength*1000L,0)
    val curiosity=chooseCompleteShort(candidates,duration,energy,shortLength*1000L,1)
    val storyShort=chooseCompleteShort(candidates,duration,energy+curiosity,shortLength*1000L,2)
    val jobs=if(batch) listOf("Monster_Long" to long,"Short_Energy" to energy,
     "Short_Curiosity" to curiosity,"Short_Story" to storyShort)
     else listOf("Monster_Long" to long)
    activeJobs=jobs
    val completed=if(resuming)prefs.getInt("completed",0).coerceIn(0,jobs.size) else 0
    prefs.edit().putString("source",uri.toString()).putInt("job_count",jobs.size).putInt("completed",completed).putString("state","rendering").apply()
    writeProductionPlan(jobs,uri)
    handler.post { render(uri,jobs,completed,0,completed) }
   }catch(e:Exception){finish("Analysis failed: ${e.message}")}
  }
  return START_STICKY
 }
 private fun audioActivity(uri:Uri,duration:Long):DoubleArray {
  // Sample the full timeline rather than only the first 1200 audio packets.
  val bins=96
  val activity=DoubleArray(bins)
  val extractor=MediaExtractor()
  try {
   extractor.setDataSource(this,uri,null)
   val track=(0 until extractor.trackCount).firstOrNull { i->
    extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith("audio/")==true
   }?:return activity
   extractor.selectTrack(track)
   val buffer=java.nio.ByteBuffer.allocate(256*1024)
   for(bin in 0 until bins) {
    val sampleUs=((duration.toDouble()*(bin+0.5)/bins)*1000.0).toLong()
    extractor.seekTo(sampleUs,MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
    var bytes=0.0
    var count=0
    while(count<12) {
     buffer.clear()
     val size=extractor.readSampleData(buffer,0)
     if(size<0)break
     bytes+=size.toDouble()
     count++
     if(!extractor.advance())break
    }
    activity[bin]=if(count>0)bytes/count else 0.0
   }
  }catch(e:Exception) {
   android.util.Log.w("ClipForge","Audio analysis unavailable",e)
  }finally { extractor.release() }
  return activity
 }
 private fun enrichWithAudio(candidates:List<Shot>,duration:Long,uri:Uri):List<Shot> {
  val levels=audioActivity(uri,duration)
  val peak=levels.maxOrNull()?.coerceAtLeast(1.0)?:1.0
  return candidates.map { shot->
   val first=((shot.start.toDouble()/duration)*levels.size).toInt().coerceIn(0,levels.lastIndex)
   val last=((shot.end.toDouble()/duration)*levels.size).toInt().coerceIn(first,levels.lastIndex)
   val window=levels.slice(first..last)
   val mean=window.average()/peak
   val localPeak=(window.maxOrNull()?:0.0)/peak
   // Highlight impact and sudden audio peaks; avoid scoring only the clip's first instant.
   val impact=if(first>0)max(0.0,(levels[first]-levels[first-1])/peak) else 0.0
   shot.copy(score=shot.score+2.0*mean+2.0*localPeak+1.5*impact)
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
      if(delta>24.0 && t-marks.last()>=1500L)marks.add(t)
     }
     previous=sig
     frame.recycle()
    }
    count++
    if(count%10==0)notifyStatus("Analyzing video... ${min(100,(t*100/duration).toInt())}%")
    t+=step
   }
   marks.add(duration)
   val clips=marks.zipWithNext { a,b->Shot(a,b) }.flatMap { s->
    val n=ceil((s.end-s.start)/8000.0).toInt().coerceAtLeast(1)
    (0 until n).map { i->Shot(s.start+(s.end-s.start)*i/n,s.start+(s.end-s.start)*(i+1)/n) }
   }.filter { it.end-it.start>=1000L }.map { s->
    val values=activity.filter { it.first>=s.start && it.first<s.end }.map { it.second }
    s.copy(score=(if(values.isEmpty())0.0 else values.average())+min(s.end-s.start,12000L)/3000.0)
   }
   require(clips.isNotEmpty()) { "No usable scenes found" }
   return duration to clips
  }finally { retriever.release() }
 }
 // Reject near-identical static scenes using tiny luminance signatures.
 private fun deduplicate(shots:List<Shot>,uri:Uri):List<Shot> {
  val retriever=MediaMetadataRetriever()
  return try {
   retriever.setDataSource(this,uri)
   val seen=mutableListOf<IntArray>()
   val result=mutableListOf<Shot>()
   for(shot in shots) {
    val middle=(shot.start+shot.end)/2
    val frame=try { retriever.getFrameAtTime(middle*1000L,MediaMetadataRetriever.OPTION_CLOSEST_SYNC) }catch(_:Exception){null}
    if(frame==null) {result.add(shot);continue}
    val sig=try { signature(frame) }finally {frame.recycle()}
    val duplicate=seen.any { diff(it,sig)<3.0 }
    if(!duplicate || result.size<3) {seen.add(sig);result.add(shot)}
   }
   if(result.size*2<shots.size)shots else result
  }catch(e:Exception){android.util.Log.w("ClipForge","Dedup skipped",e);shots}
  finally {retriever.release()}
 }
 private fun chooseShots(candidates:List<Shot>,duration:Long,target:Long,style:Int):List<Shot> {
  // Select non-overlapping segments across the entire source; never duplicate footage.
  if(duration<=0L || target<=0L)return emptyList()
  val bins=12
  val groups=(0 until bins).map { bin->
   val group=candidates.filter { s->
    s.end>s.start && ((s.start.toDouble()/duration)*bins).toInt().coerceIn(0,bins-1)==bin
   }
   if(style==0)group.shuffled() else group.sortedByDescending { it.score }
  }
  val ranked=mutableListOf<Shot>()
  var layer=0
  while(groups.any { it.size>layer }) {
   for(group in groups)if(group.size>layer)ranked.add(group[layer])
   layer++
  }
  val chosen=mutableListOf<Shot>()
  var remaining=min(target,duration)
  for(s in ranked) {
   if(remaining<1000L)break
   val free=mutableListOf(s.start.coerceAtLeast(0L) to s.end.coerceAtMost(duration))
   for(used in chosen) {
    val next=mutableListOf<Pair<Long,Long>>()
    for((start,end) in free) {
     if(used.end<=start || used.start>=end)next.add(start to end)
     else {
      if(used.start-start>=1000L)next.add(start to used.start)
      if(end-used.end>=1000L)next.add(used.end to end)
     }
    }
    free.clear()
    free.addAll(next)
   }
   for((start,end) in free) {
    val length=min(end-start,remaining)
    if(length>=1000L) {
     chosen.add(Shot(start,start+length,s.score))
     remaining-=length
    }
    if(remaining<1000L)break
   }
  }
  // Fill any remaining time from unselected source intervals, maintaining exact duration.
  if(remaining>=1000L) {
   val sorted=chosen.sortedBy { it.start }
   var cursor=0L
   for(s in sorted+Shot(duration,duration)) {
    val available=s.start-cursor
    val take=min(available,remaining)
    if(take>=1000L) {chosen.add(Shot(cursor,cursor+take));remaining-=take}
    cursor=max(cursor,s.end)
    if(remaining<1000L)break
   }
  }
  return when(style) {
   2->chosen.sortedBy { it.start }
   3->chosen.sortedByDescending { it.start }
   else->chosen.shuffled()
  }
 }
 private fun chooseLongExportShots(duration:Long,target:Long,style:Int,candidates:List<Shot>):List<Shot> {
  // Media3 may reinitialize codecs at each clip boundary. For long-form
  // exports, use fewer, longer, source-contiguous passages instead of
  // dozens of 10-12 second fragments.
  val blockMs=30_000L
  val blocks=mutableListOf<Shot>()
  var start=0L
  while(start<duration) {
   val end=min(duration,start+blockMs)
   if(end-start>=1000L) {
   val overlaps=candidates.filter { it.start<end && it.end>start }
   blocks.add(Shot(start,end,if(overlaps.isEmpty())0.0 else overlaps.map { it.score }.average()))
  }
   start=end
  }
  val ordered=when(style) {
   2->blocks
   3->blocks.reversed()
   else->blocks.sortedByDescending { it.score }.let { ranked ->
    // Lead with a strong moment, then vary the rest without repeating source ranges.
    if(ranked.isEmpty())ranked else listOf(ranked.first())+ranked.drop(1).shuffled()
   }
  }
  var remaining=min(target,duration)
  val selected=mutableListOf<Shot>()
  for(s in ordered) {
   val take=min(remaining,s.end-s.start)
   if(take>=1000L) {
    selected.add(Shot(s.start,s.start+take))
    remaining-=take
   }
   if(remaining<1000L)break
  }
  return selected
 }
 private fun chooseCompleteShort(candidates:List<Shot>,duration:Long,excluded:List<Shot>,preferredMs:Long,mode:Int):List<Shot> {
  // Three different rankings: high impact, unusual visual change, and coherent story.
  val length=preferredMs.coerceIn(15_000L,60_000L).coerceAtMost(duration)
  if(length<=0L)return emptyList()
  val starts=(candidates.map { (it.start-length/3).coerceIn(0L,(duration-length).coerceAtLeast(0L)) }+
   listOf(0L,(duration-length).coerceAtLeast(0L))).distinctBy { it/1000L }
  val windows=starts.map { start ->
   val end=start+length
   val overlapping=candidates.filter { it.start<end && it.end>start }
   val average=overlapping.map { it.score }.average().let { if(it.isNaN())0.0 else it }
   val peak=overlapping.maxOfOrNull { it.score }?:0.0
   val sceneCount=overlapping.size.toDouble()
   val score=when(mode) {
    0->peak*2+average
    1->average+sceneCount*1.5
    else->average+min(sceneCount,5.0)*0.5
   }
   Shot(start,end,score)
  }
  val ranked=windows.sortedWith(compareByDescending<Shot> { w ->
   excluded.sumOf { other -> max(0L,min(w.end,other.end)-max(w.start,other.start)) }.let { overlap ->
    if(overlap==0L)1 else 0
   }
  }.thenByDescending { it.score-
   excluded.sumOf { other -> max(0L,min(it.end,other.end)-max(it.start,other.start)) }.toDouble()/1000.0*100.0
  })
  return ranked.firstOrNull()?.let { listOf(it) }?:emptyList()
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

 private fun render(uri:Uri,jobs:List<Pair<String,List<Shot>>>,index:Int,attempt:Int,saved:Int) {
  if(index>=jobs.size) {finish("Complete: $saved/${jobs.size} videos saved in Movies/ClipForge");return}
  val (name,selected)=jobs[index]
  if(selected.isEmpty()) {finish("No clips for $name");return}
  val shots=selected
  exportWatchdog?.let { handler.removeCallbacks(it) }
  exportWatchdog=null
  notifyStatus("Rendering ${index+1}/${jobs.size}: $name (attempt ${attempt+1})")
  if(shots.any { it.end<=it.start }) {finish("Invalid clip range: $name");return}
  val items=shots.map { s->
   val media=MediaItem.Builder().setUri(uri).setClippingConfiguration(
    MediaItem.ClippingConfiguration.Builder().setStartPositionMs(s.start).setEndPositionMs(s.end).build()).build()
   val builder=EditedMediaItem.Builder(media)
   if(name.startsWith("Short_")) {
    builder.setEffects(Effects(emptyList(), listOf(Presentation.createForAspectRatio(9f/16f, Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP))))
   } else {
    builder.setEffects(Effects(emptyList(), listOf(ScaleAndRotateTransformation.Builder().setScale(1.05f,1.05f).build())))
   }
   builder.build()
  }
  val composition=Composition.Builder(EditedMediaItemSequence.Builder(items).build()).build()
  val dir=getExternalFilesDir(Environment.DIRECTORY_MOVIES)?:filesDir
  val output=File(dir,"${name}_${System.currentTimeMillis()}.mp4")
  try {
   transformer=Transformer.Builder(this).setMaxDelayBetweenMuxerSamplesMs(androidx.media3.common.C.TIME_UNSET).setVideoMimeType(MimeTypes.VIDEO_H264)
    .setAudioMimeType(MimeTypes.AUDIO_AAC).addListener(object:Transformer.Listener {
    override fun onCompleted(composition:Composition,exportResult:ExportResult) {
     exportWatchdog?.let { handler.removeCallbacks(it) }
     exportWatchdog=null
     thread {
      val expected=shots.sumOf {it.end-it.start}
      val actual=try {
       val check=MediaMetadataRetriever()
       try {
        check.setDataSource(output.absolutePath)
        check.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?:0L
       }finally {check.release()}
      }catch(_:Exception){0L}
      if(actual<=0L || abs(actual-expected)>max(2000L,expected/20L) || output.length()<8192L || !hasVideoTrack(output)) {
       android.util.Log.e("ClipForge","Duration mismatch $name expected=$expected actual=$actual attempt=$attempt")
       output.delete()
       handler.post {
        if(attempt<1)render(uri,jobs,index,attempt+1,saved)
        else finish("Duration mismatch: $name expected ${expected/1000}s got ${actual/1000}s; saved $saved/4")
       }
      }else{
       val success=try {saveToGallery(output)}catch(e:Exception){false}
       output.delete()
       handler.post {
        if(success) {
         prefs.edit().putInt("completed",index+1).apply()
         if(index==0)thread { try { exportThumbnails(uri,selected) } catch(e:Exception) { android.util.Log.w("ClipForge","Thumbnail export unavailable",e) } }
         render(uri,jobs,index+1,0,saved+1)
        }
        else finish("Gallery save failed: $name; saved $saved/4")
       }
      }
     }
    }
    override fun onError(composition:Composition,exportResult:ExportResult,e:ExportException) {
     exportWatchdog?.let { handler.removeCallbacks(it) }
     exportWatchdog=null
     android.util.Log.e("ClipForge","Export $name attempt $attempt code ${e.errorCode}",e)
     output.delete()
     if(attempt<1)render(uri,jobs,index,attempt+1,saved)
     else finish("Export failed: $name (${e.getErrorCodeName()} / ${e.errorCode}), saved $saved/${jobs.size}")
    }
   }).build()
   transformer?.start(composition,output.absolutePath)
   val active=transformer
   val progress=ProgressHolder()
   var lastPercent=-1
   var lastMovement=android.os.SystemClock.elapsedRealtime()
   var lastNotice=0L
   val watchdog=object:Runnable {
    override fun run() {
     if(transformer!==active || !running.get())return
     val state=try {active?.getProgress(progress)?:Transformer.PROGRESS_STATE_NOT_STARTED}
      catch(e:Exception) {android.util.Log.w("ClipForge","Progress unavailable",e);Transformer.PROGRESS_STATE_UNAVAILABLE}
     val now=android.os.SystemClock.elapsedRealtime()
     if(state==Transformer.PROGRESS_STATE_AVAILABLE && progress.progress>lastPercent) {
      lastPercent=progress.progress
      lastProgress=lastPercent
      prefs.edit().putInt("progress",lastPercent).putInt("active_job",index).apply()
      lastMovement=now
      notifyStatus("Rendering ${index+1}/${jobs.size}: $name — ${progress.progress}%")
     }
     // Media3 progress is an estimate and may remain unchanged for long periods
     // while encoding complex source segments. A flat percentage is NOT proof
     // that the muxer or codec has stopped. Never cancel a healthy export solely
     // because progress has not advanced for three minutes.
     if(now-lastMovement>=180_000L && now-lastNotice>=60_000L) {
      lastNotice=now
      android.util.Log.w("ClipForge","Slow export $name: progressState=$state percent=$lastPercent elapsedWithoutPercentChangeMs=${now-lastMovement}")
      notifyStatus("Still rendering ${index+1}/${jobs.size}: $name — slow segment (progress ${if(lastPercent>=0) "$lastPercent%" else "unavailable"})")
     }
     handler.postDelayed(this,5000L)
    }
   }
   exportWatchdog=watchdog
   handler.postDelayed(watchdog,5000L)
  }catch(e:Exception){
   output.delete()
   if(attempt<1)render(uri,jobs,index,attempt+1,saved)
   else finish("Export failed: ${e.message}, saved $saved/4")
  }
 }

 // Offline creator pack: export five source-derived, high-contrast 16:9 thumbnails.
 // Upscaling to 4K does not add detail beyond the source footage.
 private fun exportThumbnails(uri:Uri,selected:List<Shot>) {
  val candidates=selected.sortedByDescending { it.score }.take(5)
  if(candidates.isEmpty())return
  val retriever=MediaMetadataRetriever()
  try {
   retriever.setDataSource(this,uri)
   candidates.forEachIndexed { index,shot ->
    val at=shot.start+(shot.end-shot.start)/2
    val frame=retriever.getFrameAtTime(at*1000L,MediaMetadataRetriever.OPTION_CLOSEST_SYNC)?:return@forEachIndexed
    try {
     val wide=Bitmap.createBitmap(3840,2160,Bitmap.Config.ARGB_8888)
     try {
      val canvas=Canvas(wide)
      canvas.drawColor(Color.BLACK)
      val scale=max(3840f/frame.width,2160f/frame.height)
      val w=frame.width*scale;val h=frame.height*scale
      val dest=android.graphics.RectF((3840-w)/2,(2160-h)/2,(3840+w)/2,(2160+h)/2)
      val paint=Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
      canvas.drawBitmap(frame,null,dest,paint)
      val name="ClipForge_Thumbnail_${index+1}_${System.currentTimeMillis()}"
      saveBitmap(wide,"$name.jpg",Bitmap.CompressFormat.JPEG,90)
      if(index==0)saveBitmap(wide,"$name.png",Bitmap.CompressFormat.PNG,100)
     }finally {wide.recycle()}
    }finally {frame.recycle()}
   }
  }finally {retriever.release()}
 }
 private fun saveBitmap(bitmap:Bitmap,name:String,format:Bitmap.CompressFormat,quality:Int) {
  if(Build.VERSION.SDK_INT<29)return
  val values=ContentValues().apply {
   put(MediaStore.Images.Media.DISPLAY_NAME,name)
   put(MediaStore.Images.Media.MIME_TYPE,if(format==Bitmap.CompressFormat.PNG)"image/png" else "image/jpeg")
   put(MediaStore.Images.Media.RELATIVE_PATH,Environment.DIRECTORY_PICTURES+"/ClipForge")
   put(MediaStore.Images.Media.IS_PENDING,1)
  }
  val uri=contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,values)?:return
  try {
   contentResolver.openOutputStream(uri)?.use { out ->
    check(bitmap.compress(format,quality,out)) { "Bitmap compression failed" }
   } ?: error("Cannot write thumbnail")
   contentResolver.update(uri,ContentValues().apply {put(MediaStore.Images.Media.IS_PENDING,0)},null,null)
  }catch(e:Exception){contentResolver.delete(uri,null,null);throw e}
 }
 // Human-readable, offline edit decision list, rights checklist and SEO starting points.
 private fun writeProductionPlan(jobs:List<Pair<String,List<Shot>>>,uri:Uri) {
  val dir=getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)?:filesDir
  dir.mkdirs()
  val file=File(dir,"ClipForge_Plan_${System.currentTimeMillis()}.txt")
  val content=buildString {
   appendLine("CLIPFORGE OFFLINE PRODUCTION REPORT")
   appendLine("Source: $uri")
   appendLine("Source audio remains attached to Media3 clipped video segments.")
   appendLine("Rights checklist: confirm permission/license for every source clip, music and sound.")
   appendLine("Remixing, zooming or shuffling does not guarantee copyright clearance or YPP eligibility.")
   appendLine("Captions require speech transcription; not generated by this offline build.")
   appendLine("Audio normalization requires loudness measurement; original audio is retained without normalization.")
   appendLine("9:16 framing uses center crop, not AI subject tracking.")
   appendLine("Suggested title: Unexpected Moments That Escalated Fast | Compilation")
   appendLine("Suggested keywords: unexpected moments, funny fails, caught on camera, compilation")
   appendLine("Suggested description: A curated compilation of surprising moments. #Compilation #UnexpectedMoments #FunnyFails")
   jobs.forEach { (name,shots) ->
    appendLine()
    appendLine("$name - ${shots.size} segments, ${shots.sumOf { it.end-it.start }/1000}s")
    shots.forEach { shot -> appendLine("${shot.start}..${shot.end} ms | score=${"%.2f".format(java.util.Locale.US,shot.score)}") }
   }
  }
  file.writeText(content)
 }
 // Verify an actual video track exists; metadata duration alone is insufficient.
 private fun hasVideoTrack(file:File):Boolean {
  val extractor=MediaExtractor()
  return try {
   extractor.setDataSource(file.absolutePath)
   (0 until extractor.trackCount).any { i ->
    extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith("video/")==true
   }
  }catch(_:Exception){false}finally {extractor.release()}
 }
 private fun saveToGallery(file:File):Boolean {
  if(Build.VERSION.SDK_INT<29)return false
  val values=ContentValues().apply {
   put(MediaStore.Video.Media.DISPLAY_NAME,file.name)
   put(MediaStore.Video.Media.MIME_TYPE,"video/mp4")
   put(MediaStore.Video.Media.RELATIVE_PATH,Environment.DIRECTORY_MOVIES+"/ClipForge")
   put(MediaStore.Video.Media.IS_PENDING,1)
  }
  val uri=contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI,values)?:return false
  return try {
   contentResolver.openOutputStream(uri)?.use { dest->FileInputStream(file).use {it.copyTo(dest)} }?:error("Output unavailable")
   contentResolver.update(uri,ContentValues().apply {put(MediaStore.Video.Media.IS_PENDING,0)},null,null)
   true
  }catch(e:Exception){contentResolver.delete(uri,null,null);false}
 }
 private fun finish(message:String) {
  prefs.edit().putString("state",message).apply()
  handler.post {
   exportWatchdog?.let { handler.removeCallbacks(it) }
   exportWatchdog=null
   running.set(false)
   notifyStatus(message)
   stopForeground(STOP_FOREGROUND_DETACH)
   stopSelf()
  }
 }
 override fun onDestroy(){transformer?.cancel();super.onDestroy()}
}
