# Financial Copilot 工具与组件目录规范 (Tool Catalog)

> **版本**：1.0 | **生成时间**：2026-09-14 | **数据来源**：`docs/temp/tool.json`
> **统计概览**：UI 组件类别 **8** 组，UI 交互组件 **62** 个，Agent 专属投研分析工具 **8** 个

---

## 一、Agent 专属投研分析工具 (Agent Analytical Tools)

Agent 投研分析工具专供大模型（LLM）通过 Function Calling / Tool Calling 模式调用，具备标准化的 `provider`、`server`、`tool`、`dateMode` 及输出类型。

| 工具标识 (ID) | 工具名称 | Provider | Server / Tool | 输出类型 | 时间模式 (dateMode) | 功能描述 |
|:---|:---|:---|:---|:---|:---|:---|
| `review` | **基金点评** | `data_agent` | `T609` | `markdown` | `None` | 单只基金的结构化研究摘要，综合基金基本信息、业绩表现、超额收益、同类排名、资产配置、行业与重仓持股、基金经理履历等数据，提炼基金定位、收益来源、投资风格、持仓特征及潜在风险，帮助用户快速了解基金核心特征与历史表现。 |
| `similar` | **相似基金** | `mcp` | `wind-fund-analysis` / `fund_get_similar` | `table` | `range` | 基于基金类型、业绩、风险和持仓风格获取相似基金列表与相似度。 |
| `brinson` | **Brinson归因** | `mcp` | `wind-fund-analysis` / `fund_get_brinson_attribution` | `table` | `semiAnnualReportDate` | 获取资产配置、行业选择和交互效应等Brinson归因结果。 |
| `brinsonChart` | **Brinson归因图** | `mcp` | `wind-fund-analysis` / `fund_get_brinson_attribution` | `chart` | `semiAnnualReportDate` | 以横向多面板柱状图展示Brinson归因中的超额配置、超额收益、资产配置、个股选择和交互收益。 |
| `style` | **市值风格分析** | `mcp` | `wind-fund-analysis` / `fund_get_market_cap_style` | `table` | `range` | 获取基金市值风格暴露及变化，包括大盘成长、大盘价值、小盘成长、小盘价值等风格暴露。 |
| `nav` | **净值归因分析** | `mcp` | `wind-fund-analysis` / `fund_get_nav_attribution` | `table` | `range` | 获取净值变化来源、因子贡献和基准偏离贡献。 |
| `position` | **仓位估算** | `mcp` | `wind-fund-analysis` / `fund_get_position_estimation` | `table` | `range` | 获取权益、债券等资产的标准化仓位估算。 |
| `timing` | **选股择时能力分析** | `mcp` | `wind-fund-analysis` / `fund_get_selection_timing_analysis` | `table` | `monthEndWithYear` | 获取基金选股能力与择时能力的模型化评估。 |

### Agent 分析工具输出字段明细

#### `review` - 基金点评
- **提供方**：`data_agent` | **底层调用**：`None` / `None`
- **输出格式**：`markdown` | **日期口径**：`None`
- **核心输出字段/指标**：全文 Markdown 结构化研究报告

#### `similar` - 相似基金
- **提供方**：`mcp` | **底层调用**：`wind-fund-analysis` / `fund_get_similar`
- **输出格式**：`table` | **日期口径**：`range`
- **核心输出字段/指标**：`代码`, `名称`, `组合配置`, `收益表现`, `总评分`, `成立日期`, `基金总规模`, `Wind三年评级`, `申赎状态`, `管理人`

#### `brinson` - Brinson归因
- **提供方**：`mcp` | **底层调用**：`wind-fund-analysis` / `fund_get_brinson_attribution`
- **输出格式**：`table` | **日期口径**：`semiAnnualReportDate`
- **核心输出字段/指标**：`序号`, `行业名称`, `行业配置(%)`, `收益率(%)`, `归因分析(%)`

#### `brinsonChart` - Brinson归因图
- **提供方**：`mcp` | **底层调用**：`wind-fund-analysis` / `fund_get_brinson_attribution`
- **输出格式**：`chart` | **日期口径**：`semiAnnualReportDate`
- **核心输出字段/指标**：全文 Markdown 结构化研究报告

#### `style` - 市值风格分析
- **提供方**：`mcp` | **底层调用**：`wind-fund-analysis` / `fund_get_market_cap_style`
- **输出格式**：`table` | **日期口径**：`range`
- **核心输出字段/指标**：`日期`, `大盘价值`, `大盘成长`, `小盘价值`, `小盘成长`, `债券指数`, `R²`

