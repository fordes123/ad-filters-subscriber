# AD Filter Subscriber

[![Verify](https://github.com/fordes123/ad-filters-subscriber/actions/workflows/verify.yml/badge.svg)](https://github.com/fordes123/ad-filters-subscriber/actions/workflows/verify.yml)
[![Version](https://img.shields.io/github/v/release/fordes123/ad-filters-subscriber?sort=semver)](https://github.com/fordes123/ad-filters-subscriber/releases)
[![License](https://img.shields.io/github/license/fordes123/ad-filters-subscriber)](./LICENSE)

本项目用于聚合不同来源、不同格式的广告过滤规则，并按目标格式完成筛选、去重与转换。

- [x] adblock (adguard、ubo、abp)
- [x] dns
- [x] dnsmasq
- [x] mihomo (classical、domain、ipcidr)
- [x] smartdns
- [x] sing-box
- [x] hosts

> [!WARNING]
> - 并非所有类型规则之间都可以进行转换，且无法精确转换的规则，默认不允许扩大或缩小匹配范围。
> - 规则有效性检测基于域名解析，仅适用于可提取确定域名的规则。

## 快速开始

### CLI

从 [Releases](https://github.com/fordes123/ad-filters-subscriber/releases) 获取二进制程序：

```bash
# 使用默认配置构建
adfs

# 使用指定配置构建
adfs -c path/to/application.yml
```

未指定 `-c/--config` 时，依次查找程序目录的 `application.yml`、`config/application.yml`，使用第一个存在的文件。

### GitHub Actions

1. Fork 仓库并修改 `config/application.yml`。
2. 在 **Actions** 页面启用工作流。
3. 运行 **Update Filters**，或等待每 8 小时自动执行。

产物默认提交至 `release` 分支。手动运行时可通过 `release-branch` 指定目标分支。

```text
https://raw.githubusercontent.com/<owner>/ad-filters-subscriber/release/<file>
```

## 示例配置

```yaml
adfs:
  # 输入规则源；可配置多个
  input:
    - name: upstream                        # 唯一的规则源名称
      path: https://example.org/filter.txt  # HTTP、HTTPS 或本地文件路径
      type: adblock                         # 规则格式 adblock、dns、hosts、dnsmasq、smartdns、mihomo、sing-box
      dialect: ubo                          # Adblock 方言：core（默认）、abp、adguard、ubo

  output:
    - name: easylist.txt                    # 输出文件名
      type: adblock                         # 输出格式 adblock、dns、hosts、dnsmasq、smartdns、mihomo、sing-box
      dialect: ubo                          # Adblock 方言：core（默认）、abp、adguard、ubo

    - name: hosts
      type: hosts

    - name: clash.yaml
      type: mihomo
      dialect: domain                       # Mihomo 方言：classical（默认）、domain、ipcidr

    - name: sing-box.json
      type: sing-box

  config:
    output-dir: rule                        # 输出目录，默认为 rule
```

完整配置见
[`config/application.yml`](./config/application-example.yaml)。

## License

[MIT](./LICENSE)
