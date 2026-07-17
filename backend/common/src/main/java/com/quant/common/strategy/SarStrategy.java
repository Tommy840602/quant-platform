package com.quant.common.strategy;
import com.quant.common.dto.Kline;
/** 拋物線 SAR：SAR 翻轉方向時進出場。 */
public class SarStrategy extends BaseStrategy {
    private final double step, max; private Double sar, ep; private int trend=1; private double af;
    public SarStrategy(String id,double step,double max,double qty){super(id,qty);this.step=step;this.max=max;this.af=step;}
    @Override public void onBar(Kline b, StrategyContext ctx){
        if(!b.closed())return;
        if(sar==null){sar=b.low();ep=b.high();return;}
        double prevSar=sar; sar=sar+af*(ep-sar);
        int prev=trend;
        if(trend==1){ if(b.low()<sar){trend=-1;sar=ep;ep=b.low();af=step;} else { if(b.high()>ep){ep=b.high();af=Math.min(max,af+step);} } }
        else { if(b.high()>sar){trend=1;sar=ep;ep=b.high();af=step;} else { if(b.low()<ep){ep=b.low();af=Math.min(max,af+step);} } }
        if(prev<0&&trend>0)buy(ctx,b.symbol()); else if(prev>0&&trend<0)sell(ctx,b.symbol());
    }
}