#### `nav` - 净值归因分析
- **提供方**：`mcp` | **底层调用**：`wind-fund-analysis` / `fund_get_nav_attribution`
- **输出格式**：`table` | **日期口径**：`range`
- **核心输出字段/指标**：`模型`, `因子数`, `基金R²`, `基准R²`, `α`, `市场因子`, `规模因子`, `价值因子`, `盈利因子`, `投资因子`, `动量因子`, `主动收益`, `区间收益`, `主动风险²`, `总风险²`

#### `position` - 仓位估算
- **提供方**：`mcp` | **底层调用**：`wind-fund-analysis` / `fund_get_position_estimation`
- **输出格式**：`table` | **日期口径**：`range`
- **核心输出字段/指标**：`估算日期`, `估算股票仓位`, `仓位变化`

#### `timing` - 选股择时能力分析
- **提供方**：`mcp` | **底层调用**：`wind-fund-analysis` / `fund_get_selection_timing_analysis`
- **输出格式**：`table` | **日期口径**：`monthEndWithYear`
- **核心输出字段/指标**：`基金代码`, `基金类型`, `主动管理能力`, `综合评分`, `同类排名`, `25%分位得分`, `50%分位得分`, `75%分位得分`, `同类平均主动管理能力`, `同类平均评分`, `与同类均分差`

---

## 二、UITree 交互组件库 (UI Tree Components)

UITree 组件库供 Agent 在输出分析研报的同时，挂载并在前端工作台（Workspace）以交互式卡片渲染。支持表格（Table）、合并表头（MergedHeader）、柱状图、折线图、散点图、雷达图、相关系数矩阵及嵌入式简报（Iframe）。

### 1. 基本资料 (4 个组件)
> **分类说明**：基金基础信息对比，包括代码、类型、成立日、经理、费率等

| 组件 ID | 组件名称 | 显示类型 | 卡片标题 | 底层 Command / URL | 入参规范 (Params) |
|:---|:---|:---|:---|:---|:---|
| `FundInfoForDefault` | **基金资料** | `table` | 基本资料 | `report name=Fund.FundComapre.FundBasicInfoCompa...` | `windCodes`, `endDate`, `amount:1` |
| `FundInfoForMoneyMarketFund` | **货币型基金资料** | `table` | 基本资料 | `report name=Fund.FundComapre.FundBasicInfoCompa...` | `windCodes`, `endDate`, `amount:1` |
| `FundInfoForIndexFund` | **指数型基金资料** | `table` | 基本资料 | `report name=Fund.FundComapre.FundBasicInfoCompa...` | `windCodes`, `endDate`, `amount:1` |
| `FundInfoForQDII` | **QDII基金资料** | `table` | 基本资料 | `report name=Fund.FundComapre.FundBasicInfoCompa...` | `windCodes`, `endDate`, `amount:1` |

### 3. 持有人结构 (1 个组件)
> **分类说明**：对比基金持有人中机构与个人投资者占比

| 组件 ID | 组件名称 | 显示类型 | 卡片标题 | 底层 Command / URL | 入参规范 (Params) |
|:---|:---|:---|:---|:---|:---|
| `HolderStructure` | **持有人结构** | `chart (stackedBar)` | 持有人结构 | `MFCP.Report17Picker` | `windCodes`, `reportDate`, `merge:0` |

### 4. 资产配置 (11 个组件)
> **分类说明**：覆盖大类资产配置、行业配置、重仓持股、持债、重仓基金等持仓相关对比

