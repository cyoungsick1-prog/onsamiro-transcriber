import com.onsamiro.transcriber.QualityGuard;
public class QualityGuardSelfTest {
  private static QualityGuard.Input i(long dur,long dec,long speech,int total,int done,int fail,long until,String text){QualityGuard.Input x=new QualityGuard.Input();x.durationMs=dur;x.decodedMs=dec;x.speechMs=speech;x.totalSegments=total;x.doneSegments=done;x.failedSegments=fail;x.processedUntilMs=until;x.text=text;return x;}
  private static void expect(QualityGuard.Verdict v,QualityGuard.Input i){QualityGuard.Result r=QualityGuard.evaluate(i);if(r.verdict!=v)throw new AssertionError("expected "+v+" got "+r.verdict+" "+r.reasonText());}
  public static void main(String[]a){
    expect(QualityGuard.Verdict.REVIEW_REQUIRED,i(90_000,89_500,40_000,1,1,0,90_000,"음 아 안녕하세요"));
    expect(QualityGuard.Verdict.NO_AUDIO,i(60_000,60_000,300,1,1,0,60_000,""));
    expect(QualityGuard.Verdict.PARTIAL,i(400_000,180_000,80_000,3,1,1,180_000,"정상적인 첫 구간 내용입니다"));
    expect(QualityGuard.Verdict.PASS,i(7_000,7_000,2_000,1,1,0,7_000,"네 지금 가고 있어요"));
    expect(QualityGuard.Verdict.PASS,i(70_000,69_500,25_000,1,1,0,70_000,"고객님 현재 사용 중인 요금제 확인했고 기기 변경 조건과 월 납부금액을 설명드렸습니다 가족과 상의 후 다시 연락주시기로 했습니다"));
    System.out.println("QualityGuardSelfTest PASS");
  }
}
