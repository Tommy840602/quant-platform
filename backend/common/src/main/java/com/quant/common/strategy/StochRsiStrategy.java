package com.quant.common.strategy;
import com.quant.common.dto.Kline;
import java.util.*;
/** StochRSI：<20 買、>80 賣。 */
public class StochRsiStrategy extends BaseStrategy {
    private final int p; private final Deque<Double> closes = new ArrayDeque<>();
    public StochRsiStrategy(String id,int period,double qty){super(id,qty);this.p=period;}
    @Override public void onBar(Kline b, StrategyContext ctx){
        if(!b.closed())return; closes.addLast(b.close()); while(closes.size()>p*3)closes.removeFirst();
        if(closes.size()<p*2+1)return;
        List<Double> c=new ArrayList<>(closes); List<Double> rsis=new ArrayList<>();
        for(int i=p;i<c.size();i++){double g=0,l=0;for(int j=i-p+1;j<=i;j++){double d=c.get(j)-c.get(j-1);if(d>=0)g+=d;else l-=d;}double rs=l==0?100:(g/p)/(l/p);rsis.add(100-100/(1+rs));}
        int n=Math.min(p,rsis.size()); double hh=-1e18,ll=1e18; for(int i=rsis.size()-n;i<rsis.size();i++){hh=Math.max(hh,rsis.get(i));ll=Math.min(ll,rsis.get(i));}
        double cur=rsis.get(rsis.size()-1); double sr=hh==ll?50:(cur-ll)/(hh-ll)*100;
        if(sr<20)buy(ctx,b.symbol()); else if(sr>80)sell(ctx,b.symbol());
    }
}