| 组件 ID | 组件名称 | 显示类型 | 卡片标题 | 底层 Command / URL | 入参规范 (Params) |
|:---|:---|:---|:---|:---|:---|
| `AssetAllocation` | **资产配置** | `chart (stackedBar)` | 资产配置图 | `MFCP.Report14Picker` | `windCodes`, `reportDate` |
| `AssetAllocationForFOF` | **FOF资产配置** | `chart (stackedBar)` | 资产配置图 | `MFCP.Report14Picker` | `windCodes`, `reportDate`, `penetrate:0` |
| `IndustryAllocationForDefault` | **行业配置** | `table` | 行业配置 | `F9.FundWorkBench.IndustryAllocationPicker.GetMu...` | `windCodes`, `reportDate`, `industryType:4` |
| `IndustryAllocationForQDII` | **QDII行业配置** | `table` | 行业配置 | `F9.FundWorkBench.IndustryAllocationPicker.GetMu...` | `windCodes`, `reportDate`, `industryType:13` |
| `TopHoldings` | **重仓股票** | `MergedHeader` | 重仓股票 | `MFCP.Report16Picker2.GetData` | `windCodes`, `reportDate` |
| `BondTypeAllocation` | **债券券种配置** | `table` | 债券券种配置 | `MFCP.Report18Picker` | `windCodes`, `reportDate` |
| `TopBonds` | **重仓债券** | `MergedHeader` | 重仓债券 | `MFCP.Report19Picker.GetData` | `windCodes`, `reportDate` |
| `TopFunds` | **重仓基金** | `MergedHeader` | 重仓基金 | `PublicFundF9.HoldingFundInfoPicker.GetMultipleRecs` | `windCodes`, `reportDate` |
| `ReitsAssetsDetails` | **资产明细** | `table` | 资产明细 | `report name=Fund.REITs.REITsAssetsDetails21 win...` | `windCodes` |
| `ReitsProjectOperation` | **项目运营情况** | `table` | 项目运营情况 | `report name=Fund.REITs.REITsProjectOperation21 ...` | `windCodes` |
| `ReitsFinancialIndicators` | **季度财务指标** | `table` | 季度财务指标 | `report name=Fund.REITs.REITsFinancialIndicators...` | `windCodes`, `reportDate` |

### 5. 收益与业绩 (15 个组件)
> **分类说明**：含业绩对比表、年化/年度回报、Alpha、业绩表现、净值走势、回报、风险收益、回撤、七日年化、历史收益等

| 组件 ID | 组件名称 | 显示类型 | 卡片标题 | 底层 Command / URL | 入参规范 (Params) |
|:---|:---|:---|:---|:---|:---|
| `NetValueTrendTableNew` | **业绩表现** | `table` | 业绩表现 | `Fund.FundPerformanceNetValueTablePicker.GetMult...` | `windCodes`, `endDate` |
| `NetValueTrendForMoneyMarketFund` | **七日年化走势** | `chart (line)` | 七日年化走势 | `FactSheet.Performance.FundReturnPicker.GetMulti...` | `windCodes`, `startDate`, `endDate`, `circle:0`, `rolling:0`, `benchCode:[]`, `indexs:[]` |
| `AnnualizedReturn` | **年化回报** | `chart (bar)` | 年化回报 | `MFCP.Report10ChartPicker.GetData` | `windCodes`, `endDate`, `indexCode` |
| `YearlyReturn` | **年度回报** | `chart (bar)` | 年度回报 | `MFCP.Report11Picker` | `windCodes`, `endDate`, `indexCode` |
| `YearlyReturnTableNew` | **业绩对比表(固定周期)** | `MergedHeader` | 业绩对比表(固定周期) | `MFCP.Report11Picker` | `windCodes`, `endDate`, `indexCode`, `mark:1` |
| `Alpha` | **Alpha对比** | `chart (bar)` | Alpha对比图 | `MFCP.Report12Picker` | `windCodes`, `endDate`, `indexCode` |
| `Performance` | **风险收益指标** | `table` | 风险收益指标 | `MFCP.Report3Picker.GetData` | `windCodes`, `startDate`, `endDate`, `indexCode` |
| `PerformanceForMoneyMarketFund` | **货币型基金风险收益指标** | `table` | 风险收益指标 | `MFCP.Report3Picker.GetData` | `windCodes`, `startDate`, `endDate`, `indexCode` |
| `PerformanceForIndexFund` | **指数型基金风险收益指标** | `table` | 风险收益指标 | `MFCP.Report3Picker.GetData` | `windCodes`, `startDate`, `endDate`, `indexCode` |
| `NetValueTrend` | **净值走势** | `chart (line)` | 净值走势 | `MFCP.Report9ChartPicker.GetData` | `windCodes`, `startDate`, `endDate`, `indexCode` |
| `Return` | **回报** | `chart (bar)` | 回报 | `MFCP.Report3Picker.GetData` | `windCodes`, `startDate`, `endDate`, `indexCode`, `mark:1` |
| `RiskReturn` | **风险收益** | `chart (scatter)` | 风险收益 | `MFCP.Report3Picker.GetData` | `windCodes`, `startDate`, `endDate`, `indexCode`, `mark:1` |
| `Drawdown` | **回撤对比** | `chart (line)` | 回撤 | `MFCP.Report13Picker.GetData` | `windCodes`, `startDate`, `endDate`, `indexCode`, `circle:0` |
| `DrawdownTable` | **回撤对比表** | `table` | 回撤对比表 | `FundCompare.MaxDrawdownPicker.GetMultipleRecs` | `windCodes`, `endDate` |
| `MarketPerformance` | **市场绩效** | `table` | 市场绩效 | `FundCompare.MarketPerformancePicker.GetMultiple...` | `windCodes`, `startDate`, `endDate` |

