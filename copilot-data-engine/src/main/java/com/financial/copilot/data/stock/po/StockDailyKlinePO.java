package com.financial.copilot.data.stock.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * <h1>股票日 K 线持久化对象 (Stock Daily Kline PO)</h1>
 * <p>
 * 对应数据库物理表: {@code stock_daily_kline}
 * 记录个股每个交易日的开高低收与成交量/额，为技术指标 (RSI/MACD/SMA/EMA/波动率) 提供底层数据支撑。
 * </p>
 *
 * @author FinancialCopilot
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("stock_daily_kline")
public class StockDailyKlinePO {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 股票代码 (如 600519.SH) */
    private String stockCode;

    /** 交易日期 */
    private LocalDate tradeDate;

    /** 开盘价 */
    private BigDecimal open;

    /** 最高价 */
    private BigDecimal high;

    /** 最低价 */
    private BigDecimal low;

    /** 收盘价 */
    private BigDecimal close;

    /** 成交量 (股) */
    private BigDecimal volume;

    /** 成交额 (元) */
    private BigDecimal amount;
}
