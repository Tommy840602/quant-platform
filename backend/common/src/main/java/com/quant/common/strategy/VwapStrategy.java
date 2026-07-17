package com.quant.common.strategy;
import com.quant.common.dto.Kline;
import java.util.*;
/** VWAP 偏離：低於 VWAP*(1-dev) 買、高於 *(1+dev) 賣。 */
public class VwapStrategy extends BaseStrategy {
    private final int period; private final double dev;
    private final Deque<double[]> pv = new ArrayDeque<>();
    public VwapStrategy(String id,int period,double dev,double qty){super(id,qty);this.period=period;this.dev=dev;}
    @Override public void onBar(Kline b, StrategyContext ctx){
        if(!b.closed())return; double tp=(b.high()+b.low()+b.close())/3;
        pv.addLast(new double[]{tp*b.volume(),b.volume()}); while(pv.size()>period)pv.removeFirst();
        double pvs=0,vs=0; for(double[] x:pv){pvs+=x[0];vs+=x[1];} if(vs==0)return; double vwap=pvs/vs;
        if(b.close()<vwap*(1-dev))buy(ctx,b.symbol()); else if(b.close()>vwap*(1+dev))sell(ctx,b.symbol());
    }
}
