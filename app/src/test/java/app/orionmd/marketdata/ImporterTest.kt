package app.orionmd.marketdata

import app.orionmd.marketdata.data.ImportField
import app.orionmd.marketdata.data.Importer
import app.orionmd.marketdata.data.PortfolioCalc
import app.orionmd.marketdata.data.TxnKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ImporterTest {
    private fun drafts(text: String) = Importer.parse(text).let { Importer.drafts(it, it.mapping) }

    @Test fun template() {
        val d = drafts(Importer.TEMPLATE)
        assertEquals(5, d.size)
        assertTrue(d.all { it.include && it.problems().isEmpty() })
        assertEquals(TxnKind.SELL, d[2].kind)
        assertEquals(TxnKind.DIVIDEND, d[3].kind)
        assertEquals("2", d[3].price)
        assertEquals("BTC-USD", d[4].symbol)
    }

    @Test fun fidelityWithTitleLinesAndFooter() {
        val csv = "﻿\n\nBrokerage\n\nRun Date,Action,Symbol,Description,Type,Quantity,Price (\$),Commission (\$),Fees (\$),Accrued Interest (\$),Amount (\$),Settlement Date\n" +
            "09/15/2026,YOU BOUGHT APPLE INC (AAPL) (Cash),AAPL,APPLE INC,Cash,10,185.5,,,,-1855,09/16/2026\n" +
            "09/10/2026,DIVIDEND RECEIVED MICROSOFT (MSFT) (Cash),MSFT,MICROSOFT,Cash,,,,,,12.34,\n" +
            "09/05/2026,YOU SOLD NVIDIA (NVDA) (Cash),NVDA,NVIDIA,Cash,-3,120.25,0.65,0.02,,360.08,09/06/2026\n" +
            "09/01/2026,REINVESTMENT SPDR (SPY) (Cash),SPY,SPDR,Cash,0.05,500,,,,-25,\n" +
            "08/30/2026,Electronic Funds Transfer Received (Cash), ,No Description,Cash,,,,,,1000,\n" +
            "\"The data and information in this spreadsheet is provided to you solely for your use\"\n"
        val p = Importer.parse(csv)
        assertTrue(p.hasHeader)
        assertEquals(0, p.mapping[ImportField.DATE])
        assertEquals(1, p.mapping[ImportField.ACTION])
        val d = Importer.drafts(p, p.mapping)
        assertEquals(5, d.size)
        assertEquals(TxnKind.BUY, d[0].kind); assertEquals("10", d[0].qty); assertEquals("185.5", d[0].price); assertEquals("2026-09-15", d[0].date)
        assertEquals(TxnKind.DIVIDEND, d[1].kind); assertEquals("12.34", d[1].price); assertTrue(d[1].problems().isEmpty())
        assertEquals(TxnKind.SELL, d[2].kind); assertEquals("3", d[2].qty); assertEquals("0.67", d[2].fees) // commission + fees
        assertEquals(TxnKind.BUY, d[3].kind)
        assertEquals(TxnKind.DEPOSIT, d[4].kind); assertEquals("1000", d[4].price) // bank transfer in → cash deposit
        assertEquals(5, d.count { it.include && it.problems().isEmpty() })
    }

    @Test fun schwab() {
        val csv = "\"Date\",\"Action\",\"Symbol\",\"Description\",\"Quantity\",\"Price\",\"Fees & Comm\",\"Amount\"\n" +
            "\"09/15/2026 as of 09/12/2026\",\"Buy\",\"AAPL\",\"APPLE INC\",\"10\",\"\$185.50\",\"\",\"-\$1,855.00\"\n" +
            "\"09/10/2026\",\"Qualified Dividend\",\"MSFT\",\"MICROSOFT\",\"\",\"\",\"\",\"\$12.34\"\n" +
            "\"09/09/2026\",\"Reinvest Shares\",\"SCHD\",\"SCHWAB US DIV\",\"0.5\",\"\$27.00\",\"\",\"-\$13.50\"\n" +
            "\"09/08/2026\",\"Sell\",\"TSLA\",\"TESLA\",\"2\",\"\$250.00\",\"\$0.03\",\"\$499.97\"\n" +
            "Transactions Total,,,,,,,\"-\$1,356.19\"\n"
        val d = drafts(csv)
        assertEquals("2026-09-15", d[0].date)
        assertEquals("185.5", d[0].price)
        assertEquals(TxnKind.DIVIDEND, d[1].kind); assertEquals("12.34", d[1].price)
        assertEquals(TxnKind.BUY, d[2].kind)
        assertEquals(TxnKind.SELL, d[3].kind); assertEquals("0.03", d[3].fees)
        assertEquals(4, d.count { it.include && it.problems().isEmpty() })
    }

    @Test fun robinhood() {
        val csv = "\"Activity Date\",\"Process Date\",\"Settle Date\",\"Instrument\",\"Description\",\"Trans Code\",\"Quantity\",\"Price\",\"Amount\"\n" +
            "\"9/15/2026\",\"9/15/2026\",\"9/16/2026\",\"AAPL\",\"Apple\",\"Buy\",\"10\",\"\$185.50\",\"(\$1,855.00)\"\n" +
            "\"9/10/2026\",\"9/10/2026\",\"9/10/2026\",\"MSFT\",\"Cash Div\",\"CDIV\",\"\",\"\",\"\$12.34\"\n" +
            "\"9/09/2026\",\"9/09/2026\",\"9/09/2026\",\"\",\"ACH Deposit\",\"ACH\",\"\",\"\",\"\$500.00\"\n"
        val p = Importer.parse(csv)
        assertEquals(5, p.mapping[ImportField.ACTION])
        assertEquals(0, p.mapping[ImportField.DATE])
        val d = Importer.drafts(p, p.mapping)
        assertEquals(TxnKind.BUY, d[0].kind); assertEquals("2026-09-15", d[0].date)
        assertEquals(TxnKind.DIVIDEND, d[1].kind)
        assertEquals(TxnKind.DEPOSIT, d[2].kind); assertEquals("500", d[2].price)
    }

    @Test fun europeanSemicolon() {
        val d = drafts("Symbol;Qty;Price;Date\nAAPL;10;185,50;15.01.2026\nSAP;2;1.234,56;2026-02-01\n")
        assertEquals("185.5", d[0].price); assertEquals("2026-01-15", d[0].date)
        assertEquals("1234.56", d[1].price)
        assertTrue(d.all { it.problems().isEmpty() })
    }

    @Test fun tabSeparated() {
        val d = drafts("ticker\tshares\tprice\ttrade date\nVOO\t3\t480.10\t2026-03-01\n")
        assertEquals("VOO", d[0].symbol); assertEquals("3", d[0].qty); assertTrue(d[0].problems().isEmpty())
    }

    @Test fun pastedListsWithoutHeader() {
        val d = drafts("AAPL, 10, 185.50, 2026-01-15\nMSFT, 5, 410.25, 2026-02-03\n")
        assertEquals(2, d.size)
        assertEquals("AAPL", d[0].symbol); assertEquals("10", d[0].qty); assertEquals("185.5", d[0].price); assertEquals("2026-01-15", d[0].date)
        assertTrue(d.all { it.problems().isEmpty() })

        val e = drafts("BUY MSFT 5 @ 410.25\nSELL NVDA 2 @ 120\nBUY AAPL 1 @ 180")
        assertEquals(listOf("MSFT", "NVDA", "AAPL"), e.map { it.symbol })
        assertEquals(TxnKind.SELL, e[1].kind)
        assertEquals("410.25", e[0].price); assertEquals("5", e[0].qty)
    }

    @Test fun symbolsOnly() {
        val d = drafts("AAPL\nMSFT\nNVDA\n")
        assertEquals(3, d.size)
        assertEquals("MSFT", d[1].symbol)
        assertTrue(d[1].problems().any { it.contains("Quantity") })
    }

    @Test fun numbers() {
        assertEquals(1855.0, Importer.num("-\$1,855.00")!! * -1, 1e-9)
        assertEquals(-12.5, Importer.num("(12.50)")!!, 1e-9)
        assertEquals(0.05, Importer.num("0,05")!!, 1e-9)
        assertTrue(PortfolioCalc.parseDate("Sep 15, 2026") != null)
    }

    @Test fun etradeTransactions() {
        val csv = "For Account:,#####1234\n\nActivity/Trade Date,Transaction Date,Settlement Date,Activity Type,Description,Symbol,Cusip,Quantity #,Price \$,Amount \$,Commission,Category,Note\n" +
            "09/15/26,09/15/26,09/16/26,Bought,APPLE INC COM,AAPL,037833100,10,185.5,-1855,0,--,--\n" +
            "09/10/26,09/10/26,09/10/26,Dividend,MICROSOFT CORP COM,MSFT,594918104,0,0,12.34,0,--,--\n" +
            "09/05/26,09/05/26,09/06/26,Sold,NVIDIA CORP COM,NVDA,67066G104,-3,120.25,360.08,0.65,--,--\n" +
            "09/01/26,09/01/26,09/01/26,Reinvestment,SPDR S&P 500 ETF,SPY,78462F103,0.05,500,-25,0,--,--\n" +
            "08/30/26,08/30/26,08/30/26,Online Transfer,TRANSFER FROM BANK,--,,0,0,1000,0,--,--\n" +
            "08/29/26,08/29/26,08/29/26,Interest,INTEREST INCOME,,,0,0,0.42,0,--,--\n"
        val p = Importer.parse(csv)
        assertEquals("E*TRADE transactions", p.broker)
        assertEquals(0, p.mapping[ImportField.DATE]); assertEquals(3, p.mapping[ImportField.ACTION]); assertEquals(5, p.mapping[ImportField.SYMBOL])
        assertEquals(8, p.mapping[ImportField.PRICE]); assertEquals(10, p.mapping[ImportField.COMMISSION])
        val d = Importer.drafts(p, p.mapping)
        assertEquals(6, d.size)
        assertEquals(TxnKind.BUY, d[0].kind); assertEquals("2026-09-15", d[0].date); assertEquals("10", d[0].qty); assertEquals("185.5", d[0].price)
        assertEquals(TxnKind.DIVIDEND, d[1].kind); assertEquals("12.34", d[1].price)
        assertEquals(TxnKind.SELL, d[2].kind); assertEquals("3", d[2].qty); assertEquals("0.65", d[2].fees)
        assertEquals(TxnKind.BUY, d[3].kind)
        assertEquals(TxnKind.DEPOSIT, d[4].kind); assertEquals("1000", d[4].price)
        assertEquals(TxnKind.DEPOSIT, d[5].kind); assertEquals("Interest", d[5].note)
        assertEquals(6, d.count { it.include && it.problems().isEmpty() })
    }

    @Test fun etradeOldFormat() {
        val csv = "TransactionDate,TransactionType,SecurityType,Symbol,Quantity,Amount,Price,Commission,Description\n" +
            "09/15/26,Bought,EQ,AAPL,10,-1855,185.5,0,APPLE INC\n" +
            "09/10/26,Dividend,EQ,MSFT,0,12.34,0,0,MICROSOFT DIV\n" +
            "09/05/26,Sold,EQ,NVDA,-3,360.08,120.25,0.65,NVIDIA\n"
        val p = Importer.parse(csv)
        assertEquals("E*TRADE transactions", p.broker)
        val d = Importer.drafts(p, p.mapping)
        assertEquals(listOf(TxnKind.BUY, TxnKind.DIVIDEND, TxnKind.SELL), d.map { it.kind })
        assertTrue(d.all { it.problems().isEmpty() })
    }

    @Test fun etradePortfolioPositions() {
        val csv = "Account Summary\nAccount,Net Account Value,Total Gain,Total Gain %,Day's Gain Unrealized,Day's Gain Unrealized %,Available For Withdrawal,Cash Purchasing Power\n" +
            "Brokerage -1234,25000,1500,6.4,120,0.5,500,500\n\n" +
            "Symbol,Last Price \$,Change \$,Change %,Quantity,Price Paid \$,Day's Gain \$,Total Gain \$,Total Gain %,Value \$\n" +
            "AAPL,210.5,1.2,0.57,10,185.5,12,250,13.48,2105\n" +
            "MSFT,420.1,-2.3,-0.54,5,410.25,-11.5,49.25,2.4,2100.5\n" +
            "CASH,,,,,,,,,500\n" +
            "TOTAL,,,,,,,,,4705.5\n"
        val p = Importer.parse(csv)
        assertEquals("E*TRADE portfolio (positions)", p.broker)
        assertEquals(5, p.mapping[ImportField.PRICE]) // Price Paid, not Last Price
        val d = Importer.drafts(p, p.mapping)
        assertEquals(listOf("AAPL", "MSFT"), d.filter { it.include }.map { it.symbol })
        assertEquals("185.5", d[0].price); assertEquals("10", d[0].qty)
        assertTrue(d.filter { it.include }.all { it.problems().isEmpty() })
    }

    @Test fun descriptionMentioningSymbolIsNotAHeader() {
        val csv = "Date,Action,Symbol,Quantity,Price\n2026-01-02,Buy,AAPL,1,100\n2026-01-03,Journal,XYZ,0,0\n2026-01-04,Buy,MSFT,2,200\n"
        val d = drafts(csv.replace("Journal,XYZ", "SYMBOL CHANGE,XYZ"))
        assertEquals(3, d.size)
    }

    @Test fun etradeCryptoZeroHash() {
        val csv = "Activity/Trade Date,Transaction Date,Settlement Date,Activity Type,Description,Symbol,Cusip,Quantity #,Price \$,Amount \$,Commission,Category,Note\n" +
            "09/15/26,09/15/26,09/15/26,Bought,BITCOIN (ZERO HASH),BTC,,0.01,80000,-800,,--,--\n" +
            "09/16/26,09/16/26,09/16/26,Sold,ETHEREUM CRYPTO,ETHUSD,,0.5,3000,1500,,--,--\n" +
            "09/17/26,09/17/26,09/17/26,Bought,APPLE INC,AAPL,037833100,1,200,-200,0,--,--\n"
        val d = drafts(csv).toMutableList()
        assertEquals(listOf("BTC-USD", "ETH-USD", "AAPL"), d.map { it.symbol })
        assertEquals(2, Importer.applyCryptoFee(d, 0.5))
        assertEquals("4", d[0].fees)    // 0.5% of $800
        assertEquals("7.5", d[1].fees)  // 0.5% of $1,500
        assertEquals("0", d[2].fees)    // stock untouched
        assertEquals("BTC", Importer.normalizeCrypto("BTC")) // plain BTC without a crypto hint stays as typed
    }

    @Test fun cashFromSales() {
        fun t(kind: TxnKind, sym: String, q: Double, p: Double, day: Long, fees: Double = 0.0) =
            app.orionmd.marketdata.data.Txn("x$day", sym, kind, q, p, fees, day * 86_400_000L)
        val txns = listOf(
            t(TxnKind.BUY, "AAPL", 10.0, 100.0, 1),          // $1,000 of new money (no cash yet)
            t(TxnKind.SELL, "AAPL", 4.0, 150.0, 2, 1.0),     // +$599 proceeds
            t(TxnKind.DIVIDEND, "AAPL", 0.0, 6.0, 3),        // +$6
            t(TxnKind.BUY, "MSFT", 1.0, 400.0, 4),           // paid from cash: 605 → 205
            t(TxnKind.DEPOSIT, "CASH", 0.0, 1000.0, 5),      // +$1,000
            t(TxnKind.WITHDRAWAL, "CASH", 0.0, 200.0, 6),    // −$200
        )
        val c = app.orionmd.marketdata.data.PortfolioCalc.cash(txns)
        assertEquals(1005.0, c.balance, 1e-9)
        assertEquals(599.0, c.saleProceeds, 1e-9)
        assertEquals(1000.0, c.newMoney, 1e-9)
        val h = app.orionmd.marketdata.data.PortfolioCalc.holdings(txns)
        assertEquals(listOf("AAPL", "MSFT"), h.map { it.symbol }) // no CASH holding
        assertEquals(6.0, h.first().qty, 1e-9)
    }
}
