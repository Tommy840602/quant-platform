package com.quant.common.strategy;
import com.quant.common.dto.Kline;
import java.util.*;
/** 成交量突破：量 > 均量*mult 且收漲買、且收跌賣。 */
public class VolBreakStrategy extends BaseStrategy {
    private final int p; private final double mult; private final Deque<Double> vol=new ArrayDeque<>();
    public VolBreakStrategy(String id,int period,double mult,double qty){super(id,qty);this.p=period;this.mult=mult;}
    @Override public void onBar(Kline b, StrategyContext ctx){
        if(!b.closed())return; if(vol.size()>=p){ double avg=vol.stream().mapToDouble(x->x).average().orElse(0);
            if(b.volume()>avg*mult){ if(b.close()>=b.open())buy(ctx,b.symbol()); else sell(ctx,b.symbol()); } }
        vol.addLast(b.volume()); while(vol.size()>p)vol.removeFirst();
    }
}