### 7. 相关性 (1 个组件)
> **分类说明**：对比基金之间的收益相关性

| 组件 ID | 组件名称 | 显示类型 | 卡片标题 | 底层 Command / URL | 入参规范 (Params) |
|:---|:---|:---|:---|:---|:---|
| `CorrelationMatrix` | **相关系数矩阵** | `matrix` | 今年以来相关系数矩阵 | `MFCP.Report8NewPicker` | `windCodes`, `startDate`, `endDate`, `indexCode` |

### 8. 基金经理 (1 个组件)
> **分类说明**：对比基金经理基本资料，包括基金公司、擅长领域、在管只数/规模、投资经理年限等

| 组件 ID | 组件名称 | 显示类型 | 卡片标题 | 底层 Command / URL | 入参规范 (Params) |
|:---|:---|:---|:---|:---|:---|
| `FundManagerBasic` | **基金经理基本资料** | `table` | 基金经理基本资料 | `FundCompare.FundManagerPicker.GetMultipleRecs` | `windCodes`, `endDate` |

### 9. 基金诊断 (3 个组件)
> **分类说明**：对比基金诊断得分，包括收益能力、抗风险能力、选股择时能力、基金公司、基金经理等维度

| 组件 ID | 组件名称 | 显示类型 | 卡片标题 | 底层 Command / URL | 入参规范 (Params) |
|:---|:---|:---|:---|:---|:---|
| `AbilityChart` | **基金诊断得分对比雷达图** | `chart (radar)` | 基金诊断 | `report name=Fund.FundComapre.FundScoreCompare21...` | `windCodes`, `endDate`, `year:3` |
| `AbilityTable` | **基金诊断得分对比表格** | `table` | 基金诊断得分对比表格 | `report name=Fund.FundComapre.FundScoreCompare21...` | `windCodes`, `endDate`, `year:3` |
| `IndexTrackingDiagnosis` | **跟踪指数诊断** | `table` | 跟踪指数诊断 | `Matrix2 functions=f_info_investtype(windcode);f...` | `windCodes`, `startDate`, `endDate` |

### 13. 基金简报 (26 个组件)
> **分类说明**：展示基金简报

