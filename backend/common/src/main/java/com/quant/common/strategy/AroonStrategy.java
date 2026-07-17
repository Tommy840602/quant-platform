package com.quant.common.strategy;
import com.quant.common.dto.Kline;
import java.util.*;
/** Aroon：AroonUp>AroonDown 買、反之賣。 */
public class AroonStrategy extends BaseStrategy {
    private final int p; private final List<double[]> hl=new ArrayList<>(); private Double prevDiff;
    public AroonStrategy(String id,int period,double qty){super(id,qty);this.p=period;}
    @Override public void onBar(Kline b, StrategyContext ctx){
        if(!b.closed())return; hl.add(new double[]{b.high(),b.low()}); if(hl.size()>p+1)hl.remove(0); if(hl.size()<p+1)return;
        int hi=0,lo=0; double hh=-1e18,ll=1e18;
        for(int i=0;i<hl.size();i++){ if(hl.get(i)[0]>=hh){hh=hl.get(i)[0];hi=i;} if(hl.get(i)[1]<=ll){ll=hl.get(i)[1];lo=i;} }
        double up=(double)hi/p*100, dn=(double)lo/p*100, diff=up-dn;
        if(prevDiff!=null){ if(prevDiff<=0&&diff>0)buy(ctx,b.symbol()); else if(prevDiff>=0&&diff<0)sell(ctx,b.symbol()); }
        prevDiff=diff;
    }
}
