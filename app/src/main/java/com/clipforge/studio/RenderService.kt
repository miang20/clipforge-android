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
  thread {
   try {
    val (duration,visual)=quickScan(uri)
    val candidates=enrichWithAudio(visual,duration,uri)
    val long=chooseShots(candidates,duration,minutes*60000L,1)
    val story=chooseStory(candidates,duration)
    val other=candidates.filter { s->story.none { x->s.start<x.end && s.end>x.start } }.ifEmpty { candidates }
    val energy=chooseShots(other,duration,30000L,1)
    val curiosity=chooseShots(other,duration,45000L,0)
    val jobs=listOf("Monster_Long" to long,"Short_Energy" to energy,
     "Short_Curiosity" to curiosity,"Short_Story" to story)
    handler.post { render(uri,jobs,0,0,0) }
   }catch(e:Exception){finish("Analysis failed: ${e.message}")}
  }
  return START_STICKY
 }
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

 private fun render(uri:Uri,jobs:List<Pair<String,List<Shot>>>,index:Int,attempt:Int,saved:Int) {
  if(index>=jobs.size) {finish("Complete: $saved/4 videos saved in Movies/ClipForge");return}
  val (name,selected)=jobs[index]
  if(selected.isEmpty()) {finish("No clips for $name");return}
  val shots=when(attempt) {
   1->{
    val r=MediaMetadataRetriever()
    val duration=try {r.setDataSource(this,uri);r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?:0L}finally {r.release()}
    listOf(Shot(0L,min(selected.sumOf {it.end-it.start},duration)))
   }
   2->{
    val r=MediaMetadataRetriever()
    val duration=try {r.setDataSource(this,uri);r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?:0L}finally {r.release()}
    val requested=selected.sumOf {it.end-it.start}
    val length=min(requested,duration)
    listOf(Shot(0L,length))
   }
   else->selected
  }
  notifyStatus("Rendering ${index+1}/4: $name (attempt ${attempt+1})")
  val items=shots.map { s->
   val media=MediaItem.Builder().setUri(uri).setClippingConfiguration(
    MediaItem.ClippingConfiguration.Builder().setStartPositionMs(s.start).setEndPositionMs(s.end).build()).build()
   val builder=EditedMediaItem.Builder(media)
   if(name.contains("Short"))builder.setEffects(Effects(emptyList(),listOf(
    Presentation.createForAspectRatio(9f/16f,Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP))))
   builder.build()
  }
  val composition=Composition.Builder(EditedMediaItemSequence.Builder(items).build()).build()
  val dir=getExternalFilesDir(Environment.DIRECTORY_MOVIES)?:filesDir
  val output=File(dir,"${name}_${System.currentTimeMillis()}.mp4")
  try {
   transformer=Transformer.Builder(this).setVideoMimeType(MimeTypes.VIDEO_H264)
    .setAudioMimeType(MimeTypes.AUDIO_AAC).addListener(object:Transformer.Listener {
    override fun onCompleted(composition:Composition,exportResult:ExportResult) {
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
        if(attempt<2)render(uri,jobs,index,attempt+1,saved)
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
     android.util.Log.e("ClipForge","Export $name attempt $attempt code ${e.errorCode}",e)
     output.delete()
     if(attempt<2)render(uri,jobs,index,attempt+1,saved)
     else finish("Export failed: $name (error ${e.errorCode}), saved $saved/4")
    }
   }).build()
   transformer?.start(composition,output.absolutePath)
  }catch(e:Exception){
   output.delete()
   if(attempt<2)render(uri,jobs,index,attempt+1,saved)
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
   running.set(false)
   notifyStatus(message)
   stopForeground(STOP_FOREGROUND_DETACH)
   stopSelf()
  }
 }
 override fun onDestroy(){transformer?.cancel();super.onDestroy()}
}
