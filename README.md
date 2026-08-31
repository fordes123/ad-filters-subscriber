# AD Filter Subscriber

[![Verify](https://github.com/fordes123/ad-filters-subscriber/actions/workflows/verify.yml/badge.svg)](https://github.com/fordes123/ad-filters-subscriber/actions/workflows/verify.yml)
[![Version](https://img.shields.io/github/v/release/fordes123/ad-filters-subscriber?sort=semver)](https://github.com/fordes123/ad-filters-subscriber/releases)
[![License](https://img.shields.io/github/license/fordes123/ad-filters-subscriber)](./LICENSE)

本项目用于聚合不同来源、不同格式的广告过滤规则，并按目标格式完成筛选、去重与转换。

- [x] easylist
- [x] dns
- [x] dnsmasq
- [x] clash (mihomo)
- [x] smartdns
- [x] sing-box
- [x] hosts

> [!WARNING]
> - 并非所有类型规则之间都可以进行转换，且无法精确转换的规则，默认允许缩小匹配范围。
> - 规则有效性检测基于域名解析，仅适用于可提取确定域名的规则。

## 快速开始

### CLI

从 [Releases](https://github.com/fordes123/ad-filters-subscriber/releases) 获取二进制程序：

```bash
# 使用默认配置构建
adfs build

# 使用指定配置构建
adfs build --config path/to/application.yaml

# 检查本地规则文件
adfs check --dialect=ABP rules.txt

# 检查单条规则
adfs inspect --dialect=UBO '@@||example.com^$script,important'
```

### GitHub Actions

1. Fork 仓库并修改 `config/application.yaml`。
2. 在 **Actions** 页面启用工作流。
3. 运行 **Update Filters**，或等待每 8 小时自动执行。

产物默认提交至 `release` 分支。手动运行时可通过 `release-branch` 指定目标分支。

```text
https://raw.githubusercontent.com/<owner>/ad-filters-subscriber/release/<file>
```

## 示例配置

```yaml
application:
  # 输入规则源；可配置多个
  input:
    - name: upstream                        # 唯一的规则源名称
      path: https://example.org/filter.txt  # HTTP、HTTPS 或本地文件路径
      type: easylist                        # 规则格式 easylist、dns、hosts、dnsmasq、smartdns、clash、sing-box
      dialect: ubo                          # EasyList/DNS 方言：abp、adguard、ubo

  output:
    path: rule                              # 输出目录，默认为 rule
    files:
      - name: easylist.txt                  # 输出文件名
        type: easylist                      # 输出格式 easylist、dns、hosts、dnsmasq、smartdns、clash、sing-box
        dialect: ubo                        # EasyList/DNS 方言：abp、adguard、ubo

      - name: hosts
        type: hosts

      - name: clash.yaml
        type: clash
        dialect: domain                     # Clash 方言：classical（默认）、domain、ipcidr

      - name: sing-box.json
        type: sing-box
```

完整字段、默认值和说明见
[`config/application-example.yaml`](./config/application-example.yaml)。

> [!TIP]
> - `sing-box` 产物是严格 JSON，因此不会写入 `file_header` 注释。

## License

[MIT](./LICENSE)
