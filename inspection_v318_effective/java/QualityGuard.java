package com.onsamiro.transcriber;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class QualityGuard {
    public enum Verdict { PASS, NO_AUDIO, PARTIAL, REVIEW_REQUIRED }
    public static final class Input {
        public long durationMs, decodedMs, speechMs, processedUntilMs;
        public int totalSegments, doneSegments, failedSegments;
        public String text="";
    }
    public static final class Result {
        public final Verdict verdict; public final List<String> reasons;
        public Result(Verdict v,List<String> r){verdict=v;reasons=r;}
        public String reasonText(){return String.join(" / ",reasons);}
    }
    private static final Set<String> FILLERS=new HashSet<>(Arrays.asList("음","어","아","네","예","응","여보세요","안녕하세요","저기","그","아니"));

    public static Result evaluate(Input in) {
        List<String> why=new ArrayList<>(); long dur=Math.max(1,in.durationMs); double coverage=in.decodedMs/(double)dur, speech=in.speechMs/(double)dur;
        if(in.failedSegments>0 || (in.totalSegments>0 && in.doneSegments<in.totalSegments)){why.add("처리하지 못한 구간이 있음");return new Result(Verdict.PARTIAL,why);}
        if(in.durationMs>3000 && coverage<0.94){why.add(String.format(Locale.ROOT,"원본 대비 디코딩 범위 %.0f%%",coverage*100));return new Result(Verdict.REVIEW_REQUIRED,why);}
        if(in.durationMs>5000 && in.processedUntilMs+2500<in.durationMs){why.add("원본 끝부분까지 처리되지 않음");return new Result(Verdict.REVIEW_REQUIRED,why);}
        if(speech<0.012){why.add("음성 구간이 거의 감지되지 않음");return new Result(Verdict.NO_AUDIO,why);}

        String text=in.text==null?"":in.text.trim(); String compact=text.replaceAll("[\\s\\p{Punct}·…]+","");
        if(compact.isEmpty()){why.add("음성은 감지됐지만 전사문이 비어 있음");return new Result(Verdict.REVIEW_REQUIRED,why);}
        String[] tokens=text.replaceAll("[^가-힣A-Za-z0-9 ]+"," ").trim().split("\\s+");
        int meaningful=0;Set<String> unique=new HashSet<>();for(String t:tokens){if(t.isEmpty())continue;unique.add(t);if(!FILLERS.contains(t))meaningful++;}
        if(in.durationMs<=8000 && compact.length()>=2 && speech>=0.03){why.add("짧은 정상통화 조건 충족");return new Result(Verdict.PASS,why);}
        if(in.durationMs>20_000 && meaningful<3){why.add("통화 길이에 비해 의미 있는 단어가 너무 적음");return new Result(Verdict.REVIEW_REQUIRED,why);}
        if(in.durationMs>45_000 && compact.length()/(in.durationMs/1000.0)<0.35 && speech>0.05){why.add("음성량 대비 전사 결과가 지나치게 짧음");return new Result(Verdict.REVIEW_REQUIRED,why);}
        double rep=repetitionScore(tokens); if(rep>0.55){why.add(String.format(Locale.ROOT,"반복 문구 비율이 높음 %.0f%%",rep*100));return new Result(Verdict.REVIEW_REQUIRED,why);}
        if(tokens.length>=12 && unique.size()<=2){why.add("같은 단어가 과도하게 반복됨");return new Result(Verdict.REVIEW_REQUIRED,why);}
        why.add("원본범위·음성량·분량·반복 검사 통과");return new Result(Verdict.PASS,why);
    }

    static double repetitionScore(String[] t){if(t.length<6)return 0;Map<String,Integer> m=new HashMap<>();int grams=0,dup=0;for(int i=0;i+2<t.length;i++){String g=t[i]+"\u0001"+t[i+1]+"\u0001"+t[i+2];int n=m.getOrDefault(g,0);if(n>0)dup++;m.put(g,n+1);grams++;}return grams==0?0:dup/(double)grams;}
}
