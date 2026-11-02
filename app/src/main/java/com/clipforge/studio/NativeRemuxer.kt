package com.clipforge.studio

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import java.io.File
import java.nio.ByteBuffer
import kotlin.math.max

/**
 * No video decoder, encoder or Media3 Transformer involved.
 * Remuxes compatible source streams with keyframe-aligned passage boundaries.
 * This is a lossless 16:9 long-video path; shorts still use the separate crop pipeline.
 */
internal object NativeRemuxer {
 data class Range(val startMs: Long, val endMs: Long)
 fun export(context: Context, source: Uri, output: File, ranges: List<Range>) {
  require(ranges.isNotEmpty())
  val afd=context.contentResolver.openAssetFileDescriptor(source,"r")
   ?:error("Cannot open source")
  var muxer:MediaMuxer?=null
  try {
   val probe=MediaExtractor()
   try {
    probe.setDataSource(afd.fileDescriptor,afd.startOffset,afd.length)
    val tracks=(0 until probe.trackCount).filter {
     val mime=probe.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?:""
     mime.startsWith("video/") || mime.startsWith("audio/")
    }
    require(tracks.any { probe.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/")==true })
    muxer=MediaMuxer(output.absolutePath,MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    val mapping=tracks.associateWith { muxer!!.addTrack(probe.getTrackFormat(it)) }
    val videoTrack=tracks.first { probe.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/")==true }
    muxer!!.start()
    var timelineUs=0L
    val buffer=ByteBuffer.allocateDirect(4*1024*1024)
    for(range in ranges) {
     require(range.endMs>range.startMs)
     val startUs=range.startMs*1000L
     val endUs=range.endMs*1000L
     val syncProbe=MediaExtractor()
     val commonStartUs=try {
      syncProbe.setDataSource(afd.fileDescriptor,afd.startOffset,afd.length)
      syncProbe.selectTrack(videoTrack)
      syncProbe.seekTo(startUs,MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
      syncProbe.sampleTime.coerceAtLeast(0L)
     }finally { syncProbe.release() }
     var passageEnd=timelineUs
     for(track in tracks) {
      val extractor=MediaExtractor()
      try {
       extractor.setDataSource(afd.fileDescriptor,afd.startOffset,afd.length)
       extractor.selectTrack(track)
       extractor.seekTo(startUs,MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
       val info=android.media.MediaCodec.BufferInfo()
       var previousPts=-1L
       while(true) {
        val pts=extractor.sampleTime
        if(pts<0 || pts>=endUs)break
        if(pts<commonStartUs) { if(!extractor.advance())break; continue }
        if(extractor.sampleTrackIndex!=track) {
         if(!extractor.advance())break
         continue
        }
        buffer.clear()
        val size=extractor.readSampleData(buffer,0)
        if(size<0)break
        require(size<=buffer.capacity()) { "Sample exceeds remux buffer" }
        val shifted=timelineUs+max(0L,pts-commonStartUs)
        if(shifted>previousPts) {
         info.set(0,size,shifted,extractor.sampleFlags)
         muxer!!.writeSampleData(mapping.getValue(track),buffer,info)
         previousPts=shifted
         passageEnd=max(passageEnd,shifted+33_333L)
        }
        if(!extractor.advance())break
       }
      } finally { extractor.release() }
     }
     timelineUs=passageEnd+1000L
    }
   } finally { probe.release() }
  } finally {
   try { muxer?.stop() } catch (_:Exception) {}
   muxer?.release()
   afd.close()
  }
 }
}
