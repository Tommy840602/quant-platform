package com.quant.common.strategy;
import com.quant.common.dto.Kline;
import java.util.*;
/** 蔡金資金流 CMF：>0 買、<0 賣。 */
public class CmfStrategy extends BaseStrategy {
    private final int p; private final Deque<double[]> mv=new ArrayDeque<>(); private Double prev;
    public CmfStrategy(String id,int period,double qty){super(id,qty);this.p=period;}
    @Override public void onBar(Kline b, StrategyContext ctx){
        if(!b.closed())return; double hl=b.high()-b.low();
        double mfm=hl==0?0:((b.close()-b.low())-(b.high()-b.close()))/hl; double mfv=mfm*b.volume();
        mv.addLast(new double[]{mfv,b.volume()}); while(mv.size()>p)mv.removeFirst(); if(mv.size()<p)return;
        double s=0,v=0; for(double[] x:mv){s+=x[0];v+=x[1];} double cmf=v==0?0:s/v;
        if(prev!=null){ if(prev<=0&&cmf>0)buy(ctx,b.symbol()); else if(prev>=0&&cmf<0)sell(ctx,b.symbol()); }
        prev=cmf;
    }
}
