package com.clipforge.studio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import java.io.File
import java.nio.ByteBuffer
import kotlin.math.max

/**
 * Remuxes source video and audio packets without decoding or encoding.
 * Cuts start on preceding video sync samples; output duration can differ
 * from the requested scene duration by up to the GOP interval.
 */
internal object NativeStreamCopy {
 data class Range(val startMs:Long,val endMs:Long)
 fun export(context:Context,uri:Uri,ranges:List<Range>,file:File,onProgress:(Int)->Unit) {
  require(ranges.isNotEmpty())
  val probe=MediaExtractor()
  probe.setDataSource(context,uri,null)
  val video=(0 until probe.trackCount).firstOrNull {
   probe.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/")==true
  } ?: error("No video track")
  val audio=(0 until probe.trackCount).firstOrNull {
   probe.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/")==true
  }
  val vf=probe.getTrackFormat(video)
  val af=audio?.let {probe.getTrackFormat(it)}
  require(vf.getString(MediaFormat.KEY_MIME) in listOf("video/avc","video/hevc")) {
   "Native MP4 copy requires H.264 or H.265 video"
  }
  if(af!=null)require(af.getString(MediaFormat.KEY_MIME)=="audio/mp4a-latm") {
   "Native MP4 copy requires AAC audio"
  }
  probe.release()
  val muxer=MediaMuxer(file.absolutePath,MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
  var started=false
  try {
   val outVideo=muxer.addTrack(vf)
   val outAudio=af?.let {muxer.addTrack(it)}
   muxer.start();started=true
   val buffer=ByteBuffer.allocateDirect(4*1024*1024)
   val info=MediaCodec.BufferInfo()
   var outputOffsetUs=0L
   ranges.forEachIndexed { index,range ->
    require(range.endMs>range.startMs)
    val v=MediaExtractor()
    val a=if(audio!=null)MediaExtractor() else null
    try {
     v.setDataSource(context,uri,null);v.selectTrack(video)
     v.seekTo(range.startMs*1000,MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
     val startUs=v.sampleTime
     require(startUs>=0L){"Unable to seek video"}
     val endUs=range.endMs*1000
     if(a!=null && audio!=null) {
      a.setDataSource(context,uri,null);a.selectTrack(audio)
      a.seekTo(startUs,MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
      while(a.sampleTime>=0 && a.sampleTime<startUs) {
       if(!a.advance())break
      }
     }
     var lastVideoUs=startUs
     var lastAudioUs=startUs
     while(true) {
      val vt=v.sampleTime
      val at=a?.sampleTime ?: -1L
      val videoValid=vt>=0 && vt<endUs
      val audioValid=at>=startUs && at<endUs && outAudio!=null
      if(!videoValid && !audioValid)break
      val useVideo=videoValid && (!audioValid || vt<=at)
      val ex=if(useVideo)v else a!!
      val sampleTime=ex.sampleTime
      buffer.clear()
      val size=ex.readSampleData(buffer,0)
      if(size<0)break
      info.set(0,size,outputOffsetUs+max(0L,sampleTime-startUs),
       if(useVideo && (ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC)!=0)
        MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
      muxer.writeSampleData(if(useVideo)outVideo else outAudio!!,buffer,info)
      if(useVideo)lastVideoUs=sampleTime else lastAudioUs=sampleTime
      ex.advance()
     }
     outputOffsetUs+=max(1L,max(lastVideoUs,lastAudioUs)-startUs+33_333L)
    }finally {v.release();a?.release()}
    onProgress(((index+1)*100/ranges.size))
   }
   muxer.stop();started=false
  }finally {
   if(started)try {muxer.stop()}catch(_:Exception){}
   muxer.release()
  }
 }
}
