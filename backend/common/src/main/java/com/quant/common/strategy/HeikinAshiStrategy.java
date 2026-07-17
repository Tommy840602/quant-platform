package com.quant.common.strategy;
import com.quant.common.dto.Kline;
/** Heikin-Ashi 趨勢：HA 由陰轉陽買、由陽轉陰賣。 */
public class HeikinAshiStrategy extends BaseStrategy {
    private Double haOpen, haClose; private Integer prevDir;
    public HeikinAshiStrategy(String id,double qty){super(id,qty);}
    @Override public void onBar(Kline b, StrategyContext ctx){
        if(!b.closed())return; double hc=(b.open()+b.high()+b.low()+b.close())/4;
        double ho = haOpen==null? (b.open()+b.close())/2 : (haOpen+haClose)/2;
        haOpen=ho; haClose=hc; int dir=hc>=ho?1:-1;
        if(prevDir!=null){ if(prevDir<0&&dir>0)buy(ctx,b.symbol()); else if(prevDir>0&&dir<0)sell(ctx,b.symbol()); }
        prevDir=dir;
    }
}
