# 途牛开放平台 MCP 服务事实

Research 笔记（纯事实 + 来源，不含架构结论）。

对应 ticket：[Research supplier-neutral travel integration contracts](https://github.com/darklightn/roam/issues/5)（Wayfinder 地图 [Intelligent business travel multi-Agent MVP architecture](https://github.com/darklightn/roam/issues/1)）。

## 来源（一手，抓取/核实于 2026-11-03）

- GitHub `tuniucorp/tuniu-cli`（README）：https://github.com/tuniucorp/tuniu-cli
- GitHub `tuniucorp/aigc-agents`（README）：https://github.com/tuniucorp/aigc-agents
- 途牛开放平台 MCP 文档页：
  - 机票 https://open.tuniu.com/mcp/docs/apidoc/mcp/flightMCP.html
  - 酒店 https://open.tuniu.com/mcp/docs/apidoc/mcp/hotelMCP.html
  - 火车票 https://open.tuniu.com/mcp/docs/apidoc/mcp/trainMCP.html

## 1. 接入方式

- **CLI**：`tuniu-cli`（npm 包，TypeScript）。命令：`list`（看服务/工具）、`call <server> <tool> -a '<JSON>'`（调用）、`schema --output json`（导出工具能力 Schema 给 Agent）、`health`、`discovery`（服务动态发现）、`config`、`skill install`。
- **协议**：MCP（底层 JSON-RPC 2.0）。每个业务域是一个 MCP server，端点形如 `https://openapi.tuniu.cn/mcp/<server>`。
- **认证**：单一 API Key，环境变量 `TUNIU_API_KEY`；在 `https://open.tuniu.com/mcp/login` 注册后申请。
- **服务（server）清单**：`ticket`（门票）、`hotel`（酒店）、`flight`（机票）、`train`（火车票）、`cruise`（邮轮）、`holiday`（度假）、国际机票等。
- **Skill 分发**：CLI 内置 `SKILL.md`，`tuniu skill install` 可装到 Agent 目录（默认 `~/.agents/skills/tuniu-cli/`），文档自称"教你如何调用"。
- **官方声明特性**：无状态设计（无需维护会话）、API Key 认证、智能缓存、完整预订流程。
- **配套参考实现**：`tuniucorp/aigc-agents`（"AI 助手小牛"，Java 17 + Spring Boot + Spring AI 的旅行 Agent 框架，模块含 flight/hotel/train 的 Agent 实现）。

## 2. 机票 `flight`（5 个工具）

| 工具 | 作用 | 关键入参 | 关键出参 |
| --- | --- | --- | --- |
| `searchLowestPriceFlight` | 航班搜索（6 种模式：默认低价 / TIME / PRICE / NEAR_GO / NEAR_BACK / TRANSFER），分页 | `departureCityName`、`arrivalCityName`、`departureDate`（必填）、`searchType`、`pageNum` | `data[]`（flightNumber、airlineCompany、basePrice、totalTax、cabinClass、remainingSeats…）、`queryId` |
| `multiCabinDetails` | 舱位价格详情 | `departureCityName`、`arrivalCityName`、`departureDate`、`flightNo` | `cabinInfo[]`（`cabinPriceId`⚠️下单必需、`refundChangeRule` 退改签规则、`baggageInfo`…） |
| `getBookingRequiredInfo` | 预订必填字段说明 | 空 `{}` | 纯文本（乘客/联系人/证件要求） |
| `saveOrder` | 创建订单 | `cabinPriceId`、`tourists[]`、`contactTourist` | `orderId`、`payUrl` |
| `cancelOrder` | 取消订单 | `orderId` | `successCode`、`msg`、`errorCode` |

- 流程：搜索 → 舱位（拿 `cabinPriceId`）→ 预订说明 → 下单（拿 `orderId` + `payUrl`）→ 用户点 `payUrl` 支付。
- **`cancelOrder` 只取消"已下单未支付"的订单**。文档中无"改签"、无"已支付订单退票"执行工具。

## 3. 酒店 `hotel`（3 个工具）

| 工具 | 作用 | 关键入参 | 关键出参 |
| --- | --- | --- | --- |
| `tuniuHotelSearch` | 酒店搜索，分页 | `cityName`（首查必填）、`checkIn`/`checkOut`、`keyword`、`poiName`、`prices`、`queryId`+`pageNum`（翻页） | `hotels[]`（hotelId、hotelName、starName、lowestPrice、`refund` 退改政策、commentScore…）、`queryId` |
| `tuniuHotelDetail` | 酒店详情 + 房型报价 | `hotelId`/`hotelName` 二选一、`checkIn`/`checkOut`、`roomNum`、`adultNum` | `roomTypes[].ratePlans[]`（`preBookParam`⚠️下单必需且 30 分钟有效、`vendorRatePlanId`、`rmbPrices`、`cancelDesc`、`count` 库存） |
| `tuniuHotelCreateOrder` | 创建订单 | `hotelId`、`roomId`、`preBookParam`、`checkInDate`/`checkOutDate`、`roomCount`、`roomGuests[]`、`contactName`/`contactPhone` | `orderId`、`confirmationNumber`、`paymentUrl` |

- 流程：搜索 → 详情（拿 `preBookParam`）→ 下单（拿 `orderId` + `confirmationNumber` + `paymentUrl`）。
- **酒店无"取消订单"工具**。
- 工具名在文档页是 `tuniuHotelSearch` / `tuniuHotelDetail` / `tuniuHotelCreateOrder`（驼峰）；但 `tuniu-cli` README 速查表里写的是 `tuniu_hotel_search` / `tuniu_hotel_detail` / `tuniu_hotel_create_order`（蛇形）。两处不一致。

## 4. 火车票 `train`（5 个工具）

| 工具 | 作用 | 关键入参 | 关键出参 |
| --- | --- | --- | --- |
| `searchLowestPriceTrain` | 查车次，分页 | `departureCityName`、`arrivalCityName`、`departureDate`（必填）、`searchType`（排序）、`queryId`+`pageNum`（翻页） | `data[]`（trainNum、departStationName、destStationName、price、seatAvailable）、`queryId` |
| `queryTrainDetail` | 车次详情 + 座等价格 | `departureStationName`、`arrivalStationName`、`departureDate`、`trainNum` | `seatInfo[]`（`resId`、`price`、`departsDate`⚠️下单必需）、`trainInfo` |
| `bookTrain` | 预订下单 | `resources[]`（resourceId/adultPrice/departsDate）、`adultTourists[]`、`contact`、`acceptStandingTicket` | `orderId`、`orderDetailUrl` |
| `queryTrainOrderDetail` | 订单详情 | `orderId` | 订单状态（`statusName`）、支付状态（`payStatus`/`payStatusName`）、每张票状态（`detailInfos[].status`，含退票/改签状态码）、乘客/车票明细 |
| `cancelOrder` | 取消订单 | `orderId` | `successCode`、`errorMessage` |

- 流程：查车次 → 车次详情（拿 `resId`/`price`/`departsDate`）→ 下单（拿 `orderId` + `orderDetailUrl`）。
- **`cancelOrder` 只取消"已下单未支付"的订单**。无"改签/退票"执行工具（订单详情里能看到改签/退票状态，但那是查询结果）。
- 火车票是三个服务里**唯一有订单详情查询**的。

## 5. 支付事实

- 三个服务下单后都返回一个**支付/订单链接**（机票 `payUrl`、酒店 `paymentUrl`、火车票 `orderDetailUrl`），文档明确要求"提醒用户点击链接完成支付"。支付由用户在途牛侧完成，平台侧 MCP 不代付。
- **产品边界（已确认，2026-11-03）**：MVP 返回支付链接给用户、**不帮用户自动支付**。

## 6. 能力缺口/特点（客观事实，非结论）

1. 取消能力窄：机票/火车票的取消仅限"未支付订单"；酒店无取消工具。
2. 无改签/退票执行工具（三个服务均无）。
3. 对账能力不均衡：仅火车票有订单详情查询；机票、酒店无。
4. 供应商确认号仅酒店返回（`confirmationNumber`）；机票、火车票只有途牛 `orderId`。
5. 下单必需参数有时效：酒店 `preBookParam` 30 分钟有效；机票 `cabinPriceId`、火车票 `resId` 需先查详情获得。
6. 工具命名不统一（flight/train 驼峰、hotel `tuniuHotel*`；README 与文档页对酒店工具名写得不一致）。
