package play.server;

import java.util.concurrent.atomic.AtomicInteger;

public class Throttle {
	public enum Timespan{
		Second,Minute,Hour,Day,Week,TwoWeeks,Month,Quarter,Year;
		int numberOfSeconds(){
			switch(this){
			case Second: return 1;
			case Minute: return 60;
			case Hour: return 3600;
			case Day: return 3600*24;
			case Week: return 3600*24*7;
			case TwoWeeks: return 3600*24*14;
			case Month: return 3600*24*30;
			case Quarter: return 3600*24*92;
			case Year: return 3600*24*356;
			}
			return 1;
		}
	}
	public enum Result{
		Ok,Last,OverLimit;
		public boolean get(){return this!=OverLimit;}
	}
	
	private final int max;
	private final AtomicInteger counter;
	private final Timespan span;
	private final AtomicInteger counterForSpan;
	
	private static final long countFromMilis = 1434931200000l;
	
	private int currentSpan(){ return  (int) ((System.currentTimeMillis()-countFromMilis)/1000/span.numberOfSeconds());}
	
	public Throttle(int max, Timespan s){
		this.max = max;
		this.span = s;
		counter = new AtomicInteger(0);
		counterForSpan = new AtomicInteger(currentSpan());	
	}
	
	public Result get(){
		int currS = currentSpan();
		int cntS = counterForSpan.getAndSet(currS);
		if(currS!=cntS) counter.set(0);
		int c =  counter.incrementAndGet();
		if(c==max) return Result.Last;
		if(c>max) return Result.OverLimit;
		return Result.Ok;
	}
	
}
