package com.quant.common.strategy;
import com.quant.common.dto.Kline;
import java.util.*;
/** DMI/ADX：+DI 上穿 -DI 買、下穿賣。 */
public class AdxStrategy extends BaseStrategy {
    private final int p; private final Deque<double[]> d=new ArrayDeque<>(); private Double ph,pl,pc; private Double prevDiff;
    public AdxStrategy(String id,int period,double qty){super(id,qty);this.p=period;}
    @Override public void onBar(Kline b, StrategyContext ctx){
        if(!b.closed())return;
        if(ph!=null){ double up=b.high()-ph, dw=pl-b.low();
            double pdm=(up>dw&&up>0)?up:0, mdm=(dw>up&&dw>0)?dw:0;
            double tr=Math.max(b.high()-b.low(),Math.max(Math.abs(b.high()-pc),Math.abs(b.low()-pc)));
            d.addLast(new double[]{pdm,mdm,tr}); while(d.size()>p)d.removeFirst();
            if(d.size()==p){ double sp=0,sm=0,st=0; for(double[] x:d){sp+=x[0];sm+=x[1];st+=x[2];}
                double pdi=st==0?0:sp/st, mdi=st==0?0:sm/st, diff=pdi-mdi;
                if(prevDiff!=null){ if(prevDiff<=0&&diff>0)buy(ctx,b.symbol()); else if(prevDiff>=0&&diff<0)sell(ctx,b.symbol()); }
                prevDiff=diff; } }
        ph=b.high(); pl=b.low(); pc=b.close();
    }
}
