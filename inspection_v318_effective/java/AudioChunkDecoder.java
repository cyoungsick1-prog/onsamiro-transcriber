package com.onsamiro.transcriber;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.media.AudioFormat;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;
import android.net.Uri;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

public final class AudioChunkDecoder {
    public static final class Decoded {
        public final float[] pcm16k;
        public final long decodedMs;
        public final long speechMs;
        public Decoded(float[] p,long d,long s){pcm16k=p;decodedMs=d;speechMs=s;}
    }

    public static long durationMs(Context c, Uri uri) throws Exception {
        MediaMetadataRetriever mmr=new MediaMetadataRetriever();
        try { mmr.setDataSource(c,uri); String d=mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION); return d==null?0:Long.parseLong(d); }
        finally { try{mmr.release();}catch(Throwable ignored){} }
    }

    public static Decoded decodeRange(Context c, Uri uri, long startMs, long endMs) throws Exception {
        MediaExtractor ex=new MediaExtractor(); MediaCodec codec=null; AssetFileDescriptor afd=null;
        FloatBuilder out=new FloatBuilder((int)Math.min(Integer.MAX_VALUE-8,Math.max(16000,(endMs-startMs)*16)));
        try {
            afd=c.getContentResolver().openAssetFileDescriptor(uri,"r"); if(afd==null)throw new IllegalStateException("원본을 열 수 없습니다");
            ex.setDataSource(afd.getFileDescriptor(),afd.getStartOffset(),afd.getLength());
            int track=-1; MediaFormat fmt=null;
            for(int i=0;i<ex.getTrackCount();i++){MediaFormat f=ex.getTrackFormat(i);String mime=f.getString(MediaFormat.KEY_MIME);if(mime!=null&&mime.startsWith("audio/")){track=i;fmt=f;break;}}
            if(track<0||fmt==null)throw new IllegalStateException("오디오 트랙 없음");
            ex.selectTrack(track); ex.seekTo(startMs*1000L,MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
            String mime=fmt.getString(MediaFormat.KEY_MIME); codec=MediaCodec.createDecoderByType(mime); codec.configure(fmt,null,null,0); codec.start();
            boolean inDone=false,outDone=false; MediaCodec.BufferInfo info=new MediaCodec.BufferInfo(); long endUs=endMs*1000L, startUs=startMs*1000L, nextOutUs=startUs;
            int sampleRate=fmt.containsKey(MediaFormat.KEY_SAMPLE_RATE)?fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE):48000;
            int channels=fmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT)?fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT):1;
            int pcmEncoding=AudioFormat.ENCODING_PCM_16BIT;
            while(!outDone) {
                if(!inDone) {
                    int idx=codec.dequeueInputBuffer(10_000); if(idx>=0){ByteBuffer ib=codec.getInputBuffer(idx);int n=ex.readSampleData(ib,0);long pts=ex.getSampleTime();if(n<0||pts<0||pts>endUs){codec.queueInputBuffer(idx,0,0,Math.max(startUs,pts),MediaCodec.BUFFER_FLAG_END_OF_STREAM);inDone=true;}else{codec.queueInputBuffer(idx,0,n,pts,0);ex.advance();}}
                }
                int oi=codec.dequeueOutputBuffer(info,10_000);
                if(oi==MediaCodec.INFO_OUTPUT_FORMAT_CHANGED){MediaFormat of=codec.getOutputFormat();sampleRate=of.containsKey(MediaFormat.KEY_SAMPLE_RATE)?of.getInteger(MediaFormat.KEY_SAMPLE_RATE):sampleRate;channels=of.containsKey(MediaFormat.KEY_CHANNEL_COUNT)?of.getInteger(MediaFormat.KEY_CHANNEL_COUNT):channels;pcmEncoding=of.containsKey(MediaFormat.KEY_PCM_ENCODING)?of.getInteger(MediaFormat.KEY_PCM_ENCODING):AudioFormat.ENCODING_PCM_16BIT;}
                else if(oi>=0){ByteBuffer b=codec.getOutputBuffer(oi);if(b!=null&&info.size>0){b.position(info.offset);b.limit(info.offset+info.size);b=b.slice().order(ByteOrder.LITTLE_ENDIAN);int bytesPerSample=pcmEncoding==AudioFormat.ENCODING_PCM_FLOAT?4:2;int frameBytes=Math.max(1,bytesPerSample*channels);int frames=info.size/frameBytes;long bufStart=info.presentationTimeUs,bufEnd=bufStart+(long)(frames*1_000_000.0/sampleRate);if(nextOutUs<bufStart)nextOutUs=Math.max(startUs,bufStart);while(nextOutUs<bufEnd&&nextOutUs<endUs){double fp=(nextOutUs-bufStart)*sampleRate/1_000_000.0;int fi=(int)Math.floor(fp);if(fi>=0&&fi<frames){float mono=0;for(int ch=0;ch<channels;ch++){int pos=(fi*channels+ch)*bytesPerSample;if(pcmEncoding==AudioFormat.ENCODING_PCM_FLOAT)mono+=b.getFloat(pos);else mono+=b.getShort(pos)/32768f;}out.add(mono/channels);}nextOutUs+=62; /* ~16 kHz; corrected by accumulator below */}
                    // correct 16 kHz period drift by deriving next sample count from output size
                    nextOutUs=startUs+(long)(out.size()*1_000_000.0/16000.0);
                } codec.releaseOutputBuffer(oi,false); if((info.flags&MediaCodec.BUFFER_FLAG_END_OF_STREAM)!=0)outDone=true; if(info.presentationTimeUs>endUs)outDone=true;}
            }
            float[] pcm=out.toArray();long decoded=(long)(pcm.length*1000.0/16000.0);long speech=estimateSpeechMs(pcm);return new Decoded(pcm,decoded,speech);
        } finally { try{if(codec!=null){codec.stop();codec.release();}}catch(Throwable ignored){}try{ex.release();}catch(Throwable ignored){}try{if(afd!=null)afd.close();}catch(Throwable ignored){} }
    }

    private static long estimateSpeechMs(float[] p){int win=320,total=0,voiced=0;for(int i=0;i+win<=p.length;i+=win){double ss=0;for(int k=i;k<i+win;k++)ss+=p[k]*p[k];double rms=Math.sqrt(ss/win);total++;if(rms>=0.008)voiced++;}return voiced*20L;}
    private static final class FloatBuilder { float[] a;int n;FloatBuilder(int hint){a=new float[Math.max(4096,Math.min(hint,3_000_000))];}void add(float v){if(n==a.length)a=Arrays.copyOf(a,Math.min(Integer.MAX_VALUE-8,a.length+(a.length>>1)+1024));a[n++]=v;}int size(){return n;}float[] toArray(){return Arrays.copyOf(a,n);} }
}
