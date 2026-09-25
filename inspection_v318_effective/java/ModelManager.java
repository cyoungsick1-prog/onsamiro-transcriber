package com.onsamiro.transcriber;

import android.content.Context;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;

public final class ModelManager {
    public interface Listener { void onProgress(long done,long total,String stage); }
    public static final String RECOMMENDED_URL="https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-small.bin";

    public static File recommendedPath(Context c){File d=new File(c.getFilesDir(),"models");if(!d.exists())d.mkdirs();return new File(d,"ggml-small.bin");}

    public static File downloadRecommended(Context c,Listener l) throws Exception {
        File dst=recommendedPath(c),part=new File(dst.getAbsolutePath()+".part");long existing=part.isFile()?part.length():0;
        HttpURLConnection h=(HttpURLConnection)new URL(RECOMMENDED_URL).openConnection();h.setConnectTimeout(20_000);h.setReadTimeout(60_000);h.setInstanceFollowRedirects(true);if(existing>0)h.setRequestProperty("Range","bytes="+existing+"-");h.connect();int code=h.getResponseCode();boolean append=existing>0&&code==206;if(code/100!=2)throw new IllegalStateException("모델 다운로드 HTTP "+code);if(!append){existing=0;if(part.exists())part.delete();}
        long remain=h.getContentLengthLong(),total=remain>0?existing+remain:-1;try(InputStream in=h.getInputStream();FileOutputStream out=new FileOutputStream(part,append)){byte[] b=new byte[1024*1024];long done=existing;int n;while((n=in.read(b))>0){out.write(b,0,n);done+=n;if(l!=null)l.onProgress(done,total,"다운로드 중");}out.getFD().sync();}finally{h.disconnect();}
        if(part.length()<10L*1024*1024)throw new IllegalStateException("모델 파일이 비정상적으로 작습니다");if(l!=null)l.onProgress(part.length(),part.length(),"모델 무결성 확인 중");
        if(!WhisperBridge.validateModel(part.getAbsolutePath())){part.delete();throw new IllegalStateException("Whisper가 모델 파일을 열지 못했습니다. 손상 가능성이 있습니다");}
        String sha=sha256(part);File side=new File(dst.getAbsolutePath()+".sha256");try(FileOutputStream o=new FileOutputStream(side)){o.write(sha.getBytes(java.nio.charset.StandardCharsets.UTF_8));}
        if(dst.exists()&&!dst.delete())throw new IllegalStateException("기존 모델 교체 실패");if(!part.renameTo(dst))throw new IllegalStateException("모델 저장 완료 처리 실패");return dst;
    }

    public static boolean validateExisting(File f) {
        // This runs before every background scan. Do not hash or open the multi-gigabyte
        // native model here: downloads and explicit model selection already validate it,
        // while WhisperBridge validates it when an actual pending call needs transcription.
        try { return f != null && f.isFile() && f.canRead() && f.length() >= 10L * 1024L * 1024L; }
        catch(Throwable t){return false;}
    }
    private static String sha256(File f)throws Exception{MessageDigest md=MessageDigest.getInstance("SHA-256");try(FileInputStream in=new FileInputStream(f)){byte[] b=new byte[1024*1024];int n;while((n=in.read(b))>0)md.update(b,0,n);}StringBuilder s=new StringBuilder();for(byte x:md.digest())s.append(String.format("%02x",x));return s.toString();}
}
