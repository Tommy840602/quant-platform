package com.quant.common.strategy;
import com.quant.common.dto.Kline;
/** TEMA 三重指數均線交叉(快/慢)。 */
public class TemaStrategy extends BaseStrategy {
    private final double kf,ks; private Double f1,f2,f3,s1,s2,s3,prevDiff;
    public TemaStrategy(String id,int fast,int slow,double qty){super(id,qty);this.kf=2.0/(fast+1);this.ks=2.0/(slow+1);}
    private double t(double c,double k,double[] e){ e[0]=e[0]==0?c:c*k+e[0]*(1-k); e[1]=e[1]==0?e[0]:e[0]*k+e[1]*(1-k); e[2]=e[2]==0?e[1]:e[1]*k+e[2]*(1-k); return 3*e[0]-3*e[1]+e[2]; }
    private final double[] ef={0,0,0}, es={0,0,0};
    @Override public void onBar(Kline b, StrategyContext ctx){
        if(!b.closed())return; double c=b.close(); double tf=t(c,kf,ef), tsv=t(c,ks,es), diff=tf-tsv;
        if(prevDiff!=null){ if(prevDiff<=0&&diff>0)buy(ctx,b.symbol()); else if(prevDiff>=0&&diff<0)sell(ctx,b.symbol()); }
        prevDiff=diff;
    }
}
