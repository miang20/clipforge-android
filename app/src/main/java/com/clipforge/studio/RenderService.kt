package com.clipforge.studio

import android.app.*
import android.content.*
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.*
import android.provider.MediaStore
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.effect.Presentation
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
  if(intent==null)return START_NOT_STICKY
  if(!running.compareAndSet(false,true))return START_STICKY
  startForeground(notificationId,notification("Preparing 4 videos..."))
  val uri=Uri.parse(intent.getStringExtra("source")?:return START_NOT_STICKY)
  val minutes=intent.getLongExtra("minutes",10L).coerceIn(1,180)
  val shortLength=intent.getLongExtra("shortSeconds",0L).let { if(it==0L)30L else it.coerceIn(15,60) }
  val remixStyle=intent.getIntExtra("remixStyle",0).coerceIn(0,3)
  val batch=intent.getBooleanExtra("batch",true)
  thread {
   try {
    val (duration,visual)=quickScan(uri)
    val candidates=enrichWithAudio(visual,duration,uri)
    val long=chooseLongExportShots(duration,(duration*0.70).toLong().coerceAtMost(15*60000L),remixStyle)
    val story=chooseStory(candidates,duration)
    val other=candidates.filter { s->story.none { x->s.start<x.end && s.end>x.start } }.ifEmpty { candidates }
    val energy=chooseCompleteShort(candidates,duration,emptyList())
    val curiosity=chooseCompleteShort(candidates,duration,energy)
    val storyShort=chooseCompleteShort(candidates,duration,energy+curiosity)
    val jobs=if(batch) listOf("Monster_Long" to long,"Short_Energy" to energy,
     "Short_Curiosity" to curiosity,"Short_Story" to storyShort)
     else listOf("Monster_Long" to long)
    handler.post { render(uri,jobs,0,0,0) }
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
      if(delta>29.0 && t-marks.last()>=2000L)marks.add(t)
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
 private fun chooseLongExportShots(duration:Long,target:Long,style:Int):List<Shot> {
  // Use several complete, contiguous passages rather than the entire original
  // in source order. Keep each passage long enough to preserve the event.
  val blockMs=30_000L
  val blocks=mutableListOf<Shot>()
  var start=0L
  while(start<duration) {
   val end=min(duration,start+blockMs)
   if(end-start>=8000L)blocks.add(Shot(start,end))
   start=end
  }
  val ordered=when(style) {
   2->blocks.shuffled()
   3->blocks.reversed()
   else->blocks.shuffled()
  }
  var remaining=min(target,duration)
  val selected=mutableListOf<Shot>()
  for(s in ordered) {
   if(remaining<8000L)break
   val take=min(remaining,s.end-s.start)
   if(take>=8000L) {
    selected.add(Shot(s.start,s.start+take))
    remaining-=take
   }
  }
  // Prevent accidentally exporting the original footage in its original order.
  if(selected.size>1 && selected.zipWithNext().all { (x,y)->x.start<y.start }) {
   selected.reverse()
  }
  return selected
 }
 private fun chooseCompleteShort(candidates:List<Shot>,duration:Long,excluded:List<Shot>):List<Shot> {
  val windows=candidates.sortedByDescending { it.score }.mapNotNull { s->
   val start=(s.start-3000L).coerceAtLeast(0L)
   val end=(s.end+3000L).coerceAtMost(duration)
   if(end-start<12000L || end-start>60000L)null else Shot(start,end,s.score)
  }.distinctBy { it.start/3000L }
  return listOfNotNull(windows.firstOrNull { s->
   excluded.none { x->s.start<x.end && s.end>x.start }
  })
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
  if(name=="Monster_Long") {
   val dir=getExternalFilesDir(Environment.DIRECTORY_MOVIES)?:filesDir
   val output=File(dir,"${name}_${System.currentTimeMillis()}.mp4")
   thread {
    try {
     notifyStatus("Rendering long with native stream copy — no encoder")
     NativeRemuxer.export(this,uri,output,shots.map { NativeRemuxer.Range(it.start,it.end) })
     val savedOk=saveToGallery(output)
     output.delete()
     if(!savedOk)error("Gallery save failed")
     handler.post { render(uri,jobs,index+1,0,saved+1) }
    }catch(e:Exception) {
     android.util.Log.e("ClipForge","Native remux failed",e)
     output.delete()
     handler.post {
      if(attempt==0) {
       notifyStatus("Native remux unavailable; retrying with compatible export")
       finish("Native remux failed: ${e.message}. Original source preserved.")
      } else finish("Long export failed: ${e.message}")
     }
    }
   }
   return
  }
  val items=shots.map { s->
   val media=MediaItem.Builder().setUri(uri).setClippingConfiguration(
    MediaItem.ClippingConfiguration.Builder().setStartPositionMs(s.start).setEndPositionMs(s.end).build()).build()
   val builder=EditedMediaItem.Builder(media)
   if(name.startsWith("Short_")) {
    builder.setEffects(Effects(emptyList(), listOf(Presentation.createForAspectRatio(9f/16f, Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP))))
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
      if(actual<=0L || abs(actual-expected)>max(2000L,expected/20L)) {
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
        if(success)render(uri,jobs,index+1,0,saved+1)
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
