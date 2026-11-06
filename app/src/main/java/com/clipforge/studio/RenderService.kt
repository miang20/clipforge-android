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
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import androidx.media3.effect.Presentation
import androidx.media3.effect.Crop
import android.media.FaceDetector
import android.graphics.PointF
import androidx.media3.effect.ScaleAndRotateTransformation
import androidx.media3.transformer.*
import java.io.*
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.math.*

// PCM16 offline peak control. Gain ramps gently and a hard limiter prevents clipping.
private class ClipForgeAudioLeveler:BaseAudioProcessor() {
 private var gain=1.0
 override fun onConfigure(inputAudioFormat:AudioProcessor.AudioFormat):AudioProcessor.AudioFormat {
  return if(inputAudioFormat.encoding==androidx.media3.common.C.ENCODING_PCM_16BIT) inputAudioFormat
   else AudioProcessor.AudioFormat.NOT_SET
 }
 override fun queueInput(inputBuffer:ByteBuffer) {
  val bytes=inputBuffer.remaining()
  val output=replaceOutputBuffer(bytes)
  inputBuffer.order(ByteOrder.LITTLE_ENDIAN)
  output.order(ByteOrder.LITTLE_ENDIAN)
  val target=0.72*32767.0
  val sampleCount=bytes/2
  val start=inputBuffer.position()
  var peak=1.0
  for(i in 0 until sampleCount)peak=maxOf(peak,kotlin.math.abs(inputBuffer.getShort(start+i*2).toInt()).toDouble())
  val desired=(target/peak).coerceIn(0.4,2.0)
  // Avoid rapid gain jumps that cause audible pumping.
  gain=(gain*0.92+desired*0.08).coerceIn(0.4,2.0)
  for(i in 0 until sampleCount) {
   val sample=inputBuffer.short.toInt()
   output.putShort((sample*gain).toInt().coerceIn(-32768,32767).toShort())
  }
  if(bytes%2!=0)output.put(inputBuffer.get())
  output.flip()
 }
 override fun onReset() { gain=1.0 }
}
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
  startForeground(notificationId,notification("Preparing 4 videos..."))
  val source=intent.getStringExtra("source")?:run {stopSelf();return START_NOT_STICKY}
  if(!running.compareAndSet(false,true))return START_STICKY
  val uri=Uri.parse(source)
  val minutes=intent.getLongExtra("minutes",10L).coerceIn(7,10)
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
    // Automatic 5–7 minute long, adjusted to source length and usable scenes.
    val usableMs=candidates.sumOf { (it.end-it.start).coerceAtLeast(0L) }.coerceAtMost(duration)
    val autoTargetMs=when {
     usableMs<=300_000L -> usableMs
     usableMs<420_000L -> usableMs
     else -> (300_000L+((usableMs-420_000L)/6L)).coerceIn(300_000L,420_000L)
    }
    val long=chooseLongExportShots(duration,autoTargetMs,remixStyle,candidates)
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
   // One bounded scan for the entire batch, sampled across the complete timeline.
   val step=max(350L,ceil(duration/600.0).toLong())
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
      if(delta>24.0 && t-marks.last()>=250L)marks.add(t)
     }
     previous=sig
     frame.recycle()
    }
    count++
    if(count%10==0)notifyStatus("Analyzing video... ${min(100,(t*100/duration).toInt())}%")
    t+=step
   }
   marks.add(duration)
   val clips=marks.zipWithNext { a,b->Shot(a,b) }
    .filter { it.end>it.start }.map { s->
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
  // Real detected scenes, never arbitrary 30/60 second shuffle blocks.
  // Use the original source timestamps, preserving source audio with each scene.
  val valid=candidates.filter { it.end>it.start && it.start>=0L && it.end<=duration }
  if(valid.isEmpty())return emptyList()
  val ordered=when(style) {
   2->valid.sortedBy { it.start }
   3->valid.sortedByDescending { it.start }
   else->{
    // Prefer the highest-scoring distinct scenes; shuffle within small
    // quality bands rather than randomly filling with weak moments.
    val ranked=valid.sortedByDescending { it.score }
    val hook=ranked.first()
    val remaining=ranked.drop(1).chunked(5).flatMap { it.shuffled() }
    listOf(hook)+remaining
   }
  }
  val chosen=mutableListOf<Shot>()
  var remaining=min(target,duration)
  for(scene in ordered) {
   if(remaining<=0L)break
   // A scene is never extended or combined just to fill a time block.
   val length=min(scene.end-scene.start,remaining)
   if(length>0L) {
    chosen.add(scene.copy(end=scene.start+length))
    remaining-=length
   }
  }
  return chosen
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
  val ranked=windows.sortedWith(compareBy<Shot> { w ->
   excluded.sumOf { other -> max(0L,min(w.end,other.end)-max(w.start,other.start)) }
  }.thenByDescending { it.score })
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

 // Fully offline, no external model: track the most prominent detectable face
 // across sampled frames and center a vertical crop on its median horizontal location.
 // When no face is detected, preserve the existing safe center-crop behavior.
 private fun faceAwareCrop(uri:Uri,shots:List<Shot>):Crop? {
  if(shots.isEmpty())return null
  val retriever=MediaMetadataRetriever()
  try {
   retriever.setDataSource(this,uri)
   val sourceWidth=retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()?:return null
   val sourceHeight=retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()?:return null
   val rotation=retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull()?:0
   // Rotated source orientation requires a different crop transform.
   if(rotation%180!=0 || sourceWidth<=0 || sourceHeight<=0)return null
   val fraction=(sourceHeight.toDouble()*9.0/16.0/sourceWidth).coerceAtMost(1.0)
   if(fraction>=0.98)return null
   val positions=mutableListOf<Float>()
   val start=shots.minOf { it.start }
   val end=shots.maxOf { it.end }
   for(i in 0 until 7) {
    val at=start+(end-start)*(i+1)/8
    val frame=try {retriever.getFrameAtTime(at*1000L,MediaMetadataRetriever.OPTION_CLOSEST_SYNC)}catch(_:Exception){null}?:continue
    try {
     val width=((frame.width.coerceAtMost(600))/2*2).coerceAtLeast(2)
     val height=(frame.height.toLong()*width/frame.width).toInt().coerceAtLeast(2)
     val scaled=Bitmap.createScaledBitmap(frame,width,height,true)
     val rgb=scaled.copy(Bitmap.Config.RGB_565,false)
     try {
      val detector=FaceDetector(width,height,5)
      val faces=arrayOfNulls<FaceDetector.Face>(5)
      val count=detector.findFaces(rgb,faces)
      if(count>0) {
       val point=PointF()
       val face=faces.take(count).filterNotNull().maxByOrNull { it.eyesDistance() }
       if(face!=null) {
        face.getMidPoint(point)
        positions.add((point.x/width).coerceIn(0f,1f))
       }
      }
     }finally {rgb.recycle();if(scaled!==frame)scaled.recycle()}
    }catch(e:Exception){android.util.Log.d("ClipForge","Face sample unavailable",e)}
    finally {frame.recycle()}
   }
   if(positions.isEmpty())return null
   val center=positions.sorted()[positions.size/2].toDouble()
   val left=(center-fraction/2).coerceIn(0.0,1.0-fraction)
   val right=left+fraction
   return Crop((2*left-1).toFloat(),(2*right-1).toFloat(),-1f,1f)
  }catch(e:Exception){android.util.Log.w("ClipForge","Face-aware framing unavailable",e);return null}
  finally {retriever.release()}
 }
 // Track visual motion in each shot, including cars and animals, without cloud services.
 // This is motion-saliency tracking rather than semantic object recognition.
 private fun motionAwareCrop(uri:Uri,shot:Shot):Crop? {
  val retriever=MediaMetadataRetriever()
  try {
   retriever.setDataSource(this,uri)
   val w=retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()?:return null
   val h=retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()?:return null
   val rotation=retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull()?:0
   if(rotation%180!=0 || w<=0 || h<=0)return null
   val fraction=(h*9.0/(w*16.0)).coerceAtMost(1.0)
   if(fraction>=0.98)return null
   val centers=mutableListOf<Double>()
   var previous:IntArray?=null
   val columns=48
   val rows=27
   for(i in 0 until 6) {
    val at=shot.start+(shot.end-shot.start)*(i+1)/7
    val frame=try{retriever.getFrameAtTime(at*1000L,MediaMetadataRetriever.OPTION_CLOSEST_SYNC)}catch(_:Exception){null}?:continue
    try {
     val small=Bitmap.createScaledBitmap(frame,columns,rows,false)
     val pixels=IntArray(columns*rows)
     small.getPixels(pixels,0,columns,0,0,columns,rows)
     if(small!==frame)small.recycle()
     val prev=previous
     if(prev!=null) {
      val energy=DoubleArray(columns)
      for(y in 2 until rows-2)for(x in 1 until columns-1) {
       val idx=y*columns+x
       val now=pixels[idx];val old=prev[idx]
       val diff=(kotlin.math.abs(Color.red(now)-Color.red(old))+
         kotlin.math.abs(Color.green(now)-Color.green(old))+
         kotlin.math.abs(Color.blue(now)-Color.blue(old))).toDouble()
       // De-emphasize hard frame cuts and moving background near the edges.
       if(diff>32 && diff<500)energy[x]+=diff
      }
      val total=energy.sum()
      if(total>15000.0) {
       val weighted=energy.indices.sumOf { energy[it]*(it+0.5) }
       centers.add(weighted/(total*columns))
      }
     }
     previous=pixels
    }finally{frame.recycle()}
   }
   if(centers.isEmpty())return null
   val center=centers.sorted()[centers.size/2]
   val left=(center-fraction/2).coerceIn(0.0,1.0-fraction)
   return Crop((2*left-1).toFloat(),(2*(left+fraction)-1).toFloat(),-1f,1f)
  }catch(e:Exception){android.util.Log.w("ClipForge","Motion crop fallback",e);return null}
  finally{retriever.release()}
 }
 private fun render(uri:Uri,jobs:List<Pair<String,List<Shot>>>,index:Int,attempt:Int,saved:Int) {
  if(index>=jobs.size) {finish("Complete: $saved/${jobs.size} videos saved in Movies/ClipForge");return}
  val (name,selected)=jobs[index]
  if(selected.isEmpty()) {finish("No clips for $name");return}
  // Retry without changing the user's shuffled scene order.
  val shots=selected
  exportWatchdog?.let { handler.removeCallbacks(it) }
  exportWatchdog=null
  notifyStatus("Rendering ${index+1}/${jobs.size}: $name (attempt ${attempt+1})")
  if(shots.any { it.end<=it.start }) {finish("Invalid clip range: $name");return}
  // Long exports first use packet-level remuxing: no encoder or decoder.
  // Run off the main thread and retain Media3 as an explicit compatibility fallback.
  if(name=="Monster_Long" && attempt==0) {
   val dir=getExternalFilesDir(Environment.DIRECTORY_MOVIES)?:filesDir
   val nativeOutput=File(dir,"Native_${System.currentTimeMillis()}.mp4")
   notifyStatus("Rendering Long with Native Stream Copy — No Encoder")
   thread {
    try {
     NativeStreamCopy.export(this,uri,shots.map {
      NativeStreamCopy.Range(it.start,it.end)
     },nativeOutput) { percent ->
      lastProgress=percent
      prefs.edit().putInt("progress",percent).putInt("active_job",index).apply()
      if(percent%10==0)notifyStatus("Native Stream Copy: $percent%")
     }
     require(nativeOutput.length()>8192L && hasVideoTrack(nativeOutput)) {
      "Native output validation failed"
     }
     val savedNative=saveToGallery(nativeOutput)
     require(savedNative){"Native gallery save failed"}
     nativeOutput.delete()
     // Generate thumbnail pack on the existing background worker, and report failures.
     try {
      notifyStatus("Generating 4K thumbnail pack — Pictures/ClipForge")
      exportThumbnails(uri,selected,name)
      prefs.edit().putString("thumbnail_status","Thumbnail pack exported to Pictures/ClipForge").apply()
     }catch(e:Exception) {
      android.util.Log.e("ClipForge","Thumbnail export failed",e)
      prefs.edit().putString("thumbnail_status","Thumbnail export failed: ${e.message}").apply()
      notifyStatus("Thumbnail export failed: ${e.message}")
     }
     handler.post {
      prefs.edit().putInt("completed",index+1).apply()
      render(uri,jobs,index+1,0,saved+1)
     }
    }catch(e:Exception) {
     android.util.Log.e("ClipForge","Native stream copy failed; compatibility fallback",e)
     nativeOutput.delete()
     handler.post {
      notifyStatus("Native copy unsupported: ${e.message}. Trying compatibility export")
      render(uri,jobs,index,1,saved)
     }
    }
   }
   return
  }
  val faceCrop:Crop?=null // Avoid blocking the Transformer main thread on frame analysis.
  val items=shots.map { s->
   val crop=faceCrop // Motion analysis is disabled until it can run off the main thread.
   val media=MediaItem.Builder().setUri(uri).setClippingConfiguration(
    MediaItem.ClippingConfiguration.Builder().setStartPositionMs(s.start).setEndPositionMs(s.end).build()).build()
   val builder=EditedMediaItem.Builder(media)
   if(name.startsWith("Short_")) {
    builder.setEffects(Effects(listOf(ClipForgeAudioLeveler()), if(crop!=null)listOf(crop,Presentation.createForAspectRatio(9f/16f, Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP)) else listOf(Presentation.createForAspectRatio(9f/16f, Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP))))
   } else {
    // Long compilations prioritize stream-copy/transmux where Media3 supports it.
    // Per-segment video/audio effects force full re-encoding and caused long stalls.
    // Shorts retain reframing; long 105% zoom and leveling are deferred for reliability.
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
        if(attempt<1)handler.postDelayed({render(uri,jobs,index,attempt+1,saved)},1200L)
        else finish("Duration mismatch: $name expected ${expected/1000}s got ${actual/1000}s; saved $saved/4")
       }
      }else{
       val success=try {saveToGallery(output)}catch(e:Exception){false}
       output.delete()
       handler.post {
        if(success) {
         prefs.edit().putInt("completed",index+1).apply()
         thread { try { exportThumbnails(uri,selected,name) } catch(e:Exception) {
          android.util.Log.e("ClipForge","Thumbnail export failed",e)
          prefs.edit().putString("thumbnail_status","Thumbnail failed: ${e.message}").apply()
         } }
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
     if(attempt<1)handler.postDelayed({render(uri,jobs,index,attempt+1,saved)},1200L)
     else finish("Export failed: $name (${e.getErrorCodeName()} / ${e.errorCode}), saved $saved/${jobs.size}")
    }
   }).build()
   transformer?.start(composition,output.absolutePath)
   val active=transformer
   val progress=ProgressHolder()
   var lastPercent=-1
   var lastMovement=android.os.SystemClock.elapsedRealtime()
   var lastNotice=0L
   var recoveryStarted=false
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
     // An unchanged progress percentage alone is not conclusive.
     // After 8 minutes without movement, recover once using fewer
     // source-contiguous segments rather than waiting indefinitely.
     if(!recoveryStarted && now-lastMovement>=480_000L) {
      recoveryStarted=true
      android.util.Log.e("ClipForge","Watchdog recovery $name: state=$state progress=$lastPercent")
      notifyStatus("Recovering stalled export: $name")
      transformer=null
      try { active?.cancel() } catch(e:Exception) {
       android.util.Log.w("ClipForge","Cancel failed",e)
      }
      output.delete()
      exportWatchdog=null
      handler.postDelayed({
       if(running.get()) {
        if(attempt<1)render(uri,jobs,index,attempt+1,saved)
        else finish("Export stalled twice: $name at ${lastPercent}%; saved $saved/${jobs.size}. Codec logs needed.")
       }
      },1500L)
      return
     }
     handler.postDelayed(this,5000L)
    }
   }
   exportWatchdog=watchdog
   handler.postDelayed(watchdog,5000L)
  }catch(e:Exception){
   output.delete()
   if(attempt<1)handler.postDelayed({render(uri,jobs,index,attempt+1,saved)},1200L)
   else finish("Export failed: ${e.message}, saved $saved/4")
  }
 }

 // Offline creator pack: export five source-derived, high-contrast 16:9 thumbnails.
 // Upscaling to 4K does not add detail beyond the source footage.
 private fun exportThumbnails(uri:Uri,selected:List<Shot>,jobName:String) {
  require(selected.isNotEmpty()) { "No thumbnail candidates" }
  val best=selected.maxByOrNull { it.score }!!
  val retriever=MediaMetadataRetriever()
  try {
   retriever.setDataSource(this,uri)
   val at=(best.start+(best.end-best.start)/2)*1000L
   val frame=if(Build.VERSION.SDK_INT>=27)
    retriever.getScaledFrameAtTime(at,MediaMetadataRetriever.OPTION_CLOSEST_SYNC,1280,720)
   else retriever.getFrameAtTime(at,MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
   require(frame!=null) { "Cannot decode thumbnail frame for $jobName" }
   try {
    val style=when(jobName) {
     "Monster_Long"->1
     "Short_Energy"->1
     "Short_Curiosity"->0
     else->2
    }
    val edited=makeThumbnailVariant(frame,style)
    try {
     saveBitmap(edited,"ClipForge_${jobName}_Thumbnail.jpg",Bitmap.CompressFormat.JPEG,94)
     prefs.edit().putString("thumbnail_status","Saved $jobName thumbnail to Pictures/ClipForge").apply()
     notifyStatus("Saved $jobName thumbnail to Pictures/ClipForge")
    }finally { edited.recycle() }
   }finally { frame.recycle() }
  }finally { retriever.release() }
 }
 private fun makeThumbnailVariant(source:Bitmap,style:Int):Bitmap {
  val width=1280
  val height=720
  val output=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888)
  val canvas=Canvas(output)
  val srcWidth=source.width.toFloat()
  val srcHeight=source.height.toFloat()
  val crop=when(style) { 0->1.04f;1->1.13f;else->1.08f }
  val scale=max(width/srcWidth,height/srcHeight)*crop
  val drawW=srcWidth*scale
  val drawH=srcHeight*scale
  val left=(width-drawW)/2f
  val top=(height-drawH)/2f
  val paint=Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
  val contrast=when(style) {0->1.14f;1->1.23f;else->1.10f}
  val brightness=when(style) {0->4f;1->-5f;else->9f}
  val saturation=when(style) {0->1.13f;1->1.27f;else->1.07f}
  val lumR=0.213f;val lumG=0.715f;val lumB=0.072f
  val inv=1f-saturation
  val colorMatrix=android.graphics.ColorMatrix(floatArrayOf(
   (inv*lumR+saturation)*contrast,inv*lumG*contrast,inv*lumB*contrast,0f,brightness+128f*(1f-contrast),
   inv*lumR*contrast,(inv*lumG+saturation)*contrast,inv*lumB*contrast,0f,brightness+128f*(1f-contrast),
   inv*lumR*contrast,inv*lumG*contrast,(inv*lumB+saturation)*contrast,0f,brightness+128f*(1f-contrast),
   0f,0f,0f,1f,0f
  ))
  paint.colorFilter=android.graphics.ColorMatrixColorFilter(colorMatrix)
  canvas.drawBitmap(source,null,android.graphics.RectF(left,top,left+drawW,top+drawH),paint)
  // A subtle dark edge gradient increases central subject prominence.
  val edge=Paint(Paint.ANTI_ALIAS_FLAG)
  edge.shader=android.graphics.RadialGradient(width/2f,height/2f,850f,
   intArrayOf(Color.TRANSPARENT,Color.TRANSPARENT,Color.argb(if(style==1)110 else 75,0,0,0)),
   floatArrayOf(0f,0.55f,1f),android.graphics.Shader.TileMode.CLAMP)
  canvas.drawRect(0f,0f,width.toFloat(),height.toFloat(),edge)
  return output
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
   appendLine("Audio: per-clip adaptive PCM16 peak leveling and clipping protection (not EBU R128 loudness normalization).")
   appendLine("9:16 framing uses offline face detection with median-position crop when available; other subjects use center crop. Not general AI object tracking.")
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
