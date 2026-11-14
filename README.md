# 安德鲁牙科连锁管理系统

系统分析与设计课程项目。实现使用「和安口腔」作为演示品牌。

## 课程验收入口

### 网页管理后台

[打开和安口腔管理后台](https://dental.know11know.top/)

| 角色 | 账号 | 密码 | 登录后可查看或办理 |
| --- | --- | --- | --- |
| 总部管理员 | `dental_admin` | `ELFfud8UnEL8kXqyVWPOU0LRzwM` | 全门店工作台、账号管理 |
| 店长 | `fixture_manager` | `Hn!v_TJh36Xgwl5_iG_fKyn` | 天河店排班、调班停诊和提醒 |
| 前台 | `fixture_front` | `Hn!5mRdkbijX4_at4yeYKwf` | 天河店预约、到诊和结算 |
| 牙医 | `dental_dentist` | `GdC3uks_kJGa80X9mqhCfrwU5_c` | 本人接诊和复诊 |
| 财务 | `fixture_finance` | `Hn!GjlkePBFnhSQp9unS4e2` | 天河店费用和经营报表 |

这些账号供课程验收使用，办理流程时请使用虚构资料。[角色权限说明](STAFF-ROLES.md) · [门店目录接口示例](https://api.know11know.top/api/v1/catalog/clinics)

### 微信小程序「牙牙课程演示」

请用微信扫描下方体验码申请体验。

**体验码更新于 2026 年 11 月 14 日，2026 年 11 月 21 日前有效（北京时间）。**

![牙牙课程演示体验版二维码](demo/miniprogram-trial.png)

若微信提示体验码过期，请刷新本页查看换发的二维码，或联系项目作者。

等待开通体验权限时，可以先查看[小程序首页](demo/miniprogram-home.png)、[门店选择](demo/miniprogram-clinics.png)和[预约确认](demo/miniprogram-booking.png)截图。

## 项目文件


- [系统分析报告（PDF）](系统分析报告.pdf)
- [网页管理后台](admin-web) · Vue 3 / Vite
- [服务端](backend) · Spring Boot / MySQL
- [微信小程序](miniprogram)
- [数据库结构与演示数据](database)
- [部署配置](deploy)

2026-11-13 版本包含预约、排班、接诊、结算、复诊、经营统计和站内提醒。