| 组件 ID | 组件名称 | 显示类型 | 卡片标题 | 底层 Command / URL | 入参规范 (Params) |
|:---|:---|:---|:---|:---|:---|
| `MoneyFactSheetIframe` | **基金简报** | `iframe` | 基金简报 | `https://exp.wind.com.cn/FundStaticWeb/PublicFun...` | - |
| `MoneySevenDayYieldChart` | **七日年化** | `chart (line)` | 七日年化 | `FactSheet.Performance.FundReturnPicker.GetMulti...` | `windCodes`, `startDate`, `endDate`, `circle:0`, `rolling:0`, `benchCode:[]`, `indexs:[]`, `type:0`, `showMedian:1` |
| `MoneyMMFChart` | **万份收益** | `chart (line)` | 万份收益 | `FactSheet.Performance.FundReturnPicker.GetMulti...` | `windCodes`, `startDate`, `endDate`, `circle:0`, `rolling:0`, `benchCode:[]`, `indexs:[]`, `type:1`, `showMedian:1` |
| `MoneyPerformanceChart` | **业绩表现** | `chart (line)` | 业绩表现 | `FundCompare.FundPerformanceChartPicker.GetData` | `windCodes`, `startDate`, `endDate`, `hasPreTransitionFund:0`, `frequency:4`, `indexCode` |
| `MoneyPerformanceTable` | **业绩表现表** | `table` | 业绩表现表 | `report name=FactSheet2.Fund.FundPerformanceNetV...` | `windCodes`, `endDate`, `indexCode` |
| `MoneyYearlyReturnChart` | **年度回报** | `chart (bar)` | 年度回报 | `FundCompare.FundAnnualReturnPicker.GetData` | `windCodes`, `endDate`, `indexCode`, `hasPreTransitionFund:0` |
| `MoneyYearlyReturnTable` | **年度回报表** | `table` | 年度回报表 | `FundCompare.FundAnnualReturnPicker.GetData` | `windCodes`, `endDate`, `indexCode`, `hasPreTransitionFund:0` |
| `MoneyHistoryScaleChange` | **历年规模变化** | `chart (bar)` | 历年规模变化 | `FundCompare.FundHistoryChangePicker.GetData` | `windCodes`, `startDate`, `endDate`, `type:1` |
| `MoneyHistoryATMChart` | **历年平均剩余期限** | `chart (mixed)` | 历年平均剩余期限 | `report name=Fund.AssetPortfolio.PortfolioResidu...` | `windCodes`, `startDate`, `endDate` |
| `MoneyHistoryRMDChart` | **历年剩余期限分布** | `chart (stackedBar)` | 历年剩余期限分布 | `FundCompare.HistoryRMDPicker` | `windCodes`, `startDate`, `endDate` |
| `MoneyHistoryLRChart` | **历年杠杆率** | `chart (mixed)` | 历年杠杆率 | `report name=FactSheet2.Fund.MonetaryFundLeverag...` | `windCodes`, `endDate` |
| `MoneyHistoryTEChart` | **历年偏离度变化** | `chart (mixed)` | 历年偏离度变化 | `report name=Fund.AssetPortfolio.NetAssetDepartu...` | `windCodes`, `startDate`, `endDate` |
| `MoneyAssetAllocationChart` | **资产配置** | `chart (stackedBar)` | 资产配置 | `report name=FactSheet2.Nav.FundAssetsOfPastYear...` | `windCodes`, `reportDate` |
| `MoneyRMDChart` | **剩余期限分布** | `chart (bar)` | 剩余期限分布 | `report name=FactSheet2.Fund.FundHoldingDateDist...` | `windCodes`, `reportDate` |
| `MoneyBondAllocationChart` | **债券券种配置** | `chart (stackedBar)` | 债券券种配置 | `report name=F9_2.Fund.BondInvePortfolio.Portfol...` | `windCodes`, `startDate`, `endDate` |
| `MoneyHeavyHoldStockTable` | **重仓持股** | `table` | 重仓持股 | `PublicFundF9.HeavHeldStockStock` | `windCodes`, `reportDate`, `industryType:7` |
| `MoneyHeavyHoldFundTable` | **重仓持基** | `table` | 重仓持基 | `PublicFundF9.HoldingFundInfoPicker` | `windCodes`, `reportDate`, `top:10` |
| `MoneyHeavyHoldBondTable` | **重仓持债** | `table` | 重仓持债 | `report name=F9_2.Fund.BondInvePortfolio.HeavHel...` | `windCodes`, `reportDate` |
| `MoneyMarketOutlook` | **市场展望** | `table` | 市场展望 | `FundCompare.MarketOutlookPicker.GetMultipleRecs` | `windCodes`, `endDate` |
| `MoneyCurrentManagerTable` | **现任基金经理** | `table` | 现任基金经理 | `FundCompare.FundManagerPicker.GetMultipleRecs2` | `windCodes`, `endDate` |
| `MoneyCompanyTable` | **基金公司** | `table` | 基金公司 | `FundCompare.FundCompanyPicker.GetMultipleRecs` | `windCodes`, `endDate` |
| `MoneyCompanyProductStructureTable` | **基金公司产品结构** | `MergedHeader` | 基金公司产品结构 | `FundCompare.FundCompanyPicker.GetProductStructu...` | `windCodes`, `endDate` |
| `MoneyCompanyFundManagerTable` | **基金公司基金经理** | `MergedHeader` | 基金公司基金经理 | `FundCompare.FundCompanyPicker.GetFundManagerRecs` | `windCodes`, `endDate` |
| `MoneyBasicInfo` | **基本信息** | `table` | 基本信息 | `Report name=FactSheet2.Fund.FundBasicInformatio...` | `windCodes`, `endDate` |
| `MoneyInvestObject` | **投资目标** | `table` | 投资目标 | `Matrix2 functions=f_info_investobject(windcode)...` | `windCodes` |
| `MoneyPurchaseInfo` | **购买信息** | `table` | 购买信息 | `Report name=FactSheet2.Fund.FundBasicInformatio...` | `windCodes`, `endDate` |
