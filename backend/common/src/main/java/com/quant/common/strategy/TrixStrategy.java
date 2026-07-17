package com.quant.common.strategy;
import com.quant.common.dto.Kline;
/** TRIX：三重 EMA 變化率零軸交叉。 */
public class TrixStrategy extends BaseStrategy {
    private final double k; private Double e1,e2,e3,prevTrix,prevE3;
    public TrixStrategy(String id,int period,double qty){super(id,qty);this.k=2.0/(period+1);}
    @Override public void onBar(Kline b, StrategyContext ctx){
        if(!b.closed())return; double c=b.close();
        e1=e1==null?c:c*k+e1*(1-k); e2=e2==null?e1:e1*k+e2*(1-k); e3=e3==null?e2:e2*k+e3*(1-k);
        if(prevE3!=null){ double trix=(e3-prevE3)/prevE3;
            if(prevTrix!=null){ if(prevTrix<=0&&trix>0)buy(ctx,b.symbol()); else if(prevTrix>=0&&trix<0)sell(ctx,b.symbol()); }
            prevTrix=trix; }
        prevE3=e3;
    }
}
